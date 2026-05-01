/*
 * LocalDriveRepository.swift
 *
 * Real, GRDB-backed `DriveRepository` implementation. Replaces
 * `FakeDriveRepository` as the production data source: every
 * method talks to the SQLite tables defined by `ReleafDatabase`'s
 * migrations and round-trips through the persistence ↔ domain
 * mappers in `LocalDriveRepositoryMappers.swift`.
 *
 * Why this lives in the data layer (not feature):
 * - All the underlying repos (`NotebookRepository`,
 *   `ChapterRepository`, `PageRepository`, `ShelfRepository`)
 *   already speak GRDB and own their own observation streams.
 *   This class is the *Drive-shaped* facade over them — same
 *   API surface as the protocol, different storage.
 * - ViewModels keep depending on `DriveRepository` (the protocol);
 *   nothing in the feature layer changes when the implementation
 *   swaps under them. The only diff is the default value at
 *   each ViewModel's construction site.
 *
 * Caveats:
 * - Counts on the `Notebook` domain shape (`chapterCount`,
 *   `pageCount`) are populated lazily on `loadNotebook(id:)` and
 *   `listNotebooks()`. Single-row reads (e.g. after a rename)
 *   default both to 0; the calling screen's `.task { load() }`
 *   re-fetches and gets fresh counts. Acceptable for now —
 *   bigger payloads will want a join-based reader later.
 * - The Drive sync layer is not wired in this class. Writes set
 *   `dirty = true` on each row so the existing sync worker
 *   (`SyncScheduler` / `URLSessionDriveClient`) can pick them up
 *   and push to the user's Drive when sync is configured.
 *   When sync isn't configured, rows persist locally and stay
 *   dirty forever — same behaviour as Notepad entries today.
 */

import Foundation
import GRDB

public final class LocalDriveRepository: DriveRepository, @unchecked Sendable {

    /// Process-wide singleton. ViewModels default to this so every
    /// surface in the app reads + writes through the same GRDB
    /// instance. Tests can construct their own with a custom
    /// database.
    public static let shared = LocalDriveRepository()

    private let dbQueue: DatabaseQueue

    public init(database: ReleafDatabase = .shared) {
        self.dbQueue = database.dbQueue
    }

    // MARK: - Reads

    public func listNotebooks() async throws -> [Notebook] {
        try await dbQueue.read { db -> [Notebook] in
            try Self.fetchNotebooksWithCounts(db: db, includeArchived: false)
        }
    }

    public func loadNotebook(id: String) async throws -> NotebookDetail {
        try await dbQueue.read { db -> NotebookDetail in
            guard let nbEntity = try NotebookEntity
                .filter(Column("id") == id)
                .filter(sql: "deleted_at IS NULL")
                .fetchOne(db) else {
                throw DriveError.notFound
            }
            // Counts join — fast SQL aggregations on the two
            // child tables. Single round-trip per call.
            let chapterCount = try Self.scalarCount(
                db: db,
                sql: "SELECT COUNT(*) FROM chapters WHERE notebook_id = ? AND deleted_at IS NULL",
                arg: id
            )
            let pageCount = try Self.scalarCount(
                db: db,
                sql: """
                    SELECT COUNT(*)
                    FROM pages p
                    JOIN chapters c ON c.id = p.chapter_id
                    WHERE c.notebook_id = ? AND p.deleted_at IS NULL AND c.deleted_at IS NULL
                    """,
                arg: id
            )
            let shelfName = try Self.shelfName(db: db, shelfId: nbEntity.shelfId)
            let notebook = nbEntity.toDomainNotebook(
                chapterCount: chapterCount,
                pageCount: pageCount,
                shelfName: shelfName
            )
            // Active chapters in position order, then archived
            // chapters last (they're shown in the archive view —
            // the live notebook detail filters them out at the
            // ViewModel layer if it wants to).
            let chapterEntities = try ChapterEntity
                .filter(sql: "notebook_id = ? AND deleted_at IS NULL AND archived_at IS NULL", arguments: [id])
                .order(Column("position").asc)
                .fetchAll(db)
            let chapters = try chapterEntities.map { chEntity -> Chapter in
                let pageRows = try PageEntity
                    .filter(sql: "chapter_id = ? AND deleted_at IS NULL AND archived_at IS NULL", arguments: [chEntity.id])
                    .order(Column("position").asc, Column("updated_at").desc)
                    .fetchAll(db)
                let summaries = pageRows.map { $0.toPageSummary() }
                return chEntity.toDomainChapter(pages: summaries)
            }
            return NotebookDetail(notebook: notebook, chapters: chapters)
        }
    }

