/*
 * ChapterEntity.swift
 *
 * GRDB record for the `chapters` table. Chapters are the middle tier
 * in the notebook → chapter → page hierarchy — each belongs to exactly
 * one notebook and groups a linear sequence of pages.
 *
 * Like `NotebookEntity`, the unsuffixed `Chapter` type lives in
 * `Data/Domain/Models.swift` as the Drive-facing shape; this struct is
 * the persistence shape.
 */

import Foundation
import GRDB

public struct ChapterEntity: Codable, FetchableRecord, PersistableRecord,
                             Identifiable, Equatable, Sendable {

    public static let databaseTableName = "chapters"

    public var id: String
    public var notebookId: String
    public var title: String
    public var position: Int64
    public var driveFileId: String?
    public var createdAt: String
    public var updatedAt: String
    public var dirty: Bool
    public var deletedAt: String?

    public enum CodingKeys: String, CodingKey {
        case id
        case notebookId   = "notebook_id"
        case title
        case position
        case driveFileId  = "drive_file_id"
        case createdAt    = "created_at"
        case updatedAt    = "updated_at"
        case dirty
        case deletedAt    = "deleted_at"
    }

    public init(
        id: String,
        notebookId: String,
        title: String,
        position: Int64 = 1024,
        driveFileId: String? = nil,
        createdAt: String,
        updatedAt: String,
        dirty: Bool = true,
        deletedAt: String? = nil
    ) {
        self.id = id
        self.notebookId = notebookId
        self.title = title
        self.position = position
        self.driveFileId = driveFileId
        self.createdAt = createdAt
        self.updatedAt = updatedAt
        self.dirty = dirty
        self.deletedAt = deletedAt
    }
}
