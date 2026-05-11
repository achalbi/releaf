/*
 * CaptureModeCoordinatorTests.swift
 *
 * Mirror of Android `CaptureModeCoordinatorTest.kt` — verifies
 * the coordinator's select() persists + fires analytics, no-ops
 * on same-mode select, and emits _mode_switched before
 * _mode_selected so downstream aggregators see causal order.
 */

import XCTest
@testable import QuickInkFeatures

final class CaptureModeCoordinatorTests: XCTestCase {

    private final class RecordingAnalytics: CaptureModeCoordinator.Analytics {
        var events: [String] = []
        func modeSelected(_ mode: CaptureMode) {
            events.append("selected:\(mode.analyticsKey)")
        }
        func modeSwitched(from: CaptureMode, to: CaptureMode) {
            events.append("switched:\(from.analyticsKey)->\(to.analyticsKey)")
        }
    }

    @MainActor
    func testInitialModeIsReflected() {
        let coordinator = CaptureModeCoordinator(
            initial:   .businessCard,
            persist:   { _ in },
            analytics: RecordingAnalytics(),
        )
        XCTAssertEqual(coordinator.mode, .businessCard)
    }

    @MainActor
    func testSelectToNewModePersistsAndFiresEventsInCausalOrder() {
        var persisted: [CaptureMode] = []
        let analytics = RecordingAnalytics()
        let coordinator = CaptureModeCoordinator(
            initial:   .document,
            persist:   { persisted.append($0) },
            analytics: analytics,
        )
        coordinator.select(.businessCard)

        XCTAssertEqual(coordinator.mode, .businessCard)
        XCTAssertEqual(persisted, [.businessCard])
        XCTAssertEqual(
            analytics.events,
            ["switched:document->business_card", "selected:business_card"],
        )
    }

    @MainActor
    func testSelectToSameModeIsNoOp() {
        var persisted: [CaptureMode] = []
        let analytics = RecordingAnalytics()
        let coordinator = CaptureModeCoordinator(
            initial:   .document,
            persist:   { persisted.append($0) },
            analytics: analytics,
        )
        coordinator.select(.document)

        XCTAssertEqual(coordinator.mode, .document)
        XCTAssertEqual(persisted, [])
        XCTAssertEqual(analytics.events, [])
    }

    @MainActor
    func testRoundTripDocumentBusinessCardDocumentKeepsStateCoherent() {
        var persisted: [CaptureMode] = []
        let analytics = RecordingAnalytics()
        let coordinator = CaptureModeCoordinator(
            initial:   .document,
            persist:   { persisted.append($0) },
            analytics: analytics,
        )
        coordinator.select(.businessCard)
        coordinator.select(.document)
        XCTAssertEqual(coordinator.mode, .document)
        XCTAssertEqual(persisted, [.businessCard, .document])
        XCTAssertEqual(
            analytics.events,
            [
                "switched:document->business_card",
                "selected:business_card",
                "switched:business_card->document",
                "selected:document",
            ],
        )
    }
}
