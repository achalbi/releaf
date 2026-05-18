/*
 * ProfileSettingsRepository.swift
 *
 * GRDB-backed repository for the `profile_settings` table. Mirror of
 * Android's `ProfileSettingsDao` — same surface (observe, upsert,
 * per-field setters, dirty/markSynced) so the cross-platform sync
 * round-trip stays symmetric.
 *
 * One row per user, keyed by `id == userId`.
 *
 * Bootstrap: before v18_profile_settings landed, iOS kept the user's
 * display name / phone / punchline / photo in `UserDefaults` only
 * (no sync). `bootstrapFromLegacyUserDefaults` reads those values
 * on first launch and seeds the new GRDB row with them, marked
 * dirty so the next sync pass pushes them up to Drive. Idempotent —
 * if the row already exists, it's left alone.
 */

import Foundation
import Combine
import GRDB
import ReleafCoreData

public final class ProfileSettingsRepository: @unchecked Sendable {

    private let dbQueue: DatabaseQueue

    public init(database: QuickInkDatabase = .shared) {
        self.dbQueue = database.dbQueue
    }

    // MARK: - Reads

    /// Live publisher of the single row for `userId`. Emits `nil`
    /// while no row exists (fresh sign-in before bootstrap has run).
    public func observe(userId: String) -> AnyPublisher<ProfileSettingsEntity?, Error> {
        ValueObservation.tracking { db in
            try ProfileSettingsEntity
                .filter(Column("user_id") == userId)
                .filter(Column("deleted_at") == nil)
                .fetchOne(db)
        }
        .publisher(in: dbQueue)
        .eraseToAnyPublisher()
    }

    /// One-shot fetch — used by the bootstrap path and by callers
    /// that want a synchronous-equivalent read.
    public func find(userId: String) async throws -> ProfileSettingsEntity? {
        try await dbQueue.read { db in
            try ProfileSettingsEntity
                .filter(Column("user_id") == userId)
                .filter(Column("deleted_at") == nil)
                .fetchOne(db)
        }
    }

    // MARK: - Writes

    /// Bootstrap path. If a row for `userId` already exists, no-op.
    /// Otherwise insert one with the supplied legacy `UserDefaults`
    /// values, marked dirty so the next sync push uploads it. Called
    /// from `SettingsState`'s first-launch path so users upgrading
    /// from a pre-v18 build don't lose their profile fields.
    public func bootstrapFromLegacyUserDefaults(
        userId: String,
        customDisplayName: String?,
        phoneNumber: String?,
        personalityPunchline: String?,
        profilePhotoUri: String?
    ) async throws {
        if userId.isEmpty { return }
        if try await find(userId: userId) != nil { return }

        let now = IsoClock.nowIso()
        let row = ProfileSettingsEntity(
            id:                     userId,
            userId:                 userId,
            displayName:            customDisplayName?.nilIfBlank,
            phoneNumber:            phoneNumber?.nilIfBlank,
            personalityPunchline:   personalityPunchline?.nilIfBlank,
            transcriptionLanguages: nil,
            photoLocalUri:          profilePhotoUri?.nilIfBlank,
            photoDriveFileId:       nil,
            photoUpdatedAt:         (profilePhotoUri?.nilIfBlank != nil) ? now : nil,
            driveFileId:            nil,
            createdAt:              now,
            updatedAt:              now,
            dirty:                  true,
            deletedAt:              nil
        )
        try await dbQueue.write { db in
            var inserted = row
            try inserted.insert(db)
        }
    }

    /// Local-write upsert. Always writes — caller has already
    /// stamped `updated_at` and `dirty = 1`. Used by the per-field
    /// setters; can also be called from external bootstrap code
    /// (e.g. ProfileScreen seed) for the same shape as Android's
    /// `upsertLocal`.
    public func upsertLocal(_ entity: ProfileSettingsEntity) async throws {
        try await dbQueue.write { db in
            try entity.save(db)
        }
    }

