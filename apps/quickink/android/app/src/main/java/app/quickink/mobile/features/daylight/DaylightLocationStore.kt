/*
 * DaylightLocationStore.kt
 *
 * Tiny Compose-friendly store the Home `DaylightHero` card reads
 * its lat/long from. Sits in front of `LocationService.captureCurrent`
 * so the hero doesn't have to know about Android location APIs,
 * permission states, or the scan-flow capture path.
 *
 * Mirror of iOS `DaylightLocationStore.swift`.
 *
 * Strategy:
 *   - Persist the last successful fix in SharedPreferences so every
 *     launch after the first paints the hero instantly. Sunrise/
 *     sunset drift by <1 minute over ~100km — persisted coords stay
 *     good for weeks even if the user travels.
 *
 *   - On `refreshIfNeeded`, kick off one suspend `captureCurrent`
 *     and overwrite the cache. Skipped when permission isn't
 *     granted (the hero is not a prompt path; it must not surface
 *     the system dialog).
 *
 *   - In-flight de-dup: a single `isFetching` flag prevents two
 *     overlapping calls when refresh is invoked from both a
 *     `LaunchedEffect` and a lifecycle hook.
 */

package app.quickink.mobile.features.daylight

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.quickink.mobile.features.scan.LocationService
import androidx.core.content.edit

/**
 * Holds the cached lat/long the daylight bar reads from. Hosted
 * by `QuickInkRoot.MainShell` via `remember(applicationContext)`
 * so it persists across recomposition but is bound to the
 * activity's lifetime.
 */
class DaylightLocationStore(private val appContext: Context) {

    /** Latitude of the last successful fix, or null when none yet. */
    var latitude: Double? by mutableStateOf(null)
        private set

    /** Longitude of the last successful fix, or null when none yet. */
    var longitude: Double? by mutableStateOf(null)
        private set

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var isFetching: Boolean = false

    init {
        // Seed from the persisted cache — re-launches paint the bar
        // immediately rather than flashing the loading shell.
        if (prefs.contains(KEY_LAT) && prefs.contains(KEY_LNG)) {
            // Stored as Float — sunrise/sunset don't need lat/long
            // beyond ~4 decimal places, and Float gives us that.
            latitude  = prefs.getFloat(KEY_LAT, 0f).toDouble()
            longitude = prefs.getFloat(KEY_LNG, 0f).toDouble()
        }
    }

    /**
     * Kick off a one-shot location fetch if we don't already have
     * coordinates AND permission is granted. No-op when there's
     * already an in-flight fetch, no permission, or the cache is
     * still fresh (<24h). Safe to call repeatedly from a
     * `LaunchedEffect`.
     */
    suspend fun refreshIfNeeded() {
        if (isFetching) return
        if (!LocationService.hasPermission(appContext)) return
        if (latitude != null && longitude != null && !cacheIsStale()) return

        isFetching = true
        try {
            val captured = LocationService.captureCurrent(appContext) ?: return
            latitude  = captured.latitude
            longitude = captured.longitude
            prefs.edit {
                putFloat(KEY_LAT, captured.latitude.toFloat())
                putFloat(KEY_LNG, captured.longitude.toFloat())
                putLong (KEY_FETCHED_AT, System.currentTimeMillis())
            }
        } finally {
            isFetching = false
        }
    }

    /**
     * Cache considered stale after 24h. The sun's path is the
     * same week to week at a fixed point on Earth; a daily refresh
     * keeps us honest if the user travelled overnight.
     */
    private fun cacheIsStale(): Boolean {
        val ts = prefs.getLong(KEY_FETCHED_AT, 0L)
        if (ts == 0L) return true
        val ageMs = System.currentTimeMillis() - ts
        return ageMs > 24L * 60L * 60L * 1000L
    }

    private companion object {
        const val PREFS_NAME     = "quickink.daylight"
        const val KEY_LAT        = "lat"
        const val KEY_LNG        = "lng"
        const val KEY_FETCHED_AT = "fetched_at"
    }
}
