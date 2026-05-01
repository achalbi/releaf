/*
 * RecentsAdapter.kt
 *
 * Bridge between the existing Notepad data layer (NotepadEntry +
 * NotepadScreenUiState) and the new Recents screen's snapshot model
 * (RecentsDayStats). The new screen is intentionally a pure view of a
 * snapshot — this object does the mapping so the screen never knows
 * about NotepadEntry or attachment JSON.
 *
 * The shape mismatch between the two models is the interesting bit:
 *
 *   • An existing NotepadEntry can carry multiple attachments
 *     (photo / scan / voice) plus notes, todos, contacts, and
 *     locations. Conceptually it is a *day-page container*.
 *   • A RecentsPage has an optional dominant `type` (PHOTO or VOICE
 *     when an attachment backs it; null for notes-only pages) and an
 *     optional attached media URI.
 *
 * We resolve this by mapping ONE NotepadEntry → ONE RecentsPage, with
 * the entry's dominant content driving the page type:
 *
 *   • any scan attachment        → PHOTO + PageSource.SCAN
 *   • any photo attachment       → PHOTO + PageSource.CAMERA
 *   • any voice attachment       → VOICE + PageSource.NATIVE
 *   • notes-only / empty         → null  + PageSource.NATIVE
 *
 * Per-page capture *counts* — distinct from the dominant `type`
 * above — are derived field-by-field from the entry's storage
 * columns: `attachments` (split into photos vs scans vs voice),
 * `todos`, `contacts`, `locations`. See [computeCaptureCounts].
 */

package app.releaf.mobile.features.notepad.recents.data

import app.releaf.mobile.data.notebook.parseAttachments
import app.releaf.mobile.data.notebook.parseContacts
import app.releaf.mobile.data.notebook.parseLocations
import app.releaf.mobile.data.notebook.parseTodos
import app.releaf.mobile.data.notepad.NotepadEntry
import app.releaf.mobile.features.notepad.NotepadScreenUiState
import app.releaf.mobile.features.notepad.recents.model.CaptureCounts
import app.releaf.mobile.features.notepad.recents.model.CaptureType
import app.releaf.mobile.features.notepad.recents.model.PageSource
import app.releaf.mobile.features.notepad.recents.model.RecentsDay
import app.releaf.mobile.features.notepad.recents.model.RecentsDayStats
import app.releaf.mobile.features.notepad.recents.model.RecentsPage
import app.releaf.mobile.features.notepad.recents.model.RecentsTotals
import app.releaf.mobile.features.notepad.recents.model.RecentsWeekDay
import app.releaf.mobile.features.notepad.recents.model.Tag
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeParseException

object RecentsAdapter {

    /** Build a snapshot from the live VM state for the given anchor day.
     *  Pass `today = LocalDate.now()` from the host so date math stays
     *  consistent with the rest of the app's "today" semantics. */
    fun fromState(state: NotepadScreenUiState, today: LocalDate): RecentsDayStats {
        val todayKey = today.toString()

        // --- TODAY ---
        val todayEntries = state.entriesByDate[todayKey].orEmpty()
        val todayDay = if (todayEntries.isEmpty()) {
            // Brief: hero never dead-ends. An empty today still produces
            // a RecentsDay so the screen renders the new-entry slot.
            RecentsDay(id = todayKey, date = today, theme = "", pages = emptyList())
        } else {
            buildRecentsDay(todayEntries, today)
        }

        // --- THIS WEEK (7 cells, oldest → newest, ending today) ---
        val weekPulse = (0 until 7).map { i ->
            val d = today.minusDays((6 - i).toLong())
            val count = state.entriesByDate[d.toString()]?.size ?: 0
            RecentsWeekDay(date = d, pageCount = count, isToday = d == today)
        }

        // --- EARLIER ---
        val earlier = state.recentDays
            .asSequence()
            .filter { it.date != today }
            .map { dc -> buildRecentsDay(dc.entries, dc.date) }
            .toList()

        // --- TOTALS ---
        val month       = YearMonth.from(today)
        val daysInMonth = month.lengthOfMonth()
        val bloomedThisMonth = state.entriesByDate.entries
            .count { (key, list) ->
                if (list.isEmpty()) return@count false
                val d = runCatching { LocalDate.parse(key) }.getOrNull() ?: return@count false
                d.month == today.month && d.year == today.year
            }
        val dayStreak = computeStreak(state.entriesByDate, today)
        val topTheme  = computeTopTheme(state.entriesByDate, month)
        val totals    = RecentsTotals(
            dayStreak        = dayStreak,
            bloomedThisMonth = bloomedThisMonth,
            daysInMonth      = daysInMonth,
            topTheme         = topTheme,
        )

        return RecentsDayStats(
            today     = todayDay,
            weekPulse = weekPulse,
            earlier   = earlier,
            totals    = totals,
        )
    }

