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
 * Global typographic weight applied to every role in [AppTypography]
 * and every inline override scattered through the app. The user picks
 * one of these in Settings; everything cascades from a single source
 * via [LocalFontWeight].
 */
enum class AppFontWeight { Light, Regular, Medium, SemiBold }

/**
 * Which view mode the Tasks screen opens in. Also the source of
 * truth for the in-screen view-mode switcher — a toggle there
 * writes back here so "what mode the user last used" persists
 * across cold starts without a separate saver.
 */
enum class TaskDefaultView { Perspectives, List }

/**
 * Which visual treatment the notebook/chapter/page surfaces use.
 * `Classic` keeps the existing list-card UI; `Variant1` swaps in
 * the editorial hero-card Figma design.
 */
enum class NotebookListVariant { Classic, Variant1 }

/**
 * How long to keep audit-log events before the prune worker drops
 * them. `Forever` skips pruning entirely. The default is 365 days —
 * matches the rough "one year of history" expectation users have for
 * activity logs while keeping disk + sync growth bounded.
 */
enum class ActivityRetention(val days: Int?) {
    Days30(30),
    Days90(90),
    Days365(365),
    Forever(null),
}

/**
 * How the Home-screen activity timeline is rendered.
 *
 * `Classic` keeps the dot-on-rail "TimelineRow" treatment that ships
 * by default. `Bramble` swaps in the editorial vine-with-flowers
 * variant from the design exploration (see
 * `design-system/timeline-vine-bramble-garland.html` and
 * `ui/components/ActivityTimeline.kt`). Identical data, different
 * rendering — the user picks between them in Settings.
 */
enum class TimelineStyle { Classic, Bramble }

/** How the page-detail Overview presents its AT A GLANCE block —
 *  three-up grid (default) or single-column list. Persisted across
 *  launches so the user's last choice survives a cold start. */
enum class PageDetailViewMode { Grid, List }

/** How the notebooks list is ordered. Persisted across launches.
 *  Mirrors `NotebookSortMode` on iOS. */
enum class NotebookSortPreference { Recent, Name, Pages }

data class UiPreferencesState(
    val themeMode: ThemeMode = ThemeMode.System,
    val paletteId: AccentPaletteId = AccentPaletteId.Coral,
    val defaultTaskView: TaskDefaultView = TaskDefaultView.Perspectives,
    val notebookVariant: NotebookListVariant = NotebookListVariant.Variant1,
    val fontWeight: AppFontWeight = AppFontWeight.Light,
    val activityRetention: ActivityRetention = ActivityRetention.Days365,
    val timelineStyle: TimelineStyle = TimelineStyle.Classic,
    val pageViewMode: PageDetailViewMode = PageDetailViewMode.Grid,
    val notebookSort: NotebookSortPreference = NotebookSortPreference.Recent,
    /** Has the first-launch onboarding been seen and dismissed?
     *  Defaults to `false`; flips to `true` when the onboarding
     *  view's CTA fires. */
    val hasSeenOnboarding: Boolean = false,
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
        val variant = prefs.getString(KEY_NOTEBOOK_VARIANT, null)
            ?.let { runCatching { NotebookListVariant.valueOf(it) }.getOrNull() }
            ?: NotebookListVariant.Variant1
        val weight = prefs.getString(KEY_FONT_WEIGHT, null)
            ?.let { runCatching { AppFontWeight.valueOf(it) }.getOrNull() }
            ?: AppFontWeight.Light
        val retention = prefs.getString(KEY_ACTIVITY_RETENTION, null)
            ?.let { runCatching { ActivityRetention.valueOf(it) }.getOrNull() }
            ?: ActivityRetention.Days365
        val timelineStyle = prefs.getString(KEY_TIMELINE_STYLE, null)
            ?.let { runCatching { TimelineStyle.valueOf(it) }.getOrNull() }
            ?: TimelineStyle.Classic
        val pageViewMode = prefs.getString(KEY_PAGE_VIEW_MODE, null)
            ?.let { runCatching { PageDetailViewMode.valueOf(it) }.getOrNull() }
            ?: PageDetailViewMode.Grid
        val notebookSort = prefs.getString(KEY_NOTEBOOK_SORT, null)
            ?.let { runCatching { NotebookSortPreference.valueOf(it) }.getOrNull() }
            ?: NotebookSortPreference.Recent
        val hasSeenOnboarding = prefs.getBoolean(KEY_HAS_SEEN_ONBOARDING, false)
        return UiPreferencesState(
            themeMode         = mode,
            paletteId         = palette,
            defaultTaskView   = taskView,
            notebookVariant   = variant,
            fontWeight        = weight,
            activityRetention = retention,
            timelineStyle     = timelineStyle,
            pageViewMode      = pageViewMode,
            notebookSort      = notebookSort,
            hasSeenOnboarding = hasSeenOnboarding,
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

    fun setNotebookVariant(variant: NotebookListVariant) {
        prefs.edit().putString(KEY_NOTEBOOK_VARIANT, variant.name).apply()
        _state.value = _state.value.copy(notebookVariant = variant)
    }

    fun setFontWeight(weight: AppFontWeight) {
        prefs.edit().putString(KEY_FONT_WEIGHT, weight.name).apply()
        _state.value = _state.value.copy(fontWeight = weight)
    }

    fun setActivityRetention(retention: ActivityRetention) {
        prefs.edit().putString(KEY_ACTIVITY_RETENTION, retention.name).apply()
        _state.value = _state.value.copy(activityRetention = retention)
    }

    fun setTimelineStyle(style: TimelineStyle) {
        prefs.edit().putString(KEY_TIMELINE_STYLE, style.name).apply()
        _state.value = _state.value.copy(timelineStyle = style)
    }

    fun setPageViewMode(mode: PageDetailViewMode) {
        prefs.edit().putString(KEY_PAGE_VIEW_MODE, mode.name).apply()
        _state.value = _state.value.copy(pageViewMode = mode)
    }

    fun setNotebookSort(sort: NotebookSortPreference) {
        prefs.edit().putString(KEY_NOTEBOOK_SORT, sort.name).apply()
        _state.value = _state.value.copy(notebookSort = sort)
    }

    /** Mark first-launch onboarding as seen. Idempotent — repeated
     *  calls are safe; flips the flag for good. */
    fun markOnboardingSeen() {
        prefs.edit().putBoolean(KEY_HAS_SEEN_ONBOARDING, true).apply()
        _state.value = _state.value.copy(hasSeenOnboarding = true)
    }

    companion object {
        private const val FILE = "releaf_ui_prefs"
        private const val KEY_THEME_MODE          = "theme_mode"
        private const val KEY_PALETTE             = "accent_palette"
        private const val KEY_DEFAULT_TASK_VIEW   = "default_task_view"
        private const val KEY_NOTEBOOK_VARIANT    = "notebook_variant"
        private const val KEY_FONT_WEIGHT         = "font_weight"
        private const val KEY_ACTIVITY_RETENTION  = "activity_retention"
        private const val KEY_TIMELINE_STYLE      = "timeline_style"
        private const val KEY_PAGE_VIEW_MODE      = "page_view_mode"
        private const val KEY_NOTEBOOK_SORT       = "notebook_sort"
        private const val KEY_HAS_SEEN_ONBOARDING = "has_seen_onboarding"

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
