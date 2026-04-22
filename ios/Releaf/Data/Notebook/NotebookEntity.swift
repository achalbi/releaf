/*
 * NotebookEntity.swift
 *
 * GRDB record for the `notebooks` table. Top of the
 * notebook → chapter → page hierarchy. Naming follows iOS's
 * `NotepadEntry` convention (entity-suffix only where the domain model
 * in `Data/Domain/Models.swift` already claims the unsuffixed name) —
 * `Notebook` lives in Domain as the Drive-facing type, `NotebookEntity`
 * here is the persistence shape.
 *
 * Columns match Android's `NotebookEntity.kt` and
 * `design-system/migrations/v1_initial.sql §notebooks`, byte for byte.
 */

import Foundation
import GRDB

public struct NotebookEntity: Codable, FetchableRecord, PersistableRecord,
                              Identifiable, Equatable, Sendable {

    public static let databaseTableName = "notebooks"

    public var id: String
    public var title: String
    /// Hex color (e.g. `#E77850`) or nil for the theme default.
    public var colorHex: String?
    /// Manual ordering hint. 1024-step spacing leaves room to re-order
    /// without renumbering neighbours.
    public var position: Int64
    public var driveFileId: String?
    public var createdAt: String
    public var updatedAt: String
    /// `true` means "needs upload to Drive"; cleared by the sync worker.
    public var dirty: Bool
    /// ISO-8601 UTC when soft-deleted; nil = active.
    public var deletedAt: String?

    public enum CodingKeys: String, CodingKey {
        case id
        case title
        case colorHex     = "color_hex"
        case position
        case driveFileId  = "drive_file_id"
        case createdAt    = "created_at"
        case updatedAt    = "updated_at"
        case dirty
        case deletedAt    = "deleted_at"
    }

    public init(
        id: String,
        title: String,
        colorHex: String? = nil,
        position: Int64 = 1024,
        driveFileId: String? = nil,
        createdAt: String,
        updatedAt: String,
        dirty: Bool = true,
        deletedAt: String? = nil
    ) {
        self.id = id
        self.title = title
        self.colorHex = colorHex
        self.position = position
        self.driveFileId = driveFileId
        self.createdAt = createdAt
        self.updatedAt = updatedAt
        self.dirty = dirty
        self.deletedAt = deletedAt
    }
}
