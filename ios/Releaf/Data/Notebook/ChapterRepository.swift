/*
 * ChapterRepository.swift
 *
 * Middle-tier repo for chapters. Handles the cascade between chapter
 * and its pages (soft-delete of a chapter tombstones the pages
 * beneath it). Mirror of Android's `ChapterRepository.kt`.
 */

import Foundation
import GRDB

public final class ChapterRepository: @unchecked Sendable {

    private let dbQueue: DatabaseQueue

    public init(database: ReleafDatabase = .shared) {
        self.dbQueue = database.dbQueue
    }

    // MARK: - Observation

    public func observeForNotebook(notebookId: String) -> AsyncThrowingStream<[ChapterEntity], Error> {
        let observation = ValueObservation.tracking { db in
            try ChapterEntity
                .filter(sql: "notebook_id = ? AND deleted_at IS NULL", arguments: [notebookId])
                .order(Column("position").asc, Column("created_at").asc)
                .fetchAll(db)
        }
        return bridge(observation.values(in: dbQueue))
    }

    public func findById(_ id: String) async throws -> ChapterEntity? {
        try await dbQueue.read { db in
            try ChapterEntity
                .filter(sql: "id = ?", arguments: [id])
                .fetchOne(db)
        }
    }

    /// One-shot fetch of live chapter ids beneath a notebook — used by
    /// the notebook cascade-delete in `NotebookRepository`.
    public func liveIdsForNotebook(_ notebookId: String) async throws -> [String] {
        try await dbQueue.read { db in
            try String.fetchAll(db, sql: """
                SELECT id FROM chapters
                WHERE notebook_id = ? AND deleted_at IS NULL
                """, arguments: [notebookId])
        }
    }

    // MARK: - Create / Update

    @discardableResult
    public func createChapter(notebookId: String, title: String) async throws -> ChapterEntity {
        let now = IsoClock.nowIso()
        let entity = ChapterEntity(
            id:          Uuidv7.generate(),
            notebookId:  notebookId,
            title:       title.trimmingCharacters(in: .whitespacesAndNewlines),
            createdAt:   now,
            updatedAt:   now,
            dirty:       true
        )
        try await dbQueue.write { db in try entity.insert(db) }
        return entity
    }

    public func saveChapter(_ entity: ChapterEntity) async throws {
        // Materialize the mutated entity into an immutable `let` before
        // handing it to the @Sendable write closure — capturing a `var`
        // by reference is a Swift 6 concurrency error.
        var row = entity
        row.title = entity.title.trimmingCharacters(in: .whitespacesAndNewlines)
        row.updatedAt = IsoClock.nowIso()
        row.dirty = true
        let snapshot = row
        try await dbQueue.write { db in try snapshot.update(db) }
    }

    // MARK: - Soft delete (cascade to pages)

    public func softDeleteChapter(id: String) async throws {
        let now = IsoClock.nowIso()
        try await dbQueue.write { db in
            try db.execute(sql: """
                UPDATE pages
                SET deleted_at = ?, updated_at = ?, dirty = 1
                WHERE chapter_id = ? AND deleted_at IS NULL
                """, arguments: [now, now, id])
            try db.execute(sql: """
                UPDATE chapters
                SET deleted_at = ?, updated_at = ?, dirty = 1
                WHERE id = ?
                """, arguments: [now, now, id])
        }
    }

    public func undoSoftDeleteChapter(id: String) async throws {
        let now = IsoClock.nowIso()
        try await dbQueue.write { db in
            try db.execute(sql: """
                UPDATE chapters
                SET deleted_at = NULL, updated_at = ?, dirty = 1
                WHERE id = ?
                """, arguments: [now, id])
        }
    }
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
