/*
 * SyncRepository.swift
 *
 * App-agnostic Drive sync orchestrator. Pushes locally-dirty rows,
 * pushes tombstones, pulls remote changes, writes the manifest last.
 *
 * Per QUICKINK_DESIGN.md §1, this version no longer reaches into a
 * specific app's database. Instead it talks to a `SyncDataSource`
 * (one per app) for everything that's app-specific:
 *   - "Give me the next batch of dirty rows."
 *   - "Give me the next batch of tombstones."
 *   - "Apply this remote upsert."
 *   - "Apply this remote tombstone."
 *   - "These IDs uploaded clean — flip dirty=0."
 *
 * The orchestration loop, manifest read/write, version gate, and
 * Drive interaction stay here. Releaf's `ReleafSyncDataSource`
 * (apps/releaf/ios/Releaf/Sync/ReleafSyncDataSource.swift) and
 * QuickInk's `QuickInkSyncDataSource` (Phase 3) are the per-app
 * implementations.
 *
 * Refactor history: lifted from the old monolithic
 * `apps/releaf/ios/Releaf/Data/Sync/SyncRepository.swift` (555 lines,
 * Releaf-coupled). The Releaf-specific snapshot/apply/mark code moved
 * into ReleafSyncDataSource. The split lines up with the boundary
 * called out in the design doc.
 */

import Foundation
import ReleafCoreData   // IsoClock (PR #4a — was an inline helper in PR #3b)
import ReleafCoreDrive  // DriveClient (the orchestrator's Drive transport)

// MARK: - SyncResult

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

// MARK: - SyncRepository

public final class SyncRepository: @unchecked Sendable {

    private let dataSource: SyncDataSource
    private let driveClient: DriveClient
    private let stateStore: SyncStateStore
    private let appVersion: String

    /// Page size for `nextDirtyBatch` / `nextTombstoneBatch`. Bounded so
    /// we don't load gigantic snapshots into memory in one shot.
    /// 200 = ~2-4 MB of canonical-JSON payloads on Releaf-shaped rows.
    public static let defaultBatchSize = 200

    public init(
        dataSource: SyncDataSource,
        driveClient: DriveClient,
        stateStore: SyncStateStore = .shared,
        appVersion: String = "0.1.0"
    ) {
        self.dataSource = dataSource
        self.driveClient = driveClient
        self.stateStore = stateStore
        self.appVersion = appVersion
    }

