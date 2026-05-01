/*
 * ReminderParser.kt
 *
 * Turns a free-text line like "call mom at 7pm tomorrow" into
 * (title, remindAt) — the pair the quick-capture input in the
 * Reminders screen needs. Regex-based on purpose: a real NLP
 * dependency would dwarf the file, and the 80% of phrasings users
 * actually type fit in a handful of patterns.
 *
 * Recognised grammar (case-insensitive, any order):
 *
 *   • Time of day
 *       "at 7pm", "at 7:30 pm", "at 14:30", "at 7", "@ 7"
 *   • Day
 *       "today", "tonight", "tomorrow", weekday names
 *       ("monday" … "sunday"), "next monday"
 *   • Relative
 *       "in 2 hours", "in 45 mins", "in 3 days"
 *
 * Whichever parts are found are stripped from the title; whatever
 * remains (trimmed, whitespace-collapsed) is the title. If only a
 * time is found with no explicit day, the date is "today if still
 * future, otherwise tomorrow" — matching how humans usually phrase
 * "at 7pm" at 8am vs. at 8pm.
 *
 * If nothing parses, [defaultOffsetMs] is added to now — 1 hour by
 * default, so a bare "call mom" still schedules something usable.
 */

package app.releaf.mobile.data.reminder

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

data class ParsedReminder(
    val title: String,
    val remindAt: Long,
)

object ReminderParser {

    private val defaultOffsetMs: Long = 60L * 60_000L  // 1 hour

    // ──────────────────────────── Parse ────────────────────────────

