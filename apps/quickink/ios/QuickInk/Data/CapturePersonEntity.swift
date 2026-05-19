/*
 * CapturePersonEntity.swift
 *
 * GRDB record for the `capture_people` many-to-many join — links a
 * capture to a user-defined person. Mirror of
 * `CapturePersonEntity.kt`.
 */

import Foundation
import GRDB

public struct CapturePersonEntity: Codable, FetchableRecord, PersistableRecord, Equatable, Sendable, Identifiable {

    public static let databaseTableName = "capture_people"

    public var id: String
    public var captureId: String
    public var personId: String
    public var source: String
    public var driveFileId: String?
    public var createdAt: String
    public var updatedAt: String
    public var dirty: Bool
    public var deletedAt: String?

    public init(
        id: String,
        captureId: String,
        personId: String,
        source: String = "manual",
        driveFileId: String? = nil,
        createdAt: String,
        updatedAt: String,
        dirty: Bool = true,
        deletedAt: String? = nil
    ) {
        self.id          = id
        self.captureId   = captureId
        self.personId    = personId
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
        case personId    = "person_id"
        case source
        case driveFileId = "drive_file_id"
        case createdAt   = "created_at"
        case updatedAt   = "updated_at"
        case dirty
        case deletedAt   = "deleted_at"
    }
}
