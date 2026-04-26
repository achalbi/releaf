/*
 * TaskRepository.swift
 *
 * App-facing task store. Mirror of Android's `TaskRepository.kt` —
 * same create / save / complete / delete / restore surface plus a
 * count observer powering the home-screen Tasks card.
 *
 * Observation uses GRDB 7's `ValueObservation.values(in:)` bridged
 * into `AsyncThrowingStream` for a stable return type, matching
 * `NotepadRepository`'s convention.
 */

import Foundation
import GRDB

public final class TaskRepository: @unchecked Sendable {

    private let dbQueue: DatabaseQueue

    public init(database: ReleafDatabase = .shared) {
        self.dbQueue = database.dbQueue
    }

    // MARK: - Observation

    /// AsyncSequence of active (non-deleted) tasks for a user, open
    /// first, then by due-date asc (nulls last), then by updated_at
    /// desc. Sort order matches the Android DAO.
    public func observeActive(userId: String) -> AsyncThrowingStream<[TaskRecord], Error> {
        let observation = ValueObservation.tracking { db in
            try TaskRecord.fetchAll(db, sql: """
                SELECT * FROM tasks
                WHERE user_id = ? AND deleted_at IS NULL
                ORDER BY
                    completed ASC,
                    CASE WHEN due_date IS NULL THEN 1 ELSE 0 END ASC,
                    due_date ASC,
                    updated_at DESC
                """, arguments: [userId])
        }
        return bridge(observation.values(in: dbQueue))
    }

    /// AsyncSequence of the open-task count (not completed, not
    /// deleted). Powers the home-screen card's summary text.
    public func observeOpenCount(userId: String) -> AsyncThrowingStream<Int, Error> {
        let observation = ValueObservation.tracking { db in
            try Int.fetchOne(db, sql: """
                SELECT COUNT(*) FROM tasks
                WHERE user_id = ? AND deleted_at IS NULL AND completed = 0
                """, arguments: [userId]) ?? 0
        }
        return bridge(observation.values(in: dbQueue))
    }

    public func findById(_ id: String) async throws -> TaskRecord? {
        try await dbQueue.read { db in
            try TaskRecord
                .filter(sql: "id = ?", arguments: [id])
                .fetchOne(db)
        }
    }

    // MARK: - Mutations

    @discardableResult
    public func create(
        userId: String,
        title: String,
        description: String? = nil,
        dueDate: String? = nil,
        priority: Int = 0
    ) async throws -> TaskRecord {
        let now = IsoClock.nowIso()
        let trimmedTitle = title.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanDescription: String? = description.flatMap {
            let trimmed = $0.trimmingCharacters(in: .whitespacesAndNewlines)
            return trimmed.isEmpty ? nil : trimmed
        }
        let record = TaskRecord(
            id:          Uuidv7.generate(),
            userId:      userId,
            title:       trimmedTitle,
            description: cleanDescription,
            dueDate:     dueDate,
            priority:    priority,
            createdAt:   now,
            updatedAt:   now,
            dirty:       true
        )
        try await dbQueue.write { db in
            try record.insert(db)
        }
        return record
    }

    /// Persist edits. Bumps updated_at + dirty unconditionally.
    public func save(_ task: TaskRecord) async throws {
        var row = task
        row.title = task.title.trimmingCharacters(in: .whitespacesAndNewlines)
        row.updatedAt = IsoClock.nowIso()
        row.dirty = true
        // Snapshot to a `let` before the @Sendable write closure —
        // Swift 6 rejects capture-by-reference of mutable locals.
        let snapshot = row
        try await dbQueue.write { db in
            try snapshot.update(db)
        }
    }

    /// Flip completed. Stamps `completed_at` on true, clears on false.
    public func setCompleted(id: String, completed: Bool) async throws {
        let now = IsoClock.nowIso()
        try await dbQueue.write { db in
            try db.execute(sql: """
                UPDATE tasks
                SET completed    = ?,
                    completed_at = ?,
                    updated_at   = ?,
                    dirty        = 1
                WHERE id = ?
                """, arguments: [completed, completed ? now : nil, now, id])
        }
    }

    public func softDelete(id: String) async throws {
        let now = IsoClock.nowIso()
        try await dbQueue.write { db in
            try db.execute(sql: """
                UPDATE tasks
                SET deleted_at = ?, updated_at = ?, dirty = 1
                WHERE id = ?
                """, arguments: [now, now, id])
        }
    }

    public func undoSoftDelete(id: String) async throws {
        let now = IsoClock.nowIso()
        try await dbQueue.write { db in
            try db.execute(sql: """
                UPDATE tasks
                SET deleted_at = NULL, updated_at = ?, dirty = 1
                WHERE id = ?
                """, arguments: [now, id])
        }
    }
}

// MARK: - AsyncSequence bridge
//
// Same helper shape as NotepadRepository — stable public return type
// and iterator-discard cancellation.
private func bridge<S: AsyncSequence & Sendable>(_ sequence: S) -> AsyncThrowingStream<S.Element, Error>
where S.Element: Sendable {
    AsyncThrowingStream { continuation in
        let task = _Concurrency.Task {
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