    public func loadChapters(notebookId: String) async throws -> [Chapter] {
        try await dbQueue.read { db -> [Chapter] in
            let chapterEntities = try ChapterEntity
                .filter(sql: "notebook_id = ? AND deleted_at IS NULL AND archived_at IS NULL", arguments: [notebookId])
                .order(Column("position").asc)
                .fetchAll(db)
            return try chapterEntities.map { chEntity in
                let pageRows = try PageEntity
                    .filter(sql: "chapter_id = ? AND deleted_at IS NULL AND archived_at IS NULL", arguments: [chEntity.id])
                    .order(Column("position").asc, Column("updated_at").desc)
                    .fetchAll(db)
                return chEntity.toDomainChapter(pages: pageRows.map { $0.toPageSummary() })
            }
        }
    }

    public func loadPage(id: String) async throws -> Page {
        try await dbQueue.read { db -> Page in
            guard let pageEntity = try PageEntity
                .filter(Column("id") == id)
                .filter(sql: "deleted_at IS NULL")
                .fetchOne(db) else {
                throw DriveError.notFound
            }
            // Find the parent notebook id via the chapter row.
            // Pages don't store notebook_id directly — chapter is
            // the FK chain.
            guard let chapterEntity = try ChapterEntity
                .filter(Column("id") == pageEntity.chapterId)
                .fetchOne(db) else {
                throw DriveError.notFound
            }
            return pageEntity.toDomainPage(notebookId: chapterEntity.notebookId)
        }
    }

    public func listArchivedPages() async throws -> [ArchivedPage] {
        try await dbQueue.read { db -> [ArchivedPage] in
            // Cross-table join: archived pages → their chapter +
            // notebook for breadcrumb context. Filter out true
            // tombstones (`deleted_at`) — we only surface user-
            // archived rows, not soft-deleted ones.
            let rows = try Row.fetchAll(db, sql: """
                SELECT
                    p.id              AS pid,
                    COALESCE(p.title, '') AS ptitle,
                    p.archived_at     AS parchived,
                    c.id              AS cid,
                    c.title           AS ctitle,
                    n.id              AS nid,
                    n.title           AS ntitle
                FROM pages p
                JOIN chapters c  ON c.id = p.chapter_id
                JOIN notebooks n ON n.id = c.notebook_id
                WHERE p.archived_at IS NOT NULL
                  AND p.deleted_at IS NULL
                  AND c.deleted_at IS NULL
                  AND n.deleted_at IS NULL
                ORDER BY p.archived_at DESC
                """)
            return rows.compactMap { row -> ArchivedPage? in
                let pid: String     = row["pid"]
                let ptitle: String  = row["ptitle"]
                let parchived: String? = row["parchived"]
                let cid: String     = row["cid"]
                let ctitle: String  = row["ctitle"]
                let nid: String     = row["nid"]
                let ntitle: String  = row["ntitle"]
                guard let archivedAt = parseISODate(parchived) else { return nil }
                return ArchivedPage(
                    id: pid,
                    title: ptitle,
                    notebookId: nid,
                    notebookTitle: ntitle,
                    chapterId: cid,
                    chapterTitle: ctitle,
                    archivedAt: archivedAt
                )
            }
        }
    }

    public func listPageTemplates() async throws -> [PageTemplate] {
        // Templates aren't persisted — they're a curated catalog
        // shipped with the app, same source as the FakeDriveRepository
        // used during the in-memory days. Living in code means a
        // template tweak doesn't need a migration.
        return FakeDriveRepository.seededTemplates
    }

    // MARK: - Page mutations

    @discardableResult
    public func archivePage(id: String) async throws -> Page {
        try await dbQueue.write { db in
            try Self.touchPageArchive(db: db, id: id, archivedAt: Date())
        }
    }

    @discardableResult
    public func restorePage(id: String) async throws -> Page {
        try await dbQueue.write { db in
            try Self.touchPageArchive(db: db, id: id, archivedAt: nil)
        }
    }

    @discardableResult
    public func setPageTags(pageId: String, tags: [String]) async throws -> Page {
        try await dbQueue.write { db in
            guard var entity = try PageEntity
                .filter(Column("id") == pageId)
                .filter(sql: "deleted_at IS NULL")
                .fetchOne(db) else {
                throw DriveError.notFound
            }
            entity.tags = encodeTagsJson(tags)
            entity.updatedAt = formatISODate(Date())
            entity.dirty = true
            try entity.update(db)
            let parentNotebookId = try Self.notebookId(forChapter: entity.chapterId, db: db)
            return entity.toDomainPage(notebookId: parentNotebookId)
        }
    }

