/*
 * ReleafSyncDataSource.swift
 *
 * Releaf's implementation of `SyncDataSource` (in ReleafCoreSync).
 *
 * This file contains the Releaf-coupled code that USED to live inside
 * the old monolithic `apps/releaf/ios/Releaf/Data/Sync/SyncRepository.swift`
 * — buildLocalSnapshot, applyRemotePayload, applyRemoteTombstone,
 * markSyncedLocally, markTombstoneSyncedLocally, tableFor — pulled
 * out behind the SyncDataSource protocol so the orchestrator
 * (now in ReleafCore) can serve QuickInk too.
 *
 * Pagination policy: this implementation collects ALL dirty rows /
 * tombstones into a single batch and returns nil cursor on the first
 * call. That matches the prior single-snapshot behavior. The
 * SyncDataSource contract allows paginated implementations; we'll
 * paginate the day a Releaf user has more dirty rows than fits in
 * one ~4 MB batch. (Cursor-paginated implementation goes here, not
 * in the shared module.)
 *
 * See:
 *   - shared/ios/ReleafCore/Sources/ReleafCoreSync/SyncDataSource.swift
 *     (the protocol)
 *   - docs/QUICKINK_DESIGN.md §1 (the design rationale + Releaf-side
 *     implementation notes)
 */

import Foundation
import GRDB
import ReleafCoreData
import ReleafCoreDrive
import ReleafCoreSync

public final class ReleafSyncDataSource: SyncDataSource, @unchecked Sendable {

    private let database: ReleafDatabase
    private let userId: String

    public init(database: ReleafDatabase = .shared, userId: String) {
        self.database = database
        self.userId = userId
    }

    // MARK: - Identity

    public let driveRootFolderName: String = "Releaf"

    public let schemaVersion: SchemaVersion = .current

    public let appId: String = "releaf"

    // MARK: - Outbound: collect dirty rows

