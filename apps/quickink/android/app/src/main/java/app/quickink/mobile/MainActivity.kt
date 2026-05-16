/*
 * MainActivity.kt
 *
 * QuickInk's single activity. Hosts the Compose tree rooted at
 * `QuickInkRoot()`. Counterpart to Releaf's `MainActivity`, but no
 * navigation graph yet — that lands incrementally as the MVP flow
 * (Onboarding → Camera-first Home → Scan + OCR → Notes list →
 * Editor) ships, per QUICKINK_PROPOSAL.md §6.4.
 *
 * Splash-screen handling — modern SplashScreen API:
 *
 *   1. Manifest sets `android:theme="@style/Theme.QuickInk.Splash"`
 *      on this activity. That theme inherits from
 *      `Theme.SplashScreen` (from androidx.core:core-splashscreen)
 *      and supplies `windowSplashScreenBackground` (cream) +
 *      `windowSplashScreenAnimatedIcon` (the calligraphic Q on its
 *      coral disc).
 *
 *   2. `installSplashScreen()` runs as the very first call in
 *      `onCreate`, BEFORE `super.onCreate(savedInstanceState)`. The
 *      AndroidX library reads the splash theme attributes, draws
 *      the system splash, and — once Compose's first frame is
 *      ready — animates it out into the activity's
 *      `postSplashScreenTheme` (Theme.QuickInk → cream window
 *      background, no splash drawable). No manual `setTheme` swap
 *      needed.
 *
 *   3. On Android 12+: the platform's animated SplashScreen window
 *      shows the icon with the system's scale-in animation. On
 *      Android 6–11: the compat library renders an equivalent
 *      static splash from the same theme attributes.
 *
 * If you ever need to hold the splash open while async work
 * completes (e.g. waiting for AuthStore to settle before deciding
 * Onboarding vs. Home), wrap the splash returned by
 * `installSplashScreen()` and call `setKeepOnScreenCondition { ... }`
 * with a state predicate. Today we just let it dismiss on first
 * Compose frame — `QuickInkRoot` handles routing on its own.
 */

package app.quickink.mobile

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.quickink.mobile.features.scan.PendingShare
import app.quickink.mobile.features.settings.SettingsPreferences
import app.quickink.mobile.features.splash.QuickInkLaunchAnimation
import app.quickink.mobile.ui.theme.QuickInkTheme

class MainActivity : ComponentActivity() {

    /**
     * URIs handed in via a system share intent (ACTION_SEND /
     * ACTION_SEND_MULTIPLE), held as a Compose state so the
     * QuickInkRoot tree picks up shares delivered while the
     * activity is already alive (`onNewIntent`). MainShell consumes
     * the value via a `LaunchedEffect`, runs the import on IO, and
     * then calls back through `onPendingShareConsumed` to clear
     * this field so a recomposition doesn't re-import.
     *
     * `mutableStateOf` (not StateFlow) — the only reader is the
     * Compose tree owned by this activity, and the writer is the
     * activity itself. A flow would need an extra collector + the
     * `collectAsState` ceremony for no benefit.
     */
    private val pendingShare = mutableStateOf<PendingShare?>(null)

    /**
     * Latch that says "we've already claimed the launch intent on
     * this activity instance — don't re-read it on recreation."
     *
     * This is the fix for a duplicate-import bug: without it,
     * activity recreation (config change, dark-mode flip, process
     * death + restore) hits `onCreate` against the SAME share
     * intent (`getIntent()` still returns the SEND intent that
     * launched us), `extractShareFromIntent` returns the same
     * payload, and we kick off a second copy + OCR pass — leaving
     * a duplicate capture row plus the visible "loading repeatedly"
     * loop the user sees as the splash → import flashes through
     * again on each recreation.
     *
     * Persisted across recreation via `savedInstanceState` so the
     * latch survives. `onNewIntent` resets it to false so a fresh
     * share delivered to the running activity is processed normally.
     */
    private var hasClaimedShareIntent: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // Hand the splash window over to androidx.core:core-splashscreen.
        // Must be called BEFORE super.onCreate; the library installs
        // an `OnPreDrawListener` that holds the system splash until
        // Compose paints, then runs the standard exit animation
        // into Theme.QuickInk (declared as `postSplashScreenTheme`
        // on Theme.QuickInk.Splash).
        //
        // System splash on Android 12+ shows the icon only — no text
        // is supported by the platform splash window. The Compose
        // `QuickInkSplash` wrapper below holds the screen briefly
        // after the system splash hands off, rendering the mark +
        // wordmark together (matching the brand prototype board).
        installSplashScreen()
        super.onCreate(savedInstanceState)

