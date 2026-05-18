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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import app.quickink.mobile.QuickInkApp
import app.quickink.mobile.data.profile.ProfileSettingsEntity
import app.quickink.mobile.data.voicenote.TranscriptionLanguages
import app.quickink.mobile.features.settings.SettingsPreferences
import app.releaf.mobile.auth.AuthState
import app.releaf.mobile.auth.AuthStore
import app.releaf.mobile.data.common.IsoClock
import kotlinx.coroutines.launch

@Composable
fun OnboardingFlow(
    preferences: OnboardingPreferences,
    authStore: AuthStore,
    onComplete: () -> Unit,
) {
    val state = remember { OnboardingState() }
    val context = LocalContext.current
    val settings = remember { SettingsPreferences(context) }
    val coroutineScope = rememberCoroutineScope()

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
                    // Commit the picked transcription-language
                    // allowlist into the now-keyable profile_settings
                    // row. Run on a launched coroutine — the sign-in
                    // callback returns synchronously to advance the
                    // flow; the DB write isn't user-blocking.
                    val signedIn = authStore.state.value as? AuthState.SignedIn
                    val userId = signedIn?.session?.userId
                    if (userId != null) {
                        val ordered = TranscriptionLanguages.supported
                            .filter { it.code in state.selectedLanguageCodes }
                        val encoded = TranscriptionLanguages.encode(ordered)
                        val app = context.applicationContext as QuickInkApp
                        val dao = app.database.profileSettingsDao()
                        coroutineScope.launch {
                            runCatching {
                                val now = IsoClock.nowIso()
                                val existing = dao.findByUser(userId)
                                if (existing == null) {
                                    dao.upsertLocal(
                                        ProfileSettingsEntity(
                                            id                     = userId,
                                            userId                 = userId,
                                            displayName            = null,
                                            phoneNumber            = null,
                                            personalityPunchline   = null,
                                            transcriptionLanguages = encoded,
                                            photoLocalUri          = null,
                                            photoDriveFileId       = null,
                                            photoUpdatedAt         = null,
                                            driveFileId            = null,
                                            createdAt              = now,
                                            updatedAt              = now,
                                            dirty                  = true,
                                            deletedAt              = null,
                                        )
                                    )
                                } else {
                                    dao.setTranscriptionLanguages(
                                        id        = userId,
                                        codes     = encoded,
                                        timestamp = now,
                                    )
                                }
                            }
                        }
                    }
                    onComplete()
                },
            )
    }
}
