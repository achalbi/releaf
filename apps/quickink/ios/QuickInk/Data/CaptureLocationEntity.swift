/*
 * CaptureLocationEntity.swift
 *
 * GRDB record for the `capture_locations` many-to-many join — links
 * a capture to a user-defined location. Each row syncs independently
 * (own id + dirty + tombstone trio) so a location attached on phone
 * A reaches phone B without re-syncing the parent capture. Mirror of
 * `CaptureLocationEntity.kt`.
 */

import Foundation
import GRDB

public struct CaptureLocationEntity: Codable, FetchableRecord, PersistableRecord, Equatable, Sendable, Identifiable {

    public static let databaseTableName = "capture_locations"

    public var id: String
    public var captureId: String
    public var locationId: String
    public var source: String
    public var driveFileId: String?
    public var createdAt: String
    public var updatedAt: String
    public var dirty: Bool
    public var deletedAt: String?

    public init(
        id: String,
        captureId: String,
        locationId: String,
        source: String = "manual",
        driveFileId: String? = nil,
        createdAt: String,
        updatedAt: String,
        dirty: Bool = true,
        deletedAt: String? = nil
    ) {
        self.id          = id
        self.captureId   = captureId
        self.locationId  = locationId
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
        case locationId  = "location_id"
        case source
        case driveFileId = "drive_file_id"
        case createdAt   = "created_at"
        case updatedAt   = "updated_at"
        case dirty
        case deletedAt   = "deleted_at"
    }
}
