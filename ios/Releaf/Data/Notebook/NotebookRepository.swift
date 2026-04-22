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

    /// Create a notebook. Caller supplies `title` and an optional
    /// `colorHex`; everything else (id, timestamps, dirty) is filled in
    /// here so view-models don't duplicate the boilerplate.
    @discardableResult
    public func createNotebook(title: String, colorHex: String? = nil) async throws -> NotebookEntity {
        let now = IsoClock.nowIso()
        let entity = NotebookEntity(
            id:          Uuidv7.generate(),
            title:       title.trimmingCharacters(in: .whitespacesAndNewlines),
            colorHex:    colorHex,
            createdAt:   now,
            updatedAt:   now,
            dirty:       true
        )
        try await dbQueue.write { db in try entity.insert(db) }
        return entity
    }

    public func saveNotebook(_ entity: NotebookEntity) async throws {
        var row = entity
        row.title = entity.title.trimmingCharacters(in: .whitespacesAndNewlines)
        row.updatedAt = IsoClock.nowIso()
        row.dirty = true
        try await dbQueue.write { db in try row.update(db) }
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
