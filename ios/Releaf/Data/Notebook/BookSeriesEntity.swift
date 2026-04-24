/*
 * BookSeriesEntity.swift
 *
 * GRDB record for the `book_series` table. A series groups notebook
 * rows (volumes) under a shared book name. Single-volume books
 * leave `series_id` NULL on the notebook row — no series row is
 * required until a second volume is added.
 *
 * Introduced in GRDB migration v3.
 */

import Foundation
import GRDB

public struct BookSeriesEntity: Codable, FetchableRecord, PersistableRecord,
                                 Identifiable, Equatable, Sendable {

    public static let databaseTableName = "book_series"

    public var id: String
    public var shelfId: String
    public var name: String
    public var createdAt: String
    public var updatedAt: String
    public var dirty: Bool
    public var deletedAt: String?

    public enum CodingKeys: String, CodingKey {
        case id
        case shelfId   = "shelf_id"
        case name
        case createdAt = "created_at"
        case updatedAt = "updated_at"
        case dirty
        case deletedAt = "deleted_at"
    }

    public init(
        id: String,
        shelfId: String,
        name: String,
        createdAt: String,
        updatedAt: String,
        dirty: Bool = true,
        deletedAt: String? = nil
    ) {
        self.id = id
        self.shelfId = shelfId
        self.name = name
        self.createdAt = createdAt
        self.updatedAt = updatedAt
        self.dirty = dirty
        self.deletedAt = deletedAt
    }
}