    /** Wrap [fromState] in a [DayStatsRepo] for screens that prefer the
     *  repo seam over a snapshot parameter. */
    fun asRepo(state: NotepadScreenUiState, today: LocalDate): DayStatsRepo =
        object : DayStatsRepo {
            private val cached = fromState(state, today)
            override fun getDayStats(): RecentsDayStats = cached
        }

    // ---- Internals ----

    private fun buildRecentsDay(entries: List<NotepadEntry>, date: LocalDate): RecentsDay {
        val dateStr = date.toString()
        // Theme is the most-recently-updated entry's title. Falls back
        // to the day's first non-blank title, then to an empty string
        // (which the hero renders as "today").
        val theme = entries
            .maxByOrNull { it.updatedAt }
            ?.title
            ?.takeIf { it.isNotBlank() }
            ?: entries.firstNotNullOfOrNull { it.title?.takeIf { t -> t.isNotBlank() } }
            ?: ""
        // Sort pages chronologically so the carousel and timeline read
        // left-to-right by clock time. The existing entriesByDate is
        // already sorted by createdAt — but be defensive in case a
        // future change reorders by something else.
        val pages = entries
            .sortedBy { it.createdAt }
            .map { entry ->
                buildRecentsPage(
                    entry         = entry,
                    dayId         = dateStr,
                    captureCounts = computeCaptureCounts(entry),
                )
            }
        // Day-level captureCounts is the element-wise sum of each
        // page's per-page counts — the hero pip row reads from the
        // *active page's* counts (per-page), but [RecentsDay] keeps
        // the day total available for any future surface that needs
        // it.
        val dayCounts = pages.fold(CaptureCounts()) { acc, p ->
            acc + p.captureCounts
        }
        return RecentsDay(
            id            = dateStr,
            date          = date,
            theme         = theme,
            pages         = pages,
            captureCounts = dayCounts,
        )
    }

    /** Per-entry capture counts — one tally per capture surface plus
     *  a 0/1 `notes` tick for whether the page's free-text body is
     *  non-blank. The hero pip row renders one pip per non-zero
     *  field; the EarlierGrid card footer renders the sum. */
    private fun computeCaptureCounts(entry: NotepadEntry): CaptureCounts {
        val attachments = runCatching { entry.attachments.parseAttachments() }
            .getOrDefault(emptyList())
        val todoCount = runCatching { entry.todos.parseTodos() }
            .getOrDefault(emptyList()).size
        val contactCount = runCatching { entry.contacts.parseContacts() }
            .getOrDefault(emptyList()).size
        val locationCount = runCatching { entry.locations.parseLocations() }
            .getOrDefault(emptyList()).size
        return CaptureCounts(
            photos    = attachments.count { it.type == "photo" },
            scans     = attachments.count { it.type == "scan" },
            voice     = attachments.count { it.type == "voice" },
            todos     = todoCount,
            contacts  = contactCount,
            locations = locationCount,
            notes     = if (entry.notes.isNotBlank()) 1 else 0,
        )
    }

