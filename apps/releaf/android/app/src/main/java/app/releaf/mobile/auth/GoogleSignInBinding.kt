/*
 * GoogleSignInBinding.kt
 *
 * Compose glue that drives the real Google Sign-In flow from the
 * Sign In screen. Owns an `ActivityResultLauncher` for the Drive-scope
 * consent PendingIntent that AuthorizationClient may return, and
 * re-entrantly calls [RealGoogleAuthClient.completeConsent] on callback.
 *
 * Usage from a composable:
 *
 *     val signIn = rememberGoogleSignInAction(authStore = authStore)
 *     AppButton("Sign in with Google", onClick = signIn)
 *
 * The binding checks the `google_web_client_id` string resource for a
 * real value; when it's still the `REPLACE_WITH_…` placeholder, it
 * falls through to `AuthStore.signIn()` which runs the stub client.
 * This keeps the app previewable / buildable without Google Cloud
 * credentials and lets a real build automatically flip to the live
 * flow once the id is populated.
 */

package app.releaf.mobile.auth

import android.app.Activity
import android.content.Intent
import android.content.IntentSender
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import app.releaf.mobile.R
import kotlinx.coroutines.launch

/**
 * Build a sign-in callback that, when invoked, drives the full real
 * auth flow (Credential Manager → AuthorizationClient → Drive scope),
 * handling the consent PendingIntent via an ActivityResultLauncher.
 *
 * Returns a `() -> Unit` that callers attach to a sign-in button. Side
 * effects flow through [AuthStore.beginExternalSignIn] /
 * [AuthStore.adoptSession] / [AuthStore.failSignIn].
 */
@Composable
fun rememberGoogleSignInAction(authStore: AuthStore): () -> Unit {
    val context = LocalContext.current
    val activity = context as? Activity
    val webClientId = context.getString(R.string.google_web_client_id)

    // Placeholder guard — on default config, fall back to the stub
    // (AuthStore.signIn uses StubGoogleAuthClient).
    val useStub = webClientId == "REPLACE_WITH_GOOGLE_WEB_CLIENT_ID" || activity == null
    if (useStub) {
        return { authStore.signIn() }
    }

    // Cache identity across the consent round-trip.
    val pendingIdentity = remember { mutableStateOf<GoogleIdTokenInfo?>(null) }

    // One RealGoogleAuthClient per Activity.
    val client = remember(activity) { RealGoogleAuthClient(activity, webClientId) }
    // Compose-bound scope so the in-flight coroutine cancels on
    // disposal. A manual
    // `CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)`
    // outlives Composable disposal — if a host that re-renders on
    // AuthState (e.g. a future Settings-level reconnect screen) gets
    // torn down mid-sign-in, the launcher unregisters but the
    // coroutine keeps running, and the eventual
    // `consentLauncher.launch(...)` crashes with
    // `IllegalStateException: Attempting to launch an unregistered
    // ActivityResultLauncher`. Latent today (launcher is hosted at
    // MainActivity level), but the pattern would bite a deeper host.
    val scope = rememberCoroutineScope()

    // Tracks whether the in-flight sign-in started from a SignedIn
    // session (a future "Reconnect" entry point). When true, cancel /
    // failure paths must NOT flip auth state — see the long comment
    // in the returned lambda below. mutableStateOf so the
    // consentLauncher callback (a separate closure) can read it.
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
                // alone. Flipping to Failed here would route the user
                // away from a still-valid session unexpectedly.
            }
        }
    }

    return {
        // Reconnect-aware state transition. Starting from SignedOut /
        // Failed (the onboarding SignInScreen path), flip to SigningIn
        // so the screen renders its in-flight spinner. Starting from
        // SignedIn (a future Settings "Reconnect" path), DO NOT change
        // auth state — a transient SignedIn → SigningIn flip would
        // dispose any screen that routes on AuthState and hosts the
        // consent launcher, unregister the launcher mid-flow, and
        // make the eventual `consentLauncher.launch(...)` below crash
        // with "Attempting to launch an unregistered
        // ActivityResultLauncher". `adoptSession` still ends on
        // SignedIn either way; for the reconnect path it's a no-op
        // transition.
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
