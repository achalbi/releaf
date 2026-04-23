/*
 * SyncRepository.swift
 *
 * iOS v2 Drive sync — mirror of Android's `SyncRepository.kt`. Pushes
 * every locally-dirty row to Drive, pulls remote changes back, and
 * writes the manifest last so blobs are durable before the index flips.
 *
 * Talks directly to the GRDB `DatabaseQueue` for row fetch/upsert
 * rather than going through each per-entity repository — the sync
 * pass needs cross-table access that would otherwise force every repo
 * to grow ad-hoc sync methods. Per-entity repos still own UX-facing
 * observation and mutation.
 *
 * See the Kotlin sibling for the algorithm shape. The same
 * push-then-pull-then-manifest ordering applies here.
 */

import Foundation
import GRDB

public struct SyncResult: Equatable, Sendable {
    public let uploaded: Int
    public let tombstoned: Int
    public let downloaded: Int
    public let failed: Int
    public let versionBlocked: Bool

    public init(
        uploaded: Int = 0,
        tombstoned: Int = 0,
        downloaded: Int = 0,
        failed: Int = 0,
        versionBlocked: Bool = false
    ) {
        self.uploaded = uploaded
        self.tombstoned = tombstoned
        self.downloaded = downloaded
        self.failed = failed
        self.versionBlocked = versionBlocked
    }

    public var touched: Int { uploaded + tombstoned + downloaded + failed }
}

public final class SyncRepository: @unchecked Sendable {

    private let database: ReleafDatabase
    private let driveClient: DriveClient
    private let stateStore: SyncStateStore
    private let appVersion: String

    public init(
        database: ReleafDatabase = .shared,
        driveClient: DriveClient,
        stateStore: SyncStateStore = .shared,
        appVersion: String = "0.1.0"
    ) {
        self.database = database
        self.driveClient = driveClient
        self.stateStore = stateStore
        self.appVersion = appVersion
    }

    /// Full sync pass — push local dirty rows and tombstones, pull remote
    /// changes, write manifest last.
    @discardableResult
    public func sync(
        userId: String,
        deviceId: String,
        accessToken: String
    ) async throws -> SyncResult {
        // 1. ensure root
        let root = try await driveClient.ensureRootFolder(named: DrivePath.rootFolder, accessToken: accessToken)

        // 2. pull remote manifest
        let remoteManifest = try await fetchRemoteManifest(rootFolderId: root.id, accessToken: accessToken)

        // 3. version gate
        if let rm = remoteManifest,
           rm.schemaVersion.major > SchemaVersionConstants.major {
            await MainActor.run { stateStore.recordVersionBlocked() }
            return SyncResult(versionBlocked: true)
        }

        // 4. build local snapshot
        let snapshot = try await buildLocalSnapshot(userId: userId)

        var uploaded = 0
        var tombstoned = 0
        var failed = 0

        // Seed manifest maps from remote (if any), then patch as we go.
        var checksums: [String: EntityChecksum] = remoteManifest?.entityChecksums ?? [:]
        var tombstones: [String: TombstoneEntry] = remoteManifest?.tombstones ?? [:]

        // 5a. live uploads
        for row in snapshot.liveRows {
            let remoteHash = remoteManifest?.entityChecksums[row.id]?.sha256
            let needsUpload = remoteHash != row.sha256 || row.dirty
            if !needsUpload {
                checksums[row.id] = EntityChecksum(
                    kind: row.kind, path: row.path, sha256: row.sha256, updatedAt: row.updatedAt
                )
                continue
            }
            do {
                _ = try await driveClient.uploadJSONAtPath(
                    row.bytes,
                    relativePath: row.path,
                    rootFolderId: root.id,
                    accessToken: accessToken
                )
                checksums[row.id] = EntityChecksum(
                    kind: row.kind, path: row.path, sha256: row.sha256, updatedAt: row.updatedAt
                )
                tombstones.removeValue(forKey: row.id)
                try await markSyncedLocally(row: row)
                uploaded += 1
            } catch {
                failed += 1
            }
        }

        // 5b. tombstones
        for tomb in snapshot.tombstones {
            do {
                let tombFile = TombstoneFile(
                    id: tomb.id,
                    kind: tomb.kind,
                    deletedAt: tomb.deletedAt,
                    deviceId: deviceId,
                    hardDeleteAt: nil
                )
                let encoder = JSONEncoder()
                encoder.outputFormatting = [.sortedKeys]
                let bytes = try encoder.encode(tombFile)
                _ = try await driveClient.uploadJSONAtPath(
                    bytes,
                    relativePath: DrivePath.tombstone(id: tomb.id),
                    rootFolderId: root.id,
                    accessToken: accessToken
                )
                checksums.removeValue(forKey: tomb.id)
                tombstones[tomb.id] = TombstoneEntry(
                    kind: tomb.kind,
                    deletedAt: tomb.deletedAt,
                    deviceId: deviceId,
                    hardDeleteAt: nil
                )
                try await markTombstoneSyncedLocally(tomb: tomb)
                tombstoned += 1
            } catch {
                failed += 1
            }
        }

        // 6. pull delta
        var downloaded = 0
        if let rm = remoteManifest {
            downloaded = try await pullDelta(
                remoteManifest: rm,
                localSnapshot: snapshot,
                rootFolderId: root.id,
                accessToken: accessToken
            )
        }

        // 7. write manifest last
        let nowIso = IsoClock.nowIso()
        let manifest = ManifestV2(
            appVersion: appVersion,
            deviceId: deviceId,
            lastSyncAt: nowIso,
            clientGeneratedAt: nowIso,
            entityChecksums: checksums,
            tombstones: tombstones
        )
        let manifestBytes: Data
        do {
            manifestBytes = try CanonicalJson.encodeToData(encodable: manifest)
            _ = try await driveClient.uploadJSONAtPath(
                manifestBytes,
                relativePath: DrivePath.manifest,
                rootFolderId: root.id,
                accessToken: accessToken
            )
        } catch {
            failed += 1
            // On manifest-upload failure, still record what we did locally;
            // next pass will try again.
            await MainActor.run {
                stateStore.recordSuccess(
                    lastFullSyncAt: nowIso,
                    manifestChecksum: "",
                    pendingCount: failed
                )
            }
            return SyncResult(
                uploaded: uploaded, tombstoned: tombstoned,
                downloaded: downloaded, failed: failed
            )
        }

        // 8. update local state store
        let manifestHash = sha256Hex(manifestBytes)
        await MainActor.run {
            stateStore.recordSuccess(
                lastFullSyncAt: nowIso,
                manifestChecksum: manifestHash,
                pendingCount: failed
            )
        }

        return SyncResult(
            uploaded: uploaded,
            tombstoned: tombstoned,
            downloaded: downloaded,
            failed: failed
        )
    }

