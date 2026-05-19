/*
 * PersonEntity.swift
 *
 * GRDB record for the `people` table — user-defined people ("Me",
 * "Mom", "Dr. Rao") that captures can optionally be tagged with.
 * Many-to-many to captures via `capture_people`. Mirror of
 * `PersonEntity.kt`.
 *
 * Seeded with a single "Me" row on first launch
 * (PersonRepository.seedDefaultsIfEmpty).
 */

import Foundation
import GRDB

public struct PersonEntity: Codable, FetchableRecord, PersistableRecord, Equatable, Sendable, Identifiable {

    public static let databaseTableName = "people"

    public var id: String
    public var userId: String
    public var name: String
    public var position: Int
    public var color: String?

    /// Optional link to a device contact (CNContact identifier on iOS,
    /// ContactsContract lookup key on Android). Device-local; never
    /// sent to Drive.
    public var contactLookupKey: String?
    public var contactPhone: String?
    public var contactEmail: String?
    /// Cached photo URI (device-local). Never sent to Drive.
    public var contactPhotoUri: String?

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
        contactLookupKey: String? = nil,
        contactPhone: String? = nil,
        contactEmail: String? = nil,
        contactPhotoUri: String? = nil,
        driveFileId: String? = nil,
        createdAt: String,
        updatedAt: String,
        dirty: Bool = true,
        deletedAt: String? = nil
    ) {
        self.id               = id
        self.userId           = userId
        self.name             = name
        self.position         = position
        self.color            = color
        self.contactLookupKey = contactLookupKey
        self.contactPhone     = contactPhone
        self.contactEmail     = contactEmail
        self.contactPhotoUri  = contactPhotoUri
        self.driveFileId      = driveFileId
        self.createdAt        = createdAt
        self.updatedAt        = updatedAt
        self.dirty            = dirty
        self.deletedAt        = deletedAt
    }

    public enum CodingKeys: String, CodingKey {
        case id
        case userId           = "user_id"
        case name
        case position
        case color
        case contactLookupKey = "contact_lookup_key"
        case contactPhone     = "contact_phone"
        case contactEmail     = "contact_email"
        case contactPhotoUri  = "contact_photo_uri"
        case driveFileId      = "drive_file_id"
        case createdAt        = "created_at"
        case updatedAt        = "updated_at"
        case dirty
        case deletedAt        = "deleted_at"
    }
}
