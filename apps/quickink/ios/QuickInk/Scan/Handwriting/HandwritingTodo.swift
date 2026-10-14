/*
 * HandwritingTodo.swift
 *
 * To-do items extracted from a scanned page, and the JSON plumbing
 * that stores them in `notepad_entries.todos`.
 *
 * Wire shape (one array element):
 *
 *   {"id": "...", "text": "...", "done": false,
 *    "sourceCaptureId": "...", "sourcePageIndex": 0, "sourceLineIndex": 3}
 *
 * `id` / `text` / `done` are the cross-app core (`NotepadTodo` in
 * ReleafCoreNotes, Releaf Android `TodoItem`). The `source*` keys are
 * QuickInk provenance for a later move into a tasks table. Safe to
 * add: Swift `Codable` ignores undeclared keys and Releaf Android
 * decodes with `ignoreUnknownKeys = true`. When Releaf rewrites an
 * entry's todos it drops the `source*` keys for that entry —
 * acceptable for provenance metadata.
 *
 * Merging never re-encodes existing elements, so fields QuickInk
 * doesn't model (Releaf's `priority`) survive a write.
 *
 * Mirror of Android `HandwritingTodo.kt`.
 */

import Foundation

public struct HandwritingTodo: Equatable, Identifiable, Sendable {
    public let id: String
    public var text: String
    public var done: Bool
    /// Capture the item was read from. Nil for items not created by a scan.
    public let sourceCaptureId: String?
    public let sourcePageIndex: Int?
    public let sourceLineIndex: Int?

    public init(
        id: String,
        text: String,
        done: Bool,
        sourceCaptureId: String? = nil,
        sourcePageIndex: Int? = nil,
        sourceLineIndex: Int? = nil
    ) {
        self.id = id
        self.text = text
        self.done = done
        self.sourceCaptureId = sourceCaptureId
        self.sourcePageIndex = sourcePageIndex
        self.sourceLineIndex = sourceLineIndex
    }

    public var isFromScan: Bool { sourceCaptureId != nil }
}

public enum HandwritingTodoJson {

    /// One item as a JSON object. Source keys are omitted when nil.
    public static func toObject(_ todo: HandwritingTodo) -> [String: Any] {
        var obj: [String: Any] = ["id": todo.id, "text": todo.text, "done": todo.done]
        if let v = todo.sourceCaptureId { obj["sourceCaptureId"] = v }
        if let v = todo.sourcePageIndex { obj["sourcePageIndex"] = v }
        if let v = todo.sourceLineIndex { obj["sourceLineIndex"] = v }
        return obj
    }

    /// Parse a `todos` column. Elements without string `id` / `text`
    /// are skipped; a malformed column reads as empty.
    public static func parse(_ raw: String?) -> [HandwritingTodo] {
        parseArray(raw).compactMap { el in
            guard let obj = el as? [String: Any],
                  let id = obj["id"] as? String,
                  let text = obj["text"] as? String
            else { return nil }
            return HandwritingTodo(
                id: id,
                text: text,
                done: boolValue(obj["done"]) ?? false,
                sourceCaptureId: obj["sourceCaptureId"] as? String,
                sourcePageIndex: intValue(obj["sourcePageIndex"]),
                sourceLineIndex: intValue(obj["sourceLineIndex"])
            )
        }
    }

    /// Append `items` to an existing `todos` array, keeping existing
    /// elements (and their unknown fields) as they are. Items whose
    /// `id` already exists are skipped, so re-committing is a no-op.
    public static func append(_ existing: String?, items: [HandwritingTodo]) -> String {
        var current = parseArray(existing)
        var ids = Set(current.compactMap { ($0 as? [String: Any])?["id"] as? String })
        for item in items where ids.insert(item.id).inserted {
            current.append(toObject(item))
        }
        return serialize(current)
    }

    /// Set `done` on the element with `id`, leaving every other field unchanged.
    public static func setDone(_ existing: String?, id: String, done: Bool) -> String {
        let updated: [Any] = parseArray(existing).map { el in
            guard var obj = el as? [String: Any], obj["id"] as? String == id else { return el }
            obj["done"] = done
            return obj
        }
        return serialize(updated)
    }

    // MARK: - Internals

    private static func parseArray(_ raw: String?) -> [Any] {
        guard let raw, !raw.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              let data = raw.data(using: .utf8),
              let arr = (try? JSONSerialization.jsonObject(with: data)) as? [Any]
        else { return [] }
        return arr
    }

    private static func serialize(_ arr: [Any]) -> String {
        guard let data = try? JSONSerialization.data(withJSONObject: arr, options: [.sortedKeys]),
              let s = String(data: data, encoding: .utf8)
        else { return "[]" }
        return s
    }

    /// JSONSerialization hands booleans back as NSNumber; only accept
    /// real booleans so a numeric field never reads as `done`.
    private static func boolValue(_ v: Any?) -> Bool? {
        guard let n = v as? NSNumber, CFGetTypeID(n) == CFBooleanGetTypeID() else { return nil }
        return n.boolValue
    }

    private static func intValue(_ v: Any?) -> Int? {
        guard let n = v as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID() else { return nil }
        return n.intValue
    }
}
