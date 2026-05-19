/*
 * LocationEntity.swift
 *
 * GRDB record for the `locations` table — user-defined places ("Home",
 * "Work", "Cafe") that captures can optionally be tagged with.
 * Many-to-many to captures via `capture_locations`. Mirror of
 * Android's `LocationEntity.kt`.
 *
 * Surfaced as "Places" in the Home screen UI; the table + sync wire
 * format keep the legacy `location` kind for cross-platform parity.
 */

import Foundation
import GRDB

public struct LocationEntity: Codable, FetchableRecord, PersistableRecord, Equatable, Sendable, Identifiable {

    public static let databaseTableName = "locations"

    public var id: String
    public var userId: String
    public var name: String
    public var position: Int
    public var color: String?
    public var latitude: Double?
    public var longitude: Double?
    public var address: String?
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
        latitude: Double? = nil,
        longitude: Double? = nil,
        address: String? = nil,
        driveFileId: String? = nil,
        createdAt: String,
        updatedAt: String,
        dirty: Bool = true,
        deletedAt: String? = nil
    ) {
        self.id          = id
        self.userId      = userId
        self.name        = name
        self.position    = position
        self.color       = color
        self.latitude    = latitude
        self.longitude   = longitude
        self.address     = address
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
        case position
        case color
        case latitude
        case longitude
        case address
        case driveFileId = "drive_file_id"
        case createdAt   = "created_at"
        case updatedAt   = "updated_at"
        case dirty
        case deletedAt   = "deleted_at"
    }
}
