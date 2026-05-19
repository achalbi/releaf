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
    /// Legacy pre-A.3c label slot. Post-A.3c the
    /// `captures.category` column is gone; the field is kept on
    /// the wire so older clients can still emit it (and so a
    /// fresh client deserializes older payloads without throwing),
    /// but the new send-path always writes `nil` and the
    /// receive-path ignores it — the canonical per-capture label
    /// now lives in `capture_tags`.
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
    /// Drive file id of the per-row hold-to-record video binary
    /// upload (.mp4 in Drive). Set by `QuickInkBinarySync` after
    /// the source device successfully uploads the .mov / .mp4 to
    /// Drive. Receivers consume it the same way as
    /// [pdfDriveFileId]: download into AttachmentStorage, rewrite
    /// `video_uri` to the local file:// path. Nullable; absent on
    /// payloads from pre-v17 clients and on every capture that
    /// doesn't have a video to begin with.
    public let videoDriveFileId: String?
    /// How the capture was created — `"scan"` (the document scanner)
    /// or `"import"` (the system photo picker). Defaulted to "scan"
    /// for back-compat with rows synced from older clients that
    /// didn't write the field; the column-level default in the
    /// captures schema mirrors this.
    public let source: String
    /// Page-size class — `"card"`, `"a4"` (default), or `"small"`.
    /// Drives the sustainability hero's per-page weight on receivers
    /// exactly as on the producer. Defaulted to `"a4"` so payloads
    /// from older clients that didn't write the field hydrate with
    /// the same value the local column default uses.
    public let paperSize: String
    /// Decimal-degree latitude captured at scan time (Phase 7 —
    /// Geolocation). Both lat + lon are nullable, and `nil` on the
    /// wire when the user has the "Location for scans" toggle off,
    /// when permission is denied, or for captures from older clients
    /// that hadn't shipped the feature yet.
    public let latitude: Double?
    /// Decimal-degree longitude. Pairs with [latitude] — either both
    /// non-nil or both nil; the writer never emits one without the
    /// other.
    public let longitude: Double?
    /// Reverse-geocoded city (e.g. "San Francisco"). Sourced from
    /// `CLPlacemark.locality` at write time. Round-trips through
    /// Drive verbatim — we don't re-geocode on import so the
    /// receiver sees exactly what the capturing device's locale
    /// produced.
    public let locality: String?
    /// Reverse-geocoded neighbourhood / area (e.g. "Mission
    /// District"). `CLPlacemark.subLocality`.
    public let subLocality: String?
    /// Formatted full street address built from `CLPlacemark` via
    /// `CNPostalAddressFormatter`. Round-trips through Drive
    /// verbatim — we don't re-format on receive so the receiver
    /// sees exactly what the capturing device's locale produced.
    public let address: String?
    /// Free-form document-level notes. Currently append-only via the
    /// voice-note transcript editor (Document detail → record a voice
    /// note → edit transcript → save appends here). Nullable; absent
    /// on payloads from pre-v15 / pre-v13 clients.
    public let notes: String?
    /// Local file:// URI for the raw video that produced the capture
    /// (hold-to-record Photo-mode path, v16). Nil for every other
    /// source. Today we ship the URI verbatim across devices; the
    /// receiver won't be able to play it back until a future binary-
    /// upload pass lands the video on Drive and rewrites this field
    /// to the per-device cache path on pull (mirrors the pdf_uri /
    /// preview_uri sync story).
    public let videoUri: String?
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
        videoDriveFileId: String? = nil,
        source: String = "scan",
        paperSize: String = "a4",
        latitude: Double? = nil,
        longitude: Double? = nil,
        locality: String? = nil,
        subLocality: String? = nil,
        address: String? = nil,
        notes: String? = nil,
        videoUri: String? = nil,
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
        self.videoDriveFileId = videoDriveFileId
        self.source = source
        self.paperSize = paperSize
        self.latitude = latitude
        self.longitude = longitude
        self.locality = locality
        self.subLocality = subLocality
        self.address = address
        self.notes = notes
        self.videoUri = videoUri
        self.createdAt = createdAt
        self.updatedAt = updatedAt
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.id                 = try c.decode(String.self, forKey: .id)
        self.userId             = try c.decode(String.self, forKey: .userId)
        self.title              = try c.decodeIfPresent(String.self, forKey: .title)
        self.pdfUri             = try c.decode(String.self, forKey: .pdfUri)
        self.previewUri         = try c.decodeIfPresent(String.self, forKey: .previewUri)
        self.pageCount          = try c.decode(Int.self, forKey: .pageCount)
        self.category           = try c.decodeIfPresent(String.self, forKey: .category)
        self.pdfDriveFileId     = try c.decodeIfPresent(String.self, forKey: .pdfDriveFileId)
        self.previewDriveFileId = try c.decodeIfPresent(String.self, forKey: .previewDriveFileId)
        // `video_drive_file_id` landed in v17 (iOS) / Room v23
        // (Android). Tolerate its absence on older payloads — they
        // read back as nil.
        self.videoDriveFileId   = try c.decodeIfPresent(String.self, forKey: .videoDriveFileId)
        // `source` is back-compat optional on the wire — older
        // clients didn't write it, and a fresh-device restore from
        // such an older payload should read back as a scan rather
        // than crash on a missing key.
        self.source             = (try c.decodeIfPresent(String.self, forKey: .source)) ?? "scan"
        // `paper_size` landed in v11 (iOS) / Room v13 (Android).
        // Same back-compat treatment as `source` — payloads from
        // older clients hydrate as `"a4"`, matching the column-level
        // default.
        self.paperSize          = (try c.decodeIfPresent(String.self, forKey: .paperSize)) ?? "a4"
        // Geolocation keys landed in Phase 7. Tolerate their absence
        // (older payloads, captures from clients that haven't shipped
        // the feature, or captures the user took with location off)
        // — all of them read back as `nil`. `address` joined them
        // in Phase 7.1 with the same back-compat story.
        self.latitude           = try c.decodeIfPresent(Double.self, forKey: .latitude)
        self.longitude          = try c.decodeIfPresent(Double.self, forKey: .longitude)
        self.locality           = try c.decodeIfPresent(String.self, forKey: .locality)
        self.subLocality        = try c.decodeIfPresent(String.self, forKey: .subLocality)
        self.address            = try c.decodeIfPresent(String.self, forKey: .address)
        // `notes` landed in v13 (iOS) / Room v15 (Android). Tolerate
        // its absence on older payloads — they read back as nil.
        self.notes              = try c.decodeIfPresent(String.self, forKey: .notes)
        // `video_uri` landed in v16 (iOS) / Room v22 (Android). Same
        // back-compat treatment — payloads from older clients read
        // back as nil.
        self.videoUri           = try c.decodeIfPresent(String.self, forKey: .videoUri)
        self.createdAt          = try c.decode(String.self, forKey: .createdAt)
        self.updatedAt          = try c.decode(String.self, forKey: .updatedAt)
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
        case videoDriveFileId   = "video_drive_file_id"
        case source
        case paperSize          = "paper_size"
        case latitude
        case longitude
        case locality
        case subLocality        = "sub_locality"
        case address
        case notes
        case videoUri           = "video_uri"
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

