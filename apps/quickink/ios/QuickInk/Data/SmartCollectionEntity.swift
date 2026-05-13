/*
 * SmartCollectionEntity.swift
 *
 * GRDB record for the `smart_collections` table — rule-based
 * saved views (e.g. "Needs review"). The rule lives in
 * `ruleJson` as an AND-of-clauses array (see
 * `SmartCollectionRule.swift` for the v1 grammar).
 *
 * Schema mirrors
 * `shared/design-system/migrations/quickink/v4_workspace.sql`
 * + the iOS v8_workspace migration in `QuickInkDatabase.swift`.
 *
 * Mirror of `SmartCollectionEntity.kt` in QuickInk's Android
 * target.
 */

import Foundation
import GRDB

public struct SmartCollectionEntity: Codable, FetchableRecord, PersistableRecord, Equatable, Sendable, Identifiable {

    public static let databaseTableName = "smart_collections"

    public var id: String
    public var userId: String
    public var name: String
    /// Tabler icon name (e.g. "ti-receipt", "ti-eye"). Stored as a
    /// string so the icon palette can grow without a migration.
    /// NULL → default sparkle icon.
    public var icon: String?
    /// Hex color for the icon background tint. NULL → accent-tint
    /// default.
    public var color: String?
    /// AND-of-clauses array as canonical JSON. See
    /// `SmartCollectionRule.decode` for the parser; the grammar
    /// itself is in the brief §3.
    public var ruleJson: String
    public var position: Int
    /// True for shipped seed collections ("Needs review" today).
    /// Lets us silently update the seeded ruleJson in a later
    /// release without overwriting user-created collections.
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
        icon: String? = nil,
        color: String? = nil,
        ruleJson: String,
        position: Int = 0,
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
        self.icon        = icon
        self.color       = color
        self.ruleJson    = ruleJson
        self.position    = position
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
        case icon
        case color
        case ruleJson    = "rule_json"
        case position
        case isSeeded    = "is_seeded"
        case driveFileId = "drive_file_id"
        case createdAt   = "created_at"
        case updatedAt   = "updated_at"
        case dirty
        case deletedAt   = "deleted_at"
    }
}