    public func nextDirtyBatch(after cursor: SyncCursor?, limit: Int) async throws -> DirtyBatch {
        // Single-batch implementation — see file header.
        if cursor != nil {
            return DirtyBatch(entries: [], nextCursor: nil)
        }

        let entries = try await database.dbQueue.read { db -> [DirtyEntry] in
            var out: [DirtyEntry] = []

            // ---- notebooks ----
            let notebooks = try NotebookEntity
                .filter(sql: "deleted_at IS NULL OR dirty = 1")
                .fetchAll(db)
            for row in notebooks where row.deletedAt == nil {
                if let entry = try Self.makeEntry(
                    id: row.id,
                    kind: DrivePath.kindNotebook,
                    drivePath: DrivePath.notebook(id: row.id),
                    updatedAt: row.updatedAt,
                    encodable: row.toV2Payload()
                ) { out.append(entry) }
            }

            // ---- chapters ----
            let chapters = try ChapterEntity
                .filter(sql: "deleted_at IS NULL OR dirty = 1")
                .fetchAll(db)
            for row in chapters where row.deletedAt == nil {
                if let entry = try Self.makeEntry(
                    id: row.id,
                    kind: DrivePath.kindChapter,
                    drivePath: DrivePath.chapter(id: row.id),
                    updatedAt: row.updatedAt,
                    encodable: row.toV2Payload()
                ) { out.append(entry) }
            }

            // ---- pages ----
            let pages = try PageEntity
                .filter(sql: "deleted_at IS NULL OR dirty = 1")
                .fetchAll(db)
            for row in pages where row.deletedAt == nil {
                if let entry = try Self.makeEntry(
                    id: row.id,
                    kind: DrivePath.kindPage,
                    drivePath: DrivePath.page(id: row.id),
                    updatedAt: row.updatedAt,
                    encodable: row.toV2Payload()
                ) { out.append(entry) }
            }

            // ---- notepad entries ----
            let notepad = try NotepadEntry
                .filter(sql: "user_id = ? AND (deleted_at IS NULL OR dirty = 1)",
                        arguments: [self.userId])
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

            // ---- tasks ----
            let tasks = try TaskRecord
                .filter(sql: "user_id = ? AND (deleted_at IS NULL OR dirty = 1)",
                        arguments: [self.userId])
                .fetchAll(db)
            for row in tasks where row.deletedAt == nil {
                if let entry = try Self.makeEntry(
                    id: row.id,
                    kind: DrivePath.kindTask,
                    drivePath: DrivePath.task(id: row.id),
                    updatedAt: row.updatedAt,
                    encodable: row.toV2Payload()
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

        let entries = try await database.dbQueue.read { db -> [PendingTombstone] in
            var out: [PendingTombstone] = []

            // Walk every soft-deleted, dirty row across the entity tables.
            // Same set as nextDirtyBatch.
            let pairs: [(table: String, kind: String)] = [
                (NotebookEntity.databaseTableName, DrivePath.kindNotebook),
                (ChapterEntity.databaseTableName,  DrivePath.kindChapter),
                (PageEntity.databaseTableName,     DrivePath.kindPage),
                (NotepadEntry.databaseTableName,   DrivePath.kindNotepadEntry),
                (TaskRecord.databaseTableName,     DrivePath.kindTask),
            ]
            for (table, kind) in pairs {
                let rows = try Row.fetchAll(db, sql: """
                    SELECT id, deleted_at, updated_at
                    FROM \(table)
                    WHERE deleted_at IS NOT NULL AND dirty = 1
                    """)
                for row in rows {
                    let id: String       = row["id"]
                    let deletedAt: String = (row["deleted_at"] as String?) ?? (row["updated_at"] as String? ?? IsoClock.nowIso())
                    out.append(PendingTombstone(kind: kind, id: id, deletedAt: deletedAt))
                }
            }
            return out
        }

        return TombstoneBatch(entries: entries, nextCursor: nil)
    }

    // MARK: - Inbound: apply remote changes

    public func applyRemoteUpsert(_ change: RemoteUpsert) async throws {
        let decoder = JSONDecoder()
        try await database.dbQueue.write { db in
            switch change.kind {
            case DrivePath.kindNotebook:
                let p = try decoder.decode(NotebookPayloadV2.self, from: change.payload)
                var row = p.toEntity(driveFileId: change.driveFileId.isEmpty ? nil : change.driveFileId)
                row.dirty = false
                try row.save(db)
            case DrivePath.kindChapter:
                let p = try decoder.decode(ChapterPayloadV2.self, from: change.payload)
                var row = p.toEntity(driveFileId: change.driveFileId.isEmpty ? nil : change.driveFileId)
                row.dirty = false
                try row.save(db)
            case DrivePath.kindPage:
                let p = try decoder.decode(PagePayloadV2.self, from: change.payload)
                var row = p.toEntity(driveFileId: change.driveFileId.isEmpty ? nil : change.driveFileId)
                row.dirty = false
                try row.save(db)
            case DrivePath.kindNotepadEntry:
                let p = try decoder.decode(NotepadEntryPayloadV2.self, from: change.payload)
                var row = p.toEntity(driveFileId: change.driveFileId.isEmpty ? nil : change.driveFileId)
                row.dirty = false
                try row.save(db)
            case DrivePath.kindTask:
                let p = try decoder.decode(TaskPayloadV2.self, from: change.payload)
                var row = p.toEntity()
                row.dirty = false
                try row.save(db)
            default:
                // Forward-compat: kind we don't recognize, skip.
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

                // Race-safe: only flip dirty=0 if updated_at still
                // matches the value the row had when we read it for
                // upload. If something edited the row in between,
                // dirty stays 1 and the next pass picks it up.
                if ack.kind == DrivePath.kindTask {
                    // tasks table doesn't have drive_file_id column.
                    try db.execute(sql: """
                        UPDATE \(table)
                        SET dirty = 0
                        WHERE id = ?
                          AND dirty = 1
                          AND updated_at = ?
                        """, arguments: [ack.id, ack.updatedAt])
                } else {
                    let driveFileId = ack.driveFileId
                    try db.execute(sql: """
                        UPDATE \(table)
                        SET dirty = 0, drive_file_id = ?
                        WHERE id = ?
                          AND dirty = 1
                          AND updated_at = ?
                        """, arguments: [driveFileId, ack.id, ack.updatedAt])
                }
            }
        }
    }

    public func lastAppliedManifestEtag() async throws -> String? {
        // Etag-based skip-pull is a v2 optimization. v1 always pulls.
        // Returning nil here means the worker fetches the manifest
        // every pass — same as today's behavior.
        nil
    }

    public func setLastAppliedManifestEtag(_ etag: String) async throws {
        // No-op until v2 etag tracking lands; see above.
    }

    // MARK: - Helpers

    private static func tableFor(kind: String) -> String {
        switch kind {
        case DrivePath.kindNotebook:      return NotebookEntity.databaseTableName
        case DrivePath.kindChapter:       return ChapterEntity.databaseTableName
        case DrivePath.kindPage:          return PageEntity.databaseTableName
        case DrivePath.kindNotepadEntry:  return NotepadEntry.databaseTableName
        case DrivePath.kindTask:          return TaskRecord.databaseTableName
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
            kind: kind,
            id: id,
            drivePath: drivePath,
            payload: bytes,
            payloadSha256: sha256Hex(bytes),
            updatedAt: updatedAt
        )
    }
}