public struct TagPayloadV1: Codable, Equatable, Sendable {
    public let id: String
    public let userId: String
    public let name: String
    public let position: Int
    /// Workspace v1 — optional chip color. Defaults to nil on the
    /// wire for back-compat with payloads from pre-v8 clients.
    public let color: String?
    public let createdAt: String
    public let updatedAt: String

    public init(
        id: String,
        userId: String,
        name: String,
        position: Int,
        color: String? = nil,
        createdAt: String,
        updatedAt: String
    ) {
        self.id        = id
        self.userId    = userId
        self.name      = name
        self.position  = position
        self.color     = color
        self.createdAt = createdAt
        self.updatedAt = updatedAt
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.id        = try c.decode(String.self, forKey: .id)
        self.userId    = try c.decode(String.self, forKey: .userId)
        self.name      = try c.decode(String.self, forKey: .name)
        self.position  = try c.decode(Int.self,    forKey: .position)
        self.color     = try c.decodeIfPresent(String.self, forKey: .color)
        self.createdAt = try c.decode(String.self, forKey: .createdAt)
        self.updatedAt = try c.decode(String.self, forKey: .updatedAt)
    }

    public enum CodingKeys: String, CodingKey {
        case id
        case userId    = "user_id"
        case name
        case position
        case color
        case createdAt = "created_at"
        case updatedAt = "updated_at"
    }
}

