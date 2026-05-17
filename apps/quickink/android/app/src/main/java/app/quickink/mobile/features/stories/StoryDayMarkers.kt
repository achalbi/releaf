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
     */
    fun derive(items: List<StoryItemEntity>): List<StoryDayMarker> {
        val out = mutableListOf<StoryDayMarker>()
        var lastKey: Pair<Triple<Int, Int, Int>, StoryDayBucket>? = null
        for (item in items) {
            val iso = item.occurredAt ?: item.createdAt
            val dt = parseIso(iso) ?: continue
            val date = Triple(dt.year, dt.monthValue, dt.dayOfMonth)
            val bucket = StoryDayBucket.of(dt.hour)
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
