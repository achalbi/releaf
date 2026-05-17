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
import ReleafCoreData
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

    /// Drive folder layout — `Thoughtbasics/QuickInk/...`. The
    /// "Thoughtbasics" wrapper holds future thoughts-line apps; the
    /// QuickInk subfolder is the actual app root that all paths
    /// (captures, ocr_results, notepad_entries, categories,
    /// tombstones, manifest.json) hang off. `ensureRootFolder` on
    /// the Drive client walks slash-separated paths automatically.
    public let driveRootFolderName: String = "Thoughtbasics/QuickInk"

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
                    id:                 row["id"],
                    userId:             row["user_id"],
                    title:              row["title"] as String?,
                    pdfUri:             row["pdf_uri"],
                    previewUri:         row["preview_uri"] as String?,
                    pageCount:          row["page_count"],
                    // Legacy slot — always nil post-A.3c (column
                    // dropped). See `CapturePayloadV2.category`.
                    category:           nil,
                    pdfDriveFileId:     row["pdf_drive_file_id"] as String?,
                    previewDriveFileId: row["preview_drive_file_id"] as String?,
                    // Older rows pre-v4 had no source column; the
                    // ALTER TABLE default (`'scan'`) means SELECT
                    // always returns a value, but tolerate a nil
                    // read for safety so an unexpected schema state
                    // doesn't crash the sync export.
                    source:             (row["source"] as String?) ?? "scan",
                    // `paper_size` landed in v11; same back-compat
                    // tolerance — fall back to "a4" if the column
                    // is absent from the row read.
                    paperSize:          (row["paper_size"] as String?) ?? "a4",
                    latitude:           row["latitude"] as Double?,
                    longitude:          row["longitude"] as Double?,
                    locality:           row["locality"] as String?,
                    subLocality:        row["sub_locality"] as String?,
                    address:            row["address"] as String?,
                    notes:              row["notes"] as String?,
                    createdAt:          row["created_at"],
                    updatedAt:          row["updated_at"]
                )
                if let entry = try Self.makeEntry(
                    id: row["id"],
                    kind: DrivePath.kindCapture,
                    drivePath: DrivePath.quickInkCapture(
                        createdAt: row["created_at"],
                        id:        row["id"]
                    ),
                    updatedAt: row["updated_at"],
                    encodable: payload
                ) { out.append(entry) }
            }

            // ---- tags (typed record). Wire kind stays kindCategory
            // for back-compat with older clients on Drive; new
            // payloads land under `tags/` (DrivePath.tag) — readers
            // resolve via the manifest's per-row path. Cleanup of
            // orphaned `categories/` files is a follow-up after the
            // brief's two-week soak.
            let tagRows = try TagEntity
                .filter(Column("user_id") == userId)
                .filter(Column("dirty") == true)
                .filter(Column("deleted_at") == nil)
                .fetchAll(db)
            for row in tagRows {
                let payload = TagPayloadV1(
                    id:        row.id,
                    userId:    row.userId,
                    name:      row.name,
                    position:  row.position,
                    color:     row.color,
                    createdAt: row.createdAt,
                    updatedAt: row.updatedAt
                )
                if let entry = try Self.makeEntry(
                    id: row.id,
                    kind: DrivePath.kindCategory,
                    drivePath: DrivePath.tag(id: row.id),
                    updatedAt: row.updatedAt,
                    encodable: payload
                ) { out.append(entry) }
            }

            // ---- folders (Workspace v1) ----
            let folderRows = try FolderEntity
                .filter(Column("user_id") == userId)
                .filter(Column("dirty") == true)
                .filter(Column("deleted_at") == nil)
                .fetchAll(db)
            for row in folderRows {
                let payload = FolderPayloadV1(
                    id:        row.id,
                    userId:    row.userId,
                    name:      row.name,
                    color:     row.color,
                    position:  row.position,
                    coverUri:  row.coverUri,
                    isDefault: row.isDefault,
                    isShared:  row.isShared,
                    createdAt: row.createdAt,
                    updatedAt: row.updatedAt
                )
                if let entry = try Self.makeEntry(
                    id: row.id,
                    kind: DrivePath.kindFolder,
                    drivePath: DrivePath.folder(id: row.id),
                    updatedAt: row.updatedAt,
                    encodable: payload
                ) { out.append(entry) }
            }

            // ---- capture_tags. FK to captures handles ownership;
            // no user_id column on the join row itself, so the dirty
            // filter alone is enough. ----
            let captureTagRows = try CaptureTagEntity
                .filter(Column("dirty") == true)
                .filter(Column("deleted_at") == nil)
                .fetchAll(db)
            for row in captureTagRows {
                let payload = CaptureTagPayloadV1(
                    id:        row.id,
                    captureId: row.captureId,
                    tagId:     row.tagId,
                    source:    row.source,
                    createdAt: row.createdAt,
                    updatedAt: row.updatedAt
                )
                if let entry = try Self.makeEntry(
                    id: row.id,
                    kind: DrivePath.kindCaptureTag,
                    drivePath: DrivePath.captureTag(id: row.id),
                    updatedAt: row.updatedAt,
                    encodable: payload
                ) { out.append(entry) }
            }

            // ---- smart_collections (Workspace v1) ----
            let smartCollectionRows = try SmartCollectionEntity
                .filter(Column("user_id") == userId)
                .filter(Column("dirty") == true)
                .filter(Column("deleted_at") == nil)
                .fetchAll(db)
            for row in smartCollectionRows {
                let payload = SmartCollectionPayloadV1(
                    id:        row.id,
                    userId:    row.userId,
                    name:      row.name,
                    icon:      row.icon,
                    color:     row.color,
                    ruleJson:  row.ruleJson,
                    position:  row.position,
                    isSeeded:  row.isSeeded,
                    createdAt: row.createdAt,
                    updatedAt: row.updatedAt
                )
                if let entry = try Self.makeEntry(
                    id: row.id,
                    kind: DrivePath.kindSmartCollection,
                    drivePath: DrivePath.smartCollection(id: row.id),
                    updatedAt: row.updatedAt,
                    encodable: payload
                ) { out.append(entry) }
            }

            // ---- voice_notes (typed via GRDB) ----
            let voiceNoteRows = try VoiceNoteEntity
                .filter(Column("user_id") == userId)
                .filter(Column("deleted_at") == nil)
                .fetchAll(db)
            let dirtyVoiceNoteIds = try Set(VoiceNoteEntity
                .filter(Column("user_id") == userId)
                .filter(Column("dirty") == true)
                .fetchAll(db)
                .map(\.id))
            let voiceNotesToPush = voiceNoteRows.filter { dirtyVoiceNoteIds.contains($0.id) }
            for row in voiceNotesToPush {
                let payload = VoiceNotePayloadV1(
                    id:                  row.id,
                    captureId:           row.captureId,
                    userId:              row.userId,
                    audioUri:            row.audioUri,
                    durationMs:          row.durationMs,
                    transcription:       row.transcription,
                    transcriptionSource: row.transcriptionSource,
                    audioDriveFileId:    row.audioDriveFileId,
                    createdAt:           row.createdAt,
                    updatedAt:           row.updatedAt
                )
                if let entry = try Self.makeEntry(
                    id: row.id,
                    kind: DrivePath.kindVoiceNote,
                    drivePath: DrivePath.quickInkVoiceNote(
                        createdAt: row.createdAt,
                        captureId: row.captureId,
                        id:        row.id
                    ),
                    updatedAt: row.updatedAt,
                    encodable: payload
                ) { out.append(entry) }
            }

            // ---- profile_settings (single row per user) ----
            let profileRows = try Row.fetchAll(db, sql: """
                SELECT * FROM profile_settings
                WHERE user_id = ? AND dirty = 1 AND deleted_at IS NULL
                """, arguments: [userId])
            for row in profileRows {
                let payload = ProfileSettingsPayloadV1(
                    id:                   row["id"],
                    userId:               row["user_id"],
                    displayName:          row["display_name"] as String?,
                    phoneNumber:          row["phone_number"] as String?,
                    personalityPunchline: row["personality_punchline"] as String?,
                    photoDriveFileId:     row["photo_drive_file_id"] as String?,
                    photoUpdatedAt:       row["photo_updated_at"] as String?,
                    createdAt:            row["created_at"],
                    updatedAt:            row["updated_at"]
                )
                if let entry = try Self.makeEntry(
                    id:        row["id"],
                    kind:      DrivePath.kindProfileSettings,
                    drivePath: DrivePath.profileSettings(id: row["id"]),
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
                    drivePath: DrivePath.quickInkOcrResult(
                        createdAt:  row["created_at"],
                        captureId:  row["capture_id"],
                        pageIndex:  row["page_index"]
                    ),
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

            // categories — user-scoped.
            let categoryTombstones = try Row.fetchAll(db, sql: """
                SELECT id, deleted_at, updated_at FROM tags
                WHERE user_id = ? AND deleted_at IS NOT NULL AND dirty = 1
                """, arguments: [userId])
            for row in categoryTombstones {
                out.append(PendingTombstone(
                    kind: DrivePath.kindCategory,
                    id: row["id"],
                    deletedAt: (row["deleted_at"] as String?) ?? row["updated_at"]
                ))
            }

            // folders / capture_tags / smart_collections — Workspace v1
            // tombstones.
            let folderTombstones = try Row.fetchAll(db, sql: """
                SELECT id, deleted_at, updated_at FROM folders
                WHERE user_id = ? AND deleted_at IS NOT NULL AND dirty = 1
                """, arguments: [userId])
            for row in folderTombstones {
                out.append(PendingTombstone(
                    kind: DrivePath.kindFolder,
                    id: row["id"],
                    deletedAt: (row["deleted_at"] as String?) ?? row["updated_at"]
                ))
            }
            let captureTagTombstones = try Row.fetchAll(db, sql: """
                SELECT id, deleted_at, updated_at FROM capture_tags
                WHERE deleted_at IS NOT NULL AND dirty = 1
                """)
            for row in captureTagTombstones {
                out.append(PendingTombstone(
                    kind: DrivePath.kindCaptureTag,
                    id: row["id"],
                    deletedAt: (row["deleted_at"] as String?) ?? row["updated_at"]
                ))
            }
            let smartCollectionTombstones = try Row.fetchAll(db, sql: """
                SELECT id, deleted_at, updated_at FROM smart_collections
                WHERE user_id = ? AND deleted_at IS NOT NULL AND dirty = 1
                """, arguments: [userId])
            for row in smartCollectionTombstones {
                out.append(PendingTombstone(
                    kind: DrivePath.kindSmartCollection,
                    id: row["id"],
                    deletedAt: (row["deleted_at"] as String?) ?? row["updated_at"]
                ))
            }

            // voice_notes — user-scoped.
            let voiceTombstones = try Row.fetchAll(db, sql: """
                SELECT id, deleted_at, updated_at FROM voice_notes
                WHERE user_id = ? AND deleted_at IS NOT NULL AND dirty = 1
                """, arguments: [userId])
            for row in voiceTombstones {
                out.append(PendingTombstone(
                    kind: DrivePath.kindVoiceNote,
                    id: row["id"],
                    deletedAt: (row["deleted_at"] as String?) ?? row["updated_at"]
                ))
            }

            // profile_settings — user-scoped.
            let profileTombstones = try Row.fetchAll(db, sql: """
                SELECT id, deleted_at, updated_at FROM profile_settings
                WHERE user_id = ? AND deleted_at IS NOT NULL AND dirty = 1
                """, arguments: [userId])
            for row in profileTombstones {
                out.append(PendingTombstone(
                    kind:      DrivePath.kindProfileSettings,
                    id:        row["id"],
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

            case DrivePath.kindCategory:
                let p = try decoder.decode(TagPayloadV1.self, from: change.payload)
                try Self.upsertCategoryRow(db, payload: p, driveFileId: driveFileId)

            case DrivePath.kindFolder:
                let p = try decoder.decode(FolderPayloadV1.self, from: change.payload)
                try Self.upsertFolderRow(db, payload: p, driveFileId: driveFileId)

            case DrivePath.kindCaptureTag:
                let p = try decoder.decode(CaptureTagPayloadV1.self, from: change.payload)
                try Self.upsertCaptureTagRow(db, payload: p, driveFileId: driveFileId)

            case DrivePath.kindSmartCollection:
                let p = try decoder.decode(SmartCollectionPayloadV1.self, from: change.payload)
                try Self.upsertSmartCollectionRow(db, payload: p, driveFileId: driveFileId)

            case DrivePath.kindVoiceNote:
                let p = try decoder.decode(VoiceNotePayloadV1.self, from: change.payload)
                try Self.upsertVoiceNoteRow(db, payload: p, driveFileId: driveFileId)

            case DrivePath.kindProfileSettings:
                let p = try decoder.decode(ProfileSettingsPayloadV1.self, from: change.payload)
                // photo_local_uri is intentionally not in the wire payload;
                // leave the existing local URI intact (the binary-restore
                // pass fills it in when needed). Only the metadata fields
                // and the photo Drive-file reference travel on the wire.
                try Self.upsertProfileSettingsRow(db, payload: p, driveFileId: driveFileId)

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

    /// Cheap aggregate count of every locally-dirty row that the
    /// sync worker would push on its next pass — across notepad
    /// entries, captures, ocr_results, and categories. Drives the
    /// "N pending" pill on Home and the auto-safety-net's decision
    /// to fire `requestImmediate`. Mirror of Android's
    /// `QuickInkApp.countLocalDirty(userId:)`.
    ///
    /// `ocr_results` is intentionally NOT user-scoped here — the
    /// table doesn't carry a `user_id` column (rows FK into
    /// `captures` and inherit user via that join). The dirty count
    /// across all OCR rows is a tight upper bound; in single-user
    /// installs it equals the per-user count, and the result is
    /// only ever shown as "N pending", so a small over-count is
    /// fine.
    public func countDirtyRows(userId: String) async throws -> Int {
        try await database.dbQueue.read { db -> Int in
            let notepad: Int = (try? Int.fetchOne(db, sql: """
                SELECT COUNT(*) FROM notepad_entries
                WHERE user_id = ? AND dirty = 1
                """, arguments: [userId])) ?? 0
            let captures: Int = (try? Int.fetchOne(db, sql: """
                SELECT COUNT(*) FROM captures
                WHERE user_id = ? AND dirty = 1
                """, arguments: [userId])) ?? 0
            let ocr: Int = (try? Int.fetchOne(db, sql: """
                SELECT COUNT(*) FROM ocr_results
                WHERE dirty = 1
                """)) ?? 0
            let categories: Int = (try? Int.fetchOne(db, sql: """
                SELECT COUNT(*) FROM tags
                WHERE user_id = ? AND dirty = 1
                """, arguments: [userId])) ?? 0
            let profileSettings: Int = (try? Int.fetchOne(db, sql: """
                SELECT COUNT(*) FROM profile_settings
                WHERE user_id = ? AND dirty = 1
                """, arguments: [userId])) ?? 0
            return notepad + captures + ocr + categories + profileSettings
        }
    }

    // MARK: - Helpers

    private static func tableFor(kind: String) -> String {
        switch kind {
        case DrivePath.kindNotepadEntry:    return "notepad_entries"
        case DrivePath.kindCapture:         return "captures"
        case DrivePath.kindOcrResult:       return "ocr_results"
        case DrivePath.kindCategory:        return "tags"
        case DrivePath.kindFolder:          return "folders"
        case DrivePath.kindCaptureTag:      return "capture_tags"
        case DrivePath.kindSmartCollection: return "smart_collections"
        case DrivePath.kindVoiceNote:          return "voice_notes"
        case DrivePath.kindProfileSettings:   return "profile_settings"
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

    /// Upsert a tags row from a remote payload. Last-write-wins
    /// on `updated_at`. The wire kind stays `kindCategory` (back-
    /// compat) but the table is `tags` post-v8.
    private static func upsertCategoryRow(_ db: Database, payload: TagPayloadV1, driveFileId: String?) throws {
        try db.execute(sql: """
            INSERT INTO tags (
                id, user_id, name, position, color,
                drive_file_id, created_at, updated_at, dirty
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0)
            ON CONFLICT(id) DO UPDATE SET
                user_id       = excluded.user_id,
                name          = excluded.name,
                position      = excluded.position,
                color         = excluded.color,
                drive_file_id = excluded.drive_file_id,
                updated_at    = excluded.updated_at,
                dirty         = 0
            WHERE tags.updated_at < excluded.updated_at
            """, arguments: [
                payload.id, payload.userId, payload.name, payload.position,
                payload.color,
                driveFileId, payload.createdAt, payload.updatedAt,
            ])
    }

    private static func upsertFolderRow(_ db: Database, payload: FolderPayloadV1, driveFileId: String?) throws {
        try db.execute(sql: """
            INSERT INTO folders (
                id, user_id, name, color, position,
                cover_uri, is_default, is_shared,
                drive_file_id, created_at, updated_at, dirty
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
            ON CONFLICT(id) DO UPDATE SET
                user_id       = excluded.user_id,
                name          = excluded.name,
                color         = excluded.color,
                position      = excluded.position,
                cover_uri     = excluded.cover_uri,
                is_default    = excluded.is_default,
                is_shared     = excluded.is_shared,
                drive_file_id = excluded.drive_file_id,
                updated_at    = excluded.updated_at,
                dirty         = 0
            WHERE folders.updated_at < excluded.updated_at
            """, arguments: [
                payload.id, payload.userId, payload.name, payload.color,
                payload.position, payload.coverUri,
                payload.isDefault, payload.isShared,
                driveFileId, payload.createdAt, payload.updatedAt,
            ])
    }

    private static func upsertCaptureTagRow(_ db: Database, payload: CaptureTagPayloadV1, driveFileId: String?) throws {
        try db.execute(sql: """
            INSERT INTO capture_tags (
                id, capture_id, tag_id, source,
                drive_file_id, created_at, updated_at, dirty
            ) VALUES (?, ?, ?, ?, ?, ?, ?, 0)
            ON CONFLICT(id) DO UPDATE SET
                capture_id    = excluded.capture_id,
                tag_id        = excluded.tag_id,
                source        = excluded.source,
                drive_file_id = excluded.drive_file_id,
                updated_at    = excluded.updated_at,
                dirty         = 0
            WHERE capture_tags.updated_at < excluded.updated_at
            """, arguments: [
                payload.id, payload.captureId, payload.tagId, payload.source,
                driveFileId, payload.createdAt, payload.updatedAt,
            ])
    }

    private static func upsertSmartCollectionRow(_ db: Database, payload: SmartCollectionPayloadV1, driveFileId: String?) throws {
        try db.execute(sql: """
            INSERT INTO smart_collections (
                id, user_id, name, icon, color, rule_json, position, is_seeded,
                drive_file_id, created_at, updated_at, dirty
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
            ON CONFLICT(id) DO UPDATE SET
                user_id       = excluded.user_id,
                name          = excluded.name,
                icon          = excluded.icon,
                color         = excluded.color,
                rule_json     = excluded.rule_json,
                position      = excluded.position,
                is_seeded     = excluded.is_seeded,
                drive_file_id = excluded.drive_file_id,
                updated_at    = excluded.updated_at,
                dirty         = 0
            WHERE smart_collections.updated_at < excluded.updated_at
            """, arguments: [
                payload.id, payload.userId, payload.name, payload.icon,
                payload.color, payload.ruleJson, payload.position, payload.isSeeded,
                driveFileId, payload.createdAt, payload.updatedAt,
            ])
    }

    /// Upsert a captures row from a remote payload. Raw SQL because we
    /// don't have a typed `CaptureRow` GRDB record yet — see file header.
    private static func upsertCaptureRow(_ db: Database, payload: CapturePayloadV2, driveFileId: String?) throws {
        // CRITICAL: `pdf_uri` and `preview_uri` are device-local
        // file:// paths. The remote payload carries the SOURCE
        // device's path, which is meaningless on this device.
        // Naively replacing the row with the remote URIs is what
        // produced the "open failed: ENOENT" symptom for cross-
        // device synced captures.
        //
        // Reconcile:
        //   - If we already have this capture locally with a working
        //     file://, keep our URIs (the local binary is correct).
        //   - Otherwise, accept the remote payload but blank out
        //     URIs that won't resolve here. The next restorePending
        //     pass / on-demand open will download from Drive using
        //     pdfDriveFileId.
        let existing: (pdfUri: String, previewUri: String?)? = try Row.fetchOne(
            db,
            sql: "SELECT pdf_uri, preview_uri FROM captures WHERE id = ? LIMIT 1",
            arguments: [payload.id]
        ).map { ($0["pdf_uri"], $0["preview_uri"] as String?) }

        let resolvedPdfUri: String = {
            if let cur = existing, Self.fileExistsAt(cur.pdfUri) {
                return cur.pdfUri
            }
            if payload.pdfDriveFileId != nil {
                return "" // wait for restorePending
            }
            return payload.pdfUri
        }()
        let resolvedPreviewUri: String? = {
            if let cur = existing,
               let p = cur.previewUri,
               Self.fileExistsAt(p) {
                return p
            }
            if payload.previewDriveFileId != nil {
                return nil
            }
            return payload.previewUri
        }()

        try db.execute(sql: """
            INSERT INTO captures (
                id, user_id, title, pdf_uri, preview_uri, page_count,
                source, paper_size, drive_file_id, pdf_drive_file_id, preview_drive_file_id,
                latitude, longitude, locality, sub_locality, address, notes,
                created_at, updated_at, dirty
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
            ON CONFLICT(id) DO UPDATE SET
                user_id               = excluded.user_id,
                title                 = excluded.title,
                pdf_uri               = excluded.pdf_uri,
                preview_uri           = excluded.preview_uri,
                page_count            = excluded.page_count,
                source                = excluded.source,
                paper_size            = excluded.paper_size,
                drive_file_id         = excluded.drive_file_id,
                pdf_drive_file_id     = excluded.pdf_drive_file_id,
                preview_drive_file_id = excluded.preview_drive_file_id,
                latitude              = excluded.latitude,
                longitude             = excluded.longitude,
                locality              = excluded.locality,
                sub_locality          = excluded.sub_locality,
                address               = excluded.address,
                notes                 = excluded.notes,
                updated_at            = excluded.updated_at,
                dirty                 = 0
            WHERE captures.updated_at < excluded.updated_at
            """, arguments: [
                payload.id, payload.userId, payload.title,
                resolvedPdfUri, resolvedPreviewUri, payload.pageCount,
                payload.source, payload.paperSize, driveFileId,
                payload.pdfDriveFileId, payload.previewDriveFileId,
                payload.latitude, payload.longitude,
                payload.locality, payload.subLocality, payload.address,
                payload.notes,
                payload.createdAt, payload.updatedAt,
            ])
    }

    /// Upsert a voice_notes row from a remote payload. Reconciles
    /// `audio_uri` the same way [upsertCaptureRow] reconciles
    /// `pdf_uri`: keep the local path if the file is here, otherwise
    /// blank it out and wait for the binary-restore pass to fill in
    /// from `audio_drive_file_id`.
    private static func upsertVoiceNoteRow(_ db: Database, payload: VoiceNotePayloadV1, driveFileId: String?) throws {
        // Defensive parent-existence check — voice_notes has a FK to
        // captures with ON DELETE CASCADE; applying a payload whose
        // parent isn't local yet would raise SQLITE_CONSTRAINT.
        let parentExists = try Bool.fetchOne(
            db,
            sql: "SELECT EXISTS(SELECT 1 FROM captures WHERE id = ? AND deleted_at IS NULL)",
            arguments: [payload.captureId]
        ) ?? false
        guard parentExists else { return }

        let existingAudioUri: String? = try Row.fetchOne(
            db,
            sql: "SELECT audio_uri FROM voice_notes WHERE id = ? LIMIT 1",
            arguments: [payload.id]
        ).map { $0["audio_uri"] as String? } ?? nil

        let resolvedAudioUri: String = {
            if let cur = existingAudioUri, Self.fileExistsAt(cur) { return cur }
            if payload.audioDriveFileId != nil { return "" }
            return payload.audioUri
        }()

        try db.execute(sql: """
            INSERT INTO voice_notes (
                id, capture_id, user_id, audio_uri, duration_ms,
                transcription, transcription_source,
                drive_file_id, audio_drive_file_id,
                created_at, updated_at, dirty
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
            ON CONFLICT(id) DO UPDATE SET
                capture_id           = excluded.capture_id,
                user_id              = excluded.user_id,
                audio_uri            = excluded.audio_uri,
                duration_ms          = excluded.duration_ms,
                transcription        = excluded.transcription,
                transcription_source = excluded.transcription_source,
                drive_file_id        = excluded.drive_file_id,
                audio_drive_file_id  = excluded.audio_drive_file_id,
                updated_at           = excluded.updated_at,
                dirty                = 0
            WHERE voice_notes.updated_at < excluded.updated_at
            """, arguments: [
                payload.id, payload.captureId, payload.userId,
                resolvedAudioUri, payload.durationMs,
                payload.transcription, payload.transcriptionSource,
                driveFileId, payload.audioDriveFileId,
                payload.createdAt, payload.updatedAt,
            ])
    }

    /// Upsert a profile_settings row from a remote payload. Last-write-
    /// wins on `updated_at`. `photo_local_uri` is kept intact from the
    /// existing local row (or left NULL on a fresh insert) — the
    /// binary-restore pass fills it in separately from `photo_drive_file_id`.
    private static func upsertProfileSettingsRow(
        _ db: Database,
        payload: ProfileSettingsPayloadV1,
        driveFileId: String?
    ) throws {
        let existingPhotoLocalUri: String? = try Row.fetchOne(
            db,
            sql: "SELECT photo_local_uri FROM profile_settings WHERE id = ? LIMIT 1",
            arguments: [payload.id]
        ).map { $0["photo_local_uri"] as String? } ?? nil

        try db.execute(sql: """
            INSERT INTO profile_settings (
                id, user_id, display_name, phone_number, personality_punchline,
                photo_local_uri, photo_drive_file_id, photo_updated_at,
                drive_file_id, created_at, updated_at, dirty
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
            ON CONFLICT(id) DO UPDATE SET
                user_id               = excluded.user_id,
                display_name          = excluded.display_name,
                phone_number          = excluded.phone_number,
                personality_punchline = excluded.personality_punchline,
                photo_local_uri       = excluded.photo_local_uri,
                photo_drive_file_id   = excluded.photo_drive_file_id,
                photo_updated_at      = excluded.photo_updated_at,
                drive_file_id         = excluded.drive_file_id,
                updated_at            = excluded.updated_at,
                dirty                 = 0
            WHERE profile_settings.updated_at < excluded.updated_at
            """, arguments: [
                payload.id, payload.userId,
                payload.displayName, payload.phoneNumber, payload.personalityPunchline,
                existingPhotoLocalUri,
                payload.photoDriveFileId, payload.photoUpdatedAt,
                driveFileId, payload.createdAt, payload.updatedAt,
            ])
    }

    // MARK: - Profile bootstrap / upload-on-edit hook

    /// Seed or refresh the `profile_settings` row from the current
    /// `UserDefaults` values (same keyspace as `SettingsState`) before
    /// each upload pass. Acts as both the first-sync bootstrap and the
    /// upload-on-edit hook:
    ///   - First sync:  inserts the row if absent, marking `dirty = 1`.
    ///   - Subsequent:  updates the row if any of the four fields changed
    ///                  since the last sync, marking `dirty = 1` so the
    ///                  next metadata push carries the fresh values.
    /// Clears `photo_drive_file_id` when `photo_local_uri` changes so
    /// the binary-upload pass re-uploads the new image.
    ///
    /// Never throws — failures are swallowed so a transient DB write
    /// doesn't abort the enclosing sync pass.
    func upsertProfileFromDefaults(defaults: UserDefaults) async {
        let displayName   = defaults.string(forKey: "quickink.settings.custom_display_name")
        let phoneNumber   = defaults.string(forKey: "quickink.settings.phone_number")
        let punchline     = defaults.string(forKey: "quickink.settings.personality_punchline")
        let photoLocalUri = defaults.string(forKey: "quickink.settings.profile_photo_uri")

        let uid = userId
        try? await database.dbQueue.write { db in
            let existing = try Row.fetchOne(db, sql: """
                SELECT id, display_name, phone_number, personality_punchline,
                       photo_local_uri, photo_drive_file_id, photo_updated_at
                FROM profile_settings WHERE user_id = ? LIMIT 1
                """, arguments: [uid])
            let now = IsoClock.nowIso()

            if existing == nil {
                // First sync for this user — seed the row from UserDefaults
                // and mark dirty so the next metadata push uploads it.
                let rowId = Uuidv7.generate()
                try db.execute(sql: """
                    INSERT INTO profile_settings (
                        id, user_id, display_name, phone_number,
                        personality_punchline, photo_local_uri,
                        photo_drive_file_id, photo_updated_at,
                        drive_file_id, created_at, updated_at, dirty, deleted_at
                    ) VALUES (?, ?, ?, ?, ?, ?, NULL, ?, NULL, ?, ?, 1, NULL)
                    """, arguments: [
                        rowId, uid, displayName, phoneNumber,
                        punchline, photoLocalUri,
                        photoLocalUri != nil ? now : nil as String?,
                        now, now,
                    ])
            } else {
                let existingDisplayName  = existing?["display_name"]          as String?
                let existingPhoneNumber  = existing?["phone_number"]          as String?
                let existingPunchline    = existing?["personality_punchline"] as String?
                let existingPhotoUri     = existing?["photo_local_uri"]       as String?
                let existingPhotoDriveId = existing?["photo_drive_file_id"]   as String?
                let existingPhotoUpdAt   = existing?["photo_updated_at"]      as String?
                let profileId            = existing?["id"]                    as String? ?? ""

                let textChanged  = existingDisplayName != displayName
                                || existingPhoneNumber != phoneNumber
                                || existingPunchline   != punchline
                let photoChanged = existingPhotoUri    != photoLocalUri
                guard textChanged || photoChanged else { return }

                // When photo URI changes: clear photo_drive_file_id so
                // the binary-upload pass re-uploads the new image and
                // stamps a fresh Drive file id onto the row.
                let newPhotoDriveId = photoChanged ? nil : existingPhotoDriveId
                let newPhotoUpdAt   = photoChanged && photoLocalUri != nil
                    ? now : existingPhotoUpdAt

                let rowId = profileId
                try db.execute(sql: """
                    UPDATE profile_settings SET
                        display_name          = ?,
                        phone_number          = ?,
                        personality_punchline = ?,
                        photo_local_uri       = ?,
                        photo_drive_file_id   = ?,
                        photo_updated_at      = ?,
                        updated_at            = ?,
                        dirty                 = 1
                    WHERE id = ?
                    """, arguments: [
                        displayName, phoneNumber, punchline,
                        photoLocalUri, newPhotoDriveId, newPhotoUpdAt,
                        now, rowId,
                    ])
            }
        }
    }

    /// Best-effort filesystem-resolution check used by the cross-
    /// device URI reconciliation in [upsertCaptureRow]. Mirror of
    /// Android's `QuickInkSyncDataSource.fileExistsAt`.
    private static func fileExistsAt(_ uri: String?) -> Bool {
        guard let uri, !uri.isEmpty else { return false }
        let path: String? = {
            if let url = URL(string: uri), url.isFileURL { return url.path }
            return uri
        }()
        guard let path else { return false }
        return FileManager.default.fileExists(atPath: path)
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
