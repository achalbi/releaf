/*
 * LocationService.kt
 *
 * Thin wrapper around Android's `LocationManager` + `Geocoder` used
 * by the scan / import flows to attach a (latitude, longitude, sub-
 * locality, locality) reading to each capture. Mirror of iOS's
 * `LocationService.swift` — both apps gate the fetch on a Settings
 * toggle, return `null` on permission / fetch / geocode failure, and
 * never block the scan path.
 *
 * Implementation choices:
 *   - Coarse accuracy only. The Details card shows sub-locality and
 *     locality (city + area); GPS-level precision adds nothing here
 *     and would slow the first fix.
 *   - Single-shot, no observer registration. We ask for the last
 *     known location first (free, instant); if that's missing or
 *     stale, we fall back to `getCurrentLocation` (Android 12+) /
 *     `requestSingleUpdate` (older API levels) with a short timeout.
 *   - Reverse-geocoding uses the on-device `Geocoder`. It's
 *     synchronous on API < 33 and uses a callback on API ≥ 33; we
 *     bridge both to a single coroutine return value.
 *
 * No FusedLocationProviderClient — that's a Play Services dependency
 * and the simpler `LocationManager` API covers the city-level
 * accuracy we need without adding a transitive Google API surface.
 */

package app.quickink.mobile.features.scan

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import app.quickink.mobile.data.capture.CapturedLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/**
 * One-call helper for "give me the current location with a reverse-
 * geocoded place name." Returning `null` is the canonical "couldn't
 * get a fix" signal; the capture row writes back four NULL columns
 * and the Details card simply omits the Area / City rows. Callers
 * are expected to check the Settings toggle before invoking — this
 * service stays focused on the fetch itself.
 */
object LocationService {

    /**
     * SharedPreferences name + key for the one-shot prompt tracking
     * flag. Distinct from the main `quickink.settings` namespace so
     * a future `clearAllUserOverrides` in SettingsPreferences won't
     * accidentally re-prompt the user on the next launch.
     */
    private const val PROMPT_PREFS_NAME = "quickink.location"
    private const val PROMPT_HANDLED_KEY = "prompt_handled_v1"

