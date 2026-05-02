/*
 * QuickInkSyncPayloads.swift
 *
 * Canonical-JSON wire shapes for QuickInk's three synced entity
 * kinds: notepad_entries, captures, ocr_results. Mirror of
 * `apps/releaf/ios/Releaf/Data/Sync/SyncPayloads.swift` for the
 * relevant subset.
 *
 * Contract:
 *   - `NotepadEntryPayloadV2` is lifted verbatim from Releaf's
 *     SyncPayloads.swift. The two apps share the `notepad_entries`
 *     row shape — the cross-app shared-tables CI diff (per
 *     QUICKINK_PROPOSAL.md §3) enforces column-list parity, and the
 *     canonical-JSON encoding has to match byte-for-byte for
 *     cross-app interop. If you change one side, change the other.
 *   - `CapturePayloadV2` and `OcrResultPayloadV2` are new — defined
 *     here for the first time. QuickInk-only entity kinds.
 *   - `JSONAny` is duplicated from Releaf's SyncPayloads. Promotion
 *     into `ReleafCoreSync` is a follow-up; current cost is one
 *     ~80-line file copy.
 *
 * Local-only columns are deliberately omitted from every payload:
 *   - `dirty`, `deleted_at`, `conflict_stub` — sync bookkeeping
 *   - `drive_file_id` — set on the receive side from the manifest
 *
 * `blocks_json` on the OCR row is itself a JSON array; the payload
 * carries it as a first-class JSON value (not a stringified blob)
 * via `JSONAny`. The receive-side `toEntity` re-serialises it back
 * into a string for the column.
 */

import Foundation
import ReleafCoreNotes
import ReleafCoreSync

// =====================================================================
// JSONAny — pass-through JSON value for Codable
// (lifted verbatim from Releaf's SyncPayloads.swift; promotion to
// ReleafCoreSync is a follow-up)
// =====================================================================

public struct JSONAny: Codable, Equatable, @unchecked Sendable {
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
        try encodeJSONAny(value, into: &container)
    }

    public static func == (lhs: JSONAny, rhs: JSONAny) -> Bool {
        // Round-trip compare via JSON bytes — same approach Releaf uses.
        let l = (try? JSONSerialization.data(withJSONObject: lhs.value, options: [.sortedKeys, .fragmentsAllowed])) ?? Data()
        let r = (try? JSONSerialization.data(withJSONObject: rhs.value, options: [.sortedKeys, .fragmentsAllowed])) ?? Data()
        return l == r
    }

    /// Parse a raw JSON string into a JSONAny. Empty array on parse failure.
    public static func parseOrEmptyArray(_ raw: String?) -> JSONAny {
        guard let raw = raw, !raw.isEmpty,
              let data = raw.data(using: .utf8),
              let tree = try? JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed])
        else {
            return JSONAny([Any]())
        }
        return JSONAny(tree)
    }

    /// Compact JSON string with sorted keys — used for column storage.
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

private func encodeJSONAny(_ any: Any, into container: inout SingleValueEncodingContainer) throws {
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
// notepad_entries — same shape as Releaf's NotepadEntryPayloadV2
// (lifted verbatim; cross-app shared-table invariant).
// =====================================================================

public struct NotepadEntryPayloadV2: Codable, Equatable, Sendable {
    public let id: String
    public let userId: String
    public let entryDate: String
    public let projectId: String?
    public let title: String?
    public let description: String?
    public let category: String?
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
        case title
        case description
        case category
        case notes, contacts, locations, todos, attachments
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
            projectId: nil,  // iOS NotepadEntry doesn't carry projectId — same shape Releaf uses.
            title: title,
            description: description,
            category: category,
            notes: notes,
            contacts: JSONAny.parseOrEmptyArray(contacts),
            locations: JSONAny.parseOrEmptyArray(locations),
            todos: JSONAny.parseOrEmptyArray(todos),
            attachments: JSONAny.parseOrEmptyArray(attachments),
            sketchStrokes: JSONAny([Any]()),
            subPages: JSONAny([Any]()),
            allowBlankContent: false,
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
            title: title,
            description: description,
            category: category,
            notes: notes,
            contacts: contacts.toCompactString(),
            locations: locations.toCompactString(),
            todos: todos.toCompactString(),
            attachments: attachments.toCompactString(),
            driveFileId: driveFileId,
            createdAt: createdAt,
            updatedAt: updatedAt,
            dirty: false,
            deletedAt: nil
        )
    }
}

