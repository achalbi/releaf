/*
 * CategoryRepository.swift
 *
 * GRDB-backed repository for the `categories` table. Wraps the CRUD
 * operations Settings → Categories needs:
 *   - list active categories (live observation)
 *   - insert / rename / soft-delete / reorder
 *   - first-launch seeding of the default 6 names
 *
 * Mirror of `CategoryRepository.kt` in QuickInk's Android target.
 *
 * `categories.name` has a UNIQUE (user_id, name) constraint per the
 * v2 migration; callers should treat name collisions as a no-op (or
 * surface a localised error from the Settings UI) rather than retry.
 */

import Foundation
import Combine
import GRDB
import ReleafCoreData

public final class CategoryRepository: @unchecked Sendable {

    /// Default seed names rendered as the user's starting set. The
    /// canonical list per QuickInk's Phase 5 spec — preserved here
    /// (rather than e.g. `QuickInkDefaults`) so the seed lives next
    /// to the writer that consumes it. Names in this list are
    /// treated as system-managed: the Settings → Categories screen
    /// hides delete + rename affordances for them.
    public static let defaultSeed: [String] = [
        "Ideas", "Projects", "Meetings", "Todo", "Business Card", "Journal",
    ]

    /// True for the 6 seed names users get on first launch — system
    /// categories the management screen leaves alone. Lives here so
    /// the iOS + Android lists can drift only by editing one file
    /// each.
    public static func isPredefined(_ name: String) -> Bool {
        defaultSeed.contains(name)
    }

    private let dbQueue: DatabaseQueue

    public init(database: QuickInkDatabase = .shared) {
        self.dbQueue = database.dbQueue
    }

    // MARK: - Reads

    /// Live publisher of a user's active (non-tombstone) categories,
    /// ordered by `position` ascending then `name` for stable output
    /// when positions tie. Empty list before the seed pass runs.
    public func observe(userId: String) -> AnyPublisher<[CategoryEntity], Error> {
        ValueObservation.tracking { db in
            try CategoryEntity
                .filter(Column("user_id") == userId)
                .filter(Column("deleted_at") == nil)
                .order(Column("position").asc, Column("name").asc)
                .fetchAll(db)
        }
        .publisher(in: dbQueue)
        .eraseToAnyPublisher()
    }

    /// One-shot fetch — used by the seed-if-empty path during app
    /// launch. Synchronous-equivalent via `dbQueue.read`.
    public func listActive(userId: String) async throws -> [CategoryEntity] {
        try await dbQueue.read { db in
            try CategoryEntity
                .filter(Column("user_id") == userId)
                .filter(Column("deleted_at") == nil)
                .order(Column("position").asc, Column("name").asc)
                .fetchAll(db)
        }
    }

    // MARK: - Writes

    /// Insert a single category. Caller supplies `id` (UUIDv7) and
    /// `position` so the Settings UI can drive ordering directly.
    /// Returns `false` if the (user_id, name) UNIQUE constraint
    /// already has this name — the row is left untouched.
    @discardableResult
    public func insert(
        id: String = Uuidv7.generate(),
        userId: String,
        name: String,
        position: Int
    ) async throws -> Bool {
        let now = IsoClock.nowIso()
        return try await dbQueue.write { db in
            do {
                try db.execute(sql: """
                    INSERT INTO categories (
                        id, user_id, name, position,
                        created_at, updated_at, dirty
                    ) VALUES (?, ?, ?, ?, ?, ?, 1)
                    """, arguments: [id, userId, name, position, now, now])
                return true
            } catch let error as DatabaseError where error.resultCode == .SQLITE_CONSTRAINT {
                return false
            }
        }
    }

    public func rename(id: String, newName: String) async throws {
        let now = IsoClock.nowIso()
        try await dbQueue.write { db in
            try db.execute(sql: """
                UPDATE categories
                SET name = ?, updated_at = ?, dirty = 1
                WHERE id = ?
                """, arguments: [newName, now, id])
        }
    }

