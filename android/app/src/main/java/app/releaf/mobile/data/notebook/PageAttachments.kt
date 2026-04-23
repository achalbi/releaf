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
    /** Legacy field — never surfaced in the current UI, kept for back-
     *  compat with any on-disk data from earlier builds. New captures
     *  use [title] instead. */
    val role: String? = null,
    /** Mobile number in `+CC NNNNNNNNNN` form — validated by the capture
     *  sheet as exactly 10 local digits before the `+91` dial-code is
     *  prepended on commit. Free-form strings from older builds still
     *  round-trip because the data layer doesn't re-validate. */
    val phone: String? = null,
    /** Landline in `+CC NNNNNNNNNN` form. Same 10-digit validation as
     *  [phone] — captured as a separate field so call/add-to-contacts
     *  intents can surface both numbers without collapsing them into
     *  one. Null when unset. */
    val landline: String? = null,
    /** RFC-5322-ish email — the capture sheet validates against the
     *  platform `Patterns.EMAIL_ADDRESS` regex before allowing save,
     *  but the data layer itself stores anything so old rows survive. */
    val email: String? = null,
    /** Job title, e.g. "Product Designer". Shown as secondary text
     *  under the contact's name on the card. */
    val title: String? = null,
    /** Company / org, e.g. "Releaf". Shown as tertiary text under
     *  [title] on the card. */
    val organization: String? = null,
    /** Free-form city / region / address line, e.g. "San Francisco, CA".
     *  Not geocoded — this is a label, not a pinned coordinate. */
    val location: String? = null,
    /** Personal or company URL — rendered as a tappable row that
     *  fires `Intent.ACTION_VIEW` on the parsed URI. Stored as-entered
     *  (http/https is auto-prefixed on launch if the user left it off). */
    val website: String? = null,
)

fun List<Contact>.toJsonString(): String = jsonCodec.encodeToString(this)
fun String.parseContacts(): List<Contact> = parseList(this)

// -------------------------- Todo --------------------------

