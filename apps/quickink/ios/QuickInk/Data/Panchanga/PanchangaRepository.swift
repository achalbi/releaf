/*
 * PanchangaRepository.swift
 *
 * Owns the lifecycle of the bundled Vontikoppal / Mysore Panchanga
 * dataset: parses `Resources/panchanga_2026_27.csv` on first launch,
 * caches every row in the `panchanga` SQLite table, and exposes
 * date-keyed / month-keyed / search publishers the UI consumes.
 *
 * Bootstrap is fire-and-forget on first call to `ensureLoaded()` —
 * subsequent calls short-circuit on the row count and a stored asset
 * version. The CSV is small (≈400 rows, 17 KB) so parsing happens
 * entirely in memory off the main actor.
 *
 * Port of Releaf Android's `PanchangaRepository.kt`. Same parser, same
 * search semantics (transliteration normalise + Levenshtein typo
 * tolerance), GRDB ValueObservation instead of Room Flow.
 */

import Foundation
import Combine
import GRDB

public final class PanchangaRepository {

    /// Bundled CSV asset version. **Bump this whenever the CSV changes**
    /// so existing installs re-import on next launch. Mirror of the
    /// Android `ASSET_VERSION` constant; current value matches v3
    /// (initial OCR + 7 festival patches).
    public static let assetVersion: Int = 3
    private static let assetFilename = "panchanga_2026_27"
    private static let assetExtension = "csv"
    private static let userDefaultsKey = "quickink.panchanga.asset_version"

    private let dbQueue: DatabaseQueue

    public init(database: QuickInkDatabase = .shared) {
        self.dbQueue = database.dbQueue
    }

    // MARK: - Bootstrap

    /// Idempotently seed the `panchanga` table from the bundled CSV
    /// when needed. Two triggers reload:
    ///   1. The table is empty (fresh install / wiped data).
    ///   2. The stored asset version is older than `assetVersion` —
    ///      lets us push CSV patches to existing installs without
    ///      requiring an uninstall.
    public func ensureLoaded() async {
        do {
            let count = try await dbQueue.read { db in
                try Int.fetchOne(db, sql: "SELECT COUNT(*) FROM panchanga") ?? 0
            }
            let stored = UserDefaults.standard.integer(forKey: Self.userDefaultsKey)
            if count > 0 && stored >= Self.assetVersion { return }

            let rows = try parseCsvFromBundle()
            guard !rows.isEmpty else { return }

            try await dbQueue.write { db in
                try db.execute(sql: "DELETE FROM panchanga")
                for row in rows {
                    try row.insert(db)
                }
            }
            UserDefaults.standard.set(Self.assetVersion, forKey: Self.userDefaultsKey)
        } catch {
            // Non-fatal — the screen renders the "Panchanga data not
            // available" placeholder when the table stays empty.
            NSLog("[panchanga] ensureLoaded failed: %@", "\(error)")
        }
    }

    // MARK: - Observation

    /// Rows for the given Gregorian date, ordered by tithi number.
    /// Some dates carry two rows (transition days).
    public func observeForDate(_ date: String) -> AnyPublisher<[PanchangaEntity], Never> {
        ValueObservation.tracking { db in
            try PanchangaEntity.fetchAll(db, sql: """
                SELECT * FROM panchanga
                WHERE date = ?
                ORDER BY thithi_num ASC
                """, arguments: [date])
        }
        .publisher(in: dbQueue)
        .catch { _ in Just<[PanchangaEntity]>([]) }
        .eraseToAnyPublisher()
    }

    /// Rows whose `date` starts with `monthPrefix` (e.g. "2026-05-").
    /// Powers the festival-dot indicators on the calendar grid.
    public func observeForMonth(prefix monthPrefix: String) -> AnyPublisher<[PanchangaEntity], Never> {
        ValueObservation.tracking { db in
            try PanchangaEntity.fetchAll(db, sql: """
                SELECT * FROM panchanga
                WHERE date LIKE ? || '%'
                ORDER BY date ASC, thithi_num ASC
                """, arguments: [monthPrefix])
        }
        .publisher(in: dbQueue)
        .catch { _ in Just<[PanchangaEntity]>([]) }
        .eraseToAnyPublisher()
    }

    /// All rows that carry a non-empty `special_day`. The search
    /// implementation filters in-process so we can AND-match tokens
    /// across `special_day + masa + paksha + thithi`.
    public func observeAllSpecialDays() -> AnyPublisher<[PanchangaEntity], Never> {
        ValueObservation.tracking { db in
            try PanchangaEntity.fetchAll(db, sql: """
                SELECT * FROM panchanga
                WHERE special_day_lc != ''
                ORDER BY date ASC, thithi_num ASC
                """)
        }
        .publisher(in: dbQueue)
        .catch { _ in Just<[PanchangaEntity]>([]) }
        .eraseToAnyPublisher()
    }

    // MARK: - Festival search

