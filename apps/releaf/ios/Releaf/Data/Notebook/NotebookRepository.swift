/*
 * NotebookRepository.swift
 *
 * Orchestrates notebook + chapter + page writes as cohesive units.
 * Mirror of Android's `NotebookRepository.kt`: consumers hit this one
 * repo for the cross-entity operations (cascade soft-delete of a
 * notebook tombstones chapters and pages beneath it, same for chapter
 * → pages), while the child-specific reads/writes live on
 * `ChapterRepository` and `PageRepository`.
 *
 * Observation uses GRDB 7's `ValueObservation.values(in:)` which emits
 * a fresh snapshot on every committed transaction. Bridged to
 * `AsyncThrowingStream` at the edge so iterator cancellation maps
 * cleanly to stopping the observation.
 */

import Foundation
import GRDB
import ReleafCoreDrive  // DriveError (moved in PR #3b)

/// One active notebook joined with its live chapter + page counts.
/// Feeds the variant-1 "Your shelves" view so the UI layer gets a
/// single stream instead of having to fan out three separate reads.
public struct ShelfRecord: Equatable, Sendable {
    public let notebook: NotebookEntity
    public let chapterCount: Int
    public let pageCount: Int

    public init(notebook: NotebookEntity, chapterCount: Int, pageCount: Int) {
        self.notebook = notebook
        self.chapterCount = chapterCount
        self.pageCount = pageCount
    }
}

public final class NotebookRepository: @unchecked Sendable {

    private let dbQueue: DatabaseQueue

    public init(database: ReleafDatabase = .shared) {
        self.dbQueue = database.dbQueue
    }

    // MARK: - Observation

    /// Stream of live (non-deleted) notebooks, newest-edited first.
    public func observeActive() -> AsyncThrowingStream<[NotebookEntity], Error> {
        let observation = ValueObservation.tracking { db in
            try NotebookEntity
                .filter(sql: "deleted_at IS NULL")
                .order(Column("updated_at").desc)
                .fetchAll(db)
        }
        return bridge(observation.values(in: dbQueue))
    }

    /// Stream of live notebooks scoped to one shelf, ordered for list display.
    public func observeForShelf(shelfId: String) -> AsyncThrowingStream<[NotebookEntity], Error> {
        let observation = ValueObservation.tracking { db in
            try NotebookEntity
                .filter(sql: "shelf_id = ? AND deleted_at IS NULL", arguments: [shelfId])
                .order(Column("position").asc, Column("updated_at").desc)
                .fetchAll(db)
        }
        return bridge(observation.values(in: dbQueue))
    }

    /// Stream of volumes within a series, sorted by `volume_number`.
    public func observeForSeries(seriesId: String) -> AsyncThrowingStream<[NotebookEntity], Error> {
        let observation = ValueObservation.tracking { db in
            try NotebookEntity
                .filter(sql: "series_id = ? AND deleted_at IS NULL", arguments: [seriesId])
                .order(Column("volume_number").asc)
                .fetchAll(db)
        }
        return bridge(observation.values(in: dbQueue))
    }

    /// Stream of live notebooks with per-notebook chapter + page
    /// counts joined in. Re-fires on any change to the three tables.
    public func observeShelves() -> AsyncThrowingStream<[ShelfRecord], Error> {
        let observation = ValueObservation.tracking { db -> [ShelfRecord] in
            let notebooks = try NotebookEntity
                .filter(sql: "deleted_at IS NULL")
                .order(Column("updated_at").desc)
                .fetchAll(db)

            let chapterCounts: [String: Int] = try Self.fetchCountMap(
                db: db,
                sql: """
                    SELECT notebook_id AS id, COUNT(*) AS c
                    FROM chapters
                    WHERE deleted_at IS NULL
                    GROUP BY notebook_id
                    """
            )

            let pageCounts: [String: Int] = try Self.fetchCountMap(
                db: db,
                sql: """
                    SELECT c.notebook_id AS id, COUNT(*) AS c
                    FROM pages p
                    JOIN chapters c ON c.id = p.chapter_id
                    WHERE p.deleted_at IS NULL AND c.deleted_at IS NULL
                    GROUP BY c.notebook_id
                    """
            )

            return notebooks.map { nb in
                ShelfRecord(
                    notebook: nb,
                    chapterCount: chapterCounts[nb.id] ?? 0,
                    pageCount: pageCounts[nb.id] ?? 0
                )
            }
        }
        return bridge(observation.values(in: dbQueue))
    }

