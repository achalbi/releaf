/*
 * StorySuggestionEngine.swift
 *
 * Stories Phase 5 — the date-clustering engine that powers the
 * Stories shelf's "Suggested · today" hero card. See
 * `shared/algorithms/story-suggestions.md` for the canonical spec —
 * both platforms MUST emit identical output for identical inputs.
 *
 * Stateless engine — caller threads in the dismissed-set (the shelf
 * VM owns the process-scoped set). Output is at most one
 * `StorySuggestion`. Determinism is the load-bearing property: the
 * same captures + dismissed set re-run must produce the same id so
 * a session dismissal sticks.
 *
 * Mirror of Android `StorySuggestionEngine.kt`.
 */

import CryptoKit
import Foundation
import GRDB

public enum StorySuggestionEngine {

    /// Gap (seconds) above which we cut a cluster, per spec §3.
    private static let cutGapSeconds: TimeInterval = 18 * 3600

    /// Run the engine for the user. Returns at most one suggestion.
    /// Reads captures from `QuickInkDatabase.shared` via raw SQL so
    /// the engine doesn't depend on a Capture model struct.
    public static func compute(
        userId: String,
        database: QuickInkDatabase = .shared,
        dismissed: Set<String> = []
    ) async throws -> StorySuggestion? {
        let captures = try await readCaptures(userId: userId, database: database)
        return compute(captures: captures, dismissed: dismissed)
    }

    /// Pure-function entry point — testable without a database.
    public static func compute(
        captures: [CapturePoint],
        dismissed: Set<String>
    ) -> StorySuggestion? {
        let sorted = captures.sorted { $0.timestamp < $1.timestamp }
        let clusters = greedyCluster(sorted)
        let qualified = clusters.filter { $0.count >= 4 }
        guard !qualified.isEmpty else { return nil }

        // Sort by descending score; for ties, prefer the more recent
        // cluster (latest captureTimestamp).
        let ranked = qualified.sorted { a, b in
            let sa = score(a), sb = score(b)
            if sa != sb { return sa > sb }
            return a.last!.timestamp > b.last!.timestamp
        }
        for cluster in ranked {
            let suggestion = buildSuggestion(from: cluster)
            if !dismissed.contains(suggestion.id) { return suggestion }
        }
        return nil
    }

    /// Minimal capture projection the engine needs. Keeps the engine
    /// independent of the full `Capture` model.
    public struct CapturePoint: Equatable {
        public let id: String
        public let timestamp: Date
        public let source: String
        public let locality: String?
        public init(id: String, timestamp: Date, source: String, locality: String?) {
            self.id        = id
            self.timestamp = timestamp
            self.source    = source
            self.locality  = locality
        }
    }

    // MARK: - Internals

    private static func greedyCluster(_ sorted: [CapturePoint]) -> [[CapturePoint]] {
        var clusters: [[CapturePoint]] = []
        var current: [CapturePoint] = []
        for cap in sorted {
            if current.isEmpty { current.append(cap); continue }
            let prev = current.last!
            let gap  = cap.timestamp.timeIntervalSince(prev.timestamp)
            if gap > cutGapSeconds && current.count >= 3 {
                clusters.append(current)
                current = [cap]
            } else {
                current.append(cap)
            }
        }
        if !current.isEmpty { clusters.append(current) }
        return clusters
    }

    private static func score(_ cluster: [CapturePoint]) -> Double {
        let kinds = Set(cluster.map(\.source)).count
        return Double(cluster.count) * Double(max(1, kinds))
    }

    private static func buildSuggestion(from cluster: [CapturePoint]) -> StorySuggestion {
        let first = cluster.first!
        let last  = cluster.last!
        let id    = stableId(first: first.id, last: last.id)

        let (title, reason) = titleAndReason(cluster: cluster, first: first, last: last)

        return StorySuggestion(
            id:            id,
            reason:        reason,
            candidateRefs: cluster.map(\.id),
            score:         score(cluster)
        )
    }

    private static func stableId(first: String, last: String) -> String {
        let data = Data("\(first)\(last)".utf8)
        let digest = Insecure.SHA1.hash(data: data)
        return digest.map { String(format: "%02x", $0) }.joined().prefix(16).description
    }

