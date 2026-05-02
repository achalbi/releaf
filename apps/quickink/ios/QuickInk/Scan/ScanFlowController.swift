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
import GRDB
import ReleafCoreData
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

    /// User-selected category for the in-flight capture. Bound to
    /// the chip picker in `ScanReviewScreen`. Persisted to
    /// `captures.category` via `setCategory(_:)` whenever the user
    /// taps a chip; held here too so the chip's selected state
    /// survives state-machine transitions (recognizing → complete).
    @Published public var selectedCategory: String? = nil

    /// First-page preview JPEG of the in-flight capture. Surfaced
    /// to `ScanReviewScreen` so it can render the saved image below
    /// the category picker. `nil` outside of an active scan pass.
    @Published public var previewImageURL: URL? = nil

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
        pageURLs: [URL],
        category: String? = nil
    ) {
        // Cancel any previous in-flight pass before starting a new
        // one. The user could conceivably tap Scan twice in quick
        // succession; we don't try to deduplicate at the launcher
        // layer, so guard here.
        activeTask?.cancel()

        let captureId = UUID().uuidString.lowercased()
        let totalPages = pageURLs.count
        // Reset the picker selection so a fresh capture starts with
        // no category. The previous capture's choice was already
        // persisted to its own row.
        selectedCategory = category
        previewImageURL  = previewURL
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
                    pageCount:  totalPages,
                    category:   category
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
            // Page-ordered OCR text accumulator — used to build the
            // single appended snippet after the OCR loop completes.
            // Indexed by `pageIndex` so out-of-order completion stays
            // correct in the final paste.
            var pageTexts: [Int: String] = [:]
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
                        pageTexts[pageIndex] = result.text
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

            // 3. Append the recognized text into today's
            //    `notepad_entries` row so the home recents rail
            //    surfaces it immediately. One entry per (user, day);
            //    multiple captures append to the same row. The
            //    row's category mirrors the latest capture's pick.
            if !pageTexts.isEmpty {
                let category: String? = self?.selectedCategory ?? nil
                await self?.appendOcrToTodayEntry(
                    pageTexts: pageTexts,
                    category:  category
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

    // MARK: - Append-to-today's-entry

    /// Append a freshly-recognized capture's OCR text to today's
    /// `notepad_entries` row (creating it if missing). One entry
    /// per (userId, entryDate); multiple captures of the same day
    /// concatenate into the same row's `notes` column. The
    /// row's `category` is overwritten with the latest capture's
    /// pick — derived data, cheap to refresh, matches the design
    /// note in CategoryRepository's header.
    private func appendOcrToTodayEntry(
        pageTexts: [Int: String],
        category: String?
    ) async {
        let snippet = Self.formatSnippet(pageTexts: pageTexts)
        guard !snippet.isEmpty else { return }
        let today = Self.todayLocalDate()
        let now = IsoClock.nowIso()
        let userId = self.userId
        let dbQueue = QuickInkDatabase.shared.dbQueue

        do {
            try await dbQueue.write { db in
                if let row = try Row.fetchOne(db, sql: """
                    SELECT id, notes FROM notepad_entries
                    WHERE user_id = ? AND entry_date = ? AND deleted_at IS NULL
                    ORDER BY updated_at DESC
                    LIMIT 1
                    """, arguments: [userId, today]) {
                    let id: String = row["id"]
                    let existing: String = row["notes"]
                    let combined = existing.isEmpty ? snippet : (existing + "\n\n" + snippet)
                    try db.execute(sql: """
                        UPDATE notepad_entries
                        SET notes = ?, category = ?, updated_at = ?, dirty = 1
                        WHERE id = ?
                        """, arguments: [combined, category, now, id])
                } else {
                    try db.execute(sql: """
                        INSERT INTO notepad_entries (
                            id, user_id, entry_date, category, notes,
                            created_at, updated_at, dirty
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, 1)
                        """, arguments: [
                            UUID().uuidString.lowercased(),
                            userId, today, category, snippet, now, now,
                        ])
                }
            }
        } catch {
            print("Append OCR to today entry failed: \(error)")
        }
    }

    /// `YYYY-MM-DD` in the device's local timezone — matches the
    /// `notepad_entries.entry_date` CHECK constraint.
    private static func todayLocalDate() -> String {
        let f = DateFormatter()
        f.calendar = .init(identifier: .gregorian)
        f.locale   = .init(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd"
        return f.string(from: Date())
    }

    /// Build a CommonMark snippet for the OCR pages, page-ordered
    /// (page index keys, sorted ascending). Each page is preceded
    /// by a `## Page N` heading so multiple captures inside the
    /// same day's entry stay readable.
    private static func formatSnippet(pageTexts: [Int: String]) -> String {
        let parts = pageTexts.keys.sorted().compactMap { idx -> String? in
            let text = pageTexts[idx]?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            guard !text.isEmpty else { return nil }
            return "## Page \(idx + 1)\n\n\(text)"
        }
        return parts.joined(separator: "\n\n")
    }

    /// Reset back to `.idle` — typically called when the user
    /// dismisses the review screen.
    public func dismiss() {
        activeTask?.cancel()
        activeTask = nil
        state = .idle
        selectedCategory = nil
        previewImageURL  = nil
    }

    /// Picked-category persistence hook for the review screen's
    /// chip row. Updates `selectedCategory` so the UI redraws, then
    /// fires-and-forgets the SQL update against the in-flight
    /// capture's row. No-ops when there's no active capture
    /// (`.idle` / `.failed`).
    public func setCategory(_ name: String?) {
        selectedCategory = name
        guard let captureId = currentCaptureId else { return }
        Task {
            try? await repository.setCategory(captureId: captureId, category: name)
        }
    }

    /// Capture id from the live state machine. `nil` on `.idle` /
    /// `.failed` (no row to update).
    private var currentCaptureId: String? {
        switch state {
        case .recognizing(let id, _, _): return id
        case .complete(let id, _, _):    return id
        case .idle, .failed:             return nil
        }
    }
}
