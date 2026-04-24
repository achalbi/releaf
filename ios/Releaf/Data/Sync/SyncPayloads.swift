/*
 * SyncPayloads.swift
 *
 * v2 Drive wire formats for iOS. Mirror of Android's `SyncPayloads.kt`
 * — the two emit byte-identical canonical JSON for identical row data.
 *
 * Shape rules:
 *   - snake_case keys matching SQL columns.
 *   - JSON-typed columns (contacts, locations, todos, attachments,
 *     sketch_strokes, sub_pages) land as embedded JSON values, not
 *     quoted strings. We use `JSONAny` (a thin wrapper over Any) to
 *     pass these through Codable.
 *   - Drive payloads never carry `dirty` or `drive_file_id`.
 *   - `conflict_stub` is local-only.
 */

import Foundation

// =====================================================================
// JSONAny — pass-through JSON value for Codable
// =====================================================================

/// Pass-through Codable wrapper for an embedded JSON value (array,
/// object, string, number, bool, null). Used for payload fields that
/// carry the on-disk JSON string verbatim.
public struct JSONAny: Codable, Equatable, Sendable {
    public let value: Any

    public init(_ value: Any) { self.value = value }

    public init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if container.decodeNil() {
            self.value = NSNull()
        } else if let b = try? container.decode(Bool.self) {
            self.value = b
        } else if let i = try? container.decode(Int64.self) {
            self.value = i
        } else if let d = try? container.decode(Double.self) {
            self.value = d
        } else if let s = try? container.decode(String.self) {
            self.value = s
        } else if let a = try? container.decode([JSONAny].self) {
            self.value = a.map(\.value)
        } else if let o = try? container.decode([String: JSONAny].self) {
            var m = [String: Any]()
            for (k, v) in o { m[k] = v.value }
            self.value = m
        } else {
            throw DecodingError.dataCorruptedError(
                in: container,
                debugDescription: "JSONAny: unsupported value"
            )
        }
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        try encodeAny(value, into: &container)
    }

    public static func == (lhs: JSONAny, rhs: JSONAny) -> Bool {
        // Round-trip compare via canonical JSON bytes.
        CanonicalJson.encodeToString(lhs.value) ==
            CanonicalJson.encodeToString(rhs.value)
    }

    /// Parse a raw JSON string into a JSONAny. On parse failure,
    /// returns an empty array — mirrors Android's behavior so the bad
    /// row uploads as `[]` rather than blocking the whole sync pass.
    public static func parseOrEmptyArray(_ raw: String?) -> JSONAny {
        guard let raw = raw, !raw.isEmpty,
              let data = raw.data(using: .utf8),
              let tree = try? JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed])
        else {
            return JSONAny([Any]())
        }
        return JSONAny(tree)
    }

    /// Re-serialize this tree into a compact JSON string for local
    /// column storage. Uses `sortedKeys` so repeated round-trips are
    /// idempotent.
    public func toCompactString() -> String {
        guard let data = try? JSONSerialization.data(
            withJSONObject: value,
            options: [.sortedKeys, .fragmentsAllowed]
        ) else {
            return "[]"
        }
        return String(data: data, encoding: .utf8) ?? "[]"
    }
}

/// Recursive encoder for a Foundation JSON tree.
private func encodeAny(_ any: Any, into container: inout SingleValueEncodingContainer) throws {
    if any is NSNull { try container.encodeNil(); return }

    switch any {
    case let b as Bool:     try container.encode(b)
    case let i as Int:      try container.encode(Int64(i))
    case let i as Int64:    try container.encode(i)
    case let d as Double:   try container.encode(d)
    case let s as String:   try container.encode(s)
    case let arr as [Any]:
        try container.encode(arr.map { JSONAny($0) })
    case let nsarr as NSArray:
        try container.encode(Array(nsarr).map { JSONAny($0) })
    case let dict as [String: Any]:
        try container.encode(dict.mapValues { JSONAny($0) })
    case let nsdict as NSDictionary:
        var m = [String: JSONAny]()
        for case let (k as String, v) in nsdict { m[k] = JSONAny(v) }
        try container.encode(m)
    case let n as NSNumber:
        let type = String(cString: n.objCType)
        if type == "c" || type == "B" {
            try container.encode(n.boolValue)
        } else {
            let d = n.doubleValue
            if d.truncatingRemainder(dividingBy: 1) == 0,
               d >= Double(Int64.min), d <= Double(Int64.max) {
                try container.encode(Int64(d))
            } else {
                try container.encode(d)
            }
        }
    default:
        try container.encodeNil()
    }
}

