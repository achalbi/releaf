/*
 * PageRepository.swift
 *
 * Single-page CRUD + FTS-backed search. Mirror of Android's
 * `PageRepository.kt`. Shape matches `NotepadRepository` for consistent
 * VM-side logic: observe, findById, create, save, softDelete,
 * undoSoftDelete, plus sync-worker helpers.
 */

import Foundation
import GRDB

public struct PageSearchHit: Codable, FetchableRecord, Equatable, Identifiable, Sendable {
    public let id: String
    public let title: String?
    public let notes: String
    public let updatedAt: String
    public let notebookTitle: String
    public let chapterTitle: String

    public enum CodingKeys: String, CodingKey {
        case id
        case title
        case notes
        case updatedAt     = "updated_at"
        case notebookTitle = "notebook_title"
        case chapterTitle  = "chapter_title"
    }

    public init(
        id: String,
        title: String?,
        notes: String,
        updatedAt: String,
        notebookTitle: String,
        chapterTitle: String
    ) {
        self.id = id
        self.title = title
        self.notes = notes
        self.updatedAt = updatedAt
        self.notebookTitle = notebookTitle
        self.chapterTitle = chapterTitle
    }
}

public final class PageRepository: @unchecked Sendable {

    private let dbQueue: DatabaseQueue

    public init(database: ReleafDatabase = .shared) {
        self.dbQueue = database.dbQueue
    }

    // MARK: - Observation

    public func observeForChapter(chapterId: String) -> AsyncThrowingStream<[PageEntity], Error> {
        let observation = ValueObservation.tracking { db in
            try PageEntity
                .filter(sql: "chapter_id = ? AND deleted_at IS NULL", arguments: [chapterId])
                .order(Column("position").asc, Column("created_at").asc)
                .fetchAll(db)
        }
        return bridge(observation.values(in: dbQueue))
    }

    /// All live pages across a notebook, newest-edited first. Joins
    /// through `chapters` so tombstoned chapters also hide their pages.
    public func observeForNotebook(notebookId: String) -> AsyncThrowingStream<[PageEntity], Error> {
        let observation = ValueObservation.tracking { db in
            try PageEntity.fetchAll(db, sql: """
                SELECT p.* FROM pages p
                JOIN chapters c ON c.id = p.chapter_id
                WHERE c.notebook_id = ?
                  AND p.deleted_at IS NULL
                  AND c.deleted_at IS NULL
                ORDER BY p.updated_at DESC
                """, arguments: [notebookId])
        }
        return bridge(observation.values(in: dbQueue))
    }

    public func observeById(_ id: String) -> AsyncThrowingStream<PageEntity?, Error> {
        let observation = ValueObservation.tracking { db in
            try PageEntity
                .filter(sql: "id = ? AND deleted_at IS NULL", arguments: [id])
                .fetchOne(db)
        }
        return bridge(observation.values(in: dbQueue))
    }

    public func findById(_ id: String) async throws -> PageEntity? {
        try await dbQueue.read { db in
            try PageEntity
                .filter(sql: "id = ?", arguments: [id])
                .fetchOne(db)
        }
    }

    // MARK: - FTS search

    /// Full-text search across every live page. Uses the shared
    /// `FtsQuery` sanitizer so empty/noise queries short-circuit to an
    /// empty snapshot rather than raising an FTS5 MATCH error.
    public func searchAll(rawQuery: String) -> AsyncThrowingStream<[PageEntity], Error> {
        guard let match = FtsQuery.build(rawQuery) else {
            return AsyncThrowingStream { c in
                c.yield([])
                c.finish()
            }
        }
        let observation = ValueObservation.tracking { db in
            try PageEntity.fetchAll(db, sql: """
                SELECT p.* FROM pages p
                JOIN fts_page_notes fts ON fts.page_id = p.id
                WHERE p.deleted_at IS NULL
                  AND fts_page_notes MATCH ?
                ORDER BY rank
                """, arguments: [match])
        }
        return bridge(observation.values(in: dbQueue))
    }

    /// Full-text search across every live page, plus the notebook/chapter
    /// labels needed to make tab-level results understandable at a glance.
    public func searchAllWithContext(rawQuery: String) -> AsyncThrowingStream<[PageSearchHit], Error> {
        guard let match = FtsQuery.build(rawQuery) else {
            return AsyncThrowingStream { c in
                c.yield([])
                c.finish()
            }
        }
        let observation = ValueObservation.tracking { db in
            try PageSearchHit.fetchAll(db, sql: """
                SELECT
                    p.id,
                    p.title,
                    p.notes,
                    p.updated_at,
                    c.title AS chapter_title,
                    n.title AS notebook_title
                FROM pages p
                JOIN chapters c ON c.id = p.chapter_id
                JOIN notebooks n ON n.id = c.notebook_id
                JOIN fts_page_notes fts ON fts.page_id = p.id
                WHERE p.deleted_at IS NULL
                  AND c.deleted_at IS NULL
                  AND n.deleted_at IS NULL
                  AND fts_page_notes MATCH ?
                ORDER BY rank
                """, arguments: [match])
        }
        return bridge(observation.values(in: dbQueue))
    }

    public func searchInNotebook(notebookId: String, rawQuery: String) -> AsyncThrowingStream<[PageEntity], Error> {
        guard let match = FtsQuery.build(rawQuery) else {
            return AsyncThrowingStream { c in
                c.yield([])
                c.finish()
            }
        }
        let observation = ValueObservation.tracking { db in
            try PageEntity.fetchAll(db, sql: """
                SELECT p.* FROM pages p
                JOIN chapters c ON c.id = p.chapter_id
                JOIN fts_page_notes fts ON fts.page_id = p.id
                WHERE c.notebook_id = ?
                  AND p.deleted_at IS NULL
                  AND c.deleted_at IS NULL
                  AND fts_page_notes MATCH ?
                ORDER BY rank
                """, arguments: [notebookId, match])
        }
        return bridge(observation.values(in: dbQueue))
    }

    // MARK: - Create / Update

    @discardableResult
    public func createPage(chapterId: String, title: String? = nil, notes: String = "") async throws -> PageEntity {
        let now = IsoClock.nowIso()
        let entity = PageEntity(
            id:        Uuidv7.generate(),
            chapterId: chapterId,
            title:     title?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty,
            notes:     notes,
            createdAt: now,
            updatedAt: now,
            dirty:     true
        )
        try await dbQueue.write { db in try entity.insert(db) }
        return entity
    }

    public func savePage(_ entity: PageEntity) async throws {
        var row = entity
        row.title = entity.title?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        row.updatedAt = IsoClock.nowIso()
        row.dirty = true
        // `let` snapshot before the @Sendable write closure — Swift 6
        // rejects capture-by-reference of mutable locals.
        let snapshot = row
        try await dbQueue.write { db in try snapshot.update(db) }
    }

    // MARK: - Soft delete

    public func softDeletePage(id: String) async throws {
        let now = IsoClock.nowIso()
        try await dbQueue.write { db in
            try db.execute(sql: """
                UPDATE pages
                SET deleted_at = ?, updated_at = ?, dirty = 1
                WHERE id = ?
                """, arguments: [now, now, id])
        }
    }

    public func undoSoftDeletePage(id: String) async throws {
        let now = IsoClock.nowIso()
        try await dbQueue.write { db in
            try db.execute(sql: """
                UPDATE pages
                SET deleted_at = NULL, updated_at = ?, dirty = 1
                WHERE id = ?
                """, arguments: [now, id])
        }
    }
}

// MARK: - Helpers

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}

// MARK: - AsyncSequence bridge

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
