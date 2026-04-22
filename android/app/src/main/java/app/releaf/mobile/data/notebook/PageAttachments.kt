/*
 * PageAttachments.kt
 *
 * Lightweight value types for the page-editor sections that store their
 * state in JSON columns on the `pages` row — contacts, todos, locations,
 * and attachment manifests (photos + scans). Keeping them as JSON columns
 * avoids adding four join tables for what are effectively small
 * per-page lists; we can upgrade to proper tables later if any of them
 * grows beyond the "handful of items per page" shape.
 *
 * Uses kotlinx.serialization — the JSON format matches the schema's
 * documented `[{...}, {...}]` array convention.
 */

package app.releaf.mobile.data.notebook

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val jsonCodec = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
}

// -------------------------- Contact --------------------------

@Serializable
data class Contact(
    val id: String,
    val name: String,
    val role: String? = null,
)

fun List<Contact>.toJsonString(): String = jsonCodec.encodeToString(this)
fun String.parseContacts(): List<Contact> = parseList(this)

// -------------------------- Todo --------------------------

@Serializable
data class TodoItem(
    val id: String,
    val text: String,
    val done: Boolean = false,
)

@JvmName("todosToJsonString")
fun List<TodoItem>.toJsonString(): String = jsonCodec.encodeToString(this)
fun String.parseTodos(): List<TodoItem> = parseList(this)

// -------------------------- Location --------------------------

@Serializable
data class GeoLocation(
    val id: String,
    val lat: Double,
    val lng: Double,
    /** Reverse-geocoded short address, if we could get one. */
    val address: String? = null,
    /** ISO-8601 UTC when captured — helps the user tell apart multiple entries. */
    val capturedAt: String,
)

@JvmName("locationsToJsonString")
fun List<GeoLocation>.toJsonString(): String = jsonCodec.encodeToString(this)
fun String.parseLocations(): List<GeoLocation> = parseList(this)

// -------------------------- Attachment (photo / scan) --------------------------

@Serializable
data class Attachment(
    val id: String,
    /** "photo", "scan", or "voice". Kept as a string so a future
     *  "sketch" type can slot in without a migration. */
    val type: String,
    /** content:// or file:// URI. Photos come from the picker
     *  (persistable permission taken at capture time). Scans are PDFs
     *  copied into the app's private files dir. Voice notes are M4A
     *  files written by MediaRecorder into the attachments dir. */
    val uri: String,
    /** Optional secondary URI for scans that have both a PDF and a JPEG
     *  thumbnail variant. */
    val previewUri: String? = null,
    val capturedAt: String,
    /**
     * Clip length in milliseconds, only set for voice notes. Nullable so
     * existing photo/scan attachments round-trip unchanged (encodeDefaults
     * = false omits nulls). Populated at capture time so list rows can
     * show "0:42" without having to probe the file each render.
     */
    val durationMs: Long? = null,
    /**
     * Recognized text from ML Kit Text Recognition v2, only set for
     * scans where OCR produced a non-empty result. Nullable so existing
     * photo/scan/voice attachments round-trip unchanged. Populated once
     * at scan-save time — we don't re-run inference on view.
     */
    val recognizedText: String? = null,
    /**
     * Speech-to-text transcript, only set for voice notes where
     * recognition produced a non-empty result. Nullable so existing
     * photo/scan/voice attachments round-trip unchanged. Populated once
     * at capture time from `SpeechRecognizer` running alongside the
     * mic — we don't re-run recognition on view.
     */
    val transcript: String? = null,
) {
    companion object {
        const val TYPE_PHOTO = "photo"
        const val TYPE_SCAN  = "scan"
        const val TYPE_VOICE = "voice"
    }
}

@JvmName("attachmentsToJsonString")
fun List<Attachment>.toJsonString(): String = jsonCodec.encodeToString(this)
fun String.parseAttachments(): List<Attachment> = parseList(this)

// -------------------------- Stroke (freehand drawing) --------------------------

/**
 * A single freehand stroke on a page's drawing overlay. Points are in
 * the scroll-content coordinate space of the notes body (x,y in dp)
 * so strokes stay pinned to the position where the user drew them as
 * the content scrolls. Per the v1 spec, strokes are never reflowed
 * when text edits push content around — that mismatch is accepted.
 *
 * `color` is a 32-bit ARGB value (alpha channel baked in via the opacity
 * slider), matching Compose `Color.toArgb()`. `nib` drives the render
 * style (uniform ballpoint, velocity-variable fountain, semi-transparent
 * highlighter). Erase is a list operation — we remove whole strokes
 * that the eraser path intersects rather than modelling erase strokes
 * in the data.
 */
@Serializable
data class Stroke(
    val id: String,
    /** Packed as `[x0, y0, x1, y1, ...]` in dp — compacter than a point list. */
    val points: List<Float>,
    /** ARGB int, already multiplied by the opacity slider. */
    val color: Int,
    /** Width in dp (pre-nib). Fountain nib modulates this at render time. */
    val width: Float,
    /** "ballpoint" | "fountain" | "highlighter". */
    val nib: String,
) {
    companion object {
        const val NIB_BALLPOINT  = "ballpoint"
        const val NIB_FOUNTAIN   = "fountain"
        const val NIB_HIGHLIGHTER = "highlighter"
    }
}

@JvmName("strokesToJsonString")
fun List<Stroke>.toJsonString(): String = jsonCodec.encodeToString(this)
fun String.parseStrokes(): List<Stroke> = parseList(this)

// -------------------------- Shared parse helper --------------------------

/**
 * Defensive JSON list parser — malformed payloads return an empty list
 * instead of crashing the editor. The schema's string default is `'[]'`
 * so a missing column also round-trips cleanly.
 */
private inline fun <reified T> parseList(json: String): List<T> {
    if (json.isBlank()) return emptyList()
    return try {
        jsonCodec.decodeFromString(json)
    } catch (_: SerializationException) {
        emptyList()
    } catch (_: IllegalArgumentException) {
        emptyList()
    }
}
