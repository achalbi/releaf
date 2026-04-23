/*
 * SyncRepository.kt
 *
 * v2 Drive sync. Replaces the v1 push-only flat-manifest implementation
 * in place — no user data exists yet, so rewriting the on-Drive layout
 * is a dogfood-only operation. Callers from WorkManager (SyncWorker) and
 * the Settings "Sync now" action still see the same `push` entry point;
 * internally everything else is new.
 *
 * The high-level pass per `docs/DRIVE_SCHEMA.md` §"Sync algorithm":
 *
 *   1. ensureRoot — idempotent "Releaf/" folder in Drive root.
 *   2. pullRemoteManifest — GET /Releaf/manifest.json. Absent = first sync.
 *   3. versionGate — if remote major > ours, abort with version-mismatch.
 *   4. buildLocalSnapshot — for every live row (all kinds), compute its
 *      canonical-JSON bytes + SHA-256. Dirty rows come along with a
 *      `dirty=true` marker; clean rows can be used to rebuild the manifest
 *      without a re-upload.
 *   5. pushDelta — for each dirty row whose hash differs from remote,
 *      upload. For each local tombstone, upload the tombstone file +
 *      stage a manifest tombstone entry. For each remote entity no longer
 *      in local snapshot (pulled as tombstone or simply gone), record
 *      removal from `entity_checksums`.
 *   6. pullDelta — for each remote entity whose hash isn't in local, or
 *      whose remote `updated_at` is newer than local, download and
 *      insert/update locally with `dirty = 0`. Tombstones downloaded
 *      propagate local soft-deletes.
 *   7. writeManifest — canonical serialize + upload last.
 *   8. updateSyncState — stamp last_full_sync_at, pending count, etc.
 *
 * The manifest is rebuilt from scratch each pass by walking every live
 * row in the local DB and hashing it. Cost: O(live-row-count) per sync.
 * For the expected user (a few thousand rows tops) this is fine; the
 * design can evolve to an incremental checksum cache if profiling demands.
 */

package app.releaf.mobile.data.sync

import app.releaf.mobile.data.common.IsoClock
import app.releaf.mobile.data.drive.DriveClient
import app.releaf.mobile.data.drive.DriveError
import app.releaf.mobile.data.drive.downloadBytesAtPath
import app.releaf.mobile.data.drive.trashAtPath
import app.releaf.mobile.data.drive.uploadJsonAtPath
import app.releaf.mobile.data.notebook.ChapterDao
import app.releaf.mobile.data.notebook.NotebookDao
import app.releaf.mobile.data.notebook.PageDao
import app.releaf.mobile.data.notepad.NotepadDao
import app.releaf.mobile.data.task.TaskDao
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

// ---------- result types ----------

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

// ---------- core type ----------