// =====================================================================
// Notebook
// =====================================================================

public struct NotebookPayloadV2: Codable, Equatable, Sendable {
    public let id: String
    public let title: String
    public let description: String?
    public let colorHex: String?
    public let position: Int64
    public let archivedAt: String?
    /// Shelf this book belongs to. Optional on the wire so old
    /// clients that don't yet send it can still round-trip; readers
    /// default absent rows onto the "shelf-general" shelf.
    public let shelfId: String?
    public let seriesId: String?
    public let volumeNumber: Int?
    public let volumeName: String?
    public let createdAt: String
    public let updatedAt: String

    public enum CodingKeys: String, CodingKey {
        case id, title, description
        case colorHex     = "color_hex"
        case position
        case archivedAt   = "archived_at"
        case shelfId      = "shelf_id"
        case seriesId     = "series_id"
        case volumeNumber = "volume_number"
        case volumeName   = "volume_name"
        case createdAt    = "created_at"
        case updatedAt    = "updated_at"
    }
}

public extension NotebookEntity {
    func toV2Payload() -> NotebookPayloadV2 {
        NotebookPayloadV2(
            id: id,
            title: title,
            description: nil, // iOS NotebookEntity has no description column yet
            colorHex: colorHex,
            position: position,
            archivedAt: nil,
            shelfId: shelfId,
            seriesId: seriesId,
            volumeNumber: volumeNumber,
            volumeName: volumeName,
            createdAt: createdAt,
            updatedAt: updatedAt
        )
    }
}

public extension NotebookPayloadV2 {
    func toEntity(driveFileId: String?) -> NotebookEntity {
        NotebookEntity(
            id: id,
            title: title,
            colorHex: colorHex,
            position: position,
            shelfId: shelfId ?? "shelf-general",
            seriesId: seriesId,
            volumeNumber: volumeNumber ?? 1,
            volumeName: volumeName,
            driveFileId: driveFileId,
            createdAt: createdAt,
            updatedAt: updatedAt,
            dirty: false,
            deletedAt: nil
        )
    }
}

// =====================================================================
// Shelf
// =====================================================================

public struct ShelfPayloadV2: Codable, Equatable, Sendable {
    public let id: String
    public let name: String
    public let colorHex: String?
    public let position: Int64
    public let createdAt: String
    public let updatedAt: String

    public enum CodingKeys: String, CodingKey {
        case id, name
        case colorHex  = "color_hex"
        case position
        case createdAt = "created_at"
        case updatedAt = "updated_at"
    }
}

public extension ShelfEntity {
    func toV2Payload() -> ShelfPayloadV2 {
        ShelfPayloadV2(
            id: id,
            name: name,
            colorHex: colorHex,
            position: position,
            createdAt: createdAt,
            updatedAt: updatedAt
        )
    }
}

public extension ShelfPayloadV2 {
    func toEntity() -> ShelfEntity {
        ShelfEntity(
            id: id,
            name: name,
            colorHex: colorHex,
            position: position,
            createdAt: createdAt,
            updatedAt: updatedAt,
            dirty: false,
            deletedAt: nil
        )
    }
}

// =====================================================================
// Book series
// =====================================================================

public struct BookSeriesPayloadV2: Codable, Equatable, Sendable {
    public let id: String
    public let shelfId: String
    public let name: String
    public let createdAt: String
    public let updatedAt: String