        // Hide the top status bar app-wide. We draw the Compose tree
        // edge-to-edge under where the bar used to live, then ask the
        // window-insets controller to hide just the `statusBars()`
        // type so the bottom nav bar stays as-is. `BEHAVIOR_SHOW_
        // TRANSIENT_BARS_BY_SWIPE` matches the standard immersive UX
        // — a swipe-down from the top reveals the bar briefly and
        // it re-hides on its own. WindowCompat / WindowInsetsCompat
        // are the AndroidX shims, so this works back to minSdk 26
        // without API-level branching.
        //
        // DO NOT add the legacy `FLAG_FULLSCREEN` window flag here —
        // it suppresses IME-inset dispatch and breaks soft-keyboard
        // input on `BasicTextField` (e.g. the Search bar) and locks
        // up Compose `ModalBottomSheet` touches (e.g. the Manage-tags
        // sheet on the Scan Detail screen). Modals that genuinely
        // need to repaint over the status bar should apply the flag
        // to their own Dialog window only — see
        // `SustainabilityBreakdownSheet` in `HomeScreen.kt`.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // Restore the launch-intent latch first. On config-change
        // recreation `savedInstanceState` is non-null and carries
        // forward whatever the previous instance had recorded; on a
        // genuine cold launch it's null and the latch starts false.
        hasClaimedShareIntent = savedInstanceState
            ?.getBoolean(STATE_HAS_CLAIMED_SHARE_INTENT, false)
            ?: false

        // Cold-launch share path: if MainActivity was created in
        // response to a share intent (system share sheet on the
        // sender side, target activity not yet running), seed the
        // pending-share state from the launch Intent now so MainShell
        // sees it on its first composition. The hot path lives in
        // `onNewIntent` below.
        //
        // Skipped on recreation when the latch says we've already
        // claimed this intent — otherwise the SEND intent the OS
        // re-attaches to the recreated activity would re-trigger
        // the import + OCR pass, leaving a duplicate capture and
        // the loading-loop UX the user reported.
        if (!hasClaimedShareIntent) {
            val incoming = extractShareFromIntent(intent)
            if (incoming != null) {
                pendingShare.value     = incoming
                hasClaimedShareIntent  = true
            }
        }