// =====================================================================
// folders — QuickInk-only. Workspace v1 "intent" axis.
// =====================================================================

public struct FolderPayloadV1: Codable, Equatable, Sendable {
    public let id: String
    public let userId: String
    public let name: String
    public let color: String
    public let position: Int
    public let coverUri: String?
    public let isDefault: Bool
    public let isShared: Bool
    public let createdAt: String
    public let updatedAt: String

    public enum CodingKeys: String, CodingKey {
        case id
        case userId    = "user_id"
        case name
        case color
        case position
        case coverUri  = "cover_uri"
        case isDefault = "is_default"
        case isShared  = "is_shared"
        case createdAt = "created_at"
        case updatedAt = "updated_at"
    }
}

// =====================================================================
// capture_tags — QuickInk-only. Workspace v1 many-to-many join.
// Each row syncs independently of the parent capture.
// =====================================================================

public struct CaptureTagPayloadV1: Codable, Equatable, Sendable {
    public let id: String
    public let captureId: String
    public let tagId: String
    public let source: String
    public let createdAt: String
    public let updatedAt: String

    public init(
        id: String,
        captureId: String,
        tagId: String,
        source: String = "manual",
        createdAt: String,
        updatedAt: String
    ) {
        self.id        = id
        self.captureId = captureId
        self.tagId     = tagId
        self.source    = source
        self.createdAt = createdAt
        self.updatedAt = updatedAt
    }

    public enum CodingKeys: String, CodingKey {
        case id
        case captureId = "capture_id"
        case tagId     = "tag_id"
        case source
        case createdAt = "created_at"
        case updatedAt = "updated_at"
    }
}

// =====================================================================
// smart_collections — QuickInk-only. Rule-based saved views.
// rule_json carries the AND-of-clauses grammar (see brief §3).
// =====================================================================

public struct SmartCollectionPayloadV1: Codable, Equatable, Sendable {
    public let id: String
    public let userId: String
    public let name: String
    public let icon: String?
    public let color: String?
    public let ruleJson: String
    public let position: Int
    public let isSeeded: Bool
    public let createdAt: String
    public let updatedAt: String

    public enum CodingKeys: String, CodingKey {
        case id
        case userId    = "user_id"
        case name
        case icon
        case color
        case ruleJson  = "rule_json"
        case position
        case isSeeded  = "is_seeded"
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

// =====================================================================
// voice_notes — QuickInk-only. One row per voice note attached to a
// capture. JSON carries the metadata + `audio_drive_file_id`; the
// .m4a binary itself travels via `QuickInkBinarySync`, mirroring how
// captures' PDFs travel separately from the capture JSON.
// =====================================================================

public struct VoiceNotePayloadV1: Codable, Equatable, Sendable {
    public let id: String
    public let captureId: String
    public let userId: String
    /// Source device's local file:// URI. Same caveat as
    /// `captures.pdf_uri`: meaningless on a different device. The
    /// receive side keeps the local URI when the file already exists
    /// here, otherwise blanks it out and waits for the binary-restore
    /// pass to fill it in from `audioDriveFileId`.
    public let audioUri: String
    public let durationMs: Int
    public let transcription: String?
    public let transcriptionSource: String?
    public let audioDriveFileId: String?
    public let createdAt: String
    public let updatedAt: String