    /**
     * True when the user has granted (at least) coarse location
     * permission. Checked synchronously so the caller can short-
     * circuit before kicking off the coroutine in [captureCurrent].
     */
    fun hasPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * True when we've already asked the user about location (via the
     * onboarding step OR the post-onboarding one-shot trigger in
     * MainShell). Suppresses the one-shot re-ask on every app
     * launch. Mirror of iOS `LocationService.wasPromptHandled`.
     */
    fun wasPromptHandled(context: Context): Boolean {
        val prefs = context.applicationContext
            .getSharedPreferences(PROMPT_PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(PROMPT_HANDLED_KEY, false)
    }

    /**
     * Mark the prompt as handled. Called from the onboarding
     * LocationPermissionScreen (whether the user tapped Allow or
     * Skip) and from the MainShell's one-shot trigger so the two
     * paths share a single flag.
     */
    fun markPromptHandled(context: Context) {
        val prefs = context.applicationContext
            .getSharedPreferences(PROMPT_PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(PROMPT_HANDLED_KEY, true).apply()
    }

    /**
     * Some `Geocoder` results return the same string for `locality`
     * and `subLocality` when neighborhood-level data isn't available
     * — typical for many non-US cities where the geocoder only
     * resolves to the city, not the neighborhood. Dropping the
     * redundant sub-locality here keeps the Details card from
     * rendering an "Area" and a "City" row with identical values.
     *
     * Called by `captureCurrent` (write-time, so new scans land
     * clean), by `ScanDetailScreen`'s lazy retry (so backfilled
     * rows land clean too), and by the Details render path (so
     * existing rows with duplicate values render correctly without
     * a DB migration). Mirror of iOS `LocationService.dedupePlaceNames`.
     */
    fun dedupePlaceNames(
        locality: String?,
        subLocality: String?,
    ): Pair<String?, String?> {
        val trimmedLoc = locality?.trim()
        val trimmedSub = subLocality?.trim()
        if (!trimmedLoc.isNullOrEmpty() && trimmedLoc == trimmedSub) {
            return locality to null
        }
        return locality to subLocality
    }

    /**
     * Fetch a single location reading + reverse-geocode it. Returns
     * `null` when:
     *   - location permission isn't granted,
     *   - `LocationManager` reports all providers disabled,
     *   - the single-shot fetch times out (5 seconds),
     *   - the reverse-geocoder fails / returns no placemark.
     *
     * Safe to call from any dispatcher — the location + geocoder
     * work hops onto `Dispatchers.IO` internally.
     */
    suspend fun captureCurrent(context: Context): CapturedLocation? {
        if (!hasPermission(context)) return null
        val appContext = context.applicationContext
        return withContext(Dispatchers.IO) {
            val location = fetchLocation(appContext) ?: return@withContext null
            val placemark = reverseGeocode(appContext, location)
            val (locality, subLocality) = dedupePlaceNames(
                locality    = placemark?.first,
                subLocality = placemark?.second,
            )
            CapturedLocation(
                latitude    = location.latitude,
                longitude   = location.longitude,
                locality    = locality,
                subLocality = subLocality,
            )
        }
    }

    /**
     * Try the cheap path first: the most recent cached fix from any
     * enabled provider. Falls through to a one-shot request when the
     * cache is empty or stale. Five-second timeout on the request
     * keeps a slow GPS warm-up from blocking the scan path.
     */
    @SuppressLint("MissingPermission")
    private suspend fun fetchLocation(context: Context): Location? {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null
        if (!LocationManagerCompat.isLocationEnabled(manager)) return null

        // Walk every enabled provider for a recent cached reading.
        // A fix from the network provider that's < 5 minutes old is
        // plenty fresh for city-level place names — no need to wake
        // GPS for a per-scan attach.
        val freshnessThresholdMs = 5 * 60 * 1000L
        val nowMs = System.currentTimeMillis()
        val cached = manager.allProviders
            .mapNotNull { provider ->
                try {
                    manager.getLastKnownLocation(provider)
                } catch (_: SecurityException) {
                    null
                }
            }
            .filter { nowMs - it.time < freshnessThresholdMs }
            .maxByOrNull { it.time }
        if (cached != null) return cached

        // No fresh cache → one-shot request. 5s timeout via withTimeoutOrNull;
        // a "never resolved" path (provider stuck) bails cleanly rather
        // than blocking the scan flow forever.
        return withTimeoutOrNull(5_000L) {
            requestSingleLocation(manager)
        }
    }

    /**
     * Bridge `LocationManager.getCurrentLocation` (API ≥ 30) /
     * `requestSingleUpdate` (older API levels) to a suspending call.
     * Resolves with the first fix from any provider; resolves with
     * `null` when cancelled or when the provider stack reports
     * unavailable.
     */
    @SuppressLint("MissingPermission")
    private suspend fun requestSingleLocation(manager: LocationManager): Location? {
        // Pick the best available provider for a one-shot read.
        // NETWORK is cheap and usually accurate to a city block;
        // PASSIVE picks up whatever's already on; GPS as last resort.
        val provider = listOf(
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
            LocationManager.GPS_PROVIDER,
        ).firstOrNull { manager.isProviderEnabled(it) } ?: return null

        return suspendCancellableCoroutine { cont ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val cancellation = CancellationSignal()
                cont.invokeOnCancellation { cancellation.cancel() }
                manager.getCurrentLocation(
                    provider,
                    cancellation,
                    Runnable::run,
                ) { location ->
                    if (cont.isActive) cont.resume(location)
                }
            } else {
                @Suppress("DEPRECATION")
                manager.requestSingleUpdate(
                    provider,
                    object : android.location.LocationListener {
                        override fun onLocationChanged(location: Location) {
                            if (cont.isActive) cont.resume(location)
                        }
                        override fun onProviderEnabled(provider: String)  {}
                        override fun onProviderDisabled(provider: String) {
                            if (cont.isActive) cont.resume(null)
                        }
                        @Deprecated("legacy override, kept for API < R")
                        override fun onStatusChanged(
                            provider: String?,
                            status: Int,
                            extras: android.os.Bundle?,
                        ) {}
                    },
                    android.os.Looper.getMainLooper(),
                )
            }
        }
    }

    /**
     * Public reverse-geocode entry point used by `ScanDetailScreen`'s
     * post-load retry: when a capture has lat/lon but no locality /
     * sub-locality (Geocoder was rate-limited / offline at scan
     * time), Details re-runs this and persists the result. Builds
     * a transient `Location` so the inner [reverseGeocode] overload
     * doesn't need a second copy of the API-level branching.
     *
     * Returns `null` on any failure — same contract as the inner
     * overload, so callers can no-op cleanly.
     */
    suspend fun reverseGeocode(
        context: Context,
        latitude: Double,
        longitude: Double,
    ): Pair<String?, String?>? {
        val loc = Location("manual").apply {
            this.latitude  = latitude
            this.longitude = longitude
        }
        return reverseGeocode(context, loc)
    }

    /**
     * Reverse-geocode a `Location` to (locality, subLocality). Uses
     * the API ≥ 33 callback form when available so we don't block on
     * the network call from the IO dispatcher; falls back to the
     * synchronous overload on older devices.
     *
     * Returns `null` on any failure — empty results, IO error, or a
     * null address list — so the caller writes the coordinates
     * without place names rather than failing the whole capture.
     */
    private suspend fun reverseGeocode(
        context: Context,
        location: Location,
    ): Pair<String?, String?>? {
        val geocoder = runCatching {
            Geocoder(context, Locale.getDefault())
        }.getOrNull() ?: return null

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            withTimeoutOrNull(5_000L) {
                suspendCancellableCoroutine<Pair<String?, String?>?> { cont ->
                    geocoder.getFromLocation(
                        location.latitude,
                        location.longitude,
                        1,
                    ) { addresses ->
                        val address = addresses.firstOrNull()
                        if (address == null) {
                            if (cont.isActive) cont.resume(null)
                        } else {
                            if (cont.isActive) {
                                cont.resume(address.locality to address.subLocality)
                            }
                        }
                    }
                }
            }
        } else {
            runCatching {
                @Suppress("DEPRECATION")
                val list = geocoder.getFromLocation(
                    location.latitude,
                    location.longitude,
                    1,
                )
                val address = list?.firstOrNull() ?: return@runCatching null
                address.locality to address.subLocality
            }.getOrNull()
        }
    }
}
