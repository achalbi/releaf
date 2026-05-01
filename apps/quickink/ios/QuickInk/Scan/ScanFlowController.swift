/*
 * ScanFlowController.swift
 *
 * Drives the Home → Scan → OCR review flow:
 *   1. UI calls `onScanComplete(pdfURL:previewURL:pageURLs:)`
 *      after the document scanner returns successfully.
 *   2. Controller persists a fresh `captures` row.
 *   3. Controller kicks off `OcrPipeline.recognizePages(pageURLs)`,
 *      writing each successful `OcrResult` into `ocr_results` as
 *      it arrives.
 *   4. State transitions: `.idle` → `.recognizing(progress)`
 *      → `.complete(captureId, success/total)`.
 *
 * Mirror of `ScanFlowController.kt`. Plain `ObservableObject` with
 * a manual coroutine-equivalent (`Task`) — we don't use SwiftUI's
 * Observable macro yet because the QuickInk package's iOS floor
 * (.v16) predates it.
 */

import Foundation
import ReleafCoreScan

@MainActor
public final class ScanFlowController: ObservableObject {

    public enum State: Sendable {
        case idle
        /// `completedPages` includes both successes and failures.
        case recognizing(captureId: String, totalPages: Int, completedPages: Int)
        case complete(captureId: String, totalPages: Int, successCount: Int)
        case failed(message: String)
    }

    @Published public private(set) var state: State = .idle

    private let userId: String
    private let repository: CaptureRepository
    private let pipeline: OcrPipeline
    /// Slice 4.2c — fired once at the end of each scan pass so the
    /// just-captured rows push to Drive in seconds rather than
    /// waiting for the 15-min periodic. Wired by
    /// `QuickInkRoot.MainShell` to call
    /// `QuickInkSyncEnvironment.shared.scheduler.requestImmediate()`.
    /// Defaults to a no-op for tests / previews. Fires ONCE per
    /// pass — at `.complete` or after a partial fail-path — rather
    /// than per OCR row, so a 30-page scan triggers one sync kick.
    private let onPassComplete: () -> Void
    private var activeTask: Task<Void, Never>?

    public init(
        userId: String,
        repository: CaptureRepository = CaptureRepository(),
        pipeline: OcrPipeline = OcrPipeline(engine: VisionTextRecognizer()),
        onPassComplete: @escaping () -> Void = {}
    ) {
        self.userId = userId
        self.repository = repository
        self.pipeline = pipeline
        self.onPassComplete = onPassComplete
    }

    // MARK: - Public API

    /// Called by the Home screen after `DocumentScannerView`'s
    /// `onComplete(pdfURL:previewURL:)` fires, plus the per-page
    /// JPEGs the scanner produced (passed through from the
    /// scanner's `VNDocumentCameraScan.imageOfPage`-derived
    /// JPEG list — DocumentScannerView's onComplete carries
    /// pdfURL + previewURL today; the per-page list comes from
    /// the same successful scan path).
    public func onScanComplete(
        pdfURL: URL?,
        previewURL: URL?,
        pageURLs: [URL]
    ) {
        // Cancel any previous in-flight pass before starting a new
        // one. The user could conceivably tap Scan twice in quick
        // succession; we don't try to deduplicate at the launcher
        // layer, so guard here.
        activeTask?.cancel()

        let captureId = UUID().uuidString.lowercased()
        let totalPages = pageURLs.count
        state = .recognizing(captureId: captureId, totalPages: totalPages, completedPages: 0)

        let userId = self.userId
        let repository = self.repository
        let pipeline = self.pipeline

        activeTask = Task { @MainActor [weak self] in
            // 1. Persist the parent capture so OCR row foreign keys
            //    have something to reference.
            do {
                try await repository.insertCapture(
                    id:         captureId,
                    userId:     userId,
                    title:      nil,
                    pdfURL:     pdfURL,
                    previewURL: previewURL,
                    pageCount:  totalPages
                )
            } catch {
                self?.state = .failed(message: "Couldn't save scan: \(error.localizedDescription)")
                return
            }

            // 2. Stream OCR results, persisting each one as it
            //    lands. The pipeline emits in completion order; we
            //    rely on the `ocr_results.UNIQUE (capture_id,
            //    page_index)` index to keep ordering stable for
            //    later reads.
            var successCount = 0
            var completed    = 0
            for await page in pipeline.recognizePages(pageURLs) {
                completed += 1
                switch page {
                case .success(let pageIndex, let result):
                    do {
                        try await repository.insertOcrResult(
                            captureId: captureId,
                            pageIndex: pageIndex,
                            result:    result
                        )
                        successCount += 1
                    } catch {
                        // Persistence error on a single page — log,
                        // continue. The capture row + other pages
                        // are still valid; the user gets a partial
                        // result rather than an empty capture.
                        // No structured logger yet; print is fine
                        // for dev builds.
                        print("CaptureRepository.insertOcrResult failed for page \(pageIndex): \(error)")
                    }
                case .failure(let pageIndex, let error):
                    print("OCR failed for page \(pageIndex): \(error)")
                }
                self?.state = .recognizing(
                    captureId:      captureId,
                    totalPages:     totalPages,
                    completedPages: completed
                )
            }

            self?.state = .complete(
                captureId:    captureId,
                totalPages:   totalPages,
                successCount: successCount
            )

            // Slice 4.2c — kick an immediate sync so the just-
            // captured scan + OCR rows land on Drive without
            // waiting for the 15-min periodic. The capture row
            // itself was always persisted (we'd have early-returned
            // to .failed otherwise); some OCR rows may have failed
            // individually, but those that did persist are still
            // worth pushing.
            self?.onPassComplete()
        }
    }

    /// Reset back to `.idle` — typically called when the user
    /// dismisses the review screen.
    public func dismiss() {
        activeTask?.cancel()
        activeTask = nil
        state = .idle
    }
}
