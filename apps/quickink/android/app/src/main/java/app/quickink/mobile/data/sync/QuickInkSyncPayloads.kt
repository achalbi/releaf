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
import app.quickink.mobile.data.capturelocation.CaptureLocationEntity
import app.quickink.mobile.data.captureperson.CapturePersonEntity
import app.quickink.mobile.data.capturetag.CaptureTagEntity
import app.quickink.mobile.data.folder.FolderEntity
import app.quickink.mobile.data.location.LocationEntity
import app.quickink.mobile.data.ocr.OcrResultEntity
import app.quickink.mobile.data.person.PersonEntity
import app.quickink.mobile.data.profile.ProfileSettingsEntity
import app.quickink.mobile.data.smartcollection.SmartCollectionEntity
import app.quickink.mobile.data.tag.TagEntity
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
    @SerialName("id")                    val id: String,
    @SerialName("user_id")               val userId: String,
    @SerialName("title")                 val title: String? = null,
    @SerialName("pdf_uri")               val pdfUri: String,
    @SerialName("preview_uri")           val previewUri: String? = null,
    @SerialName("page_count")            val pageCount: Int,
    /**
     * Legacy pre-A.3c label slot. Post-A.3c the `captures.category`
     * column is gone; the field is kept on the wire so older
     * clients can still emit it (and so a fresh client deserializes
     * older payloads without throwing), but the new send-path
     * always writes `null` and the receive-path ignores it — the
     * canonical per-capture label now lives in `capture_tags`.
     */
    @SerialName("category")              val category: String? = null,
    /**
     * Drive file id of the per-row PDF binary upload (Phase 6 —
     * Drive backup). Restore-on-fresh-device fetches the binary
     * from this id and rewrites `pdf_uri` to point at the new
     * local copy. `null` when the writer hadn't uploaded the PDF
     * yet.
     */
    @SerialName("pdf_drive_file_id")     val pdfDriveFileId: String? = null,
    /** Drive file id of the per-row preview-JPEG binary upload. */
    @SerialName("preview_drive_file_id") val previewDriveFileId: String? = null,
    /**
     * Drive file id of the per-row hold-to-record video binary
     * upload (.mp4 in Drive). Same restore semantics as
     * [pdfDriveFileId]: the receive-side `QuickInkBinarySync.restorePending`
     * downloads the bytes, drops them into AttachmentStorage, then
     * rewrites `video_uri` to the new local path. Nullable; absent
     * on payloads from pre-v22 clients and on every capture without
     * a video.
     */
    @SerialName("video_drive_file_id")   val videoDriveFileId: String? = null,
    /**
     * How the capture was created — `"scan"` (the document scanner)
     * or `"import"` (the system photo picker). Defaulted to "scan"
     * for back-compat with rows synced from older clients that
     * didn't write the field; the column-level default in the
     * captures schema mirrors this.
     */
    @SerialName("source")                val source: String = "scan",
    /**
     * Page-size class — `"card"`, `"a4"` (default), or `"small"`.
     * Drives the sustainability hero's per-page weight on receivers
     * exactly as on the producer. Defaulted to `"a4"` so payloads
     * from older clients that didn't write the field hydrate with
     * the same value the local column default uses.
     */
    @SerialName("paper_size")            val paperSize: String = "a4",
    /**
     * Decimal-degree latitude captured at scan time (Phase 7 —
     * Geolocation). `null` on the wire when the user has the
     * "Attach location to scans" toggle off, when permission is
     * denied, or for captures from older clients. Pairs with
     * [longitude] — writers never emit one without the other.
     */
    @SerialName("latitude")              val latitude: Double? = null,
    @SerialName("longitude")             val longitude: Double? = null,
    /**
     * Reverse-geocoded city, sourced from `Geocoder.locality` at
     * write time. Round-trips through Drive verbatim — we don't
     * re-geocode on receive so the receiver sees exactly what the
     * capturing device's locale produced.
     */
    @SerialName("locality")              val locality: String? = null,
    /** Reverse-geocoded neighbourhood / area. `Geocoder.subLocality`. */
    @SerialName("sub_locality")          val subLocality: String? = null,
    /**
     * Formatted full street address built from `Geocoder` results.
     * Round-trips through Drive verbatim — we don't re-format on
     * receive so the receiver sees exactly what the capturing
     * device's locale produced.
     */
    @SerialName("address")               val address: String? = null,
    /**
     * Free-form document-level notes. Currently append-only via the
     * voice-note transcript editor. Nullable; absent on payloads
     * from pre-v15 clients.
     */
    @SerialName("notes")                 val notes: String? = null,
    /**
     * Local file:// URI for the raw video that produced the capture
     * (hold-to-record Photo-mode path, v22). Nullable; absent on
     * payloads from pre-v22 clients. Source-device URI ships
     * verbatim, but the receiver drops it in `toEntity` and waits
     * for `QuickInkBinarySync.restorePending` to download the
     * companion .mp4 from Drive via [videoDriveFileId] and rewrite
     * `video_uri` to the new local path. Mirror of the pdf_uri /
     * preview_uri sync story.
     */
    @SerialName("video_uri")             val videoUri: String? = null,
    @SerialName("created_at")            val createdAt: String,
    @SerialName("updated_at")            val updatedAt: String,
)