    private static func fetchCountMap(db: Database, sql: String) throws -> [String: Int] {
        let rows = try Row.fetchAll(db, sql: sql)
        var out: [String: Int] = [:]
        for row in rows {
            guard let id: String = row["id"] else { continue }
            let c: Int = row["c"] ?? 0
            out[id] = c
        }
        return out
    }

    public func observeById(_ id: String) -> AsyncThrowingStream<NotebookEntity?, Error> {
        let observation = ValueObservation.tracking { db in
            try NotebookEntity
                .filter(sql: "id = ? AND deleted_at IS NULL", arguments: [id])
                .fetchOne(db)
        }
        return bridge(observation.values(in: dbQueue))
    }

    public func findById(_ id: String) async throws -> NotebookEntity? {
        try await dbQueue.read { db in
            try NotebookEntity
                .filter(sql: "id = ?", arguments: [id])
                .fetchOne(db)
        }
    }

    // MARK: - Create / Update

    /// Create a standalone book (no series). Defaults to the
    /// General shelf so existing call-sites that aren't yet shelf-
    /// aware continue to work.
    @discardableResult
    public func createNotebook(
        title: String,
        colorHex: String? = nil,
        shelfId: String = "shelf-general"
    ) async throws -> NotebookEntity {
        let now = IsoClock.nowIso()
        let entity = NotebookEntity(
            id:           Uuidv7.generate(),
            title:        title.trimmingCharacters(in: .whitespacesAndNewlines),
            colorHex:     colorHex,
            shelfId:      shelfId,
            seriesId:     nil,
            volumeNumber: 1,
            volumeName:   nil,
            createdAt:    now,
            updatedAt:    now,
            dirty:        true
        )
        try await dbQueue.write { db in try entity.insert(db) }
        return entity
    }

    /// Promote an existing standalone notebook to belong to a
    /// series so additional volumes can be added. Returns the new
    /// (or existing) series id.
    @discardableResult
    public func ensureSeriesFor(notebookId: String, seriesName: String? = nil) async throws -> String {
        guard let nb = try await dbQueue.read({ db in
            try NotebookEntity.filter(sql: "id = ?", arguments: [notebookId]).fetchOne(db)
        }) else {
            throw DriveError.notFound
        }
        if let existing = nb.seriesId { return existing }

        let now = IsoClock.nowIso()
        let series = BookSeriesEntity(
            id:        Uuidv7.generate(),
            shelfId:   nb.shelfId,
            name:      seriesName?.trimmingCharacters(in: .whitespacesAndNewlines).ifEmpty(nb.title) ?? nb.title,
            createdAt: now,
            updatedAt: now,
            dirty:     true
        )
        try await dbQueue.write { db in
            try series.insert(db)
            try db.execute(sql: """
                UPDATE notebooks
                SET series_id = ?, volume_number = 1, updated_at = ?, dirty = 1
                WHERE id = ?
                """, arguments: [series.id, now, notebookId])
        }
        return series.id
    }

    /// Add a new volume under an existing series.
    @discardableResult
    public func addVolumeToSeries(
        seriesId: String,
        volumeName: String? = nil,
        colorHex: String? = nil
    ) async throws -> NotebookEntity {
        let (series, next): (BookSeriesEntity, Int) = try await dbQueue.read { db in
            guard let series = try BookSeriesEntity
                .filter(sql: "id = ?", arguments: [seriesId])
                .fetchOne(db) else {
                throw DriveError.notFound
            }
            let max = try Int.fetchOne(db, sql: """
                SELECT MAX(volume_number) FROM notebooks
                WHERE series_id = ? AND deleted_at IS NULL
                """, arguments: [seriesId]) ?? 0
            return (series, max + 1)
        }

        let cleanedVolumeName = volumeName?.trimmingCharacters(in: .whitespacesAndNewlines)
            .nilIfEmpty
        let displayTitle = cleanedVolumeName ?? "\(series.name) vol \(next)"

        let now = IsoClock.nowIso()
        let entity = NotebookEntity(
            id:           Uuidv7.generate(),
            title:        displayTitle,
            colorHex:     colorHex,
            shelfId:      series.shelfId,
            seriesId:     series.id,
            volumeNumber: next,
            volumeName:   cleanedVolumeName,
            createdAt:    now,
            updatedAt:    now,
            dirty:        true
        )
        try await dbQueue.write { db in try entity.insert(db) }
        return entity
    }

