/*
 * Models.kt
 * Domain model — the shape of a Releaf notebook in memory.
 *
 * Persistence (Drive) formats are NOT on these types; DriveRepository maps
 * to/from the JSON payloads described in docs/DRIVE_SCHEMA.md.
 */

package app.releaf.mobile.data.domain

import java.time.Instant

// Note: `CaptureMode` now lives in ui.components. It's fundamentally a UI
// concept (the 7 tappable rows in the capture sheet / tab bar) and no
// domain type references it.

/** Lifecycle state shown on the variant-1 shelves cards. */
enum class NotebookStatus { Active, Paused, Archived, Shared }

/**
 * Top-level container in the Shelf → Book → Chapter → Page
 * hierarchy. Every book belongs to exactly one shelf; the default
 * shelf is `"shelf-general"` and is seeded by migration on upgrade.
 */
data class Shelf(
    val id: String,
    val name: String,
    val colorHex: String? = null,
    val position: Int = 0,
    val updatedAt: Instant = Instant.now(),
)

/**
 * Groups multiple [Notebook] rows into "volumes of the same book".
 * When a book has a single volume this type isn't materialized in
 * the UI — the notebook renders its title on its own.
 */
data class BookSeries(
    val id: String,
    val shelfId: String,
    val name: String,
    val updatedAt: Instant = Instant.now(),
)

data class Notebook(
    val id: String,
    val title: String,
    val description: String? = null,
    val colorToken: String? = null,
    val position: Int = 0,
    val archivedAt: Instant? = null,
    val updatedAt: Instant = Instant.now(),
    val chapterCount: Int = 0,
    val pageCount: Int = 0,
    /** Shelf grouping (e.g. "GARDEN"). Used by variant-1 shelves UI. */
    val shelfName: String? = null,
    /** Volume number (e.g. 2). Used by variant-1 shelves UI. */
    val volumeNumber: Int? = null,
    /** Lifecycle status. When null, derived from [archivedAt]. */
    val status: NotebookStatus? = null,
    /** Opaque key for the variant-1 hero-card icon. */
    val iconKey: String? = null,

    /**
     * Shelf this book belongs to (FK to [Shelf.id]). Required in
     * the schema; defaulted to the General shelf here so call
     * sites that don't know the shelf yet still compile.
     */
    val shelfId: String = "shelf-general",
    /** Series this book is a volume of. Null = single-volume book. */
    val seriesId: String? = null,
    /** 1 for the only (or first) volume; 2+ for subsequent volumes. */
    val seriesVolumeNumber: Int = 1,
    /** Per-volume label (e.g. "2026"). Null → UI composes
     *  "<series> vol <n>". */
    val volumeLabel: String? = null,
) {
    val isArchived: Boolean get() = archivedAt != null

    /** Explicit [status] if set, else derived from [archivedAt]. */
    val resolvedStatus: NotebookStatus
        get() = status ?: if (isArchived) NotebookStatus.Archived else NotebookStatus.Active
}

data class Chapter(
    val id: String,
    val notebookId: String,
    val title: String,
    val position: Int = 0,
    val updatedAt: Instant = Instant.now(),
    val pages: List<PageSummary> = emptyList(),
    /** Soft-delete timestamp. Non-null means the chapter is in
     *  archive — the list filters it out by default; the archive
     *  view surfaces it with a Restore affordance. */
    val archivedAt: Instant? = null,
) {
    val isArchived: Boolean get() = archivedAt != null
}

data class PageSummary(
    val id: String,
    val title: String,
    val capturedOn: String? = null,
    val updatedAt: Instant = Instant.now(),
    val counts: PageCounts = PageCounts(),
    /** Free-form tags shown as pills on variant-1 page views. */
    val tags: List<String> = emptyList(),
)

data class PageCounts(
    val photos: Int = 0,
    val voiceNotes: Int = 0,
    val todoItems: Int = 0,
    val scannedDocuments: Int = 0,
    val contacts: Int = 0,
    val locations: Int = 0,
) {
    val total: Int get() = photos + voiceNotes + todoItems + scannedDocuments + contacts + locations
}