    /// Rename a category and propagate the change to every capture
    /// row that references it by name. `captures.category` stores
    /// the value (not an FK), so a plain UPDATE on `categories.name`
    /// would orphan the historical tags otherwise. Both writes
    /// run in a single transaction; both rows are marked dirty for
    /// sync.
    public func renameAndPropagate(
        id: String,
        oldName: String,
        newName: String,
        userId: String
    ) async throws {
        let now = IsoClock.nowIso()
        try await dbQueue.write { db in
            try db.execute(sql: """
                UPDATE categories
                SET name = ?, updated_at = ?, dirty = 1
                WHERE id = ?
                """, arguments: [newName, now, id])

            try db.execute(sql: """
                UPDATE captures
                SET category = ?, updated_at = ?, dirty = 1
                WHERE user_id = ? AND category = ?
                """, arguments: [newName, now, userId, oldName])
        }
    }

    /// Soft-delete: stamp `deleted_at` so the sync worker mirrors
    /// the tombstone to Drive on its next pass. Already-assigned
    /// `captures.category` strings keep working — captures.category
    /// is a value, not an FK.
    public func softDelete(id: String) async throws {
        let now = IsoClock.nowIso()
        try await dbQueue.write { db in
            try db.execute(sql: """
                UPDATE categories
                SET deleted_at = ?, updated_at = ?, dirty = 1
                WHERE id = ?
                """, arguments: [now, now, id])
        }
    }

    public func reorder(ids: [String]) async throws {
        let now = IsoClock.nowIso()
        try await dbQueue.write { db in
            for (index, id) in ids.enumerated() {
                try db.execute(sql: """
                    UPDATE categories
                    SET position = ?, updated_at = ?, dirty = 1
                    WHERE id = ?
                    """, arguments: [index, now, id])
            }
        }
    }

    /// Seed the default 6 categories if the user has no active rows.
    /// Idempotent — call from app launch or first sign-in. Honors
    /// the (user_id, name) UNIQUE constraint, so a partial earlier
    /// seed run is safe to retry.
    public func seedDefaultsIfEmpty(userId: String) async throws {
        let existing = try await listActive(userId: userId)
        guard existing.isEmpty else { return }
        for (index, name) in Self.defaultSeed.enumerated() {
            _ = try await insert(userId: userId, name: name, position: index)
        }
    }

    /// One-shot migration for users who were on a previous default
    /// seed that included "Study" (which has since been replaced by
    /// "Business Card"). Renames Study → Business Card in the
    /// categories table and retags any captures that referenced
    /// "Study" so the user's existing scans still group correctly.
    ///
    /// Guarded by a UserDefaults flag so it only runs once per
    /// install. The body itself is also idempotent — if Business
    /// Card already exists we soft-delete Study instead of trying to
    /// rename (the (user_id, name) UNIQUE constraint would refuse
    /// the rename otherwise).
    public func migrateLegacyStudyToBusinessCardIfNeeded(userId: String) async throws {
        let flagKey = "quickink.migrations.study-to-business-card-v1"
        let defaults = UserDefaults.standard
        guard !defaults.bool(forKey: flagKey) else { return }

        let now = IsoClock.nowIso()
        try await dbQueue.write { db in
            let studyExists = (try Int.fetchOne(db, sql: """
                SELECT COUNT(*) FROM categories
                WHERE user_id = ? AND name = 'Study' AND deleted_at IS NULL
                """, arguments: [userId]) ?? 0) > 0
            let businessCardExists = (try Int.fetchOne(db, sql: """
                SELECT COUNT(*) FROM categories
                WHERE user_id = ? AND name = 'Business Card' AND deleted_at IS NULL
                """, arguments: [userId]) ?? 0) > 0

            if studyExists && !businessCardExists {
                try db.execute(sql: """
                    UPDATE categories
                    SET name = 'Business Card', updated_at = ?, dirty = 1
                    WHERE user_id = ? AND name = 'Study' AND deleted_at IS NULL
                    """, arguments: [now, userId])
            } else if studyExists && businessCardExists {
                try db.execute(sql: """
                    UPDATE categories
                    SET deleted_at = ?, updated_at = ?, dirty = 1
                    WHERE user_id = ? AND name = 'Study' AND deleted_at IS NULL
                    """, arguments: [now, now, userId])
            }

            // Retag any captures still pointing at "Study" so they
            // continue to group with the renamed category. Runs even
            // when no category row was touched (covers the case
            // where the user deleted "Study" earlier but still has
            // older captures with that string in their `category`).
            try db.execute(sql: """
                UPDATE captures
                SET category = 'Business Card', updated_at = ?, dirty = 1
                WHERE user_id = ? AND category = 'Study' AND deleted_at IS NULL
                """, arguments: [now, userId])
        }
        defaults.set(true, forKey: flagKey)
    }
}