    public enum CodingKeys: String, CodingKey {
        case id
        case shelfId   = "shelf_id"
        case name
        case createdAt = "created_at"
        case updatedAt = "updated_at"
    }
}

public extension BookSeriesEntity {
    func toV2Payload() -> BookSeriesPayloadV2 {
        BookSeriesPayloadV2(
            id: id,
            shelfId: shelfId,
            name: name,
            createdAt: createdAt,
            updatedAt: updatedAt
        )
    }
}

public extension BookSeriesPayloadV2 {
    func toEntity() -> BookSeriesEntity {
        BookSeriesEntity(
            id: id,
            shelfId: shelfId,
            name: name,
            createdAt: createdAt,
            updatedAt: updatedAt,
            dirty: false,
            deletedAt: nil
        )
    }
}

// =====================================================================
// Chapter
// =====================================================================

public struct ChapterPayloadV2: Codable, Equatable, Sendable {
    public let id: String
    public let notebookId: String
    public let title: String
    public let description: String?
    public let position: Int64
    public let createdAt: String
    public let updatedAt: String

    public enum CodingKeys: String, CodingKey {
        case id
        case notebookId = "notebook_id"
        case title, description
        case position
        case createdAt = "created_at"
        case updatedAt = "updated_at"
    }
}

public extension ChapterEntity {
    func toV2Payload() -> ChapterPayloadV2 {
        ChapterPayloadV2(
            id: id,
            notebookId: notebookId,
            title: title,
            description: nil, // iOS ChapterEntity has no description column yet
            position: position,
            createdAt: createdAt,
            updatedAt: updatedAt
        )
    }
}

public extension ChapterPayloadV2 {
    func toEntity(driveFileId: String?) -> ChapterEntity {
        ChapterEntity(
            id: id,
            notebookId: notebookId,
            title: title,
            position: position,
            driveFileId: driveFileId,
            createdAt: createdAt,
            updatedAt: updatedAt,
            dirty: false,
            deletedAt: nil
        )
    }
}

// =====================================================================
// Page
// =====================================================================

public struct PagePayloadV2: Codable, Equatable, Sendable {
    public let id: String
    public let chapterId: String
    public let projectId: String?
    public let templateId: String?
    public let title: String?
    public let notes: String
    public let contacts: JSONAny
    public let locations: JSONAny
    public let todos: JSONAny
    public let attachments: JSONAny
    public let sketchStrokes: JSONAny
    public let subPages: JSONAny
    public let position: Int64
    public let createdAt: String
    public let updatedAt: String

    public enum CodingKeys: String, CodingKey {
        case id
        case chapterId     = "chapter_id"
        case projectId     = "project_id"
        case templateId    = "template_id"
        case title, notes, contacts, locations, todos, attachments
        case sketchStrokes = "sketch_strokes"
        case subPages      = "sub_pages"
        case position
        case createdAt     = "created_at"
        case updatedAt     = "updated_at"
    }
}

public extension PageEntity {
    func toV2Payload() -> PagePayloadV2 {
        PagePayloadV2(
            id: id,
            chapterId: chapterId,
            projectId: projectId,
            templateId: templateId,
            title: title,
            notes: notes,
            contacts: JSONAny.parseOrEmptyArray(contacts),
            locations: JSONAny.parseOrEmptyArray(locations),
            todos: JSONAny.parseOrEmptyArray(todos),
            attachments: JSONAny.parseOrEmptyArray(attachments),
            sketchStrokes: JSONAny([Any]()),
            subPages: JSONAny([Any]()),
            position: position,
            createdAt: createdAt,
            updatedAt: updatedAt
        )
    }
}

public extension PagePayloadV2 {
    func toEntity(driveFileId: String?) -> PageEntity {
        PageEntity(
            id: id,
            chapterId: chapterId,
            projectId: projectId,
            templateId: templateId,
            title: title,
            notes: notes,
            contacts: contacts.toCompactString(),
            locations: locations.toCompactString(),
            todos: todos.toCompactString(),
            attachments: attachments.toCompactString(),
            position: position,
            conflictStub: nil,
            driveFileId: driveFileId,
            createdAt: createdAt,
            updatedAt: updatedAt,
            dirty: false,
            deletedAt: nil
        )
    }
}

