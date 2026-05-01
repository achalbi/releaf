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
import androidx.compose.ui.platform.LocalContext
import app.quickink.mobile.R
import app.releaf.mobile.auth.AuthStore
import app.releaf.mobile.auth.ConsentRequiredException
import app.releaf.mobile.auth.GoogleAuthError
import app.releaf.mobile.auth.GoogleIdTokenInfo
import app.releaf.mobile.auth.RealGoogleAuthClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    val scope  = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }

    val consentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val identity = pendingIdentity.value
        pendingIdentity.value = null
        if (identity == null) {
            authStore.cancelSignIn()
            return@rememberLauncherForActivityResult
        }
        if (result.resultCode != Activity.RESULT_OK) {
            authStore.cancelSignIn()
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            try {
                val session = client.completeConsent(identity, result.data)
                authStore.adoptSession(session)
            } catch (_: GoogleAuthError.Cancelled) {
                authStore.cancelSignIn()
            } catch (e: Exception) {
                authStore.failSignIn(e.localizedMessage ?: "Sign-in failed")
            }
        }
    }

    return {
        authStore.beginExternalSignIn()
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
                authStore.cancelSignIn()
            } catch (e: Exception) {
                authStore.failSignIn(e.localizedMessage ?: "Sign-in failed")
            }
        }
    }
}
