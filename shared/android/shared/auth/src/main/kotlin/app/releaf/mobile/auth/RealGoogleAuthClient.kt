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

import android.accounts.Account
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
    /**
     * Plain `Context` rather than `Activity` so this client can be
     * constructed from `applicationContext` for paths that don't
     * surface UI — namely the analytics flush worker's
     * `authStore.idToken()` call, which runs inside a CoroutineWorker
     * with no Activity available. Activity-bound flows (sign-in,
     * authorization consent, foreground refresh) still need an
     * Activity; they go through [requireActivity] which throws if
     * the stored context isn't one.
     *
     * The convenience `(Activity, String)` ctor below lets existing
     * callers (QuickInkApp foreground refresh, QuickInkAuthBinding
     * sign-in) continue to pass an Activity unchanged.
     */
    private val context: Context,
    /**
     * OAuth 2.0 **Web** client ID for the Releaf Google Cloud project.
     * Credential Manager requires the Web Client ID (not the Android
     * one) because that's the audience the ID token is minted for.
     */
    private val webClientId: String,
) : GoogleAuthClient {

    /**
     * Activity-flavoured convenience constructor. Existing call sites
     * pass an Activity; the cast `activity as Context` keeps that
     * working without forcing every caller to widen their type.
     */
    constructor(activity: Activity, webClientId: String) :
        this(activity as Context, webClientId)

    private val credentialManager: CredentialManager = CredentialManager.create(context)

    /**
     * Activity-bound — `Identity.getAuthorizationClient` requires an
     * Activity. Lazy so an analytics-only construction (with
     * `applicationContext`) doesn't trip the require until the
     * caller actually reaches a UI-bound code path.
     */
    private val authorizationClient by lazy {
        Identity.getAuthorizationClient(requireActivity("authorize"))
    }

    /**
     * Pull the Activity out of [context], or throw a clear error
     * if the construction context was application-only. Names the
     * UI-bound operation that needed the Activity so the error
     * message tells the developer exactly which call site to fix.
     */
    private fun requireActivity(operation: String): Activity =
        (context as? Activity) ?: throw GoogleAuthError.Underlying(
            "RealGoogleAuthClient: $operation requires an Activity context " +
                "(got ${context::class.java.simpleName}). Construct with " +
                "RealGoogleAuthClient(activity, webClientId) for UI-bound flows."
        )

    // ------------------------------------------------------------------
    // ID token caching for analytics auth
    // ------------------------------------------------------------------

    private val idTokenLock = Any()

    @Volatile private var cachedIdToken: String? = null

    /** Epoch seconds parsed from the cached JWT's `exp` claim. */
    @Volatile private var cachedIdTokenExpiresAt: Long = 0L

    override suspend fun idToken(): String {
        synchronized(idTokenLock) {
            val cached = cachedIdToken
            if (cached != null) {
                val secondsLeft = cachedIdTokenExpiresAt - Instant.now().epochSecond
                if (secondsLeft > MIN_TTL_SECONDS) return cached
            }
        }
        val fresh = fetchFreshIdToken()
        synchronized(idTokenLock) {
            cachedIdToken = fresh
            // Fall back to a 60-min default if we can't parse the exp
            // claim — better than 0 (which would make every call
            // re-fetch). The verifier will still gate by the real exp
            // server-side; this is just a local hint.
            cachedIdTokenExpiresAt = parseJwtExp(fresh)
                ?: (Instant.now().epochSecond + 3_600)
        }
        return fresh
    }

    /**
     * Silent-only Credential Manager fetch. Falls through to
     * [GoogleAuthError.Underlying] if no cached credential is
     * available — by definition the analytics worker can only run
     * after the user has signed in interactively elsewhere, so the
     * silent path always succeeds in production. Failures here are
     * swallowed by the worker (it leaves rows queued).
     *
     * Uses [buildBackgroundSilentOption] (strict — auto-select + filter
     * by authorized accounts) rather than the permissive
     * [buildSilentOption] used by interactive sign-in. With application
     * context (no Activity attached), even a brief account-confirmation
     * UI flash from GMS will fail to host and emit the system
     * "Request cancelled by quickink" toast; the strict option forces
     * GMS into "auto-return cached credential or throw NoCredential"
     * with no UI path at all.
     */
    private suspend fun fetchFreshIdToken(): String {
        val response = try {
            requestCredential(buildBackgroundSilentOption())
        } catch (e: NoCredentialException) {
            throw GoogleAuthError.Underlying(
                "ID-token fetch: no cached credential — sign in required " +
                    "(${e.localizedMessage ?: e::class.java.simpleName})"
            )
        } catch (_: GetCredentialCancellationException) {
            throw GoogleAuthError.Cancelled
        } catch (e: GetCredentialException) {
            throw GoogleAuthError.Underlying(
                e.localizedMessage ?: "ID-token fetch failed"
            )
        }
        val cred = response.credential
        if (cred !is CustomCredential ||
            cred.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            throw GoogleAuthError.Underlying(
                "ID-token fetch: unexpected credential type ${cred::class.java.simpleName}"
            )
        }
        return GoogleIdTokenCredential.createFrom(cred.data).idToken
    }

    /**
     * Decode the JWT's middle segment as base64url, scan for the
     * `exp` claim, return its epoch-seconds value. Returns null on
     * any parse failure — the caller falls back to a synthetic TTL.
     * Keeping this regex-based avoids pulling in a JSON parser just
     * for a single integer claim.
     */
    private fun parseJwtExp(jwt: String): Long? = runCatching {
        val parts = jwt.split('.')
        if (parts.size < 2) return@runCatching null
        val decoded = String(
            android.util.Base64.decode(
                parts[1],
                android.util.Base64.URL_SAFE or
                    android.util.Base64.NO_PADDING or
                    android.util.Base64.NO_WRAP,
            )
        )
        Regex(""""exp"\s*:\s*(\d+)""")
            .find(decoded)
            ?.groupValues
            ?.get(1)
            ?.toLong()
    }.getOrNull()

    // ------------------------------------------------------------------
    // GoogleAuthClient
    // ------------------------------------------------------------------

    override suspend fun signIn(): GoogleAuthSession {
        val identity = requestIdentity()
        // Pin the second authorize() call to the account the user
        // just picked in Credential Manager. Without this,
        // AuthorizationClient on multi-account devices shows ITS
        // OWN account chooser before the consent sheet — the user
        // ends up picking the same account twice for one sign-in.
        // `identity.id` is the email per GoogleIdTokenCredential.
        val authResult = authorize(email = identity.id)

        if (authResult.hasResolution()) {
            throw ConsentRequiredException(
                intentSender = authResult.pendingIntent!!.intentSender,
                identity = identity,
            )
        }
        return buildSession(identity, authResult)
    }

    /**
     * Background-safe silent token refresh. Used by QuickInk's sync
     * worker after a 401 from Drive: if GMS still has the user's
     * authorization cached, this returns a freshly-rotated session
     * without ever touching the UI surface. Returns `null` when
     * GMS would need to prompt the user again (revoked consent,
     * cleared cache, etc.) — caller falls back to the existing
     * AUTH_REJECTED banner path.
     *
     * Crucially we use `Identity.getAuthorizationClient(context.
     * applicationContext)` instead of the lazy [authorizationClient]
     * field that pins itself to an Activity. The authorize() call
     * for an already-authorized account completes silently with no
     * UI; if the result reports `hasResolution()` we KNOW the
     * system needs an Activity to host the consent flow and we
     * bail with `null` rather than launching the PendingIntent
     * (which has nothing to attach to from a worker context and
     * would emit the GMS "Request cancelled by quickink" toast).
     *
     * Doesn't touch any of the cached ID-token state — that
     * pipeline stays gated on a foreground call from
     * [AnalyticsFlushWorker]. This method is strictly for the
     * Drive access-token rotation path.
     */
    suspend fun refreshSilentBackground(session: GoogleAuthSession): GoogleAuthSession? {
        return try {
            val client = Identity.getAuthorizationClient(context.applicationContext)
            val request = AuthorizationRequest.Builder()
                .setRequestedScopes(listOf(Scope(DRIVE_FILE_SCOPE)))
                .setAccount(Account(session.email, GOOGLE_ACCOUNT_TYPE))
                .build()
            val result = client.authorize(request).awaitTask()
            if (result.hasResolution()) {
                android.util.Log.i(
                    TAG,
                    "refreshSilentBackground: GMS needs UI consent — returning null",
                )
                return null
            }
            val token = result.accessToken
            if (token.isNullOrBlank()) {
                android.util.Log.w(
                    TAG,
                    "refreshSilentBackground: result had no access token — returning null",
                )
                return null
            }
            android.util.Log.i(
                TAG,
                "refreshSilentBackground: ok (rotated access token, " +
                    "scopes=${result.grantedScopes})",
            )
            session.copy(
                accessToken = token,
                expiresAt = Instant.now().plusSeconds(ACCESS_TOKEN_TTL_SECONDS),
            )
        } catch (e: Exception) {
            android.util.Log.w(
                TAG,
                "refreshSilentBackground: failed (${e::class.java.simpleName}: ${e.message})",
            )
            null
        }
    }

    override suspend fun refresh(session: GoogleAuthSession): GoogleAuthSession {
        // Pin to the signed-in account so the silent refresh path
        // never surfaces an account picker on multi-account devices
        // (same reasoning as signIn() — see comment above).
        val authResult = authorize(email = session.email)
        if (authResult.hasResolution()) {
            // Diagnostic — when refresh fails with hasResolution=true
            // right after a successful interactive consent, we want
            // to know WHY Google is asking for UI again. Print
            // anything the AuthorizationResult exposes pre-throw so
            // `adb logcat -s QuickInkAuth` shows us:
            //   - which scopes the result already lists as granted
            //     (sometimes populated even when the user has to
            //     re-confirm)
            //   - which package the consent intent would launch into
            //   - the email we pinned the request to (for "device
            //     account mismatch" hypotheses)
            //   - the token prefix we tried to refresh (for "expired
            //     beyond the underlying-grant window" hypotheses)
            val granted = runCatching { authResult.grantedScopes }
                .getOrNull()
                ?.joinToString(",")
                .orEmpty()
            val intentSenderPkg = runCatching {
                authResult.pendingIntent?.creatorPackage
            }.getOrNull().orEmpty()
            android.util.Log.w(
                TAG,
                "refresh: hasResolution=true — Google wants UI. " +
                    "pinnedEmail=${session.email} " +
                    "tokenPrefix=${session.accessToken.take(12)} " +
                    "secondsUntilExpiry=${session.expiresAt.epochSecond - Instant.now().epochSecond} " +
                    "grantedScopesOnResult=[$granted] " +
                    "consentLaunchPkg=$intentSenderPkg"
            )
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

    /**
     * Build the strict silent option used by [fetchFreshIdToken] — the
     * background ID-token refresh path that runs from the analytics
     * worker with `applicationContext` and no Activity attached.
     *
     *   - `setFilterByAuthorizedAccounts(true)`: only consider accounts
     *     that have previously signed in to this server client. The
     *     analytics worker only runs after the user has signed in
     *     interactively, so the account is always in this set;
     *     restricting the filter prevents GMS from offering UI to
     *     "pick another account".
     *   - `setAutoSelectEnabled(true)`: when there's a single matching
     *     credential (the steady-state for our app, one Google
     *     account signed in), GMS returns it without any user
     *     interaction. Without this, even the "silent" path can
     *     surface a brief account-confirmation UI flash — and from
     *     an applicationContext caller that flash has nothing to
     *     host on, triggering the GMS "Request cancelled by
     *     quickink" toast a few times a day.
     *
     * If GMS can't satisfy the request silently under these stricter
     * rules (multi-account ambiguity, cleared cache, etc.) it throws
     * `NoCredentialException`; the analytics worker leaves the
     * outbox queued and retries on the next periodic tick.
     */
    private fun buildBackgroundSilentOption(): GetCredentialRequest {
        val option = GetGoogleIdOption.Builder()
            .setServerClientId(webClientId)
            .setFilterByAuthorizedAccounts(true)
            .setAutoSelectEnabled(true)
            .build()
        return GetCredentialRequest.Builder().addCredentialOption(option).build()
    }

    /**
     * Thin wrapper so [requestIdentity]'s two phases share one call
     * site. Passes [context] (Activity OR Application) — the
     * silent-only `idToken()` path uses the application context;
     * the interactive sign-in path uses an Activity. Credential
     * Manager needs an Activity-bound context only when it has to
     * surface UI (the button-fallback path); silent paths run fine
     * with an application context.
     */
    private suspend fun requestCredential(request: GetCredentialRequest): GetCredentialResponse =
        credentialManager.getCredential(context = context, request = request)

    /**
     * Run the second-phase Drive scope authorization. When [email] is
     * non-blank, pin the request to that Google account via
     * `setAccount(...)` — without it, AuthorizationClient on
     * multi-account devices shows its own account chooser, even when
     * the user just picked an account in the Credential Manager
     * step. Empty string falls back to the default behaviour (used
     * when the caller has no identity hint, e.g. a refresh against a
     * session that pre-dates the email field).
     */
    private suspend fun authorize(email: String? = null): AuthorizationResult {
        val builder = AuthorizationRequest.Builder()
            .setRequestedScopes(listOf(Scope(DRIVE_FILE_SCOPE)))
        if (!email.isNullOrBlank()) {
            builder.setAccount(Account(email, GOOGLE_ACCOUNT_TYPE))
        }
        return authorizationClient.authorize(builder.build()).awaitTask()
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
         * Standard `Account.type` for Google accounts on Android —
         * matches what AccountManager surfaces and what
         * AuthorizationClient expects. Hard-coded to "com.google" per
         * the platform contract; [GoogleAuthUtil.GOOGLE_ACCOUNT_TYPE]
         * holds the same value but pulling it in adds a transitive
         * dep this module otherwise doesn't need.
         */
        private const val GOOGLE_ACCOUNT_TYPE = "com.google"

        /**
         * Conservative local TTL. The real token lifetime varies but is
         * typically ~1 hour; we refresh on 401 (not on expiry) so this
         * is mostly a sanity upper bound.
         */
        const val ACCESS_TOKEN_TTL_SECONDS = 3_300L  // 55 minutes

        /**
         * If the cached ID token has fewer than this many seconds of
         * life left, refetch via the silent Credential Manager path
         * before returning. 60s is short enough that an in-flight
         * analytics POST won't expire mid-request, long enough that
         * we don't refetch on every flush in the steady state.
         */
        private const val MIN_TTL_SECONDS = 60L
    }
}

// ---- coroutine helpers ----

/** Await a Play Services `Task<T>` into a suspend point. */
private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.resumeWithException(GoogleAuthError.Cancelled) }
}
