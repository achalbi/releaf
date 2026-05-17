/*
 * StoryDayMarkersTests.swift
 *
 * Coverage for `StoryDayMarkers.derive` — the helper that walks a
 * story's items and emits a marker each time the effective date OR
 * time-of-day bucket changes from the previous item. The derivation
 * is shared verbatim between iOS + Android; this file mirrors
 * `StoryDayMarkersTest.kt`.
 */

import XCTest
@testable import QuickInkFeatures

final class StoryDayMarkersTests: XCTestCase {

    // MARK: - Fixtures

    private func item(id: String, occurredAt: String? = nil, createdAt: String = isoUtc("2026-05-01T09:00:00")) -> StoryItem {
        StoryItem(
            id:          id,
            storyId:     "story-1",
            position:    1,
            kind:        StoryItem.Kind.textBlock.rawValue,
            refId:       nil,
            text:        nil,
            caption:     nil,
            occurredAt:  occurredAt,
            layout:      StoryItem.Layout.full.rawValue,
            createdAt:   createdAt,
            updatedAt:   createdAt,
            dirty:       true,
            deletedAt:   nil
        )
    }

    /// Build an ISO-8601 UTC timestamp like `2026-05-04T18:00:00.000Z`.
    private static func isoUtc(_ ymdHms: String) -> String {
        // Accept "YYYY-MM-DDTHH:MM:SS"; emit with millis + Z.
        return "\(ymdHms).000Z"
    }
    private func isoUtc(_ ymdHms: String) -> String { Self.isoUtc(ymdHms) }

    // MARK: - Basic shape

    func testEmptyItemsYieldsNoMarkers() {
        XCTAssertEqual(StoryDayMarkers.derive(from: []), [])
    }

    func testSingleItemYieldsOneOpeningMarker() {
        let items = [item(id: "a", createdAt: isoUtc("2026-05-04T18:00:00"))]
        let markers = StoryDayMarkers.derive(from: items)
        XCTAssertEqual(markers.count, 1)
        XCTAssertEqual(markers[0].precedingItemId, "a")
        XCTAssertEqual(markers[0].label, "— MAY 4 · EVENING —")
    }

    // MARK: - Same vs different bucket

    func testSameDayAndBucketCollapsesToOneMarker() {
        let items = [
            item(id: "a", createdAt: isoUtc("2026-05-04T18:00:00")), // EVENING
            item(id: "b", createdAt: isoUtc("2026-05-04T19:30:00")), // EVENING
        ]
        let markers = StoryDayMarkers.derive(from: items)
        XCTAssertEqual(markers.count, 1)
        XCTAssertEqual(markers[0].precedingItemId, "a")
    }

    func testSameDayDifferentBucketEmitsTwoMarkers() {
        let items = [
            item(id: "a", createdAt: isoUtc("2026-05-04T18:00:00")), // EVENING
            item(id: "b", createdAt: isoUtc("2026-05-04T22:00:00")), // NIGHT
        ]
        let markers = StoryDayMarkers.derive(from: items)
        XCTAssertEqual(markers.count, 2)
        XCTAssertEqual(markers[0].label, "— MAY 4 · EVENING —")
        XCTAssertEqual(markers[1].label, "— MAY 4 · NIGHT —")
        XCTAssertEqual(markers[0].precedingItemId, "a")
        XCTAssertEqual(markers[1].precedingItemId, "b")
    }

    func testDifferentDaysSameBucketEmitsTwoMarkers() {
        let items = [
            item(id: "a", createdAt: isoUtc("2026-05-04T18:00:00")), // EVENING May 4
            item(id: "b", createdAt: isoUtc("2026-05-05T18:00:00")), // EVENING May 5
        ]
        let markers = StoryDayMarkers.derive(from: items)
        XCTAssertEqual(markers.count, 2)
        XCTAssertEqual(markers[0].label, "— MAY 4 · EVENING —")
        XCTAssertEqual(markers[1].label, "— MAY 5 · EVENING —")
    }

    // MARK: - Bucket boundaries

    func testHourBoundariesMatchSpec() {
        // Spec §3: MORNING 05–10, AFTERNOON 11–16, EVENING 17–20, NIGHT 21–04.
        let cases: [(hour: Int, bucket: String)] = [
            ( 5, "MORNING"),
            (10, "MORNING"),
            (11, "AFTERNOON"),
            (16, "AFTERNOON"),
            (17, "EVENING"),
            (20, "EVENING"),
            (21, "NIGHT"),
            ( 0, "NIGHT"),
            ( 4, "NIGHT"),
        ]
        for (hour, expectedBucket) in cases {
            let hh = String(format: "%02d", hour)
            let items = [item(id: "a-\(hour)", createdAt: isoUtc("2026-05-04T\(hh):00:00"))]
            let markers = StoryDayMarkers.derive(from: items)
            XCTAssertEqual(markers.count, 1, "hour=\(hour)")
            XCTAssertTrue(
                markers[0].label.contains(expectedBucket),
                "hour=\(hour) label=\(markers[0].label) expected bucket=\(expectedBucket)"
            )
        }
    }

    // MARK: - occurredAt overrides createdAt

    func testOccurredAtTakesPrecedenceOverCreatedAt() {
        // Two items captured at NIGHT but tagged with MORNING + EVENING
        // occurredAt overrides. Markers should reflect occurredAt.
        let items = [
            item(id: "a", occurredAt: isoUtc("2026-05-04T09:00:00"), createdAt: isoUtc("2026-05-04T23:00:00")),
            item(id: "b", occurredAt: isoUtc("2026-05-04T18:00:00"), createdAt: isoUtc("2026-05-04T23:30:00")),
        ]
        let markers = StoryDayMarkers.derive(from: items)
        XCTAssertEqual(markers.map(\.label), ["— MAY 4 · MORNING —", "— MAY 4 · EVENING —"])
    }

    // MARK: - Bad input

    func testUnparseableTimestampIsSilentlySkipped() {
        let items = [
            item(id: "a", createdAt: isoUtc("2026-05-04T09:00:00")),
            item(id: "garbage", createdAt: "not-an-iso-string"),
            item(id: "c", createdAt: isoUtc("2026-05-04T18:00:00")),
        ]
        let markers = StoryDayMarkers.derive(from: items)
        // "garbage" is dropped from the walk — but the bucket state
        // carries over the prior item, so "c"'s EVENING bucket is
        // a new bucket relative to "a"'s MORNING and emits a marker.
        XCTAssertEqual(markers.map(\.precedingItemId), ["a", "c"])
        XCTAssertEqual(markers.map(\.label), ["— MAY 4 · MORNING —", "— MAY 4 · EVENING —"])
    }

    // MARK: - Months across the year

    func testAllMonthsRenderAsThreeLetterUppercase() {
        // Spot-check a few months to make sure the abbrev table is
        // wired right.
        let cases: [(iso: String, month: String)] = [
            ("2026-01-15T09:00:00", "JAN"),
            ("2026-06-15T09:00:00", "JUN"),
            ("2026-12-15T09:00:00", "DEC"),
        ]
        for (iso, expected) in cases {
            let items = [item(id: "a", createdAt: isoUtc(iso))]
            let markers = StoryDayMarkers.derive(from: items)
            XCTAssertEqual(markers.count, 1)
            XCTAssertTrue(markers[0].label.hasPrefix("— \(expected)"), markers[0].label)
        }
    }
}
