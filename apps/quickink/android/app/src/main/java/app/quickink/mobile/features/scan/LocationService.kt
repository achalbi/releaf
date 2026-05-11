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
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import app.quickink.mobile.data.capture.CapturedLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    // v2 — re-asks existing users who only granted ACCESS_COARSE_-
    // LOCATION the first time around. The new dialog requests both
    // coarse + fine so the system can show the Precise / Approximate
    // toggle (Android 12+); without re-asking, those users stay
    // capped at city-block triangulation accuracy.
    private const val PROMPT_HANDLED_KEY = "prompt_handled_v2"

    /**
     * Log tag for the geolocation pipeline. Grep `adb logcat -s
     * QuickInkLocation:*` to filter the capture flow's diagnostic
     * trail (auth check → fetch → reverse-geocode → dedupe →
     * persist).
     */
    private const val TAG = "QuickInkLocation"

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
     * True when the user has granted `ACCESS_FINE_LOCATION` — the
     * gate for GPS-level fixes. When false but `hasPermission` is
     * true, [fetchLocation] falls back to NETWORK_PROVIDER (cell-
     * tower / Wi-Fi triangulation), which is much less accurate
     * but doesn't require GPS warmup.
     */
    fun hasFinePermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION,
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
        if (!hasPermission(context)) {
            Log.i(TAG, "captureCurrent: no permission, returning null")
            return null
        }
        val appContext = context.applicationContext
        return withContext(Dispatchers.IO) {
            val location = fetchLocation(appContext)
            if (location == null) {
                Log.i(TAG, "captureCurrent: fetchLocation returned null")
                return@withContext null
            }
            Log.i(TAG, "captureCurrent: got fix lat=${location.latitude} lon=${location.longitude}")
            val resolved = reverseGeocodeFull(appContext, location)
            Log.i(TAG, "captureCurrent: placemark raw locality=${resolved?.locality} subLocality=${resolved?.subLocality} address=${resolved?.address}")
            val (locality, subLocality) = dedupePlaceNames(
                locality    = resolved?.locality,
                subLocality = resolved?.subLocality,
            )
            Log.i(TAG, "captureCurrent: dedupe -> locality=$locality subLocality=$subLocality address=${resolved?.address}")
            CapturedLocation(
                latitude    = location.latitude,
                longitude   = location.longitude,
                locality    = locality,
                subLocality = subLocality,
                address     = resolved?.address,
            )
        }
    }

    /**
     * Bundle of fields a single geocode call produces — kept as a
     * tight value type so the call sites get a labelled triple
     * rather than juggling three positional `Pair`s.
     */
    data class ResolvedPlace(
        val locality: String?,
        val subLocality: String?,
        val address: String?,
    )

    /**
     * Try the cheap path first: the most recent cached fix from any
     * enabled provider. Falls through to a one-shot request when the
     * cache is empty or stale. Five-second timeout on the request
     * keeps a slow GPS warm-up from blocking the scan path.
     */
    @SuppressLint("MissingPermission")
    private suspend fun fetchLocation(context: Context): Location? = coroutineScope {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return@coroutineScope null
        if (!LocationManagerCompat.isLocationEnabled(manager)) return@coroutineScope null
        val fineGranted = hasFinePermission(context)
        Log.i(TAG, "fetchLocation: fineGranted=$fineGranted")

        // Cached fix — 60 s freshness window. No tight accuracy
        // filter: NETWORK-provider cached fixes typically report
        // 500–3000 m accuracy, which is still useful for a city /
        // sub-locality reverse geocode. A genuinely garbage fix
        // (10+ km) gets cut at 5 km below.
        val freshnessThresholdMs = 60 * 1000L
        val cacheAccuracyCeilingMeters = 5_000f
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
            .filter { !it.hasAccuracy() || it.accuracy < cacheAccuracyCeilingMeters }
            // Best accuracy wins among recent fixes — earlier code
            // picked newest, which would prefer a stale NETWORK fix
            // over a fresh GPS one.
            .minByOrNull { if (it.hasAccuracy()) it.accuracy else Float.MAX_VALUE }
        if (cached != null) {
            Log.i(
                TAG,
                "fetchLocation: using cached fix provider=${cached.provider} accuracy=${cached.accuracy}m age=${nowMs - cached.time}ms",
            )
            return@coroutineScope cached
        }

        // Fresh fetch — race GPS + NETWORK in parallel inside one
        // shared budget. GPS gets street-level accuracy but warms
        // up slow (5–30 s typical, longer indoors); NETWORK is
        // ~1 s with city-block accuracy. Running them concurrently
        // means an indoor session where GPS never fixes still
        // returns a NETWORK result inside the budget, and an
        // outdoor session gets the better GPS reading without
        // serial waits.
        val gpsBudgetMs     = 10_000L
        val networkBudgetMs = 8_000L
        val gpsJob = if (fineGranted && manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            async {
                val fix = withTimeoutOrNull(gpsBudgetMs) {
                    requestFromProvider(manager, LocationManager.GPS_PROVIDER)
                }
                Log.i(TAG, "fetchLocation: GPS result=${fix?.let { "fix accuracy=${it.accuracy}m" } ?: "null/timeout"}")
                fix
            }
        } else null
        val networkJob = if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            async {
                val fix = withTimeoutOrNull(networkBudgetMs) {
                    requestFromProvider(manager, LocationManager.NETWORK_PROVIDER)
                }
                Log.i(TAG, "fetchLocation: NETWORK result=${fix?.let { "fix accuracy=${it.accuracy}m" } ?: "null/timeout"}")
                fix
            }
        } else null
        if (gpsJob == null && networkJob == null) {
            Log.i(TAG, "fetchLocation: no provider enabled, returning null")
            return@coroutineScope null
        }

        // awaitAll waits for both to finish (success or timeout),
        // then we prefer GPS over NETWORK. The 10 s upper bound
        // (max of the two budgets) is acceptable for scan UX —
        // the user typically waits a few seconds for OCR anyway.
        val results = listOfNotNull(gpsJob, networkJob).awaitAll()
        val gpsResult = gpsJob?.let { results[0] }
        val networkResult = networkJob?.let { results[results.size - 1] }
        gpsResult ?: networkResult
    }

    /**
     * Bridge `LocationManager.getCurrentLocation` (API ≥ 30) /
     * `requestSingleUpdate` (older API levels) to a suspending call.
     * Resolves with the first fix from any provider; resolves with
     * `null` when cancelled or when the provider stack reports
     * unavailable.
     */
    /**
     * Bridge `LocationManager.getCurrentLocation` (API ≥ 30) /
     * `requestSingleUpdate` (older API levels) to a suspending call
     * for the given provider. Resolves with the first fix the
     * provider produces, or `null` when the provider reports
     * unavailable or the caller cancels (typically via the parent
     * `withTimeoutOrNull` budget set by [fetchLocation]).
     */
    @SuppressLint("MissingPermission")
    private suspend fun requestFromProvider(
        manager: LocationManager,
        provider: String,
    ): Location? {
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
     * post-load retry: when a capture has lat/lon but no place names
     * (Geocoder was rate-limited / offline at scan time), Details
     * re-runs this and persists the result. Returns a tight
     * `ResolvedPlace` triple — locality + sub-locality + formatted
     * address — so the caller doesn't need to peek into `Address`
     * directly.
     *
     * Returns `null` on any failure — same contract as the inner
     * overload, so callers can no-op cleanly.
     */
    suspend fun reverseGeocodeFull(
        context: Context,
        latitude: Double,
        longitude: Double,
    ): ResolvedPlace? {
        val loc = Location("manual").apply {
            this.latitude  = latitude
            this.longitude = longitude
        }
        return reverseGeocodeFull(context, loc)
    }

    /**
     * Reverse-geocode a `Location` into a `ResolvedPlace` (locality,
     * sub-locality, full formatted address). Uses the API ≥ 33
     * callback form when available so we don't block on the network
     * call from the IO dispatcher; falls back to the synchronous
     * overload on older devices.
     *
     * Returns `null` on any failure — empty results, IO error, or a
     * null address list — so the caller writes the coordinates
     * without place names rather than failing the whole capture.
     */
    private suspend fun reverseGeocodeFull(
        context: Context,
        location: Location,
    ): ResolvedPlace? {
        val geocoder = runCatching {
            Geocoder(context, Locale.getDefault())
        }.getOrNull() ?: return null

        val address: android.location.Address? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            withTimeoutOrNull(5_000L) {
                suspendCancellableCoroutine<android.location.Address?> { cont ->
                    geocoder.getFromLocation(
                        location.latitude,
                        location.longitude,
                        1,
                    ) { addresses ->
                        if (cont.isActive) cont.resume(addresses.firstOrNull())
                    }
                }
            }
        } else {
            runCatching {
                @Suppress("DEPRECATION")
                geocoder.getFromLocation(
                    location.latitude,
                    location.longitude,
                    1,
                )?.firstOrNull()
            }.getOrNull()
        }

        if (address == null) return null
        return ResolvedPlace(
            locality    = address.locality,
            subLocality = address.subLocality,
            address     = formatFullAddress(address),
        )
    }

    /**
     * Join every `Address.getAddressLine(i)` line with ", " to
     * produce a single-line full address. The geocoder fills
     * `getAddressLine(0)` for most regions already; the loop covers
     * the rare case where it splits across multiple lines (some
     * locales / providers). Returns `null` when no lines are
     * present so the Details card simply hides the row.
     */
    private fun formatFullAddress(address: android.location.Address): String? {
        val max = address.maxAddressLineIndex
        if (max < 0) return null
        val parts = (0..max)
            .mapNotNull { address.getAddressLine(it) }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        return parts.joinToString(separator = ", ")
    }
}
