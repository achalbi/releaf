/*
 * SmartCollectionRule.swift
 *
 * v1 rule grammar for `smart_collections.rule_json`. AND-of-clauses
 * shape, no OR, no nesting — per brief §3. Six clause types ship
 * in v1; three (handwriting / signature / has-OCR) are placeholders
 * for the Phase E OCR-derived signals.
 *
 * Wire format is JSON with a `"type"` discriminator. JSON round-trips
 * through canonical-JSON the same byte-for-byte as Android.
 *
 * Mirror of `SmartCollectionRule.kt` in QuickInk's Android target.
 */

import Foundation

public enum RuleClause: Codable, Equatable, Sendable {
    case folderIs(folderId: String)
    case tagIs(tagId: String)
    case tagIsNot(tagId: String)
    case dateRange(field: String, preset: String)
    case sourceIs(value: String)
    case hasHandwriting(value: Bool)
    case hasSignature(value: Bool)
    case hasOcrText(value: Bool)

    private enum CodingKeys: String, CodingKey {
        case type
        case folderId  = "folder_id"
        case tagId     = "tag_id"
        case field
        case preset
        case value
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let type = try c.decode(String.self, forKey: .type)
        switch type {
        case "folder_is":
            self = .folderIs(folderId: try c.decode(String.self, forKey: .folderId))
        case "tag_is":
            self = .tagIs(tagId: try c.decode(String.self, forKey: .tagId))
        case "tag_is_not":
            self = .tagIsNot(tagId: try c.decode(String.self, forKey: .tagId))
        case "date_range":
            self = .dateRange(
                field:  try c.decode(String.self, forKey: .field),
                preset: try c.decode(String.self, forKey: .preset),
            )
        case "source_is":
            self = .sourceIs(value: try c.decode(String.self, forKey: .value))
        case "has_handwriting":
            self = .hasHandwriting(value: try c.decode(Bool.self, forKey: .value))
        case "has_signature":
            self = .hasSignature(value: try c.decode(Bool.self, forKey: .value))
        case "has_ocr_text":
            self = .hasOcrText(value: try c.decode(Bool.self, forKey: .value))
        default:
            throw DecodingError.dataCorruptedError(
                forKey: .type, in: c,
                debugDescription: "Unknown rule type: \(type)",
            )
        }
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        switch self {
        case .folderIs(let id):
            try c.encode("folder_is", forKey: .type)
            try c.encode(id, forKey: .folderId)
        case .tagIs(let id):
            try c.encode("tag_is", forKey: .type)
            try c.encode(id, forKey: .tagId)
        case .tagIsNot(let id):
            try c.encode("tag_is_not", forKey: .type)
            try c.encode(id, forKey: .tagId)
        case .dateRange(let f, let p):
            try c.encode("date_range", forKey: .type)
            try c.encode(f, forKey: .field)
            try c.encode(p, forKey: .preset)
        case .sourceIs(let v):
            try c.encode("source_is", forKey: .type)
            try c.encode(v, forKey: .value)
        case .hasHandwriting(let v):
            try c.encode("has_handwriting", forKey: .type)
            try c.encode(v, forKey: .value)
        case .hasSignature(let v):
            try c.encode("has_signature", forKey: .type)
            try c.encode(v, forKey: .value)
        case .hasOcrText(let v):
            try c.encode("has_ocr_text", forKey: .type)
            try c.encode(v, forKey: .value)
        }
    }
}

public enum SmartCollectionRule {
    public static func encode(_ clauses: [RuleClause]) -> String {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        guard let data = try? encoder.encode(clauses),
              let s = String(data: data, encoding: .utf8) else {
            return "[]"
        }
        return s
    }

    public static func decode(_ json: String?) -> [RuleClause] {
        guard let json, !json.isEmpty,
              let data = json.data(using: .utf8) else {
            return []
        }
        return (try? JSONDecoder().decode([RuleClause].self, from: data)) ?? []
    }
}

