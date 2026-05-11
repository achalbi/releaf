/*
 * CaptureAnalytics.swift
 *
 * Lightweight event sink for the capture-mode UX. Four events:
 *
 *   capture_mode_selected   user picked a surface (fires on first
 *                           render with the persisted mode, then on
 *                           every pill tap that toggles to a new mode)
 *   capture_mode_switched   toggle changed from one mode to another
 *   capture_auto_fired      Business Card surface auto-captured on
 *                           the stability gate
 *   capture_manual_fired    user tapped the shutter in either mode
 *
 * Today these write to OSLog only. The existing analytics
 * outbox (`AnalyticsRepository.enqueueCapture`) is bound to a
 * single Rails endpoint with a fixed payload shape, so adding
 * event types here would require a server change in lockstep —
 * out of scope for the capture-mode feature. When the backend
 * gains a generic event channel, [track] is the single place
 * to swap in `enqueue…`.
 *
 * Mirror of Android `CaptureAnalytics.kt`.
 */

import Foundation
import OSLog

public enum CaptureAnalytics {

    private static let logger = Logger(
        subsystem: Bundle.main.bundleIdentifier ?? "app.quickink.mobile",
        category:  "CaptureAnalytics",
    )

    public static func modeSelected(_ mode: CaptureMode) {
        track("capture_mode_selected", params: ["mode": mode.analyticsKey])
    }

    public static func modeSwitched(from: CaptureMode, to: CaptureMode) {
        guard from != to else { return }
        track(
            "capture_mode_switched",
            params: ["from": from.analyticsKey, "to": to.analyticsKey],
        )
    }

    public static func autoFired(mode: CaptureMode, timeToLockMs: Int) {
        track(
            "capture_auto_fired",
            params: [
                "mode":            mode.analyticsKey,
                "time_to_lock_ms": String(timeToLockMs),
            ],
        )
    }

    public static func manualFired(mode: CaptureMode) {
        track("capture_manual_fired", params: ["mode": mode.analyticsKey])
    }

    private static func track(_ event: String, params: [String: String]) {
        // Sort the keys so the rendered line is stable across
        // runs — easier to grep / diff in Console.app.
        let rendered = params
            .sorted { $0.key < $1.key }
            .map { "\($0.key)=\($0.value)" }
            .joined(separator: " ")
        logger.info("\(event, privacy: .public) \(rendered, privacy: .public)")
    }
}
