/*
 * QuickInkSyncPayloads.kt
 *
 * Canonical-JSON wire shapes for QuickInk's three synced entity
 * kinds: `notepad_entries`, `captures`, `ocr_results`. Mirror of
 * `apps/releaf/.../sync/SyncPayloads.kt` for Releaf's set.
 *
 * Contract:
 *   - `NotepadEntryPayloadV2` is lifted verbatim from Releaf's
 *     `SyncPayloads.kt`. The two apps share the `notepad_entries`
 *     row shape — the CI shared-tables diff (per QUICKINK_PROPOSAL.md
 *     §3) enforces column-list parity, and the canonical-JSON
 *     encoding has to match byte-for-byte for cross-app interop.
 *     If you change one side, change the other.
 *   - `CapturePayloadV2` and `OcrResultPayloadV2` are new — defined
 *     here for the first time. QuickInk-only entity kinds.
 *
 * Local-only columns are deliberately omitted from every payload:
 *   - `dirty`, `deleted_at`, `conflict_stub` — sync bookkeeping
 *   - `drive_file_id` — set on the receive side from the manifest
 *
 * `blocks_json` on `OcrResultEntity` is itself a JSON array; the
 * payload decodes it into a `JsonElement` so the array is
 * first-class on the wire (not a stringified blob inside JSON).
 * The receive-side `toEntity` helper re-serialises it back into a
 * string for the column.
 */

@file:Suppress("unused") // referenced by QuickInkSyncDataSource

package app.quickink.mobile.data.sync

import app.quickink.mobile.data.capture.CaptureEntity
import app.quickink.mobile.data.ocr.OcrResultEntity
import app.releaf.mobile.data.notepad.NotepadEntry
import app.releaf.mobile.data.sync.SyncJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

// =====================================================================
// notepad_entries — same shape as Releaf's NotepadEntryPayloadV2
// (lifted verbatim; cross-app shared-table invariant).
// =====================================================================

@Serializable
data class NotepadEntryPayloadV2(
    @SerialName("id")                  val id: String,
    @SerialName("user_id")             val userId: String,
    @SerialName("entry_date")          val entryDate: String,
    @SerialName("project_id")          val projectId: String? = null,
    @SerialName("title")               val title: String? = null,
    @SerialName("description")         val description: String? = null,
    @SerialName("category")            val category: String? = null,
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
    description       = description,
    category          = category,
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
    description       = description,
    category          = category,
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
// captures — QuickInk-only. One row per scan session.
// =====================================================================

@Serializable
data class CapturePayloadV2(
    @SerialName("id")           val id: String,
    @SerialName("user_id")      val userId: String,
    @SerialName("title")        val title: String? = null,
    @SerialName("pdf_uri")      val pdfUri: String,
    @SerialName("preview_uri")  val previewUri: String? = null,
    @SerialName("page_count")   val pageCount: Int,
    @SerialName("created_at")   val createdAt: String,
    @SerialName("updated_at")   val updatedAt: String,
)

fun CaptureEntity.toV2Payload(): CapturePayloadV2 = CapturePayloadV2(
    id         = id,
    userId     = userId,
    title      = title,
    pdfUri     = pdfUri,
    previewUri = previewUri,
    pageCount  = pageCount,
    createdAt  = createdAt,
    updatedAt  = updatedAt,
)

fun CapturePayloadV2.toEntity(driveFileId: String?): CaptureEntity = CaptureEntity(
    id           = id,
    userId       = userId,
    title        = title,
    pdfUri       = pdfUri,
    previewUri   = previewUri,
    pageCount    = pageCount,
    conflictStub = null,
    driveFileId  = driveFileId,
    createdAt    = createdAt,
    updatedAt    = updatedAt,
    dirty        = false,
    deletedAt    = null,
)

// =====================================================================
// ocr_results — QuickInk-only. One row per scanned page.
// =====================================================================

@Serializable
data class OcrResultPayloadV2(
    @SerialName("id")             val id: String,
    @SerialName("capture_id")     val captureId: String,
    @SerialName("page_index")     val pageIndex: Int,
    @SerialName("language")       val language: String? = null,
    @SerialName("confidence")     val confidence: Double? = null,
    @SerialName("text")           val text: String,
    /**
     * `OcrBlock[]` as a first-class JSON array on the wire, NOT a
     * stringified blob nested inside the row's JSON. The local row
     * stores the same content in `ocr_results.blocks_json` as a
     * string column for Room compatibility; the encode/decode pair
     * here flips between the two.
     */
    @SerialName("blocks")         val blocks: JsonElement,
    @SerialName("engine")         val engine: String,
    @SerialName("engine_version") val engineVersion: String? = null,
    @SerialName("created_at")     val createdAt: String,
    @SerialName("updated_at")     val updatedAt: String,
)

fun OcrResultEntity.toV2Payload(): OcrResultPayloadV2 = OcrResultPayloadV2(
    id            = id,
    captureId     = captureId,
    pageIndex     = pageIndex,
    language      = language,
    confidence    = confidence,
    text          = text,
    blocks        = parseJsonArrayOrEmpty(blocksJson),
    engine        = engine,
    engineVersion = engineVersion,
    createdAt     = createdAt,
    updatedAt     = updatedAt,
)

fun OcrResultPayloadV2.toEntity(driveFileId: String?): OcrResultEntity = OcrResultEntity(
    id            = id,
    captureId     = captureId,
    pageIndex     = pageIndex,
    language      = language,
    confidence    = confidence,
    text          = text,
    blocksJson    = SyncJson.encodeToString(JsonElement.serializer(), blocks),
    engine        = engine,
    engineVersion = engineVersion,
    driveFileId   = driveFileId,
    createdAt     = createdAt,
    updatedAt     = updatedAt,
    dirty         = false,
    deletedAt     = null,
)

// =====================================================================
// helpers
// =====================================================================

/**
 * Parse `raw` as a JSON array; return an empty `JsonArray` on null,
 * blank, or any parse failure. Mirror of Releaf's same-named helper
 * — kept here as a private rather than promoting to `:shared:sync`
 * for now (the parser is two lines and the cost of duplication is
 * a single grep when refactoring).
 */
private fun parseJsonArrayOrEmpty(raw: String?): JsonElement {
    if (raw.isNullOrBlank()) return JsonArray(emptyList())
    return runCatching { SyncJson.parseToJsonElement(raw) }
        .getOrElse { JsonArray(emptyList()) }
}
