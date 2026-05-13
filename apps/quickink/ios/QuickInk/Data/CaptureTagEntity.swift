/*
 * CaptureTagEntity.swift
 *
 * GRDB record for the `capture_tags` many-to-many join — links
 * captures to tags. Each row syncs independently (own id + dirty
 * + tombstone trio) so a tag added on phone A reaches phone B
 * without re-syncing the entire capture.
 *
 * Schema mirrors
 * `shared/design-system/migrations/quickink/v4_workspace.sql`
 * + the iOS v8_workspace migration in `QuickInkDatabase.swift`.
 *
 * Mirror of `CaptureTagEntity.kt` in QuickInk's Android target.
 */

import Foundation
import GRDB

public struct CaptureTagEntity: Codable, FetchableRecord, PersistableRecord, Equatable, Sendable, Identifiable {

    public static let databaseTableName = "capture_tags"

    public var id: String
    public var captureId: String
    public var tagId: String
    /// Provenance of this tag assignment:
    ///   - `"manual"` — user typed/selected from the picker.
    ///   - `"ai-suggested"` — the auto-tagging heuristic (Phase E)
    ///     accepted by the user; surfaced visually identically to
    ///     manual once accepted but kept distinct here for analytics.
    ///   - `"migration"` — the v8 backfill row carrying the legacy
    ///     `captures.category` value into the join.
    public var source: String
    public var driveFileId: String?
    public var createdAt: String
    public var updatedAt: String
    public var dirty: Bool
    public var deletedAt: String?

    public init(
        id: String,
        captureId: String,
        tagId: String,
        source: String = "manual",
        driveFileId: String? = nil,
        createdAt: String,
        updatedAt: String,
        dirty: Bool = true,
        deletedAt: String? = nil
    ) {
        self.id          = id
        self.captureId   = captureId
        self.tagId       = tagId
        self.source      = source
        self.driveFileId = driveFileId
        self.createdAt   = createdAt
        self.updatedAt   = updatedAt
        self.dirty       = dirty
        self.deletedAt   = deletedAt
    }

    public enum CodingKeys: String, CodingKey {
        case id
        case captureId   = "capture_id"
        case tagId       = "tag_id"
        case source
        case driveFileId = "drive_file_id"
        case createdAt   = "created_at"
        case updatedAt   = "updated_at"
        case dirty
        case deletedAt   = "deleted_at"
    }
}

/// Projection for the per-tag document-count query — one row per
/// tag with active captures attached. Drives the home tag cloud
/// counts and the tag library cards' "N documents" subtitle.
public struct TagCount: Codable, FetchableRecord, Equatable, Sendable {
    public let tagId: String
    public let docCount: Int

    public enum CodingKeys: String, CodingKey {
        case tagId    = "tag_id"
        case docCount = "doc_count"
    }
}
