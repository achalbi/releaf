/*
 * NotepadCategory.swift
 *
 * Pure-data helpers for the `category` column on `notepad_entries`.
 * There's no separate `categories` table — the column itself is the
 * source of truth, and this file provides:
 *
 *   - The seed list of predefined categories (Home / Work / Personal
 *     / Health / Travel / Ideas) the editor's picker shows up front.
 *   - Custom-category discovery: any non-predefined string the user
 *     has typed on at least one active entry becomes a custom chip
 *     automatically — `deriveCustomCategories(from:)` returns that
 *     set, alphabetised, ready for the picker to append after the
 *     predefined row.
 *
 * Comparisons use `caseInsensitiveCompare` everywhere so "home" and
 * "Home" don't fork into two chips. The display form is always the
 * canonical-cased version (predefined: matched against `predefined`;
 * custom: trimmed as the user typed it on the most recent entry).
 *
 * Mirror of Android's `NotepadCategory.kt` — same predefined order,
 * same case-insensitive matching, same alphabetised custom output so
 * the two clients render identical chip rows for the same data.
 */

import Foundation

public enum NotepadCategory {

    /// Predefined categories shown in the picker before any custom
    /// chips. Order is the picker's display order; don't sort
    /// alphabetically here.
    public static let predefined: [String] = [
        "Home",
        "Work",
        "Personal",
        "Health",
        "Travel",
        "Ideas",
    ]

    /// True when `name` matches one of the predefined categories,
    /// case-insensitive. Used by the picker to colour predefined
    /// chips differently from custom chips.
    public static func isPredefined(_ name: String?) -> Bool {
        let trimmed = name?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard !trimmed.isEmpty else { return false }
        return predefined.contains { $0.caseInsensitiveCompare(trimmed) == .orderedSame }
    }

    /// Canonicalise a stored category value to its display form. For
    /// predefined categories this returns the canonical-cased entry
    /// from `predefined` ("home" → "Home"); for custom strings it
    /// returns the trimmed input as-is so the user's casing wins.
    /// Nil / blank returns nil (uncategorised).
    public static func displayName(_ name: String?) -> String? {
        let trimmed = name?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard !trimmed.isEmpty else { return nil }
        return predefined.first { $0.caseInsensitiveCompare(trimmed) == .orderedSame }
            ?? trimmed
    }

    /// Walk `entries` and return every distinct *custom* category
    /// (i.e. anything not in `predefined`) that's currently in use,
    /// canonicalised by lower-case key, sorted alphabetically by
    /// display form. The picker appends this list after the
    /// predefined row so the user sees their own categories without
    /// any explicit "manage categories" step.
    ///
    /// Soft-deleted entries are filtered out by the caller (this
    /// helper is dumb on purpose — it just dedupes whatever it's
    /// handed).
    public static func deriveCustomCategories(from entries: [NotepadEntry]) -> [String] {
        guard !entries.isEmpty else { return [] }
        // First-occurrence wins for the display casing — entries are
        // usually delivered newest-first, so the most recent typing
        // of a custom category sets its display form.
        var seen: [String: String] = [:]
        var orderedKeys: [String] = []
        for entry in entries {
            let raw = entry.category?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            guard !raw.isEmpty else { continue }
            if predefined.contains(where: { $0.caseInsensitiveCompare(raw) == .orderedSame }) {
                continue
            }
            let key = raw.lowercased()
            if seen[key] == nil {
                seen[key] = raw
                orderedKeys.append(key)
            }
        }
        return orderedKeys
            .compactMap { seen[$0] }
            .sorted { $0.lowercased() < $1.lowercased() }
    }

    /// Resolve the user's preferred display order against the live
    /// predefined + custom sets. Mirror of Android's
    /// `NotepadCategory.applyOrder(...)` — same algorithm, same
    /// fallback rules.
    ///
    /// Result: every available name listed exactly once. Names the
    /// user has explicitly ordered come first, in `userOrder`. Any
    /// predefined still missing is appended next in its declared
    /// order. Any custom still missing is appended last
    /// alphabetically. Names in `userOrder` that no longer exist
    /// (deleted custom, etc.) are silently dropped.
    public static func applyOrder(
        userOrder: [String],
        customs: [String]
    ) -> [String] {
        // Build a key → display map of every available name.
        // Insertion order matters: predefined declared first, customs
        // alphabetised after, so the fallback paths stay stable.
        var available: [(key: String, display: String)] = []
        var keyIndex: [String: Int] = [:]
        for name in predefined {
            let key = name.lowercased()
            keyIndex[key] = available.count
            available.append((key, name))
        }
        for name in customs.sorted(by: { $0.lowercased() < $1.lowercased() }) {
            let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmed.isEmpty else { continue }
            let key = trimmed.lowercased()
            // First entry wins (predefined). If a custom collides
            // case-insensitively with a predefined name, the
            // predefined display wins for casing consistency.
            if keyIndex[key] == nil {
                keyIndex[key] = available.count
                available.append((key, trimmed))
            }
        }

        var result: [String] = []
        var claimed: Set<String> = []

        // Pass 1: user's explicit order.
        for raw in userOrder {
            let key = raw.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
            guard !key.isEmpty, !claimed.contains(key),
                  let idx = keyIndex[key] else { continue }
            result.append(available[idx].display)
            claimed.insert(key)
        }
        // Pass 2: every still-available name in natural order
        // (predefined declared → customs alphabetised).
        for (key, display) in available {
            guard !claimed.contains(key) else { continue }
            result.append(display)
            claimed.insert(key)
        }
        return result
    }
}
