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
    /// Shelf this book lives on. Required — every book belongs to
    /// exactly one shelf. Migrated rows land on `shelf-general`.
    public var shelfId: String
    /// Series this book is a volume of. Nil = single-volume book.
    public var seriesId: String?
    /// 1 for the only (or first) volume; 2+ for subsequent volumes.
    public var volumeNumber: Int
    /// Optional per-volume label (e.g. "2026"). UI composes
    /// "<series> vol <n>" when nil and the parent series has >1 volumes.
    public var volumeName: String?
    public var driveFileId: String?
    public var createdAt: String
    public var updatedAt: String
    /// `true` means "needs upload to Drive"; cleared by the sync worker.
    public var dirty: Bool
    /// ISO-8601 UTC when soft-deleted; nil = active.
    public var deletedAt: String?
    /// Soft-archive timestamp (separate from `deletedAt`). Added in
    /// v6 — non-nil means the notebook lives in the archive bin and
    /// is filtered out of the active notebook list.
    public var archivedAt: String?

    public enum CodingKeys: String, CodingKey {
        case id
        case title
        case colorHex     = "color_hex"
        case position
        case shelfId      = "shelf_id"
        case seriesId     = "series_id"
        case volumeNumber = "volume_number"
        case volumeName   = "volume_name"
        case driveFileId  = "drive_file_id"
        case createdAt    = "created_at"
        case updatedAt    = "updated_at"
        case dirty
        case deletedAt    = "deleted_at"
        case archivedAt   = "archived_at"
    }

    public init(
        id: String,
        title: String,
        colorHex: String? = nil,
        position: Int64 = 1024,
        shelfId: String = "shelf-general",
        seriesId: String? = nil,
        volumeNumber: Int = 1,
        volumeName: String? = nil,
        driveFileId: String? = nil,
        createdAt: String,
        updatedAt: String,
        dirty: Bool = true,
        deletedAt: String? = nil,
        archivedAt: String? = nil
    ) {
        self.id = id
        self.title = title
        self.colorHex = colorHex
        self.position = position
        self.shelfId = shelfId
        self.seriesId = seriesId
        self.volumeNumber = volumeNumber
        self.volumeName = volumeName
        self.driveFileId = driveFileId
        self.createdAt = createdAt
        self.updatedAt = updatedAt
        self.dirty = dirty
        self.deletedAt = deletedAt
        self.archivedAt = archivedAt
    }
}
