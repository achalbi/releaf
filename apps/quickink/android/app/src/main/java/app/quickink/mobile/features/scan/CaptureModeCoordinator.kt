/*
 * CaptureModeCoordinator.kt
 *
 * Source of truth for "which capture surface is mounted" on
 * QuickCaptureScreen. Owns the Compose state, the persistence
 * hook to [SettingsPreferences.lastCaptureMode], and the
 * analytics fan-out for `capture_mode_selected` /
 * `capture_mode_switched`.
 *
 * Kept as a plain class (not a ViewModel) so the rest of the
 * scan feature's `remember { … }` ownership pattern carries
 * over — the host composable constructs one with the user's
 * persisted starting mode, hands it to the toggle + surface
 * dispatch, and observes [mode] from Compose. The class is also
 * trivially testable from JUnit: construct with fakes for the
 * persist + analytics hooks, drive `select(…)`, assert on the
 * recorded calls.
 *
 * "Swap the detector / swap the overlay" in the spec collapses
 * into the Compose tree itself — the host renders one of two
 * surface composables keyed on [mode]. The detector and overlay
 * lifecycles ride on the active surface's `DisposableEffect`,
 * which is the Compose-idiomatic way to express "tear down on
 * unmount". No extra wiring in this class.
 *
 * Mirror of iOS `CaptureModeCoordinator.swift`.
 */

package app.quickink.mobile.features.scan

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class CaptureModeCoordinator(
    initial: CaptureMode,
    /**
     * Called on every successful [select] with the new mode.
     * Wired by the screen to [SettingsPreferences.lastCaptureMode]
     * so the next launch opens on the same surface. Defaults to a
     * no-op for tests / previews that don't care about persistence.
     */
    private val persist: (CaptureMode) -> Unit = {},
    /**
     * Analytics fan-out for the two mode-related events. The
     * implementation defaults to the real [CaptureAnalytics]
     * sink; tests inject their own recorder to assert the
     * call order without touching logcat.
     */
    private val analytics: Analytics = DefaultAnalytics,
) {
    /**
     * Slim two-method facade. Lets unit tests inject a recorder
     * without depending on the static [CaptureAnalytics] object.
     */
    interface Analytics {
        fun modeSelected(mode: CaptureMode)
        fun modeSwitched(from: CaptureMode, to: CaptureMode)
    }

    private object DefaultAnalytics : Analytics {
        override fun modeSelected(mode: CaptureMode) =
            CaptureAnalytics.modeSelected(mode)
        override fun modeSwitched(from: CaptureMode, to: CaptureMode) =
            CaptureAnalytics.modeSwitched(from, to)
    }

    /**
     * Backed by Compose state so the host composable re-renders
     * the moment a new mode lands. Reads from outside Compose
     * (e.g. unit tests, [select] internals) snapshot the current
     * value the same way Compose readers do.
     */
    var mode: CaptureMode by mutableStateOf(initial)
        private set

    /**
     * Toggle to [next]. No-op when the user re-taps their
     * current mode — saves a redundant persist + analytics fan-
     * out on every tap-the-already-active-pill. Fires
     * `_mode_switched` before `_mode_selected` so dashboards that
     * count transitions see them in causal order.
     */
    fun select(next: CaptureMode) {
        val previous = mode
        if (previous == next) return
        mode = next
        persist(next)
        analytics.modeSwitched(previous, next)
        analytics.modeSelected(next)
    }
}
