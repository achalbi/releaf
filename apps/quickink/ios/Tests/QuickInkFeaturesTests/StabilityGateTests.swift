/*
 * StabilityGateTests.swift
 *
 * XCTest mirror of `StabilityGateTest.kt` — drives the clock
 * manually so debounce + streak elapsed are testable without
 * sleeping. The detector itself isn't exercised; the gate's
 * input is "a quad" and the test feeds quads directly.
 */

import XCTest
@testable import QuickInkFeatures

final class StabilityGateTests: XCTestCase {

    private func quad(offset: Float = 0) -> DetectedQuad {
        DetectedQuad(
            tl: Point2f(x: 10  + offset, y: 10 + offset),
            tr: Point2f(x: 110 + offset, y: 10 + offset),
            br: Point2f(x: 110 + offset, y: 70 + offset),
            bl: Point2f(x: 10  + offset, y: 70 + offset),
        )
    }

    func testGateFiresOnThirdConsecutiveWithinThresholdVote() {
        var now: Int64 = 0
        let gate = StabilityGate(perCornerDriftThresholdPx: 5, clock: { now })
        XCTAssertFalse(gate.vote(quad(offset: 0)))   ; now += 30
        XCTAssertFalse(gate.vote(quad(offset: 1)))   ; now += 30
        XCTAssertTrue (gate.vote(quad(offset: 2)))
    }

    func testGateRejectsVotesThatDriftPastThreshold() {
        var now: Int64 = 0
        let gate = StabilityGate(perCornerDriftThresholdPx: 5, clock: { now })
        _ = gate.vote(quad(offset: 0)); now += 30
        _ = gate.vote(quad(offset: 2)); now += 30
        XCTAssertFalse(gate.vote(quad(offset: 20)))
    }

    func testGateDebouncesAfterFiring() {
        var now: Int64 = 0
        let gate = StabilityGate(perCornerDriftThresholdPx: 5, debounceMs: 1000, clock: { now })
        _ = gate.vote(quad(offset: 0)); now += 30
        _ = gate.vote(quad(offset: 1)); now += 30
        XCTAssertTrue(gate.vote(quad(offset: 2)))
        now += 500
        XCTAssertFalse(gate.vote(quad(offset: 3)))
        XCTAssertFalse(gate.vote(quad(offset: 4)))
        XCTAssertFalse(gate.vote(quad(offset: 5)))
        // Step past the debounce window and re-accumulate.
        now += 1000
        XCTAssertFalse(gate.vote(quad(offset: 6))); now += 30
        XCTAssertFalse(gate.vote(quad(offset: 7))); now += 30
        XCTAssertTrue (gate.vote(quad(offset: 8)))
    }

    func testResetClearsBufferedStreak() {
        var now: Int64 = 0
        let gate = StabilityGate(perCornerDriftThresholdPx: 5, clock: { now })
        _ = gate.vote(quad(offset: 0)); now += 30
        _ = gate.vote(quad(offset: 1)); now += 30
        gate.reset()
        XCTAssertFalse(gate.vote(quad(offset: 2))); now += 30
        XCTAssertFalse(gate.vote(quad(offset: 3))); now += 30
        XCTAssertTrue (gate.vote(quad(offset: 4)))
    }

    func testStreakElapsedTracksTimeSinceFirstVote() {
        var now: Int64 = 1000
        let gate = StabilityGate(perCornerDriftThresholdPx: 5, clock: { now })
        _ = gate.vote(quad(offset: 0))
        now = 1080
        XCTAssertEqual(gate.streakElapsedMs(), 80)
    }
}
