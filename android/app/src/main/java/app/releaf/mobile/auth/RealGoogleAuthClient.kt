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
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
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

    override suspend fun signOut() {
        try {
            credentialManager.clearCredentialState(androidx.credentials.ClearCredentialStateRequest())
        } catch (_: Exception) {
            // Best-effort — the AuthStore clears tokens regardless.
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

    private suspend fun requestIdentity(): GoogleIdTokenInfo {
        val option = GetGoogleIdOption.Builder()
            .setServerClientId(webClientId)
            .setFilterByAuthorizedAccounts(false)
            .build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(option)
            .build()

        val response = try {
            credentialManager.getCredential(context = activity, request = request)
        } catch (_: GetCredentialCancellationException) {
            throw GoogleAuthError.Cancelled
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

    private suspend fun authorize(): AuthorizationResult {
        val request = AuthorizationRequest.Builder()
            .setRequestedScopes(listOf(Scope(DRIVE_FILE_SCOPE)))
            .build()
        return authorizationClient.authorize(request).awaitTask()
    }

    private fun buildSession(identity: GoogleIdTokenInfo, result: AuthorizationResult): GoogleAuthSession {
        return GoogleAuthSession(
            userId = identity.id,
            email = identity.id,
            displayName = identity.displayName,
            accessToken = result.accessToken
                ?: throw GoogleAuthError.Underlying("Authorization succeeded but access token is null"),
            refreshToken = null, // Android flow doesn't surface one
            expiresAt = Instant.now().plusSeconds(ACCESS_TOKEN_TTL_SECONDS),
        )
    }

    companion object {
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
