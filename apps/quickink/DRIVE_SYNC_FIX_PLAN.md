# QuickInk Android Drive Sync — Fix Plan for `Google rejected your token (401/403)`

Status: proposal, awaiting your approval before edits.
Scope: **Android only** (per your direction). iOS side noted as not configured — left for a later pass.
Token-policy: keep "log and fall through" on transient 401/403, add explicit re-auth UX.

---

## TL;DR

Android already has more of the recovery scaffolding wired than I initially feared. The Settings screen already renders an `AuthRejectedBanner` when `LAST_SYNC_ERROR_CODE == AUTH_REJECTED`, the worker already writes that code on 401/403, and `strings.xml` has a real `google_web_client_id` (not the placeholder), so `OkHttpDriveClient` is the active client.

What's missing is the loop that lets the app *recover* from 401/403 without forcing the user to sign out. Right now:

1. Worker hits 401/403 → writes `AUTH_REJECTED` → returns `Result.failure()` (no retry).
2. Banner appears in Settings → user can only **Sign out**.
3. Sign in again → token rotates → next sync works.

This is correct for genuine "scope revoked" cases, but **the most common cause is just the access token expiring after 60 minutes idle**, and `RealGoogleAuthClient.refresh()` requires an `Activity`, which the worker doesn't have. Result: every long-idle period forces the user through a sign-out / sign-in dance for what should be a silent token rotation.

The fix has three pieces, in order of impact:

1. **Foreground token-refresh hook** in `QuickInkApp` — when the user opens the app and the cached token is near/past expiry, run `RealGoogleAuthClient.refresh()` from the topmost Activity. The next worker pass picks up a fresh token. (Highest impact — eliminates the most common 401/403 cause.)
2. **Stub-poisoning guard** in `AuthStore.restore()` — if a previous build wrote `accessToken="stub-access-token"` to EncryptedSharedPreferences, never restore it as a valid session. (Cheap, fixes upgrade-from-stub-build cases.)
3. **401 vs 403 disambiguation** in `OkHttpDriveClient` and a "Try again" button on the AuthRejectedBanner — turn the rate-limit / quota subset of 403 into a transient-retry path instead of an AUTH_REJECTED dead-end. (Quality-of-life; reduces false-positive banners.)

---

## What's already working (don't touch)

- `strings.xml::google_web_client_id` is set to `534102618638-…apps.googleusercontent.com`. Confirmed real.
- `QuickInkApp.onCreate` correctly picks `OkHttpDriveClient` over `InMemoryDriveClient`.
- `QuickInkAuthBinding.kt` correctly switches stub → real based on the placeholder check.
- `QuickInkSyncWorker` writes `LAST_SYNC_ERROR_CODE = AUTH_REJECTED` on `DriveError.Unauthenticated`, then `Result.failure()` (no retry — correct for auth-rejected).
- `SettingsScreen.kt::AuthRejectedBanner` (lines 264-266 + 519-554) already surfaces the error and offers Sign Out.
- `observeAuthForSyncLifecycle` correctly schedules + cancels work; the regression where transient `SigningIn` / `Failed` was killing in-flight workers has been fixed.

So your existing wiring is sound. The bug is in what happens **between** sign-in and the worker giving up.

---

## How the auth + sync stack flows today (Android)

1. App start → `QuickInkApp.onCreate`:
   - Builds `AuthStore.get(this)` — `restore()` reads `EncryptedSharedPreferences("releaf_auth")` and resolves to `SignedIn(GoogleAuthSession)` if all keys are present.
   - Picks `OkHttpDriveClient` (real client ID is set).
   - `observeAuthForSyncLifecycle` fires `schedulePeriodic + requestImmediate` on `SignedIn`.

2. Sign-in (first time) → `rememberQuickInkSignInAction`:
   - Phase A (CredentialManager) returns `GoogleIdTokenInfo` (id, email, displayName).
   - Phase B (`AuthorizationClient.authorize(driveFileScope)`) returns `AuthorizationResult` with `accessToken` and `grantedScopes`.
   - `buildSession` checks `granted.contains(driveFileScope)` (defensive — user might tap "Continue" without ticking the Drive checkbox) and throws `MissingDriveScope` if not.
   - On success, `authStore.adoptSession(session)` persists to EncryptedSharedPreferences with `expiresAt = now + 3300s` (55min, conservative upper bound).

