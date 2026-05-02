/*
 * CategoryEntity.swift
 *
 * GRDB record for the `categories` table — user-configurable tags
 * shown in the scan-review screen's picker and managed in
 * Settings → Categories. Schema mirrors
 * `shared/design-system/migrations/quickink/v2_capture_categories.sql`.
 *
 * Mirror of `CategoryEntity.kt` in QuickInk's Android target.
 */

import Foundation
import GRDB

public struct CategoryEntity: Codable, FetchableRecord, PersistableRecord, Equatable, Sendable {

    public static let databaseTableName = "categories"

    public var id: String
    public var userId: String
    public var name: String
    public var position: Int
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
        case driveFileId = "drive_file_id"
        case createdAt   = "created_at"
        case updatedAt   = "updated_at"
        case dirty
        case deletedAt   = "deleted_at"
    }
}