    public func movePage(pageId: String, toNotebookId: String, toChapterId: String?) async throws {
        try await dbQueue.write { db in
            guard var entity = try PageEntity
                .filter(Column("id") == pageId)
                .filter(sql: "deleted_at IS NULL")
                .fetchOne(db) else {
                throw DriveError.notFound
            }
            // Validate destination notebook exists + isn't archived.
            guard try NotebookEntity
                .filter(Column("id") == toNotebookId)
                .filter(sql: "deleted_at IS NULL")
                .fetchCount(db) > 0 else {
                throw DriveError.notFound
            }
            // Resolve destination chapter — explicit if given,
            // otherwise the destination notebook's first active
            // chapter. Throws when neither is reachable.
            let resolvedChapterId: String
            if let toChapterId {
                guard try ChapterEntity
                    .filter(Column("id") == toChapterId)
                    .filter(sql: "notebook_id = ? AND deleted_at IS NULL", arguments: [toNotebookId])
                    .fetchCount(db) > 0 else {
                    throw DriveError.notFound
                }
                resolvedChapterId = toChapterId
            } else {
                guard let firstChapter = try ChapterEntity
                    .filter(sql: "notebook_id = ? AND deleted_at IS NULL AND archived_at IS NULL", arguments: [toNotebookId])
                    .order(Column("position").asc)
                    .fetchOne(db) else {
                    throw DriveError.notFound
                }
                resolvedChapterId = firstChapter.id
            }
            entity.chapterId = resolvedChapterId
            entity.updatedAt = formatISODate(Date())
            entity.dirty = true
            try entity.update(db)
        }
    }

    @discardableResult
    public func duplicatePage(id: String) async throws -> Page {
        try await dbQueue.write { db in
            guard let source = try PageEntity
                .filter(Column("id") == id)
                .filter(sql: "deleted_at IS NULL")
                .fetchOne(db) else {
                throw DriveError.notFound
            }
            let newId = "pg-\(UUID().uuidString.prefix(8).lowercased())"
            let now = formatISODate(Date())
            let copy = PageEntity(
                id: newId,
                chapterId: source.chapterId,
                projectId: source.projectId,
                templateId: source.templateId,
                title: (source.title ?? "Untitled page") + " (copy)",
                notes: source.notes,
                contacts: source.contacts,
                locations: source.locations,
                todos: source.todos,
                attachments: source.attachments,
                tags: source.tags,
                position: source.position + 1,
                conflictStub: nil,
                driveFileId: nil,
                createdAt: now,
                updatedAt: now,
                dirty: true,
                deletedAt: nil,
                archivedAt: nil
            )
            try copy.insert(db)
            let parentNotebookId = try Self.notebookId(forChapter: source.chapterId, db: db)
            return copy.toDomainPage(notebookId: parentNotebookId)
        }
    }

    @discardableResult
    public func applyTemplate(toPageId pageId: String, templateId: String) async throws -> Page {
        try await dbQueue.write { db in
            guard var entity = try PageEntity
                .filter(Column("id") == pageId)
                .filter(sql: "deleted_at IS NULL")
                .fetchOne(db) else {
                throw DriveError.notFound
            }
            guard let template = FakeDriveRepository.seededTemplates
                .first(where: { $0.id == templateId }) else {
                throw DriveError.notFound
            }
            // Append template pre-notes as their own typed
            // `StoredPageNote` entries so each one keeps its
            // identity. The legacy `notes` markdown column is
            // rebuilt from the joined bodies — kept in sync only
            // because the FTS triggers index against it.
            let nowIso = formatISODate(Date())
            let existingPageNotes: [StoredPageNote] = entity.pageNotesJson.parsePageNotes()
            let appendedPageNotes: [StoredPageNote] = template.preNotes
                .enumerated()
                .compactMap { idx, body -> StoredPageNote? in
                    let trimmed = body.trimmingCharacters(in: .whitespaces)
                    guard !trimmed.isEmpty else { return nil }
                    return StoredPageNote(
                        id: "tmpl-n-\(template.id)-\(pageId)-\(idx)",
                        body: body,
                        createdAt: nowIso
                    )
                }
            let mergedPageNotes = existingPageNotes + appendedPageNotes
            entity.pageNotesJson = mergedPageNotes.toJsonString()
            entity.notes = mergedPageNotes.map { $0.body }.joined(separator: "\n\n")
            // Append todos onto the existing JSON list, generating
            // ids that won't collide if the user re-applies the
            // template to the same page.
            var existingTodos: [NotepadTodo] = entity.todos.parseTodos()
            for (idx, body) in template.preTodos.enumerated() {
                existingTodos.append(NotepadTodo(
                    id: "tmpl-t-\(template.id)-\(pageId)-\(idx)",
                    text: body,
                    done: false
                ))
            }
            entity.todos = existingTodos.toJsonString()
            entity.updatedAt = formatISODate(Date())
            entity.dirty = true
            try entity.update(db)
            let parentNotebookId = try Self.notebookId(forChapter: entity.chapterId, db: db)
            return entity.toDomainPage(notebookId: parentNotebookId)
        }
    }

