/*
 * CaptureRepository.swift
 *
 * GRDB-backed repository for `captures` + `ocr_results`. Wraps the
 * raw SQL queries the scan flow needs:
 *   - insert a fresh capture after the scanner returns
 *   - persist a list of OcrResults keyed to a capture
 *   - observe captures + their OCR rows for a user
 *
 * Mirror of `CaptureRepository.kt` in QuickInk's Android target.
 * Lives in QuickInk's app target (not :shared:scan) because both
 * tables are QuickInk-specific — Releaf doesn't have an
 * equivalent today.
 *
 * `OcrResult` (from `ReleafCoreScan`) is encoded into the
 * `blocks_json` TEXT column via `JSONEncoder` over the existing
 * `Codable` conformance — no separate persistence model.
 */

import Foundation
import GRDB
import ReleafCoreData
import ReleafCoreScan

/// Result row for a Library / Search capture-based query. The
/// `ocrSnippet` is `nil` for non-OCR hits (e.g. category-only
/// matches) and contains the FTS5-extracted excerpt otherwise.
public struct SearchHit: Identifiable, Sendable {
    public var capture: CaptureSummary
    public var ocrSnippet: String?
    public var id: String { capture.id }
}

public final class CaptureRepository: @unchecked Sendable {

    private let dbQueue: DatabaseQueue
    private let encoder: JSONEncoder
    private let decoder: JSONDecoder

    public init(database: QuickInkDatabase = .shared) {
        self.dbQueue = database.dbQueue
        self.encoder = JSONEncoder()
        self.decoder = JSONDecoder()
    }

    // MARK: - Captures

    /// Insert a fresh capture row. `id` is supplied by the caller
    /// (UUIDv7 from `ReleafCoreData.Uuidv7.generate()`) so the
    /// scan flow can reference the capture before persistence
    /// completes — i.e. for OCR rows whose `capture_id` foreign-
    /// keys must already be valid.
    public func insertCapture(
        id: String,
        userId: String,
        title: String?,
        pdfURL: URL?,
        previewURL: URL?,
        pageCount: Int,
        /// Capture origin. `"scan"` (default) — went through
        /// VisionKit's document scanner. `"import"` — picked from
        /// the system photo picker. Drives the "Import" pill in
        /// the Library cards.
        source: String = "scan",
        /// Page-size class. Drives the sustainability hero's per-page
        /// weight — `.card` scores +4 pts/page, `.a4` +2, `.small`
        /// +1. Defaulted to `.a4` so legacy callers (and rows synced
        /// from older clients without the field on the wire) read
        /// back as standard pages.
        paperSize: PaperSize = .a4,
        /// Optional geolocation captured at scan/import time. All
        /// fields are nullable in the schema; pass `nil` when the
        /// user has the "Location for scans" toggle off, when the
        /// system permission is denied, or when the fetch / geocode
        /// failed. The struct keeps lat+lon + place name + address
        /// atomic — we never write half of it.
        location: CapturedLocation? = nil
    ) async throws {
        let now = IsoClock.nowIso()
        try await dbQueue.write { db in
            try db.execute(sql: """
                INSERT INTO captures (
                    id, user_id, title, pdf_uri, preview_uri,
                    page_count, source, paper_size,
                    latitude, longitude, locality, sub_locality, address,
                    created_at, updated_at, dirty
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
                """, arguments: [
                    id,
                    userId,
                    title,
                    pdfURL?.absoluteString ?? "",  // captures.pdf_uri NOT NULL
                    previewURL?.absoluteString,
                    pageCount,
                    source,
                    paperSize.rawValue,
                    location?.latitude,
                    location?.longitude,
                    location?.locality,
                    location?.subLocality,
                    location?.address,
                    now,
                    now,
                ])
        }
    }

