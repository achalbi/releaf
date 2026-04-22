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
) {
    val isArchived: Boolean get() = archivedAt != null
}

data class Chapter(
    val id: String,
    val notebookId: String,
    val title: String,
    val position: Int = 0,
    val updatedAt: Instant = Instant.now(),
    val pages: List<PageSummary> = emptyList(),
)

data class PageSummary(
    val id: String,
    val title: String,
    val capturedOn: String? = null,
    val updatedAt: Instant = Instant.now(),
    val counts: PageCounts = PageCounts(),
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
) {
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
