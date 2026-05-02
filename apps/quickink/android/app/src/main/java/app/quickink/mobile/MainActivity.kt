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

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import app.quickink.mobile.features.splash.QuickInkSplash
import app.quickink.mobile.ui.theme.QuickInkTheme

class MainActivity : ComponentActivity() {
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
        setContent {
            QuickInkTheme {
                var showSplash by remember { mutableStateOf(true) }
                if (showSplash) {
                    QuickInkSplash(onFinished = { showSplash = false })
                } else {
                    // QuickInkTheme provides the warm coral/cream palette
                    // and Cormorant Garamond / Caveat typography via
                    // CompositionLocals. Every screen under QuickInkRoot
                    // reads from `LocalQuickInkColors` /
                    // `LocalQuickInkTypography`, so the wrapper here is
                    // what makes the whole app look like the mockups.
                    QuickInkRoot()
                }
            }
        }
    }
}