    public init(
        id: String,
        captureId: String,
        userId: String,
        audioUri: String,
        durationMs: Int,
        transcription: String? = nil,
        transcriptionSource: String? = nil,
        audioDriveFileId: String? = nil,
        createdAt: String,
        updatedAt: String
    ) {
        self.id                  = id
        self.captureId           = captureId
        self.userId              = userId
        self.audioUri            = audioUri
        self.durationMs          = durationMs
        self.transcription       = transcription
        self.transcriptionSource = transcriptionSource
        self.audioDriveFileId    = audioDriveFileId
        self.createdAt           = createdAt
        self.updatedAt           = updatedAt
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.id                  = try c.decode(String.self, forKey: .id)
        self.captureId           = try c.decode(String.self, forKey: .captureId)
        self.userId              = try c.decode(String.self, forKey: .userId)
        self.audioUri            = try c.decode(String.self, forKey: .audioUri)
        self.durationMs          = try c.decode(Int.self,    forKey: .durationMs)
        self.transcription       = try c.decodeIfPresent(String.self, forKey: .transcription)
        self.transcriptionSource = try c.decodeIfPresent(String.self, forKey: .transcriptionSource)
        self.audioDriveFileId    = try c.decodeIfPresent(String.self, forKey: .audioDriveFileId)
        self.createdAt           = try c.decode(String.self, forKey: .createdAt)
        self.updatedAt           = try c.decode(String.self, forKey: .updatedAt)
    }

    public enum CodingKeys: String, CodingKey {
        case id
        case captureId           = "capture_id"
        case userId              = "user_id"
        case audioUri            = "audio_uri"
        case durationMs          = "duration_ms"
        case transcription
        case transcriptionSource = "transcription_source"
        case audioDriveFileId    = "audio_drive_file_id"
        case createdAt           = "created_at"
        case updatedAt           = "updated_at"
    }
}

// =====================================================================
// Stories (Phase 1 + Phase 2) — see design/STORIES_HANDOFF.md.
//
// `story`           → metadata for one curated narrative.
// `story_item`      → one entry in a story's ordered child list.
// `story_voice_clip`→ inline voice clip attached to a story_item of
//                     kind = 'voice_clip'. JSON carries metadata +
//                     `audio_drive_file_id`; the .m4a binary
//                     travels via `QuickInkBinarySync` mirroring
//                     voice_notes.
// =====================================================================

public struct StoryPayloadV1: Codable, Equatable, Sendable {
    public let id: String
    public let userId: String
    public let title: String
    public let subtitle: String?
    public let coverItemId: String?
    public let coverStyle: String
    public let themeStyle: String
    public let groupingMode: String
    public let timeRangeStart: String?
    public let timeRangeEnd: String?
    public let status: String
    public let shareMode: String
    public let shareSlug: String?
    public let createdAt: String
    public let updatedAt: String

    public enum CodingKeys: String, CodingKey {
        case id
        case userId         = "user_id"
        case title
        case subtitle
        case coverItemId    = "cover_item_id"
        case coverStyle     = "cover_style"
        case themeStyle     = "theme_style"
        case groupingMode   = "grouping_mode"
        case timeRangeStart = "time_range_start"
        case timeRangeEnd   = "time_range_end"
        case status
        case shareMode      = "share_mode"
        case shareSlug      = "share_slug"
        case createdAt      = "created_at"
        case updatedAt      = "updated_at"
    }
}

public struct StoryItemPayloadV1: Codable, Equatable, Sendable {
    public let id: String
    public let storyId: String
    public let position: Int
    public let kind: String
    public let refId: String?
    public let text: String?
    public let caption: String?
    public let occurredAt: String?
    public let layout: String
    public let createdAt: String
    public let updatedAt: String

    public enum CodingKeys: String, CodingKey {
        case id
        case storyId    = "story_id"
        case position
        case kind
        case refId      = "ref_id"
        case text
        case caption
        case occurredAt = "occurred_at"
        case layout
        case createdAt  = "created_at"
        case updatedAt  = "updated_at"
    }
}

public struct StoryVoiceClipPayloadV1: Codable, Equatable, Sendable {
    public let id: String
    public let storyItemId: String
    public let userId: String
    /// Source device's local file:// URI — same caveat as
    /// `VoiceNotePayloadV1.audioUri`. The receive side keeps the URI
    /// when the file exists locally; otherwise blanks it and waits for
    /// the binary-restore pass.
    public let audioUri: String
    public let durationMs: Int
    public let transcription: String?
    public let transcriptionSource: String?
    public let audioDriveFileId: String?
    public let createdAt: String
    public let updatedAt: String