3. Periodic sync → `QuickInkSyncWorker.doWork`:
   - Reads `authStore.state.value` — if `SignedIn`, takes `session.accessToken`.
   - **Does NOT refresh** the token. The comment at line 86-99 acknowledges this and notes that `gate-3` was previously returning `Result.retry` on stale TTL, which created an infinite-retry loop and was removed.
   - Hands the (possibly expired) token to `OkHttpDriveClient`.
   - On 401/403 → catches `DriveError.Unauthenticated` → writes `AUTH_REJECTED` → `Result.failure()`.

4. Settings:
   - Banner watches `errorCodeRow` from `SyncStateDao.observe(LAST_SYNC_ERROR_CODE)` — appears when value is `AUTH_REJECTED`.
   - Banner button calls `authStore.signOut()` which clears prefs and flips state to `SignedOut`. The `OnboardingFlow` then re-presents the SignIn screen on next composition.

The dead-end is in step 3: there is no path from "token expired" back to "fresh token" without going through step 4's sign-out.

---

## The four issues, ranked by likelihood

### 1. No foreground token-refresh hook (highest impact)

**Symptom:** user signs in, uses the app, backgrounds for >60 min, comes back, taps Sync now or scans something. The first sync 401s because the cached `accessToken` is past Google's wire expiry. Banner appears. User doesn't understand why — they JUST signed in.

**Cause:** `RealGoogleAuthClient.refresh()` requires an `Activity` (it calls `Identity.getAuthorizationClient(activity)`). Workers don't have one. So the worker never refreshes.

**Fix:** add `QuickInkApp.refreshTokenOnForeground()`:

```kotlin
// In QuickInkApp.onCreate, after observeAuthForSyncLifecycle():
registerActivityLifecycleCallbacks(topActivityTracker)
ProcessLifecycleOwner.get().lifecycle.addObserver(
    LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_START) {
            maybeRefreshTokenInForeground()
        }
    }
)

private fun maybeRefreshTokenInForeground() {
    val state = authStore.state.value as? AuthState.SignedIn ?: return
    val activity = topActivityTracker.current ?: return
    // Only refresh when within 5 minutes of expiry (or already
    // expired). Cheap to check; avoids hitting the Google
    // endpoint on every cold launch.
    val refreshThreshold = state.session.expiresAt.minusSeconds(300)
    if (Instant.now().isBefore(refreshThreshold)) return

    appScope.launch {
        runCatching {
            val client = RealGoogleAuthClient(activity, getString(R.string.google_web_client_id))
            val fresh = client.refresh(state.session)
            authStore.adoptSession(fresh)
            // Clear the AUTH_REJECTED banner if it was up;
            // the next sync will validate the new token.
            database.syncStateDao().upsert(
                SyncStateEntity(SyncStateKeys.LAST_SYNC_ERROR_CODE, "", IsoClock.nowIso())
            )
            QuickInkSyncScheduler.requestImmediate(this@QuickInkApp)
        }.onFailure { e ->
            Log.w("QuickInkSync", "Foreground token refresh failed: $e — leaving session as-is, worker will eventually 401 if the wire token is also dead.")
        }
    }
}
```

`AuthorizationClient.authorize()` returns silently (no UI) when the user's prior consent is still valid — it just rotates the access token. If the consent has been revoked server-side, it returns `hasResolution() == true` and our refresh path throws `Drive scope no longer granted`, which we swallow (the worker's eventual 401 will set `AUTH_REJECTED` and the banner takes over).

Files touched:
- `apps/quickink/android/app/src/main/java/app/quickink/mobile/QuickInkApp.kt`

Risk: low. The whole hook is no-op when:
- not signed in,
- no foreground Activity,
- token isn't near expiry,
- web client ID is the placeholder.

### 2. Stub-poisoning of EncryptedSharedPreferences

**Symptom:** the app launches as "signed in", but every sync 401s on every pass forever, regardless of network or refresh. Sign Out + Sign In fixes it permanently.

**Cause:** an earlier build was wired to `StubGoogleAuthClient`. The user signed in. `AuthStore.persist()` wrote:
```
access_token = "stub-access-token"
refresh_token = "stub-refresh-token"
expires_at = <now + 1h>
user_id = "stub-user"
email = "you@example.com"
```
On upgrade to a build with `RealGoogleAuthClient`, `restore()` reads these back and treats them as a real session. Drive instantly rejects the literal string `"stub-access-token"`.