class SyncRepository(
    private val notepadDao: NotepadDao,
    private val notebookDao: NotebookDao,
    private val chapterDao: ChapterDao,
    private val pageDao: PageDao,
    private val taskDao: TaskDao,
    private val syncStateDao: SyncStateDao,
    private val driveClient: DriveClient,
    /** SemVer + build metadata. Informational only on the wire. */
    private val appVersion: String = "0.1.0",
    private val json: Json = SyncJson,
) {

    /**
     * Full sync pass — push then pull. Safe to call repeatedly.
     *
     * @param userId      Owner of per-user tables (notepad_entries, tasks).
     * @param deviceId    This device's stable install id; stamped on
     *                    manifests + tombstones.
     * @param accessToken OAuth access token with drive.file scope. Caller
     *                    is responsible for freshness.
     */
    suspend fun sync(
        userId: String,
        deviceId: String,
        accessToken: String,
    ): SyncResult {
        // ---- 1. ensure the root folder ----
        val root = driveClient.ensureRootFolder(DrivePath.ROOT_FOLDER, accessToken)

        // ---- 2. pull remote manifest ----
        val remoteManifest = fetchRemoteManifest(root.id, accessToken)

        // ---- 3. version gate ----
        if (remoteManifest != null &&
            remoteManifest.schemaVersion.major > SchemaVersionConstants.MAJOR
        ) {
            return SyncResult(
                uploaded = 0, tombstoned = 0, downloaded = 0, failed = 0,
                versionBlocked = true,
            )
        }

        // ---- 4. build local snapshot (every live row + every dirty tombstone) ----
        val localSnapshot = buildLocalSnapshot(userId)

        var uploaded = 0
        var tombstoned = 0
        var failed = 0

        // ---- 5. push delta ----
        val manifestChecksums = HashMap<String, EntityChecksum>()
        val manifestTombstones = HashMap<String, TombstoneEntry>()

        // Seed from remote manifest — we'll overwrite / remove as we go.
        remoteManifest?.entityChecksums?.let { manifestChecksums.putAll(it) }
        remoteManifest?.tombstones?.let { manifestTombstones.putAll(it) }

        // Live row uploads — only rows whose hash differs from remote.
        for (snap in localSnapshot.liveRows) {
            val remoteChecksum = remoteManifest?.entityChecksums?.get(snap.id)?.sha256
            val needsUpload = remoteChecksum != snap.sha256 || snap.dirty
            if (!needsUpload) {
                // Still record in manifest we'll write at the end.
                manifestChecksums[snap.id] = EntityChecksum(
                    kind = snap.kind,
                    path = snap.path,
                    sha256 = snap.sha256,
                    updatedAt = snap.updatedAt,
                )
                continue
            }
            try {
                driveClient.uploadJsonAtPath(snap.bytes, snap.path, root.id, accessToken)
                manifestChecksums[snap.id] = EntityChecksum(
                    kind = snap.kind,
                    path = snap.path,
                    sha256 = snap.sha256,
                    updatedAt = snap.updatedAt,
                )
                manifestTombstones.remove(snap.id)
                markSyncedLocally(snap)
                uploaded++
            } catch (_: DriveError) {
                failed++
                // Row stays dirty locally, so the next pass retries.
            }
        }

        // Tombstones — upload/remove.
        for (tomb in localSnapshot.tombstones) {
            try {
                val tombstoneFile = TombstoneFile(
                    id = tomb.id,
                    kind = tomb.kind,
                    deletedAt = tomb.deletedAt,
                    deviceId = deviceId,
                    hardDeleteAt = null,
                )
                val tombstoneBytes = json.encodeToString(tombstoneFile).toByteArray(Charsets.UTF_8)
                driveClient.uploadJsonAtPath(
                    data = tombstoneBytes,
                    relativePath = DrivePath.tombstone(tomb.id),
                    rootFolderId = root.id,
                    accessToken = accessToken,
                )
                // The live payload file + any media blobs are left in place
                // per DRIVE_SCHEMA.md §"Hard-delete policy". `hard_delete_at`
                // remains null; a future "Empty archive" settings action
                // will stamp it and physically delete.
                manifestChecksums.remove(tomb.id)
                manifestTombstones[tomb.id] = TombstoneEntry(
                    kind = tomb.kind,
                    deletedAt = tomb.deletedAt,
                    deviceId = deviceId,
                    hardDeleteAt = null,
                )
                markTombstoneSyncedLocally(tomb)
                tombstoned++
            } catch (_: DriveError) {
                failed++
            }
        }

        // ---- 6. pull delta ----
        var downloaded = 0
        if (remoteManifest != null) {
            downloaded += pullDelta(
                remoteManifest = remoteManifest,
                localSnapshot = localSnapshot,
                rootFolderId = root.id,
                accessToken = accessToken,
            )
        }

        // ---- 7. write manifest last ----
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
            // Manifest failed — payloads are durable so next pass recovers.
            failed++
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

    /** Legacy entry point — pre-v2 callers still say `pushDirty`. */
    @Deprecated("Use sync()", ReplaceWith("sync(userId, deviceId, accessToken)"))
    suspend fun pushDirty(
        userId: String,
        deviceId: String,
        accessToken: String,
    ): SyncResult = sync(userId, deviceId, accessToken)

    // ------------------------------------------------------------------
    // Remote manifest fetch
    // ------------------------------------------------------------------

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

    // ------------------------------------------------------------------
    // Local snapshot construction
    // ------------------------------------------------------------------

    private suspend fun buildLocalSnapshot(userId: String): LocalSnapshot {
        val liveRows = mutableListOf<LiveRow>()
        val tombstones = mutableListOf<Tombstone>()

        // ----- notebooks -----
        val notebookRows = (notebookDao.activeRows() + notebookDao.dirtyRows()
            .filter { it.deletedAt == null })
            .distinctBy { it.id }
        for (row in notebookRows) {
            val payload = row.toV2Payload()
            val elem = json.encodeToJsonElement(NotebookPayloadV2.serializer(), payload)
            val bytes = CanonicalJson.encodeToBytes(elem)
            liveRows += LiveRow(
                id = row.id,
                kind = DrivePath.KIND_NOTEBOOK,
                path = DrivePath.notebook(row.id),
                bytes = bytes,
                sha256 = sha256Hex(bytes),
                updatedAt = row.updatedAt,
                dirty = row.dirty,
                markSynced = { driveFileId ->
                    notebookDao.markSynced(row.id, driveFileId ?: "", row.updatedAt)
                },
            )
        }
        for (row in notebookDao.dirtyRows().filter { it.deletedAt != null }) {
            tombstones += Tombstone(
                id = row.id,
                kind = DrivePath.KIND_NOTEBOOK,
                deletedAt = row.deletedAt ?: row.updatedAt,
                markSynced = { notebookDao.markTombstoneSynced(row.id) },
            )
        }

        // ----- chapters -----
        val chapterRows = (chapterDao.activeRows() + chapterDao.dirtyRows()
            .filter { it.deletedAt == null })
            .distinctBy { it.id }
        for (row in chapterRows) {
            val payload = row.toV2Payload()
            val elem = json.encodeToJsonElement(ChapterPayloadV2.serializer(), payload)
            val bytes = CanonicalJson.encodeToBytes(elem)
            liveRows += LiveRow(
                id = row.id,
                kind = DrivePath.KIND_CHAPTER,
                path = DrivePath.chapter(row.id),
                bytes = bytes,
                sha256 = sha256Hex(bytes),
                updatedAt = row.updatedAt,
                dirty = row.dirty,
                markSynced = { driveFileId ->
                    chapterDao.markSynced(row.id, driveFileId ?: "", row.updatedAt)
                },
            )
        }
        for (row in chapterDao.dirtyRows().filter { it.deletedAt != null }) {
            tombstones += Tombstone(
                id = row.id,
                kind = DrivePath.KIND_CHAPTER,
                deletedAt = row.deletedAt ?: row.updatedAt,
                markSynced = { chapterDao.markTombstoneSynced(row.id) },
            )
        }

        // ----- pages -----
        val pageRows = (pageDao.activeRows() + pageDao.dirtyRows()
            .filter { it.deletedAt == null })
            .distinctBy { it.id }
        for (row in pageRows) {
            val payload = row.toV2Payload()
            val elem = json.encodeToJsonElement(PagePayloadV2.serializer(), payload)
            val bytes = CanonicalJson.encodeToBytes(elem)
            liveRows += LiveRow(
                id = row.id,
                kind = DrivePath.KIND_PAGE,
                path = DrivePath.page(row.id),
                bytes = bytes,
                sha256 = sha256Hex(bytes),
                updatedAt = row.updatedAt,
                dirty = row.dirty,
                markSynced = { driveFileId ->
                    pageDao.markSynced(row.id, driveFileId ?: "", row.updatedAt)
                },
            )
        }
        for (row in pageDao.dirtyRows().filter { it.deletedAt != null }) {
            tombstones += Tombstone(
                id = row.id,
                kind = DrivePath.KIND_PAGE,
                deletedAt = row.deletedAt ?: row.updatedAt,
                markSynced = { pageDao.markTombstoneSynced(row.id) },
            )
        }

        // ----- notepad entries -----
        val notepadRows = (notepadDao.activeRows(userId) + notepadDao.dirtyRows()
            .filter { it.deletedAt == null && it.userId == userId })
            .distinctBy { it.id }
        for (row in notepadRows) {
            val payload = row.toV2Payload()
            val elem = json.encodeToJsonElement(NotepadEntryPayloadV2.serializer(), payload)
            val bytes = CanonicalJson.encodeToBytes(elem)
            liveRows += LiveRow(
                id = row.id,
                kind = DrivePath.KIND_NOTEPAD_ENTRY,
                path = DrivePath.notepadEntry(row.entryDate, row.id),
                bytes = bytes,
                sha256 = sha256Hex(bytes),
                updatedAt = row.updatedAt,
                dirty = row.dirty,
                markSynced = { driveFileId ->
                    notepadDao.markSynced(row.id, driveFileId ?: "", row.updatedAt)
                },
            )
        }
        for (row in notepadDao.dirtyRows().filter { it.deletedAt != null && it.userId == userId }) {
            tombstones += Tombstone(
                id = row.id,
                kind = DrivePath.KIND_NOTEPAD_ENTRY,
                deletedAt = row.deletedAt ?: row.updatedAt,
                markSynced = { notepadDao.markTombstoneSynced(row.id) },
            )
        }

        // ----- tasks -----
        val taskRows = (taskDao.activeRows() + taskDao.dirtyRows()
            .filter { it.deletedAt == null && it.userId == userId })
            .distinctBy { it.id }
            .filter { it.userId == userId }
        for (row in taskRows) {
            val payload = row.toV2Payload()
            val elem = json.encodeToJsonElement(TaskPayloadV2.serializer(), payload)
            val bytes = CanonicalJson.encodeToBytes(elem)
            liveRows += LiveRow(
                id = row.id,
                kind = DrivePath.KIND_TASK,
                path = DrivePath.task(row.id),
                bytes = bytes,
                sha256 = sha256Hex(bytes),
                updatedAt = row.updatedAt,
                dirty = row.dirty,
                markSynced = { taskDao.markSynced(row.id, row.updatedAt) },
            )
        }
        for (row in taskDao.dirtyRows().filter { it.deletedAt != null && it.userId == userId }) {
            tombstones += Tombstone(
                id = row.id,
                kind = DrivePath.KIND_TASK,
                deletedAt = row.deletedAt ?: row.updatedAt,
                markSynced = { taskDao.markTombstoneSynced(row.id) },
            )
        }

        return LocalSnapshot(liveRows = liveRows, tombstones = tombstones)
    }

    private suspend fun markSyncedLocally(row: LiveRow) {
        // driveFileId isn't strictly needed in v2 (paths are deterministic
        // and we don't re-use Drive IDs for routing), but we keep the
        // DAO signature for back-compat. Empty string signals "uploaded,
        // no persistent fileId tracked."
        row.markSynced("")
    }

    private suspend fun markTombstoneSyncedLocally(tomb: Tombstone) {
        tomb.markSynced()
    }

    // ------------------------------------------------------------------
    // Pull path
    // ------------------------------------------------------------------

    private suspend fun pullDelta(
        remoteManifest: ManifestV2,
        localSnapshot: LocalSnapshot,
        rootFolderId: String,
        accessToken: String,
    ): Int {
        var downloaded = 0
        val localById = localSnapshot.liveRows.associateBy { it.id }

        for ((entityId, remoteChecksum) in remoteManifest.entityChecksums) {
            val localRow = localById[entityId]

            // If local has the same checksum, already in sync.
            if (localRow != null && localRow.sha256 == remoteChecksum.sha256) continue

            // If local has a newer updated_at and is dirty, the upload
            // path above will handle it (not this pull). Skip.
            if (localRow != null &&
                localRow.dirty &&
                localRow.updatedAt >= remoteChecksum.updatedAt) continue

            // Otherwise — remote is newer or we don't have it locally.
            val bytes = driveClient.downloadBytesAtPath(
                relativePath = remoteChecksum.path,
                rootFolderId = rootFolderId,
                accessToken = accessToken,
            ) ?: continue
            try {
                applyRemotePayload(remoteChecksum.kind, bytes)
                downloaded++
            } catch (_: Exception) {
                // Parse or apply failed — skip this entity this pass,
                // we'll retry on the next one.
            }
        }

        // Apply tombstones from remote — soft-delete locally so the next
        // sync pass propagates the tombstone acknowledgment.
        for ((entityId, remoteTomb) in remoteManifest.tombstones) {
            if (localSnapshot.tombstones.any { it.id == entityId }) continue
            applyRemoteTombstone(entityId, remoteTomb)
            downloaded++
        }

        return downloaded
    }

    private suspend fun applyRemotePayload(kind: String, bytes: ByteArray) {
        val text = bytes.toString(Charsets.UTF_8)
        when (kind) {
            DrivePath.KIND_NOTEBOOK -> {
                val p = json.decodeFromString(NotebookPayloadV2.serializer(), text)
                notebookDao.upsert(p.toEntity(driveFileId = null))
            }
            DrivePath.KIND_CHAPTER -> {
                val p = json.decodeFromString(ChapterPayloadV2.serializer(), text)
                chapterDao.upsert(p.toEntity(driveFileId = null))
            }
            DrivePath.KIND_PAGE -> {
                val p = json.decodeFromString(PagePayloadV2.serializer(), text)
                pageDao.upsert(p.toEntity(driveFileId = null))
            }
            DrivePath.KIND_NOTEPAD_ENTRY -> {
                val p = json.decodeFromString(NotepadEntryPayloadV2.serializer(), text)
                notepadDao.upsert(p.toEntity(driveFileId = null))
            }
            DrivePath.KIND_TASK -> {
                val p = json.decodeFromString(TaskPayloadV2.serializer(), text)
                taskDao.upsert(p.toEntity())
            }
            else -> {
                // Unknown kind — forward-compat gap. Ignore.
            }
        }
    }

    private suspend fun applyRemoteTombstone(id: String, entry: TombstoneEntry) {
        val nowIso = entry.deletedAt
        when (entry.kind) {
            DrivePath.KIND_NOTEBOOK      -> notebookDao.softDelete(id, nowIso)
            DrivePath.KIND_CHAPTER       -> chapterDao.softDelete(id, nowIso)
            DrivePath.KIND_PAGE          -> pageDao.softDelete(id, nowIso)
            DrivePath.KIND_NOTEPAD_ENTRY -> notepadDao.softDelete(id, nowIso)
            DrivePath.KIND_TASK          -> taskDao.softDelete(id, nowIso)
        }
    }
}

// ---------- snapshot types ----------

/** A single live row ready to go to Drive. */
private data class LiveRow(
    val id: String,
    val kind: String,
    val path: String,
    val bytes: ByteArray,
    val sha256: String,
    val updatedAt: String,
    val dirty: Boolean,
    /** Call after a successful Drive upload. Clears `dirty` race-safely. */
    val markSynced: suspend (driveFileId: String?) -> Int,
) {
    // Data class equality over ByteArray breaks value identity; drop it.
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = id.hashCode()
}

private data class Tombstone(
    val id: String,
    val kind: String,
    val deletedAt: String,
    val markSynced: suspend () -> Int,
)

private data class LocalSnapshot(
    val liveRows: List<LiveRow>,
    val tombstones: List<Tombstone>,
)
