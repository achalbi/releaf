/*
 * FolderRepository.swift
 *
 * GRDB-backed repository for the `folders` table — the "intent"
 * axis of the Workspace v1 two-axis IA. Wraps the operations the
 * Workspace home + folder-detail screens need:
 *   - list active folders (live observation)
 *   - create / rename / recolor / reorder / soft-delete
 *   - first-launch seed of "Unfiled"
 *   - one-time materialize of legacy `captures.category` rows
 *     into `capture_tags`
 *
 * Mirror of `FolderRepository.kt` in QuickInk's Android target.
 *
 * `folders.name` has a partial-unique-index on (user_id, name)
 * excluding tombstones (see v8_workspace), so callers should
 * treat name collisions as a no-op rather than retry.
 */

import Foundation
import Combine
import GRDB
import ReleafCoreData

public final class FolderRepository: @unchecked Sendable {

    /// Default folder name for the seeded Unfiled row.
    public static let defaultFolderName = "Unfiled"

    /// Neutral stone color — matches the design's "no judgement"
    /// tone for the default folder. User-created folders pick
    /// brighter accents from the palette.
    public static let defaultFolderColor = "#A8A29E"

    /// Source tag for capture_tags rows written by the v8 backfill
    /// pass (legacy `captures.category` → `capture_tags`). Distinct
    /// from "manual" / "ai-suggested" so analytics can ignore
    /// migration noise.
    public static let migrationSource = "migration"

    private let dbQueue: DatabaseQueue
    private let tagRepository: TagRepository

    public init(database: QuickInkDatabase = .shared) {
        self.dbQueue = database.dbQueue
        self.tagRepository = TagRepository(database: database)
    }

    // MARK: - Reads

    public func observe(userId: String) -> AnyPublisher<[FolderEntity], Error> {
        ValueObservation.tracking { db in
            try FolderEntity
                .filter(Column("user_id") == userId)
                .filter(Column("deleted_at") == nil)
                .order(Column("position").asc, Column("name").asc)
                .fetchAll(db)
        }
        .publisher(in: dbQueue)
        .eraseToAnyPublisher()
    }

    public func listActive(userId: String) async throws -> [FolderEntity] {
        try await dbQueue.read { db in
            try FolderEntity
                .filter(Column("user_id") == userId)
                .filter(Column("deleted_at") == nil)
                .order(Column("position").asc, Column("name").asc)
                .fetchAll(db)
        }
    }

    public func findDefault(userId: String) async throws -> FolderEntity? {
        try await dbQueue.read { db in
            try FolderEntity
                .filter(Column("user_id") == userId)
                .filter(Column("is_default") == true)
                .filter(Column("deleted_at") == nil)
                .fetchOne(db)
        }
    }

    // MARK: - Writes

    /// Create a user folder. Returns the inserted entity or `nil`
    /// on UNIQUE-name collision.
    @discardableResult
    public func create(
        userId: String,
        name: String,
        color: String,
        position: Int = Int.max
    ) async throws -> FolderEntity? {
        let id  = Uuidv7.generate()
        let now = IsoClock.nowIso()
        let entity = FolderEntity(
            id:        id,
            userId:    userId,
            name:      name,
            color:     color,
            position:  position,
            createdAt: now,
            updatedAt: now,
            dirty:     true,
        )
        return try await dbQueue.write { db in
            do {
                try entity.insert(db)
                return entity
            } catch let error as DatabaseError where error.resultCode == .SQLITE_CONSTRAINT {
                return nil
            }
        }
    }

    public func rename(id: String, newName: String) async throws {
        let now = IsoClock.nowIso()
        try await dbQueue.write { db in
            try db.execute(sql: """
                UPDATE folders
                SET name = ?, updated_at = ?, dirty = 1
                WHERE id = ?
                """, arguments: [newName, now, id])
        }
    }

    public func setColor(id: String, color: String) async throws {
        let now = IsoClock.nowIso()
        try await dbQueue.write { db in
            try db.execute(sql: """
                UPDATE folders
                SET color = ?, updated_at = ?, dirty = 1
                WHERE id = ?
                """, arguments: [color, now, id])
        }
    }

    public func reorder(ids: [String]) async throws {
        let now = IsoClock.nowIso()
        try await dbQueue.write { db in
            for (index, id) in ids.enumerated() {
                try db.execute(sql: """
                    UPDATE folders
                    SET position = ?, updated_at = ?, dirty = 1
                    WHERE id = ?
                    """, arguments: [index, now, id])
            }
        }
    }

    /// Soft-delete a folder. The captures inside are relocated to
    /// Unfiled first; the default folder itself is non-deletable
    /// (guarded by the SQL `is_default = 0` clause).
    public func softDelete(userId: String, folderId: String) async throws {
        guard let unfiled = try await findDefault(userId: userId) else { return }
        guard unfiled.id != folderId else { return }
        let now = IsoClock.nowIso()
        try await dbQueue.write { db in
            try db.execute(sql: """
                UPDATE captures
                SET folder_id = ?, updated_at = ?, dirty = 1
                WHERE folder_id = ? AND deleted_at IS NULL
                """, arguments: [unfiled.id, now, folderId])
            try db.execute(sql: """
                UPDATE folders
                SET deleted_at = ?, updated_at = ?, dirty = 1
                WHERE id = ? AND is_default = 0
                """, arguments: [now, now, folderId])
        }
    }

