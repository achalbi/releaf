/*
 * SyncRepository.kt
 *
 * App-agnostic Drive sync orchestrator. Pushes locally-dirty rows,
 * pushes tombstones, pulls remote changes, writes the manifest last.
 *
 * Per QUICKINK_DESIGN.md §1, this version no longer reaches into a
 * specific app's database. Instead it talks to a `SyncDataSource`
 * (one per app) for everything that's app-specific:
 *
 *   - "Give me the next batch of dirty rows."
 *   - "Give me the next batch of tombstones."
 *   - "Apply this remote upsert."
 *   - "Apply this remote tombstone."
 *   - "These IDs uploaded clean — flip dirty=0."
 *
 * The orchestration loop, manifest read/write, version gate, and
 * Drive interaction stay here. Releaf's `ReleafSyncDataSource`
 * (apps/releaf/android/.../sync/ReleafSyncDataSource.kt) and QuickInk's
 * `QuickInkSyncDataSource` (Phase 3) are the per-app implementations.
 *
 * Refactor history: lifted from the old monolithic
 * `apps/releaf/android/.../data/sync/SyncRepository.kt` (573 lines,
 * Releaf-coupled). The Releaf-specific snapshot/apply/mark code moved
 * into ReleafSyncDataSource. Mirror of iOS PR #3b's SyncRepository.swift
 * — same algorithm, same boundary, byte-identical Drive output.
 */

package app.releaf.mobile.data.sync

import app.releaf.mobile.data.common.IsoClock  // PR #4b — was inline nowIsoUtc()
import app.releaf.mobile.data.drive.DriveClient
import app.releaf.mobile.data.drive.DriveError
import app.releaf.mobile.data.drive.downloadBytesAtPath
import app.releaf.mobile.data.drive.uploadJsonAtPath
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// ─── Result types ──────────────────────────────────────────────────────

/** Summary of a single sync pass. Logged by the worker. */
data class SyncResult(
    val uploaded: Int,
    val tombstoned: Int,
    val downloaded: Int,
    val failed: Int,
    val versionBlocked: Boolean = false,
) {
    val touched: Int get() = uploaded + tombstoned + downloaded + failed
}

// ─── Core orchestrator ─────────────────────────────────────────────────

