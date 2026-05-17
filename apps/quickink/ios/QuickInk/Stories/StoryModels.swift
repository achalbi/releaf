/*
 * StoryModels.swift
 *
 * Phase 1 of the Stories feature — the row shapes for the `story` and
 * `story_item` tables created in QuickInkDatabase v14_stories.
 *
 * A `Story` is a curated narrative — a chosen subset of the user's
 * captures, photos, notes, and voice clips assembled into an ordered
 * sequence with optional inline text. A `StoryItem` is one entry in
 * that sequence; `kind` discriminates between a reference to an
 * existing capture/photo/note/voice-clip (`refId` carries the foreign
 * id) and an inline block (`text` carries the body).
 *
 * Date fields are stored as ISO-8601 strings (mirror of the rest of
 * the schema — see VoiceNoteEntity for the same pattern), generated
 * via `IsoClock.nowIso()`. Enums are stored as their `rawValue`
 * (lowercase TEXT) so the column type stays human-readable in a
 * SQLite browser.
 *
 * Mirror of Android `StoryEntity.kt` + `StoryItemEntity.kt`.
 */

import Foundation
import GRDB

// MARK: - Story

public struct Story: Codable, FetchableRecord, MutablePersistableRecord, Equatable, Identifiable {

    public var id: String
    public var userId: String
    public var title: String
    public var subtitle: String?
    public var coverItemId: String?
    public var coverStyle: String
    public var themeStyle: String
    public var groupingMode: String
    public var timeRangeStart: String?
    public var timeRangeEnd: String?
    public var status: String
    public var shareMode: String
    public var shareSlug: String?
    public var createdAt: String
    public var updatedAt: String
    public var dirty: Bool
    public var deletedAt: String?

    public static var databaseTableName: String { "story" }

    public enum CoverStyle: String { case photo, typographic, gradient }
    public enum ThemeStyle: String { case editorial, scrapbook, minimal }
    public enum GroupingMode: String { case timeline, activity, custom }
    public enum Status: String { case draft, published }
    public enum ShareMode: String {
        case `private`, publicLink = "public_link", inApp = "in_app", exported
    }

    public enum Columns {
        public static let id = Column(CodingKeys.id)
        public static let userId = Column(CodingKeys.userId)
        public static let title = Column(CodingKeys.title)
        public static let subtitle = Column(CodingKeys.subtitle)
        public static let coverItemId = Column(CodingKeys.coverItemId)
        public static let coverStyle = Column(CodingKeys.coverStyle)
        public static let themeStyle = Column(CodingKeys.themeStyle)
        public static let groupingMode = Column(CodingKeys.groupingMode)
        public static let timeRangeStart = Column(CodingKeys.timeRangeStart)
        public static let timeRangeEnd = Column(CodingKeys.timeRangeEnd)
        public static let status = Column(CodingKeys.status)
        public static let shareMode = Column(CodingKeys.shareMode)
        public static let shareSlug = Column(CodingKeys.shareSlug)
        public static let createdAt = Column(CodingKeys.createdAt)
        public static let updatedAt = Column(CodingKeys.updatedAt)
        public static let dirty = Column(CodingKeys.dirty)
        public static let deletedAt = Column(CodingKeys.deletedAt)
    }

    enum CodingKeys: String, CodingKey {
        case id
        case userId           = "user_id"
        case title
        case subtitle
        case coverItemId      = "cover_item_id"
        case coverStyle       = "cover_style"
        case themeStyle       = "theme_style"
        case groupingMode     = "grouping_mode"
        case timeRangeStart   = "time_range_start"
        case timeRangeEnd     = "time_range_end"
        case status
        case shareMode        = "share_mode"
        case shareSlug        = "share_slug"
        case createdAt        = "created_at"
        case updatedAt        = "updated_at"
        case dirty
        case deletedAt        = "deleted_at"
    }
}

// MARK: - StoryItem

public struct StoryItem: Codable, FetchableRecord, MutablePersistableRecord, Equatable, Identifiable {

    public var id: String
    public var storyId: String
    public var position: Int
    public var kind: String
    public var refId: String?
    public var text: String?
    public var caption: String?
    public var occurredAt: String?
    public var layout: String
    public var createdAt: String
    public var updatedAt: String
    public var dirty: Bool
    public var deletedAt: String?

