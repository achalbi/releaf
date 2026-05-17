/*
 * StoryDayMarkers.kt
 *
 * Mirror of iOS `StoryDayMarkers.swift`. See that file's header for
 * the derivation rule + time-of-day bucket boundaries.
 */

package app.quickink.mobile.features.stories

import app.quickink.mobile.data.storyitem.StoryItemEntity
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class StoryDayMarker(
    /** The item this marker should render *before*. */
    val precedingItemId: String,
    /** All-caps label with em-dash framing, e.g. `— MAY 4 · EVENING —`. */
    val label: String,
)

enum class StoryDayBucket(val display: String) {
    MORNING("MORNING"),
    AFTERNOON("AFTERNOON"),
    EVENING("EVENING"),
    NIGHT("NIGHT");

    companion object {
        fun of(hour: Int): StoryDayBucket = when (hour) {
            in 5..10  -> MORNING
            in 11..16 -> AFTERNOON
            in 17..20 -> EVENING
            else      -> NIGHT
        }
    }
}

object StoryDayMarkers {

    private val MONTH_NAMES = listOf(
        "JAN", "FEB", "MAR", "APR", "MAY", "JUN",
        "JUL", "AUG", "SEP", "OCT", "NOV", "DEC",
    )

    /**
     * Compute markers for a story's items. Returns markers in the
     * same order the items appear; the very first item always gets
     * one (the reader needs an opener even for single-day stories).
     *
     * Dates + hours read in the user's CURRENT system timezone (the
     * spec's "local time of the effective date"). An 8 pm capture
     * in Tokyo and an 8 pm capture in New York both fall in their
     * respective EVENING buckets, even when viewed from a third
     * timezone. Per-capture timezone in the schema is v1.1; until
     * then, the viewing device's zone is the best we have.
     */
    fun derive(items: List<StoryItemEntity>): List<StoryDayMarker> {
        val out = mutableListOf<StoryDayMarker>()
        var lastKey: Pair<Triple<Int, Int, Int>, StoryDayBucket>? = null
        val localZone = java.time.ZoneId.systemDefault()
        for (item in items) {
            val iso = item.occurredAt ?: item.createdAt
            val parsed = parseIso(iso) ?: continue
            val local = parsed.atZoneSameInstant(localZone)
            val date = Triple(local.year, local.monthValue, local.dayOfMonth)
            val bucket = StoryDayBucket.of(local.hour)
            val key = date to bucket
            if (key != lastKey) {
                out += StoryDayMarker(
                    precedingItemId = item.id,
                    label           = label(date, bucket),
                )
                lastKey = key
            }
        }
        return out
    }

    private fun label(date: Triple<Int, Int, Int>, bucket: StoryDayBucket): String {
        val month = MONTH_NAMES.getOrNull(date.second - 1) ?: "—"
        return "— $month ${date.third} · ${bucket.display} —"
    }

    /**
     * Parse the schema's ISO-8601 timestamps. `IsoClock.nowIso()`
     * emits fractional seconds; accept either shape via
     * `OffsetDateTime.parse` (which handles both with `ISO_OFFSET_DATE_TIME`).
     */
    private fun parseIso(iso: String): OffsetDateTime? = runCatching {
        OffsetDateTime.parse(iso)
    }.getOrNull()
}
