/*
 * OnboardingState.kt
 *
 * State machine for QuickInk's 3-screen onboarding (welcome /
 * permissions / sign-in). Steps advance forward only; there's no
 * "back" affordance in this v1 because the screens are
 * informational + a single-tap sign-in. If/when a multi-step
 * permissions screen lands, add a `previous()` and the matching
 * UI affordance.
 *
 * Mirror of iOS `OnboardingState.swift`. Persistence is delegated
 * to `OnboardingPreferences` — kept separate so the state holder
 * can be Compose-state-only (no Context capture, friendly to
 * `@Preview`s).
 */

package app.quickink.mobile.features.onboarding

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class OnboardingState {

    enum class Step { Welcome, Permissions, SignIn }

    var step by mutableStateOf(Step.Welcome)
        private set

    /**
     * Drive backup preference toggled on the sign-in screen. Held
     * locally for now; the eventual Settings screen (Slice 5)
     * persists this through `UiPreferences` or QuickInk's own
     * OnboardingPreferences.
     */
    var driveBackupEnabled by mutableStateOf(true)

    fun advance() {
        step = when (step) {
            Step.Welcome     -> Step.Permissions
            Step.Permissions -> Step.SignIn
            Step.SignIn      -> Step.SignIn  // terminal — caller invokes onComplete instead
        }
    }
}