    private fun buildRecentsPage(
        entry: NotepadEntry,
        dayId: String,
        captureCounts: CaptureCounts,
    ): RecentsPage {
        val attachments = runCatching { entry.attachments.parseAttachments() }
            .getOrDefault(emptyList())
        val firstScan  = attachments.firstOrNull { it.type == "scan" }
        val firstPhoto = attachments.firstOrNull { it.type == "photo" }
        val firstVoice = attachments.firstOrNull { it.type == "voice" }

        // Notes-only and empty entries get `type = null` — they have
        // no attachment-backed dominant flavour. Their content mix
        // (todos, contacts, locations, body text) lives in
        // `captureCounts` and the page's body fields.
        val type: CaptureType?
        val source: PageSource
        when {
            firstScan  != null -> { type = CaptureType.PHOTO; source = PageSource.SCAN }
            firstPhoto != null -> { type = CaptureType.PHOTO; source = PageSource.CAMERA }
            firstVoice != null -> { type = CaptureType.VOICE; source = PageSource.NATIVE }
            else               -> { type = null;              source = PageSource.NATIVE }
        }

        val mediaUri    = firstScan?.uri ?: firstPhoto?.uri
        val durationSec = firstVoice?.durationMs?.let { (it / 1000L).toInt() }
        val tags        = listOfNotNull(tagFor(entry.category))

        return RecentsPage(
            id            = entry.id,
            dayId         = dayId,
            type          = type,
            source        = source,
            createdAt     = parseLocalDateTime(entry.createdAt),
            updatedAt     = parseLocalDateTime(entry.updatedAt),
            title         = entry.title?.takeIf { it.isNotBlank() } ?: "Untitled",
            description   = entry.description.orEmpty(),
            tags          = tags,
            mediaUri      = mediaUri,
            durationSec   = durationSec,
            captureCounts = captureCounts,
        )
    }

    /** Map an existing category string to one of the four Tag values
     *  the Recents screen knows about. Categories with no Tag analogue
     *  (Health/Travel/Ideas + arbitrary customs) drop quietly. */
    private fun tagFor(category: String?): Tag? {
        val name = category?.trim()?.lowercase() ?: return null
        return when (name) {
            "home"     -> Tag.HOME
            "work"     -> Tag.WORK
            "personal" -> Tag.PERSONAL
            "recipes"  -> Tag.RECIPES
            else       -> null
        }
    }

    /** Walk back from [today] until we hit a date with no entries. */
    private fun computeStreak(
        entriesByDate: Map<String, List<NotepadEntry>>,
        today: LocalDate,
    ): Int {
        var streak = 0
        var d = today
        while (true) {
            val list = entriesByDate[d.toString()].orEmpty()
            if (list.isEmpty()) break
            streak++
            d = d.minusDays(1)
        }
        return streak
    }

    /** Most-frequent Tag among entries created in [month]. */
    private fun computeTopTheme(
        entriesByDate: Map<String, List<NotepadEntry>>,
        month: YearMonth,
    ): Tag? {
        val counts = mutableMapOf<Tag, Int>()
        entriesByDate.forEach { (key, list) ->
            val d = runCatching { LocalDate.parse(key) }.getOrNull() ?: return@forEach
            if (d.month != month.month || d.year != month.year) return@forEach
            list.forEach { entry ->
                tagFor(entry.category)?.let { tag ->
                    counts[tag] = (counts[tag] ?: 0) + 1
                }
            }
        }
        return counts.maxByOrNull { it.value }?.key
    }

    /** ISO-8601 UTC timestamp → LocalDateTime in the device zone so the
     *  hero header prints "8:15 PM" rather than "01:15 PM" UTC. Falls
     *  back to LocalDateTime.MIN on parse failure (defensive — the
     *  data layer always writes well-formed timestamps). */
    private fun parseLocalDateTime(iso: String): LocalDateTime =
        try {
            Instant.parse(iso).atZone(ZoneId.systemDefault()).toLocalDateTime()
        } catch (_: DateTimeParseException) {
            LocalDateTime.MIN
        }
}