    public enum CodingKeys: String, CodingKey {
        case id
        case storyItemId         = "story_item_id"
        case userId              = "user_id"
        case audioUri            = "audio_uri"
        case durationMs          = "duration_ms"
        case transcription
        case transcriptionSource = "transcription_source"
        case audioDriveFileId    = "audio_drive_file_id"
        case createdAt           = "created_at"
        case updatedAt           = "updated_at"
    }
}

// =====================================================================
// profile_settings — QuickInk-only. One row per user; carries the
// custom display-name override, phone, personality punchline,
// transcription-language allowlist, and the photo's Drive-file
// linkage. The actual photo binary travels via QuickInkBinarySync
// (same path captures take); this payload only carries the metadata
// reference (`photo_drive_file_id`). `photo_local_uri` is
// deliberately NOT in the wire shape — it's a device-local file://
// URI that wouldn't make sense on a different device.
//
// Mirror of Android's `ProfileSettingsPayloadV1`. Field order +
// JSON keys match 1:1 so the Drive JSON round-trips across platforms.
// =====================================================================

public struct ProfileSettingsPayloadV1: Codable, Equatable, Sendable {
    public let id: String
    public let userId: String
    public let displayName: String?
    public let phoneNumber: String?
    public let personalityPunchline: String?
    /// Comma-separated BCP-47 codes (e.g. "en,hi,kn") for the user's
    /// transcription-language allowlist. `nil` on payloads written
    /// before the field landed; the receiver treats that as "fall
    /// back to device locale + English" rather than carrying meaning.
    /// Wire shape is a single string rather than a JSON array to
    /// keep the round-trip identical to the column type and avoid a
    /// payload-version bump for an additive change.
    public let transcriptionLanguages: String?
    public let photoDriveFileId: String?
    public let photoUpdatedAt: String?
    public let createdAt: String
    public let updatedAt: String

    public init(
        id: String,
        userId: String,
        displayName: String? = nil,
        phoneNumber: String? = nil,
        personalityPunchline: String? = nil,
        transcriptionLanguages: String? = nil,
        photoDriveFileId: String? = nil,
        photoUpdatedAt: String? = nil,
        createdAt: String,
        updatedAt: String
    ) {
        self.id                     = id
        self.userId                 = userId
        self.displayName            = displayName
        self.phoneNumber            = phoneNumber
        self.personalityPunchline   = personalityPunchline
        self.transcriptionLanguages = transcriptionLanguages
        self.photoDriveFileId       = photoDriveFileId
        self.photoUpdatedAt         = photoUpdatedAt
        self.createdAt              = createdAt
        self.updatedAt              = updatedAt
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.id                     = try c.decode(String.self, forKey: .id)
        self.userId                 = try c.decode(String.self, forKey: .userId)
        self.displayName            = try c.decodeIfPresent(String.self, forKey: .displayName)
        self.phoneNumber            = try c.decodeIfPresent(String.self, forKey: .phoneNumber)
        self.personalityPunchline   = try c.decodeIfPresent(String.self, forKey: .personalityPunchline)
        self.transcriptionLanguages = try c.decodeIfPresent(String.self, forKey: .transcriptionLanguages)
        self.photoDriveFileId       = try c.decodeIfPresent(String.self, forKey: .photoDriveFileId)
        self.photoUpdatedAt         = try c.decodeIfPresent(String.self, forKey: .photoUpdatedAt)
        self.createdAt              = try c.decode(String.self, forKey: .createdAt)
        self.updatedAt              = try c.decode(String.self, forKey: .updatedAt)
    }

