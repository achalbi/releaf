/*
 * PageEntity.swift
 *
 * GRDB record for the `pages` table — the authored leaf of the
 * notebook → chapter → page hierarchy. Carries the markdown body that
 * `fts_page_notes` indexes, plus the four feature-section JSON columns
 * (contacts, locations, todos, attachments) that match NotepadEntry.
 *
 * `Page` is claimed by the Drive-facing domain type in
 * `Data/Domain/Models.swift`, so this struct uses the `Entity` suffix
 * to avoid a duplicate declaration in the `ReleafData` module.
 */

import Foundation
import GRDB

public struct PageEntity: Codable, FetchableRecord, PersistableRecord,
                          Identifiable, Equatable, Sendable {

    public static let databaseTableName = "pages"

    public var id: String
    public var chapterId: String
    public var projectId: String?
    public var templateId: String?
    /// Nullable — the UI falls back to a derived default when absent.
    public var title: String?
    /// Canonical CommonMark. Empty string is valid.
    public var notes: String
    /// JSON array of `NotepadContact` (same shape as notepad). Never nil; `"[]"` default.
    public var contacts: String
    /// JSON array of `GeoLocation`. Never nil; `"[]"` default.
    public var locations: String
    /// JSON array of `NotepadTodo`. Never nil; `"[]"` default.
    public var todos: String
    /// JSON array of `Attachment` (photo/scan manifests). Never nil.
    public var attachments: String
    public var position: Int64
    /// JSON `{local_notes, remote_notes, remote_updated_at}` or nil.
    public var conflictStub: String?
    public var driveFileId: String?
    public var createdAt: String
    public var updatedAt: String
    public var dirty: Bool
    public var deletedAt: String?

    public enum CodingKeys: String, CodingKey {
        case id
        case chapterId    = "chapter_id"
        case projectId    = "project_id"
        case templateId   = "template_id"
        case title
        case notes
        case contacts
        case locations
        case todos
        case attachments
        case position
        case conflictStub = "conflict_stub"
        case driveFileId  = "drive_file_id"
        case createdAt    = "created_at"
        case updatedAt    = "updated_at"
        case dirty
        case deletedAt    = "deleted_at"
    }

    public init(
        id: String,
        chapterId: String,
        projectId: String? = nil,
        templateId: String? = nil,
        title: String? = nil,
        notes: String = "",
        contacts: String = "[]",
        locations: String = "[]",
        todos: String = "[]",
        attachments: String = "[]",
        position: Int64 = 1024,
        conflictStub: String? = nil,
        driveFileId: String? = nil,
        createdAt: String,
        updatedAt: String,
        dirty: Bool = true,
        deletedAt: String? = nil
    ) {
        self.id = id
        self.chapterId = chapterId
        self.projectId = projectId
        self.templateId = templateId
        self.title = title
        self.notes = notes
        self.contacts = contacts
        self.locations = locations
        self.todos = todos
        self.attachments = attachments
        self.position = position
        self.conflictStub = conflictStub
        self.driveFileId = driveFileId
        self.createdAt = createdAt
        self.updatedAt = updatedAt
        self.dirty = dirty
        self.deletedAt = deletedAt
    }
}