    /// Build `(title, reason)` per spec §6. Title gets the locality
    /// prefix when ≥ 60% of the cluster's captures share one.
    private static func titleAndReason(
        cluster: [CapturePoint],
        first: CapturePoint,
        last: CapturePoint
    ) -> (String, String) {
        let dateRange = formatDateRange(start: first.timestamp, end: last.timestamp)

        let dominantLocality: String? = {
            let buckets = Dictionary(grouping: cluster) { $0.locality ?? "" }
            guard let (key, items) = buckets.max(by: { $0.value.count < $1.value.count }) else { return nil }
            if key.isEmpty { return nil }
            let share = Double(items.count) / Double(cluster.count)
            return share >= 0.6 ? key : nil
        }()
        let title: String = dominantLocality.map { "\($0), \(dateRange)" } ?? "Captures, \(dateRange)"

        let scanCount   = cluster.filter { $0.source == "scan" }.count
        let importCount = cluster.filter { $0.source == "import" }.count
        let parts: [String] = {
            var out: [String] = []
            if scanCount > 0   { out.append("\(scanCount) \(scanCount == 1 ? "scan" : "scans")") }
            if importCount > 0 { out.append("\(importCount) \(importCount == 1 ? "photo" : "photos")") }
            if out.isEmpty     { out.append("\(cluster.count) \(cluster.count == 1 ? "capture" : "captures")") }
            return out
        }()
        let kindClause = parts.joined(separator: " and ")
        let reason = "\(kindClause), \(dateRange)"

        // Title isn't part of the StorySuggestion struct in v3 — the
        // shelf surfaces only `reason`. We return both so the preview
        // screen can pick one up via a future addition. For now the
        // preview derives its title from the reason.
        _ = title
        return (title, reason)
    }

    /// Both formatters + the comparison calendar pin to UTC so the
    /// engine emits the same date range as the Android side for the
    /// same input — Android reads via `OffsetDateTime.toLocalDate()`
    /// which preserves the offset. See `StoryDayMarkers.parseIso`
    /// for the same rationale.
    private static let monthDayFmt: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "MMM d"
        f.timeZone   = TimeZone(identifier: "UTC")
        return f
    }()
    private static let dayOnlyFmt: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "d"
        f.timeZone   = TimeZone(identifier: "UTC")
        return f
    }()

    private static func formatDateRange(start: Date, end: Date) -> String {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "UTC")!
        if cal.isDate(start, inSameDayAs: end) {
            return monthDayFmt.string(from: start)
        }
        let sameMonth = cal.component(.month, from: start) == cal.component(.month, from: end)
            && cal.component(.year, from: start) == cal.component(.year, from: end)
        if sameMonth {
            return "\(monthDayFmt.string(from: start))–\(dayOnlyFmt.string(from: end))"
        }
        return "\(monthDayFmt.string(from: start)) – \(monthDayFmt.string(from: end))"
    }

    // MARK: - DB read

    private static func readCaptures(userId: String, database: QuickInkDatabase) async throws -> [CapturePoint] {
        try await database.dbQueue.read { db in
            let rows = try Row.fetchAll(db, sql: """
                SELECT id, source, created_at, locality
                FROM captures
                WHERE user_id = ? AND deleted_at IS NULL
                ORDER BY created_at ASC
                """, arguments: [userId])
            let parsers: [ISO8601DateFormatter] = [
                { let f = ISO8601DateFormatter(); f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]; return f }(),
                { let f = ISO8601DateFormatter(); f.formatOptions = [.withInternetDateTime]; return f }(),
            ]
            func parse(_ iso: String) -> Date? {
                for p in parsers { if let d = p.date(from: iso) { return d } }
                return nil
            }
            var out: [CapturePoint] = []
            out.reserveCapacity(rows.count)
            for row in rows {
                let iso: String = row["created_at"]
                guard let dt = parse(iso) else { continue }
                out.append(CapturePoint(
                    id:        row["id"],
                    timestamp: dt,
                    source:    (row["source"] as String?) ?? "scan",
                    locality:  row["locality"] as String?
                ))
            }
            return out
        }
    }
}
