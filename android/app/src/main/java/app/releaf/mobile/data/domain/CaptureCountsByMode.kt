/*
 * CaptureCountsByMode.kt
 * Aggregate capture counts across the whole library, grouped by
 * capture mode. Feeds the home-screen "trees saved" strip.
 *
 * Today only `notes` is populated (from live-page count); the other
 * four modes will come online when the design-system `captures`
 * table ships (see design-system/migrations/v1_initial.sql). The
 * data class intentionally exposes zero for absent modes so the UI
 * can consume the same shape before and after that migration.
 */

package app.releaf.mobile.data.domain

data class CaptureCountsByMode(
    val notes: Int = 0,
    val photos: Int = 0,
    val scans: Int = 0,
    val voice: Int = 0,
    val contacts: Int = 0,
) {
    /** Flat sum across all five capture modes. */
    val total: Int get() = notes + photos + scans + voice + contacts

    companion object {
        val EMPTY = CaptureCountsByMode()
    }
}
