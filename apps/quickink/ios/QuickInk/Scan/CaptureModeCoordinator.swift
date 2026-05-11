/*
 * CaptureModeCoordinator.swift
 *
 * Source of truth for "which capture surface is mounted" on
 * QuickCaptureScreen. Owns the SwiftUI state (via @Published),
 * the persistence hook to `SettingsState.lastCaptureMode`, and
 * the analytics fan-out for `capture_mode_selected` /
 * `capture_mode_switched`.
 *
 * `@MainActor ObservableObject` so SwiftUI views bind directly.
 * The host constructs one with the user's persisted starting
 * mode, hands it to the toggle + surface dispatch, and observes
 * `mode` via `@ObservedObject`. Trivial to unit-test from
 * XCTest: construct with fakes for the persist + analytics
 * hooks, drive `select(_:)`, assert on the recorded calls.
 *
 * "Swap the detector / swap the overlay" in the spec collapses
 * into the SwiftUI view tree itself — the host renders one of
 * two surface views keyed on `mode`. Detector + overlay
 * lifecycles ride on each surface's `@StateObject` /
 * `.onDisappear`. No extra wiring needed in this class.
 *
 * Mirror of Android `CaptureModeCoordinator.kt`.
 */

import Foundation
import SwiftUI

@MainActor
public final class CaptureModeCoordinator: ObservableObject {

    /// Slim two-method facade. Lets unit tests inject a recorder
    /// without depending on the static `CaptureAnalytics` enum.
    public protocol Analytics {
        func modeSelected(_ mode: CaptureMode)
        func modeSwitched(from: CaptureMode, to: CaptureMode)
    }

    private struct DefaultAnalytics: Analytics {
        func modeSelected(_ mode: CaptureMode) {
            CaptureAnalytics.modeSelected(mode)
        }
        func modeSwitched(from: CaptureMode, to: CaptureMode) {
            CaptureAnalytics.modeSwitched(from: from, to: to)
        }
    }

    @Published public private(set) var mode: CaptureMode

    private let persist: (CaptureMode) -> Void
    private let analytics: Analytics

    /// Default-argument values must be at least as visible as
    /// the initializer. The `DefaultAnalytics` adapter is
    /// `private`, so we expose it through a `nil` sentinel
    /// and materialize the real instance inside the body
    /// instead of inlining it at the call site.
    public init(
        initial: CaptureMode,
        persist: @escaping (CaptureMode) -> Void = { _ in },
        analytics: Analytics? = nil,
    ) {
        self.mode = initial
        self.persist = persist
        self.analytics = analytics ?? DefaultAnalytics()
    }

    /// Toggle to `next`. No-op when the user re-taps their
    /// current mode — saves a redundant persist + analytics
    /// fan-out. Fires `_mode_switched` before `_mode_selected`
    /// so dashboards that count transitions see them in causal
    /// order.
    public func select(_ next: CaptureMode) {
        let previous = mode
        guard previous != next else { return }
        mode = next
        persist(next)
        analytics.modeSwitched(from: previous, to: next)
        analytics.modeSelected(next)
    }
}
