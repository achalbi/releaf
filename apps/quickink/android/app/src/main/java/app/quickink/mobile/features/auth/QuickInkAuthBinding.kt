/*
 * QuickInkAuthBinding.kt
 *
 * Compose hook that drives the real Google Sign-In flow. Mirror
 * of Releaf's `auth/GoogleSignInBinding.kt` — the binding owns an
 * `ActivityResultLauncher` for the Drive-scope consent
 * PendingIntent that AuthorizationClient may return, and re-
 * entrantly calls `RealGoogleAuthClient.completeConsent` on
 * callback.
 *
 * Usage (from a composable):
 *
 *     val signIn = rememberQuickInkSignInAction(authStore = authStore)
 *     OnboardingPrimaryButton("Sign in with Google", onClick = signIn)
 *
 * The binding checks `R.string.google_web_client_id` — when it's
 * still the `REPLACE_WITH_…` placeholder, falls through to
 * `AuthStore.signIn()` which uses the StubGoogleAuthClient. Keeps
 * the app buildable + previewable without QuickInk's Google Cloud
 * credentials checked in; flips automatically once the resource
 * is populated.
 *
 * The promotion path — moving this binding into `:shared:auth` so
 * Releaf and QuickInk share a single hook with the webClientId
 * passed in — is a follow-up; for now, app-target lift keeps the
 * R-class import (which is per-app) clean.
 */

package app.quickink.mobile.features.auth

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import app.quickink.mobile.R
import app.releaf.mobile.auth.AuthState
import app.releaf.mobile.auth.AuthStore
import app.releaf.mobile.auth.ConsentRequiredException
import app.releaf.mobile.auth.GoogleAuthError
import app.releaf.mobile.auth.GoogleIdTokenInfo
import app.releaf.mobile.auth.RealGoogleAuthClient
import kotlinx.coroutines.launch

/**
 * Build a sign-in callback that, when invoked, drives the full
 * real auth flow (Credential Manager → AuthorizationClient →
 * Drive scope), handling the consent PendingIntent via an
 * ActivityResultLauncher.
 *
 * Returns a `() -> Unit` callers attach to a sign-in button. Side
 * effects flow through [AuthStore.beginExternalSignIn] /
 * [AuthStore.adoptSession] / [AuthStore.failSignIn] /
 * [AuthStore.cancelSignIn].
 */
@Composable
fun rememberQuickInkSignInAction(authStore: AuthStore): () -> Unit {
    val context = LocalContext.current
    val activity = context as? Activity
    val webClientId = context.getString(R.string.google_web_client_id)

    // Placeholder guard — on default config, fall back to the
    // stub via AuthStore.signIn() (which uses StubGoogleAuthClient).
    val useStub = webClientId == "REPLACE_WITH_GOOGLE_WEB_CLIENT_ID" || activity == null
    if (useStub) {
        return { authStore.signIn() }
    }

    // Cache identity across the consent round-trip.
    val pendingIdentity = remember { mutableStateOf<GoogleIdTokenInfo?>(null) }

    // One RealGoogleAuthClient per Activity.
    val client = remember(activity) { RealGoogleAuthClient(activity, webClientId) }
    // Compose-bound coroutine scope. The previous manual
    // `CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)`
    // outlived Composable disposal — when SettingsScreen got torn
    // down mid-sign-in (see the routing comment in the returned
    // lambda below), the launcher unregistered but the coroutine
    // kept going, and its `consentLauncher.launch(...)` resumed
    // against a dead launcher and crashed with
    // `IllegalStateException: Attempting to launch an unregistered
    // ActivityResultLauncher`. `rememberCoroutineScope()` cancels
    // cleanly on disposal so the coroutine never reaches the dead
    // launcher.
    val scope  = rememberCoroutineScope()

    // Tracks whether the in-flight sign-in started from a SignedIn
    // session (the Settings "Reconnect" path). When true, cancel /
    // failure paths must NOT flip auth state — see the long comment
    // in the returned lambda below. Stored in a mutableStateOf so
    // the consentLauncher callback (a separate closure) can read it.
    val reconnectInProgress = remember { mutableStateOf(false) }

    val consentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val identity = pendingIdentity.value
        pendingIdentity.value = null
        val wasReconnect = reconnectInProgress.value
        reconnectInProgress.value = false
        if (identity == null) {
            if (!wasReconnect) authStore.cancelSignIn()
            return@rememberLauncherForActivityResult
        }
        if (result.resultCode != Activity.RESULT_OK) {
            if (!wasReconnect) authStore.cancelSignIn()
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            try {
                val session = client.completeConsent(identity, result.data)
                authStore.adoptSession(session)
            } catch (_: GoogleAuthError.Cancelled) {
                if (!wasReconnect) authStore.cancelSignIn()
            } catch (e: Exception) {
                if (!wasReconnect) authStore.failSignIn(e.localizedMessage ?: "Sign-in failed")
                // Reconnect from SignedIn: leave the existing session
                // alone. The AUTH_REJECTED banner stays up; user can
                // retry. Flipping to Failed here would route them to
                // ReSignInGate and discard the session unexpectedly.
            }
        }
    }

    return {
        // Reconnect-aware state transition. When starting from
        // `SignedOut` / `Failed` (the onboarding SignInScreen and the
        // post-onboarding ReSignInGate paths), flip to `SigningIn` so
        // those screens render their in-flight spinner. But when
        // starting from `SignedIn` (the Settings "Reconnect" path
        // launched by the AUTH_REJECTED banner), DO NOT change auth
        // state — QuickInkRoot routes on
        // `authState !is SignedIn -> ReSignInGate`, and a transient
        // SignedIn → SigningIn flip would dispose MainShell mid-flow,
        // unregister the consent launcher hosted by SettingsScreen,
        // and make the eventual `consentLauncher.launch(...)` below
        // crash with "Attempting to launch an unregistered
        // ActivityResultLauncher". The final `adoptSession` still
        // ends on SignedIn either way; for the reconnect path that's
        // a no-op transition.
        val startedSignedIn = authStore.state.value is AuthState.SignedIn
        reconnectInProgress.value = startedSignedIn
        if (!startedSignedIn) {
            authStore.beginExternalSignIn()
        }
        scope.launch {
            try {
                val session = client.signIn()
                authStore.adoptSession(session)
            } catch (consent: ConsentRequiredException) {
                pendingIdentity.value = consent.identity
                consentLauncher.launch(
                    IntentSenderRequest.Builder(consent.intentSender).build()
                )
            } catch (_: GoogleAuthError.Cancelled) {
                if (!startedSignedIn) authStore.cancelSignIn()
                reconnectInProgress.value = false
            } catch (e: Exception) {
                if (!startedSignedIn) authStore.failSignIn(e.localizedMessage ?: "Sign-in failed")
                reconnectInProgress.value = false
            }
        }
    }
}
