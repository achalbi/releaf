/*
 * MainActivity.kt
 *
 * QuickInk's single activity. Hosts the Compose tree rooted at
 * `QuickInkRoot()`. Counterpart to Releaf's `MainActivity`, but no
 * navigation graph yet — that lands incrementally as the MVP flow
 * (Onboarding → Camera-first Home → Scan + OCR → Notes list →
 * Editor) ships, per QUICKINK_PROPOSAL.md §6.4.
 *
 * Splash-screen handling (`installSplashScreen()`, custom theme
 * resource) is deliberately deferred to the brand pass — Releaf's
 * `MainActivity` shows the pattern, including holding the splash
 * while Compose's first frame renders. We adopt the same approach
 * once QuickInk has its own splash assets.
 */

package app.quickink.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            QuickInkRoot()
        }
    }
}
