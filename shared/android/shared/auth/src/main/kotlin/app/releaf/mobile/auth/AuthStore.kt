/*
 * AuthStore.kt
 * App-wide auth state. Wraps a `GoogleAuthClient` and persists the current
 * session via EncryptedSharedPreferences so the app can resume on re-launch.
 */

package app.releaf.mobile.auth

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant

sealed interface AuthState {
    data object SignedOut : AuthState
    data object SigningIn : AuthState
    data class  SignedIn(val session: GoogleAuthSession) : AuthState
    data class  Failed(val message: String) : AuthState
}

class AuthStore private constructor(
    appContext: Context,
    private val client: GoogleAuthClient,
) {
    private val prefs = EncryptedSharedPreferences.create(
        appContext,
        "releaf_auth",
        MasterKey.Builder(appContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private val _state = MutableStateFlow<AuthState>(restore())
    val state: StateFlow<AuthState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun signIn() {
        _state.value = AuthState.SigningIn
        scope.launch {
            try {
                val session = client.signIn()
                persist(session)
                _state.value = AuthState.SignedIn(session)
            } catch (_: GoogleAuthError.Cancelled) {
                _state.value = AuthState.SignedOut
            } catch (e: Exception) {
                _state.value = AuthState.Failed(e.localizedMessage ?: "Sign-in failed")
            }
        }
    }

    fun signOut() {
        scope.launch {
            // Snapshot the current access token BEFORE clearing prefs
            // so the client can revoke it server-side. Clearing prefs
            // first would lose the token and leave Google's
            // AuthorizationClient holding a cached grant — the next
            // sign-in would silently restore the same dead token.
            val tokenForRevoke: String? = (_state.value as? AuthState.SignedIn)
                ?.session?.accessToken
                ?: prefs.getString(KEY_ACCESS, null)
            runCatching { client.signOut(tokenForRevoke) }
            prefs.edit().clear().apply()
            _state.value = AuthState.SignedOut
        }
    }

    /**
     * Adopt a session obtained elsewhere (e.g. a `RealGoogleAuthClient`
     * flow that runs inside the SignInScreen, where the Activity context
     * + ActivityResultLauncher for the Drive consent sheet are
     * available). Persists the session and flips to `SignedIn`.
     */
    fun adoptSession(session: GoogleAuthSession) {
        persist(session)
        _state.value = AuthState.SignedIn(session)
    }

    /**
     * Surface a sign-in failure from an external flow — e.g. a caught
     * [GoogleAuthError] from the Credential Manager or
     * AuthorizationClient path. Mirrors the internal failure path used
     * by [signIn].
     */
    fun failSignIn(message: String) {
        _state.value = AuthState.Failed(message)
    }

    /** Surface a user-cancelled external flow. */
    fun cancelSignIn() {
        _state.value = AuthState.SignedOut
    }

    /** Transition to SigningIn while an external flow runs. */
    fun beginExternalSignIn() {
        _state.value = AuthState.SigningIn
    }

    /**
     * Pass-through to the underlying [GoogleAuthClient.idToken]. The
     * QuickInk analytics worker uses this to authenticate every
     * request to api-quickink.thoughtbasics.com — the backend's
     * `GoogleTokenVerifier` reads RS256 JWTs minted by Google's
     * Credential Manager.
     *
     * The store doesn't cache here — the client implementation owns
     * the cache + silent-refresh logic. Calling [idToken] from
     * multiple workers is safe; the client guards with an internal
     * lock.
     */
    suspend fun idToken(): String = client.idToken()

    // MARK: — persistence

    private fun restore(): AuthState {
        val userId      = prefs.getString(KEY_USER_ID, null) ?: return AuthState.SignedOut
        val email       = prefs.getString(KEY_EMAIL, null)   ?: return AuthState.SignedOut
        val access      = prefs.getString(KEY_ACCESS, null)  ?: return AuthState.SignedOut
        val refresh     = prefs.getString(KEY_REFRESH, null)
        val displayName = prefs.getString(KEY_DISPLAY, null)
        val expiresAt   = prefs.getLong(KEY_EXPIRES, 0L).takeIf { it > 0 }?.let(Instant::ofEpochSecond)
            ?: return AuthState.SignedOut

        // Stub-poisoning guard. If a previous build was wired to
        // `StubGoogleAuthClient` and the user signed in, EncryptedSharedPreferences
        // is now holding the literal sentinel strings the stub returns
        // (see `StubGoogleAuthClient.signIn` — `accessToken =
        // "stub-access-token"`, `userId = "stub-user"`). On upgrade to a
        // build with a real `GoogleAuthClient`, naively restoring those
        // values would let the worker hand the literal stub string to
        // Drive on every pass — Drive 401s every time and the user is
        // stuck behind an AUTH_REJECTED banner that re-sign-in fixes
        // permanently. Detect the sentinel and clear, so the upgrade
        // path lands on the SignIn screen cleanly.
        if (access == STUB_ACCESS_TOKEN_SENTINEL || userId == STUB_USER_ID_SENTINEL) {
            Log.w(
                "QuickInkAuth",
                "restore: detected stub session in prefs (access='$STUB_ACCESS_TOKEN_SENTINEL' " +
                    "or userId='$STUB_USER_ID_SENTINEL') — clearing and forcing SignedOut so " +
                    "the user re-signs in with a real client."
            )
            prefs.edit().clear().apply()
            return AuthState.SignedOut
        }

        return AuthState.SignedIn(
            GoogleAuthSession(
                userId = userId,
                email = email,
                displayName = displayName,
                accessToken = access,
                refreshToken = refresh,
                expiresAt = expiresAt,
            )
        )
    }

    private fun persist(session: GoogleAuthSession) {
        prefs.edit().apply {
            putString(KEY_USER_ID, session.userId)
            putString(KEY_EMAIL, session.email)
            putString(KEY_DISPLAY, session.displayName)
            putString(KEY_ACCESS, session.accessToken)
            putString(KEY_REFRESH, session.refreshToken)
            putLong(KEY_EXPIRES, session.expiresAt.epochSecond)
        }.apply()
    }

    companion object {
        private const val KEY_USER_ID = "user_id"
        private const val KEY_EMAIL   = "email"
        private const val KEY_DISPLAY = "display_name"
        private const val KEY_ACCESS  = "access_token"
        private const val KEY_REFRESH = "refresh_token"
        private const val KEY_EXPIRES = "expires_at"

        // Sentinels written by `StubGoogleAuthClient.signIn`. Used
        // by [restore] to detect a stale stub session in prefs after
        // an upgrade to a real-client build and force a clean
        // sign-out instead of restoring a session whose access
        // token Google will instantly 401.
        private const val STUB_ACCESS_TOKEN_SENTINEL = "stub-access-token"
        private const val STUB_USER_ID_SENTINEL      = "stub-user"

        @Volatile private var instance: AuthStore? = null

        fun get(context: Context, client: GoogleAuthClient = StubGoogleAuthClient()): AuthStore =
            instance ?: synchronized(this) {
                instance ?: AuthStore(context.applicationContext, client).also { instance = it }
            }
    }
}
