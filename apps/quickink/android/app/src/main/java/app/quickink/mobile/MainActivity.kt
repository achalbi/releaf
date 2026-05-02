/*
 * MainActivity.kt
 *
 * QuickInk's single activity. Hosts the Compose tree rooted at
 * `QuickInkRoot()`. Counterpart to Releaf's `MainActivity`, but no
 * navigation graph yet — that lands incrementally as the MVP flow
 * (Onboarding → Camera-first Home → Scan + OCR → Notes list →
 * Editor) ships, per QUICKINK_PROPOSAL.md §6.4.
 *
 * Splash-screen handling: the manifest gives this activity
 * `Theme.QuickInk.Splash` so the system shows the cream + centered-Q
 * window background between launcher tap and our first Compose
 * frame. Immediately on entering `onCreate` we swap to
 * `Theme.QuickInk` (no splash drawable on the window) so the splash
 * doesn't bleed into the in-app surface. The Compose-side
 * `QuickInkTheme { ... }` wrapper still owns the actual look of every
 * screen — this XML theme step only governs the system splash window
 * and the early background color before Compose paints.
 *
 * If we later add `androidx.core:core-splashscreen` for nicer
 * Android 12+ splash transitions, swap this `setTheme` block for
 * `installSplashScreen()`. The drawable + theme files don't need
 * to change.
 */

package app.quickink.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.quickink.mobile.ui.theme.QuickInkTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Swap from the splash theme (windowBackground = cream + Q
        // mark) to the real app theme (windowBackground = plain
        // cream) before super.onCreate so the splash drawable doesn't
        // remain behind Compose's first frame. Order matches the
        // Releaf MainActivity convention.
        setTheme(R.style.Theme_QuickInk)
        super.onCreate(savedInstanceState)
        setContent {
            // QuickInkTheme provides the warm coral/cream palette
            // and Cormorant Garamond / Caveat typography via
            // CompositionLocals. Every screen under QuickInkRoot
            // reads from `LocalQuickInkColors` / `LocalQuickInkTypography`,
            // so the wrapper here is what makes the whole app look
            // like the mockups.
            QuickInkTheme {
                QuickInkRoot()
            }
        }
    }
}