    // MARK: - Manifest fetch

    private func fetchRemoteManifest(
        rootFolderId: String,
        accessToken: String
    ) async throws -> ManifestV2? {
        guard let bytes = try await driveClient.downloadBytesAtPath(
            DrivePath.manifest,
            rootFolderId: rootFolderId,
            accessToken: accessToken
        ) else { return nil }
        return try? JSONDecoder().decode(ManifestV2.self, from: bytes)
    }

    // MARK: - Local snapshot

    private func buildLocalSnapshot(userId: String) async throws -> LocalSnapshot {
        try await database.dbQueue.read { db in
            var liveRows: [LiveRow] = []
            var tombs: [Tombstone] = []

            // ---- notebooks ----
            let notebookLive = try NotebookEntity
                .filter(sql: "deleted_at IS NULL OR dirty = 1")
                .fetchAll(db)
            for row in notebookLive where row.deletedAt == nil {
                liveRows.append(try self.makeLiveRow(
                    id: row.id,
                    kind: DrivePath.kindNotebook,
                    path: DrivePath.notebook(id: row.id),
                    updatedAt: row.updatedAt,
                    dirty: row.dirty,
                    encodable: row.toV2Payload()
                ))
            }
            for row in notebookLive where row.deletedAt != nil && row.dirty {
                tombs.append(Tombstone(
                    id: row.id,
                    kind: DrivePath.kindNotebook,
                    deletedAt: row.deletedAt ?? row.updatedAt,
                    tableName: NotebookEntity.databaseTableName
                ))
            }

            // ---- chapters ----
            let chapterLive = try ChapterEntity
                .filter(sql: "deleted_at IS NULL OR dirty = 1")
                .fetchAll(db)
            for row in chapterLive where row.deletedAt == nil {
                liveRows.append(try self.makeLiveRow(
                    id: row.id,
                    kind: DrivePath.kindChapter,
                    path: DrivePath.chapter(id: row.id),
                    updatedAt: row.updatedAt,
                    dirty: row.dirty,
                    encodable: row.toV2Payload()
                ))
            }
            for row in chapterLive where row.deletedAt != nil && row.dirty {
                tombs.append(Tombstone(
                    id: row.id,
                    kind: DrivePath.kindChapter,
                    deletedAt: row.deletedAt ?? row.updatedAt,
                    tableName: ChapterEntity.databaseTableName
                ))
            }

            // ---- pages ----
            let pageLive = try PageEntity
                .filter(sql: "deleted_at IS NULL OR dirty = 1")
                .fetchAll(db)
            for row in pageLive where row.deletedAt == nil {
                liveRows.append(try self.makeLiveRow(
                    id: row.id,
                    kind: DrivePath.kindPage,
                    path: DrivePath.page(id: row.id),
                    updatedAt: row.updatedAt,
                    dirty: row.dirty,
                    encodable: row.toV2Payload()
                ))
            }
            for row in pageLive where row.deletedAt != nil && row.dirty {
                tombs.append(Tombstone(
                    id: row.id,
                    kind: DrivePath.kindPage,
                    deletedAt: row.deletedAt ?? row.updatedAt,
                    tableName: PageEntity.databaseTableName
                ))
            }

            // ---- notepad entries ----
            let notepadLive = try NotepadEntry
                .filter(sql: "user_id = ? AND (deleted_at IS NULL OR dirty = 1)",
                        arguments: [userId])
                .fetchAll(db)
            for row in notepadLive where row.deletedAt == nil {
                liveRows.append(try self.makeLiveRow(
                    id: row.id,
                    kind: DrivePath.kindNotepadEntry,
                    path: DrivePath.notepadEntry(entryDate: row.entryDate, entryId: row.id),
                    updatedAt: row.updatedAt,
                    dirty: row.dirty,
                    encodable: row.toV2Payload()
                ))
            }
            for row in notepadLive where row.deletedAt != nil && row.dirty {
                tombs.append(Tombstone(
                    id: row.id,
                    kind: DrivePath.kindNotepadEntry,
                    deletedAt: row.deletedAt ?? row.updatedAt,
                    tableName: NotepadEntry.databaseTableName
                ))
            }

            // ---- tasks ----
            let taskLive = try TaskRecord
                .filter(sql: "user_id = ? AND (deleted_at IS NULL OR dirty = 1)",
                        arguments: [userId])
                .fetchAll(db)
            for row in taskLive where row.deletedAt == nil {
                liveRows.append(try self.makeLiveRow(
                    id: row.id,
                    kind: DrivePath.kindTask,
                    path: DrivePath.task(id: row.id),
                    updatedAt: row.updatedAt,
                    dirty: row.dirty,
                    encodable: row.toV2Payload()
                ))
            }
            for row in taskLive where row.deletedAt != nil && row.dirty {
                tombs.append(Tombstone(
                    id: row.id,
                    kind: DrivePath.kindTask,
                    deletedAt: row.deletedAt ?? row.updatedAt,
                    tableName: TaskRecord.databaseTableName
                ))
            }

            return LocalSnapshot(liveRows: liveRows, tombstones: tombs)
        }
    }

