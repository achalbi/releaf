/*
 * CaptureAnalytics.kt
 *
 * Lightweight event sink for the capture-mode UX. Four events:
 *
 *   capture_mode_selected   user picked a surface (fires on first
 *                           render with the persisted mode, then on
 *                           every pill tap that toggles to a new mode)
 *   capture_mode_switched   toggle changed from one mode to another
 *                           (fired alongside _selected with from/to
 *                           — _selected stays the canonical "user
 *                           is in this mode" signal)
 *   capture_auto_fired      Business Card surface auto-captured on
 *                           the stability gate
 *   capture_manual_fired    user tapped the shutter in either mode
 *
 * Today these write to logcat only. The existing analytics outbox
 * (`AnalyticsRepository.enqueueCapture`) is bound to a single Rails
 * endpoint with a fixed payload shape, so adding event types here
 * would require a server change in lockstep — out of scope for the
 * capture-mode feature. When the backend gains a generic event
 * channel, [track] is the single place to swap in `enqueue…`.
 *
 * Mirror of iOS `CaptureAnalytics.swift`.
 */

package app.quickink.mobile.features.scan

import android.util.Log

object CaptureAnalytics {

    private const val TAG = "CaptureAnalytics"

    fun modeSelected(mode: CaptureMode) {
        track("capture_mode_selected", mapOf("mode" to mode.analyticsKey))
    }

    fun modeSwitched(from: CaptureMode, to: CaptureMode) {
        if (from == to) return
        track(
            "capture_mode_switched",
            mapOf("from" to from.analyticsKey, "to" to to.analyticsKey),
        )
    }

    fun autoFired(mode: CaptureMode, timeToLockMs: Long) {
        track(
            "capture_auto_fired",
            mapOf(
                "mode"            to mode.analyticsKey,
                "time_to_lock_ms" to timeToLockMs.toString(),
            ),
        )
    }

    fun manualFired(mode: CaptureMode) {
        track("capture_manual_fired", mapOf("mode" to mode.analyticsKey))
    }

    /**
     * Single send point. Logcat-only for now; replace the body
     * with an `AnalyticsRepository.enqueueX(...)` call once a
     * generic event channel exists on the backend. Params are
     * stringified into a single line so log filtering in Android
     * Studio reads as one event per line.
     */
    private fun track(event: String, params: Map<String, String>) {
        val rendered = params.entries.joinToString(separator = " ") { (k, v) -> "$k=$v" }
        Log.i(TAG, "$event $rendered")
    }
}