**Fix:** in `AuthStore.restore()`:

```kotlin
private fun restore(): AuthState {
    val userId = prefs.getString(KEY_USER_ID, null) ?: return AuthState.SignedOut
    val access = prefs.getString(KEY_ACCESS, null) ?: return AuthState.SignedOut
    // ... existing reads ...

    // Stub-poisoning guard: previous dev/preview builds wrote
    // "stub-access-token" / "stub-user" via StubGoogleAuthClient.
    // Treat those as absent so the upgrade flow starts clean.
    if (access == "stub-access-token" || userId == "stub-user") {
        prefs.edit().clear().apply()
        return AuthState.SignedOut
    }

    return AuthState.SignedIn(GoogleAuthSession(...))
}
```

Files touched:
- `shared/android/shared/auth/src/main/kotlin/app/releaf/mobile/auth/AuthStore.kt`

Risk: minimal. The sentinel strings are unique to the stub.

### 3. 401 vs 403 conflated; rate-limit looks like auth failure

**Symptom:** during a busy sync (lots of dirty rows, multiple binary uploads), the app hits Drive's per-user rate limit (1000 queries / 100 seconds). Drive returns 403 with `error.errors[0].reason == "rateLimitExceeded"`. The worker treats it as `Unauthenticated`, writes `AUTH_REJECTED`, and the banner appears. User signs out and back in — fixes nothing because the actual problem was a rate limit that's already cleared.

**Cause:** `OkHttpDriveClient` has comments admitting this is a hack:
```kotlin
// 403 = Drive scope wasn't granted at sign-in (most
// common silent-sync-failure cause), treat the same as
// 401 so the worker's auth-error path forces re-sign-in
// and the user re-consents with the drive.file checkbox.
if (resp.code == 401 || resp.code == 403) throw DriveError.Unauthenticated
```

But Drive returns 403 for a dozen distinct reasons. We need to look at the response body:

```json
{"error": {"code": 403, "errors": [{"reason": "rateLimitExceeded"}]}}
```

**Fix:**

a. Add a richer error type in `DriveError.kt`:
```kotlin
sealed class DriveError : Exception() {
    object Unauthenticated : DriveError()      // 401 OR 403 + auth reason
    object RateLimited : DriveError()           // 403 + rate/quota reason
    object NotFound : DriveError()
    data class Underlying(override val message: String) : DriveError()
}
```

b. Update `OkHttpDriveClient`'s six 401/403 sites to parse the body and dispatch:
```kotlin
private fun classifyAuthFailure(resp: Response): DriveError {
    if (resp.code == 401) return DriveError.Unauthenticated
    val body = resp.peekBody(2048).string()
    val reason = parseErrorReason(body)  // new helper
    return when (reason) {
        "rateLimitExceeded",
        "userRateLimitExceeded",
        "dailyLimitExceeded" -> DriveError.RateLimited
        else -> DriveError.Unauthenticated
    }
}
```

c. In `QuickInkSyncWorker.doWork`'s catch chain, add:
```kotlin
} catch (e: DriveError.RateLimited) {
    Log.w(TAG, "sync: result=RETRY (rate limited): $e")
    recordPendingFromError(app)
    writeSyncErrorCode(app, SyncErrorCodes.TRANSIENT)
    Result.retry()
} catch (e: DriveError.Unauthenticated) {
    // existing AUTH_REJECTED path
}
```

Files touched:
- `shared/android/shared/drive/src/main/kotlin/app/releaf/mobile/data/drive/DriveError.kt` (new error case)
- `shared/android/shared/drive/src/main/kotlin/app/releaf/mobile/data/drive/OkHttpDriveClient.kt` (six call sites)
- `apps/quickink/android/.../QuickInkSyncWorker.kt` (one new catch)

Risk: medium. Touches shared code that Releaf also depends on, so the new `DriveError.RateLimited` needs to be either a new sealed-subclass that Releaf's catch chain ignores (falls through to the generic `DriveError` catch and retries — which is what we want) OR mirrored into Releaf's worker explicitly.

### 4. AuthRejectedBanner has only "Sign out" — no in-place refresh

**Symptom:** even when the foreground refresh path of #1 lands, there's a window where the banner is up and the user is staring at it, wondering whether to tap Sign out (annoying — they have to re-pick their account) or just close + reopen the app (they don't know that triggers a refresh).

