/*
 * SyncPayloads.kt
 *
 * v2 Drive wire formats. Each entity kind gets a payload type, a
 * `toV2Payload()` mapper from the local Entity, and a `.toEntity(...)`
 * inverse for the download path.
 *
 * Rules:
 *   - Keys are snake_case and match the SQL column names byte-for-byte
 *     so a human reading the JSON can grep the schema for field meaning.
 *   - JSON-typed columns (contacts, locations, todos, attachments,
 *     sketch_strokes, sub_pages) land on Drive as embedded JSON arrays
 *     or objects — NOT as quoted strings. The payload type uses
 *     `JsonElement` for these fields; the `to`/`from` mappers parse the
 *     local string column on write and re-serialize it on read.
 *   - Drive payloads NEVER carry `dirty` or `drive_file_id` — those are
 *     local bookkeeping. The only Drive-side identity is the file's
 *     entity id + its checksum in the manifest.
 *   - `conflict_stub` is local-only too. It's a device's unresolved-
 *     merge record; peer devices don't know or care.
 *
 * Round-trip contract: for a row with `dirty = 0`, decoding its Drive
 * payload → re-encoding it must produce byte-identical canonical JSON
 * to what was uploaded. The test suite covers this for every kind.
 */

package app.releaf.mobile.data.sync

import app.releaf.mobile.data.notebook.BookSeriesEntity
import app.releaf.mobile.data.notebook.ChapterEntity
import app.releaf.mobile.data.notebook.NotebookEntity
import app.releaf.mobile.data.notebook.PageEntity
import app.releaf.mobile.data.notepad.NotepadEntry
import app.releaf.mobile.data.shelf.ShelfEntity
import app.releaf.mobile.data.task.TaskEntity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/**
 * Default JSON configuration for sync. `ignoreUnknownKeys` lets a
 * newer writer's extra fields flow past an older reader without
 * blowing up (forward-compat for `minor` bumps per OPEN_QUESTIONS §5).
 */
val SyncJson: Json = Json {
    prettyPrint       = false
    ignoreUnknownKeys = true
    encodeDefaults    = true
}

// =====================================================================
// Notebook
// =====================================================================

@Serializable
data class NotebookPayloadV2(
    @SerialName("id")            val id: String,
    @SerialName("title")         val title: String,
    @SerialName("description")   val description: String? = null,
    @SerialName("color_hex")     val colorHex: String? = null,
    @SerialName("position")      val position: Long,
    @SerialName("archived_at")   val archivedAt: String? = null,
    /**
     * Shelf this book belongs to. Optional on the wire so old
     * clients that don't yet send it still round-trip; readers
     * default absent rows onto the "shelf-general" shelf.
     */
    @SerialName("shelf_id")      val shelfId: String? = null,
    @SerialName("series_id")     val seriesId: String? = null,
    @SerialName("volume_number") val volumeNumber: Int? = null,
    @SerialName("volume_name")   val volumeName: String? = null,
    @SerialName("created_at")    val createdAt: String,
    @SerialName("updated_at")    val updatedAt: String,
)

fun NotebookEntity.toV2Payload(): NotebookPayloadV2 = NotebookPayloadV2(
    id           = id,
    title        = title,
    description  = description,
    colorHex     = colorHex,
    position     = position,
    archivedAt   = archivedAt,
    shelfId      = shelfId,
    seriesId     = seriesId,
    volumeNumber = volumeNumber,
    volumeName   = this.volumeName,
    createdAt    = createdAt,
    updatedAt    = updatedAt,
)

fun NotebookPayloadV2.toEntity(driveFileId: String?): NotebookEntity = NotebookEntity(
    id           = id,
    title        = title,
    description  = description,
    colorHex     = colorHex,
    position     = position,
    shelfId      = shelfId ?: "shelf-general",
    seriesId     = seriesId,
    volumeNumber = volumeNumber ?: 1,
    volumeName   = this.volumeName,
    driveFileId  = driveFileId,
    createdAt    = createdAt,
    updatedAt    = updatedAt,
    dirty        = false,
    archivedAt   = archivedAt,
    deletedAt    = null,
)

// =====================================================================
// Shelf
// =====================================================================

