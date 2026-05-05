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

import android.util.Log
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
    /**
     * Upload-side failures only — counts rows whose
     * [DriveClient.uploadJsonAtPath] threw a [DriveError]. The push
     * pass increments this, the pull pass does NOT (see [applyFailed]).
     * Worker callers may use this to decide between Result.success
     * and Result.retry on the periodic / push path.
     */
    val failed: Int,
    /**
     * Pull-side apply failures — counts rows whose
     * [SyncDataSource.applyRemoteUpsert] threw mid-restore (parse
     * error, FK constraint, schema mismatch, write conflict). Was
     * silently swallowed before; now exposed so QuickInkRestoreWorker
     * can log "downloaded=N applyFailed=M" instead of pretending the
     * restore was clean. Zero on the upload-only / push-only path.
     */
    val applyFailed: Int = 0,
    val versionBlocked: Boolean = false,
) {
    val touched: Int get() = uploaded + tombstoned + downloaded + failed + applyFailed
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
     * Full sync pass — push, then optionally pull. Safe to call
     * repeatedly.
     *
     * @param deviceId    This device's stable install id; stamped on
     *                    manifests + tombstones.
     * @param accessToken OAuth access token with drive.file scope. Caller
     *                    is responsible for freshness.
     * @param pullRemote  When true (default), the pass also downloads
     *                    every remote upsert / tombstone whose
     *                    checksum differs from local — bidirectional
     *                    sync. When false, the pass only pushes
     *                    locally-dirty rows + tombstones to Drive
     *                    and writes a fresh manifest, skipping the
     *                    pull-down step. The push half still consults
     *                    the remote manifest to dedupe (no point
     *                    re-uploading a row whose remote sha matches
     *                    local) — only the inbound apply is dropped.
     *
     *                    QuickInk uses `pullRemote = false` for
     *                    its periodic / Sync-now path: the app's
     *                    capture data is local-first and the user
     *                    only needs Drive as a one-way backup.
     *                    The cross-device "I want my scans on a new
     *                    phone" case is handled by [restore], which
     *                    is the explicit Restore from Drive button.
     */
    suspend fun sync(
        deviceId: String,
        accessToken: String,
        pullRemote: Boolean = true,
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
        // Skipped entirely when [pullRemote] is false (QuickInk's
        // upload-only path). The push half above still consulted
        // the remote manifest for checksum dedupe, so we already
        // benefit from the round-trip; we just don't APPLY remote
        // upserts back to local.
        var downloaded = 0
        var applyFailed = 0
        if (pullRemote && remoteManifest != null) {
            val pullStats = pullDelta(
                remoteManifest = remoteManifest,
                rootFolderId = root.id,
                accessToken = accessToken,
            )
            downloaded = pullStats.downloaded
            applyFailed = pullStats.applyFailed
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
            uploaded    = uploaded,
            tombstoned  = tombstoned,
            downloaded  = downloaded,
            failed      = failed,
            applyFailed = applyFailed,
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
        val pullStats = pullDelta(remoteManifest, root.id, accessToken)

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
            uploaded    = 0,
            tombstoned  = 0,
            downloaded  = pullStats.downloaded,
            failed      = 0,
            applyFailed = pullStats.applyFailed,
        )
    }

    // ─── Drive-side cleanup (orphan retirement) ────────────────────────

    /** Outcome of a single [cleanupOrphans] pass. */
    data class CleanupResult(
        val orphansFound: Int,
        val orphansTombstoned: Int,
        val manifestRewritten: Boolean,
    )

    /**
     * Tombstone manifest entries whose parent is missing from the
     * same manifest. Intended to retire data-hygiene orphans left
     * behind by older builds that didn't cascade soft-delete
     * (typical case for QuickInk: ocr_result entries pointing at a
     * capture that was tombstoned but whose children weren't).
     *
     * Algorithm:
     *   1. Fetch the current manifest.
     *   2. Ask the data source to identify orphans (it knows the
     *      parent-child relationships for its own kinds).
     *   3. For each orphan, upload a `tombstones/<id>.json` file and
     *      mutate the in-memory manifest (remove from
     *      `entityChecksums`, add to `tombstones`).
     *   4. If any orphan was successfully tombstoned, rewrite the
     *      manifest so future restores on any device see the orphans
     *      as tombstones rather than orphan upserts.
     *
     * Idempotent: re-running on a clean manifest finds zero orphans
     * and exits early. Safe to call from the periodic / restore
     * paths; cheap when nothing's wrong (one manifest fetch + one
     * data-source scan).
     *
     * Failure handling: per-orphan upload failures are logged and
     * skipped — the loop continues. The final manifest rewrite is
     * also wrapped in try/catch; if it fails, the tombstone files
     * we DID upload are still durable on Drive (just orphaned in
     * the manifest until the next successful pass). No throws to
     * the caller.
     */
    suspend fun cleanupOrphans(
        deviceId: String,
        accessToken: String,
    ): CleanupResult {
        val root = driveClient.ensureRootFolder(dataSource.driveRootFolderName, accessToken)
        val manifest = fetchRemoteManifest(root.id, accessToken)
            ?: return CleanupResult(orphansFound = 0, orphansTombstoned = 0, manifestRewritten = false)

        val orphans = dataSource.findOrphanIds(manifest)
        if (orphans.isEmpty()) {
            return CleanupResult(orphansFound = 0, orphansTombstoned = 0, manifestRewritten = false)
        }
        Log.i(
            TAG,
            "cleanupOrphans: found ${orphans.size} orphan entity/entities on Drive — " +
                "tombstoning. (Sample: ${orphans.take(3).joinToString { "${it.kind}:${it.id} " +
                    "missingParent=${it.missingParentId}" }}…)"
        )

        val newChecksums = HashMap(manifest.entityChecksums)
        val newTombstones = HashMap(manifest.tombstones)
        val nowIso = IsoClock.nowIso()
        var tombstoned = 0

        for (orphan in orphans) {
            try {
                val tombFile = TombstoneFile(
                    id           = orphan.id,
                    kind         = orphan.kind,
                    deletedAt    = nowIso,
                    deviceId     = deviceId,
                    hardDeleteAt = null,
                )
                val bytes = json.encodeToString(tombFile).toByteArray(Charsets.UTF_8)
                driveClient.uploadJsonAtPath(
                    data         = bytes,
                    relativePath = DrivePath.tombstone(orphan.id),
                    rootFolderId = root.id,
                    accessToken  = accessToken,
                )
                newChecksums.remove(orphan.id)
                newTombstones[orphan.id] = TombstoneEntry(
                    kind         = orphan.kind,
                    deletedAt    = nowIso,
                    deviceId     = deviceId,
                    hardDeleteAt = null,
                )
                tombstoned++
            } catch (e: Exception) {
                Log.w(
                    TAG,
                    "cleanupOrphans: failed to tombstone orphan kind=${orphan.kind} id=${orphan.id}: $e"
                )
            }
        }

        if (tombstoned == 0) {
            return CleanupResult(orphansFound = orphans.size, orphansTombstoned = 0, manifestRewritten = false)
        }

        // Rewrite the manifest so future restores see the orphans as
        // tombstones, not phantom upserts. Failure here doesn't
        // invalidate the tombstone files we already uploaded — the
        // next successful sync pass picks up where we left off (the
        // tombstone files are durable; the manifest is just an index
        // pointing at them).
        var manifestRewritten = false
        try {
            val cleaned = manifest.copy(
                deviceId          = deviceId,
                lastSyncAt        = nowIso,
                clientGeneratedAt = nowIso,
                entityChecksums   = newChecksums,
                tombstones        = newTombstones,
            )
            val manifestBytes = json.encodeToString(cleaned).toByteArray(Charsets.UTF_8)
            driveClient.uploadJsonAtPath(
                data         = manifestBytes,
                relativePath = DrivePath.MANIFEST,
                rootFolderId = root.id,
                accessToken  = accessToken,
            )
            manifestRewritten = true
        } catch (e: Exception) {
            Log.w(TAG, "cleanupOrphans: failed to rewrite manifest after tombstoning: $e")
        }

        Log.i(
            TAG,
            "cleanupOrphans: done — tombstoned=$tombstoned of ${orphans.size}, " +
                "manifestRewritten=$manifestRewritten"
        )
        return CleanupResult(
            orphansFound       = orphans.size,
            orphansTombstoned  = tombstoned,
            manifestRewritten  = manifestRewritten,
        )
    }

    // ─── Pull ──────────────────────────────────────────────────────────

    /** Pair-shaped result from [pullDelta]: (downloaded, applyFailed). */
    private data class PullStats(val downloaded: Int, val applyFailed: Int)

    /**
     * FK-rank for each entity kind, lower = "apply earlier". The
     * remote manifest's `entityChecksums` is a `Map<String, …>`; map
     * iteration order is not guaranteed to match dependency order, so
     * an `ocr_result` upsert can fire before its parent `capture` and
     * the INSERT then fails the FK constraint. Used by [pullDelta] to
     * stable-sort entries before applying.
     *
     * Three tiers cover today's kinds:
     *   - rank 0 (no FK dependency on another synced kind): notebook,
     *     notepad_entry, daily_log, capture, category, task, tag,
     *     project, reference_link, page_template.
     *   - rank 1 (one synced parent): chapter (→ notebook),
     *     ocr_result (→ capture).
     *   - rank 2 (two-level deep): page (→ chapter → notebook).
     *
     * Unknown kinds default to rank 0 — forward-compat-friendly, and
     * if a future kind has FK deps the data source will already
     * tolerate the wrong order via its own ON CONFLICT handling.
     */
    private fun applyRank(kind: String): Int = when (kind) {
        DrivePath.KIND_PAGE        -> 2
        DrivePath.KIND_CHAPTER     -> 1
        DrivePath.KIND_OCR_RESULT  -> 1
        else                       -> 0
    }

    private suspend fun pullDelta(
        remoteManifest: ManifestV2,
        rootFolderId: String,
        accessToken: String,
    ): PullStats {
        var downloaded = 0
        var applyFailed = 0

        // Stable-sort by FK rank so parents land before children.
        // Without this, `entityChecksums` iterates in HashMap order
        // and an ocr_result whose parent capture hasn't been applied
        // yet trips a FOREIGN KEY constraint, the catch below logs
        // it, and the row is silently lost — for periodic sync we
        // could rely on the next pass picking it up, but Restore
        // from Drive has no next pass and the user notices missing
        // data.
        val orderedEntries = remoteManifest.entityChecksums.entries
            .sortedBy { applyRank(it.value.kind) }

        for ((entityId, remoteChecksum) in orderedEntries) {
            val bytes = driveClient.downloadBytesAtPath(
                relativePath = remoteChecksum.path,
                rootFolderId = rootFolderId,
                accessToken = accessToken,
            )
            if (bytes == null) {
                // Manifest references a file that's no longer in Drive.
                // Could be a race with a concurrent delete, or the
                // manifest is stale relative to actual files. Count as
                // an apply failure so the caller can surface "M of N
                // didn't land" — a silent skip here is what made
                // partial restores undetectable.
                Log.w(
                    TAG,
                    "pullDelta: download returned null for kind=${remoteChecksum.kind} " +
                        "id=$entityId path=${remoteChecksum.path} — manifest likely " +
                        "out of sync with Drive contents."
                )
                applyFailed++
                continue
            }

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
            } catch (e: Exception) {
                // Per-row apply failure — log with enough context to
                // diagnose without dumping the full payload (which may
                // be megabytes). Common causes: FK constraint
                // (parent not yet applied — applyRank above mostly
                // prevents this), JSON parse error (schema drift), or
                // write conflict on a row we just transactionally
                // touched.
                Log.w(
                    TAG,
                    "pullDelta: applyRemoteUpsert failed for kind=${remoteChecksum.kind} " +
                        "id=$entityId payloadSize=${bytes.size} " +
                        "exception=${e.javaClass.simpleName} message=${e.message}"
                )
                applyFailed++
            }
        }

        // Apply remote tombstones — soft-delete locally.
        // Tombstone application is far less likely to fail (it's
        // typically a parameterised UPDATE that no-ops if the row
        // doesn't exist), but wrap in try/catch for parity with the
        // upsert path so a single broken tombstone doesn't poison
        // the whole loop.
        for ((entityId, remoteTomb) in remoteManifest.tombstones) {
            try {
                dataSource.applyRemoteTombstone(
                    RemoteTombstone(
                        kind = remoteTomb.kind,
                        id = entityId,
                        deletedAt = remoteTomb.deletedAt,
                    )
                )
                downloaded++
            } catch (e: Exception) {
                Log.w(
                    TAG,
                    "pullDelta: applyRemoteTombstone failed for kind=${remoteTomb.kind} " +
                        "id=$entityId exception=${e.javaClass.simpleName} message=${e.message}"
                )
                applyFailed++
            }
        }

        return PullStats(downloaded = downloaded, applyFailed = applyFailed)
    }

    // ─── Helpers ───────────────────────────────────────────────────────

    companion object {
        /**
         * Page size for nextDirtyBatch / nextTombstoneBatch. Bounded so
         * we don't load gigantic snapshots into memory at once.
         */
        private const val DEFAULT_BATCH_SIZE = 200

        /**
         * Logcat tag for sync-orchestrator messages (currently used by
         * [pullDelta]'s per-row apply-failure logs). Shared with the
         * QuickInk + Releaf sync workers so a single
         * `adb logcat -s QuickInkSync` filter shows orchestrator and
         * worker output side by side.
         */
        private const val TAG = "QuickInkSync"
    }
}

// Note: `sha256Hex(bytes: ByteArray)` is defined as a top-level
// function in CanonicalJson.kt (same module after PR #3c). Reused
// here without re-import.