    public enum CodingKeys: String, CodingKey {
        case id
        case userId                 = "user_id"
        case displayName            = "display_name"
        case phoneNumber            = "phone_number"
        case personalityPunchline   = "personality_punchline"
        case transcriptionLanguages = "transcription_languages"
        case photoDriveFileId       = "photo_drive_file_id"
        case photoUpdatedAt         = "photo_updated_at"
        case createdAt              = "created_at"
        case updatedAt              = "updated_at"
    }
}

// =====================================================================
// locations — QuickInk-only. User-defined places ("Home", "Work")
// surfaced as "Places" in the Home screen. The wire kind keeps the
// legacy `location` string for byte-for-byte parity with Android.
// Mirror of Android's `LocationPayloadV1`.
// =====================================================================

public struct LocationPayloadV1: Codable, Equatable, Sendable {
    public let id: String
    public let userId: String
    public let name: String
    public let position: Int
    public let color: String?
    public let latitude: Double?
    public let longitude: Double?
    public let address: String?
    public let createdAt: String
    public let updatedAt: String

    public init(
        id: String,
        userId: String,
        name: String,
        position: Int,
        color: String? = nil,
        latitude: Double? = nil,
        longitude: Double? = nil,
        address: String? = nil,
        createdAt: String,
        updatedAt: String
    ) {
        self.id        = id
        self.userId    = userId
        self.name      = name
        self.position  = position
        self.color     = color
        self.latitude  = latitude
        self.longitude = longitude
        self.address   = address
        self.createdAt = createdAt
        self.updatedAt = updatedAt
    }

    public enum CodingKeys: String, CodingKey {
        case id
        case userId    = "user_id"
        case name
        case position
        case color
        case latitude
        case longitude
        case address
        case createdAt = "created_at"
        case updatedAt = "updated_at"
    }
}

public extension LocationEntity {
    func toV1Payload() -> LocationPayloadV1 {
        LocationPayloadV1(
            id:        id,
            userId:    userId,
            name:      name,
            position:  position,
            color:     color,
            latitude:  latitude,
            longitude: longitude,
            address:   address,
            createdAt: createdAt,
            updatedAt: updatedAt
        )
    }
}

public extension LocationPayloadV1 {
    func toEntity(driveFileId: String?) -> LocationEntity {
        LocationEntity(
            id:          id,
            userId:      userId,
            name:        name,
            position:    position,
            color:       color,
            latitude:    latitude,
            longitude:   longitude,
            address:     address,
            driveFileId: driveFileId,
            createdAt:   createdAt,
            updatedAt:   updatedAt,
            dirty:       false
        )
    }
}

// =====================================================================
// capture_locations — QuickInk-only. Many-to-many join between
// captures and locations. Mirror of `CaptureLocationPayloadV1`.
// =====================================================================

public struct CaptureLocationPayloadV1: Codable, Equatable, Sendable {
    public let id: String
    public let captureId: String
    public let locationId: String
    public let source: String
    public let createdAt: String
    public let updatedAt: String

    public init(
        id: String,
        captureId: String,
        locationId: String,
        source: String = "manual",
        createdAt: String,
        updatedAt: String
    ) {
        self.id         = id
        self.captureId  = captureId
        self.locationId = locationId
        self.source     = source
        self.createdAt  = createdAt
        self.updatedAt  = updatedAt
    }

