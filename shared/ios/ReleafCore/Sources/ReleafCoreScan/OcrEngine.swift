/*
 * OcrEngine.swift
 *
 * Engine-agnostic OCR contract. Both apps consume the same `OcrEngine`
 * surface so a Releaf code path that runs OCR on a scanned page and a
 * QuickInk one are identical at the call site — only the bound impl
 * differs (Apple Vision on iOS, ML Kit on Android).
 *
 * Mirror of `OcrEngine.kt` in `:shared:scan`. Per QUICKINK_PROPOSAL.md
 * §6.1 + §6.2:
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
 *   - Concrete impls (`VisionTextRecognizer`)
 *   - Multi-page parallel pipeline (sits on top of the engine)
 *   - Per-page progress callbacks (caller owns concurrency)
 *   - Cloud-OCR engine selection (one engine per platform today; the
 *     protocol shape leaves room for a future swap)
 */

import Foundation

/// Engine-agnostic OCR contract. Implementors are expected to be safe
/// to call from any actor; the protocol's `Sendable` conformance is
/// load-bearing for that.
public protocol OcrEngine: Sendable {

    /// Recognize text in a single image.
    ///
    /// `imageURL` is a local `file://` URL pointing at one scanned
    /// page (a JPEG / PNG / HEIC the device scanner produced, already
    /// copied into `AttachmentStorage` by `DocumentScannerView`'s
    /// success path). Implementors load the image off the URL.
    ///
    /// Returns a well-formed `OcrResult` — including for blank pages,
    /// where `text` is empty and `blocks` is `[]`. Throwing is reserved
    /// for engine failures (image unreadable, recognizer init failed,
    /// recognition crashed mid-flight); see `OcrError` for the cases.
    func recognize(imageURL: URL) async throws -> OcrResult
}

/// Recognized text + per-block positional data + engine metadata.
///
/// Encoded into `ocr_results.blocks_json` per page. The flat `text`
/// field is the one mirrored into `notepad_entries.notes` for FTS5
/// search; `blocks` is the positional record that drives the
/// searchable-PDF prototype's invisible text layer (and any future
/// "tap word to highlight" UX).
public struct OcrResult: Codable, Equatable, Sendable {

    /// Full recognized text. Paragraph breaks preserved as `\n\n`;
    /// line breaks preserved as `\n`. Empty string when the page had
    /// no detectable text.
    public let text: String

    /// Canonical positional record. One element per recognized line
    /// (or paragraph / word, depending on the engine and `kind`); see
    /// `OcrBlock` for the per-block shape. Empty when no text.
    public let blocks: [OcrBlock]

    /// BCP-47 mean across blocks (e.g. `"en-US"`). Nil when the
    /// engine didn't classify, or when the page had no text.
    public let language: String?

    /// Mean confidence across blocks, 0.0–1.0. Nil when no engine
    /// in the call path exposed per-block confidence (ML Kit's
    /// Play-Services variant doesn't), or when the page had no
    /// text. Apple Vision's `VNRecognizedText.confidence` is always
    /// populated on iOS, so the iOS impl emits a real value here
    /// for non-empty pages.
    public let confidence: Double?

    /// Stable identifier for the engine that produced this result.
    /// Today: `"apple-vision"` or `"mlkit-latin-v2"`. Persisted on the
    /// row so future-us can re-OCR with a newer engine and tell which
    /// rows came from which version.
    public let engine: String

    /// Engine version string (e.g. `"VNRecognizeTextRequest.revision3"`
    /// for Apple Vision). Optional because not every engine exposes a
    /// stable version handle.
    public let engineVersion: String?

    public init(
        text: String,
        blocks: [OcrBlock],
        language: String?,
        confidence: Double?,
        engine: String,
        engineVersion: String?
    ) {
        self.text = text
        self.blocks = blocks
        self.language = language
        self.confidence = confidence
        self.engine = engine
        self.engineVersion = engineVersion
    }
}

/// One recognized region — line, paragraph, or word — with its
/// bounding box and confidence.
public struct OcrBlock: Codable, Equatable, Sendable {

    /// Whether the block represents a line, a paragraph, or a single
    /// word. Apple Vision is line-grained; ML Kit emits paragraph +
    /// line tiers. Stored on the block so a downstream renderer can
    /// pick the right granularity for its overlay.
    public enum Kind: String, Codable, Sendable {
        case line
        case paragraph
        case word
    }

    /// The recognized text, with line breaks within multi-line
    /// paragraphs preserved as `\n`.
    public let text: String

    /// Normalized bounding box in image space, 0..1, origin top-left.
    /// Implementors are responsible for normalizing — Apple Vision's
    /// API gives lower-left origin, ML Kit gives pixel rects; both
    /// land here in the same normalized top-left frame.
    public let bbox: OcrBbox

    /// 0.0–1.0 confidence for this block. Nil when the engine
    /// doesn't expose per-block confidence — ML Kit's Play-Services
    /// variant 19.0.1 doesn't expose it on `Text.TextBlock` or
    /// `Text.Line` at all, so the Android impl always sets nil here.
    /// Apple Vision exposes `VNRecognizedText.confidence` on every
    /// observation, so the iOS impl always sets a real value.
    public let confidence: Double?

    /// BCP-47 language for this block. Often the same as
    /// `OcrResult.language`, but engines that mix scripts may emit
    /// per-block classifications.
    public let language: String?

    public let kind: Kind

    public init(
        text: String,
        bbox: OcrBbox,
        confidence: Double?,
        language: String?,
        kind: Kind
    ) {
        self.text = text
        self.bbox = bbox
        self.confidence = confidence
        self.language = language
        self.kind = kind
    }
}

/// Normalized bounding box, 0..1 in image space, origin top-left.
public struct OcrBbox: Codable, Equatable, Sendable {
    public let x: Double
    public let y: Double
    public let width: Double
    public let height: Double

    public init(x: Double, y: Double, width: Double, height: Double) {
        self.x = x
        self.y = y
        self.width = width
        self.height = height
    }
}

/// Failure modes the contract surfaces. Pipeline callers usually
/// translate each case into a different user-facing message ("can't
/// read this page", "scanner unavailable", etc.); each impl maps its
/// platform-side error onto the closest case.
public enum OcrError: Error {

    /// The file at `url` couldn't be loaded as an image (missing,
    /// unsupported format, corrupt bytes).
    case imageUnreadable(URL)

    /// The recognizer couldn't initialize. `message` carries the
    /// platform-side reason for diagnostics — surface verbatim in
    /// debug builds; in production prefer a generic "scanner
    /// unavailable" copy.
    case recognizerInitFailed(message: String)

    /// Recognition failed mid-flight — engine threw, returned an
    /// inconsistent payload, or timed out. `message` carries the
    /// platform-side reason; pipeline callers usually retry once
    /// before surfacing a "couldn't read this page" toast.
    case recognitionFailed(message: String)
}