    @discardableResult
    public func createPage(notebookId: String, chapterId: String, title: String) async throws -> Page {
        try await dbQueue.write { db in
            // Guard against stale ids — the caller normally pulls
            // these out of `defaultCaptureDestination()` so they
            // should be live, but a stale path shouldn't insert
            // an orphan page.
            guard try NotebookEntity
                .filter(Column("id") == notebookId)
                .filter(sql: "deleted_at IS NULL")
                .fetchCount(db) > 0 else {
                throw DriveError.notFound
            }
            guard try ChapterEntity
                .filter(Column("id") == chapterId)
                .filter(sql: "notebook_id = ? AND deleted_at IS NULL", arguments: [notebookId])
                .fetchCount(db) > 0 else {
                throw DriveError.notFound
            }
            let id = "pg-\(UUID().uuidString.prefix(8).lowercased())"
            let now = formatISODate(Date())
            let resolvedTitle = title.trimmingCharacters(in: .whitespacesAndNewlines)
            let entity = PageEntity(
                id: id,
                chapterId: chapterId,
                projectId: nil,
                templateId: nil,
                title: resolvedTitle.isEmpty ? "New page" : resolvedTitle,
                notes: "",
                contacts: "[]",
                locations: "[]",
                todos: "[]",
                attachments: "[]",
                tags: "[]",
                position: 1024,
                conflictStub: nil,
                driveFileId: nil,
                createdAt: now,
                updatedAt: now,
                dirty: true,
                deletedAt: nil,
                archivedAt: nil
            )
            try entity.insert(db)
            return entity.toDomainPage(notebookId: notebookId)
        }
    }

    public func defaultCaptureDestination() async throws -> (notebookId: String, chapterId: String) {
        try await dbQueue.read { db -> (String, String) in
            // Pick the most-recently-updated active notebook and
            // its first active chapter. Throws when nothing is
            // reachable so the caller can surface an empty state.
            guard let notebook = try NotebookEntity
                .filter(sql: "deleted_at IS NULL AND archived_at IS NULL")
                .order(Column("updated_at").desc)
                .fetchOne(db) else {
                throw DriveError.notFound
            }
            guard let chapter = try ChapterEntity
                .filter(sql: "notebook_id = ? AND deleted_at IS NULL AND archived_at IS NULL", arguments: [notebook.id])
                .order(Column("position").asc)
                .fetchOne(db) else {
                throw DriveError.notFound
            }
            return (notebook.id, chapter.id)
        }
    }

    // MARK: - Notebook mutations

    @discardableResult
    public func renameNotebook(id: String, title: String) async throws -> Notebook {
        try await dbQueue.write { db in
            guard var entity = try NotebookEntity
                .filter(Column("id") == id)
                .filter(sql: "deleted_at IS NULL")
                .fetchOne(db) else {
                throw DriveError.notFound
            }
            entity.title = title
            entity.updatedAt = formatISODate(Date())
            entity.dirty = true
            try entity.update(db)
            return entity.toDomainNotebook(
                chapterCount: 0,
                pageCount: 0,
                shelfName: try Self.shelfName(db: db, shelfId: entity.shelfId)
            )
        }
    }

    @discardableResult
    public func archiveNotebook(id: String) async throws -> Notebook {
        try await Self.touchNotebookArchive(dbQueue: dbQueue, id: id, archivedAt: Date())
    }

    @discardableResult
    public func restoreNotebook(id: String) async throws -> Notebook {
        try await Self.touchNotebookArchive(dbQueue: dbQueue, id: id, archivedAt: nil)
    }

    // MARK: - Chapter mutations

