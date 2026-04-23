/*
 * Manifest.swift
 *
 * v2 manifest wire format per `docs/DRIVE_SCHEMA.md` §`manifest.json`.
 * Mirror of Android's `Manifest.kt`.
 *
 * The manifest is the single source of truth for "what exists on
 * Drive and what its current checksum is." Sync compares local row
 * hashes against `entity_checksums[id].sha256` to decide what to
 * upload/pull; deletes propagate via `tombstones[id]`.
 *
 * Ordering invariant: the manifest is the last thing the sync worker
 * writes in a pass, so blobs are always durable before the index is
 * updated.
 */

import Foundation

public enum SchemaVersionConstants {
    public static let major = 2
    public static let minor = 0
    public static let migrationVersion = 1
}

public struct SchemaVersion: Codable, Equatable, Sendable {
    public let major: Int
    public let minor: Int

    public static let current = SchemaVersion(
        major: SchemaVersionConstants.major,
        minor: SchemaVersionConstants.minor
    )
}

public struct EntityChecksum: Codable, Equatable, Sendable {
    /// One of the `DrivePath.kind*` constants.
    public let kind: String
    /// Drive path relative to `Releaf/`.
    public let path: String
    /// Hex-lowercase SHA-256 of the canonical-JSON payload.
    public let sha256: String
    /// ISO-8601 UTC — entity's `updated_at` at checksum time.
    public let updatedAt: String

    public enum CodingKeys: String, CodingKey {
        case kind, path, sha256
        case updatedAt = "updated_at"
    }

    public init(kind: String, path: String, sha256: String, updatedAt: String) {
        self.kind = kind
        self.path = path
        self.sha256 = sha256
        self.updatedAt = updatedAt
    }
}

public struct TombstoneEntry: Codable, Equatable, Sendable {
    public let kind: String
    public let deletedAt: String
    public let deviceId: String
    public let hardDeleteAt: String?

    public enum CodingKeys: String, CodingKey {
        case kind
        case deletedAt    = "deleted_at"
        case deviceId     = "device_id"
        case hardDeleteAt = "hard_delete_at"
    }

    public init(kind: String, deletedAt: String, deviceId: String, hardDeleteAt: String? = nil) {
        self.kind = kind
        self.deletedAt = deletedAt
        self.deviceId = deviceId
        self.hardDeleteAt = hardDeleteAt
    }
}

public struct ManifestV2: Codable, Equatable, Sendable {
    public let schemaVersion: SchemaVersion
    public let migrationVersion: Int
    public let appVersion: String
    public let deviceId: String
    public let lastSyncAt: String
    public let clientGeneratedAt: String
    public let entityChecksums: [String: EntityChecksum]
    public let tombstones: [String: TombstoneEntry]

    public enum CodingKeys: String, CodingKey {
        case schemaVersion     = "schema_version"
        case migrationVersion  = "migration_version"
        case appVersion        = "app_version"
        case deviceId          = "device_id"
        case lastSyncAt        = "last_sync_at"
        case clientGeneratedAt = "client_generated_at"
        case entityChecksums   = "entity_checksums"
        case tombstones
    }

    public init(
        schemaVersion: SchemaVersion = .current,
        migrationVersion: Int = SchemaVersionConstants.migrationVersion,
        appVersion: String,
        deviceId: String,
        lastSyncAt: String,
        clientGeneratedAt: String,
        entityChecksums: [String: EntityChecksum] = [:],
        tombstones: [String: TombstoneEntry] = [:]
    ) {
        self.schemaVersion = schemaVersion
        self.migrationVersion = migrationVersion
        self.appVersion = appVersion
        self.deviceId = deviceId
        self.lastSyncAt = lastSyncAt
        self.clientGeneratedAt = clientGeneratedAt
        self.entityChecksums = entityChecksums
        self.tombstones = tombstones
    }
}

/// Payload of a `tombstones/{id}.json` file.
public struct TombstoneFile: Codable, Equatable, Sendable {
    public let id: String
    public let kind: String
    public let deletedAt: String
    public let deviceId: String
    public let hardDeleteAt: String?

    public enum CodingKeys: String, CodingKey {
        case id, kind
        case deletedAt    = "deleted_at"
        case deviceId     = "device_id"
        case hardDeleteAt = "hard_delete_at"
    }

    public init(id: String, kind: String, deletedAt: String, deviceId: String, hardDeleteAt: String? = nil) {
        self.id = id
        self.kind = kind
        self.deletedAt = deletedAt
        self.deviceId = deviceId
        self.hardDeleteAt = hardDeleteAt
    }
}
