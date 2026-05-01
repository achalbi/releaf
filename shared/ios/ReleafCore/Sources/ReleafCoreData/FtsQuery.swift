/*
 * FtsQuery.swift
 *
 * Turns a free-form user query into an FTS5 MATCH expression. Mirror of the
 * Kotlin `FtsQuery` on Android — both clients search the same local SQLite
 * schema via the same grammar so UX is identical across platforms.
 *
 * Intentionally simple: no phrase queries, no NEAR, no column filters.
 * Returns nil if the query degenerates to nothing usable; callers should
 * treat that as "empty result set" rather than passing the empty string to
 * SQLite (which would raise a MATCH error).
 *
 * PR #4a moved this from `apps/releaf/ios/Releaf/Data/Notepad/FtsQuery.swift`
 * into ReleafCoreData. Behavior unchanged.
 */

import Foundation

public enum FtsQuery {

    public static func build(_ rawQuery: String) -> String? {
        // Lowercase, strip punctuation → spaces, split on whitespace, append
        // prefix wildcards so "app" matches "application".
        let normalized = rawQuery.lowercased().map { ch -> Character in
            if ch.isLetter || ch.isNumber || ch.isWhitespace { return ch }
            return " "
        }
        let terms = String(normalized)
            .split(whereSeparator: { $0.isWhitespace })
            .map { "\($0)*" }

        guard !terms.isEmpty else { return nil }
        return terms.joined(separator: " ")
    }
}
