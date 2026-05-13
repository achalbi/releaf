/*
 * TagEntity.swift
 *
 * GRDB record for the `categories` table — user-configurable tags
 * shown in the scan-review screen's picker and managed in
 * Settings → Categories. Schema mirrors
 * `shared/design-system/migrations/quickink/v2_capture_categories.sql`.
 *
 * Mirror of `TagEntity.kt` in QuickInk's Android target.
 */

import Foundation
import GRDB

public struct TagEntity: Codable, FetchableRecord, PersistableRecord, Equatable, Sendable {

    /// Table renamed from `categories` → `tags` in v8_workspace.
    /// The Swift type name stays `TagEntity` for one more
    /// commit so the callsites can be migrated to `TagEntity`
    /// independently (iOS A.2).
    public static let databaseTableName = "tags"

    public var id: String
    public var userId: String
    public var name: String
    public var position: Int
    /// Workspace v1 — optional hex color for the tag chip
    /// (e.g. "#E66943"). NULL → UI falls back to the accent
    /// tint. Added in v8_workspace.
    public var color: String?
    public var driveFileId: String?
    public var createdAt: String
    public var updatedAt: String
    public var dirty: Bool
    public var deletedAt: String?

    public init(
        id: String,
        userId: String,
        name: String,
        position: Int,
        color: String? = nil,
        driveFileId: String? = nil,
        createdAt: String,
        updatedAt: String,
        dirty: Bool,
        deletedAt: String? = nil
    ) {
        self.id = id
        self.userId = userId
        self.name = name
        self.position = position
        self.color = color
        self.driveFileId = driveFileId
        self.createdAt = createdAt
        self.updatedAt = updatedAt
        self.dirty = dirty
        self.deletedAt = deletedAt
    }

    public enum CodingKeys: String, CodingKey {
        case id
        case userId      = "user_id"
        case name
        case position
        case color
        case driveFileId = "drive_file_id"
        case createdAt   = "created_at"
        case updatedAt   = "updated_at"
        case dirty
        case deletedAt   = "deleted_at"
    }
}