    // MARK: - First-launch seed + backfill

    /// Seed the default "Unfiled" folder if no `is_default` row
    /// exists for this user. Idempotent. Returns the (possibly
    /// pre-existing) default folder.
    @discardableResult
    public func seedDefaultsIfNeeded(userId: String) async throws -> FolderEntity {
        if let existing = try await findDefault(userId: userId) { return existing }
        let id  = Uuidv7.generate()
        let now = IsoClock.nowIso()
        let entity = FolderEntity(
            id:         id,
            userId:     userId,
            name:       Self.defaultFolderName,
            color:      Self.defaultFolderColor,
            position:   0,
            isDefault:  true,
            isShared:   false,
            createdAt:  now,
            updatedAt:  now,
            dirty:      true,
        )
        let inserted: Bool = try await dbQueue.write { db in
            do { try entity.insert(db); return true }
            catch let error as DatabaseError where error.resultCode == .SQLITE_CONSTRAINT { return false }
        }
        if inserted { return entity }
        // Race lost; re-fetch the row that won.
        guard let raced = try await findDefault(userId: userId) else {
            throw QuickInkRepositoryError.raceLost("seedDefaultsIfNeeded")
        }
        return raced
    }

    /// Backfill every capture with `folder_id IS NULL` to point at
    /// the seeded Unfiled folder. Idempotent via UserDefaults — the
    /// flag's per-user suffix lets the migration re-run cleanly for
    /// a different signed-in account.
    public func backfillFolderIdsIfNeeded(userId: String) async throws {
        let key = Self.backfillFolderIdsFlag(for: userId)
        let defaults = UserDefaults.standard
        guard !defaults.bool(forKey: key) else { return }

        let unfiled = try await seedDefaultsIfNeeded(userId: userId)
        let now = IsoClock.nowIso()
        try await dbQueue.write { db in
            try db.execute(sql: """
                UPDATE captures
                SET folder_id = ?, updated_at = ?, dirty = 1
                WHERE user_id = ?
                  AND folder_id IS NULL
                  AND deleted_at IS NULL
                """, arguments: [unfiled.id, now, userId])
        }
        defaults.set(true, forKey: key)
    }

    /// Materialize legacy `captures.category` values into
    /// `capture_tags` rows. Runs once per user via UserDefaults
    /// guard. After this completes the column is safe to drop
    /// (deferred to iOS A.3c).
    public func materializeCategoryToTagsIfNeeded(userId: String) async throws {
        let key = Self.materializeCategoryFlag(for: userId)
        let defaults = UserDefaults.standard
        guard !defaults.bool(forKey: key) else { return }

        struct Pair { let captureId: String; let categoryName: String }
        let pairs: [Pair] = try await dbQueue.read { db in
            try Row.fetchAll(db, sql: """
                SELECT id AS capture_id, category AS category_name
                FROM captures
                WHERE user_id = ?
                  AND category IS NOT NULL
                  AND deleted_at IS NULL
                """, arguments: [userId])
                .compactMap { row in
                    guard let cap: String = row["capture_id"],
                          let cat: String = row["category_name"]
                    else { return nil }
                    return Pair(captureId: cap, categoryName: cat)
                }
        }

        for pair in pairs {
            let tag = try await tagRepository.findOrCreate(
                userId: userId, name: pair.categoryName,
            )
            // Skip if a join row already exists for this pair —
            // the unique-active partial index would refuse another
            // anyway. Read once, write conditionally.
            let alreadyAttached: Bool = try await dbQueue.read { db in
                (try Int.fetchOne(db, sql: """
                    SELECT COUNT(*) FROM capture_tags
                    WHERE capture_id = ? AND tag_id = ?
                    """, arguments: [pair.captureId, tag.id]) ?? 0) > 0
            }
            if alreadyAttached { continue }

            let now = IsoClock.nowIso()
            let row = CaptureTagEntity(
                id:        Uuidv7.generate(),
                captureId: pair.captureId,
                tagId:     tag.id,
                source:    Self.migrationSource,
                createdAt: now,
                updatedAt: now,
                dirty:     true,
            )
            try await dbQueue.write { db in
                do { try row.insert(db) }
                catch let error as DatabaseError where error.resultCode == .SQLITE_CONSTRAINT {
                    // unique-active index — race with another writer.
                }
            }
        }

        defaults.set(true, forKey: key)
    }

    /// Convenience — calls seed + materialize + backfill in
    /// order. Safe to invoke on every launch; each step
    /// short-circuits when its work is done.
    public func runFirstLaunchMigrationIfNeeded(userId: String) async throws {
        _ = try await seedDefaultsIfNeeded(userId: userId)
        try await materializeCategoryToTagsIfNeeded(userId: userId)
        try await backfillFolderIdsIfNeeded(userId: userId)
    }

    private static func backfillFolderIdsFlag(for userId: String) -> String {
        "quickink.workspace.folder-backfill-v1.\(userId)"
    }
    private static func materializeCategoryFlag(for userId: String) -> String {
        "quickink.workspace.category-materialize-v1.\(userId)"
    }
}