    /// Per-field setter for display name. Treats blank as "clear the
    /// override" — null in the column, so the resolver falls back to
    /// the auth-provider name. Bumps `updated_at` + `dirty`.
    public func setDisplayName(userId: String, value: String?) async throws {
        try await ensureRow(userId: userId)
        let now = IsoClock.nowIso()
        try await dbQueue.write { db in
            try db.execute(sql: """
                UPDATE profile_settings
                SET display_name = ?, updated_at = ?, dirty = 1
                WHERE user_id = ?
                """, arguments: [value?.nilIfBlank, now, userId])
        }
    }

    public func setPhoneNumber(userId: String, value: String?) async throws {
        try await ensureRow(userId: userId)
        let now = IsoClock.nowIso()
        try await dbQueue.write { db in
            try db.execute(sql: """
                UPDATE profile_settings
                SET phone_number = ?, updated_at = ?, dirty = 1
                WHERE user_id = ?
                """, arguments: [value?.nilIfBlank, now, userId])
        }
    }

    public func setPersonalityPunchline(userId: String, value: String?) async throws {
        try await ensureRow(userId: userId)
        let now = IsoClock.nowIso()
        try await dbQueue.write { db in
            try db.execute(sql: """
                UPDATE profile_settings
                SET personality_punchline = ?, updated_at = ?, dirty = 1
                WHERE user_id = ?
                """, arguments: [value?.nilIfBlank, now, userId])
        }
    }

    /// Set the transcription-language allowlist. `codes` is a comma-
    /// separated BCP-47 string (e.g. "en,hi,kn"); pass `nil` (or an
    /// empty list) to clear and fall back to "device locale +
    /// English" at read time.
    public func setTranscriptionLanguages(userId: String, codes: String?) async throws {
        try await ensureRow(userId: userId)
        let now = IsoClock.nowIso()
        try await dbQueue.write { db in
            try db.execute(sql: """
                UPDATE profile_settings
                SET transcription_languages = ?, updated_at = ?, dirty = 1
                WHERE user_id = ?
                """, arguments: [codes?.nilIfBlank, now, userId])
        }
    }

    /// Photo binary changed — stamps `photo_updated_at` separately
    /// so the restore worker can spot stale local copies without
    /// re-downloading on every metadata-only edit. Mirror of
    /// Android's `ProfileSettingsDao.setPhoto`.
    public func setPhoto(userId: String, localUri: String?) async throws {
        try await ensureRow(userId: userId)
        let now = IsoClock.nowIso()
        try await dbQueue.write { db in
            try db.execute(sql: """
                UPDATE profile_settings
                SET photo_local_uri     = ?,
                    photo_updated_at    = ?,
                    photo_drive_file_id = NULL,
                    updated_at          = ?,
                    dirty               = 1
                WHERE user_id = ?
                """, arguments: [localUri?.nilIfBlank, now, now, userId])
        }
    }

    // MARK: - Helpers

    /// Make sure a row exists for `userId` before a per-field setter
    /// fires. The bootstrap path normally lands the row first, but
    /// this defensive seed handles the fresh-sign-in window where a
    /// setter races bootstrap.
    private func ensureRow(userId: String) async throws {
        if userId.isEmpty { return }
        if try await find(userId: userId) != nil { return }
        let now = IsoClock.nowIso()
        let row = ProfileSettingsEntity(
            id:        userId,
            userId:    userId,
            createdAt: now,
            updatedAt: now,
            dirty:     true
        )
        try await dbQueue.write { db in
            var seed = row
            try seed.insert(db)
        }
    }
}

private extension String {
    /// `self` with leading/trailing whitespace trimmed; `nil` if the
    /// trimmed result is empty. Centralises the "blank input = clear
    /// the override" rule used by every per-field setter.
    var nilIfBlank: String? {
        let trimmed = self.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
}
