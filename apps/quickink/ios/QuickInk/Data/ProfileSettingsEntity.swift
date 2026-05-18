/*
 * ProfileSettingsEntity.swift
 *
 * GRDB record for the `profile_settings` table — the user's
 * profile-page state (display name override, phone, personality
 * line, transcription-language allowlist, profile photo). Single
 * row per user; the PK == user_id.
 *
 * Mirror of `ProfileSettingsEntity.kt` in QuickInk's Android target.
 * The column list matches 1:1 so the Drive JSON payload round-trips
 * cleanly between platforms via `ProfileSettingsPayloadV1`.
 *
 * Photo binary: `photoLocalUri` is the file:// path on THIS device
 * (`Documents/profile_photo.jpg`). Device-local, never sent to
 * Drive. The cross-device link is `photoDriveFileId` + `photoUpdatedAt`.
 *
 * Until v18_profile_settings landed, profile fields lived in
 * `UserDefaults` (`SettingsState`). `ProfileSettingsRepository`'s
 * bootstrap path migrates those legacy values into the new table
 * on first launch for users already past onboarding.
 */

import Foundation
import GRDB

public struct ProfileSettingsEntity: Codable, FetchableRecord, PersistableRecord, Equatable, Sendable, Identifiable {

    public static let databaseTableName = "profile_settings"

    /// Single-row PK. Equals `userId` (one profile per user).
    public var id: String

    public var userId: String

    /// User-set override of the auth-derived display name. Null
    /// means "use the auth-provider name."
    public var displayName: String?

    public var phoneNumber: String?

    public var personalityPunchline: String?

    /// Comma-separated BCP-47 codes the user picked for transcription
    /// (e.g. "en,hi,kn"). Null on rows synced from older clients or
    /// fresh installs; the transcriber treats null as "fall back to
    /// device locale + English."
    public var transcriptionLanguages: String?

    /// `file://` URI on THIS device. Not synced. Cross-device link
    /// is the (`photoDriveFileId`, `photoUpdatedAt`) pair.
    public var photoLocalUri: String?

    public var photoDriveFileId: String?

    /// Distinct from `updatedAt` (which moves any time any field
    /// changes) so the binary-restore step can decide whether its
    /// local file is stale without re-downloading on every metadata-
    /// only edit (e.g. display-name change).
    public var photoUpdatedAt: String?

    public var driveFileId: String?

    public var createdAt: String

    public var updatedAt: String

    public var dirty: Bool

    public var deletedAt: String?

    public init(
        id: String,
        userId: String,
        displayName: String? = nil,
        phoneNumber: String? = nil,
        personalityPunchline: String? = nil,
        transcriptionLanguages: String? = nil,
        photoLocalUri: String? = nil,
        photoDriveFileId: String? = nil,
        photoUpdatedAt: String? = nil,
        driveFileId: String? = nil,
        createdAt: String,
        updatedAt: String,
        dirty: Bool,
        deletedAt: String? = nil
    ) {
        self.id = id
        self.userId = userId
        self.displayName = displayName
        self.phoneNumber = phoneNumber
        self.personalityPunchline = personalityPunchline
        self.transcriptionLanguages = transcriptionLanguages
        self.photoLocalUri = photoLocalUri
        self.photoDriveFileId = photoDriveFileId
        self.photoUpdatedAt = photoUpdatedAt
        self.driveFileId = driveFileId
        self.createdAt = createdAt
        self.updatedAt = updatedAt
        self.dirty = dirty
        self.deletedAt = deletedAt
    }

    public enum CodingKeys: String, CodingKey {
        case id
        case userId                 = "user_id"
        case displayName            = "display_name"
        case phoneNumber            = "phone_number"
        case personalityPunchline   = "personality_punchline"
        case transcriptionLanguages = "transcription_languages"
        case photoLocalUri          = "photo_local_uri"
        case photoDriveFileId       = "photo_drive_file_id"
        case photoUpdatedAt         = "photo_updated_at"
        case driveFileId            = "drive_file_id"
        case createdAt              = "created_at"
        case updatedAt              = "updated_at"
        case dirty
        case deletedAt              = "deleted_at"
    }
}
