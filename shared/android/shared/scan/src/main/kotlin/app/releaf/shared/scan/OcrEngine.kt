/*
 * OcrEngine.kt
 *
 * Engine-agnostic OCR contract. Both apps consume the same `OcrEngine`
 * surface so a Releaf code path that runs OCR on a scanned page and a
 * QuickInk one are identical at the call site — only the bound impl
 * differs (Apple Vision on iOS, ML Kit on Android).
 *
 * Mirror of `OcrEngine.swift` in `ReleafCoreScan`. Per
 * QUICKINK_PROPOSAL.md §6.1 + §6.2:
 *
 *   - The contract returns the richer `OcrResult` payload (text +
 *     blocks + bbox + confidence + language) — *not* a flat string.
 *     Both engines flatten cleanly onto `OcrBlock`.
 *   - `OcrResult.engine` carries a stable identifier per impl
 *     (`"apple-vision"`, `"mlkit-latin-v2"`) so persisted rows in the
 *     `ocr_results` table stay traceable across schema migrations.
 *   - The full text is also mirrored into `notepad_entries.notes` by
 *     the (separate) ingest path so the existing FTS5 index picks up
 *     scanned-doc words. `ocr_results.blocks_json` is the canonical
 *     positional record; this struct is the in-memory shape that
 *     gets encoded into that column.
 *
 * What's deliberately NOT in this file (Phase 3 follow-ups):
 *   - Concrete impls (`MlKitTextRecognizer`)
 *   - Multi-page parallel pipeline (sits on top of the engine)
 *   - Per-page progress callbacks (caller owns concurrency)
 *   - Cloud-OCR engine selection (one engine per platform today; the
 *     interface shape leaves room for a future swap)
 *   - kotlinx.serialization annotations on the value types — the
 *     storage layer (which is a separate piece, gated on QuickInk's
 *     forked v1_initial.sql migration) will add the `@Serializable`
 *     decorations alongside the `ocr_results` Room entity. Keeping
 *     the contract free of serialization concerns means a swap to
 *     Moshi or a hand-rolled JSON converter is still on the table.
 */

package app.releaf.shared.scan

import android.net.Uri
import kotlinx.serialization.Serializable

/**
 * Engine-agnostic OCR contract. Implementors are expected to be safe
 * to call from any coroutine context.
 */
interface OcrEngine {

    /**
     * Recognize text in a single image.
     *
     * `imageUri` is a local `file://` Uri pointing at one scanned
     * page (a JPEG / PNG / WebP the device scanner produced, already
     * copied into `AttachmentStorage` by `DocumentScannerLauncher`'s
     * `onResult` path). Implementors load the image off the Uri.
     *
     * Returns a well-formed `OcrResult` — including for blank pages,
     * where `text` is empty and `blocks` is `[]`. Throwing is reserved
     * for engine failures (image unreadable, recognizer init failed,
     * recognition crashed mid-flight); see `OcrException` for the
     * cases.
     */
    suspend fun recognize(imageUri: Uri): OcrResult
}

/**
 * Recognized text + per-block positional data + engine metadata.
 *
 * Encoded into `ocr_results.blocks_json` per page. The flat `text`
 * field is the one mirrored into `notepad_entries.notes` for FTS5
 * search; `blocks` is the positional record that drives the
 * searchable-PDF prototype's invisible text layer (and any future
 * "tap word to highlight" UX).
 */
@Serializable
data class OcrResult(
    /**
     * Full recognized text. Paragraph breaks preserved as `\n\n`;
     * line breaks preserved as `\n`. Empty string when the page had
     * no detectable text.
     */
    val text: String,

    /**
     * Canonical positional record. One element per recognized line
     * (or paragraph / word, depending on the engine and `kind`); see
     * `OcrBlock` for the per-block shape. Empty when no text.
     */
    val blocks: List<OcrBlock>,

    /**
     * BCP-47 mean across blocks (e.g. `"en-US"`). Null when the
     * engine didn't classify, or when the page had no text.
     */
    val language: String?,

    /**
     * Mean confidence across blocks, 0.0–1.0. Null when no engine
     * in the call path exposed per-block confidence (ML Kit's
     * Play-Services variant doesn't), or when the page had no text.
     * Apple Vision's per-observation confidence is always populated
     * on iOS, so the iOS impl emits a real value here for non-empty
     * pages.
     */
    val confidence: Double?,

    /**
     * Stable identifier for the engine that produced this result.
     * Today: `"apple-vision"` or `"mlkit-latin-v2"`. Persisted on the
     * row so future-us can re-OCR with a newer engine and tell which
     * rows came from which version.
     */
    val engine: String,

    /**
     * Engine version string. Optional because not every engine
     * exposes a stable version handle.
     */
    val engineVersion: String?,
)