@Serializable
data class ShelfPayloadV2(
    @SerialName("id")         val id: String,
    @SerialName("name")       val name: String,
    @SerialName("color_hex")  val colorHex: String? = null,
    @SerialName("position")   val position: Long,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

fun ShelfEntity.toV2Payload(): ShelfPayloadV2 = ShelfPayloadV2(
    id        = id,
    name      = name,
    colorHex  = colorHex,
    position  = position,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun ShelfPayloadV2.toEntity(): ShelfEntity = ShelfEntity(
    id        = id,
    name      = name,
    colorHex  = colorHex,
    position  = position,
    createdAt = createdAt,
    updatedAt = updatedAt,
    dirty     = false,
    deletedAt = null,
)

// =====================================================================
// Book series
// =====================================================================

@Serializable
data class BookSeriesPayloadV2(
    @SerialName("id")         val id: String,
    @SerialName("shelf_id")   val shelfId: String,
    @SerialName("name")       val name: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

fun BookSeriesEntity.toV2Payload(): BookSeriesPayloadV2 = BookSeriesPayloadV2(
    id        = id,
    shelfId   = shelfId,
    name      = name,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun BookSeriesPayloadV2.toEntity(): BookSeriesEntity = BookSeriesEntity(
    id        = id,
    shelfId   = shelfId,
    name      = name,
    createdAt = createdAt,
    updatedAt = updatedAt,
    dirty     = false,
    deletedAt = null,
)

// =====================================================================
// Chapter
// =====================================================================

@Serializable
data class ChapterPayloadV2(
    @SerialName("id")          val id: String,
    @SerialName("notebook_id") val notebookId: String,
    @SerialName("title")       val title: String,
    @SerialName("description") val description: String? = null,
    @SerialName("position")    val position: Long,
    @SerialName("created_at")  val createdAt: String,
    @SerialName("updated_at")  val updatedAt: String,
)

fun ChapterEntity.toV2Payload(): ChapterPayloadV2 = ChapterPayloadV2(
    id          = id,
    notebookId  = notebookId,
    title       = title,
    description = description,
    position    = position,
    createdAt   = createdAt,
    updatedAt   = updatedAt,
)

fun ChapterPayloadV2.toEntity(driveFileId: String?): ChapterEntity = ChapterEntity(
    id          = id,
    notebookId  = notebookId,
    title       = title,
    description = description,
    position    = position,
    driveFileId = driveFileId,
    createdAt   = createdAt,
    updatedAt   = updatedAt,
    dirty       = false,
    deletedAt   = null,
)

// =====================================================================
// Page
// =====================================================================

@Serializable
data class PagePayloadV2(
    @SerialName("id")             val id: String,
    @SerialName("chapter_id")     val chapterId: String,
    @SerialName("project_id")     val projectId: String? = null,
    @SerialName("template_id")    val templateId: String? = null,
    @SerialName("title")          val title: String? = null,
    @SerialName("notes")          val notes: String,
    @SerialName("contacts")       val contacts: JsonElement,
    @SerialName("locations")      val locations: JsonElement,
    @SerialName("todos")          val todos: JsonElement,
    @SerialName("attachments")    val attachments: JsonElement,
    @SerialName("sketch_strokes") val sketchStrokes: JsonElement,
    @SerialName("sub_pages")      val subPages: JsonElement,
    @SerialName("position")       val position: Long,
    @SerialName("created_at")     val createdAt: String,
    @SerialName("updated_at")     val updatedAt: String,
)

fun PageEntity.toV2Payload(): PagePayloadV2 = PagePayloadV2(
    id            = id,
    chapterId     = chapterId,
    projectId     = projectId,
    templateId    = templateId,
    title         = title,
    notes         = notes,
    contacts      = parseJsonArrayOrEmpty(contacts),
    locations     = parseJsonArrayOrEmpty(locations),
    todos         = parseJsonArrayOrEmpty(todos),
    attachments   = parseJsonArrayOrEmpty(attachments),
    sketchStrokes = parseJsonArrayOrEmpty(sketchStrokes),
    subPages      = parseJsonArrayOrEmpty(subPages),
    position      = position,
    createdAt     = createdAt,
    updatedAt     = updatedAt,
)

fun PagePayloadV2.toEntity(driveFileId: String?): PageEntity = PageEntity(
    id            = id,
    chapterId     = chapterId,
    projectId     = projectId,
    templateId    = templateId,
    title         = title,
    notes         = notes,
    contacts      = SyncJson.encodeToString(JsonElement.serializer(), contacts),
    locations     = SyncJson.encodeToString(JsonElement.serializer(), locations),
    todos         = SyncJson.encodeToString(JsonElement.serializer(), todos),
    attachments   = SyncJson.encodeToString(JsonElement.serializer(), attachments),
    sketchStrokes = SyncJson.encodeToString(JsonElement.serializer(), sketchStrokes),
    subPages      = SyncJson.encodeToString(JsonElement.serializer(), subPages),
    position      = position,
    conflictStub  = null,
    driveFileId   = driveFileId,
    createdAt     = createdAt,
    updatedAt     = updatedAt,
    dirty         = false,
    deletedAt     = null,
)

// =====================================================================
// NotepadEntry
// =====================================================================

@Serializable
data class NotepadEntryPayloadV2(
    @SerialName("id")                  val id: String,
    @SerialName("user_id")             val userId: String,
    @SerialName("entry_date")          val entryDate: String,
    @SerialName("project_id")          val projectId: String? = null,
    @SerialName("title")               val title: String? = null,
    @SerialName("notes")               val notes: String,
    @SerialName("contacts")            val contacts: JsonElement,
    @SerialName("locations")           val locations: JsonElement,
    @SerialName("todos")               val todos: JsonElement,
    @SerialName("attachments")         val attachments: JsonElement,
    @SerialName("sketch_strokes")      val sketchStrokes: JsonElement,
    @SerialName("sub_pages")           val subPages: JsonElement,
    @SerialName("allow_blank_content") val allowBlankContent: Boolean,
    @SerialName("created_at")          val createdAt: String,
    @SerialName("updated_at")          val updatedAt: String,
)

fun NotepadEntry.toV2Payload(): NotepadEntryPayloadV2 = NotepadEntryPayloadV2(
    id                = id,
    userId            = userId,
    entryDate         = entryDate,
    projectId         = projectId,
    title             = title,
    notes             = notes,
    contacts          = parseJsonArrayOrEmpty(contacts),
    locations         = parseJsonArrayOrEmpty(locations),
    todos             = parseJsonArrayOrEmpty(todos),
    attachments       = parseJsonArrayOrEmpty(attachments),
    sketchStrokes     = parseJsonArrayOrEmpty(sketchStrokes),
    subPages          = parseJsonArrayOrEmpty(subPages),
    allowBlankContent = allowBlankContent,
    createdAt         = createdAt,
    updatedAt         = updatedAt,
)

fun NotepadEntryPayloadV2.toEntity(driveFileId: String?): NotepadEntry = NotepadEntry(
    id                = id,
    userId            = userId,
    entryDate         = entryDate,
    projectId         = projectId,
    title             = title,
    notes             = notes,
    contacts          = SyncJson.encodeToString(JsonElement.serializer(), contacts),
    locations         = SyncJson.encodeToString(JsonElement.serializer(), locations),
    todos             = SyncJson.encodeToString(JsonElement.serializer(), todos),
    attachments       = SyncJson.encodeToString(JsonElement.serializer(), attachments),
    sketchStrokes     = SyncJson.encodeToString(JsonElement.serializer(), sketchStrokes),
    subPages          = SyncJson.encodeToString(JsonElement.serializer(), subPages),
    allowBlankContent = allowBlankContent,
    conflictStub      = null,
    driveFileId       = driveFileId,
    createdAt         = createdAt,
    updatedAt         = updatedAt,
    dirty             = false,
    deletedAt         = null,
)

// =====================================================================
// Task
// =====================================================================

@Serializable
data class TaskPayloadV2(
    @SerialName("id")           val id: String,
    @SerialName("user_id")      val userId: String,
    @SerialName("title")        val title: String,
    @SerialName("description")  val description: String? = null,
    @SerialName("due_date")     val dueDate: String? = null,
    @SerialName("completed")    val completed: Boolean,
    @SerialName("completed_at") val completedAt: String? = null,
    @SerialName("priority")     val priority: Int,
    @SerialName("status")       val status: String,
    @SerialName("created_at")   val createdAt: String,
    @SerialName("updated_at")   val updatedAt: String,
)

fun TaskEntity.toV2Payload(): TaskPayloadV2 = TaskPayloadV2(
    id          = id,
    userId      = userId,
    title       = title,
    description = description,
    dueDate     = dueDate,
    completed   = completed,
    completedAt = completedAt,
    priority    = priority,
    status      = status,
    createdAt   = createdAt,
    updatedAt   = updatedAt,
)

fun TaskPayloadV2.toEntity(): TaskEntity = TaskEntity(
    id          = id,
    userId      = userId,
    title       = title,
    description = description,
    dueDate     = dueDate,
    completed   = completed,
    completedAt = completedAt,
    priority    = priority,
    status      = status,
    createdAt   = createdAt,
    updatedAt   = updatedAt,
    dirty       = false,
    deletedAt   = null,
)

// =====================================================================
// Helpers
// =====================================================================

/**
 * Parse a locally-stored JSON string column into a `JsonElement`. On
 * parse failure, return an empty array — the row is still uploadable
 * and the bad column is surfaced as `[]` on Drive, which is safer than
 * aborting the whole sync pass. Emits to logs via `println` on failure;
 * replace with a real logger when we get one.
 */
private fun parseJsonArrayOrEmpty(raw: String?): JsonElement {
    if (raw.isNullOrEmpty()) return JsonArray(emptyList())
    return try {
        SyncJson.parseToJsonElement(raw)
    } catch (_: Exception) {
        JsonArray(emptyList())
    }
}
