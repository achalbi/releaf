package app.releaf.mobile.features.notepad.recents.model

import java.time.LocalDate
import java.time.LocalDateTime

/** Per-page user-defined tag. */
enum class Tag(val display: String) {
    HOME("Home"),
    WORK("Work"),
    RECIPES("Recipes"),
    PERSONAL("Personal"),
}

/** Top-of-screen filter chip selection. */
sealed class TagFilter {
    object All : TagFilter()
    data class Single(val tag: Tag) : TagFilter()
}

/**
 * Dominant capture flavour of a page — used as a hint by the
 * adapter and mock data, never as the source of truth. The page's
 * full content mix lives in [CaptureCounts] (photos, scans, voice,
 * todos, contacts, locations).
 *
 * Only attachment-backed flavours have a value here. Notes-only
 * pages and empty pages have a `null` `RecentsPage.type`.
 */
enum class CaptureType {
    PHOTO,
    VOICE,
}

/** How the page got into Releaf — useful for the "imported" amber accent. */
enum class PageSource {
    CAMERA,
    LIBRARY,
    SCAN,
    NATIVE,
}

/**
 * A single capture inside a [RecentsDay]. The hosting app may have a richer
 * model; this is the shape the Recents screen needs.
 */
data class RecentsPage(
    val id: String,
    val dayId: String,
    /** Dominant capture flavour. `null` for notes-only and empty
     *  pages — the multi-surface mix is what the UI actually reads
     *  via [captureCounts]. */
    val type: CaptureType?,
    val source: PageSource,
    val createdAt: LocalDateTime,
    /** Last-modified timestamp — drives the EarlierGrid sort so the
     *  most-recently-touched page lands first / in the tall slot.
     *  Defaults to [createdAt] for mock data; the adapter overrides
     *  with the underlying NotepadEntry's `updatedAt`. */
    val updatedAt: LocalDateTime = createdAt,
    val title: String,
    val description: String,
    val tags: List<Tag> = emptyList(),
    val mediaUri: String? = null,
    val durationSec: Int? = null,
    /** Per-page capture mix driving the hero pip row. Mock pages
     *  default to a single capture derived from [type]; the real
     *  adapter overrides with attachment-level counts so an entry
     *  with photo + voice + todos contributes a tick to each pip
     *  when that page is active. */
    val captureCounts: CaptureCounts = CaptureCounts.single(type),
)

/** True for pages that came in from outside the app (library / scan). */
fun RecentsPage.isImported(): Boolean = source == PageSource.LIBRARY || source == PageSource.SCAN

/**
 * Per-page capture tally — one field per surface where the page can
 * carry user content. Six map to the picker cells / [CaptureMode]
 * (minus `Overview`) plus a seventh for the page's own notes body:
 *
 *   • `photos`    — camera attachments
 *   • `scans`     — scanned-document attachments (kept distinct from
 *                   photos: a scanned receipt and a snapshot are two
 *                   different deliberate captures)
 *   • `voice`     — voice memos
 *   • `todos`     — items in the page's todo list
 *   • `contacts`  — saved contacts on the page
 *   • `locations` — pinned locations on the page
 *   • `notes`     — 0 or 1, signals whether the page's free-text
 *                   body is non-blank (the body is a single field on
 *                   the entry, not a list, so this is binary)
 *
 * The hero's pip row renders one pip per non-zero field; the
 * EarlierGrid card footer renders the sum.
 */
data class CaptureCounts(
    val photos:    Int = 0,
    val scans:     Int = 0,
    val voice:     Int = 0,
    val todos:     Int = 0,
    val contacts:  Int = 0,
    val locations: Int = 0,
    val notes:     Int = 0,
) {
    /** Sum across every surface — used by EarlierGrid cards to show
     *  a single "X captures" tally per page. */
    val total: Int get() = photos + scans + voice + todos + contacts + locations + notes

    /** Element-wise sum — used by the adapter to derive a day-level
     *  total from its pages' counts. */
    operator fun plus(other: CaptureCounts): CaptureCounts = CaptureCounts(
        photos    = photos    + other.photos,
        scans     = scans     + other.scans,
        voice     = voice     + other.voice,
        todos     = todos     + other.todos,
        contacts  = contacts  + other.contacts,
        locations = locations + other.locations,
        notes     = notes     + other.notes,
    )

    companion object {
        /** Counts for a page whose dominant flavour is [type]. Used
         *  by mock data where each page corresponds 1:1 to a single
         *  capture, and as the default per-page count on
         *  [RecentsPage] when the caller hasn't computed
         *  attachment-level counts. A `null` type — notes-only page
         *  — yields a single notes tick. */
        fun single(type: CaptureType?): CaptureCounts = when (type) {
            CaptureType.PHOTO -> CaptureCounts(photos = 1)
            CaptureType.VOICE -> CaptureCounts(voice = 1)
            null              -> CaptureCounts(notes  = 1)
        }

        /** Fallback derivation from a page list — sums each page's
         *  [RecentsPage.captureCounts]. */
        fun from(pages: List<RecentsPage>): CaptureCounts =
            pages.fold(CaptureCounts()) { acc, p -> acc + p.captureCounts }
    }
}

/** A single day's worth of pages. id is yyyy-MM-dd. */
data class RecentsDay(
    val id: String,
    val date: LocalDate,
    val theme: String,
    val pages: List<RecentsPage> = emptyList(),
    /** Capture counts for the pip row. Defaults to a derivation from
     *  [pages] for mock data; the adapter overrides with attachment-
     *  level counts so multi-attachment entries are tallied correctly. */
    val captureCounts: CaptureCounts = CaptureCounts.from(pages),
)

/** Single bar in the WeekPulse strip. */
data class RecentsWeekDay(
    val date: LocalDate,
    val pageCount: Int,
    val isToday: Boolean,
)

/** Aggregate counters shown in the StatsStrip. */
data class RecentsTotals(
    val dayStreak: Int,
    val bloomedThisMonth: Int,
    val daysInMonth: Int,
    val topTheme: Tag?,
)

/** The full payload the screen renders. */
data class RecentsDayStats(
    val today: RecentsDay?,
    val weekPulse: List<RecentsWeekDay>,
    val earlier: List<RecentsDay>,
    val totals: RecentsTotals,
)