    private func makeLiveRow<T: Encodable>(
        id: String,
        kind: String,
        path: String,
        updatedAt: String,
        dirty: Bool,
        encodable: T
    ) throws -> LiveRow {
        let bytes = try CanonicalJson.encodeToData(encodable: encodable)
        return LiveRow(
            id: id,
            kind: kind,
            path: path,
            bytes: bytes,
            sha256: sha256Hex(bytes),
            updatedAt: updatedAt,
            dirty: dirty
        )
    }

    private func markSyncedLocally(row: LiveRow) async throws {
        try await database.dbQueue.write { db in
            let table = self.tableFor(kind: row.kind)
            // Race-safe clear — only flip to dirty=0 if updated_at matches.
            if row.kind == DrivePath.kindTask {
                try db.execute(sql: """
                    UPDATE \(table)
                    SET dirty = 0
                    WHERE id = ?
                      AND dirty = 1
                      AND updated_at = ?
                    """, arguments: [row.id, row.updatedAt])
            } else {
                try db.execute(sql: """
                    UPDATE \(table)
                    SET dirty = 0, drive_file_id = ''
                    WHERE id = ?
                      AND dirty = 1
                      AND updated_at = ?
                    """, arguments: [row.id, row.updatedAt])
            }
        }
    }

    private func markTombstoneSyncedLocally(tomb: Tombstone) async throws {
        try await database.dbQueue.write { db in
            try db.execute(sql: """
                UPDATE \(tomb.tableName)
                SET dirty = 0
                WHERE id = ? AND deleted_at IS NOT NULL
                """, arguments: [tomb.id])
        }
    }