// =====================================================================
// NotepadEntry
// =====================================================================

public struct NotepadEntryPayloadV2: Codable, Equatable, Sendable {
    public let id: String
    public let userId: String
    public let entryDate: String
    public let projectId: String?
    public let title: String?
    public let notes: String
    public let contacts: JSONAny
    public let locations: JSONAny
    public let todos: JSONAny
    public let attachments: JSONAny
    public let sketchStrokes: JSONAny
    public let subPages: JSONAny
    public let allowBlankContent: Bool
    public let createdAt: String
    public let updatedAt: String

    public enum CodingKeys: String, CodingKey {
        case id
        case userId            = "user_id"
        case entryDate         = "entry_date"
        case projectId         = "project_id"
        case title, notes, contacts, locations, todos, attachments
        case sketchStrokes     = "sketch_strokes"
        case subPages          = "sub_pages"
        case allowBlankContent = "allow_blank_content"
        case createdAt         = "created_at"
        case updatedAt         = "updated_at"
    }
}

public extension NotepadEntry {
    func toV2Payload() -> NotepadEntryPayloadV2 {
        NotepadEntryPayloadV2(
            id: id,
            userId: userId,
            entryDate: entryDate,
            projectId: projectId,
            title: title,
            notes: notes,
            contacts: JSONAny.parseOrEmptyArray(contacts),
            locations: JSONAny.parseOrEmptyArray(locations),
            todos: JSONAny.parseOrEmptyArray(todos),
            attachments: JSONAny.parseOrEmptyArray(attachments),
            sketchStrokes: JSONAny([Any]()),
            subPages: JSONAny([Any]()),
            allowBlankContent: allowBlankContent,
            createdAt: createdAt,
            updatedAt: updatedAt
        )
    }
}

public extension NotepadEntryPayloadV2 {
    func toEntity(driveFileId: String?) -> NotepadEntry {
        NotepadEntry(
            id: id,
            userId: userId,
            entryDate: entryDate,
            projectId: projectId,
            title: title,
            notes: notes,
            contacts: contacts.toCompactString(),
            locations: locations.toCompactString(),
            todos: todos.toCompactString(),
            attachments: attachments.toCompactString(),
            allowBlankContent: allowBlankContent,
            conflictStub: nil,
            driveFileId: driveFileId,
            createdAt: createdAt,
            updatedAt: updatedAt,
            dirty: false,
            deletedAt: nil
        )
    }
}

// =====================================================================
// Task
// =====================================================================

public struct TaskPayloadV2: Codable, Equatable, Sendable {
    public let id: String
    public let userId: String
    public let title: String
    public let description: String?
    public let dueDate: String?
    public let completed: Bool
    public let completedAt: String?
    public let priority: Int
    public let createdAt: String
    public let updatedAt: String

    public enum CodingKeys: String, CodingKey {
        case id
        case userId      = "user_id"
        case title, description
        case dueDate     = "due_date"
        case completed
        case completedAt = "completed_at"
        case priority
        case createdAt   = "created_at"
        case updatedAt   = "updated_at"
    }
}

public extension TaskRecord {
    func toV2Payload() -> TaskPayloadV2 {
        TaskPayloadV2(
            id: id,
            userId: userId,
            title: title,
            description: description,
            dueDate: dueDate,
            completed: completed,
            completedAt: completedAt,
            priority: priority,
            createdAt: createdAt,
            updatedAt: updatedAt
        )
    }
}

public extension TaskPayloadV2 {
    func toEntity() -> TaskRecord {
        TaskRecord(
            id: id,
            userId: userId,
            title: title,
            description: description,
            dueDate: dueDate,
            completed: completed,
            completedAt: completedAt,
            priority: priority,
            createdAt: createdAt,
            updatedAt: updatedAt,
            dirty: false,
            deletedAt: nil
        )
    }
}