    @discardableResult
    public func createChapter(notebookId: String, title: String) async throws -> Chapter {
        try await dbQueue.write { db in
            guard try NotebookEntity
                .filter(Column("id") == notebookId)
                .filter(sql: "deleted_at IS NULL")
                .fetchCount(db) > 0 else {
                throw DriveError.notFound
            }
            // Position = max + 1 so new chapters land at the end of
            // the existing list.
            let nextPosition: Int64 = try Int64.fetchOne(db, sql: """
                SELECT COALESCE(MAX(position), 0) + 1024
                FROM chapters
                WHERE notebook_id = ? AND deleted_at IS NULL
                """, arguments: [notebookId]) ?? 1024
            let resolved = title.trimmingCharacters(in: .whitespacesAndNewlines)
            let id = "ch-\(UUID().uuidString.prefix(8).lowercased())"
            let now = formatISODate(Date())
            let entity = ChapterEntity(
                id: id,
                notebookId: notebookId,
                title: resolved.isEmpty ? "Untitled chapter" : resolved,
                position: nextPosition,
                driveFileId: nil,
                createdAt: now,
                updatedAt: now,
                dirty: true,
                deletedAt: nil,
                archivedAt: nil
            )
            try entity.insert(db)
            return entity.toDomainChapter()
        }
    }

    @discardableResult
    public func archiveChapter(id: String) async throws -> Chapter {
        try await Self.touchChapterArchive(dbQueue: dbQueue, id: id, archivedAt: Date())
    }

    @discardableResult
    public func restoreChapter(id: String) async throws -> Chapter {
        try await Self.touchChapterArchive(dbQueue: dbQueue, id: id, archivedAt: nil)
    }

    @discardableResult
    public func renameChapter(id: String, title: String) async throws -> Chapter {
        try await dbQueue.write { db in
            guard var entity = try ChapterEntity
                .filter(Column("id") == id)
                .filter(sql: "deleted_at IS NULL")
                .fetchOne(db) else {
                throw DriveError.notFound
            }
            let trimmed = title.trimmingCharacters(in: .whitespacesAndNewlines)
            entity.title = trimmed.isEmpty ? "Untitled chapter" : trimmed
            entity.updatedAt = formatISODate(Date())
            entity.dirty = true
            try entity.update(db)
            return entity.toDomainChapter()
        }
    }

    // MARK: - Internal helpers

    /// Toggle a page's `archived_at` timestamp. nil → restore.
    /// Returns the updated `Page` so callers can refresh their
    /// local state without a re-fetch.
    private static func touchPageArchive(
        db: Database,
        id: String,
        archivedAt: Date?
    ) throws -> Page {
        guard var entity = try PageEntity
            .filter(Column("id") == id)
            .filter(sql: "deleted_at IS NULL")
            .fetchOne(db) else {
            throw DriveError.notFound
        }
        // Idempotent: archive on already-archived returns the row
        // as-is, same on restore. Saves a write + a notify.
        let target = archivedAt.map(formatISODate)
        if entity.archivedAt != target {
            entity.archivedAt = target
            entity.updatedAt = formatISODate(Date())
            entity.dirty = true
            try entity.update(db)
        }
        let parentNotebookId = try notebookId(forChapter: entity.chapterId, db: db)
        return entity.toDomainPage(notebookId: parentNotebookId)
    }

    /// Toggle a notebook's `archived_at`. Wrapped in `dbQueue.write`
    /// at the call site so the read+write is one transaction.
    private static func touchNotebookArchive(
        dbQueue: DatabaseQueue,
        id: String,
        archivedAt: Date?
    ) async throws -> Notebook {
        try await dbQueue.write { db in
            guard var entity = try NotebookEntity
                .filter(Column("id") == id)
                .filter(sql: "deleted_at IS NULL")
                .fetchOne(db) else {
                throw DriveError.notFound
            }
            let target = archivedAt.map(formatISODate)
            if entity.archivedAt != target {
                entity.archivedAt = target
                entity.updatedAt = formatISODate(Date())
                entity.dirty = true
                try entity.update(db)
            }
            return entity.toDomainNotebook(
                chapterCount: 0,
                pageCount: 0,
                shelfName: try shelfName(db: db, shelfId: entity.shelfId)
            )
        }
    }

