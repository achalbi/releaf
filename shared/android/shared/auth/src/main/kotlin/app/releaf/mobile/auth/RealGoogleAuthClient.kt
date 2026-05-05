/*
 * RealGoogleAuthClient.kt
 *
 * Production Google Sign-In + Drive scope authorization.
 *
 * Two-phase flow:
 *
 *   Phase A — Identity (Credential Manager)
 *     CredentialManager.getCredential(...) returns a GoogleIdTokenCredential
 *     with the user's `sub` / email / display name. This is the *identity*
 *     assertion; it doesn't grant any Google API scope on its own.
 *
 *   Phase B — Drive scope grant (play-services-auth AuthorizationClient)
 *     AuthorizationClient.authorize(AuthorizationRequest) with
 *     `drive.file` scope returns either:
 *       • `hasResolution() == true`  → a PendingIntent the caller must
 *         launch. After the user consents, a second authorize() call
 *         returns the access token.
 *       • `hasResolution() == false` → the access token is already in
 *         `result.accessToken`.
 *
 * Caller contract: SignInScreen owns an `ActivityResultLauncher` for the
 * consent PendingIntent. It calls [signIn] first; if
 * [ConsentRequiredException] is thrown, it launches the contained
 * IntentSender via the launcher. On callback, it calls [completeConsent]
 * with the launcher's result to finish the flow.
 *
 * Token refresh: Google doesn't return a usable refresh token on
 * Android. Instead, every time we need a fresh access token we call
 * [refresh], which invokes `AuthorizationClient.authorize()` again.
 * When the prior consent is still valid, the call returns silently
 * without a resolution; that's the common path. If the grant has been
 * revoked, the call throws and the UI surfaces a re-sign-in prompt.
 *
 * Caller wiring: this class needs an Activity context for
 * `AuthorizationClient` (play-services-auth surfaces the consent sheet
 * via the Activity). A plain Application context won't work for the
 * consent path. Callers should construct one per-Activity and not
 * retain the instance across configuration changes.
 */

package app.releaf.mobile.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.suspendCancellableCoroutine
import java.time.Instant
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Surfaced when AuthorizationClient needs to launch a consent sheet.
 * The caller must launch [intentSender] via an ActivityResultLauncher
 * and, on success, call [RealGoogleAuthClient.completeConsent] with
 * the resulting Intent.
 */
class ConsentRequiredException(
    val intentSender: IntentSender,
    /** The identity info from phase A — keep it so we can resume without re-prompting. */
    val identity: GoogleIdTokenInfo,
) : Exception("Drive scope consent required")

/** Stripped-down snapshot of a `GoogleIdTokenCredential` for continuation state. */
data class GoogleIdTokenInfo(
    val id: String,
    val displayName: String?,
)