    /// Full sync pass — push local dirty rows and tombstones,
    /// optionally pull remote changes, write manifest last.
    ///
    /// Pass `pullRemote: false` for QuickInk's upload-only path.
    /// The push half still consults the remote manifest for
    /// checksum dedupe (so we don't re-upload a row whose remote
    /// sha matches local) — only the inbound apply is dropped.
    /// Cross-device download is then exclusively a Restore-from-Drive
    /// action via [restore].
    @discardableResult
    public func sync(
        deviceId: String,
        accessToken: String,
        pullRemote: Bool = true
    ) async throws -> SyncResult {
        // 1. Ensure root folder exists. The folder name comes from
        // the data source so each app gets its own top-level folder
        // (Releaf → "Releaf", QuickInk → "QuickInk").
        let root = try await driveClient.ensureRootFolder(
            named: dataSource.driveRootFolderName,
            accessToken: accessToken
        )

        // 2. Pull remote manifest.
        let remoteManifest = try await fetchRemoteManifest(
            rootFolderId: root.id,
            accessToken: accessToken
        )

        // 3. Version gate. If the remote manifest was written by a
        // future major version of the app, refuse to write — older
        // clients shouldn't clobber newer data shapes.
        if let rm = remoteManifest,
           rm.schemaVersion.major > dataSource.schemaVersion.major {
            await MainActor.run { stateStore.recordVersionBlocked() }
            return SyncResult(versionBlocked: true)
        }

        // 4. Seed manifest checksums + tombstones from remote (if any),
        // then patch as we upload new content.
        var checksums: [String: EntityChecksum] = remoteManifest?.entityChecksums ?? [:]
        var tombstones: [String: TombstoneEntry] = remoteManifest?.tombstones ?? [:]

        var uploaded = 0
        var tombstoned = 0
        var failed = 0

        // 5a. Push dirty rows in batches. Per batch: upload each row,
        // collect the (kind, id, updatedAt, driveFileId) acks, hand
        // them back to the data source so it can flip dirty=0.
        var dirtyCursor: SyncCursor? = nil
        repeat {
            let batch = try await dataSource.nextDirtyBatch(
                after: dirtyCursor,
                limit: Self.defaultBatchSize
            )

            var acks: [SyncAck] = []
            for entry in batch.entries {
                let remoteHash = remoteManifest?.entityChecksums[entry.id]?.sha256
                if remoteHash == entry.payloadSha256 {
                    // Remote already has this exact bytes. Just refresh
                    // the manifest entry and ack so dirty clears.
                    checksums[entry.id] = EntityChecksum(
                        kind: entry.kind,
                        path: entry.drivePath,
                        sha256: entry.payloadSha256,
                        updatedAt: entry.updatedAt
                    )
                    acks.append(SyncAck(
                        kind: entry.kind,
                        id: entry.id,
                        updatedAt: entry.updatedAt,
                        driveFileId: ""
                    ))
                    continue
                }

                do {
                    let driveFile = try await driveClient.uploadJSONAtPath(
                        entry.payload,
                        relativePath: entry.drivePath,
                        rootFolderId: root.id,
                        accessToken: accessToken
                    )
                    checksums[entry.id] = EntityChecksum(
                        kind: entry.kind,
                        path: entry.drivePath,
                        sha256: entry.payloadSha256,
                        updatedAt: entry.updatedAt
                    )
                    tombstones.removeValue(forKey: entry.id)
                    acks.append(SyncAck(
                        kind: entry.kind,
                        id: entry.id,
                        updatedAt: entry.updatedAt,
                        driveFileId: driveFile.id
                    ))
                    uploaded += 1
                } catch {
                    failed += 1
                }
            }

            if !acks.isEmpty {
                try await dataSource.markSynced(acks)
            }

            dirtyCursor = batch.nextCursor
        } while dirtyCursor != nil

        // 5b. Push tombstones the same way. Each tombstone becomes a
        // file under `tombstones/<id>.json` in Drive, plus a manifest
        // entry that other devices read to soft-delete locally.
        var tombCursor: SyncCursor? = nil
        repeat {
            let batch = try await dataSource.nextTombstoneBatch(
                after: tombCursor,
                limit: Self.defaultBatchSize
            )

            var acks: [SyncAck] = []
            for tomb in batch.entries {
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
                    let driveFile = try await driveClient.uploadJSONAtPath(
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
                    acks.append(SyncAck(
                        kind: tomb.kind,
                        id: tomb.id,
                        updatedAt: tomb.deletedAt,
                        driveFileId: driveFile.id
                    ))
                    tombstoned += 1
                } catch {
                    failed += 1
                }
            }

            if !acks.isEmpty {
                try await dataSource.markSynced(acks)
            }

            tombCursor = batch.nextCursor
        } while tombCursor != nil

        // 6. Pull remote delta (entries the local device doesn't have
        // or has at an older sha256). Skipped when `pullRemote` is
        // false — QuickInk's upload-only path. Push still ran above.
        var downloaded = 0
        if pullRemote, let rm = remoteManifest {
            downloaded = try await pullDelta(
                remoteManifest: rm,
                rootFolderId: root.id,
                accessToken: accessToken
            )
        }

        // 7. Write the manifest LAST so payloads are durable on Drive
        // before the index points at them. A failure mid-loop leaves
        // the previous manifest authoritative; nothing has been
        // permanently broken.
        let nowIso = IsoClock.nowIso()
        // ManifestV2's `schemaVersion` defaults to `SchemaVersion.current`
        // (a Releaf-derived global). For app-agnostic correctness we
        // pass the data source's version explicitly. `appId` is not
        // yet a manifest field — captured in `dataSource.appId` for
        // future debug/logging only; adding it to the wire format is a
        // follow-up commit (would need a SchemaVersion minor bump).
        let manifest = ManifestV2(
            schemaVersion: dataSource.schemaVersion,
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
            // Manifest never reached Drive — the sync did NOT succeed.
            // Don't touch `lastFullSyncAt` / `manifestChecksum`: the
            // UI's "Last synced" pill must keep showing the previous
            // successful pass's timestamp, not lie that a failed pass
            // just landed. Only the pending-count is bumped so the
            // user sees the rows queued for retry. Mirrors Android's
            // `Result.retry()` path where WorkManager backs off
            // without claiming success.
            failed += 1
            // Snapshot to a `let` before crossing the @Sendable boundary
            // (capturing a mutable local is a Swift 6 concurrency error).
            let pending = failed
            await MainActor.run {
                stateStore.recordSyncFailed(pendingCount: pending)
            }
            // Surface the underlying error to the caller so the
            // scheduler / UI can react (today: logged in
            // `QuickInkSyncEnvironment.runOnce` after this rethrow).
            throw error
        }

        // 8. Persist sync state for the UI.
        let manifestHash = sha256Hex(manifestBytes)
        let pending = failed
        await MainActor.run {
            stateStore.recordSuccess(
                lastFullSyncAt: nowIso,
                manifestChecksum: manifestHash,
                pendingCount: pending
            )
        }

        return SyncResult(
            uploaded: uploaded,
            tombstoned: tombstoned,
            downloaded: downloaded,
            failed: failed
        )
    }