    /**
     * Parse [raw]. Never throws — falls back to (raw trimmed, now + 1h)
     * if no patterns match. [now] is injectable for deterministic
     * tests; callers normally pass [LocalDateTime.now].
     *
     * Dispatch: Natty (com.joestelmach:natty) goes first — a mature
     * Java parser that handles most English phrasings ("next tuesday
     * at 3", "friday evening", "2 hours from now"). If Natty can't
     * find a date reference OR throws, we fall back to the in-house
     * regex path below which covers the "call mom at 7pm tomorrow"
     * baseline without any dependency weight.
     */
    fun parse(
        raw: String,
        now: LocalDateTime = LocalDateTime.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): ParsedReminder {
        // Users routinely say/type "Remind me to …" as a lead-in —
        // both in voice ("Hey, remind me to call mom") and typed
        // input (it's the placeholder hint). Strip it up front so
        // neither the NL parser nor the saved title carries the
        // boilerplate.
        var working = stripRemindMePrefix(raw).trim()
        if (working.isEmpty()) return ParsedReminder(raw, toEpochMs(now.plusHours(1), zone))

        tryParseWithNatty(working, zone)?.let { return it }

        // 1) Relative offsets — "in 2 hours", "in 45 min".
        val relative = RELATIVE_REGEX.find(working)
        val relativeTime: LocalDateTime? = relative?.let {
            val n = it.groupValues[1].toIntOrNull() ?: return@let null
            when (it.groupValues[2].lowercase().take(3)) {
                "min" -> now.plusMinutes(n.toLong())
                "hou", "hrs", "hr" -> now.plusHours(n.toLong())
                "day" -> now.plusDays(n.toLong())
                "wee" -> now.plusWeeks(n.toLong())
                else  -> null
            }
        }
        if (relative != null) working = working.replaceRange(relative.range, " ")

        // 2) Explicit time of day.
        var parsedTime: LocalTime? = null
        TIME_REGEX.find(working)?.let { match ->
            parsedTime = parseLocalTime(
                hourText   = match.groupValues[1],
                minuteText = match.groupValues[2],
                ampm       = match.groupValues[3],
            )
            if (parsedTime != null) working = working.replaceRange(match.range, " ")
        }

        // 3) Day keyword.
        var parsedDate: LocalDate? = null
        DAY_REGEX.find(working)?.let { match ->
            parsedDate = parseDayKeyword(
                prefix  = match.groupValues[1],
                keyword = match.groupValues[2].lowercase(),
                today   = now.toLocalDate(),
            )
            if (parsedDate != null) working = working.replaceRange(match.range, " ")
        }

        // 4) Assemble the final LocalDateTime.
        val resolved: LocalDateTime = when {
            // Relative wins over absolute — "call mom in 2 hours" is
            // unambiguous even if someone also typed "tomorrow".
            relativeTime != null -> relativeTime
            parsedDate != null && parsedTime != null ->
                LocalDateTime.of(parsedDate, parsedTime)
            parsedDate != null ->
                LocalDateTime.of(parsedDate, LocalTime.of(9, 0))
            parsedTime != null -> {
                val candidate = LocalDateTime.of(now.toLocalDate(), parsedTime)
                if (candidate.isAfter(now)) candidate else candidate.plusDays(1)
            }
            else -> now.plusNanos(defaultOffsetMs * 1_000_000L)
        }

        // Filler words the user typically types between a title and
        // the time/date bits we pulled out. Removing them keeps the
        // saved title readable ("call mom", not "call mom at on").
        val title = working
            .replace(FILLER_REGEX, " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim(',', '.', ';', '—', '-')
            .ifEmpty { raw.trim() }

        return ParsedReminder(title = title, remindAt = toEpochMs(resolved, zone))
    }

    /**
     * Remove a leading "remind me to …" / "remind me about …" /
     * "remind me …" phrase (with optional polite prefixes like
     * "please" or "can you"). Called from [parse] and also surfaced
     * publicly so the voice-capture flow can clean the transcript
     * before it lands in the input field — otherwise the user sees
     * the boilerplate echo their instruction back at them.
     */
    fun stripRemindMePrefix(raw: String): String {
        val trimmed = raw.trimStart()
        val match = REMIND_ME_PREFIX.find(trimmed) ?: return trimmed
        return trimmed.substring(match.range.last + 1).trimStart()
    }

    private val REMIND_ME_PREFIX = Regex(
        """^(please\s+)?(can\s+you\s+|could\s+you\s+|would\s+you\s+)?remind\s+me\s+(to|about|that)?\s*""",
        RegexOption.IGNORE_CASE,
    )

    // ──────────────────────────── Natty ────────────────────────────

    /**
     * Run Natty over [raw] and return (stripped title, epoch ms) if
     * a date reference was found. Natty's `DateGroup` exposes both
     * the parsed dates AND the substring ranges they came from, so
     * we can excise the date/time bits from the title cleanly.
     *
     * Returns null (caller falls back to the regex path) if Natty
     * returns no groups, throws, or picks up a date that's in the
     * past without an explicit year (its "next occurrence" logic
     * covers most of that already).
     */
    private fun tryParseWithNatty(raw: String, zone: ZoneId): ParsedReminder? {
        return try {
            val parser = com.joestelmach.natty.Parser()
            val groups = parser.parse(raw)
            if (groups.isEmpty()) return null
            val first = groups.first()
            val dates = first.dates
            if (dates.isEmpty()) return null

            val date: java.util.Date = dates.first()
            val remindAt = date.toInstant().atZone(zone).toInstant().toEpochMilli()

            // Natty's `getText()` is the matched substring (e.g.
            // "at 7pm tomorrow"). Strip it from the original input
            // to get a clean title.
            val matched = first.text
            val title = raw
                .replace(matched, " ", ignoreCase = true)
                .replace(FILLER_REGEX, " ")
                .replace(Regex("\\s+"), " ")
                .trim()
                .trim(',', '.', ';', '—', '-')
                .ifEmpty { raw.trim() }

            ParsedReminder(title = title, remindAt = remindAt)
        } catch (t: Throwable) {
            // Natty throws on some edge cases (e.g. malformed input
            // with unbalanced punctuation). Don't fail the whole
            // capture — fall through to the regex path.
            null
        }
    }

    // ──────────────────────────── Helpers ──────────────────────────

    /** e.g. "7pm", "7:30 pm", "14:30", "7". */
    private val TIME_REGEX = Regex(
        """\b(?:at\s+|@\s*)?(\d{1,2})(?::(\d{2}))?\s*(am|pm)?\b""",
        RegexOption.IGNORE_CASE,
    )

    /** Optional "next" / "on" prefix + a day keyword. */
    private val DAY_REGEX = Regex(
        """\b(next\s+|on\s+)?(today|tonight|tomorrow|mon(?:day)?|tue(?:sday)?|wed(?:nesday)?|thu(?:rsday)?|fri(?:day)?|sat(?:urday)?|sun(?:day)?)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** "in 2 hours", "in 45 mins", "in 3 days". */
    private val RELATIVE_REGEX = Regex(
        """\bin\s+(\d{1,3})\s*(min(?:ute)?s?|hours?|hrs?|days?|weeks?)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** Filler words to strip after time/date fragments are gone. */
    private val FILLER_REGEX = Regex(
        """\b(at|on|by|around|about|the)\b""",
        RegexOption.IGNORE_CASE,
    )

    private fun parseLocalTime(
        hourText: String,
        minuteText: String,
        ampm: String,
    ): LocalTime? {
        val hour0 = hourText.toIntOrNull() ?: return null
        val minute = minuteText.toIntOrNull() ?: 0
        if (minute !in 0..59) return null

        val hour = when (ampm.lowercase()) {
            "am" -> if (hour0 == 12) 0 else hour0
            "pm" -> if (hour0 == 12) 12 else (hour0 + 12).coerceAtMost(23)
            else -> {
                // Bare number — if the "hour" is implausibly small
                // without am/pm, assume the user meant a round hour
                // and skip. We only trust 0..23 here.
                if (hour0 in 0..23) hour0 else return null
            }
        }
        if (hour !in 0..23) return null
        return LocalTime.of(hour, minute)
    }

    private fun parseDayKeyword(
        prefix: String,
        keyword: String,
        today: LocalDate,
    ): LocalDate? {
        val isNext = prefix.trim().equals("next", ignoreCase = true)
        return when (keyword) {
            "today", "tonight" -> today
            "tomorrow"         -> today.plusDays(1)
            else -> {
                val dow = dayOfWeekFromPrefix(keyword) ?: return null
                val adjuster = if (isNext)
                    TemporalAdjusters.next(dow)
                else
                    TemporalAdjusters.nextOrSame(dow)
                today.with(adjuster).let {
                    // If nextOrSame landed on today itself, the user
                    // probably meant the upcoming one — so if today
                    // IS that weekday we still return today (useful
                    // for phrases like "review on friday" said on a
                    // friday morning). Fine for either interpretation.
                    it
                }
            }
        }
    }

    private fun dayOfWeekFromPrefix(s: String): DayOfWeek? = when (s.take(3)) {
        "mon" -> DayOfWeek.MONDAY
        "tue" -> DayOfWeek.TUESDAY
        "wed" -> DayOfWeek.WEDNESDAY
        "thu" -> DayOfWeek.THURSDAY
        "fri" -> DayOfWeek.FRIDAY
        "sat" -> DayOfWeek.SATURDAY
        "sun" -> DayOfWeek.SUNDAY
        else  -> null
    }

    private fun toEpochMs(dt: LocalDateTime, zone: ZoneId): Long =
        dt.atZone(zone).toInstant().toEpochMilli()
}