class RealGoogleAuthClient(
    private val activity: Activity,
    /**
     * OAuth 2.0 **Web** client ID for the Releaf Google Cloud project.
     * Credential Manager requires the Web Client ID (not the Android
     * one) because that's the audience the ID token is minted for.
     */
    private val webClientId: String,
) : GoogleAuthClient {

    private val credentialManager: CredentialManager = CredentialManager.create(activity)
    private val authorizationClient = Identity.getAuthorizationClient(activity)

    // ------------------------------------------------------------------
    // GoogleAuthClient
    // ------------------------------------------------------------------

    override suspend fun signIn(): GoogleAuthSession {
        val identity = requestIdentity()
        val authResult = authorize()

        if (authResult.hasResolution()) {
            throw ConsentRequiredException(
                intentSender = authResult.pendingIntent!!.intentSender,
                identity = identity,
            )
        }
        return buildSession(identity, authResult)
    }

    override suspend fun refresh(session: GoogleAuthSession): GoogleAuthSession {
        val authResult = authorize()
        if (authResult.hasResolution()) {
            // Revoked / needs re-consent. Caller should sign out and back in.
            throw GoogleAuthError.Underlying("Drive scope no longer granted")
        }
        return session.copy(
            accessToken = authResult.accessToken ?: session.accessToken,
            expiresAt = Instant.now().plusSeconds(ACCESS_TOKEN_TTL_SECONDS),
        )
    }

    override suspend fun signOut(accessToken: String?) {
        // 1. Server-side revoke. CRITICAL — without this, Google's
        //    AuthorizationClient can serve a cached
        //    AuthorizationResult on the very next sign-in, returning
        //    the exact same dead token we're trying to throw away.
        //    Symptom users hit: tap "Sign out", sign back in, sync
        //    still 401s with the old token.
        if (!accessToken.isNullOrBlank()) {
            runCatching { revokeAccessTokenSync(accessToken) }
                .onFailure {
                    android.util.Log.w(
                        TAG, "signOut: revoke failed (best-effort): $it"
                    )
                }
                .onSuccess {
                    android.util.Log.i(
                        TAG, "signOut: OAuth grant revoked server-side"
                    )
                }
        }

        // 2. Clear Credential Manager's local cache so the next
        //    Get-Credential call re-authenticates the user instead
        //    of silently returning the previously-signed-in account.
        try {
            credentialManager.clearCredentialState(androidx.credentials.ClearCredentialStateRequest())
        } catch (_: Exception) {
            // Best-effort — the AuthStore clears tokens regardless.
        }
    }

    /**
     * POST to Google's OAuth revoke endpoint via plain
     * HttpURLConnection so :shared:auth doesn't need an OkHttp
     * dependency. Documented at
     * https://developers.google.com/identity/protocols/oauth2/native-app#tokenrevoke
     * — the endpoint accepts the access token (or refresh token)
     * in the `token` query parameter and returns 200 when the
     * grant has been fully revoked.
     */
    private suspend fun revokeAccessTokenSync(accessToken: String) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val url = java.net.URL(
                "https://oauth2.googleapis.com/revoke?token=" +
                    java.net.URLEncoder.encode(accessToken, "UTF-8")
            )
            val conn = url.openConnection() as java.net.HttpURLConnection
            try {
                conn.requestMethod    = "POST"
                conn.connectTimeout   = 10_000
                conn.readTimeout      = 10_000
                conn.doOutput         = true
                conn.setFixedLengthStreamingMode(0)
                conn.outputStream.close()
                val code = conn.responseCode
                if (code !in 200..299) {
                    val errBody = (conn.errorStream ?: conn.inputStream)
                        ?.bufferedReader()?.use { it.readText() }.orEmpty()
                    error("revoke HTTP $code: $errBody")
                }
            } finally {
                conn.disconnect()
            }
        }
    }

    // ------------------------------------------------------------------
    // Public re-entry points
    // ------------------------------------------------------------------

    /**
     * Complete sign-in after the user has consented via the PendingIntent
     * launched in response to [ConsentRequiredException]. Pass the Intent
     * returned by the ActivityResultLauncher — the method reads the
     * AuthorizationResult out of it and builds the final session.
     */
    suspend fun completeConsent(identity: GoogleIdTokenInfo, data: Intent?): GoogleAuthSession {
        val authResult = authorizationClient.getAuthorizationResultFromIntent(data)
        return buildSession(identity, authResult)
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    /**
     * Two-phase identity request:
     *
     *  1. Silent / returning-user path via [GetGoogleIdOption]. Asks
     *     Credential Manager for a saved Google ID token credential.
     *     Fast — no UI for users whose device already has a matching
     *     credential cached (typical for anyone who's signed into
     *     another Credential-Manager-aware app on the same Google
     *     account).
     *
     *  2. Fallback / button path via [GetSignInWithGoogleOption].
     *     Triggers Google's "Sign in with Google" account chooser UI
     *     unconditionally. This is what users on fresh devices, fresh
     *     accounts, or accounts that have never signed into a CM-aware
     *     app need — the silent path throws [NoCredentialException]
     *     for them, which used to surface as the "no credentials
     *     available" error reported in production.
     *
     * The split matches Google's documented best practice (see
     * developer.android.com/identity/sign-in/credential-manager-siwg).
     * Try silent first so returning users skip the chooser; fall
     * through to the button option only when Credential Manager has
     * nothing matching to offer.
     *
     * Cancellation and other [GetCredentialException]s aren't fallen
     * through — the user explicitly dismissed the silent prompt, or
     * something else (Play Services missing / outdated, network down,
     * malformed Cloud project config) failed. Surface the error to
     * the caller rather than re-prompting and looking flaky.
     */
    private suspend fun requestIdentity(): GoogleIdTokenInfo {
        val response: GetCredentialResponse = try {
            // Phase 1 — silent path.
            requestCredential(buildSilentOption())
        } catch (_: GetCredentialCancellationException) {
            throw GoogleAuthError.Cancelled
        } catch (_: NoCredentialException) {
            // Phase 2 — button path. The silent option had no
            // matching credentials cached; show the account
            // chooser so a first-time / fresh-device user can
            // pick their account.
            android.util.Log.i(
                TAG,
                "requestIdentity: no Credential Manager credential cached " +
                    "(NoCredentialException) — falling back to " +
                    "Sign-in-with-Google account chooser."
            )
            try {
                requestCredential(buildButtonOption())
            } catch (_: GetCredentialCancellationException) {
                throw GoogleAuthError.Cancelled
            } catch (e: NoCredentialException) {
                // Even the button path returned no credentials — this
                // is a real configuration / device problem, not just
                // a missing cached credential. Most likely causes:
                //   - No Google account on the device (Settings →
                //     Accounts → Add account).
                //   - Google Play Services missing or outdated.
                //   - The app's signing-cert SHA-1 isn't registered
                //     against the Android client ID in Google Cloud
                //     Console (Credential Manager rejects unregistered
                //     callers with NoCredential).
                throw GoogleAuthError.Underlying(
                    "Google sign-in unavailable on this device. " +
                        "Make sure a Google account is added (Settings → " +
                        "Accounts) and that Google Play Services is up to date. " +
                        "(${e.localizedMessage ?: e::class.java.simpleName})"
                )
            } catch (e: GetCredentialException) {
                throw GoogleAuthError.Underlying(e.localizedMessage ?: "Sign-in failed")
            }
        } catch (e: GetCredentialException) {
            throw GoogleAuthError.Underlying(e.localizedMessage ?: "Sign-in failed")
        }

        val credential = response.credential
        if (credential !is CustomCredential ||
            credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            throw GoogleAuthError.Underlying("Unexpected credential type")
        }
        val tokenCred = GoogleIdTokenCredential.createFrom(credential.data)
        return GoogleIdTokenInfo(
            id = tokenCred.id,
            displayName = tokenCred.displayName,
        )
    }

    /**
     * Build the silent / returning-user [GetGoogleIdOption]. Doesn't
     * filter by previously-authorized accounts so a fresh install on
     * a device that's already signed in (via another app) can use
     * the cached credential without re-consenting. `autoSelectEnabled`
     * is left at its default (false) — we want the user to confirm the
     * account on every fresh sign-in, which is also what the prior
     * code did. Flip if the UX wants silent re-auth on single-
     * account devices.
     */
    private fun buildSilentOption(): GetCredentialRequest {
        val option = GetGoogleIdOption.Builder()
            .setServerClientId(webClientId)
            .setFilterByAuthorizedAccounts(false)
            .build()
        return GetCredentialRequest.Builder().addCredentialOption(option).build()
    }

    /**
     * Build the explicit "Sign in with Google" button option. Always
     * shows Google's account chooser, even when no credential is
     * cached. Used as the fallback when the silent path throws
     * [NoCredentialException].
     */
    private fun buildButtonOption(): GetCredentialRequest {
        val option = GetSignInWithGoogleOption.Builder(serverClientId = webClientId).build()
        return GetCredentialRequest.Builder().addCredentialOption(option).build()
    }

    /** Thin wrapper so [requestIdentity]'s two phases share one call site. */
    private suspend fun requestCredential(request: GetCredentialRequest): GetCredentialResponse =
        credentialManager.getCredential(context = activity, request = request)

    private suspend fun authorize(): AuthorizationResult {
        val request = AuthorizationRequest.Builder()
            .setRequestedScopes(listOf(Scope(DRIVE_FILE_SCOPE)))
            .build()
        return authorizationClient.authorize(request).awaitTask()
    }

    private fun buildSession(identity: GoogleIdTokenInfo, result: AuthorizationResult): GoogleAuthSession {
        val token = result.accessToken
            ?: throw GoogleAuthError.Underlying("Authorization succeeded but access token is null")

        // Diagnostic: the user might tap "Continue" on the consent
        // sheet WITHOUT ticking the Drive checkbox. The flow then
        // succeeds with an access token that doesn't include Drive
        // scope — every Drive call later returns 401/403 and the
        // user is stuck wondering why sync is broken. Verify the
        // grant explicitly before persisting the session.
        val granted = result.grantedScopes
        android.util.Log.i(
            TAG,
            "buildSession: grantedScopes=$granted; need=$DRIVE_FILE_SCOPE",
        )
        val driveGranted = granted.any { it == DRIVE_FILE_SCOPE }
        if (!driveGranted) {
            throw GoogleAuthError.MissingDriveScope
        }

        return GoogleAuthSession(
            userId = identity.id,
            email = identity.id,
            displayName = identity.displayName,
            accessToken = token,
            refreshToken = null, // Android flow doesn't surface one
            expiresAt = Instant.now().plusSeconds(ACCESS_TOKEN_TTL_SECONDS),
        )
    }

    companion object {
        private const val TAG = "QuickInkAuth"

        /** The only scope Releaf ever requests. See `PROMPT.md` §Hard constraints #5. */
        const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"

        /**
         * Conservative local TTL. The real token lifetime varies but is
         * typically ~1 hour; we refresh on 401 (not on expiry) so this
         * is mostly a sanity upper bound.
         */
        const val ACCESS_TOKEN_TTL_SECONDS = 3_300L  // 55 minutes
    }
}

// ---- coroutine helpers ----

/** Await a Play Services `Task<T>` into a suspend point. */
private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.resumeWithException(GoogleAuthError.Cancelled) }
}