    public static var databaseTableName: String { "story_item" }

    public enum Kind: String {
        case document, photo, note
        case voiceClip       = "voice_clip"
        case textBlock       = "text_block"
        case handwrittenNote = "handwritten_note"
        case dateDivider     = "date_divider"
        case placePin        = "place_pin"
    }
    public enum Layout: String { case full, half, grid }

    public enum Columns {
        public static let id = Column(CodingKeys.id)
        public static let storyId = Column(CodingKeys.storyId)
        public static let position = Column(CodingKeys.position)
        public static let kind = Column(CodingKeys.kind)
        public static let refId = Column(CodingKeys.refId)
        public static let text = Column(CodingKeys.text)
        public static let caption = Column(CodingKeys.caption)
        public static let occurredAt = Column(CodingKeys.occurredAt)
        public static let layout = Column(CodingKeys.layout)
        public static let createdAt = Column(CodingKeys.createdAt)
        public static let updatedAt = Column(CodingKeys.updatedAt)
        public static let dirty = Column(CodingKeys.dirty)
        public static let deletedAt = Column(CodingKeys.deletedAt)
    }

    enum CodingKeys: String, CodingKey {
        case id
        case storyId    = "story_id"
        case position
        case kind
        case refId      = "ref_id"
        case text
        case caption
        case occurredAt = "occurred_at"
        case layout
        case createdAt  = "created_at"
        case updatedAt  = "updated_at"
        case dirty
        case deletedAt  = "deleted_at"
    }
}

// MARK: - StoryVoiceClip

/// One row per inline voice clip attached to a `StoryItem` of
/// `kind = .voiceClip`. The .m4a lives in `AttachmentStorage` and the
/// row points at it through `audioUri`. Drive sync mirrors the
/// `voice_notes` precedent — two drive-id columns so transcript
/// edits don't re-upload the binary. See QuickInkDatabase v15.
public struct StoryVoiceClip: Codable, FetchableRecord, MutablePersistableRecord, Equatable {

    public var id: String
    public var storyItemId: String
    public var userId: String
    public var audioUri: String
    public var durationMs: Int
    public var transcription: String?
    public var transcriptionSource: String?
    public var driveFileId: String?
    public var audioDriveFileId: String?
    public var createdAt: String
    public var updatedAt: String
    public var dirty: Bool
    public var deletedAt: String?

    public static var databaseTableName: String { "story_voice_clip" }

    public enum Columns {
        public static let id = Column(CodingKeys.id)
        public static let storyItemId = Column(CodingKeys.storyItemId)
        public static let userId = Column(CodingKeys.userId)
        public static let audioUri = Column(CodingKeys.audioUri)
        public static let durationMs = Column(CodingKeys.durationMs)
        public static let transcription = Column(CodingKeys.transcription)
        public static let transcriptionSource = Column(CodingKeys.transcriptionSource)
        public static let driveFileId = Column(CodingKeys.driveFileId)
        public static let audioDriveFileId = Column(CodingKeys.audioDriveFileId)
        public static let createdAt = Column(CodingKeys.createdAt)
        public static let updatedAt = Column(CodingKeys.updatedAt)
        public static let dirty = Column(CodingKeys.dirty)
        public static let deletedAt = Column(CodingKeys.deletedAt)
    }

    enum CodingKeys: String, CodingKey {
        case id
        case storyItemId         = "story_item_id"
        case userId              = "user_id"
        case audioUri            = "audio_uri"
        case durationMs          = "duration_ms"
        case transcription
        case transcriptionSource = "transcription_source"
        case driveFileId         = "drive_file_id"
        case audioDriveFileId    = "audio_drive_file_id"
        case createdAt           = "created_at"
        case updatedAt           = "updated_at"
        case dirty
        case deletedAt           = "deleted_at"
    }
}

// MARK: - StorySuggestion (Phase 5 — ephemeral, not persisted in v3)

/// Single suggestion surfaced by the auto-grouping engine. Lives in
/// memory only — Phase 5 builds a process-scoped cache; v3 does not
/// persist suggestions to disk. See `STORIES_DESIGN.md` §8.
public struct StorySuggestion: Equatable {
    public let id: String
    public let reason: String
    public let candidateRefs: [String]
    public let score: Double
}
