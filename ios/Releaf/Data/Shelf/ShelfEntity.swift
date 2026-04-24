/*
 * ShelfEntity.swift
 *
 * GRDB record for the `shelves` table — top of the Shelf → Book →
 * Chapter → Page hierarchy. Introduced in GRDB migration v3; existing
 * notebooks land on the default "General" shelf seeded by the same
 * migration.
 */

import Foundation
import GRDB

public struct ShelfEntity: Codable, FetchableRecord, PersistableRecord,
                           Identifiable, Equatable, Sendable {

    public static let databaseTableName = "shelves"

    public var id: String
    public var name: String
    /// Hex color (e.g. `#7AA874`) or nil for theme default.
    public var colorHex: String?
    /// Manual ordering hint. 1024-step spacing leaves room for
    /// re-ordering without renumbering neighbours.
    public var position: Int64
    public var createdAt: String
    public var updatedAt: String
    public var dirty: Bool
    public var deletedAt: String?

    public enum CodingKeys: String, CodingKey {
        case id
        case name
        case colorHex  = "color_hex"
        case position
        case createdAt = "created_at"
        case updatedAt = "updated_at"
        case dirty
        case deletedAt = "deleted_at"
    }

    public init(
        id: String,
        name: String,
        colorHex: String? = nil,
        position: Int64 = 1024,
        createdAt: String,
        updatedAt: String,
        dirty: Bool = true,
        deletedAt: String? = nil
    ) {
        self.id = id
        self.name = name
        self.colorHex = colorHex
        self.position = position
        self.createdAt = createdAt
        self.updatedAt = updatedAt
        self.dirty = dirty
        self.deletedAt = deletedAt
    }

    /// Stable id of the default "General" shelf seeded by migration.
    public static let defaultGeneralId: String = "shelf-general"
}
