/*
 * IsoClock.kt
 *
 * One place to format an ISO-8601 UTC timestamp with millisecond precision —
 * the shape every `created_at` / `updated_at` column in the schema wants:
 *   2026-04-21T10:15:30.123Z
 *
 * Matches the SQL default `strftime('%Y-%m-%dT%H:%M:%fZ', 'now')`, which is
 * the fallback when a row is inserted without the app-layer setting the
 * column. Keeping a single helper avoids subtle drift (some call sites
 * emitting nanos, others seconds, etc.).
 */

package app.releaf.mobile.data.common

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField

object IsoClock {

    private val FORMATTER = DateTimeFormatterBuilder()
        .appendPattern("yyyy-MM-dd'T'HH:mm:ss")
        .appendFraction(ChronoField.MILLI_OF_SECOND, 3, 3, true)
        .appendLiteral('Z')
        .toFormatter()

    /** Current UTC timestamp, e.g. `2026-04-21T10:15:30.123Z`. */
    fun nowIso(): String = FORMATTER.format(Instant.now().atOffset(ZoneOffset.UTC))

    /** Today's date in the local zone as YYYY-MM-DD. */
    fun todayLocalDate(): String =
        DateTimeFormatter.ISO_LOCAL_DATE.format(
            Instant.now().atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        )
}
