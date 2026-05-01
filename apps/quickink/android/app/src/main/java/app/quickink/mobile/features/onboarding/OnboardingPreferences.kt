/*
 * OnboardingPreferences.kt
 *
 * Thin SharedPreferences wrapper for QuickInk's onboarding-completed
 * flag. Mirror of iOS's `OnboardingState.isCompleted` /
 * `markComplete` UserDefaults pair.
 *
 * The `_v1` suffix on the key is deliberate — if QuickInk ever ships
 * a v2 onboarding (added screen, different content), bump to `_v2`
 * so existing users see the new flow once.
 */

package app.quickink.mobile.features.onboarding

import android.content.Context
import android.content.SharedPreferences

class OnboardingPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var isCompleted: Boolean
        get() = prefs.getBoolean(KEY_COMPLETED_V1, false)
        set(value) {
            prefs.edit().putBoolean(KEY_COMPLETED_V1, value).apply()
        }

    private companion object {
        const val PREFS_NAME       = "quickink.onboarding"
        const val KEY_COMPLETED_V1 = "completed_v1"
    }
}
