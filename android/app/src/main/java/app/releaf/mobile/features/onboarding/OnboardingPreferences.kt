/*
 * OnboardingPreferences.kt
 *
 * Tracks whether the user has seen (or skipped) the first-run
 * onboarding wizard. Backed by SharedPreferences to match the
 * existing [UiPreferences] pattern — no extra Gradle dep.
 */

package app.releaf.mobile.features.onboarding

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class OnboardingPreferences(private val prefs: SharedPreferences) {

    private val _completedAt = MutableStateFlow(prefs.getLong(KEY_COMPLETED_AT, 0L))
    val completedAt: StateFlow<Long> = _completedAt.asStateFlow()

    val hasCompleted: Boolean get() = _completedAt.value > 0L

    fun markComplete() {
        val now = System.currentTimeMillis()
        prefs.edit().putLong(KEY_COMPLETED_AT, now).apply()
        _completedAt.value = now
    }

    companion object {
        private const val FILE = "releaf_onboarding_prefs"
        private const val KEY_COMPLETED_AT = "completed_at"

        @Volatile private var instance: OnboardingPreferences? = null

        fun get(context: Context): OnboardingPreferences =
            instance ?: synchronized(this) {
                instance ?: OnboardingPreferences(
                    context.applicationContext
                        .getSharedPreferences(FILE, Context.MODE_PRIVATE),
                ).also { instance = it }
            }
    }
}
