/*
 * Attachments.swift
 *
 * Lightweight value types for the notepad editor's feature sections —
 * contacts, todos, locations, and attachment manifests (photos + scans).
 * Mirror of Android's `data/notebook/PageAttachments.kt`; same JSON shapes
 * so sync payloads round-trip unchanged between platforms.
 *
 * All four live as JSON strings in dedicated columns on the
 * `notepad_entries` row. Parsing/serialization lives in this file so the
 * editor VM can work with typed arrays and the schema side stays as
 * plain TEXT columns.
 *
 * Naming note: the types here are `NotepadContact` / `NotepadTodo` to
 * avoid colliding with the Drive-fake `Contact` / `TodoItem` structs in
 * `Data/Domain/Models.swift`. Both live in the `ReleafData` target, so
 * we keep the names distinct until the Drive-fake path retires.
 */

import Foundation

// MARK: - Shared JSON codec

private let jsonEncoder: JSONEncoder = {
    let e = JSONEncoder()
    // Skip optional defaults so the on-disk JSON stays compact and matches
    // Android's kotlinx.serialization `encodeDefaults = false` shape.
    e.outputFormatting = [.withoutEscapingSlashes]
    return e
}()

private let jsonDecoder = JSONDecoder()

/// Defensive list parser — malformed payloads return an empty list rather
/// than crashing the editor. The schema default is `'[]'` so a missing
/// column also round-trips cleanly.
private func decodeList<T: Decodable>(_ json: String) -> [T] {
    guard !json.isEmpty else { return [] }
    guard let data = json.data(using: .utf8) else { return [] }
    return (try? jsonDecoder.decode([T].self, from: data)) ?? []
}

private func encodeList<T: Encodable>(_ items: [T]) -> String {
    guard let data = try? jsonEncoder.encode(items) else { return "[]" }
    return String(data: data, encoding: .utf8) ?? "[]"
}

// MARK: - NotepadContact

public struct NotepadContact: Codable, Equatable, Identifiable, Sendable {
    public let id: String
    public let name: String
    public let role: String?

    public init(id: String, name: String, role: String? = nil) {
        self.id = id
        self.name = name
        self.role = role
    }
}

public extension Array where Element == NotepadContact {
    func toJsonString() -> String { encodeList(self) }
}

public extension String {
    func parseContacts() -> [NotepadContact] { decodeList(self) }
}

// MARK: - NotepadTodo

public struct NotepadTodo: Codable, Equatable, Identifiable, Sendable {
    public let id: String
    public let text: String
    public let done: Bool

    public init(id: String, text: String, done: Bool = false) {
        self.id = id
        self.text = text
        self.done = done
    }
}

public extension Array where Element == NotepadTodo {
    func toJsonString() -> String { encodeList(self) }
}

public extension String {
    func parseTodos() -> [NotepadTodo] { decodeList(self) }
}

// MARK: - GeoLocation

public struct GeoLocation: Codable, Equatable, Identifiable, Sendable {
    public let id: String
    public let lat: Double
    public let lng: Double
    /// Reverse-geocoded short address, if we could get one.
    public let address: String?
    /// ISO-8601 UTC when captured — helps the user tell apart multiple entries.
    public let capturedAt: String

    public init(
        id: String,
        lat: Double,
        lng: Double,
        address: String? = nil,
        capturedAt: String
    ) {
        self.id = id
        self.lat = lat
        self.lng = lng
        self.address = address
        self.capturedAt = capturedAt
    }
}

public extension Array where Element == GeoLocation {
    func toJsonString() -> String { encodeList(self) }
}

public extension String {
    func parseLocations() -> [GeoLocation] { decodeList(self) }
}

// MARK: - Attachment (photo / scan)

public struct Attachment: Codable, Equatable, Identifiable, Sendable {
    public let id: String
    /// "photo", "scan", or "voice" — string-typed so a future "sketch"
    /// kind can drop in without a migration.
    public let type: String
    /// file:// URL to a copy of the captured asset in our own files
    /// directory. iOS photo-picker items come back as `Data`, not URIs,
    /// so the UI layer writes them to disk before building an
    /// Attachment. Voice notes are M4A files written by AVAudioRecorder
    /// into the same attachments dir.
    public let uri: String
    /// Optional secondary URL for scans that carry both a PDF and a
    /// JPEG preview thumbnail.
    public let previewUri: String?
    public let capturedAt: String
    /// Clip length in milliseconds. Only populated for voice notes;
    /// nil for photo/scan so existing JSON round-trips unchanged
    /// (Swift's JSONEncoder omits nil-valued optional keys by default,
    /// matching Android's `encodeDefaults = false`). Cached at capture
    /// time so list rows can render "0:42" without re-probing the file.
    public let durationMs: Int?
    /// Speech-to-text transcript. Only populated for voice notes where
    /// `SFSpeechRecognizer` produced a non-empty result. Nullable so
    /// existing JSON round-trips unchanged. Computed once post-capture
    /// via an on-device recognizer — we don't re-run inference on view.
    public let transcript: String?

    public static let typePhoto = "photo"
    public static let typeScan  = "scan"
    public static let typeVoice = "voice"

    public init(
        id: String,
        type: String,
        uri: String,
        previewUri: String? = nil,
        capturedAt: String,
        durationMs: Int? = nil,
        transcript: String? = nil
    ) {
        self.id = id
        self.type = type
        self.uri = uri
        self.previewUri = previewUri
        self.capturedAt = capturedAt
        self.durationMs = durationMs
        self.transcript = transcript
    }
}

public extension Array where Element == Attachment {
    func toJsonString() -> String { encodeList(self) }
}

public extension String {
    func parseAttachments() -> [Attachment] { decodeList(self) }
}