    /// Attach a tag by name as the capture's primary label, the
    /// post-A.3c replacement for the legacy `setCategory`. Find-or-
    /// creates the tag in the user's namespace (so a fresh
    /// scan-review pick lazily materializes the row) then attaches
    /// via the join — idempotent if the same tag is already
    /// attached. Pass `nil` / blank to clear ALL active tag
    /// attachments on the capture.
    ///
    /// Best-effort: the caller's `try?` covers SQL failure modes
    /// the same way the legacy `setCategory` did.
    public func attachOrEnsurePrimaryTag(
        captureId: String,
        userId: String,
        name: String?,
        source: String = "manual"
    ) async throws {
        let trimmed = (name ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let tagRepo = TagRepository(database: QuickInkDatabase.shared)
        let captureTagRepo = CaptureTagRepository(database: QuickInkDatabase.shared)

        if trimmed.isEmpty {
            try await captureTagRepo.softDeleteByCaptureId(captureId: captureId)
            return
        }
        let tag = try await tagRepo.findOrCreate(userId: userId, name: trimmed)
        try await captureTagRepo.attachTag(
            captureId: captureId,
            tagId:     tag.id,
            source:    source
        )
    }

    /// Workspace v1 — assign a capture to a folder. Mirror of
    /// Android's `CaptureDao.setFolder`. Bumps dirty so the change
    /// propagates via sync on the next push.
    public func setFolder(captureId: String, folderId: String) async throws {
        let now = IsoClock.nowIso()
        try await dbQueue.write { db in
            try db.execute(sql: """
                UPDATE captures
                SET folder_id = ?, updated_at = ?, dirty = 1
                WHERE id = ?
                """, arguments: [folderId, now, captureId])
        }
    }

    /// Workspace v1 — Continue card signal. Writes the most-recent
    /// page the user was on. Page is 1-indexed.
    public func setLastOpened(
        captureId: String,
        openedAt: String,
        page: Int,
        deviceId: String?
    ) async throws {
        try await dbQueue.write { db in
            try db.execute(sql: """
                UPDATE captures
                SET last_opened_at = ?,
                    last_opened_page = ?,
                    last_opened_device = ?,
                    updated_at = ?,
                    dirty = 1
                WHERE id = ?
                """, arguments: [openedAt, page, deviceId, openedAt, captureId])
        }
    }

    /// Update the user-editable title on a capture. Same dirty-bit
    /// pattern as `setCategory`. Pass `nil` to clear the title
    /// (which makes the Library card fall back to OCR snippet →
    /// category → "Untitled scan").
    public func setTitle(captureId: String, title: String?) async throws {
        let now = IsoClock.nowIso()
        try await dbQueue.write { db in
            try db.execute(sql: """
                UPDATE captures
                SET title = ?, updated_at = ?, dirty = 1
                WHERE id = ?
                """, arguments: [title, now, captureId])
        }
    }

    /// Overwrite `captures.notes` outright. Used by the notes editor
    /// on the detail screen when the user explicitly edits the whole
    /// notes blob. Empty / whitespace input is stored as nil so the
    /// detail card falls back to the "Add notes" empty state.
    public func setNotes(captureId: String, notes: String?) async throws {
        let trimmed = notes?.trimmingCharacters(in: .whitespacesAndNewlines)
        let next: String? = (trimmed?.isEmpty ?? true) ? nil : trimmed
        let now = IsoClock.nowIso()
        try await dbQueue.write { db in
            try db.execute(sql: """
                UPDATE captures
                SET notes = ?, updated_at = ?, dirty = 1
                WHERE id = ?
                """, arguments: [next, now, captureId])
        }
    }

    /// Append `text` to `captures.notes` as a new paragraph (separated
    /// by a blank line). Used by the voice-note transcript editor:
    /// after recording + transcribing, the edited text is saved both
    /// onto the voice note's `transcription` field AND appended here
    /// so the document carries the running notes across all clips.
    /// Empty / whitespace input is a no-op; the existing notes value
    /// is preserved.
    public func appendNote(captureId: String, text: String) async throws {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        let now = IsoClock.nowIso()
        try await dbQueue.write { db in
            let existing: String? = try String.fetchOne(
                db,
                sql: "SELECT notes FROM captures WHERE id = ? LIMIT 1",
                arguments: [captureId]
            )
            let next: String = {
                guard let cur = existing?.trimmingCharacters(in: .whitespacesAndNewlines),
                      !cur.isEmpty else { return trimmed }
                return cur + "\n\n" + trimmed
            }()
            try db.execute(sql: """
                UPDATE captures
                SET notes = ?, updated_at = ?, dirty = 1
                WHERE id = ?
                """, arguments: [next, now, captureId])
        }
    }

    /// Update the `paper_size` bucket on an existing capture. Called
    /// from `ScanReviewScreen`'s paper-size chip when the user
    /// disambiguates the auto-detection (typically A4 vs A5, where
    /// aspect ratio alone can't tell). Same dirty + updated_at
    /// pattern as `setTitle` / `setFolder` so the change syncs.
    public func setPaperSize(captureId: String, paperSize: PaperSize) async throws {
        let now = IsoClock.nowIso()
        try await dbQueue.write { db in
            try db.execute(sql: """
                UPDATE captures
                SET paper_size = ?, updated_at = ?, dirty = 1
                WHERE id = ?
                """, arguments: [paperSize.rawValue, now, captureId])
        }
    }

    /// Backfill the reverse-geocoded place name + full address on a
    /// capture whose coordinates landed without them at scan time
    /// (rate-limited CLGeocoder, no network, or a remote area the
    /// system couldn't resolve). Called from the Details screen on
    /// open when the row has lat/lon but missing names — the
    /// re-tried geocode persists here and propagates to other
    /// devices on the next Drive push (dirty=1 + updated_at bumped).
    public func updateLocation(
        captureId: String,
        locality: String?,
        subLocality: String?,
        address: String?
    ) async throws {
        let now = IsoClock.nowIso()
        try await dbQueue.write { db in
            try db.execute(sql: """
                UPDATE captures
                SET locality = ?, sub_locality = ?, address = ?,
                    updated_at = ?, dirty = 1
                WHERE id = ?
                """, arguments: [locality, subLocality, address, now, captureId])
        }
    }

    /// Replace the OCR text on a single page row. Used by the scan
    /// detail editor when the user corrects the recognised text.
    /// Sets the dirty bit + bumps `updated_at` so the next sync
    /// pass mirrors the change to Drive. The FTS5 virtual table
    /// rebuilds its index automatically via the AFTER UPDATE
    /// trigger on `ocr_results`.
    public func setOcrText(ocrResultId: String, text: String) async throws {
        let now = IsoClock.nowIso()
        try await dbQueue.write { db in
            try db.execute(sql: """
                UPDATE ocr_results
                SET text = ?, updated_at = ?, dirty = 1
                WHERE id = ?
                """, arguments: [text, now, ocrResultId])
        }
    }

    // MARK: - Search

    /// Search captures across tag name + OCR text. Returns each
    /// matching capture once with the strongest OCR snippet. Mirror
    /// of Android's `CaptureRepository.search`.
    ///
    /// Passes:
    ///   1. Captures with any attached tag whose `name` contains
    ///      `query` (substring, case-insensitive). Replaces the
    ///      pre-A.3c `captures.category` substring pass. No OCR
    ///      snippet for these.
    ///   2. OCR-text matches via `fts_ocr_text` MATCH joined back
    ///      to captures.
    ///   3. (Fallback) LIKE-based substring over `ocr_results.text`
    ///      when the FTS pass came back empty.
    ///
    /// `requiredTagIds` post-filters the union so only captures
    /// tagged with EVERY id survive. Used by the search-bar
    /// `#tag` autocomplete: a user typing `#invoice paid` keeps
    /// Invoice as a hard filter and runs "paid" through the OCR
    /// pass.
    public func search(
        userId: String,
        query: String,
        requiredTagIds: [String] = []
    ) async throws -> [SearchHit] {
        let trimmed = query.trimmingCharacters(in: .whitespaces)
        if trimmed.isEmpty && requiredTagIds.isEmpty { return [] }

        // Tag-only path — no residual text. Return every capture
        // that carries the full set of required tags, newest-first.
        if trimmed.isEmpty && !requiredTagIds.isEmpty {
            return try await dbQueue.read { db in
                let summaries = try capturesWithAllTags(
                    db: db, userId: userId, requiredTagIds: requiredTagIds,
                )
                return summaries.map { SearchHit(capture: $0, ocrSnippet: nil) }
            }
        }
        let likePattern = "%\(trimmed)%"
        let ftsQuery = Self.buildFtsQuery(trimmed)

        return try await dbQueue.read { db -> [SearchHit] in
            var seen = Set<String>()
            var hits: [SearchHit] = []

            // Pass 1 — tag-name substring match (post-A.3c
            // replacement for the legacy `captures.category` pass).
            let tagRows = try CaptureSummary.fetchAll(db, sql: """
                SELECT DISTINCT captures.id, captures.title, captures.preview_uri,
                       captures.pdf_uri, captures.page_count, captures.created_at,
                       captures.source
                FROM captures
                JOIN capture_tags ON capture_tags.capture_id = captures.id
                JOIN tags         ON tags.id         = capture_tags.tag_id
                WHERE captures.user_id    = ?
                  AND captures.deleted_at IS NULL
                  AND capture_tags.deleted_at IS NULL
                  AND tags.deleted_at     IS NULL
                  AND lower(tags.name)    LIKE lower(?)
                ORDER BY captures.created_at DESC
                LIMIT 50
                """, arguments: [userId, likePattern])
            for c in tagRows where !seen.contains(c.id) {
                seen.insert(c.id)
                hits.append(SearchHit(capture: c, ocrSnippet: nil))
            }

            // Pass 2 — OCR-text MATCH via FTS5. `snippet()` returns
            // a 32-token excerpt centred on the match with `[…]`
            // markers around the matched span; we strip the markers
            // when rendering.
            //
            // The OUTER query joins back to captures so we can apply
            // the user-scope filter (FTS5 rows aren't user-scoped
            // directly — they FK through ocr_results → captures).
            let ocrRows = try Row.fetchAll(db, sql: """
                SELECT c.id            AS id,
                       c.preview_uri   AS preview_uri,
                       c.pdf_uri       AS pdf_uri,
                       c.page_count    AS page_count,
                       c.created_at    AS created_at,
                       snippet(fts_ocr_text, 1, '\u{2039}', '\u{203A}', '…', 24) AS ocr_snippet
                FROM   fts_ocr_text f
                JOIN   ocr_results r ON r.id = f.ocr_result_id
                JOIN   captures    c ON c.id = r.capture_id
                WHERE  f.text MATCH ?
                  AND  r.deleted_at IS NULL
                  AND  c.deleted_at IS NULL
                  AND  c.user_id = ?
                ORDER BY rank
                LIMIT 50
                """, arguments: [ftsQuery, userId])

            for row in ocrRows {
                let id: String = row["id"]
                guard !seen.contains(id) else { continue }
                seen.insert(id)
                let summary = CaptureSummary(
                    id:         id,
                    previewUri: row["preview_uri"] as String?,
                    pdfUri:     row["pdf_uri"],
                    pageCount:  row["page_count"],
                    createdAt:  row["created_at"]
                )
                let snippet = (row["ocr_snippet"] as String?)?
                    .replacingOccurrences(of: "\u{2039}", with: "")
                    .replacingOccurrences(of: "\u{203A}", with: "")
                hits.append(SearchHit(capture: summary, ocrSnippet: snippet))
            }

            // Pass 3 — substring fallback over `ocr_results.text`. If
            // the FTS5 pass returned nothing (malformed MATCH expr,
            // empty FTS index because triggers haven't fired, etc.),
            // a slow LIKE-based scan still produces results so the
            // user isn't left wondering why "obvious" terms miss.
            // Only runs when FTS came back empty — it's O(rows ×
            // text length), bounded by LIMIT 50.
            if hits.allSatisfy({ $0.ocrSnippet == nil }) {
                let likeRows = try Row.fetchAll(db, sql: """
                    SELECT c.id            AS id,
                           c.preview_uri   AS preview_uri,
                           c.pdf_uri       AS pdf_uri,
                           c.page_count    AS page_count,
                           c.created_at    AS created_at,
                           substr(r.text, max(1, instr(lower(r.text), lower(?)) - 30), 120) AS ocr_snippet
                    FROM   ocr_results r
                    JOIN   captures    c ON c.id = r.capture_id
                    WHERE  r.deleted_at IS NULL
                      AND  c.deleted_at IS NULL
                      AND  c.user_id = ?
                      AND  lower(r.text) LIKE lower(?)
                    ORDER BY c.created_at DESC
                    LIMIT 50
                    """, arguments: [trimmed, userId, likePattern])

                for row in likeRows {
                    let id: String = row["id"]
                    guard !seen.contains(id) else { continue }
                    seen.insert(id)
                    let summary = CaptureSummary(
                        id:         id,
                        previewUri: row["preview_uri"] as String?,
                        pdfUri:     row["pdf_uri"],
                        pageCount:  row["page_count"],
                        createdAt:  row["created_at"]
                    )
                    let snippet = row["ocr_snippet"] as String?
                    hits.append(SearchHit(capture: summary, ocrSnippet: snippet))
                }
            }

            // Tag-id post-filter — keep only captures that carry
            // every required tag.
            if !requiredTagIds.isEmpty {
                let survivors = try captureIdsWithAllTags(
                    db: db, userId: userId, requiredTagIds: requiredTagIds,
                )
                hits = hits.filter { survivors.contains($0.capture.id) }
            }

            return hits
        }
    }

    /// Capture summaries (newest-first) that carry every tag id in
    /// `requiredTagIds`. Used by the `#tag`-only search path.
    private func capturesWithAllTags(
        db: Database,
        userId: String,
        requiredTagIds: [String]
    ) throws -> [CaptureSummary] {
        let ids = try captureIdsWithAllTags(
            db: db, userId: userId, requiredTagIds: requiredTagIds,
        )
        if ids.isEmpty { return [] }
        let placeholders = ids.map { _ in "?" }.joined(separator: ",")
        var args: [DatabaseValueConvertible] = ids.map { $0 as DatabaseValueConvertible }
        args.append(userId)
        return try CaptureSummary.fetchAll(db, sql: """
            SELECT id, title, preview_uri, pdf_uri, page_count, created_at, source
            FROM captures
            WHERE id IN (\(placeholders))
              AND user_id = ?
              AND deleted_at IS NULL
            ORDER BY created_at DESC
            """, arguments: StatementArguments(args))
    }

    private func captureIdsWithAllTags(
        db: Database,
        userId: String,
        requiredTagIds: [String]
    ) throws -> Set<String> {
        if requiredTagIds.isEmpty { return [] }
        let placeholders = requiredTagIds.map { _ in "?" }.joined(separator: ",")
        var args: [DatabaseValueConvertible] = requiredTagIds.map { $0 as DatabaseValueConvertible }
        args.append(userId)
        args.append(requiredTagIds.count)
        let rows = try String.fetchAll(db, sql: """
            SELECT capture_tags.capture_id
            FROM capture_tags
            JOIN captures ON captures.id = capture_tags.capture_id
            WHERE capture_tags.tag_id IN (\(placeholders))
              AND capture_tags.deleted_at IS NULL
              AND captures.user_id = ?
              AND captures.deleted_at IS NULL
            GROUP BY capture_tags.capture_id
            HAVING COUNT(DISTINCT capture_tags.tag_id) = ?
            """, arguments: StatementArguments(args))
        return Set(rows)
    }

    /// Build a tokenised FTS5 MATCH expression from a free-form user
    /// query. Each whitespace-delimited word becomes a prefix term
    /// (`word*`) so partial-word matches work mid-typing.
    ///
    /// Strips EVERY non-alphanumeric / non-whitespace char (replaces
    /// with a space) before tokenising. The earlier "strip the five
    /// known FTS5 operators" approach left other reserved chars
    /// behind (`-`, `+`, `^`, smart quotes, OR / NEAR keywords),
    /// which produced malformed MATCH expressions for queries like
    /// "it's" or "math-physics" — the SQL throws, caller catches and
    /// zeroes the hits, and the user thinks search is broken.
    /// Aggressive sanitisation trades a tiny bit of expressivity for
    /// reliability; users typing punctuation almost always want the
    /// alphanumeric tokens around it.
    private static func buildFtsQuery(_ raw: String) -> String {
        let cleaned = raw.lowercased().map { c -> Character in
            if c.isLetter || c.isNumber || c.isWhitespace { return c }
            return " "
        }
        let tokens = String(cleaned).split(whereSeparator: { $0.isWhitespace })
        guard !tokens.isEmpty else { return "\"\"" }
        return tokens.map { "\($0)*" }.joined(separator: " ")
    }

    // MARK: - Binary attachments (Drive backup)

    /// One row per capture whose binaries (PDF and/or preview JPEG)
    /// haven't been mirrored to Drive yet. Live + tombstoned rows
    /// are both included — tombstones drive the cascade-trash path.
    public struct PendingBinaryRow: Sendable {
        public let id: String
        public let userId: String
        public let pdfUri: String
        public let previewUri: String?
        public let createdAt: String
        public let pdfDriveFileId: String?
        public let previewDriveFileId: String?
        public let deletedAt: String?
    }

    public func pendingBinaryRows(userId: String) async throws -> [PendingBinaryRow] {
        try await dbQueue.read { db -> [PendingBinaryRow] in
            let rows = try Row.fetchAll(db, sql: """
                SELECT id, user_id, pdf_uri, preview_uri, created_at,
                       pdf_drive_file_id, preview_drive_file_id, deleted_at
                FROM captures
                WHERE user_id = ?
                  AND (
                    -- Live rows missing at least one binary upload.
                    (deleted_at IS NULL AND (
                        pdf_drive_file_id IS NULL
                        OR (preview_uri IS NOT NULL AND preview_drive_file_id IS NULL)
                    ))
                    -- Tombstoned rows that still have Drive ids to trash.
                    OR (deleted_at IS NOT NULL AND (
                        pdf_drive_file_id IS NOT NULL
                        OR preview_drive_file_id IS NOT NULL
                    ))
                  )
                ORDER BY created_at DESC
                LIMIT 50
                """, arguments: [userId])
            return rows.map { row in
                PendingBinaryRow(
                    id:                  row["id"],
                    userId:              row["user_id"],
                    pdfUri:              row["pdf_uri"],
                    previewUri:          row["preview_uri"] as String?,
                    createdAt:           row["created_at"],
                    pdfDriveFileId:      row["pdf_drive_file_id"] as String?,
                    previewDriveFileId:  row["preview_drive_file_id"] as String?,
                    deletedAt:           row["deleted_at"] as String?
                )
            }
        }
    }

    /// Stamp the Drive file id of a successful PDF upload back onto
    /// the local capture row. Called once per successful upload.
    public func markPdfSynced(captureId: String, driveFileId: String?) async throws {
        try await dbQueue.write { db in
            try db.execute(sql: """
                UPDATE captures SET pdf_drive_file_id = ? WHERE id = ?
                """, arguments: [driveFileId, captureId])
        }
    }

    /// Stamp the Drive file id of a successful preview-JPEG upload.
    public func markPreviewSynced(captureId: String, driveFileId: String?) async throws {
        try await dbQueue.write { db in
            try db.execute(sql: """
                UPDATE captures SET preview_drive_file_id = ? WHERE id = ?
                """, arguments: [driveFileId, captureId])
        }
    }

    /// Stamp pdf_uri (and optionally preview_uri) on a capture row
    /// after a fresh-device restore-from-Drive copy completes. The
    /// previous URIs are stale (point at another device's storage),
    /// so the restorer rewrites them to local paths once the binary
    /// is back in `AttachmentStorage`.
    public func updateLocalUris(
        captureId: String,
        pdfUri: String?,
        previewUri: String?
    ) async throws {
        try await dbQueue.write { db in
            if let pdfUri {
                try db.execute(sql: """
                    UPDATE captures SET pdf_uri = ? WHERE id = ?
                    """, arguments: [pdfUri, captureId])
            }
            if let previewUri {
                try db.execute(sql: """
                    UPDATE captures SET preview_uri = ? WHERE id = ?
                    """, arguments: [previewUri, captureId])
            }
        }
    }

    /// Soft-delete a capture. Stamps `deleted_at` + bumps `dirty`
    /// so the sync worker mirrors the tombstone to Drive on its
    /// next pass. The cascade rule on `ocr_results.capture_id`
    /// only fires on hard DELETE, so OCR rows stay around in
    /// SQLite until a future cleanup pass — the home rail filters
    /// captures by `deleted_at IS NULL` either way.
    public func softDelete(id: String) async throws {
        let now = IsoClock.nowIso()
        try await dbQueue.write { db in
            try db.execute(sql: """
                UPDATE captures
                SET deleted_at = ?, updated_at = ?, dirty = 1
                WHERE id = ?
                """, arguments: [now, now, id])
        }
    }

    // MARK: - OCR results

    /// Persist a successful page recognition into `ocr_results`.
    /// `OcrResult.blocks` is encoded to JSON via `Codable`. The
    /// `id` is generated here (per-row UUIDv7).
    public func insertOcrResult(
        captureId: String,
        pageIndex: Int,
        result: OcrResult
    ) async throws {
        let id = Uuidv7.generate()
        let now = IsoClock.nowIso()
        let blocksJson = try String(
            data: encoder.encode(result.blocks),
            encoding: .utf8
        ) ?? "[]"

        try await dbQueue.write { db in
            try db.execute(sql: """
                INSERT INTO ocr_results (
                    id, capture_id, page_index, language, confidence,
                    text, blocks_json, engine, engine_version,
                    created_at, updated_at, dirty
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
                """, arguments: [
                    id,
                    captureId,
                    pageIndex,
                    result.language,
                    result.confidence,
                    result.text,
                    blocksJson,
                    result.engine,
                    result.engineVersion,
                    now,
                    now,
                ])
        }
    }
}