class SyncRepository(
    private val dataSource: SyncDataSource,
    private val driveClient: DriveClient,
    private val syncStateDao: SyncStateDao,
    /** SemVer + build metadata. Informational only on the wire. */
    private val appVersion: String = "0.1.0",
    private val json: Json = SyncJson,
) {

    /**
     * Full sync pass — push then pull. Safe to call repeatedly.
     *
     * @param deviceId    This device's stable install id; stamped on
     *                    manifests + tombstones.
     * @param accessToken OAuth access token with drive.file scope. Caller
     *                    is responsible for freshness.
     */
    suspend fun sync(
        deviceId: String,
        accessToken: String,
    ): SyncResult {
        // ---- 1. ensure the root folder ----
        // Per-app folder name comes from the data source (Releaf →
        // "Releaf", QuickInk → "QuickInk").
        val root = driveClient.ensureRootFolder(dataSource.driveRootFolderName, accessToken)

        // ---- 2. pull remote manifest ----
        val remoteManifest = fetchRemoteManifest(root.id, accessToken)

        // ---- 3. version gate ----
        // If the remote manifest was written by a future major version
        // of the app, refuse to write — older clients shouldn't clobber
        // newer data shapes.
        if (remoteManifest != null &&
            remoteManifest.schemaVersion.major > dataSource.schemaVersion.major
        ) {
            return SyncResult(
                uploaded = 0, tombstoned = 0, downloaded = 0, failed = 0,
                versionBlocked = true,
            )
        }

        // ---- 4. seed manifest checksums + tombstones from remote ----
        val manifestChecksums = HashMap<String, EntityChecksum>()
        val manifestTombstones = HashMap<String, TombstoneEntry>()
        remoteManifest?.entityChecksums?.let { manifestChecksums.putAll(it) }
        remoteManifest?.tombstones?.let { manifestTombstones.putAll(it) }

        var uploaded = 0
        var tombstoned = 0
        var failed = 0

        // ---- 5a. push dirty rows in batches ----
        var dirtyCursor: SyncCursor? = null
        do {
            val batch = dataSource.nextDirtyBatch(after = dirtyCursor, limit = DEFAULT_BATCH_SIZE)
            val acks = mutableListOf<SyncAck>()

            for (entry in batch.entries) {
                val remoteHash = remoteManifest?.entityChecksums?.get(entry.id)?.sha256
                if (remoteHash == entry.payloadSha256) {
                    // Remote already has this exact bytes. Refresh
                    // manifest entry and ack so dirty clears.
                    manifestChecksums[entry.id] = EntityChecksum(
                        kind = entry.kind,
                        path = entry.drivePath,
                        sha256 = entry.payloadSha256,
                        updatedAt = entry.updatedAt,
                    )
                    acks += SyncAck(
                        kind = entry.kind,
                        id = entry.id,
                        updatedAt = entry.updatedAt,
                        driveFileId = "",
                    )
                    continue
                }

                try {
                    val driveFile = driveClient.uploadJsonAtPath(
                        data = entry.payload,
                        relativePath = entry.drivePath,
                        rootFolderId = root.id,
                        accessToken = accessToken,
                    )
                    manifestChecksums[entry.id] = EntityChecksum(
                        kind = entry.kind,
                        path = entry.drivePath,
                        sha256 = entry.payloadSha256,
                        updatedAt = entry.updatedAt,
                    )
                    manifestTombstones.remove(entry.id)
                    acks += SyncAck(
                        kind = entry.kind,
                        id = entry.id,
                        updatedAt = entry.updatedAt,
                        driveFileId = driveFile.id,
                    )
                    uploaded++
                } catch (_: DriveError) {
                    failed++
                }
            }

            if (acks.isNotEmpty()) dataSource.markSynced(acks)
            dirtyCursor = batch.nextCursor
        } while (dirtyCursor != null)

        // ---- 5b. push tombstones in batches ----
        var tombCursor: SyncCursor? = null
        do {
            val batch = dataSource.nextTombstoneBatch(after = tombCursor, limit = DEFAULT_BATCH_SIZE)
            val acks = mutableListOf<SyncAck>()

            for (tomb in batch.entries) {
                try {
                    val tombFile = TombstoneFile(
                        id = tomb.id,
                        kind = tomb.kind,
                        deletedAt = tomb.deletedAt,
                        deviceId = deviceId,
                        hardDeleteAt = null,
                    )
                    val bytes = json.encodeToString(tombFile).toByteArray(Charsets.UTF_8)
                    val driveFile = driveClient.uploadJsonAtPath(
                        data = bytes,
                        relativePath = DrivePath.tombstone(tomb.id),
                        rootFolderId = root.id,
                        accessToken = accessToken,
                    )
                    manifestChecksums.remove(tomb.id)
                    manifestTombstones[tomb.id] = TombstoneEntry(
                        kind = tomb.kind,
                        deletedAt = tomb.deletedAt,
                        deviceId = deviceId,
                        hardDeleteAt = null,
                    )
                    acks += SyncAck(
                        kind = tomb.kind,
                        id = tomb.id,
                        updatedAt = tomb.deletedAt,
                        driveFileId = driveFile.id,
                    )
                    tombstoned++
                } catch (_: DriveError) {
                    failed++
                }
            }

            if (acks.isNotEmpty()) dataSource.markSynced(acks)
            tombCursor = batch.nextCursor
        } while (tombCursor != null)

        // ---- 6. pull remote delta ----
        var downloaded = 0
        if (remoteManifest != null) {
            downloaded = pullDelta(
                remoteManifest = remoteManifest,
                rootFolderId = root.id,
                accessToken = accessToken,
            )
        }

        // ---- 7. write manifest LAST ----
        // Payloads are durable on Drive before the index points at them.
        // A failure mid-loop leaves the previous manifest authoritative;
        // nothing is permanently broken.
        val nowIso = IsoClock.nowIso()
        val manifest = ManifestV2(
            appVersion = appVersion,
            deviceId = deviceId,
            lastSyncAt = nowIso,
            clientGeneratedAt = nowIso,
            entityChecksums = manifestChecksums,
            tombstones = manifestTombstones,
        )
        val manifestBytes = json.encodeToString(manifest).toByteArray(Charsets.UTF_8)
        try {
            driveClient.uploadJsonAtPath(
                data = manifestBytes,
                relativePath = DrivePath.MANIFEST,
                rootFolderId = root.id,
                accessToken = accessToken,
            )
        } catch (_: DriveError) {
            failed++
            // Manifest failed — payloads are durable; next pass recovers.
        }

        // ---- 8. update local sync_state ----
        syncStateDao.upsert(SyncStateEntity(
            key = SyncStateKeys.LAST_FULL_SYNC_AT,
            value = nowIso,
            updatedAt = nowIso,
        ))
        syncStateDao.upsert(SyncStateEntity(
            key = SyncStateKeys.MANIFEST_CHECKSUM,
            value = sha256Hex(manifestBytes),
            updatedAt = nowIso,
        ))
        syncStateDao.upsert(SyncStateEntity(
            key = SyncStateKeys.PENDING_COUNT,
            value = failed.toString(),
            updatedAt = nowIso,
        ))

        return SyncResult(
            uploaded = uploaded,
            tombstoned = tombstoned,
            downloaded = downloaded,
            failed = failed,
        )
    }

    // ─── Manifest fetch ────────────────────────────────────────────────

    private suspend fun fetchRemoteManifest(
        rootFolderId: String,
        accessToken: String,
    ): ManifestV2? {
        val bytes = driveClient.downloadBytesAtPath(
            relativePath = DrivePath.MANIFEST,
            rootFolderId = rootFolderId,
            accessToken = accessToken,
        ) ?: return null
        return try {
            json.decodeFromString(ManifestV2.serializer(), bytes.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Pull-only pass — no push, no manifest write. Applies every
     * remote upsert + tombstone in the current Drive manifest to the
     * local store via the data source. Used by Settings → "Restore
     * from Drive" to recover after an uninstall / device swap, where
     * the user wants the local DB rehydrated from the cloud copy
     * without the worker's bidirectional reconciliation.
     *
     * Last-write-wins still applies inside `applyRemoteUpsert` (the
     * data source's responsibility) — this isn't a force-overwrite.
     * True force-restore (clobber everything regardless of
     * updatedAt) needs a separate `applyRemoteUpsert(force = true)`
     * flavour on the data source; out of scope for this slice.
     */
    suspend fun restore(
        deviceId: String,
        accessToken: String,
    ): SyncResult {
        val nowIso = IsoClock.nowIso()

        // ---- 1. ensure root folder ----
        val root = driveClient.ensureRootFolder(dataSource.driveRootFolderName, accessToken)

        // ---- 2. fetch manifest ----
        val remoteManifest = fetchRemoteManifest(root.id, accessToken)
            ?: run {
                // No backup yet on Drive — empty result, but stamp
                // the sync state so the UI reflects "we tried".
                syncStateDao.upsert(SyncStateEntity(
                    key       = SyncStateKeys.LAST_FULL_SYNC_AT,
                    value     = nowIso,
                    updatedAt = nowIso,
                ))
                return SyncResult(uploaded = 0, tombstoned = 0, downloaded = 0, failed = 0)
            }

        // ---- 3. version gate ----
        if (remoteManifest.schemaVersion.major > dataSource.schemaVersion.major) {
            return SyncResult(
                uploaded = 0, tombstoned = 0, downloaded = 0, failed = 0,
                versionBlocked = true,
            )
        }

        // ---- 4. pull only ----
        val downloaded = pullDelta(remoteManifest, root.id, accessToken)

        // ---- 5. record activity ----
        // Stamp `lastFullSyncAt` with the restore timestamp so the
        // Home pill / Settings row reflect the activity — the user
        // just got fresh data from Drive, and surfacing it as
        // "Synced moments ago" matches their mental model.
        syncStateDao.upsert(SyncStateEntity(
            key       = SyncStateKeys.LAST_FULL_SYNC_AT,
            value     = nowIso,
            updatedAt = nowIso,
        ))

        return SyncResult(
            uploaded = 0, tombstoned = 0, downloaded = downloaded, failed = 0,
        )
    }

    // ─── Pull ──────────────────────────────────────────────────────────

    private suspend fun pullDelta(
        remoteManifest: ManifestV2,
        rootFolderId: String,
        accessToken: String,
    ): Int {
        var downloaded = 0

        for ((entityId, remoteChecksum) in remoteManifest.entityChecksums) {
            val bytes = driveClient.downloadBytesAtPath(
                relativePath = remoteChecksum.path,
                rootFolderId = rootFolderId,
                accessToken = accessToken,
            ) ?: continue

            try {
                dataSource.applyRemoteUpsert(
                    RemoteUpsert(
                        kind = remoteChecksum.kind,
                        id = entityId,
                        payload = bytes,
                        updatedAt = remoteChecksum.updatedAt,
                        driveFileId = "",
                    )
                )
                downloaded++
            } catch (_: Exception) {
                // Apply failed (parse error, schema mismatch, write
                // conflict). Skip and retry next pass.
            }
        }

        // Apply remote tombstones — soft-delete locally.
        for ((entityId, remoteTomb) in remoteManifest.tombstones) {
            dataSource.applyRemoteTombstone(
                RemoteTombstone(
                    kind = remoteTomb.kind,
                    id = entityId,
                    deletedAt = remoteTomb.deletedAt,
                )
            )
            downloaded++
        }

        return downloaded
    }

    // ─── Helpers ───────────────────────────────────────────────────────

    companion object {
        /**
         * Page size for nextDirtyBatch / nextTombstoneBatch. Bounded so
         * we don't load gigantic snapshots into memory at once.
         */
        private const val DEFAULT_BATCH_SIZE = 200
    }
}

// Note: `sha256Hex(bytes: ByteArray)` is defined as a top-level
// function in CanonicalJson.kt (same module after PR #3c). Reused
// here without re-import.