    /// Toggle a chapter's `archived_at`. Same pattern as the
    /// notebook variant.
    private static func touchChapterArchive(
        dbQueue: DatabaseQueue,
        id: String,
        archivedAt: Date?
    ) async throws -> Chapter {
        try await dbQueue.write { db in
            guard var entity = try ChapterEntity
                .filter(Column("id") == id)
                .filter(sql: "deleted_at IS NULL")
                .fetchOne(db) else {
                throw DriveError.notFound
            }
            let target = archivedAt.map(formatISODate)
            if entity.archivedAt != target {
                entity.archivedAt = target
                entity.updatedAt = formatISODate(Date())
                entity.dirty = true
                try entity.update(db)
            }
            return entity.toDomainChapter()
        }
    }

    /// Resolve the parent notebook id for a chapter. Pages keep
    /// only `chapter_id` so we hop through chapters whenever a
    /// page-side mapper needs the notebook.
    private static func notebookId(forChapter chapterId: String, db: Database) throws -> String {
        guard let chapterEntity = try ChapterEntity
            .filter(Column("id") == chapterId)
            .fetchOne(db) else {
            throw DriveError.notFound
        }
        return chapterEntity.notebookId
    }

    /// Look up a shelf's display name. Used to populate
    /// `Notebook.shelfName` on detail reads. Returns nil for
    /// orphaned (deleted-shelf) notebooks rather than throwing,
    /// so a stale FK doesn't crash the load.
    private static func shelfName(db: Database, shelfId: String) throws -> String? {
        try ShelfEntity
            .filter(Column("id") == shelfId)
            .filter(sql: "deleted_at IS NULL")
            .fetchOne(db)?.name
    }

    /// Single-row count helper. Used by the count joins in
    /// `loadNotebook`. Returns 0 when the query returns no rows
    /// (e.g. notebook with no chapters yet).
    private static func scalarCount(
        db: Database,
        sql: String,
        arg: String
    ) throws -> Int {
        try Int.fetchOne(db, sql: sql, arguments: [arg]) ?? 0
    }

    /// Build the active-notebook list with chapter + page counts
    /// joined in. Same shape `observeShelves` uses internally,
    /// reused here for `listNotebooks()` so the domain shape
    /// already carries counts when the notebook list renders.
    private static func fetchNotebooksWithCounts(
        db: Database,
        includeArchived: Bool
    ) throws -> [Notebook] {
        let activeFilter = includeArchived
            ? "deleted_at IS NULL"
            : "deleted_at IS NULL AND archived_at IS NULL"
        let notebooks = try NotebookEntity
            .filter(sql: activeFilter)
            .order(Column("updated_at").desc)
            .fetchAll(db)

        // Counts for chapters per notebook.
        let chapterCounts: [String: Int] = try fetchCountMap(
            db: db,
            sql: """
                SELECT notebook_id AS id, COUNT(*) AS c
                FROM chapters
                WHERE deleted_at IS NULL
                GROUP BY notebook_id
                """
        )
        // Counts for pages per notebook (via chapters).
        let pageCounts: [String: Int] = try fetchCountMap(
            db: db,
            sql: """
                SELECT c.notebook_id AS id, COUNT(*) AS c
                FROM pages p
                JOIN chapters c ON c.id = p.chapter_id
                WHERE p.deleted_at IS NULL AND c.deleted_at IS NULL
                GROUP BY c.notebook_id
                """
        )

        // Resolve shelf names in one batch query so we don't
        // round-trip per notebook.
        let shelfIds = Set(notebooks.map { $0.shelfId })
        let shelfNameMap: [String: String]
        if shelfIds.isEmpty {
            shelfNameMap = [:]
        } else {
            let placeholders = shelfIds.map { _ in "?" }.joined(separator: ",")
            let rows = try Row.fetchAll(
                db,
                sql: "SELECT id, name FROM shelves WHERE id IN (\(placeholders)) AND deleted_at IS NULL",
                arguments: StatementArguments(Array(shelfIds))
            )
            var map: [String: String] = [:]
            for row in rows {
                let id: String = row["id"]
                let name: String = row["name"]
                map[id] = name
            }
            shelfNameMap = map
        }

        return notebooks.map { nb in
            nb.toDomainNotebook(
                chapterCount: chapterCounts[nb.id] ?? 0,
                pageCount:    pageCounts[nb.id] ?? 0,
                shelfName:    shelfNameMap[nb.shelfId]
            )
        }
    }

    private static func fetchCountMap(db: Database, sql: String) throws -> [String: Int] {
        let rows = try Row.fetchAll(db, sql: sql)
        var out: [String: Int] = [:]
        for row in rows {
            let id: String = row["id"]
            let c: Int = row["c"]
            out[id] = c
        }
        return out
    }
}