fun CaptureEntity.toV2Payload(): CapturePayloadV2 = CapturePayloadV2(
    id                 = id,
    userId             = userId,
    title              = title,
    pdfUri             = pdfUri,
    previewUri         = previewUri,
    pageCount          = pageCount,
    // Legacy slot — always null post-A.3c (column dropped). See
    // `CapturePayloadV2.category` docstring.
    category           = null,
    pdfDriveFileId     = pdfDriveFileId,
    previewDriveFileId = previewDriveFileId,
    videoDriveFileId   = videoDriveFileId,
    source             = source,
    paperSize          = paperSize,
    latitude           = latitude,
    longitude          = longitude,
    locality           = locality,
    subLocality        = subLocality,
    address            = address,
    notes              = notes,
    videoUri           = videoUri,
    createdAt          = createdAt,
    updatedAt          = updatedAt,
)

fun CapturePayloadV2.toEntity(driveFileId: String?): CaptureEntity = CaptureEntity(
    id                 = id,
    userId             = userId,
    title              = title,
    notes              = notes,
    pdfUri             = pdfUri,
    previewUri         = previewUri,
    pageCount          = pageCount,
    // `category` from the payload is intentionally dropped here —
    // the column is gone post-A.3c. Older clients still emit the
    // field; the receive side either has a corresponding
    // capture_tags row already (from the A.3a materialize) or
    // doesn't care about the legacy label for new captures.
    source             = source,
    paperSize          = paperSize,
    latitude           = latitude,
    longitude          = longitude,
    locality           = locality,
    subLocality        = subLocality,
    address            = address,
    conflictStub       = null,
    driveFileId        = driveFileId,
    pdfDriveFileId     = pdfDriveFileId,
    previewDriveFileId = previewDriveFileId,
    videoDriveFileId   = videoDriveFileId,
    // Drop the URI on receive — the remote path is meaningless on
    // this device. `QuickInkBinarySync.restorePending` downloads
    // the companion .mp4 from Drive via [videoDriveFileId] and
    // rewrites `video_uri` to the local file:// path via
    // `captureDao.setVideoUriLocal`.
    videoUri           = null,
    createdAt          = createdAt,
    updatedAt          = updatedAt,
    dirty              = false,
    deletedAt          = null,
)

// =====================================================================
// tags — QuickInk-only. User-configurable list (renamed from
// `categories` in v4_workspace.sql), synced so the same chip set
// follows the user across devices. Wire format keeps the same
// JSON field shape — only the Kotlin class name changed; older
// payloads still on Drive under the `categories/` prefix
// deserialize identically until the Phase A.3 path migration
// rewrites them under `tags/`.
// =====================================================================

