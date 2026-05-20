/*
 * FolderEntity.swift
 *
 * GRDB record for the `folders` table — the "intent" axis of the
 * Workspace v1 two-axis IA. One row per user-defined folder.
 * Captures reference it via `captures.folder_id`. Folders are
 * color-coded, carry live-state badges in the UI ("3 new",
 * "needs review"), and are the (future) unit of sharing.
 *
 * Schema mirrors
 * `shared/design-system/migrations/quickink/v4_workspace.sql`
 * + the iOS v8_workspace migration in `QuickInkDatabase.swift`.
 *
 * Mirror of `FolderEntity.kt` in QuickInk's Android target.
 */

import Foundation
import GRDB

public struct FolderEntity: Codable, FetchableRecord, PersistableRecord, Equatable, Sendable, Identifiable {

    public static let databaseTableName = "folders"

    public var id: String
    public var userId: String
    public var name: String
    /// Hex color (e.g. "#E66943"). NOT NULL — every folder carries
    /// a visible identity in the Workspace home folder list. Seeded
    /// "Unsorted" uses a neutral stone color; user-created folders
    /// pick from the design's palette.
    public var color: String
    /// Caller-managed sort order — lower values render first.
    /// Seeded "Unsorted" gets position = 0.
    public var position: Int
    /// Reserved for the design's "covers" folder-visual mode
    /// (Milanote-style cover image). Out of scope for v1 ship.
    public var coverUri: String?
    /// Exactly one row per user has `isDefault = true`. That's the
    /// seeded "Unsorted" folder. Used to guard the UI from deleting
    /// it and to backfill captures when a user-folder is deleted
    /// (move-to-Unsorted, never cascade-delete).
    public var isDefault: Bool
    /// Reserved column for the post-v1 share flow. Defaults to
    /// false. Costs nothing to add now; saves a migration when
    /// sharing ships.
    public var isShared: Bool
    /// Behavioral type — "Inbox" / "Archive" / "Project" /
    /// "Reference" (`WORKSPACE_SPEC.md` §6). Seeded folders carry
    /// the spec'd value; user-created folders are NULL until they
    /// pick a type, at which point Phase B.1 lets them assign one.
    /// Added in v20_workspace_taxonomy.
    public var type: String?
    /// Presentation tier — 1 (Workflow), 2 (Life domains), 3
    /// (Creative & output); 0 (= Custom) is the visual bucket for
    /// user-created folders that coexist with the 12 seeded ones.
    /// Added in v20_workspace_taxonomy.
    public var tier: Int
    /// 1 for the 12 spec'd seeded folders (Inbox, Archive, Finance,
    /// …), 0 for user-created folders. Distinguishes the two without
    /// relying on the stable seed IDs at every read site. Added in
    /// v20_workspace_taxonomy.
    public var isSeeded: Bool
    public var driveFileId: String?
    public var createdAt: String
    public var updatedAt: String
    public var dirty: Bool
    public var deletedAt: String?

    public init(
        id: String,
        userId: String,
        name: String,
        color: String,
        position: Int,
        coverUri: String? = nil,
        isDefault: Bool = false,
        isShared: Bool = false,
        type: String? = nil,
        tier: Int = 0,
        isSeeded: Bool = false,
        driveFileId: String? = nil,
        createdAt: String,
        updatedAt: String,
        dirty: Bool = true,
        deletedAt: String? = nil
    ) {
        self.id          = id
        self.userId      = userId
        self.name        = name
        self.color       = color
        self.position    = position
        self.coverUri    = coverUri
        self.isDefault   = isDefault
        self.isShared    = isShared
        self.type        = type
        self.tier        = tier
        self.isSeeded    = isSeeded
        self.driveFileId = driveFileId
        self.createdAt   = createdAt
        self.updatedAt   = updatedAt
        self.dirty       = dirty
        self.deletedAt   = deletedAt
    }

    public enum CodingKeys: String, CodingKey {
        case id
        case userId      = "user_id"
        case name
        case color
        case position
        case coverUri    = "cover_uri"
        case isDefault   = "is_default"
        case isShared    = "is_shared"
        case type
        case tier
        case isSeeded    = "is_seeded"
        case driveFileId = "drive_file_id"
        case createdAt   = "created_at"
        case updatedAt   = "updated_at"
        case dirty
        case deletedAt   = "deleted_at"
    }
}
