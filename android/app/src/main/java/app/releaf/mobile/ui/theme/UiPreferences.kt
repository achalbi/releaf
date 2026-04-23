/*
 * UiPreferences.kt
 *
 * Process-scoped store for the two UI preferences that affect the
 * whole app chrome:
 *   - [ThemeMode]        — System / Light / Dark override
 *   - [AccentPaletteId]  — which primary-color palette to paint with
 *
 * Backed by SharedPreferences (no Gradle dep + synchronous read at
 * startup is fine for two flags). Callers observe the current value
 * via [state] (a `StateFlow`) and mutate via the `set*` methods.
 */

package app.releaf.mobile.ui.theme

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How the app should resolve light-vs-dark. */
enum class ThemeMode { System, Light, Dark }

/** Which of the four primary palettes paints the accent roles. */
enum class AccentPaletteId { Coral, Green, Yellow, Dry }

/**
 * Which view mode the Tasks screen opens in. Also the source of
 * truth for the in-screen view-mode switcher — a toggle there
 * writes back here so "what mode the user last used" persists
 * across cold starts without a separate saver.
 */
enum class TaskDefaultView { Perspectives, List }

data class UiPreferencesState(
    val themeMode: ThemeMode = ThemeMode.System,
    val paletteId: AccentPaletteId = AccentPaletteId.Coral,
    val defaultTaskView: TaskDefaultView = TaskDefaultView.Perspectives,
)

class UiPreferences(private val prefs: SharedPreferences) {

    private val _state = MutableStateFlow(load())
    val state: StateFlow<UiPreferencesState> = _state.asStateFlow()

    private fun load(): UiPreferencesState {
        val mode = prefs.getString(KEY_THEME_MODE, null)
            ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
            ?: ThemeMode.System
        val palette = prefs.getString(KEY_PALETTE, null)
            ?.let { runCatching { AccentPaletteId.valueOf(it) }.getOrNull() }
            ?: AccentPaletteId.Coral
        val taskView = prefs.getString(KEY_DEFAULT_TASK_VIEW, null)
            ?.let { runCatching { TaskDefaultView.valueOf(it) }.getOrNull() }
            ?: TaskDefaultView.Perspectives
        return UiPreferencesState(
            themeMode       = mode,
            paletteId       = palette,
            defaultTaskView = taskView,
        )
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
        _state.value = _state.value.copy(themeMode = mode)
    }

    fun setPalette(id: AccentPaletteId) {
        prefs.edit().putString(KEY_PALETTE, id.name).apply()
        _state.value = _state.value.copy(paletteId = id)
    }

    fun setDefaultTaskView(view: TaskDefaultView) {
        prefs.edit().putString(KEY_DEFAULT_TASK_VIEW, view.name).apply()
        _state.value = _state.value.copy(defaultTaskView = view)
    }

    companion object {
        private const val FILE = "releaf_ui_prefs"
        private const val KEY_THEME_MODE        = "theme_mode"
        private const val KEY_PALETTE           = "accent_palette"
        private const val KEY_DEFAULT_TASK_VIEW = "default_task_view"

        @Volatile private var instance: UiPreferences? = null

        fun get(context: Context): UiPreferences =
            instance ?: synchronized(this) {
                instance ?: UiPreferences(
                    context.applicationContext
                        .getSharedPreferences(FILE, Context.MODE_PRIVATE),
                ).also { instance = it }
            }
    }
}
