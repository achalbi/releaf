/*
 * CaptureMode.swift
 *
 * Which capture surface QuickInk shows behind the shutter on
 * iOS. Two surfaces, picked by an inline pill toggle on
 * QuickCaptureScreen:
 *
 *   - .document       → VisionKit's `VNDocumentCameraViewController`
 *                       (unchanged behavior; runs in Apple's UI).
 *   - .businessCard   → in-app `AVCaptureSession` preview with a
 *                       card-shaped guide overlay and a custom
 *                       detector that auto-captures on a stable
 *                       quad.
 *
 * The toggle is persisted under the UserDefaults key
 * `quickink.capture.last_mode` (see [SettingsState.lastCaptureMode])
 * so the next session opens on whatever the user used last.
 * First-launch fallback is `.document` — that's the established
 * capture path.
 *
 * Why not a single shared `AVCaptureSession` across both modes:
 * Document mode runs inside VisionKit's
 * `VNDocumentCameraViewController`, which owns its own
 * `AVCaptureSession` internally. We can't reach into it from
 * the host, so the mode toggle swaps surfaces rather than
 * swapping detectors against a shared session.
 *
 * Mirror of Android `CaptureMode.kt`.
 */

import Foundation

public enum CaptureMode: String, CaseIterable, Sendable {
    case document
    case businessCard

    public var analyticsKey: String {
        switch self {
        case .document:     return "document"
        case .businessCard: return "business_card"
        }
    }

    public var pillLabel: String {
        switch self {
        case .document:     return "Document"
        case .businessCard: return "Business Card"
        }
    }

    public static func fromAnalyticsKey(_ key: String?) -> CaptureMode {
        switch key {
        case "business_card": return .businessCard
        default:              return .document
        }
    }

    /// Maps the capture surface to the paper-size class persisted on
    /// the capture row. The sustainability hero reads `paper_size` to
    /// weight each page (card +4, A4 +2, smaller +1). Document mode
    /// covers VisionKit's standard rectangular-document path; we
    /// assume A4 / Letter rather than trying to infer dimensions
    /// from the captured pixels.
    public var paperSize: PaperSize {
        switch self {
        case .document:     return .a4
        case .businessCard: return .card
        }
    }
}

/// Coarse page-size class persisted on each capture row. Five buckets:
///   - `.card`   — business cards / ID cards. +0.4 pts/page.
///   - `.a4`     — A4 documents (also the default A-series fallback).
///                 +0.2 pts/page.
///   - `.a5`     — A5 documents (half the paper of A4). +0.1 pts/page.
///                 Distinguished from `.a4` only via the user-pick chip
///                 on `ScanReviewScreen` — aspect ratio alone can't
///                 tell them apart (both are 1:√2 by design).
///   - `.letter` — US Letter / Legal documents. +0.2 pts/page (≈ A4).
///   - `.custom` — Anything that didn't match a standard ratio (square
///                 photos, oversized rectangles, etc.) or that the user
///                 explicitly marked as non-standard. +0.2 pts/page.
///
/// String-backed so it round-trips through the SQLite `paper_size`
/// column (TEXT NOT NULL DEFAULT 'a4') without a separate codec.
/// Older "small" rows from the v11 schema's reserved slot decode as
/// `.a5` via the [fromRaw] init for back-compat.
public enum PaperSize: String, CaseIterable, Sendable {
    case card
    case a4
    case a5
    case letter
    case custom

    /// String → enum with the legacy `"small"` slot mapped onto `.a5`
    /// (the new home for "smaller than A4" semantics). Defaults to
    /// `.a4` for unknown or missing values so the home hero never
    /// drops a capture's pages on a typo.
    public static func fromRaw(_ raw: String?) -> PaperSize {
        switch raw {
        case "card":   return .card
        case "a4":     return .a4
        case "a5":     return .a5
        case "small":  return .a5     // legacy v11 reserved slot
        case "letter": return .letter
        case "custom": return .custom
        default:       return .a4
        }
    }
}

/// Classify the captured page's `PaperSize` from its rectified pixel
/// dimensions. VisionKit / ML Kit both warp the detected document
/// quad into a rectangle whose w:h ≈ the physical document's w:h, so
/// the ratio is reliable. Buckets:
///
///   - 1.55–1.85 → `.card`   (business cards 1.71, ID cards 1.58)
///   - 1.25–1.34 → `.letter` (US Letter 1.294)
///   - 1.38–1.45 → `.a4`     (all A-series share 1:√2 ≈ 1.414)
///   - everything else → `.custom`
///
/// A4 vs A5 is mathematically indistinguishable from aspect ratio
/// alone (by ISO 216 design — folding A4 in half preserves the ratio).
/// The user resolves that ambiguity via the chip on
/// `ScanReviewScreen`; this classifier defaults the A-series bucket
/// to `.a4` as the more common pick.
///
/// Degenerate inputs (zero or negative dimensions) fall back to
/// `.a4` — the points-system default — rather than crashing or
/// returning a misleading bucket.
public func classifyPaperSize(width: Int, height: Int) -> PaperSize {
    guard width > 0, height > 0 else { return .a4 }
    let long  = Double(max(width, height))
    let short = Double(min(width, height))
    let ratio = long / short
    switch ratio {
    case 1.55...1.85: return .card
    case 1.38...1.45: return .a4
    case 1.25...1.34: return .letter
    default:          return .custom
    }
}

