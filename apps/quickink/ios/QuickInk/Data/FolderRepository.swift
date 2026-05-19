/*
 * FolderRepository.swift
 *
 * GRDB-backed repository for the `folders` table — the "intent"
 * axis of the Workspace v1 two-axis IA. Wraps the operations the
 * Workspace home + folder-detail screens need:
 *   - list active folders (live observation)
 *   - create / rename / recolor / reorder / soft-delete
 *   - first-launch seed of "Unsorted" (renamed from "Unfiled" — the
 *     seed also migrates any pre-existing default row to the new name)
 *
 * The legacy `captures.category` → `capture_tags` materialize step
 * shipped in v8's first-launch pass; post-A.3c the column itself
 * is gone (the v9 schema migration carries the materialize +
 * drop atomically), so the helper is gone from this file.
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

    /// Default folder name for the seeded Unsorted row.
    public static let defaultFolderName = "Unsorted"

    /// Prior seed name. Existing installs have a default folder row
    /// with this name; `seedDefaultsIfNeeded` migrates it on next launch.
    private static let legacyDefaultFolderName = "Unfiled"

    /// Neutral stone color — matches the design's "no judgement"
    /// tone for the default folder. User-created folders pick
    /// brighter accents from the palette.
    public static let defaultFolderColor = "#A8A29E"

    private let dbQueue: DatabaseQueue

    public init(database: QuickInkDatabase = .shared) {
        self.dbQueue = database.dbQueue
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
    /// the default folder (Unsorted) first; the default folder itself
    /// is non-deletable (guarded by the SQL `is_default = 0` clause).
    public func softDelete(userId: String, folderId: String) async throws {
        guard let defaultFolder = try await findDefault(userId: userId) else { return }
        guard defaultFolder.id != folderId else { return }
        let now = IsoClock.nowIso()
        try await dbQueue.write { db in
            try db.execute(sql: """
                UPDATE captures
                SET folder_id = ?, updated_at = ?, dirty = 1
                WHERE folder_id = ? AND deleted_at IS NULL
                """, arguments: [defaultFolder.id, now, folderId])
            try db.execute(sql: """
                UPDATE folders
                SET deleted_at = ?, updated_at = ?, dirty = 1
                WHERE id = ? AND is_default = 0
                """, arguments: [now, now, folderId])
        }
    }

    // MARK: - First-launch seed + backfill

    /// Seed the default "Unsorted" folder if no `is_default` row
    /// exists for this user. Idempotent. Returns the (possibly
    /// pre-existing) default folder. Also migrates a legacy default
    /// row named "Unfiled" to the current name on next launch; a
    /// UNIQUE collision (user has another folder already named
    /// "Unsorted") leaves the row as-is.
    @discardableResult
    public func seedDefaultsIfNeeded(userId: String) async throws -> FolderEntity {
        if let existing = try await findDefault(userId: userId) {
            guard existing.name == Self.legacyDefaultFolderName else { return existing }
            let now = IsoClock.nowIso()
            let renamed: Bool = try await dbQueue.write { db in
                do {
                    try db.execute(sql: """
                        UPDATE folders
                        SET name = ?, updated_at = ?, dirty = 1
                        WHERE id = ?
                        """, arguments: [Self.defaultFolderName, now, existing.id])
                    return true
                } catch let error as DatabaseError where error.resultCode == .SQLITE_CONSTRAINT {
                    return false
                }
            }
            return renamed
                ? (try await findDefault(userId: userId) ?? existing)
                : existing
        }
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
    /// the seeded default folder. Idempotent via UserDefaults — the
    /// flag's per-user suffix lets the migration re-run cleanly for
    /// a different signed-in account.
    public func backfillFolderIdsIfNeeded(userId: String) async throws {
        let key = Self.backfillFolderIdsFlag(for: userId)
        let defaults = UserDefaults.standard
        guard !defaults.bool(forKey: key) else { return }

        let defaultFolder = try await seedDefaultsIfNeeded(userId: userId)
        let now = IsoClock.nowIso()
        try await dbQueue.write { db in
            try db.execute(sql: """
                UPDATE captures
                SET folder_id = ?, updated_at = ?, dirty = 1
                WHERE user_id = ?
                  AND folder_id IS NULL
                  AND deleted_at IS NULL
                """, arguments: [defaultFolder.id, now, userId])
        }
        defaults.set(true, forKey: key)
    }

    /// Convenience — calls seed + backfill in order. Safe to invoke
    /// on every launch; each step short-circuits when its work is
    /// done. The legacy materialize step is gone post-A.3c — the
    /// v9 schema migration carries the materialize + column drop
    /// atomically before any app code runs.
    public func runFirstLaunchMigrationIfNeeded(userId: String) async throws {
        _ = try await seedDefaultsIfNeeded(userId: userId)
        try await backfillFolderIdsIfNeeded(userId: userId)
    }

    private static func backfillFolderIdsFlag(for userId: String) -> String {
        "quickink.workspace.folder-backfill-v1.\(userId)"
    }
}
