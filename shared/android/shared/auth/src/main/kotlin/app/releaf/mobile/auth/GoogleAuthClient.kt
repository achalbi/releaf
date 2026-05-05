/*
 * GoogleAuthClient.kt
 * Protocol + in-memory stub for Google Sign-In.
 *
 * Real implementation: wrap androidx.credentials.CredentialManager +
 * com.google.android.libraries.identity.googleid.GetGoogleIdOption for sign-in,
 * and com.google.android.gms.auth.api.identity.AuthorizationClient to request
 * the Drive scope.
 *
 * Scope required for Drive writes: `https://www.googleapis.com/auth/drive.file`.
 */

package app.releaf.mobile.auth

import kotlinx.coroutines.delay
import java.time.Instant

data class GoogleAuthSession(
    val userId: String,
    val email: String,
    val displayName: String?,
    val accessToken: String,
    val refreshToken: String?,
    val expiresAt: Instant,
)

sealed class GoogleAuthError(message: String) : Exception(message) {
    object Cancelled       : GoogleAuthError("Sign-in cancelled")
    object NotImplemented  : GoogleAuthError("Not implemented")
    /**
     * Sign-in succeeded but the user didn't actually grant the
     * Drive scope (most likely path: they unticked the "Google
     * Drive" checkbox on the OAuth consent sheet, or the OAuth
     * client / Cloud project doesn't have Drive API enabled).
     * Without the scope, every subsequent Drive call 401s and the
     * sync surface stays stuck on "Never". The SignIn UI catches
     * this specific case and shows a "please grant Drive" message
     * instead of a generic "sign-in failed".
     */
    object MissingDriveScope : GoogleAuthError(
        "Drive access wasn't granted. Sign in again and tick the Google Drive permission."
    )
    class  Underlying(msg: String) : GoogleAuthError(msg)
}

interface GoogleAuthClient {
    suspend fun signIn(): GoogleAuthSession
    suspend fun refresh(session: GoogleAuthSession): GoogleAuthSession
    /**
     * Sign the user out. Implementations should clear local
     * Credential-Manager / SDK caches AND, when [accessToken] is
     * non-null, call Google's OAuth `/revoke` endpoint to nuke
     * the grant server-side. Without server-side revocation, the
     * AuthorizationClient may serve a cached AuthorizationResult
     * on the next sign-in, returning the same dead token and
     * keeping the user stuck. Best-effort: any failure in the
     * revoke call is logged but doesn't propagate.
     */
    suspend fun signOut(accessToken: String? = null)
}

/**
 * Default stub — lets the skeleton build + preview without the real SDKs.
 * Returns a fake session after a short delay. Replace before shipping.
 */
class StubGoogleAuthClient : GoogleAuthClient {
    override suspend fun signIn(): GoogleAuthSession {
        delay(400)
        return GoogleAuthSession(
            userId = "stub-user",
            email = "you@example.com",
            displayName = "Preview User",
            accessToken = "stub-access-token",
            refreshToken = "stub-refresh-token",
            expiresAt = Instant.now().plusSeconds(3_600),
        )
    }

    override suspend fun refresh(session: GoogleAuthSession): GoogleAuthSession =
        session.copy(expiresAt = Instant.now().plusSeconds(3_600))

    override suspend fun signOut(accessToken: String?) = Unit
}
