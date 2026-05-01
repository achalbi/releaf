/*
 * QuickInkSyncDataSource.swift
 *
 * QuickInk's implementation of `ReleafCoreSync.SyncDataSource`.
 * Mirror of `apps/releaf/.../Sync/ReleafSyncDataSource.swift` but
 * for QuickInk's three entity kinds: notepad_entries, captures,
 * ocr_results.
 *
 * Pagination policy: same as Releaf's iOS — single-batch with
 * `nextCursor: nil`. Paginated implementations would land here
 * when a real QuickInk user accumulates enough dirty rows that
 * one Drive payload exceeds ~4 MB.
 *
 * Row reads:
 *   - `NotepadEntry` (from ReleafCoreNotes) is a GRDB
 *     `FetchableRecord` / `PersistableRecord`, so notepad reads
 *     use the typed surface — same shape Releaf's iOS uses.
 *   - `captures` and `ocr_results` don't have iOS-side typed
 *     records yet (Slice 1 used raw SQL for the
 *     `CaptureRepository`'s inserts and we kept that convention).
 *     This file uses `Row.fetchAll(db, sql:)` for them; if more
 *     code starts touching these tables, defining `CaptureRow` /
 *     `OcrResultRow` GRDB records becomes worthwhile.
 *
 * `lastAppliedManifestEtag` is a v1 no-op (returns nil, set is
 * also no-op) — matches Releaf's iOS stance.
 *
 * See:
 *   - shared/ios/ReleafCore/Sources/ReleafCoreSync/SyncDataSource.swift — protocol
 *   - apps/releaf/.../Sync/ReleafSyncDataSource.swift — reference
 *   - QUICKINK_PROPOSAL.md §1 — design rationale
 */

import Foundation
import GRDB
import ReleafCoreNotes
import ReleafCoreSync

public final class QuickInkSyncDataSource: SyncDataSource, @unchecked Sendable {

    private let database: QuickInkDatabase
    private let userId: String

    public init(database: QuickInkDatabase = .shared, userId: String) {
        self.database = database
        self.userId = userId
    }

    // MARK: - Identity

    public let driveRootFolderName: String = "QuickInk"

    public let schemaVersion: SchemaVersion = .current

    public let appId: String = "quickink"

    // MARK: - Outbound: collect dirty rows

    public func nextDirtyBatch(after cursor: SyncCursor?, limit: Int) async throws -> DirtyBatch {
        if cursor != nil {
            return DirtyBatch(entries: [], nextCursor: nil)
        }

        let userId = self.userId
        let entries = try await database.dbQueue.read { db -> [DirtyEntry] in
            var out: [DirtyEntry] = []

            // ---- notepad_entries (typed via GRDB) ----
            let notepad = try NotepadEntry
                .filter(sql: "user_id = ? AND (deleted_at IS NULL OR dirty = 1)",
                        arguments: [userId])
                .fetchAll(db)
            for row in notepad where row.deletedAt == nil {
                if let entry = try Self.makeEntry(
                    id: row.id,
                    kind: DrivePath.kindNotepadEntry,
                    drivePath: DrivePath.notepadEntry(entryDate: row.entryDate, entryId: row.id),
                    updatedAt: row.updatedAt,
                    encodable: row.toV2Payload()
                ) { out.append(entry) }
            }

            // ---- captures (raw SQL — no typed record yet) ----
            let captureRows = try Row.fetchAll(db, sql: """
                SELECT * FROM captures
                WHERE user_id = ? AND (deleted_at IS NULL OR dirty = 1)
                """, arguments: [userId])
            for row in captureRows where (row["deleted_at"] as String?) == nil {
                let payload = CapturePayloadV2(
                    id:         row["id"],
                    userId:     row["user_id"],
                    title:      row["title"] as String?,
                    pdfUri:     row["pdf_uri"],
                    previewUri: row["preview_uri"] as String?,
                    pageCount:  row["page_count"],
                    createdAt:  row["created_at"],
                    updatedAt:  row["updated_at"]
                )
                if let entry = try Self.makeEntry(
                    id: row["id"],
                    kind: DrivePath.kindCapture,
                    drivePath: DrivePath.capture(id: row["id"]),
                    updatedAt: row["updated_at"],
                    encodable: payload
                ) { out.append(entry) }
            }

            // ---- ocr_results (raw SQL; not user-scoped — FK to captures) ----
            let ocrRows = try Row.fetchAll(db, sql: """
                SELECT * FROM ocr_results
                WHERE deleted_at IS NULL OR dirty = 1
                """)
            for row in ocrRows where (row["deleted_at"] as String?) == nil {
                let payload = OcrResultPayloadV2(
                    id:            row["id"],
                    captureId:     row["capture_id"],
                    pageIndex:     row["page_index"],
                    language:      row["language"] as String?,
                    confidence:    row["confidence"] as Double?,
                    text:          row["text"],
                    blocks:        JSONAny.parseOrEmptyArray(row["blocks_json"]),
                    engine:        row["engine"],
                    engineVersion: row["engine_version"] as String?,
                    createdAt:     row["created_at"],
                    updatedAt:     row["updated_at"]
                )
                if let entry = try Self.makeEntry(
                    id: row["id"],
                    kind: DrivePath.kindOcrResult,
                    drivePath: DrivePath.ocrResult(captureId: row["capture_id"], pageIndex: row["page_index"]),
                    updatedAt: row["updated_at"],
                    encodable: payload
                ) { out.append(entry) }
            }

            return out
        }

        return DirtyBatch(entries: entries, nextCursor: nil)
    }

