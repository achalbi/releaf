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
        pageCount: Int
    ) async throws {
        let now = IsoClock.nowIso()
        try await dbQueue.write { db in
            try db.execute(sql: """
                INSERT INTO captures (
                    id, user_id, title, pdf_uri, preview_uri,
                    page_count, created_at, updated_at, dirty
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1)
                """, arguments: [
                    id,
                    userId,
                    title,
                    pdfURL?.absoluteString ?? "",  // captures.pdf_uri NOT NULL
                    previewURL?.absoluteString,
                    pageCount,
                    now,
                    now,
                ])
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

