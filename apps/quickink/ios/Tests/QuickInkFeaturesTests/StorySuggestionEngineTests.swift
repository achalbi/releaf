/*
 * StorySuggestionEngineTests.swift
 *
 * Coverage for `StorySuggestionEngine.compute(captures:dismissed:)` —
 * the pure-function path the DB-backed overload delegates to. Tests
 * the §3–§7 rules of `shared/algorithms/story-suggestions.md`:
 *
 *   §3 cut rule  — gap > 18h AND previous cluster has ≥ 3 items
 *   §4 filter    — drop clusters with < 4 items
 *   §5 scoring   — itemCount × max(1, distinctSources); recency tiebreak
 *   §6 reason    — "N scans and M photos, MMM d–d"
 *   §7 dismissal — session set suppresses; engine returns runner-up
 *   §9 determinism — stable id across runs
 *
 * Mirror of `StorySuggestionEngineTest.kt`.
 */

import XCTest
@testable import QuickInkFeatures

final class StorySuggestionEngineTests: XCTestCase {

    // MARK: - Fixtures

    private func point(
        id: String,
        date: String,
        source: String = "scan",
        locality: String? = nil
    ) -> StorySuggestionEngine.CapturePoint {
        let fmt = ISO8601DateFormatter()
        fmt.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let parsed = fmt.date(from: date) ?? {
            let g = ISO8601DateFormatter()
            g.formatOptions = [.withInternetDateTime]
            return g.date(from: date)!
        }()
        return StorySuggestionEngine.CapturePoint(
            id:        id,
            timestamp: parsed,
            source:    source,
            locality:  locality
        )
    }

    private func iso(_ ymdHms: String) -> String { "\(ymdHms).000Z" }

    // MARK: - Below threshold

    func testEmptyCapturesYieldsNoSuggestion() {
        XCTAssertNil(StorySuggestionEngine.compute(captures: [], dismissed: []))
    }

    func testFewerThanFourCapturesYieldsNoSuggestion() {
        let captures = (0..<3).map { idx in
            point(id: "c\(idx)", date: iso("2026-05-04T0\(idx + 9):00:00"))
        }
        XCTAssertNil(StorySuggestionEngine.compute(captures: captures, dismissed: []))
    }

    // MARK: - Single cluster qualifies

    func testFourTightCapturesYieldOneSuggestion() {
        let captures = (0..<4).map { idx in
            let hh = String(format: "%02d", 10 + idx)
            return point(id: "c\(idx)", date: iso("2026-05-04T\(hh):30:00"))
        }
        let s = StorySuggestionEngine.compute(captures: captures, dismissed: [])
        XCTAssertNotNil(s)
        XCTAssertEqual(s?.candidateRefs.count, 4)
        XCTAssertEqual(s?.candidateRefs, ["c0", "c1", "c2", "c3"])
        XCTAssertTrue(s?.reason.contains("May 4") ?? false, s?.reason ?? "")
    }

    // MARK: - Cut rule

    func testGapOver18HoursAndPriorClusterHasThreeOrMoreItemsCuts() {
        // 4 captures at hour 10..13, then a 20h gap, then 4 more.
        // First cluster qualifies (4 items); cut applies (prev ≥ 3
        // items + gap > 18h). Both clusters should qualify; the
        // engine returns the higher score (or recency tiebreak).
        let day1 = (10..<14).map { hr in
            point(id: "a\(hr)", date: iso("2026-05-04T\(hr):00:00"))
        }
        let day2 = (10..<14).map { hr in
            point(id: "b\(hr)", date: iso("2026-05-05T\(hr):00:00"))
        }
        let s = StorySuggestionEngine.compute(captures: day1 + day2, dismissed: [])
        XCTAssertNotNil(s)
        // Recency tiebreak — same size, same diversity, prefer later cluster (b).
        XCTAssertEqual(s?.candidateRefs, day2.map(\.id))
    }

    func testCutDoesNotApplyWhenPriorClusterHasFewerThanThreeItems() {
        // 2 items, then a 30h gap, then 4 more. The "previous
        // cluster has ≥ 3 items" guard means we DON'T cut after the
        // 2nd item, so all 6 land in one cluster.
        let head = [
            point(id: "a1", date: iso("2026-05-04T09:00:00")),
            point(id: "a2", date: iso("2026-05-04T10:00:00")),
        ]
        let tail = (10..<14).map { hr in
            point(id: "b\(hr)", date: iso("2026-05-05T\(hr):00:00"))
        }
        let s = StorySuggestionEngine.compute(captures: head + tail, dismissed: [])
        XCTAssertNotNil(s)
        XCTAssertEqual(s?.candidateRefs.count, 6)
    }