    public func nextTombstoneBatch(after cursor: SyncCursor?, limit: Int) async throws -> TombstoneBatch {
        if cursor != nil {
            return TombstoneBatch(entries: [], nextCursor: nil)
        }

        let userId = self.userId
        let entries = try await database.dbQueue.read { db -> [PendingTombstone] in
            var out: [PendingTombstone] = []

            // notepad_entries — user-scoped.
            let notepadRows = try Row.fetchAll(db, sql: """
                SELECT id, deleted_at, updated_at FROM notepad_entries
                WHERE user_id = ? AND deleted_at IS NOT NULL AND dirty = 1
                """, arguments: [userId])
            for row in notepadRows {
                out.append(PendingTombstone(
                    kind: DrivePath.kindNotepadEntry,
                    id: row["id"],
                    deletedAt: (row["deleted_at"] as String?) ?? row["updated_at"]
                ))
            }

            // captures — user-scoped.
            let captureRows = try Row.fetchAll(db, sql: """
                SELECT id, deleted_at, updated_at FROM captures
                WHERE user_id = ? AND deleted_at IS NOT NULL AND dirty = 1
                """, arguments: [userId])
            for row in captureRows {
                out.append(PendingTombstone(
                    kind: DrivePath.kindCapture,
                    id: row["id"],
                    deletedAt: (row["deleted_at"] as String?) ?? row["updated_at"]
                ))
            }

            // ocr_results — not user-scoped at the row level.
            let ocrRows = try Row.fetchAll(db, sql: """
                SELECT id, deleted_at, updated_at FROM ocr_results
                WHERE deleted_at IS NOT NULL AND dirty = 1
                """)
            for row in ocrRows {
                out.append(PendingTombstone(
                    kind: DrivePath.kindOcrResult,
                    id: row["id"],
                    deletedAt: (row["deleted_at"] as String?) ?? row["updated_at"]
                ))
            }

            return out
        }

        return TombstoneBatch(entries: entries, nextCursor: nil)
    }

    // MARK: - Inbound: apply remote changes

    public func applyRemoteUpsert(_ change: RemoteUpsert) async throws {
        let decoder = JSONDecoder()
        let driveFileId = change.driveFileId.isEmpty ? nil : change.driveFileId

        try await database.dbQueue.write { db in
            switch change.kind {
            case DrivePath.kindNotepadEntry:
                let p = try decoder.decode(NotepadEntryPayloadV2.self, from: change.payload)
                var row = p.toEntity(driveFileId: driveFileId)
                row.dirty = false
                try row.save(db)

            case DrivePath.kindCapture:
                let p = try decoder.decode(CapturePayloadV2.self, from: change.payload)
                try Self.upsertCaptureRow(db, payload: p, driveFileId: driveFileId)

            case DrivePath.kindOcrResult:
                let p = try decoder.decode(OcrResultPayloadV2.self, from: change.payload)
                try Self.upsertOcrResultRow(db, payload: p, driveFileId: driveFileId)

            default:
                // Forward-compat: unknown kind, skip.
                break
            }
        }
    }

    public func applyRemoteTombstone(_ tombstone: RemoteTombstone) async throws {
        let table = Self.tableFor(kind: tombstone.kind)
        guard !table.isEmpty else { return }
        try await database.dbQueue.write { db in
            try db.execute(sql: """
                UPDATE \(table)
                SET deleted_at = ?, updated_at = ?, dirty = 0
                WHERE id = ?
                """, arguments: [tombstone.deletedAt, tombstone.deletedAt, tombstone.id])
        }
    }

    // MARK: - Bookkeeping