    /// Pull-only pass — no push, no manifest write. Applies every
    /// remote upsert + tombstone in the current Drive manifest to the
    /// local store via the data source. Used by Settings → "Restore
    /// from Drive" to recover after an uninstall / device swap, where
    /// the user wants the local DB rehydrated from the cloud copy
    /// without the worker's bidirectional reconciliation.
    ///
    /// Last-write-wins still applies inside `applyRemoteUpsert` (the
    /// data source's responsibility) — this isn't a force-overwrite.
    /// True force-restore (clobber everything regardless of
    /// updatedAt) needs a separate `applyRemoteUpsert(force: true)`
    /// flavour on the data source; out of scope for this slice.
    @discardableResult
    public func restore(
        deviceId: String,
        accessToken: String
    ) async throws -> SyncResult {
        // 1. Ensure the root folder exists. If the user really did
        // start from a fresh install, this creates an empty Drive
        // folder; the manifest fetch then comes back nil and we
        // return an empty result — same behaviour as the very first
        // sync pass on a new device.
        let root = try await driveClient.ensureRootFolder(
            named: dataSource.driveRootFolderName,
            accessToken: accessToken
        )

        // 2. Fetch the remote manifest. Nil = no Drive backup yet,
        // nothing to restore.
        guard let remoteManifest = try await fetchRemoteManifest(
            rootFolderId: root.id,
            accessToken: accessToken
        ) else {
            let nowIso = IsoClock.nowIso()
            await MainActor.run {
                stateStore.recordSuccess(
                    lastFullSyncAt: nowIso,
                    manifestChecksum: "",
                    pendingCount: 0
                )
            }
            return SyncResult()
        }

        // 3. Version gate — same posture as `sync(...)`. Refuse to
        // restore from a manifest written by a future major version.
        if remoteManifest.schemaVersion.major > dataSource.schemaVersion.major {
            await MainActor.run { stateStore.recordVersionBlocked() }
            return SyncResult(versionBlocked: true)
        }

        // 4. Pull every remote upsert + tombstone via the existing
        // `pullDelta` path.
        let downloaded = try await pullDelta(
            remoteManifest: remoteManifest,
            rootFolderId:   root.id,
            accessToken:    accessToken
        )

        // 5. Persist sync state. We deliberately stamp `lastFullSyncAt`
        // with the restore timestamp so the Home pill / Settings row
        // reflect the activity — the user just got fresh data from
        // Drive, and surfacing it as "Synced moments ago" matches
        // their mental model. `pendingCount` is left unchanged.
        let nowIso = IsoClock.utc()
        await MainActor.run {
            stateStore.recordSuccess(
                lastFullSyncAt: nowIso,
                manifestChecksum: "",
                pendingCount: 0
            )
        }

        return SyncResult(
            uploaded: 0,
            tombstoned: 0,
            downloaded: downloaded,
            failed: 0
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

    // MARK: - Pull

    private func pullDelta(
        remoteManifest: ManifestV2,
        rootFolderId: String,
        accessToken: String
    ) async throws -> Int {
        var downloaded = 0

        for (entityId, remoteChecksum) in remoteManifest.entityChecksums {
            guard let bytes = try await driveClient.downloadBytesAtPath(
                remoteChecksum.path,
                rootFolderId: rootFolderId,
                accessToken: accessToken
            ) else { continue }

            do {
                try await dataSource.applyRemoteUpsert(
                    RemoteUpsert(
                        kind: remoteChecksum.kind,
                        id: entityId,
                        payload: bytes,
                        updatedAt: remoteChecksum.updatedAt,
                        driveFileId: ""  // Drive fileId resolution is per-call;
                                         // not in the manifest. The data source
                                         // will re-fetch on next push if needed.
                    )
                )
                downloaded += 1
            } catch {
                // Apply failed (parse error, schema mismatch, write
                // conflict). Skip and retry next pass.
            }
        }

        // Apply remote tombstones — soft-delete locally.
        for (entityId, remoteTomb) in remoteManifest.tombstones {
            try await dataSource.applyRemoteTombstone(
                RemoteTombstone(
                    kind: remoteTomb.kind,
                    id: entityId,
                    deletedAt: remoteTomb.deletedAt
                )
            )
            downloaded += 1
        }

        return downloaded
    }
}