    public enum CodingKeys: String, CodingKey {
        case id
        case captureId  = "capture_id"
        case locationId = "location_id"
        case source
        case createdAt  = "created_at"
        case updatedAt  = "updated_at"
    }
}

public extension CaptureLocationEntity {
    func toV1Payload() -> CaptureLocationPayloadV1 {
        CaptureLocationPayloadV1(
            id:         id,
            captureId:  captureId,
            locationId: locationId,
            source:     source,
            createdAt:  createdAt,
            updatedAt:  updatedAt
        )
    }
}

public extension CaptureLocationPayloadV1 {
    func toEntity(driveFileId: String?) -> CaptureLocationEntity {
        CaptureLocationEntity(
            id:          id,
            captureId:   captureId,
            locationId:  locationId,
            source:      source,
            driveFileId: driveFileId,
            createdAt:   createdAt,
            updatedAt:   updatedAt,
            dirty:       false
        )
    }
}

// =====================================================================
// people — QuickInk-only. User-defined people ("Me", "Mom", "Dr. Rao").
// `contact_lookup_key` and `contact_photo_uri` are device-local and
// stay off the wire — only `contact_phone` + `contact_email` travel.
// Mirror of Android's `PersonPayloadV1`.
// =====================================================================

public struct PersonPayloadV1: Codable, Equatable, Sendable {
    public let id: String
    public let userId: String
    public let name: String
    public let position: Int
    public let color: String?
    public let contactPhone: String?
    public let contactEmail: String?
    public let createdAt: String
    public let updatedAt: String

    public init(
        id: String,
        userId: String,
        name: String,
        position: Int,
        color: String? = nil,
        contactPhone: String? = nil,
        contactEmail: String? = nil,
        createdAt: String,
        updatedAt: String
    ) {
        self.id           = id
        self.userId       = userId
        self.name         = name
        self.position     = position
        self.color        = color
        self.contactPhone = contactPhone
        self.contactEmail = contactEmail
        self.createdAt    = createdAt
        self.updatedAt    = updatedAt
    }

    public enum CodingKeys: String, CodingKey {
        case id
        case userId       = "user_id"
        case name
        case position
        case color
        case contactPhone = "contact_phone"
        case contactEmail = "contact_email"
        case createdAt    = "created_at"
        case updatedAt    = "updated_at"
    }
}

public extension PersonEntity {
    func toV1Payload() -> PersonPayloadV1 {
        PersonPayloadV1(
            id:           id,
            userId:       userId,
            name:         name,
            position:     position,
            color:        color,
            contactPhone: contactPhone,
            contactEmail: contactEmail,
            createdAt:    createdAt,
            updatedAt:    updatedAt
        )
    }
}

public extension PersonPayloadV1 {
    func toEntity(driveFileId: String?) -> PersonEntity {
        PersonEntity(
            id:               id,
            userId:           userId,
            name:             name,
            position:         position,
            color:            color,
            contactLookupKey: nil,
            contactPhone:     contactPhone,
            contactEmail:     contactEmail,
            contactPhotoUri:  nil,
            driveFileId:      driveFileId,
            createdAt:        createdAt,
            updatedAt:        updatedAt,
            dirty:            false
        )
    }
}

// =====================================================================
// capture_people — QuickInk-only. Many-to-many join between captures
// and people. Mirror of `CapturePersonPayloadV1`.
// =====================================================================

public struct CapturePersonPayloadV1: Codable, Equatable, Sendable {
    public let id: String
    public let captureId: String
    public let personId: String
    public let source: String
    public let createdAt: String
    public let updatedAt: String

    public init(
        id: String,
        captureId: String,
        personId: String,
        source: String = "manual",
        createdAt: String,
        updatedAt: String
    ) {
        self.id        = id
        self.captureId = captureId
        self.personId  = personId
        self.source    = source
        self.createdAt = createdAt
        self.updatedAt = updatedAt
    }

    public enum CodingKeys: String, CodingKey {
        case id
        case captureId = "capture_id"
        case personId  = "person_id"
        case source
        case createdAt = "created_at"
        case updatedAt = "updated_at"
    }
}

public extension CapturePersonEntity {
    func toV1Payload() -> CapturePersonPayloadV1 {
        CapturePersonPayloadV1(
            id:        id,
            captureId: captureId,
            personId:  personId,
            source:    source,
            createdAt: createdAt,
            updatedAt: updatedAt
        )
    }
}

public extension CapturePersonPayloadV1 {
    func toEntity(driveFileId: String?) -> CapturePersonEntity {
        CapturePersonEntity(
            id:          id,
            captureId:   captureId,
            personId:    personId,
            source:      source,
            driveFileId: driveFileId,
            createdAt:   createdAt,
            updatedAt:   updatedAt,
            dirty:       false
        )
    }
}
