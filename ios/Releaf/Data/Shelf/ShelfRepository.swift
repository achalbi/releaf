/*
 * ShelfRepository.swift
 *
 * App-facing store for `shelves`. Observes via GRDB's
 * `ValueObservation` and guarantees the default "General" shelf is
 * always present so the book-creation flow always has a landing
 * target.
 */

import Foundation
import GRDB

public final class ShelfRepository: @unchecked Sendable {

    private let dbQueue: DatabaseQueue

    public init(database: ReleafDatabase = .shared) {
        self.dbQueue = database.dbQueue
    }

    /// Stream of live (non-deleted) shelves ordered for list display.
    public func observeActive() -> AsyncThrowingStream<[ShelfEntity], Error> {
        let observation = ValueObservation.tracking { db in
            try ShelfEntity
                .filter(sql: "deleted_at IS NULL")
                .order(Column("position").asc, Column("created_at").asc)
                .fetchAll(db)
        }
        return bridge(observation.values(in: dbQueue))
    }

    public func observeById(_ id: String) -> AsyncThrowingStream<ShelfEntity?, Error> {
        let observation = ValueObservation.tracking { db in
            try ShelfEntity
                .filter(sql: "id = ?", arguments: [id])
                .fetchOne(db)
        }
        return bridge(observation.values(in: dbQueue))
    }

    public func findById(_ id: String) async throws -> ShelfEntity? {
        try await dbQueue.read { db in
            try ShelfEntity
                .filter(sql: "id = ?", arguments: [id])
                .fetchOne(db)
        }
    }

    @discardableResult
    public func createShelf(name: String, colorHex: String? = nil) async throws -> ShelfEntity {
        let now = IsoClock.nowIso()
        let shelf = ShelfEntity(
            id:        Uuidv7.generate(),
            name:      name.trimmingCharacters(in: .whitespacesAndNewlines).ifEmpty("Untitled shelf"),
            colorHex:  colorHex,
            createdAt: now,
            updatedAt: now,
            dirty:     true
        )
        try await dbQueue.write { db in try shelf.insert(db) }
        return shelf
    }

    public func rename(id: String, name: String, colorHex: String? = nil) async throws {
        guard var current = try await findById(id) else { return }
        let cleaned = name.trimmingCharacters(in: .whitespacesAndNewlines).ifEmpty(current.name)
        if cleaned == current.name && colorHex == current.colorHex { return }
        current.name = cleaned
        current.colorHex = colorHex ?? current.colorHex
        current.updatedAt = IsoClock.nowIso()
        current.dirty = true
        // Snapshot to a `let` before the @Sendable write closure —
        // Swift 6 rejects capture-by-reference of mutable locals.
        let snapshot = current
        try await dbQueue.write { db in try snapshot.update(db) }
    }

    public func softDelete(id: String) async throws {
        let now = IsoClock.nowIso()
        try await dbQueue.write { db in
            try db.execute(sql: """
                UPDATE shelves
                SET deleted_at = ?, updated_at = ?, dirty = 1
                WHERE id = ?
                """, arguments: [now, now, id])
        }
    }

    public func undoSoftDelete(id: String) async throws {
        let now = IsoClock.nowIso()
        try await dbQueue.write { db in
            try db.execute(sql: """
                UPDATE shelves
                SET deleted_at = NULL, updated_at = ?, dirty = 1
                WHERE id = ?
                """, arguments: [now, id])
        }
    }

    /// Ensure the default "General" shelf exists. Called at app
    /// startup on both platforms; no-op when the migration has
    /// already seeded it.
    @discardableResult
    public func ensureDefaultShelf() async throws -> ShelfEntity {
        if let existing = try await findById(ShelfEntity.defaultGeneralId) {
            return existing
        }
        let now = IsoClock.nowIso()
        let shelf = ShelfEntity(
            id:        ShelfEntity.defaultGeneralId,
            name:      "General",
            colorHex:  "#7AA874",
            position:  1024,
            createdAt: now,
            updatedAt: now,
            dirty:     true
        )
        try await dbQueue.write { db in try shelf.insert(db) }
        return shelf
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

private extension String {
    func ifEmpty(_ fallback: String) -> String {
        trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? fallback : self
    }
}