**Fix:** add a "Try again" primary button to the banner that calls into the same `maybeRefreshTokenInForeground()` helper from #1:

```kotlin
@Composable
private fun AuthRejectedBanner(
    onTryAgain: () -> Unit,    // new: triggers foreground refresh
    onSignOut: () -> Unit,     // existing fallback
) {
    // ... existing layout ...
    Row(horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2)) {
        PrimaryButton(label = "Try again", onClick = onTryAgain, modifier = Modifier.weight(1f))
        SecondaryButton(label = "Sign out", onClick = onSignOut, modifier = Modifier.weight(1f))
    }
}
```

If "Try again" succeeds, the banner clears via the `LAST_SYNC_ERROR_CODE = ""` write in #1's hook. If it fails (true revoke / hard 401), the banner stays up and the user falls back to Sign out.

Files touched:
- `apps/quickink/android/.../SettingsScreen.kt`

Risk: low.

---

## Sequencing — three small PRs

PR 1 — defensive (1 + 2 above):

a. Foreground token-refresh hook in `QuickInkApp`. ~80 lines + activity tracker class.

b. Stub-poisoning guard in `AuthStore.restore()`. 6 lines.

PR 2 — error-class disambiguation (3 above):

a. `DriveError.RateLimited` sealed-subclass.

b. `OkHttpDriveClient` body-parsing classifier + six call-site updates.

c. `QuickInkSyncWorker` extra catch.

PR 3 — UX (4 above):

a. AuthRejectedBanner adds "Try again" wired to the hook from PR 1.

PR 1 is the high-impact one. PR 2 and PR 3 are quality-of-life.

---

## Test plan

For each PR, verify on a real device:

1. **Cold launch with valid keychain token, online:** sync runs within ~5s, "Last synced" updates. (Regression check.)

2. **Cold launch with token past expiry (force-set `expires_at = 0` in EncryptedSharedPreferences via debug rig):** foreground hook fires `refresh()` on ON_START, token rotates silently, next sync succeeds. No banner.

3. **Stub-poisoning:** prepopulate prefs with `access_token = "stub-access-token"`, launch. Expect SignIn screen (NOT a sync 401 loop, NOT a stuck banner).

4. **Rate limit:** force a 403 with `rateLimitExceeded` body via OkHttp `Interceptor` in a debug build. Worker `Result.retry`s with backoff. NO banner.

5. **Real revoke:** sign in, revoke at https://myaccount.google.com/permissions. Trigger sync. Banner appears. Tap "Try again" — refresh fails with `Drive scope no longer granted`. Tap "Sign out" — full re-auth flow, banner clears.

6. **Network offline:** worker hits IO exception, `Result.retry`. No banner.

Unit tests:

- `AuthStoreTest`: stub-poisoning guard returns `SignedOut`.
- `OkHttpDriveClientErrorClassifierTest`: 403 + `{"reason":"rateLimitExceeded"}` → `RateLimited`; 403 + no body → `Unauthenticated`; 401 → `Unauthenticated`.
- `QuickInkSyncWorkerTest`: existing AUTH_REJECTED path still fires on `Unauthenticated`; new TRANSIENT path fires on `RateLimited`.

---

## One thing I'd like to confirm before editing

I want to verify, with a one-line debug log in your build, whether the 401/403 you're seeing right now is:

a. **Stub-poisoning** — the access token in prefs is the literal `"stub-access-token"`. PR 1's stub-guard fixes this in one line.

b. **Expired-token** — the access token is a real `ya29.*` string but Drive rejects it. PR 1's foreground refresh fixes this.

c. **Real revoke / scope-not-granted** — refresh also fails. PR 3's UX is what matters; the underlying problem is user-side.

The debug log I'd add (and revert before merge):

```kotlin
// In AuthStore.restore(), right before the final return:
android.util.Log.w("QuickInkAuth",
    "restore: tokenPrefix=${access.take(12)}... " +
    "expiresAt=$expiresAt nowEpoch=${Instant.now().epochSecond}")
```

`adb logcat -s QuickInkAuth` will show the line on next launch. If `tokenPrefix` starts with `stub-access`, it's (a). If it starts with `ya29.`, it's (b) or (c) — and we look at the next sync's outcome to disambiguate.

If you confirm which one it is, I can prioritize the matching PR first instead of landing all three together.
