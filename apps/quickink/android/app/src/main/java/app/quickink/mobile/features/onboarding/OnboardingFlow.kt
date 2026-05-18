/*
 * OnboardingFlow.kt
 *
 * Top-level container for QuickInk's 3-screen onboarding. Drives
 * an `OnboardingState` and routes to the active step's screen.
 * Routing is state-driven (`when (state.step)`) rather than
 * NavHost-based — the screens advance forward only and a full nav
 * graph would be ceremony for three forward-only steps.
 *
 * The flow is presented by `QuickInkRoot` when
 * `OnboardingPreferences.isCompleted` is false; on completion it
 * sets the preference and calls `onComplete`, after which
 * `QuickInkRoot` swaps to the main shell.
 *
 * Mirror of iOS `OnboardingFlow.swift`.
 */

package app.quickink.mobile.features.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import app.quickink.mobile.features.settings.SettingsPreferences
import app.releaf.mobile.auth.AuthStore

@Composable
fun OnboardingFlow(
    preferences: OnboardingPreferences,
    authStore: AuthStore,
    onComplete: () -> Unit,
) {
    val state = remember { OnboardingState() }
    val context = LocalContext.current
    val settings = remember { SettingsPreferences(context) }

    when (state.step) {
        OnboardingState.Step.Welcome ->
            WelcomeScreen(onContinue = state::advance)

        OnboardingState.Step.Permissions ->
            PermissionsScreen(onContinue = state::advance)

        OnboardingState.Step.Location ->
            LocationPermissionScreen(onContinue = state::advance)

        OnboardingState.Step.Languages ->
            LanguagesScreen(
                state      = state,
                onContinue = state::advance,
            )

        OnboardingState.Step.SignIn ->
            SignInScreen(
                state      = state,
                authStore  = authStore,
                onSignedIn = {
                    preferences.isCompleted = true
                    // Persist the user's Drive backup choice into
                    // the settings keyspace so the Settings screen
                    // reads it on first open. Onboarding state
                    // itself is in-memory and discarded after
                    // sign-in completes.
                    settings.driveBackupEnabled = state.driveBackupEnabled
                    onComplete()
                },
            )
    }
}
