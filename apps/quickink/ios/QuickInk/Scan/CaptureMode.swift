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

/// Coarse page-size class persisted on each capture row. Three buckets:
///   - `.card`  — business-card scans. +4 pts/page in the tree score.
///   - `.a4`    — A4 / Letter documents (default). +2 pts/page.
///   - `.small` — smaller-than-A4 imports. +1 pt/page. Reserved for
///                a future PDF-from-Files import path that can read
///                MediaBox dimensions; no current code path writes it.
///
/// String-backed so it round-trips through the SQLite `paper_size`
/// column (TEXT NOT NULL DEFAULT 'a4') without a separate codec.
public enum PaperSize: String, CaseIterable, Sendable {
    case card
    case a4
    case small
}

