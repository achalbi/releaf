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
    class  Underlying(msg: String) : GoogleAuthError(msg)
}

interface GoogleAuthClient {
    suspend fun signIn(): GoogleAuthSession
    suspend fun refresh(session: GoogleAuthSession): GoogleAuthSession
    suspend fun signOut()
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

    override suspend fun signOut() = Unit
}