/**
 * Full page payload — the thing the page detail screen displays.
 * Mirrors the `page.json` body described in docs/DRIVE_SCHEMA.md.
 */
data class Page(
    val id: String,
    val notebookId: String,
    val chapterId: String,
    val title: String,
    val capturedOn: String? = null,
    val updatedAt: Instant = Instant.now(),
    val notes: List<Note> = emptyList(),
    val photos: List<Photo> = emptyList(),
    val voiceNotes: List<VoiceNote> = emptyList(),
    val todoItems: List<TodoItem> = emptyList(),
    val scannedDocuments: List<ScannedDocument> = emptyList(),
    val contacts: List<Contact> = emptyList(),
    val locations: List<LocationPin> = emptyList(),
    /** Free-form tags shown as pills on variant-1 page views. */
    val tags: List<String> = emptyList(),
    /** Soft-delete timestamp. Non-null means the page is in archive
     *  — it still renders, but the page detail surfaces an
     *  ArchivedBanner with a Restore action. */
    val archivedAt: Instant? = null,
) {
    val isArchived: Boolean get() = archivedAt != null

    val counts: PageCounts get() = PageCounts(
        photos = photos.size,
        voiceNotes = voiceNotes.size,
        todoItems = todoItems.size,
        scannedDocuments = scannedDocuments.size,
        contacts = contacts.size,
        locations = locations.size,
    )
}

data class Note(
    val id: String,
    val body: String,
    val createdAt: Instant = Instant.now(),
)

/** A photo reference. Actual bytes live on Drive under `photos/<driveFileId>`. */
data class Photo(
    val id: String,
    val driveFileId: String? = null,
    val caption: String? = null,
    val capturedAt: Instant = Instant.now(),
    val width: Int? = null,
    val height: Int? = null,
)

data class VoiceNote(
    val id: String,
    val driveFileId: String? = null,
    val durationMs: Long,
    val recordedAt: Instant = Instant.now(),
    val transcription: String? = null,
)

data class TodoItem(
    val id: String,
    val body: String,
    val done: Boolean = false,
    val position: Int = 0,
)

data class ScannedDocument(
    val id: String,
    val driveFileId: String? = null,
    val title: String,
    val pageCount: Int = 1,
    val scannedAt: Instant = Instant.now(),
)

data class Contact(
    val id: String,
    val name: String,
    val phone: String? = null,
    val email: String? = null,
    val notes: String? = null,
)

data class LocationPin(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val capturedAt: Instant = Instant.now(),
    val notes: String? = null,
)

/**
 * A reusable page seed. Carries a small block of content that
 * `applyTemplate(toPageId, templateId)` writes onto an existing
 * page. Templates are user-curated *and* app-seeded; the seed set
 * in `FakeDriveRepository` covers the most common shapes
 * (daily walk, recipe, meeting notes, field journal, morning pages).
 *
 * `pre*` fields are content that the apply step prepends/concats
 * to whatever the page already contains. Empty lists mean
 * "leave that section alone" — applying a template never deletes
 * existing captures.
 */
data class PageTemplate(
    val id: String,
    val title: String,
    val description: String,
    /** Lookup key into ShelfTheme.iconSystemName so the picker row
     *  can render a small icon matching the template's shape. */
    val iconKey: String? = null,
    /** Pre-filled note bodies — each becomes one Note when applied. */
    val preNotes: List<String> = emptyList(),
    /** Pre-filled todo bodies — each becomes one TodoItem when applied. */
    val preTodos: List<String> = emptyList(),
) {
    /** Lightweight summary of what the template touches. */
    val summary: String
        get() {
            val parts = buildList {
                if (preNotes.isNotEmpty()) add("${preNotes.size} note${if (preNotes.size == 1) "" else "s"}")
                if (preTodos.isNotEmpty()) add("${preTodos.size} to-do${if (preTodos.size == 1) "" else "s"}")
            }
            return if (parts.isEmpty()) "blank scaffold" else parts.joinToString(" · ")
        }
}
