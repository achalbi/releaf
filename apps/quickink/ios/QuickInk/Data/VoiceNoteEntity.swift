/*
 * VoiceNoteEntity.swift
 *
 * One row per voice note attached to a capture. Voice notes are
 * captured on the Document detail screen — the user taps "Voice
 * notes" → "Record", talks for up to two minutes, and the resulting
 * .m4a lands in AttachmentStorage with a row pointing at it. A
 * capture can have any number of voice notes; deleting the capture
 * cascades to its voice notes via the `ON DELETE CASCADE` foreign
 * key (mirror of how `ocr_results` rows attach to captures).
 *
 * Storage layout:
 *   - `audio_uri`     — file:// URL of the on-disk .m4a. AAC, 44.1kHz
 *                       mono, 96kbps. Cleaned up when the row is
 *                       hard-deleted.
 *   - `duration_ms`   — committed clip duration in milliseconds.
 *   - `transcription` — speech-to-text result. Nullable while we're
 *                       still recording or transcribing fails.
 *   - `drive_file_id` — Drive id of the uploaded .m4a after sync.
 *                       NULL until the next sync push completes.
 *
 * Mirror of Android's `VoiceNoteEntity` (Room) — column names match
 * byte-for-byte so the Drive sync payload round-trips identically
 * between platforms.
 */

import Foundation
import GRDB

public struct VoiceNoteEntity: Codable, FetchableRecord, MutablePersistableRecord, Equatable {

    public var id: String
    public var captureId: String
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

    public static var databaseTableName: String { "voice_notes" }

    public enum Columns {
        public static let id = Column(CodingKeys.id)
        public static let captureId = Column(CodingKeys.captureId)
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
        case captureId           = "capture_id"
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