@Serializable
data class TodoItem(
    val id: String,
    val text: String,
    val done: Boolean = false,
    /**
     * Priority level: 0 = none, 1 = low, 2 = medium, 3 = high.
     * Rendered as a 3-dot picker on the todo row — the dot
     * corresponding to the current level is tinted; the others
     * stay grey. Default 0 so existing rows round-trip unchanged.
     */
    val priority: Int = 0,
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
     * photo/scan/voice attachments round-trip unchanged. Populated on
     * the user's explicit "Transcribe" tap on the card — we don't
     * re-run recognition on view.
     */
    val transcript: String? = null,
    /**
     * Which engine produced `transcript`. Either `"mlkit"` (ML Kit
     * GenAI Speech Recognition / Gemini Nano) or `"sherpa"` (sherpa-onnx
     * with the Whisper model). The UI reads this to label the "Try
     * again with <other engine>" affordance — since both engines are
     * deterministic on the same audio, re-running with the same engine
     * is pointless, but swapping engines is the recourse when the
     * transcript is inaccurate.
     */
    val transcriptSource: String? = null,
    /**
     * User-overridden title for a scan. When non-null it wins over
     * the derived title (which reads the first line of OCR text).
     * Only meaningful for [TYPE_SCAN]; other types ignore it.
     */
    val title: String? = null,
    /**
     * User-overridden category id for a scan. String so it serializes
     * cleanly alongside the other JSON fields. Matches
     * `ScanCategory.name` (e.g. "GENERAL", "TODO", "DAILY"). Null =
     * use the derived classification from OCR text.
     */
    val categoryId: String? = null,
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

// -------------------------- TextBox (free-form text) --------------------------

/**
 * A free-form text box placed on a sub-page at the position the user
 * tapped while Text mode was active. Coords are in dp, relative to the
 * sub-page's card origin — same coordinate space as [Stroke.points],
 * so text boxes stay pinned alongside strokes as the scroll container
 * moves.
 *
 * Color is an ARGB int (the pen palette swatch × current opacity at
 * creation time), matching [Stroke.color]. Font size stores the sp
 * preset chosen on the thickness toggle (S=14 / M=18 / L=24).
 */
@Serializable
data class TextBox(
    val id: String,
    val xDp: Float,
    val yDp: Float,
    val text: String = "",
    /** ARGB int, already multiplied by the opacity slider at creation. */
    val color: Int,
    /** Font size in sp. */
    val fontSp: Float = 18f,
)

@JvmName("textBoxesToJsonString")
fun List<TextBox>.toJsonString(): String = jsonCodec.encodeToString(this)
fun String.parseTextBoxes(): List<TextBox> = parseList(this)

// -------------------------- LedgerEntry (ruled-page ledger) --------------------------

/**
 * One row in a sub-page's ledger (shown when `SubPage.background ==
 * BG_RULED`). Two user-visible fields — a free-form [label] on the
 * left and a numeric [amount] on the right — plus a stable [id] so
 * Compose list keys stay consistent as the user types.
 *
 * [amount] is nullable on purpose: we want to distinguish "row exists
 * but user hasn't entered a number" from "row's number is zero" so a
 * blank row doesn't pull the total back to 0 in confusing ways.
 */
@Serializable
data class LedgerEntry(
    val id: String,
    val label: String = "",
    val amount: Double? = null,
    /** `true` = money out (expense), `false` = money in (earning).
     *  The total row in the ledger editor subtracts expenses and adds
     *  earnings, so existing rows without this field are treated as
     *  earnings — that matches the visual "+" default the toggle
     *  shows when a new row is inserted. */
    val isExpense: Boolean = false,
)

@JvmName("ledgerEntriesToJsonString")
fun List<LedgerEntry>.toJsonString(): String = jsonCodec.encodeToString(this)
fun String.parseLedgerEntries(): List<LedgerEntry> = parseList(this)

// -------------------------- SubPage (horizontal pager) --------------------------

/**
 * One sub-page inside a notebook page (or notepad entry). Pages can now
 * hold a list of SubPages that the user swipes between horizontally —
 * each owns its own notes body + freehand strokes, while the parent row
 * still owns the side-channel lists (photos, contacts, todos, etc).
 *
 * Persisted as a JSON array in the `sub_pages` column. The VM lazy-
 * migrates legacy rows (where the parent's `notes` / `sketch_strokes`
 * columns carry the content and `sub_pages` is empty) into a single
 * sub-page on first load.
 */
@Serializable
data class SubPage(
    val id: String,
    /** Canonical CommonMark. Empty string is valid. */
    val notes: String = "",
    /** Freehand strokes overlaid on this sub-page's body. */
    val strokes: List<Stroke> = emptyList(),
    /**
     * Free-form text boxes the user dropped onto the sub-page in Text
     * mode. Each one carries its own position, text, color, and font
     * size — see [TextBox]. Rendered above the background / rich-text
     * editor and below the stroke layer, so strokes can annotate over
     * text without the text getting re-wrapped.
     */
    val textBoxes: List<TextBox> = emptyList(),
    /**
     * Background pattern id — one of [BG_PLAIN], [BG_GRID], [BG_DOTS],
     * [BG_LINES], [BG_RULED]. Rendered behind the text + strokes by
     * `NotesBackground`. Default is plain so existing sub-pages keep
     * their current look.
     */
    val background: String = BG_PLAIN,
    /**
     * Multiplier on the background pattern's base spacing (24dp).
     * 0.5..2.0 range in the picker. Only affects the pattern — text
     * and strokes are unaffected (per the v1 spec).
     */
    val bgScale: Float = 1.0f,
    /**
     * Optional `file://` URI pointing at an image (JPG/PNG) rendered
     * behind text + strokes instead of the pattern. Populated by the
     * "Import PDF page to notes" flow — lets the user mark up a
     * scanned-document page with the pen/highlighter. When non-null,
     * `background` / `bgScale` are ignored.
     */
    val backgroundImageUri: String? = null,
    /**
     * Ledger rows shown when [background] is [BG_RULED]. Each entry
     * pairs a free-form label with an optional numeric amount; the
     * editor auto-sums the amounts into a live total field at the
     * bottom of the page. Empty on any other background — the list is
     * preserved through background swaps so a user can flip Ruled →
     * Plain → Ruled without losing their rows.
     */
    val ledgerEntries: List<LedgerEntry> = emptyList(),
) {
    companion object {
        const val BG_PLAIN = "plain"
        const val BG_GRID  = "grid"
        const val BG_DOTS  = "dots"
        const val BG_LINES = "lines"
        /** Ledger mode: the page body is replaced by a two-column
         *  entries form (label + amount) with a live total at the
         *  bottom. No ruled paper / margin line — the editor renders
         *  its own framing. */
        const val BG_RULED = "ruled"
    }
}

@JvmName("subPagesToJsonString")
fun List<SubPage>.toJsonString(): String = jsonCodec.encodeToString(this)
fun String.parseSubPages(): List<SubPage> = parseList(this)

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