@Serializable
data class TagPayloadV1(
    @SerialName("id")           val id: String,
    @SerialName("user_id")      val userId: String,
    @SerialName("name")         val name: String,
    @SerialName("position")     val position: Int,
    @SerialName("color")        val color: String? = null,
    @SerialName("created_at")   val createdAt: String,
    @SerialName("updated_at")   val updatedAt: String,
)

fun TagEntity.toV1Payload(): TagPayloadV1 = TagPayloadV1(
    id        = id,
    userId    = userId,
    name      = name,
    position  = position,
    color     = color,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun TagPayloadV1.toEntity(driveFileId: String?): TagEntity = TagEntity(
    id           = id,
    userId       = userId,
    name         = name,
    position     = position,
    color        = color,
    driveFileId  = driveFileId,
    createdAt    = createdAt,
    updatedAt    = updatedAt,
    dirty        = false,
    deletedAt    = null,
)

// =====================================================================
// profile_settings — QuickInk-only. One row per user; carries the
// custom display-name override, phone, personality punchline, and
// the photo's Drive-file linkage. The actual photo binary travels
// via QuickInkBinarySync (same path captures take); this payload
// only carries the metadata reference (`photo_drive_file_id`).
// `photo_local_uri` is deliberately NOT in the wire shape — it's a
// device-local file:// URI that wouldn't make sense on a different
// device. The restore side fills it in after re-downloading the
// binary.
// =====================================================================

@Serializable
data class ProfileSettingsPayloadV1(
    @SerialName("id")                    val id: String,
    @SerialName("user_id")               val userId: String,
    @SerialName("display_name")          val displayName: String? = null,
    @SerialName("phone_number")          val phoneNumber: String? = null,
    @SerialName("personality_punchline") val personalityPunchline: String? = null,
    @SerialName("photo_drive_file_id")   val photoDriveFileId: String? = null,
    @SerialName("photo_updated_at")      val photoUpdatedAt: String? = null,
    @SerialName("created_at")            val createdAt: String,
    @SerialName("updated_at")            val updatedAt: String,
)

fun ProfileSettingsEntity.toV1Payload(): ProfileSettingsPayloadV1 = ProfileSettingsPayloadV1(
    id                   = id,
    userId               = userId,
    displayName          = displayName,
    phoneNumber          = phoneNumber,
    personalityPunchline = personalityPunchline,
    photoDriveFileId     = photoDriveFileId,
    photoUpdatedAt       = photoUpdatedAt,
    createdAt            = createdAt,
    updatedAt            = updatedAt,
)

/**
 * Build a fresh entity from a remote payload. `photoLocalUri` is
 * left null on the receive side — the binary-restore step
 * downloads the file and writes the local URI back in a separate
 * pass. Until that lands, the Profile screen shows the initial /
 * default avatar.
 */
fun ProfileSettingsPayloadV1.toEntity(driveFileId: String?): ProfileSettingsEntity = ProfileSettingsEntity(
    id                   = id,
    userId               = userId,
    displayName          = displayName,
    phoneNumber          = phoneNumber,
    personalityPunchline = personalityPunchline,
    photoLocalUri        = null,
    photoDriveFileId     = photoDriveFileId,
    photoUpdatedAt       = photoUpdatedAt,
    driveFileId          = driveFileId,
    createdAt            = createdAt,
    updatedAt            = updatedAt,
    dirty                = false,
    deletedAt            = null,
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

// =====================================================================
// folders — QuickInk-only. Workspace v1 "intent" axis. One row per
// user-defined folder; captures FK into folders.id via
// captures.folder_id. The is_default = true row is the seeded
// "Unfiled" folder. is_shared is reserved for the post-v1 share
// flow — currently always 0.
// =====================================================================

@Serializable
data class FolderPayloadV1(
    @SerialName("id")          val id: String,
    @SerialName("user_id")     val userId: String,
    @SerialName("name")        val name: String,
    @SerialName("color")       val color: String,
    @SerialName("position")    val position: Int,
    @SerialName("cover_uri")   val coverUri: String? = null,
    @SerialName("is_default")  val isDefault: Boolean = false,
    @SerialName("is_shared")   val isShared: Boolean = false,
    @SerialName("created_at")  val createdAt: String,
    @SerialName("updated_at")  val updatedAt: String,
)

fun FolderEntity.toV1Payload(): FolderPayloadV1 = FolderPayloadV1(
    id        = id,
    userId    = userId,
    name      = name,
    color     = color,
    position  = position,
    coverUri  = coverUri,
    isDefault = isDefault,
    isShared  = isShared,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun FolderPayloadV1.toEntity(driveFileId: String?): FolderEntity = FolderEntity(
    id          = id,
    userId      = userId,
    name        = name,
    color       = color,
    position    = position,
    coverUri    = coverUri,
    isDefault   = isDefault,
    isShared    = isShared,
    driveFileId = driveFileId,
    createdAt   = createdAt,
    updatedAt   = updatedAt,
    dirty       = false,
    deletedAt   = null,
)

// =====================================================================
// capture_tags — QuickInk-only. Workspace v1 many-to-many join. Each
// row syncs independently so a tag added on phone A reaches phone B
// without re-syncing the entire capture. `source` distinguishes
// manual / ai-suggested / migration provenance.
// =====================================================================

@Serializable
data class CaptureTagPayloadV1(
    @SerialName("id")          val id: String,
    @SerialName("capture_id")  val captureId: String,
    @SerialName("tag_id")      val tagId: String,
    @SerialName("source")      val source: String = "manual",
    @SerialName("created_at")  val createdAt: String,
    @SerialName("updated_at")  val updatedAt: String,
)

fun CaptureTagEntity.toV1Payload(): CaptureTagPayloadV1 = CaptureTagPayloadV1(
    id        = id,
    captureId = captureId,
    tagId     = tagId,
    source    = source,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun CaptureTagPayloadV1.toEntity(driveFileId: String?): CaptureTagEntity = CaptureTagEntity(
    id          = id,
    captureId   = captureId,
    tagId       = tagId,
    source      = source,
    driveFileId = driveFileId,
    createdAt   = createdAt,
    updatedAt   = updatedAt,
    dirty       = false,
    deletedAt   = null,
)

// =====================================================================
// smart_collections — QuickInk-only. Workspace v1 rule-based saved
// view. `rule_json` is an AND-of-clauses array on the wire (parsed
// app-side, not the DB). `is_seeded` distinguishes shipped seeds
// from user-created collections; the seeded flag is wire-stable so
// every device agrees on which collections are managed by the app.
// =====================================================================

@Serializable
data class SmartCollectionPayloadV1(
    @SerialName("id")          val id: String,
    @SerialName("user_id")     val userId: String,
    @SerialName("name")        val name: String,
    @SerialName("icon")        val icon: String? = null,
    @SerialName("color")       val color: String? = null,
    @SerialName("rule_json")   val ruleJson: String,
    @SerialName("position")    val position: Int = 0,
    @SerialName("is_seeded")   val isSeeded: Boolean = false,
    @SerialName("created_at")  val createdAt: String,
    @SerialName("updated_at")  val updatedAt: String,
)

fun SmartCollectionEntity.toV1Payload(): SmartCollectionPayloadV1 = SmartCollectionPayloadV1(
    id        = id,
    userId    = userId,
    name      = name,
    icon      = icon,
    color     = color,
    ruleJson  = ruleJson,
    position  = position,
    isSeeded  = isSeeded,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun SmartCollectionPayloadV1.toEntity(driveFileId: String?): SmartCollectionEntity = SmartCollectionEntity(
    id          = id,
    userId      = userId,
    name        = name,
    icon        = icon,
    color       = color,
    ruleJson    = ruleJson,
    position    = position,
    isSeeded    = isSeeded,
    driveFileId = driveFileId,
    createdAt   = createdAt,
    updatedAt   = updatedAt,
    dirty       = false,
    deletedAt   = null,
)

// =====================================================================
// voice_notes — QuickInk-only. One row per recorded clip attached to
// a capture. JSON carries the metadata + `audio_drive_file_id`; the
// .m4a binary itself goes through `QuickInkBinarySync`, mirroring how
// captures' PDFs travel separately from the capture JSON.
// =====================================================================

@kotlinx.serialization.Serializable
data class VoiceNotePayloadV1(
    @kotlinx.serialization.SerialName("id")                   val id: String,
    @kotlinx.serialization.SerialName("capture_id")           val captureId: String,
    @kotlinx.serialization.SerialName("user_id")              val userId: String,
    /**
     * Source device's local file:// URI. Same caveat as
     * `captures.pdf_uri`: meaningless on a different device. The
     * receive side keeps the local URI when the file already exists
     * here, otherwise blanks it out and waits for the binary-restore
     * pass to fill it in from `audio_drive_file_id`.
     */
    @kotlinx.serialization.SerialName("audio_uri")            val audioUri: String,
    @kotlinx.serialization.SerialName("duration_ms")          val durationMs: Long,
    @kotlinx.serialization.SerialName("transcription")        val transcription: String? = null,
    @kotlinx.serialization.SerialName("transcription_source") val transcriptionSource: String? = null,
    /** Drive id of the .m4a binary upload. `null` until first push. */
    @kotlinx.serialization.SerialName("audio_drive_file_id")  val audioDriveFileId: String? = null,
    @kotlinx.serialization.SerialName("created_at")           val createdAt: String,
    @kotlinx.serialization.SerialName("updated_at")           val updatedAt: String,
)

fun app.quickink.mobile.data.voicenote.VoiceNoteEntity.toV1Payload(): VoiceNotePayloadV1 =
    VoiceNotePayloadV1(
        id                  = id,
        captureId           = captureId,
        userId              = userId,
        audioUri            = audioUri,
        durationMs          = durationMs,
        transcription       = transcription,
        transcriptionSource = transcriptionSource,
        audioDriveFileId    = audioDriveFileId,
        createdAt           = createdAt,
        updatedAt           = updatedAt,
    )

fun VoiceNotePayloadV1.toEntity(driveFileId: String?): app.quickink.mobile.data.voicenote.VoiceNoteEntity =
    app.quickink.mobile.data.voicenote.VoiceNoteEntity(
        id                  = id,
        captureId           = captureId,
        userId              = userId,
        audioUri            = audioUri,
        durationMs          = durationMs,
        transcription       = transcription,
        transcriptionSource = transcriptionSource,
        driveFileId         = driveFileId,
        audioDriveFileId    = audioDriveFileId,
        createdAt           = createdAt,
        updatedAt           = updatedAt,
        dirty               = false,
        deletedAt           = null,
    )

// =====================================================================
// Stories (Phase 1 + Phase 2) — see design/STORIES_HANDOFF.md. Mirror
// of iOS `StoryPayloadV1` / `StoryItemPayloadV1` / `StoryVoiceClipPayloadV1`.
// =====================================================================

@kotlinx.serialization.Serializable
data class StoryPayloadV1(
    @kotlinx.serialization.SerialName("id")               val id: String,
    @kotlinx.serialization.SerialName("user_id")          val userId: String,
    @kotlinx.serialization.SerialName("title")            val title: String,
    @kotlinx.serialization.SerialName("subtitle")         val subtitle: String? = null,
    @kotlinx.serialization.SerialName("cover_item_id")    val coverItemId: String? = null,
    @kotlinx.serialization.SerialName("cover_style")      val coverStyle: String,
    @kotlinx.serialization.SerialName("theme_style")      val themeStyle: String,
    @kotlinx.serialization.SerialName("grouping_mode")    val groupingMode: String,
    @kotlinx.serialization.SerialName("time_range_start") val timeRangeStart: String? = null,
    @kotlinx.serialization.SerialName("time_range_end")   val timeRangeEnd: String? = null,
    @kotlinx.serialization.SerialName("status")           val status: String,
    @kotlinx.serialization.SerialName("share_mode")       val shareMode: String,
    @kotlinx.serialization.SerialName("share_slug")       val shareSlug: String? = null,
    @kotlinx.serialization.SerialName("created_at")       val createdAt: String,
    @kotlinx.serialization.SerialName("updated_at")       val updatedAt: String,
)

fun app.quickink.mobile.data.story.StoryEntity.toV1Payload(): StoryPayloadV1 =
    StoryPayloadV1(
        id             = id,
        userId         = userId,
        title          = title,
        subtitle       = subtitle,
        coverItemId    = coverItemId,
        coverStyle     = coverStyle,
        themeStyle     = themeStyle,
        groupingMode   = groupingMode,
        timeRangeStart = timeRangeStart,
        timeRangeEnd   = timeRangeEnd,
        status         = status,
        shareMode      = shareMode,
        shareSlug      = shareSlug,
        createdAt      = createdAt,
        updatedAt      = updatedAt,
    )

fun StoryPayloadV1.toEntity(): app.quickink.mobile.data.story.StoryEntity =
    app.quickink.mobile.data.story.StoryEntity(
        id             = id,
        userId         = userId,
        title          = title,
        subtitle       = subtitle,
        coverItemId    = coverItemId,
        coverStyle     = coverStyle,
        themeStyle     = themeStyle,
        groupingMode   = groupingMode,
        timeRangeStart = timeRangeStart,
        timeRangeEnd   = timeRangeEnd,
        status         = status,
        shareMode      = shareMode,
        shareSlug      = shareSlug,
        createdAt      = createdAt,
        updatedAt      = updatedAt,
        dirty          = false,
        deletedAt      = null,
    )

@kotlinx.serialization.Serializable
data class StoryItemPayloadV1(
    @kotlinx.serialization.SerialName("id")          val id: String,
    @kotlinx.serialization.SerialName("story_id")    val storyId: String,
    @kotlinx.serialization.SerialName("position")    val position: Int,
    @kotlinx.serialization.SerialName("kind")        val kind: String,
    @kotlinx.serialization.SerialName("ref_id")      val refId: String? = null,
    @kotlinx.serialization.SerialName("text")        val text: String? = null,
    @kotlinx.serialization.SerialName("caption")     val caption: String? = null,
    @kotlinx.serialization.SerialName("occurred_at") val occurredAt: String? = null,
    @kotlinx.serialization.SerialName("layout")      val layout: String,
    @kotlinx.serialization.SerialName("created_at")  val createdAt: String,
    @kotlinx.serialization.SerialName("updated_at")  val updatedAt: String,
)

fun app.quickink.mobile.data.storyitem.StoryItemEntity.toV1Payload(): StoryItemPayloadV1 =
    StoryItemPayloadV1(
        id         = id,
        storyId    = storyId,
        position   = position,
        kind       = kind,
        refId      = refId,
        text       = text,
        caption    = caption,
        occurredAt = occurredAt,
        layout     = layout,
        createdAt  = createdAt,
        updatedAt  = updatedAt,
    )

fun StoryItemPayloadV1.toEntity(): app.quickink.mobile.data.storyitem.StoryItemEntity =
    app.quickink.mobile.data.storyitem.StoryItemEntity(
        id         = id,
        storyId    = storyId,
        position   = position,
        kind       = kind,
        refId      = refId,
        text       = text,
        caption    = caption,
        occurredAt = occurredAt,
        layout     = layout,
        createdAt  = createdAt,
        updatedAt  = updatedAt,
        dirty      = false,
        deletedAt  = null,
    )

@kotlinx.serialization.Serializable
data class StoryVoiceClipPayloadV1(
    @kotlinx.serialization.SerialName("id")                   val id: String,
    @kotlinx.serialization.SerialName("story_item_id")        val storyItemId: String,
    @kotlinx.serialization.SerialName("user_id")              val userId: String,
    @kotlinx.serialization.SerialName("audio_uri")            val audioUri: String,
    @kotlinx.serialization.SerialName("duration_ms")          val durationMs: Long,
    @kotlinx.serialization.SerialName("transcription")        val transcription: String? = null,
    @kotlinx.serialization.SerialName("transcription_source") val transcriptionSource: String? = null,
    @kotlinx.serialization.SerialName("audio_drive_file_id")  val audioDriveFileId: String? = null,
    @kotlinx.serialization.SerialName("created_at")           val createdAt: String,
    @kotlinx.serialization.SerialName("updated_at")           val updatedAt: String,
)

fun app.quickink.mobile.data.storyvoiceclip.StoryVoiceClipEntity.toV1Payload(): StoryVoiceClipPayloadV1 =
    StoryVoiceClipPayloadV1(
        id                  = id,
        storyItemId         = storyItemId,
        userId              = userId,
        audioUri            = audioUri,
        durationMs          = durationMs,
        transcription       = transcription,
        transcriptionSource = transcriptionSource,
        audioDriveFileId    = audioDriveFileId,
        createdAt           = createdAt,
        updatedAt           = updatedAt,
    )

fun StoryVoiceClipPayloadV1.toEntity(driveFileId: String?): app.quickink.mobile.data.storyvoiceclip.StoryVoiceClipEntity =
    app.quickink.mobile.data.storyvoiceclip.StoryVoiceClipEntity(
        id                  = id,
        storyItemId         = storyItemId,
        userId              = userId,
        audioUri            = audioUri,
        durationMs          = durationMs,
        transcription       = transcription,
        transcriptionSource = transcriptionSource,
        driveFileId         = driveFileId,
        audioDriveFileId    = audioDriveFileId,
        createdAt           = createdAt,
        updatedAt           = updatedAt,
        dirty               = false,
        deletedAt           = null,
    )

// =====================================================================
// locations — QuickInk-only. User-defined places ("Home", "Work", …)
// surfaced as the Home-screen chip rail. Mirror of `tags` on the
// wire; lives under `locations/` per DrivePath.
// =====================================================================

@Serializable
data class LocationPayloadV1(
    @SerialName("id")           val id: String,
    @SerialName("user_id")      val userId: String,
    @SerialName("name")         val name: String,
    @SerialName("position")     val position: Int,
    @SerialName("color")        val color: String? = null,
    @SerialName("latitude")     val latitude: Double? = null,
    @SerialName("longitude")    val longitude: Double? = null,
    @SerialName("address")      val address: String? = null,
    @SerialName("created_at")   val createdAt: String,
    @SerialName("updated_at")   val updatedAt: String,
)

fun LocationEntity.toV1Payload(): LocationPayloadV1 = LocationPayloadV1(
    id        = id,
    userId    = userId,
    name      = name,
    position  = position,
    color     = color,
    latitude  = latitude,
    longitude = longitude,
    address   = address,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun LocationPayloadV1.toEntity(driveFileId: String?): LocationEntity = LocationEntity(
    id           = id,
    userId       = userId,
    name         = name,
    position     = position,
    color        = color,
    latitude     = latitude,
    longitude    = longitude,
    address      = address,
    driveFileId  = driveFileId,
    createdAt    = createdAt,
    updatedAt    = updatedAt,
    dirty        = false,
    deletedAt    = null,
)

// =====================================================================
// capture_locations — QuickInk-only. Many-to-many join between
// captures and user-defined locations. Each row syncs independently
// so a location added on phone A reaches phone B without re-syncing
// the entire capture. Mirror of `capture_tags`.
// =====================================================================

@Serializable
data class CaptureLocationPayloadV1(
    @SerialName("id")           val id: String,
    @SerialName("capture_id")   val captureId: String,
    @SerialName("location_id")  val locationId: String,
    @SerialName("source")       val source: String = "manual",
    @SerialName("created_at")   val createdAt: String,
    @SerialName("updated_at")   val updatedAt: String,
)

fun CaptureLocationEntity.toV1Payload(): CaptureLocationPayloadV1 = CaptureLocationPayloadV1(
    id         = id,
    captureId  = captureId,
    locationId = locationId,
    source     = source,
    createdAt  = createdAt,
    updatedAt  = updatedAt,
)

fun CaptureLocationPayloadV1.toEntity(driveFileId: String?): CaptureLocationEntity =
    CaptureLocationEntity(
        id          = id,
        captureId   = captureId,
        locationId  = locationId,
        source      = source,
        driveFileId = driveFileId,
        createdAt   = createdAt,
        updatedAt   = updatedAt,
        dirty       = false,
        deletedAt   = null,
    )

// =====================================================================
// people — QuickInk-only. User-defined people ("Me", "Mom", "Dr. Rao", …)
// surfaced as the Home-screen people chip rail. Mirror of `locations`
// on the wire (minus the geo columns); lives under `people/` per
// DrivePath.
// =====================================================================

@Serializable
data class PersonPayloadV1(
    @SerialName("id")            val id: String,
    @SerialName("user_id")       val userId: String,
    @SerialName("name")          val name: String,
    @SerialName("position")      val position: Int,
    @SerialName("color")         val color: String? = null,
    /**
     * Portable cached contact fields. `contact_lookup_key` and
     * `contact_photo_uri` are deliberately omitted — both are
     * device-local handles that wouldn't resolve on a different
     * device. Phone + email are the real-world identifiers that
     * round-trip cleanly.
     */
    @SerialName("contact_phone") val contactPhone: String? = null,
    @SerialName("contact_email") val contactEmail: String? = null,
    @SerialName("created_at")    val createdAt: String,
    @SerialName("updated_at")    val updatedAt: String,
)

fun PersonEntity.toV1Payload(): PersonPayloadV1 = PersonPayloadV1(
    id           = id,
    userId       = userId,
    name         = name,
    position     = position,
    color        = color,
    contactPhone = contactPhone,
    contactEmail = contactEmail,
    createdAt    = createdAt,
    updatedAt    = updatedAt,
)

fun PersonPayloadV1.toEntity(driveFileId: String?): PersonEntity = PersonEntity(
    id               = id,
    userId           = userId,
    name             = name,
    position         = position,
    color            = color,
    contactLookupKey = null,
    contactPhone     = contactPhone,
    contactEmail     = contactEmail,
    contactPhotoUri  = null,
    driveFileId      = driveFileId,
    createdAt        = createdAt,
    updatedAt        = updatedAt,
    dirty            = false,
    deletedAt        = null,
)

// =====================================================================
// capture_people — QuickInk-only. Many-to-many join between captures
// and user-defined people. Mirror of `capture_locations`.
// =====================================================================

@Serializable
data class CapturePersonPayloadV1(
    @SerialName("id")           val id: String,
    @SerialName("capture_id")   val captureId: String,
    @SerialName("person_id")    val personId: String,
    @SerialName("source")       val source: String = "manual",
    @SerialName("created_at")   val createdAt: String,
    @SerialName("updated_at")   val updatedAt: String,
)

fun CapturePersonEntity.toV1Payload(): CapturePersonPayloadV1 = CapturePersonPayloadV1(
    id         = id,
    captureId  = captureId,
    personId   = personId,
    source     = source,
    createdAt  = createdAt,
    updatedAt  = updatedAt,
)

fun CapturePersonPayloadV1.toEntity(driveFileId: String?): CapturePersonEntity =
    CapturePersonEntity(
        id          = id,
        captureId   = captureId,
        personId    = personId,
        source      = source,
        driveFileId = driveFileId,
        createdAt   = createdAt,
        updatedAt   = updatedAt,
        dirty       = false,
        deletedAt   = null,
    )