    /// Create a book and its enclosing series in one call — use
    /// when the user knows up front the book will have multiple
    /// volumes.
    @discardableResult
    public func createBookInNewSeries(
        shelfId: String,
        seriesName: String,
        volumeName: String? = nil,
        colorHex: String? = nil
    ) async throws -> NotebookEntity {
        let now = IsoClock.nowIso()
        let series = BookSeriesEntity(
            id:        Uuidv7.generate(),
            shelfId:   shelfId,
            name:      seriesName.trimmingCharacters(in: .whitespacesAndNewlines).ifEmpty("Untitled book"),
            createdAt: now,
            updatedAt: now,
            dirty:     true
        )
        let cleanedVolumeName = volumeName?.trimmingCharacters(in: .whitespacesAndNewlines)
            .nilIfEmpty
        let entity = NotebookEntity(
            id:           Uuidv7.generate(),
            title:        cleanedVolumeName ?? series.name,
            colorHex:     colorHex,
            shelfId:      shelfId,
            seriesId:     series.id,
            volumeNumber: 1,
            volumeName:   cleanedVolumeName,
            createdAt:    now,
            updatedAt:    now,
            dirty:        true
        )
        try await dbQueue.write { db in
            try series.insert(db)
            try entity.insert(db)
        }
        return entity
    }

    public func saveNotebook(_ entity: NotebookEntity) async throws {
        var row = entity
        row.title = entity.title.trimmingCharacters(in: .whitespacesAndNewlines)
        row.updatedAt = IsoClock.nowIso()
        row.dirty = true
        // Capture an immutable snapshot before crossing the actor
        // boundary — `var` capture in @Sendable closures is a Swift 6
        // concurrency error.
        let snapshot = row
        try await dbQueue.write { db in try snapshot.update(db) }
    }

    // MARK: - Soft delete (cascade)

    /// Tombstone a notebook + every live chapter and page beneath it.
    /// Each row gets `deleted_at = now, dirty = 1` so the sync worker
    /// knows to propagate the cascade to Drive. No explicit transaction:
    /// a UI that observes mid-cascade just flicks rows out in sequence.
    /// When we care, wrap in `dbQueue.write { db in ... }` with a
    /// single call inside.
    public func softDeleteNotebook(id: String) async throws {
        let now = IsoClock.nowIso()
        try await dbQueue.write { db in
            // Collect live chapter ids for the notebook, cascade pages
            // per chapter, then tombstone chapters, then the notebook.
            let liveChapterIds = try String.fetchAll(db, sql: """
                SELECT id FROM chapters
                WHERE notebook_id = ? AND deleted_at IS NULL
                """, arguments: [id])

            for chapterId in liveChapterIds {
                try db.execute(sql: """
                    UPDATE pages
                    SET deleted_at = ?, updated_at = ?, dirty = 1
                    WHERE chapter_id = ? AND deleted_at IS NULL
                    """, arguments: [now, now, chapterId])
            }

            try db.execute(sql: """
                UPDATE chapters
                SET deleted_at = ?, updated_at = ?, dirty = 1
                WHERE notebook_id = ? AND deleted_at IS NULL
                """, arguments: [now, now, id])

            try db.execute(sql: """
                UPDATE notebooks
                SET deleted_at = ?, updated_at = ?, dirty = 1
                WHERE id = ?
                """, arguments: [now, now, id])
        }
    }

    /// Restore a notebook tombstone. Only the notebook row comes back —
    /// chapters and pages that were cascaded into tombstones by
    /// [softDeleteNotebook] stay deleted. Matches Android's behavior.
    public func undoSoftDeleteNotebook(id: String) async throws {
        let now = IsoClock.nowIso()
        try await dbQueue.write { db in
            try db.execute(sql: """
                UPDATE notebooks
                SET deleted_at = NULL, updated_at = ?, dirty = 1
                WHERE id = ?
                """, arguments: [now, id])
        }
    }
}

// MARK: - AsyncSequence bridge

/// Same bridge used by `NotepadRepository`. Keeps iterators cancellable
/// by forwarding termination to the GRDB observation task.
private func bridge<S: AsyncSequence & Sendable>(_ sequence: S) -> AsyncThrowingStream<S.Element, Error>
where S.Element: Sendable {
    AsyncThrowingStream { continuation in
        let task = Task {
            do {
                for try await value in sequence {
                    continuation.yield(value)
                }
                continuation.finish()
            } catch {
                continuation.finish(throwing: error)
            }
        }
        continuation.onTermination = { _ in task.cancel() }
    }
}

// MARK: - String helpers (shared with ShelfRepository)

private extension String {
    func ifEmpty(_ fallback: String) -> String {
        trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? fallback : self
    }
    var nilIfEmpty: String? {
        trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : self
    }
}