    private func tableFor(kind: String) -> String {
        switch kind {
        case DrivePath.kindNotebook:      return NotebookEntity.databaseTableName
        case DrivePath.kindChapter:       return ChapterEntity.databaseTableName
        case DrivePath.kindPage:          return PageEntity.databaseTableName
        case DrivePath.kindNotepadEntry:  return NotepadEntry.databaseTableName
        case DrivePath.kindTask:          return TaskRecord.databaseTableName
        default: return ""
        }
    }

    // MARK: - Pull

    private func pullDelta(
        remoteManifest: ManifestV2,
        localSnapshot: LocalSnapshot,
        rootFolderId: String,
        accessToken: String
    ) async throws -> Int {
        var downloaded = 0
        let localById = Dictionary(uniqueKeysWithValues: localSnapshot.liveRows.map { ($0.id, $0) })

        for (entityId, remoteChecksum) in remoteManifest.entityChecksums {
            let localRow = localById[entityId]
            // same checksum, skip
            if let l = localRow, l.sha256 == remoteChecksum.sha256 { continue }
            // local dirty and newer, handled by upload path already
            if let l = localRow, l.dirty, l.updatedAt >= remoteChecksum.updatedAt { continue }

            guard let bytes = try await driveClient.downloadBytesAtPath(
                remoteChecksum.path,
                rootFolderId: rootFolderId,
                accessToken: accessToken
            ) else { continue }

            do {
                try await applyRemotePayload(kind: remoteChecksum.kind, bytes: bytes)
                downloaded += 1
            } catch {
                // parse/apply failed — skip this entity, retry next pass
            }
        }

        // Apply remote tombstones — soft-delete locally.
        let localTombIds = Set(localSnapshot.tombstones.map(\.id))
        for (entityId, remoteTomb) in remoteManifest.tombstones {
            if localTombIds.contains(entityId) { continue }
            try await applyRemoteTombstone(id: entityId, entry: remoteTomb)
            downloaded += 1
        }

        return downloaded
    }

    private func applyRemotePayload(kind: String, bytes: Data) async throws {
        let decoder = JSONDecoder()
        try await database.dbQueue.write { db in
            switch kind {
            case DrivePath.kindNotebook:
                let p = try decoder.decode(NotebookPayloadV2.self, from: bytes)
                var row = p.toEntity(driveFileId: nil)
                row.dirty = false
                try row.save(db)
            case DrivePath.kindChapter:
                let p = try decoder.decode(ChapterPayloadV2.self, from: bytes)
                var row = p.toEntity(driveFileId: nil)
                row.dirty = false
                try row.save(db)
            case DrivePath.kindPage:
                let p = try decoder.decode(PagePayloadV2.self, from: bytes)
                var row = p.toEntity(driveFileId: nil)
                row.dirty = false
                try row.save(db)
            case DrivePath.kindNotepadEntry:
                let p = try decoder.decode(NotepadEntryPayloadV2.self, from: bytes)
                var row = p.toEntity(driveFileId: nil)
                row.dirty = false
                try row.save(db)
            case DrivePath.kindTask:
                let p = try decoder.decode(TaskPayloadV2.self, from: bytes)
                var row = p.toEntity()
                row.dirty = false
                try row.save(db)
            default:
                break // forward-compat: unknown kind, skip
            }
        }
    }

    private func applyRemoteTombstone(id: String, entry: TombstoneEntry) async throws {
        let table: String
        switch entry.kind {
        case DrivePath.kindNotebook:      table = NotebookEntity.databaseTableName
        case DrivePath.kindChapter:       table = ChapterEntity.databaseTableName
        case DrivePath.kindPage:          table = PageEntity.databaseTableName
        case DrivePath.kindNotepadEntry:  table = NotepadEntry.databaseTableName
        case DrivePath.kindTask:          table = TaskRecord.databaseTableName
        default: return
        }
        try await database.dbQueue.write { db in
            try db.execute(sql: """
                UPDATE \(table)
                SET deleted_at = ?, updated_at = ?, dirty = 0
                WHERE id = ?
                """, arguments: [entry.deletedAt, entry.deletedAt, id])
        }
    }
}

// MARK: - Snapshot types

private struct LiveRow {
    let id: String
    let kind: String
    let path: String
    let bytes: Data
    let sha256: String
    let updatedAt: String
    let dirty: Bool
}

private struct Tombstone {
    let id: String
    let kind: String
    let deletedAt: String
    let tableName: String
}

private struct LocalSnapshot {
    let liveRows: [LiveRow]
    let tombstones: [Tombstone]
}