/**
 * One recognized region — line, paragraph, or word — with its
 * bounding box and confidence.
 */
@Serializable
data class OcrBlock(
    /**
     * The recognized text, with line breaks within multi-line
     * paragraphs preserved as `\n`.
     */
    val text: String,

    /**
     * Normalized bounding box in image space, 0..1, origin top-left.
     * Implementors are responsible for normalizing — Apple Vision's
     * API gives lower-left origin, ML Kit gives pixel rects; both
     * land here in the same normalized top-left frame.
     */
    val bbox: OcrBbox,

    /**
     * 0.0–1.0 confidence for this block. Null when the engine
     * doesn't expose per-block confidence — ML Kit's Play-Services
     * variant 19.0.1 doesn't expose it on `Text.TextBlock` or
     * `Text.Line` at all, so the Android impl always sets null here.
     * Apple Vision exposes a per-observation confidence on every
     * recognized text observation, so the iOS impl always sets a
     * real value.
     */
    val confidence: Double?,

    /**
     * BCP-47 language for this block. Often the same as
     * `OcrResult.language`, but engines that mix scripts may emit
     * per-block classifications.
     */
    val language: String?,

    val kind: Kind,
) {
    /**
     * Whether the block represents a line, a paragraph, or a single
     * word. Apple Vision is line-grained; ML Kit emits paragraph +
     * line tiers. Stored on the block so a downstream renderer can
     * pick the right granularity for its overlay.
     *
     * The Kotlin convention — and Releaf's existing convention for
     * sibling enums (`CaptureMode.Overview` etc.) — is PascalCase
     * symbol names. iOS's `OcrBlock.Kind` cases are lowercase per
     * Swift convention. When the storage layer adds `@SerialName`
     * decoration to align with the iOS-side encoded form, the wire
     * names become lowercase (`"line"` / `"paragraph"` / `"word"`)
     * on both platforms — matters for cross-platform readers of
     * `ocr_results.blocks_json` exported via Drive sync.
     */
    @Serializable
    enum class Kind { Line, Paragraph, Word }
}

/** Normalized bounding box, 0..1 in image space, origin top-left. */
@Serializable
data class OcrBbox(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
)

/**
 * Failure modes the contract surfaces. Pipeline callers usually
 * translate each case into a different user-facing message ("can't
 * read this page", "scanner unavailable", etc.); each impl maps its
 * platform-side error onto the closest subclass.
 */
sealed class OcrException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /**
     * The image at `uri` couldn't be loaded (missing, unsupported
     * format, corrupt bytes).
     */
    class ImageUnreadable(val uri: Uri, cause: Throwable? = null)
        : OcrException("Image at $uri couldn't be loaded", cause)

    /**
     * The recognizer couldn't initialize. `message` carries the
     * platform-side reason for diagnostics — surface verbatim in
     * debug builds; in production prefer a generic "scanner
     * unavailable" copy.
     */
    class RecognizerInitFailed(message: String, cause: Throwable? = null)
        : OcrException("Recognizer init failed: $message", cause)

    /**
     * Recognition failed mid-flight — engine threw, returned an
     * inconsistent payload, or timed out. `message` carries the
     * platform-side reason; pipeline callers usually retry once
     * before surfacing a "couldn't read this page" toast.
     */
    class RecognitionFailed(message: String, cause: Throwable? = null)
        : OcrException("Recognition failed: $message", cause)
}
