/*
 * CaptureMode.kt
 *
 * Which capture surface QuickInk shows behind the shutter. Four
 * surfaces:
 *
 *   - DOCUMENT       → ML Kit `GmsDocumentScanning` system intent
 *                      (unchanged behavior; runs in Google's UI).
 *   - BUSINESS_CARD  → in-app CameraX preview with a card-shaped
 *                      guide overlay and a custom OpenCV-backed
 *                      detector that auto-captures on a stable
 *                      quad. Retired from the public picker (the
 *                      Sundial radial menu) but kept in the enum
 *                      so legacy persisted values still decode.
 *   - PHOTO          → in-app CameraX preview with a plain manual
 *                      shutter. No quad detection, no overlay —
 *                      a single still that lands in the same scan
 *                      pipeline tagged `source="photo"` /
 *                      `paperSize=Custom`. Tap shutter → still
 *                      capture (no hold-to-record; video lives on
 *                      its own surface).
 *   - VIDEO          → in-app CameraX preview wired for
 *                      [VideoCapture] + [Recorder]. Tap shutter to
 *                      start recording, tap again to stop. 2:00
 *                      hard cap. Lands the same first-frame still
     *                      in the scan pipeline (`source="video"` /
 *                      `paperSize=Custom`) with the recorded audio
 *                      extracted as a voice note attachment.
 *
 * Both `.Photo` and `.Video` are reached only through the radial
 * Sundial menu on the bottom-nav FAB; neither lives on the legacy
 * top-bar pill (which is now a static "Scan" title since the
 * BusinessCard retirement). Keeping each capture intent on its own
 * surface means the gesture stays a single explicit tap — no more
 * tap-vs-hold ambiguity.
 *
 * The pill-selected mode is persisted under the SharedPreferences
 * key `quickink.capture.last_mode` (see [SettingsPreferences]) so
 * the next session opens on whatever the user used last.
 * First-launch fallback is DOCUMENT — that's the established
 * capture path. The Sundial → `.Photo` / `.Video` paths
 * deliberately do NOT persist (see `QuickCaptureScreen`):
 * transient capture surfaces shouldn't overwrite the user's last
 * pill choice.
 *
 * Why not a single shared CameraController across all modes:
 * Document mode runs inside Google's system scanner activity,
 * which owns its own camera session in a separate process. We
 * can't reach into it from the host, so the mode toggle swaps
 * surfaces rather than swapping detectors against a shared
 * preview. Mode-switch latency is dominated by the surface
 * mount/unmount, which is well under 100 ms in Compose / SwiftUI.
 *
 * Mirror of iOS `CaptureMode.swift`.
 */

package app.quickink.mobile.features.scan

enum class CaptureMode {
    Document,
    BusinessCard,
    Photo,
    Video;

    val analyticsKey: String
        get() = when (this) {
            Document     -> "document"
            BusinessCard -> "business_card"
            Photo        -> "photo"
            Video        -> "video"
        }

    val pillLabel: String
        get() = when (this) {
            Document     -> "Document"
            BusinessCard -> "Business Card"
            Photo        -> "Photo"
            Video        -> "Video"
        }

    /**
     * Maps the capture surface to the paper-size class persisted on
     * the capture row. The sustainability hero reads `paper_size` to
     * weight each page (card +4, A4 +2, smaller +1). Document mode
     * covers ML Kit's standard rectangular-document path; we assume
     * A4 / Letter rather than trying to infer dimensions from the
     * captured pixels. Photo / Video mode pass [PaperSize.Custom] —
     * an arbitrary phone-camera frame's aspect ratio is meaningless
     * against the A4 / Letter / card ratio bands, and [Custom]
     * shares A4's +0.2 pts/page weight so the sustainability hero
     * still scores the page.
     */
    val paperSize: PaperSize
        get() = when (this) {
            Document     -> PaperSize.A4
            BusinessCard -> PaperSize.Card
            Photo        -> PaperSize.Custom
            Video        -> PaperSize.Custom
        }

    companion object {
        fun fromAnalyticsKey(key: String?): CaptureMode = when (key) {
            "business_card" -> BusinessCard
            "photo"         -> Photo
            "video"         -> Video
            else            -> Document
        }
    }
}

/**
 * Coarse page-size class persisted on each capture row. Five buckets:
 *   - [Card]   — business cards / ID cards. +0.4 pts/page.
 *   - [A4]     — A4 documents (also the default A-series fallback).
 *                +0.2 pts/page.
 *   - [A5]     — A5 documents (half the paper of A4). +0.1 pts/page.
 *                Distinguished from [A4] only via the user-pick chip
 *                on ScanReviewScreen — aspect ratio alone can't tell
 *                them apart (both are 1:√2 by design).
 *   - [Letter] — US Letter / Legal documents. +0.2 pts/page (≈ A4).
 *   - [Custom] — Anything that didn't match a standard ratio (square
 *                photos, oversized rectangles, etc.) or that the user
 *                explicitly marked as non-standard. +0.2 pts/page.
 *
 * The [raw] string round-trips through the SQLite `paper_size`
 * column (TEXT NOT NULL DEFAULT 'a4') without a separate codec.
 * Older "small" rows from the v13 schema's reserved slot decode as
 * [A5] via [fromRaw] for back-compat.
 */
enum class PaperSize(val raw: String) {
    Card("card"),
    A4("a4"),
    A5("a5"),
    Letter("letter"),
    Custom("custom");

    companion object {
        /**
         * String → enum with the legacy `"small"` slot mapped onto
         * [A5] (the new home for "smaller than A4" semantics).
         * Defaults to [A4] for unknown / missing values so the home
         * hero never drops a capture's pages on a typo.
         */
        fun fromRaw(value: String?): PaperSize = when (value) {
            "card"   -> Card
            "a4"     -> A4
            "a5"     -> A5
            "small"  -> A5      // legacy v13 reserved slot
            "letter" -> Letter
            "custom" -> Custom
            else     -> A4
        }
    }
}

/**
 * Classify the captured page's [PaperSize] from its rectified pixel
 * dimensions. VisionKit / ML Kit both warp the detected document
 * quad into a rectangle whose w:h ≈ the physical document's w:h,
 * so the ratio is reliable. Buckets:
 *
 *   - 1.55–1.85 → [PaperSize.Card]   (business cards 1.71, IDs 1.58)
 *   - 1.25–1.34 → [PaperSize.Letter] (US Letter 1.294)
 *   - 1.38–1.45 → [PaperSize.A4]     (all A-series share 1:√2 ≈ 1.414)
 *   - everything else → [PaperSize.Custom]
 *
 * A4 vs A5 is mathematically indistinguishable from aspect ratio
 * alone (by ISO 216 design — folding A4 in half preserves the
 * ratio). The user resolves that ambiguity via the chip on
 * ScanReviewScreen; this classifier defaults the A-series bucket
 * to [PaperSize.A4] as the more common pick.
 *
 * Degenerate inputs (zero or negative dimensions) fall back to
 * [PaperSize.A4] — the points-system default — rather than
 * crashing or returning a misleading bucket.
 */
fun classifyPaperSize(width: Int, height: Int): PaperSize {
    if (width <= 0 || height <= 0) return PaperSize.A4
    val long  = maxOf(width, height).toDouble()
    val short = minOf(width, height).toDouble()
    val ratio = long / short
    return when {
        ratio in 1.55..1.85 -> PaperSize.Card
        ratio in 1.38..1.45 -> PaperSize.A4
        ratio in 1.25..1.34 -> PaperSize.Letter
        else                -> PaperSize.Custom
    }
}