// =====================================================================
// captures — QuickInk-only. One row per scan session.
// =====================================================================

public struct CapturePayloadV2: Codable, Equatable, Sendable {
    public let id: String
    public let userId: String
    public let title: String?
    public let pdfUri: String
    public let previewUri: String?
    public let pageCount: Int
    /// Pre-tagged category name (Phase 5 — Categories). `nil` for
    /// captures created before v2 / by clients that haven't picked
    /// a category. Round-trips through Drive verbatim — captures
    /// don't FK into categories, so a deleted category name still
    /// reads back unchanged.
    public let category: String?
    /// Drive file id of the per-row PDF binary upload (Phase 6 —
    /// Drive backup). Restore-on-fresh-device fetches the binary
    /// from this id and rewrites `pdf_uri` to point at the new
    /// local copy. `nil` when the writer hadn't uploaded the PDF
    /// yet.
    public let pdfDriveFileId: String?
    /// Drive file id of the per-row preview-JPEG binary upload.
    /// Same restore semantics as [pdfDriveFileId].
    public let previewDriveFileId: String?
    public let createdAt: String
    public let updatedAt: String

    public init(
        id: String,
        userId: String,
        title: String?,
        pdfUri: String,
        previewUri: String?,
        pageCount: Int,
        category: String?,
        pdfDriveFileId: String? = nil,
        previewDriveFileId: String? = nil,
        createdAt: String,
        updatedAt: String
    ) {
        self.id = id
        self.userId = userId
        self.title = title
        self.pdfUri = pdfUri
        self.previewUri = previewUri
        self.pageCount = pageCount
        self.category = category
        self.pdfDriveFileId = pdfDriveFileId
        self.previewDriveFileId = previewDriveFileId
        self.createdAt = createdAt
        self.updatedAt = updatedAt
    }

    public enum CodingKeys: String, CodingKey {
        case id
        case userId             = "user_id"
        case title
        case pdfUri             = "pdf_uri"
        case previewUri         = "preview_uri"
        case pageCount          = "page_count"
        case category
        case pdfDriveFileId     = "pdf_drive_file_id"
        case previewDriveFileId = "preview_drive_file_id"
        case createdAt          = "created_at"
        case updatedAt          = "updated_at"
    }
}

// =====================================================================
// ocr_results — QuickInk-only. One row per scanned page.
// =====================================================================

// =====================================================================
// categories — QuickInk-only. User-configurable list, synced so
// the same chip set follows the user across devices.
// =====================================================================

public struct CategoryPayloadV1: Codable, Equatable, Sendable {
    public let id: String
    public let userId: String
    public let name: String
    public let position: Int
    public let createdAt: String
    public let updatedAt: String

    public enum CodingKeys: String, CodingKey {
        case id
        case userId    = "user_id"
        case name
        case position
        case createdAt = "created_at"
        case updatedAt = "updated_at"
    }
}

public struct OcrResultPayloadV2: Codable, Equatable, Sendable {
    public let id: String
    public let captureId: String
    public let pageIndex: Int
    public let language: String?
    public let confidence: Double?
    public let text: String
    /// `OcrBlock[]` as a first-class JSON array on the wire, NOT a
    /// stringified blob nested inside the row's JSON. The local row
    /// stores the same content in `ocr_results.blocks_json` as a
    /// string column; encode/decode flips between the two.
    public let blocks: JSONAny
    public let engine: String
    public let engineVersion: String?
    public let createdAt: String
    public let updatedAt: String

    public enum CodingKeys: String, CodingKey {
        case id
        case captureId     = "capture_id"
        case pageIndex     = "page_index"
        case language
        case confidence
        case text
        case blocks
        case engine
        case engineVersion = "engine_version"
        case createdAt     = "created_at"
        case updatedAt     = "updated_at"
    }
}