/// Flat editor-input projection of a rule-clause list. Each field
/// is one editable slot in the smart-collection editor; converting
/// to / from a `[RuleClause]` lives here so the UI doesn't have to
/// reason about the JSON grammar directly. Mirror of Android's
/// `SmartCollectionRuleInput`.
///
/// The `dateRange` clause's `field` is collapsed to `"created_at"`
/// here — the editor doesn't yet expose the `last_opened_at`
/// variant. `nil` / empty values mean "no clause of that kind".
public struct SmartCollectionRuleInput: Equatable, Sendable {
    public var folderId: String?
    public var datePreset: String?
    public var tagIncludeIds: [String]
    public var tagExcludeIds: [String]
    public var sourceValue: String?
    public var hasHandwriting: Bool?
    public var hasSignature: Bool?
    public var hasOcrText: Bool?

    public init(
        folderId: String? = nil,
        datePreset: String? = nil,
        tagIncludeIds: [String] = [],
        tagExcludeIds: [String] = [],
        sourceValue: String? = nil,
        hasHandwriting: Bool? = nil,
        hasSignature: Bool? = nil,
        hasOcrText: Bool? = nil
    ) {
        self.folderId = folderId
        self.datePreset = datePreset
        self.tagIncludeIds = tagIncludeIds
        self.tagExcludeIds = tagExcludeIds
        self.sourceValue = sourceValue
        self.hasHandwriting = hasHandwriting
        self.hasSignature = hasSignature
        self.hasOcrText = hasOcrText
    }

    /// True when no clause is selected — the editor's Save guard.
    public var isEmpty: Bool {
        folderId == nil &&
        datePreset == nil &&
        tagIncludeIds.isEmpty &&
        tagExcludeIds.isEmpty &&
        sourceValue == nil &&
        hasHandwriting == nil &&
        hasSignature == nil &&
        hasOcrText == nil
    }

    /// Compile back into the canonical AND-of-clauses list.
    public func toClauses() -> [RuleClause] {
        var clauses: [RuleClause] = []
        if let folderId { clauses.append(.folderIs(folderId: folderId)) }
        if let datePreset {
            clauses.append(.dateRange(field: "created_at", preset: datePreset))
        }
        for id in tagIncludeIds { clauses.append(.tagIs(tagId: id)) }
        for id in tagExcludeIds { clauses.append(.tagIsNot(tagId: id)) }
        if let sourceValue { clauses.append(.sourceIs(value: sourceValue)) }
        if let hasHandwriting { clauses.append(.hasHandwriting(value: hasHandwriting)) }
        if let hasSignature   { clauses.append(.hasSignature(value: hasSignature)) }
        if let hasOcrText     { clauses.append(.hasOcrText(value: hasOcrText)) }
        return clauses
    }

    /// Build a flat editor-input from a decoded clause list. When
    /// a clause type appears more than once the editor only tracks
    /// the first (folder / date / source / OCR flags) or unions
    /// ids (tag include / exclude).
    public static func fromClauses(_ clauses: [RuleClause]) -> SmartCollectionRuleInput {
        var input = SmartCollectionRuleInput()
        for c in clauses {
            switch c {
            case .folderIs(let id):
                if input.folderId == nil { input.folderId = id }
            case .dateRange(let field, let preset):
                if input.datePreset == nil && field == "created_at" {
                    input.datePreset = preset
                }
            case .tagIs(let id):
                if !input.tagIncludeIds.contains(id) { input.tagIncludeIds.append(id) }
            case .tagIsNot(let id):
                if !input.tagExcludeIds.contains(id) { input.tagExcludeIds.append(id) }
            case .sourceIs(let v):
                if input.sourceValue == nil { input.sourceValue = v }
            case .hasHandwriting(let v):
                if input.hasHandwriting == nil { input.hasHandwriting = v }
            case .hasSignature(let v):
                if input.hasSignature == nil { input.hasSignature = v }
            case .hasOcrText(let v):
                if input.hasOcrText == nil { input.hasOcrText = v }
            }
        }
        return input
    }
}