    // MARK: - Scoring

    func testMixedSourcesScoreHigherThanSingleSourceOfSameSize() {
        // Cluster A: 4 scans (score = 4 * 1 = 4)
        // Cluster B: 4 mixed (2 scans + 2 imports, score = 4 * 2 = 8)
        // Engine should prefer B.
        let clusterA = (0..<4).map { hr in
            point(id: "a\(hr)", date: iso("2026-05-04T0\(hr + 1):00:00"), source: "scan")
        }
        let clusterB = (0..<4).map { hr in
            point(
                id: "b\(hr)",
                date: iso("2026-05-06T0\(hr + 1):00:00"),
                // Alternate scan / import for diversity = 2.
                source: hr % 2 == 0 ? "scan" : "import"
            )
        }
        let s = StorySuggestionEngine.compute(captures: clusterA + clusterB, dismissed: [])
        XCTAssertEqual(s?.candidateRefs.first, "b0")
    }

    // MARK: - Reason format

    func testReasonStringIncludesScanAndPhotoCounts() {
        let captures = [
            point(id: "s1", date: iso("2026-05-04T09:00:00"), source: "scan"),
            point(id: "s2", date: iso("2026-05-04T10:00:00"), source: "scan"),
            point(id: "s3", date: iso("2026-05-04T11:00:00"), source: "scan"),
            point(id: "p1", date: iso("2026-05-04T12:00:00"), source: "import"),
        ]
        let s = StorySuggestionEngine.compute(captures: captures, dismissed: [])
        XCTAssertNotNil(s)
        let reason = s?.reason ?? ""
        XCTAssertTrue(reason.contains("3 scans"),  reason)
        XCTAssertTrue(reason.contains("1 photo"),  reason)
        XCTAssertTrue(reason.contains("May 4"),    reason)
    }

    func testReasonOmitsZeroSideWhenOnlyOneSourcePresent() {
        let captures = (0..<5).map { hr in
            point(id: "s\(hr)", date: iso("2026-05-04T0\(hr + 1):00:00"), source: "scan")
        }
        let s = StorySuggestionEngine.compute(captures: captures, dismissed: [])
        XCTAssertNotNil(s)
        let reason = s?.reason ?? ""
        XCTAssertTrue(reason.contains("5 scans"), reason)
        XCTAssertFalse(reason.contains("photo"),  reason)
    }

    // MARK: - Determinism + dismissal

    func testStableIdAcrossRuns() {
        let captures = (0..<4).map { hr in
            point(id: "c\(hr)", date: iso("2026-05-04T0\(hr + 9):00:00"))
        }
        let first = StorySuggestionEngine.compute(captures: captures, dismissed: [])
        let again = StorySuggestionEngine.compute(captures: captures, dismissed: [])
        XCTAssertNotNil(first)
        XCTAssertEqual(first?.id, again?.id)
    }

    func testDismissedSuggestionReturnsNextBest() {
        // Two qualifying clusters. Dismiss the higher-scored one
        // (or whichever the engine ranks first); engine returns the
        // other.
        let day1 = (10..<14).map { hr in
            point(id: "a\(hr)", date: iso("2026-05-04T\(hr):00:00"))
        }
        let day2 = (10..<14).map { hr in
            point(id: "b\(hr)", date: iso("2026-05-08T\(hr):00:00"))
        }
        let first = StorySuggestionEngine.compute(captures: day1 + day2, dismissed: [])
        XCTAssertNotNil(first)
        let dismissedSet: Set<String> = [first!.id]
        let second = StorySuggestionEngine.compute(captures: day1 + day2, dismissed: dismissedSet)
        XCTAssertNotNil(second)
        XCTAssertNotEqual(first!.id, second!.id)
    }

    func testAllSuggestionsDismissedReturnsNil() {
        let captures = (0..<4).map { hr in
            point(id: "c\(hr)", date: iso("2026-05-04T0\(hr + 9):00:00"))
        }
        let first = StorySuggestionEngine.compute(captures: captures, dismissed: [])
        XCTAssertNotNil(first)
        let dismissed: Set<String> = [first!.id]
        XCTAssertNil(StorySuggestionEngine.compute(captures: captures, dismissed: dismissed))
    }
}