    public func markSynced(_ acks: [SyncAck]) async throws {
        guard !acks.isEmpty else { return }
        try await database.dbQueue.write { db in
            for ack in acks {
                let table = Self.tableFor(kind: ack.kind)
                guard !table.isEmpty else { continue }

                // Race-safe clear of the dirty bit. Same pattern Releaf's
                // iOS uses — only flip dirty=0 if updated_at still
                // matches; otherwise the row was edited mid-upload and
                // the next pass picks it up fresh.
                try db.execute(sql: """
                    UPDATE \(table)
                    SET dirty = 0, drive_file_id = ?
                    WHERE id = ?
                      AND dirty = 1
                      AND updated_at = ?
                    """, arguments: [ack.driveFileId, ack.id, ack.updatedAt])

                // Tombstone-clear path — no updated_at guard. Same
                // pattern as Android's `markTombstoneSynced`.
                try db.execute(sql: """
                    UPDATE \(table)
                    SET dirty = 0
                    WHERE id = ? AND deleted_at IS NOT NULL
                    """, arguments: [ack.id])
            }
        }
    }

    public func lastAppliedManifestEtag() async throws -> String? {
        // v1: always pulls the manifest. v2 etag-skip lands later.
        nil
    }

    public func setLastAppliedManifestEtag(_ etag: String) async throws {
        // No-op until v2 etag tracking lands.
    }

    // MARK: - Helpers

    private static func tableFor(kind: String) -> String {
        switch kind {
        case DrivePath.kindNotepadEntry: return "notepad_entries"
        case DrivePath.kindCapture:      return "captures"
        case DrivePath.kindOcrResult:    return "ocr_results"
        default: return ""
        }
    }

    private static func makeEntry<T: Encodable>(
        id: String,
        kind: String,
        drivePath: String,
        updatedAt: String,
        encodable: T
    ) throws -> DirtyEntry? {
        let bytes = try CanonicalJson.encodeToData(encodable: encodable)
        return DirtyEntry(
            kind:          kind,
            id:            id,
            drivePath:     drivePath,
            payload:       bytes,
            payloadSha256: sha256Hex(bytes),
            updatedAt:     updatedAt
        )
    }

    /// Upsert a captures row from a remote payload. Raw SQL because we
    /// don't have a typed `CaptureRow` GRDB record yet — see file header.
    private static func upsertCaptureRow(_ db: Database, payload: CapturePayloadV2, driveFileId: String?) throws {
        try db.execute(sql: """
            INSERT INTO captures (
                id, user_id, title, pdf_uri, preview_uri, page_count,
                drive_file_id, created_at, updated_at, dirty
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
            ON CONFLICT(id) DO UPDATE SET
                user_id       = excluded.user_id,
                title         = excluded.title,
                pdf_uri       = excluded.pdf_uri,
                preview_uri   = excluded.preview_uri,
                page_count    = excluded.page_count,
                drive_file_id = excluded.drive_file_id,
                updated_at    = excluded.updated_at,
                dirty         = 0
            WHERE captures.updated_at < excluded.updated_at
            """, arguments: [
                payload.id, payload.userId, payload.title,
                payload.pdfUri, payload.previewUri, payload.pageCount,
                driveFileId, payload.createdAt, payload.updatedAt,
            ])
    }

    /// Upsert an ocr_results row. Same raw-SQL story; `blocks_json` is
    /// re-serialised from the wire `JSONAny` into the column's compact
    /// string format.
    private static func upsertOcrResultRow(_ db: Database, payload: OcrResultPayloadV2, driveFileId: String?) throws {
        let blocksJson = payload.blocks.toCompactString()
        try db.execute(sql: """
            INSERT INTO ocr_results (
                id, capture_id, page_index, language, confidence,
                text, blocks_json, engine, engine_version, drive_file_id,
                created_at, updated_at, dirty
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
            ON CONFLICT(id) DO UPDATE SET
                capture_id     = excluded.capture_id,
                page_index     = excluded.page_index,
                language       = excluded.language,
                confidence     = excluded.confidence,
                text           = excluded.text,
                blocks_json    = excluded.blocks_json,
                engine         = excluded.engine,
                engine_version = excluded.engine_version,
                drive_file_id  = excluded.drive_file_id,
                updated_at     = excluded.updated_at,
                dirty          = 0
            WHERE ocr_results.updated_at < excluded.updated_at
            """, arguments: [
                payload.id, payload.captureId, payload.pageIndex,
                payload.language, payload.confidence, payload.text,
                blocksJson, payload.engine, payload.engineVersion,
                driveFileId, payload.createdAt, payload.updatedAt,
            ])
    }
}
