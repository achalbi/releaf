/*
 * RelativeTime.kt
 *
 * Cheap ISO-8601 → "n days ago" / "Apr 5, 2026" helpers used by the
 * notebook / chapter / page list rows. Input is whatever `IsoClock.nowIso()`
 * writes (yyyy-MM-dd'T'HH:mm:ss.SSSZ); output is display-ready text.
 *
 * Thresholds are intentionally coarse — we'd rather say "2 days ago" than
 * "48 hours" on a notebook list. If the row wants a precise time, the detail
 * screen can show the absolute date.
 */

package app.releaf.mobile.ui.components

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

private val LOCAL_ZONE: ZoneId = ZoneId.systemDefault()
private val ABSOLUTE_DATE = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault())

fun parseIsoOrNull(iso: String?): Instant? =
    runCatching { iso?.let(Instant::parse) }.getOrNull()

/** Coarse "x ago" label. Falls back to the absolute date past ~30 days. */
fun relativeTimeAgo(iso: String?, now: Instant = Instant.now()): String {
    val then = parseIsoOrNull(iso) ?: return ""
    val seconds = ChronoUnit.SECONDS.between(then, now).coerceAtLeast(0)
    return when {
        seconds < 60             -> "just now"
        seconds < 60 * 60        -> "${seconds / 60} min ago"
        seconds < 60 * 60 * 24   -> "${seconds / 3600} hr ago"
        seconds < 60 * 60 * 24 * 2 -> "yesterday"
        seconds < 60 * 60 * 24 * 30 -> "${seconds / (60 * 60 * 24)} days ago"
        else -> absoluteDate(iso)
    }
}

/** "Apr 5, 2026" — used for the "Created" stat cell. */
fun absoluteDate(iso: String?): String {
    val then = parseIsoOrNull(iso) ?: return ""
    return ABSOLUTE_DATE.format(then.atZone(LOCAL_ZONE).toLocalDate())
}
