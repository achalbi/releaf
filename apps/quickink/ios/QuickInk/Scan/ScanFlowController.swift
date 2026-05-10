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

    /// What `onPassComplete` receives. Mirrors the field set
    /// `AnalyticsRepository.enqueueCapture` consumes — same field
    /// names so the wiring at the call site reads as a 1:1 forward.
    public struct PassSummary: Sendable {
        public let captureId:  String
        public let source:     String
        public let pageCount:  Int
        public let category:   String?
        public let hasOcr:     Bool
        public let ocrChars:   Int
        /// ISO-8601 timestamp the user finished the scan pass.
        public let capturedAt: String
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
    /// Fired once at the end of each scan pass. Wired by
    /// `QuickInkRoot.MainShell` to enqueue an analytics outbox
    /// row (drained opportunistically by `AnalyticsFlushTask`).
    /// Drive-sync used to also fan out from here; that's now
    /// user-initiated only via Settings → "Sync now". Defaults to
    /// a no-op for tests / previews.
    ///
    /// Fires ONCE per pass — at `.complete` or after a partial
    /// fail-path — rather than per OCR row, so a 30-page scan
    /// triggers one analytics enqueue. The outbox dedupes by
    /// capture id anyway, but firing once at the end avoids
    /// redundant work + log noise.
    ///
    /// The [PassSummary] arg carries every field
    /// `AnalyticsRepository.enqueueCapture` needs, so the call
    /// site reads as a 1:1 forward.
    private let onPassComplete: (PassSummary) -> Void
    private var activeTask: Task<Void, Never>?

    public init(
        userId: String,
        repository: CaptureRepository = CaptureRepository(),
        pipeline: OcrPipeline = OcrPipeline(engine: VisionTextRecognizer()),
        onPassComplete: @escaping (PassSummary) -> Void = { _ in }
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
    /// - Parameter source: `"scan"` when the result came from the
    ///   document scanner (default), `"import"` when it came from
    ///   the system photo picker. Persisted on the capture row so
    ///   the Library cards can render an "Import" pill.
    public func onScanComplete(
        pdfURL: URL?,
        previewURL: URL?,
        pageURLs: [URL],
        category: String? = nil,
        source: String = "scan"
    ) {
        // Cancel any previous in-flight pass before starting a new
        // one. The user could conceivably tap Scan twice in quick
        // succession; we don't try to deduplicate at the launcher
        // layer, so guard here.
        activeTask?.cancel()

        let captureId = UUID().uuidString.lowercased()
        let totalPages = pageURLs.count
        // Stamp once at the start of the pass so retries / OCR-pipeline
        // jitter don't shift the analytics timestamp away from "when
        // the user actually finished scanning". The capturedAt field
        // ends up on `analytics_outbox` and on the backend's
        // `capture_events.captured_at` column.
        let capturedAt = IsoClock.nowIso()
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
                    category:   category,
                    source:     source
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

                        // Fast-path auto-pick: as soon as page 0
                        // (the first physical page) lands, try to
                        // match its leading tokens against the
                        // user's categories so the review screen's
                        // chip flips on without waiting for the
                        // rest of a multi-page pipeline. Two-word
                        // phrases ("Meeting Notes") are tried
                        // before the first word alone — see
                        // `matchCategoryName(tokens:)`. Subsequent
                        // pages skip this branch — only page 1
                        // drives the auto-pick. The post-loop block
                        // below still runs as a fallback when page
                        // 0's OCR fails entirely.
                        if pageIndex == 0, let strongSelf = self, strongSelf.selectedCategory == nil {
                            let tokens = Self.extractLeadingTokens(pageTexts: pageTexts)
                            if let match = await strongSelf.matchCategoryName(tokens: tokens) {
                                strongSelf.selectedCategory = match
                                try? await repository.setCategory(captureId: captureId, category: match)
                            }
                        }
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

            // 3. Auto-pick fallback. The fast-path inside the loop
            //    (above) handles the common case — page 0 succeeds
            //    and the chip flips on the moment its OCR lands.
            //    This block catches the edge case where page 0 OCR
            //    failed but a later page succeeded: we still try the
            //    lowest-indexed available page's leading tokens so
            //    the user gets some auto-match instead of none.
            //    Skipped when the user pre-picked a category or the
            //    fast-path already matched.
            if let strongSelf = self,
               strongSelf.selectedCategory == nil {
                let tokens = Self.extractLeadingTokens(pageTexts: pageTexts)
                if let match = await strongSelf.matchCategoryName(tokens: tokens) {
                    strongSelf.selectedCategory = match
                    // Persist on the in-flight capture row so a later
                    // restart restores the auto-pick. Best-effort.
                    try? await repository.setCategory(captureId: captureId, category: match)
                }
            }

            // 4. Auto-populate the capture's title now that category
            //    + OCR are both settled. Priority:
            //      (a) the picked category, when present;
            //      (b) otherwise, the first two words of the
            //          earliest non-blank OCR page.
            //    The user can edit the title later from the scan
            //    detail screen — that write also goes through
            //    `setTitle`, so the latest value wins. Best-effort:
            //    a SQL failure here leaves the title null and the
            //    Library card falls back to its existing cascade.
            if let autoTitle = Self.computeInitialTitle(
                category:  self?.selectedCategory,
                pageTexts: pageTexts
            ) {
                try? await repository.setTitle(captureId: captureId, title: autoTitle)
            }

            // 5. Append the recognized text into today's
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

            // Hand the just-completed pass to the host
            // (QuickInkRoot.MainShell) so it can enqueue an
            // analytics-outbox row + opportunistically request a
            // flush. The `hasOcr` / `ocrChars` fields are computed
            // from the same `pageTexts` accumulator we built during
            // the OCR loop. Drive-sync used to also fan out from
            // here; that's now user-initiated only via Settings →
            // "Sync now".
            let totalChars = pageTexts.values.reduce(0) { $0 + $1.count }
            let summary = PassSummary(
                captureId:  captureId,
                source:     source,
                pageCount:  totalPages,
                category:   self?.selectedCategory,
                hasOcr:     successCount > 0,
                ocrChars:   totalChars,
                capturedAt: capturedAt
            )
            self?.onPassComplete(summary)
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

    /// Compute the initial title to stamp on a freshly-captured row.
    /// Priority:
    ///   (1) the picked / auto-matched `category`, trimmed —
    ///       explicit tagging is the strongest signal of intent;
    ///   (2) the first two words of the earliest non-blank OCR page —
    ///       gives every untagged capture a readable preview header.
    /// Returns `nil` when neither signal is available; the caller
    /// leaves the title column at NULL so the Library card's existing
    /// "Untitled scan" fallback handles it.
    private static func computeInitialTitle(
        category: String?,
        pageTexts: [Int: String]
    ) -> String? {
        if let raw = category?.trimmingCharacters(in: .whitespaces),
           !raw.isEmpty {
            return raw
        }

        let firstKey = pageTexts.keys.sorted().first { idx in
            let t = pageTexts[idx]?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            return !t.isEmpty
        }
        guard let key = firstKey,
              let raw = pageTexts[key]?.trimmingCharacters(in: .whitespacesAndNewlines),
              !raw.isEmpty else {
            return nil
        }

        let words = raw
            .split(whereSeparator: { $0.isWhitespace })
            .map(String.init)
            .filter { !$0.isEmpty }
        guard !words.isEmpty else { return nil }
        return words.prefix(2).joined(separator: " ")
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

    // MARK: - Auto-category from leading OCR tokens

    /// Pull up to `maxTokens` leading word tokens from the lowest-
    /// indexed page's OCR text. Strips leading/trailing whitespace,
    /// splits on whitespace runs, and trims any non-alphanumeric
    /// padding so `"Ideas,"` / `"Ideas."` / `"  ideas "` all reduce
    /// to `"Ideas"`. Returns an empty list when there's no usable
    /// text — callers should treat that as "no auto-match".
    static func extractLeadingTokens(pageTexts: [Int: String], maxTokens: Int = 2) -> [String] {
        guard let firstKey = pageTexts.keys.min(),
              let raw = pageTexts[firstKey] else { return [] }
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return [] }
        // Filter empty-after-strip tokens *before* taking the
        // window, so a stray leading "—"/"•"/"1." doesn't eat into
        // our maxTokens budget. With the wrong order, OCR text like
        // "— Meeting Notes" would yield just ["Meeting"] instead of
        // ["Meeting", "Notes"] and a "Meeting Notes" category would
        // miss the auto-match.
        let words = trimmed
            .split(whereSeparator: { $0.isWhitespace })
            .map { String($0).trimmingCharacters(in: CharacterSet.alphanumerics.inverted) }
            .filter { !$0.isEmpty }
        return Array(words.prefix(maxTokens))
    }

    /// Look up a category whose name matches the leading OCR
    /// `tokens` case- and number-insensitively, scoped to the
    /// current `userId`. The widest window is tried first — for a
    /// two-token input like `["Meeting", "Notes"]` we check the
    /// full phrase against multi-word categories before falling
    /// back to just `"Meeting"`. Each token (on both sides) is
    /// stemmed via `depluralize(_:)` so "Idea" still matches
    /// "Ideas", "Story" still matches "Stories", and so on.
    /// Returns the canonical (database-cased) name on a hit,
    /// `nil` otherwise. Reads the shared GRDB queue directly to
    /// avoid coupling the controller to `CategoryRepository`'s
    /// observation API — we only need a one-shot read here.
    private func matchCategoryName(tokens: [String]) async -> String? {
        guard !tokens.isEmpty else { return nil }
        let userId = self.userId
        let dbQueue = QuickInkDatabase.shared.dbQueue
        return try? await dbQueue.read { db -> String? in
            // Pull the active category names and compare in Swift —
            // the depluralization rules are awkward to express in
            // SQL, and the typical user has a single-digit number
            // of categories so the in-memory pass is cheap.
            let rows = try Row.fetchAll(db, sql: """
                SELECT name FROM categories
                WHERE user_id = ? AND deleted_at IS NULL
                """, arguments: [userId])
            // Try the widest window first (two-word phrase), then
            // fall back to just the first word. A single-token
            // input only runs the fallback iteration.
            for window in stride(from: tokens.count, through: 1, by: -1) {
                let needle = Self.depluralizePhrase(tokens.prefix(window).joined(separator: " "))
                guard !needle.isEmpty else { continue }
                // Pass 1: canonical (depluralized) name match.
                if let row = rows.first(where: { row in
                    Self.depluralizePhrase(row["name"] as String) == needle
                }) {
                    return row["name"] as String?
                }
                // Pass 2: alias match — categories with known
                // synonyms / OCR-error variants that don't reduce
                // to the canonical via depluralization alone (e.g.
                // "card" / "businesscard" / "8usiness" all map to
                // "Business Card").
                if let row = rows.first(where: { row in
                    let canonical = row["name"] as String
                    return Self.aliasesFor(canonical).contains { alias in
                        Self.depluralizePhrase(alias) == needle
                    }
                }) {
                    return row["name"] as String?
                }
            }
            return nil
        }
    }

    /// Synonyms / OCR-error variants for a category name that the
    /// canonical depluralization pass wouldn't catch. The keys are
    /// matched case-insensitively against the active category list;
    /// returning a non-empty list adds an extra pass to
    /// [matchCategoryName] that compares the OCR needle against each
    /// alias (after depluralization). Empty for unknown categories,
    /// which fall through to the canonical-only match.
    nonisolated static func aliasesFor(_ canonicalName: String) -> [String] {
        switch canonicalName.lowercased() {
        case "business card":
            // "card" alone, the no-space mash-up, and the common
            // OCR misread where ML Kit decodes "B" as "8".
            return [
                "card",
                "businesscard",
                "8usiness",
                "8usinesscard",
                "8usiness card",
            ]
        default:
            return []
        }
    }

    /// Whitespace-split tokenizer + per-token depluralizer. Lets
    /// `"Meeting Notes"` reduce to `"meeting note"` so a category
    /// named "Meeting Notes" still matches a scan starting with
    /// "Meeting Note" (or vice versa). Empty input → empty stem.
    nonisolated static func depluralizePhrase(_ s: String) -> String {
        s.split(whereSeparator: { $0.isWhitespace })
            .map { depluralize(String($0)) }
            .joined(separator: " ")
    }

    /// Reduce an English word to its (rough) singular stem so we can
    /// compare a scanned token like "Ideas" against a category named
    /// "Idea" (or vice versa). Rules cover the common regular cases:
    ///
    ///   - `-ies` → `-y`     (stories → story)
    ///   - `-ches`/`-shes`/`-xes`/`-zes`/`-sses` → drop `-es`
    ///                       (boxes → box, classes → class)
    ///   - `-ss`             → kept as-is (class stays class)
    ///   - trailing `-s`     → dropped (ideas → idea, votes → vote)
    ///
    /// Irregular plurals (mice, geese, children) aren't handled —
    /// they fall through to a literal exact-match. Returns the lower-
    /// cased stem.
    ///
    /// `nonisolated` so it's callable from the GRDB read closure
    /// (which runs on a background queue, not the main actor) inside
    /// `matchCategoryName`. The function is pure — no instance
    /// state, no UI work — so the isolation downgrade is safe.
    nonisolated static func depluralize(_ s: String) -> String {
        let lower = s.lowercased()
        guard lower.count > 2 else { return lower }
        if lower.hasSuffix("ies") {
            return String(lower.dropLast(3)) + "y"
        }
        if lower.hasSuffix("ches") || lower.hasSuffix("shes") ||
           lower.hasSuffix("xes")  || lower.hasSuffix("zes")  ||
           lower.hasSuffix("sses") {
            return String(lower.dropLast(2))
        }
        if lower.hasSuffix("ss") { return lower }
        if lower.hasSuffix("s")  { return String(lower.dropLast()) }
        return lower
    }
}