    /// Free-form festival search. Splits the query into whitespace-
    /// separated tokens and AND-matches each one against a per-row
    /// "haystack" composed of `special_day + masa + paksha + thithi`.
    ///
    /// Matching has two layers per token:
    ///   1. Transliteration-normalised substring (e.g. "janmastami"
    ///      finds "janmashtami" via `sh→s`).
    ///   2. Word-level Levenshtein with a length-scaled threshold
    ///      (≤3 letters: exact; 4-7: one edit; ≥8: two edits).
    public func searchSpecialDay(_ query: String) -> AnyPublisher<[PanchangaEntity], Never> {
        let tokens = query
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
            .split(whereSeparator: { $0.isWhitespace })
            .map(String.init)
            .filter { !$0.isEmpty }
            .map(Self.normalise)
        if tokens.isEmpty {
            return Just<[PanchangaEntity]>([]).eraseToAnyPublisher()
        }
        return observeAllSpecialDays()
            .map { rows -> [PanchangaEntity] in
                rows.filter { row in
                    let haystackRaw = "\(row.specialDayLowercase) \(row.masa.lowercased()) \(row.paksha.lowercased()) \(row.thithi.lowercased())"
                    let haystack = Self.normalise(haystackRaw)
                    let haystackWords = haystack
                        .split(whereSeparator: { $0.isWhitespace })
                        .map(String.init)
                        .filter { !$0.isEmpty }
                    return tokens.allSatisfy { token in
                        Self.matchesToken(haystack: haystack, haystackWords: haystackWords, token: token)
                    }
                }
            }
            .eraseToAnyPublisher()
    }

    private static func matchesToken(haystack: String, haystackWords: [String], token: String) -> Bool {
        if haystack.contains(token) { return true }
        let threshold = typoThreshold(token.count)
        if threshold == 0 { return false }
        return haystackWords.contains { levenshtein($0, token) <= threshold }
    }

    private static func typoThreshold(_ tokenLength: Int) -> Int {
        switch tokenLength {
        case 0...3: return 0
        case 4...7: return 1
        default:    return 2
        }
    }

    private static let maxTypoThreshold = 2

    private static func levenshtein(_ a: String, _ b: String) -> Int {
        let ac = Array(a)
        let bc = Array(b)
        if ac.isEmpty { return bc.count }
        if bc.isEmpty { return ac.count }
        if abs(ac.count - bc.count) > maxTypoThreshold {
            return ac.count + bc.count
        }
        var prev = Array(0...bc.count)
        var curr = Array(repeating: 0, count: bc.count + 1)
        for i in 1...ac.count {
            curr[0] = i
            for j in 1...bc.count {
                let cost = ac[i - 1] == bc[j - 1] ? 0 : 1
                curr[j] = Swift.min(
                    prev[j] + 1,         // deletion
                    curr[j - 1] + 1,     // insertion
                    prev[j - 1] + cost   // substitution
                )
            }
            swap(&prev, &curr)
        }
        return prev[bc.count]
    }

    /// Collapse common romanised-Indic transliteration variants —
    /// `sh→s`, `chh→ch`, `ph→f`, `aa→a`, `ee→i`, etc. — so the user
    /// can spell festival names however they're used to seeing them.
    /// Ordered longest / most specific first.
    private static func normalise(_ s: String) -> String {
        var out = s
        out = out.replacingOccurrences(of: "chh", with: "ch")
        out = out.replacingOccurrences(of: "aa",  with: "a")
        out = out.replacingOccurrences(of: "ee",  with: "i")
        out = out.replacingOccurrences(of: "oo",  with: "u")
        out = out.replacingOccurrences(of: "ii",  with: "i")
        out = out.replacingOccurrences(of: "uu",  with: "u")
        out = out.replacingOccurrences(of: "sh",  with: "s")
        out = out.replacingOccurrences(of: "ph",  with: "f")
        return out
    }

    // MARK: - CSV parsing

    private func parseCsvFromBundle() throws -> [PanchangaEntity] {
        guard let url = Bundle.module.url(forResource: Self.assetFilename, withExtension: Self.assetExtension) else {
            return []
        }
        let raw = try String(contentsOf: url, encoding: .utf8)
        var lines = raw.split(whereSeparator: \.isNewline).map(String.init)
        guard !lines.isEmpty else { return [] }
        let header = lines.removeFirst()
        guard header.hasPrefix("date,") else { return [] }

        var out: [PanchangaEntity] = []
        out.reserveCapacity(400)
        for line in lines {
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            if trimmed.isEmpty { continue }
            let fields = Self.parseCsvLine(trimmed)
            if fields.count < 5 { continue }
            let date       = fields[0].trimmingCharacters(in: .whitespaces)
            let masa       = fields[1].trimmingCharacters(in: .whitespaces)
            let paksha     = fields[2].trimmingCharacters(in: .whitespaces)
            let thithi     = fields[3].trimmingCharacters(in: .whitespaces)
            let thithiNum  = fields[4].trimmingCharacters(in: .whitespaces)
            let specialDay = fields.count >= 6 ? fields[5].trimmingCharacters(in: .whitespaces) : ""
            out.append(PanchangaEntity(
                id: "\(date)#\(thithiNum)",
                date: date,
                masa: masa,
                paksha: paksha,
                thithi: thithi,
                thithiNum: thithiNum,
                specialDay: specialDay,
                specialDayLowercase: specialDay.lowercased()
            ))
        }
        return out
    }

    /// Single-line CSV parser — handles double-quoted fields with
    /// embedded commas. Doesn't handle escaped quotes inside quoted
    /// fields (the upstream dataset never uses them).
    private static func parseCsvLine(_ line: String) -> [String] {
        var fields: [String] = []
        fields.reserveCapacity(6)
        var current = ""
        var inQuotes = false
        for ch in line {
            if ch == "\"" {
                inQuotes.toggle()
            } else if ch == "," && !inQuotes {
                fields.append(current)
                current.removeAll(keepingCapacity: true)
            } else {
                current.append(ch)
            }
        }
        fields.append(current)
        return fields
    }
}