        setContent {
            // Hoist the user's Appearance picks (primary color +
            // theme mode) up to the activity so the QuickInkTheme
            // wrapper sees them on every recomposition. The Settings
            // screen mutates these via callbacks plumbed through
            // QuickInkRoot — local state + a parallel SP write keeps
            // the theme reactive across tab switches without a
            // SharedPreferences observer.
            val context = LocalContext.current
            val preferences = remember { SettingsPreferences(context) }
            var primaryColor by remember { mutableStateOf(preferences.primaryColor) }
            var themeMode    by remember { mutableStateOf(preferences.themeMode) }

            QuickInkTheme(
                themeMode    = themeMode,
                primaryColor = primaryColor,
            ) {
                var showSplash by remember { mutableStateOf(true) }
                if (showSplash) {
                    // Read the user's last-known Tree-points balance
                    // (written by HomeScreen on every page-count flow
                    // push) so the cinematic counter pill ticks up to
                    // the user's actual current value rather than a
                    // hardcoded preview default. Defaults to 0 on a
                    // fresh install — the counter then doesn't tick,
                    // which is the correct empty-state read.
                    val cachedTarget = remember(context) {
                        app.quickink.mobile.features.settings.SettingsPreferences
                            .readCachedTreePoints(context)
                    }
                    QuickInkLaunchAnimation(
                        onFinished = { showSplash = false },
                        target     = cachedTarget,
                    )
                } else {
                    // QuickInkTheme provides the warm coral/cream palette
                    // and Cormorant Garamond / Caveat typography via
                    // CompositionLocals. Every screen under QuickInkRoot
                    // reads from `LocalQuickInkColors` /
                    // `LocalQuickInkTypography`, so the wrapper here is
                    // what makes the whole app look like the mockups.
                    QuickInkRoot(
                        currentPrimaryColor   = primaryColor,
                        currentThemeMode      = themeMode,
                        onPrimaryColorChange  = {
                            primaryColor = it
                            preferences.primaryColor = it
                        },
                        onThemeModeChange     = {
                            themeMode = it
                            preferences.themeMode = it
                        },
                        // Share-target plumbing: MainShell reads the
                        // value via a LaunchedEffect, kicks the
                        // import on IO, then clears the field via
                        // `onPendingShareConsumed`. The activity
                        // itself doesn't care whether the consumer
                        // was the import path or a not-yet-mounted
                        // sign-in gate — the state survives across
                        // gates because it's an activity-scoped
                        // mutableStateOf, not a Composable-scoped
                        // remember.
                        pendingShare              = pendingShare.value,
                        onPendingShareConsumed    = { pendingShare.value = null },
                    )
                }
            }
        }
    }

    /**
     * Hot share path. Fires when a SEND/SEND_MULTIPLE intent is
     * delivered to an already-running MainActivity (singleTask
     * launch mode keeps a single instance per task, so the OS
     * routes here instead of constructing a new activity).
     *
     * Also calls `setIntent(intent)` so the activity's saved
     * intent reflects the share — without it, a configuration
     * change would re-run `onCreate` against the original launch
     * intent and the share would be lost.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val incoming = extractShareFromIntent(intent)
        if (incoming != null) {
            // Overwrite any stale pending-share that hasn't been
            // consumed yet (e.g. user shared once while signed out,
            // bounced through the sign-in gate, then shared again
            // before the gate cleared). The newest share wins —
            // matches platform expectations for share targets.
            pendingShare.value     = incoming
            // Re-arm the latch for this brand-new intent so a
            // recreation that follows still sees it claimed and
            // doesn't double-trigger.
            hasClaimedShareIntent  = true
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Persist the latch so config-change recreation restores it
        // on the next `onCreate` and the share isn't re-claimed.
        outState.putBoolean(STATE_HAS_CLAIMED_SHARE_INTENT, hasClaimedShareIntent)
    }

    private companion object {
        private const val STATE_HAS_CLAIMED_SHARE_INTENT = "share.has_claimed_intent"
    }

    /**
     * Pull URIs out of a SEND / SEND_MULTIPLE intent. Returns null
     * for any intent that isn't a recognised share so callers can
     * unconditionally feed `getIntent()` (which on a normal cold
     * launch is `ACTION_MAIN`) without checking first.
     *
     * `Intent.EXTRA_STREAM` is read with the typed overload on
     * Tiramisu+ and the deprecated untyped form on older API
     * levels — minSdk 26 still in scope.
     */
    private fun extractShareFromIntent(intent: Intent?): PendingShare? {
        if (intent == null) return null
        val type = intent.type ?: return null
        val isPdf = when {
            type.startsWith("image/")        -> false
            type == "application/pdf"        -> true
            // Anything else (text/*, video/*, etc.) — the manifest
            // doesn't advertise QuickInk for those types, so this
            // branch is defensive only.
            else                             -> return null
        }
        return when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = singleStreamExtra(intent) ?: return null
                PendingShare(uris = listOf(uri), isPdf = isPdf)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                // PDF SEND_MULTIPLE isn't a manifest-declared filter,
                // but a sender could deliver one anyway. Reject —
                // we don't have a defined behaviour for combining
                // multiple separate PDFs into one capture, and a
                // half-correct flow is worse than a no-op.
                if (isPdf) return null
                val uris = multipleStreamExtras(intent) ?: return null
                if (uris.isEmpty()) null
                else PendingShare(uris = uris, isPdf = false)
            }
            else -> null
        }
    }

    private fun singleStreamExtra(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }

    private fun multipleStreamExtras(intent: Intent): List<Uri>? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
        }
}
