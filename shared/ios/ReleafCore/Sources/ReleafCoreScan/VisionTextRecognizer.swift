/*
 * VisionTextRecognizer.swift
 *
 * Apple Vision-backed `OcrEngine` impl. Wraps `VNRecognizeTextRequest`
 * onto the engine-agnostic surface defined in `OcrEngine.swift`.
 *
 * Recognition posture:
 *   - `.accurate` recognition level (vs `.fast`) — document OCR isn't
 *     latency-critical, and the accuracy gap between the two is wide
 *     enough to matter for handwritten or low-contrast pages.
 *   - `usesLanguageCorrection = true` — Vision's language model
 *     corrects common OCR confusions ("rn"→"m", "1"↔"l", etc.).
 *   - `automaticallyDetectsLanguage = true` (iOS 16+) — Vision picks
 *     the recognition language per page; we don't constrain
 *     `recognitionLanguages`. If a user later needs a forced
 *     language, that becomes a constructor parameter.
 *   - Revision 3 (iOS 16+) — current best engine. Pinned explicitly
 *     so accuracy doesn't regress when Apple ships a newer revision
 *     with different defaults.
 *
 * Coordinate-system bridge:
 *   Vision's `boundingBox` is normalized 0..1 with origin
 *   BOTTOM-LEFT. The `OcrEngine` contract specifies origin TOP-LEFT.
 *   We flip Y here so the rest of the system never has to think
 *   about Vision's quirk — see `normalizedTopLeftBbox`.
 *
 * Granularity:
 *   `VNRecognizedTextObservation` is line-level; we emit one
 *   `OcrBlock(kind: .line)` per observation. Vision doesn't expose a
 *   paragraph tier; the searchable-PDF prototype's invisible text
 *   layer renders fine off line-grained boxes.
 *
 * Concurrency:
 *   `VNImageRequestHandler.perform` is synchronous and CPU-bound. We
 *   push it off the calling actor via `Task.detached(priority: .userInitiated)`
 *   so OCR runs on a background QoS while the caller can stay on
 *   `@MainActor` / a SwiftUI view body / wherever they are.
 *
 * Why this file is `#if os(iOS)`-guarded:
 *   Vision's `automaticallyDetectsLanguage` is iOS 16+ / macOS 13+,
 *   but the package's macOS floor (.v10_15) is set purely for
 *   resolver appeasement (see `apps/releaf/ios/Package.swift`'s
 *   header note) — nothing actually runs on macOS. A `#else` stub
 *   that throws at runtime would just be ceremony; an honest
 *   compile-time absence is cleaner.
 */

#if os(iOS)

import Foundation
import Vision

public struct VisionTextRecognizer: OcrEngine {

    public init() {}

    public func recognize(imageURL: URL) async throws -> OcrResult {
        // Detached so Vision runs off the calling actor. The closure
        // captures only `imageURL` (URL is Sendable) and returns
        // `OcrResult` (also Sendable per the contract); strict-
        // concurrency-safe by construction.
        try await Task.detached(priority: .userInitiated) {
            try Self.runRecognition(imageURL: imageURL)
        }.value
    }

    // MARK: - Internals

    private static func runRecognition(imageURL: URL) throws -> OcrResult {
        let handler = VNImageRequestHandler(url: imageURL, options: [:])
        let request = VNRecognizeTextRequest()
        request.recognitionLevel             = .accurate
        request.usesLanguageCorrection       = true
        request.automaticallyDetectsLanguage = true
        request.revision                     = VNRecognizeTextRequestRevision3

        do {
            try handler.perform([request])
        } catch {
            // Most common failure path is "file at URL doesn't exist
            // or isn't a readable image" — distinguish that from a
            // generic Vision error so pipeline callers can surface
            // the right copy. We probe via FileManager rather than
            // by inspecting Vision's error domain because Vision's
            // error codes for this case are undocumented.
            if !FileManager.default.fileExists(atPath: imageURL.path) {
                throw OcrError.imageUnreadable(imageURL)
            }
            throw OcrError.recognitionFailed(message: error.localizedDescription)
        }

        let observations = request.results ?? []
        return flatten(observations: observations)
    }

    /// Walk Vision's per-line observations into the contract's
    /// `OcrBlock` shape. Empty pages return a well-formed empty
    /// `OcrResult` (the contract says throwing is reserved for engine
    /// failures, not "no text found").
    private static func flatten(
        observations: [VNRecognizedTextObservation]
    ) -> OcrResult {
        var blocks: [OcrBlock] = []
        var lines:  [String]   = []
        var totalConfidence    = 0.0

        for observation in observations {
            // `topCandidates(1)` returns up to 1 candidate (the most
            // likely string for this region). We always take the top
            // one; observations with no candidates are skipped — rare,
            // typically only happens on malformed input.
            guard let candidate = observation.topCandidates(1).first else { continue }

            let bbox = normalizedTopLeftBbox(from: observation.boundingBox)
            blocks.append(OcrBlock(
                text:       candidate.string,
                bbox:       bbox,
                confidence: Double(candidate.confidence),
                // Vision doesn't expose a detected language per
                // observation. The request-level
                // `recognitionLanguages` after auto-detect could be
                // probed, but it's unreliable as a per-block signal.
                // Leaving nil; downstream pipelines that need a
                // language hint can derive one from `OcrResult.language`
                // once Phase-3 wiring decides what to publish there.
                language:   nil,
                kind:       .line
            ))
            lines.append(candidate.string)
            totalConfidence += Double(candidate.confidence)
        }

        // Nil for empty pages — the contract reserves `0.0` for "the
        // engine actually measured this confidence value." For
        // non-empty pages this is the mean of every block's Vision
        // confidence.
        let meanConfidence: Double? = blocks.isEmpty
            ? nil
            : totalConfidence / Double(blocks.count)

        return OcrResult(
            text:          lines.joined(separator: "\n"),
            blocks:        blocks,
            // See the per-block comment above — Vision's
            // request-level language signal isn't reliable enough to
            // populate this for now. Pipeline callers that need a
            // language tag should rely on the source document's
            // metadata instead.
            language:      nil,
            confidence:    meanConfidence,
            engine:        "apple-vision",
            engineVersion: "revision\(VNRecognizeTextRequestRevision3)"
        )
    }

    /// Vision's `boundingBox` is normalized 0..1 with origin
    /// BOTTOM-LEFT. The `OcrEngine` contract specifies origin
    /// TOP-LEFT. Flip Y: a box with bottom-left (x, y, w, h) becomes
    /// top-left (x, 1 - y - h, w, h).
    private static func normalizedTopLeftBbox(from cgRect: CGRect) -> OcrBbox {
        OcrBbox(
            x:      Double(cgRect.minX),
            y:      Double(1.0 - cgRect.maxY),
            width:  Double(cgRect.width),
            height: Double(cgRect.height)
        )
    }
}

#endif
