/*
 * CaptureMode.kt
 *
 * Which capture surface QuickInk shows behind the shutter. Two
 * surfaces, picked by an inline pill toggle on QuickCaptureScreen:
 *
 *   - DOCUMENT       → ML Kit `GmsDocumentScanning` system intent
 *                      (unchanged behavior; runs in Google's UI).
 *   - BUSINESS_CARD  → in-app CameraX preview with a card-shaped
 *                      guide overlay and a custom OpenCV-backed
 *                      detector that auto-captures on a stable
 *                      quad.
 *
 * The toggle is persisted under the SharedPreferences key
 * `quickink.capture.last_mode` (see [CaptureModePreference]) so
 * the next session opens on whatever the user used last.
 * First-launch fallback is DOCUMENT — that's the established
 * capture path; landing card-first would surprise users who came
 * here for document scanning.
 *
 * Why not a single shared CameraController across both modes:
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
    BusinessCard;

    val analyticsKey: String
        get() = when (this) {
            Document     -> "document"
            BusinessCard -> "business_card"
        }

    val pillLabel: String
        get() = when (this) {
            Document     -> "Document"
            BusinessCard -> "Business Card"
        }

    /**
     * Maps the capture surface to the paper-size class persisted on
     * the capture row. The sustainability hero reads `paper_size` to
     * weight each page (card +4, A4 +2, smaller +1). Document mode
     * covers ML Kit's standard rectangular-document path; we assume
     * A4 / Letter rather than trying to infer dimensions from the
     * captured pixels.
     */
    val paperSize: PaperSize
        get() = when (this) {
            Document     -> PaperSize.A4
            BusinessCard -> PaperSize.Card
        }

    companion object {
        fun fromAnalyticsKey(key: String?): CaptureMode = when (key) {
            "business_card" -> BusinessCard
            else            -> Document
        }
    }
}

/**
 * Coarse page-size class persisted on each capture row. Three buckets:
 *   - [Card]  — business-card scans. +4 pts/page in the tree score.
 *   - [A4]    — A4 / Letter documents (default). +2 pts/page.
 *   - [Small] — smaller-than-A4 imports. +1 pt/page. Reserved for a
 *               future PDF-from-Files import path that can read
 *               MediaBox dimensions; no current code path writes it.
 *
 * The [raw] string round-trips through the SQLite `paper_size`
 * column (TEXT NOT NULL DEFAULT 'a4') without a separate codec.
 */
enum class PaperSize(val raw: String) {
    Card("card"),
    A4("a4"),
    Small("small");

    companion object {
        fun fromRaw(value: String?): PaperSize = when (value) {
            "card"  -> Card
            "small" -> Small
            else    -> A4
        }
    }
}
