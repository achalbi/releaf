/*
 * TagRepository.swift
 *
 * GRDB-backed repository for the `categories` table. Wraps the CRUD
 * operations Settings → Categories needs:
 *   - list active categories (live observation)
 *   - insert / rename / soft-delete / reorder
 *   - first-launch seeding of the default 6 names
 *
 * Mirror of `TagRepository.kt` in QuickInk's Android target.
 *
 * `categories.name` has a UNIQUE (user_id, name) constraint per the
 * v2 migration; callers should treat name collisions as a no-op (or
 * surface a localised error from the Settings UI) rather than retry.
 */

import Foundation
import Combine
import GRDB
import ReleafCoreData

/// Shared repository-side error type for QuickInk's data layer.
public enum QuickInkRepositoryError: Error {
    /// A find-or-create lost the race to a concurrent writer and
    /// the post-insert re-read still missed. Defensive — should
    /// never fire in practice.
    case raceLost(String)
}

public final class TagRepository: @unchecked Sendable {

    /// Default seed names rendered as the user's starting set. The
    /// canonical list per QuickInk's Phase 5 spec — preserved here
    /// (rather than e.g. `QuickInkDefaults`) so the seed lives next
    /// to the writer that consumes it. Names in this list are
    /// treated as system-managed: the Settings → Categories screen
    /// hides delete + rename affordances for them.
    /// `needs-review` joins the seed list in Workspace v1 so the
    /// seeded "Needs review" smart collection has a tag to
    /// reference.
    public static let defaultSeed: [String] = [
        "ideas", "projects", "meetings", "todo", "business-card", "journal",
        "needs-review",
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
    public func observe(userId: String) -> AnyPublisher<[TagEntity], Error> {
        ValueObservation.tracking { db in
            try TagEntity
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
    public func listActive(userId: String) async throws -> [TagEntity] {
        try await dbQueue.read { db in
            try TagEntity
                .filter(Column("user_id") == userId)
                .filter(Column("deleted_at") == nil)
                .order(Column("position").asc, Column("name").asc)
                .fetchAll(db)
        }
    }

    /// Look up a single active tag by name within a user's namespace.
    /// Used by the tag picker (dedupe) and the auto-tagging
    /// heuristic (Phase E).
    public func findByName(userId: String, name: String) async throws -> TagEntity? {
        try await dbQueue.read { db in
            try TagEntity
                .filter(Column("user_id") == userId)
                .filter(Column("name") == name)
                .filter(Column("deleted_at") == nil)
                .fetchOne(db)
        }
    }

    /// Find-or-create. Materialize and tag-picker callsites both
    /// pass through here. Race-safe: on UNIQUE collision we re-read
    /// and return the existing row.
    public func findOrCreate(userId: String, name: String) async throws -> TagEntity {
        if let hit = try await findByName(userId: userId, name: name) {
            return hit
        }
        let id  = Uuidv7.generate()
        let now = IsoClock.nowIso()
        let inserted = try await insert(
            id:       id,
            userId:   userId,
            name:     name,
            position: Int.max,
        )
        if inserted {
            return TagEntity(
                id:        id,
                userId:    userId,
                name:      name,
                position:  Int.max,
                color:     nil,
                createdAt: now,
                updatedAt: now,
                dirty:     true,
            )
        }
        // Race lost — another caller created it. Re-fetch.
        guard let raced = try await findByName(userId: userId, name: name) else {
            throw QuickInkRepositoryError.raceLost("findOrCreate(\(name))")
        }
        return raced
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
                    INSERT INTO tags (
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
                UPDATE tags
                SET name = ?, updated_at = ?, dirty = 1
                WHERE id = ?
                """, arguments: [newName, now, id])
        }
    }

    /// Rename a tag. Post-A.3c the per-capture primary label lives
    /// in `capture_tags` (which FKs the tag by id), so a rename
    /// propagates to every attached capture for free — no
    /// per-capture write needed. `oldName` and `userId` are now
    /// unused, retained only to preserve the signature for callers.
    public func renameAndPropagate(
        id: String,
        oldName: String,
        newName: String,
        userId: String
    ) async throws {
        _ = oldName
        _ = userId
        let now = IsoClock.nowIso()
        try await dbQueue.write { db in
            try db.execute(sql: """
                UPDATE tags
                SET name = ?, updated_at = ?, dirty = 1
                WHERE id = ?
                """, arguments: [newName, now, id])
        }
    }

    /// Soft-delete: stamp `deleted_at` so the sync worker mirrors
    /// the tombstone to Drive on its next pass. `capture_tags` rows
    /// referencing the tag stay intact (tombstoned tags still resolve
    /// by id); the tag just disappears from the active-tag list.
    public func softDelete(id: String) async throws {
        let now = IsoClock.nowIso()
        try await dbQueue.write { db in
            try db.execute(sql: """
                UPDATE tags
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
                    UPDATE tags
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

    /// One-shot migration that renames the legacy capitalized seed
    /// names ("Ideas", "Projects", "Meetings", "Todo", "Business
    /// Card", "Journal") to their kebab-case form so existing users
    /// land on the same canonical form as fresh seeds and the AI
    /// suggester chips. `capture_tags` rows reference the tag by id
    /// so the rename propagates without touching the join. The
    /// legacy `captures.category` column gets a matching update so
    /// any code still reading the dropped-but-present column groups
    /// captures consistently.
    ///
    /// Guarded by a UserDefaults flag so it only runs once per
    /// install. Body is defensive — if the kebab target already
    /// exists we soft-delete the capitalized row instead of trying
    /// to rename (the (user_id, name) UNIQUE constraint would
    /// refuse the rename otherwise).
    public func migrateLegacySeedNamesToKebabIfNeeded(userId: String) async throws {
        let flagKey = "quickink.migrations.seed-kebab-v1"
        let defaults = UserDefaults.standard
        guard !defaults.bool(forKey: flagKey) else { return }

        let renames: [(old: String, new: String)] = [
            ("Ideas",         "ideas"),
            ("Projects",      "projects"),
            ("Meetings",      "meetings"),
            ("Todo",          "todo"),
            ("Business Card", "business-card"),
            ("Journal",       "journal"),
        ]
        let now = IsoClock.nowIso()
        try await dbQueue.write { db in
            for (old, new) in renames {
                let oldExists = (try Int.fetchOne(db, sql: """
                    SELECT COUNT(*) FROM tags
                    WHERE user_id = ? AND name = ? AND deleted_at IS NULL
                    """, arguments: [userId, old]) ?? 0) > 0
                guard oldExists else { continue }
                let newExists = (try Int.fetchOne(db, sql: """
                    SELECT COUNT(*) FROM tags
                    WHERE user_id = ? AND name = ? AND deleted_at IS NULL
                    """, arguments: [userId, new]) ?? 0) > 0
                if !newExists {
                    try db.execute(sql: """
                        UPDATE tags
                        SET name = ?, updated_at = ?, dirty = 1
                        WHERE user_id = ? AND name = ? AND deleted_at IS NULL
                        """, arguments: [new, now, userId, old])
                } else {
                    try db.execute(sql: """
                        UPDATE tags
                        SET deleted_at = ?, updated_at = ?, dirty = 1
                        WHERE user_id = ? AND name = ? AND deleted_at IS NULL
                        """, arguments: [now, now, userId, old])
                }
                // Post-A.3c: capture_tags rows FK the tag by id, so
                // the tag rename above propagates without touching
                // any capture row.
            }
        }
        defaults.set(true, forKey: flagKey)
    }

    /// One-shot migration for users who were on a previous default
    /// seed that included "Study" (which has since been replaced by
    /// "business-card"). Renames Study → business-card in the tags
    /// table and retags any captures that referenced "Study" so the
    /// user's existing scans still group correctly.
    ///
    /// Guarded by a UserDefaults flag so it only runs once per
    /// install. The body itself is also idempotent — if business-card
    /// already exists we soft-delete Study instead of trying to
    /// rename (the (user_id, name) UNIQUE constraint would refuse
    /// the rename otherwise).
    public func migrateLegacyStudyToBusinessCardIfNeeded(userId: String) async throws {
        let flagKey = "quickink.migrations.study-to-business-card-v1"
        let defaults = UserDefaults.standard
        guard !defaults.bool(forKey: flagKey) else { return }

        let now = IsoClock.nowIso()
        try await dbQueue.write { db in
            let studyExists = (try Int.fetchOne(db, sql: """
                SELECT COUNT(*) FROM tags
                WHERE user_id = ? AND name = 'Study' AND deleted_at IS NULL
                """, arguments: [userId]) ?? 0) > 0
            let businessCardExists = (try Int.fetchOne(db, sql: """
                SELECT COUNT(*) FROM tags
                WHERE user_id = ? AND name = 'business-card' AND deleted_at IS NULL
                """, arguments: [userId]) ?? 0) > 0

            if studyExists && !businessCardExists {
                try db.execute(sql: """
                    UPDATE tags
                    SET name = 'business-card', updated_at = ?, dirty = 1
                    WHERE user_id = ? AND name = 'Study' AND deleted_at IS NULL
                    """, arguments: [now, userId])
            } else if studyExists && businessCardExists {
                try db.execute(sql: """
                    UPDATE tags
                    SET deleted_at = ?, updated_at = ?, dirty = 1
                    WHERE user_id = ? AND name = 'Study' AND deleted_at IS NULL
                    """, arguments: [now, now, userId])
            }
            // Post-A.3c: capture_tags rows FK the tag by id, so the
            // rename above propagates to every attached capture for
            // free — no per-capture write needed.
        }
        defaults.set(true, forKey: flagKey)
    }
}
