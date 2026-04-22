/*
 * SyncPayloads.kt
 *
 * Wire formats for the rows the sync worker writes to Drive. These are the
 * on-disk JSON shapes — they're stable and tied to `schemaVersion`. If we
 * change any field, bump [SyncManifest.schemaVersion] and handle the read
 * side when we grow a pull path.
 *
 * Intentionally flat per-type: `{entity}_id.json` under
 * `/Releaf/{entityDir}/`, and parent refs are carried inside the payload
 * (chapter carries `notebook_id`, page carries `chapter_id`). This is
 * simpler than replicating the user's tree in Drive folders and means the
 * worker can upload rows in any order.
 *
 * Serialization uses kotlinx.serialization so we get a single source of
 * truth for field names — the `@SerialName`s track the schema snake_case
 * while our Kotlin stays camelCase.
 */

package app.releaf.mobile.data.sync

import app.releaf.mobile.data.notebook.ChapterEntity
import app.releaf.mobile.data.notebook.NotebookEntity
import app.releaf.mobile.data.notebook.PageEntity
import app.releaf.mobile.data.notepad.NotepadEntry
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class NotepadEntryPayload(
    @SerialName("id")         val id: String,
    @SerialName("user_id")    val userId: String,
    @SerialName("entry_date") val entryDate: String,
    @SerialName("title")      val title: String?,
    @SerialName("notes")      val notes: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

fun NotepadEntry.toPayload(): NotepadEntryPayload = NotepadEntryPayload(
    id        = id,
    userId    = userId,
    entryDate = entryDate,
    title     = title,
    notes     = notes,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

@Serializable
data class NotebookPayload(
    @SerialName("id")         val id: String,
    @SerialName("title")      val title: String,
    @SerialName("color_hex")  val colorHex: String?,
    @SerialName("position")   val position: Long,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

fun NotebookEntity.toPayload(): NotebookPayload = NotebookPayload(
    id        = id,
    title     = title,
    colorHex  = colorHex,
    position  = position,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

@Serializable
data class ChapterPayload(
    @SerialName("id")          val id: String,
    @SerialName("notebook_id") val notebookId: String,
    @SerialName("title")       val title: String,
    @SerialName("position")    val position: Long,
    @SerialName("created_at")  val createdAt: String,
    @SerialName("updated_at")  val updatedAt: String,
)

fun ChapterEntity.toPayload(): ChapterPayload = ChapterPayload(
    id         = id,
    notebookId = notebookId,
    title      = title,
    position   = position,
    createdAt  = createdAt,
    updatedAt  = updatedAt,
)

@Serializable
data class PagePayload(
    @SerialName("id")          val id: String,
    @SerialName("chapter_id")  val chapterId: String,
    @SerialName("project_id")  val projectId: String?,
    @SerialName("template_id") val templateId: String?,
    @SerialName("title")       val title: String?,
    @SerialName("notes")       val notes: String,
    /** Raw JSON arrays preserved as strings — the page row stores them
     *  pre-serialized, so we ship them through verbatim to avoid a re-encode. */
    @SerialName("contacts")    val contacts: String,
    @SerialName("locations")   val locations: String,
    @SerialName("position")    val position: Long,
    @SerialName("created_at")  val createdAt: String,
    @SerialName("updated_at")  val updatedAt: String,
)

fun PageEntity.toPayload(): PagePayload = PagePayload(
    id         = id,
    chapterId  = chapterId,
    projectId  = projectId,
    templateId = templateId,
    title      = title,
    notes      = notes,
    contacts   = contacts,
    locations  = locations,
    position   = position,
    createdAt  = createdAt,
    updatedAt  = updatedAt,
)

/**
 * Top-level manifest written to `/Releaf/manifest.json` after each sync.
 * Consumed eventually by a pull path + by a future "restore from Drive"
 * flow. v1 is deliberately minimal — we only include what we actually use
 * locally today.
 */
@Serializable
data class SyncManifest(
    @SerialName("schema_version")   val schemaVersion: Int = 1,
    @SerialName("user_id")          val userId: String,
    @SerialName("device_id")        val deviceId: String,
    @SerialName("last_synced_at")   val lastSyncedAt: String,
    @SerialName("entity_counts")    val entityCounts: Map<String, Int>,
)
