/*
 * ReleafSyncDataSource.kt
 *
 * Releaf's implementation of `SyncDataSource` (defined in :shared:sync).
 *
 * This file holds the Releaf-coupled code that USED to live inside the
 * old monolithic `SyncRepository.kt` (PR #3c moved the orchestrator out
 * to :shared:sync and split this Releaf-specific snapshot/apply/mark
 * code into its own data source).
 *
 * Pagination policy: this implementation collects ALL dirty rows /
 * tombstones into a single batch and returns null cursor on the first
 * call. That matches the prior single-snapshot behavior. The
 * SyncDataSource contract allows paginated implementations; we'll
 * paginate the day a Releaf user has more dirty rows than fits in one
 * ~4 MB batch. (Cursor-paginated implementation would go here, not in
 * the shared module.)
 *
 * Mirror of iOS's ReleafSyncDataSource.swift. See also:
 *   - shared/android/shared/sync/.../SyncDataSource.kt (the protocol)
 *   - docs/QUICKINK_DESIGN.md §1 (the design rationale + Releaf-side
 *     implementation notes)
 */

package app.releaf.mobile.data.sync

import app.releaf.mobile.data.common.IsoClock
import app.releaf.mobile.data.notebook.ChapterDao
import app.releaf.mobile.data.notebook.NotebookDao
import app.releaf.mobile.data.notebook.PageDao
import app.releaf.mobile.data.notepad.NotepadDao
import app.releaf.mobile.data.task.TaskDao
import kotlinx.serialization.json.Json

class ReleafSyncDataSource(
    private val notepadDao: NotepadDao,
    private val notebookDao: NotebookDao,
    private val chapterDao: ChapterDao,
    private val pageDao: PageDao,
    private val taskDao: TaskDao,
    private val userId: String,
    private val json: Json = SyncJson,
) : SyncDataSource {

    // ─── Identity ──────────────────────────────────────────────────────

    override val driveRootFolderName: String = "Releaf"

    override val schemaVersion: SchemaVersion = SchemaVersion.CURRENT

    override val appId: String = "releaf"

    // ─── Outbound: collect dirty rows ──────────────────────────────────

    override suspend fun nextDirtyBatch(after: SyncCursor?, limit: Int): DirtyBatch {
        // Single-batch implementation — see file header.
        if (after != null) return DirtyBatch(emptyList(), nextCursor = null)

        val entries = mutableListOf<DirtyEntry>()

        // ---- notebooks ----
        val notebookRows = (notebookDao.activeRows() + notebookDao.dirtyRows()
            .filter { it.deletedAt == null })
            .distinctBy { it.id }
        for (row in notebookRows) {
            val payload = row.toV2Payload()
            val elem = json.encodeToJsonElement(NotebookPayloadV2.serializer(), payload)
            val bytes = CanonicalJson.encodeToBytes(elem)
            entries += DirtyEntry(
                kind = DrivePath.KIND_NOTEBOOK,
                id = row.id,
                drivePath = DrivePath.notebook(row.id),
                payload = bytes,
                payloadSha256 = sha256Hex(bytes),
                updatedAt = row.updatedAt,
            )
        }

        // ---- chapters ----
        val chapterRows = (chapterDao.activeRows() + chapterDao.dirtyRows()
            .filter { it.deletedAt == null })
            .distinctBy { it.id }
        for (row in chapterRows) {
            val payload = row.toV2Payload()
            val elem = json.encodeToJsonElement(ChapterPayloadV2.serializer(), payload)
            val bytes = CanonicalJson.encodeToBytes(elem)
            entries += DirtyEntry(
                kind = DrivePath.KIND_CHAPTER,
                id = row.id,
                drivePath = DrivePath.chapter(row.id),
                payload = bytes,
                payloadSha256 = sha256Hex(bytes),
                updatedAt = row.updatedAt,
            )
        }

        // ---- pages ----
        val pageRows = (pageDao.activeRows() + pageDao.dirtyRows()
            .filter { it.deletedAt == null })
            .distinctBy { it.id }
        for (row in pageRows) {
            val payload = row.toV2Payload()
            val elem = json.encodeToJsonElement(PagePayloadV2.serializer(), payload)
            val bytes = CanonicalJson.encodeToBytes(elem)
            entries += DirtyEntry(
                kind = DrivePath.KIND_PAGE,
                id = row.id,
                drivePath = DrivePath.page(row.id),
                payload = bytes,
                payloadSha256 = sha256Hex(bytes),
                updatedAt = row.updatedAt,
            )
        }

        // ---- notepad entries ----
        val notepadRows = (notepadDao.activeRows(userId) + notepadDao.dirtyRows()
            .filter { it.deletedAt == null && it.userId == userId })
            .distinctBy { it.id }
        for (row in notepadRows) {
            val payload = row.toV2Payload()
            val elem = json.encodeToJsonElement(NotepadEntryPayloadV2.serializer(), payload)
            val bytes = CanonicalJson.encodeToBytes(elem)
            entries += DirtyEntry(
                kind = DrivePath.KIND_NOTEPAD_ENTRY,
                id = row.id,
                drivePath = DrivePath.notepadEntry(row.entryDate, row.id),
                payload = bytes,
                payloadSha256 = sha256Hex(bytes),
                updatedAt = row.updatedAt,
            )
        }

        // ---- tasks ----
        val taskRows = (taskDao.activeRows() + taskDao.dirtyRows()
            .filter { it.deletedAt == null && it.userId == userId })
            .distinctBy { it.id }
            .filter { it.userId == userId }
        for (row in taskRows) {
            val payload = row.toV2Payload()
            val elem = json.encodeToJsonElement(TaskPayloadV2.serializer(), payload)
            val bytes = CanonicalJson.encodeToBytes(elem)
            entries += DirtyEntry(
                kind = DrivePath.KIND_TASK,
                id = row.id,
                drivePath = DrivePath.task(row.id),
                payload = bytes,
                payloadSha256 = sha256Hex(bytes),
                updatedAt = row.updatedAt,
            )
        }

        return DirtyBatch(entries, nextCursor = null)
    }

    override suspend fun nextTombstoneBatch(after: SyncCursor?, limit: Int): TombstoneBatch {
        if (after != null) return TombstoneBatch(emptyList(), nextCursor = null)

        val entries = mutableListOf<PendingTombstone>()

        for (row in notebookDao.dirtyRows().filter { it.deletedAt != null }) {
            entries += PendingTombstone(
                kind = DrivePath.KIND_NOTEBOOK,
                id = row.id,
                deletedAt = row.deletedAt ?: row.updatedAt,
            )
        }
        for (row in chapterDao.dirtyRows().filter { it.deletedAt != null }) {
            entries += PendingTombstone(
                kind = DrivePath.KIND_CHAPTER,
                id = row.id,
                deletedAt = row.deletedAt ?: row.updatedAt,
            )
        }
        for (row in pageDao.dirtyRows().filter { it.deletedAt != null }) {
            entries += PendingTombstone(
                kind = DrivePath.KIND_PAGE,
                id = row.id,
                deletedAt = row.deletedAt ?: row.updatedAt,
            )
        }
        for (row in notepadDao.dirtyRows().filter { it.deletedAt != null && it.userId == userId }) {
            entries += PendingTombstone(
                kind = DrivePath.KIND_NOTEPAD_ENTRY,
                id = row.id,
                deletedAt = row.deletedAt ?: row.updatedAt,
            )
        }
        for (row in taskDao.dirtyRows().filter { it.deletedAt != null && it.userId == userId }) {
            entries += PendingTombstone(
                kind = DrivePath.KIND_TASK,
                id = row.id,
                deletedAt = row.deletedAt ?: row.updatedAt,
            )
        }

        return TombstoneBatch(entries, nextCursor = null)
    }

    // ─── Inbound: apply remote changes ─────────────────────────────────

    override suspend fun applyRemoteUpsert(change: RemoteUpsert) {
        val text = change.payload.toString(Charsets.UTF_8)
        val driveFileId = change.driveFileId.takeIf { it.isNotEmpty() }
        when (change.kind) {
            DrivePath.KIND_NOTEBOOK -> {
                val p = json.decodeFromString(NotebookPayloadV2.serializer(), text)
                notebookDao.upsert(p.toEntity(driveFileId = driveFileId))
            }
            DrivePath.KIND_CHAPTER -> {
                val p = json.decodeFromString(ChapterPayloadV2.serializer(), text)
                chapterDao.upsert(p.toEntity(driveFileId = driveFileId))
            }
            DrivePath.KIND_PAGE -> {
                val p = json.decodeFromString(PagePayloadV2.serializer(), text)
                pageDao.upsert(p.toEntity(driveFileId = driveFileId))
            }
            DrivePath.KIND_NOTEPAD_ENTRY -> {
                val p = json.decodeFromString(NotepadEntryPayloadV2.serializer(), text)
                notepadDao.upsert(p.toEntity(driveFileId = driveFileId))
            }
            DrivePath.KIND_TASK -> {
                val p = json.decodeFromString(TaskPayloadV2.serializer(), text)
                taskDao.upsert(p.toEntity())
            }
            else -> {
                // Forward-compat: kind we don't recognize, skip.
            }
        }
    }

    override suspend fun applyRemoteTombstone(tombstone: RemoteTombstone) {
        val nowIso = tombstone.deletedAt
        when (tombstone.kind) {
            DrivePath.KIND_NOTEBOOK      -> notebookDao.softDelete(tombstone.id, nowIso)
            DrivePath.KIND_CHAPTER       -> chapterDao.softDelete(tombstone.id, nowIso)
            DrivePath.KIND_PAGE          -> pageDao.softDelete(tombstone.id, nowIso)
            DrivePath.KIND_NOTEPAD_ENTRY -> notepadDao.softDelete(tombstone.id, nowIso)
            DrivePath.KIND_TASK          -> taskDao.softDelete(tombstone.id, nowIso)
        }
    }

    // ─── Bookkeeping ───────────────────────────────────────────────────

    override suspend fun markSynced(acks: List<SyncAck>) {
        // The orchestrator doesn't distinguish "row upload ack" from
        // "tombstone upload ack" — they both arrive here. We can tell
        // them apart by row state on the receiving side and call the
        // right DAO method.
        //
        // Calling BOTH `markSynced` and `markTombstoneSynced` per ack
        // also works (each DAO method has a WHERE clause that matches
        // only the right row state) but doubles the SQL writes.
        // Cheaper to disambiguate up front via DAO `findById`-style
        // probes — but for the v1 shape, we call both. The DAO methods
        // are idempotent and at most one of them mutates the row.
        for (ack in acks) {
            when (ack.kind) {
                DrivePath.KIND_NOTEBOOK -> {
                    notebookDao.markSynced(ack.id, ack.driveFileId, ack.updatedAt)
                    notebookDao.markTombstoneSynced(ack.id)
                }
                DrivePath.KIND_CHAPTER -> {
                    chapterDao.markSynced(ack.id, ack.driveFileId, ack.updatedAt)
                    chapterDao.markTombstoneSynced(ack.id)
                }
                DrivePath.KIND_PAGE -> {
                    pageDao.markSynced(ack.id, ack.driveFileId, ack.updatedAt)
                    pageDao.markTombstoneSynced(ack.id)
                }
                DrivePath.KIND_NOTEPAD_ENTRY -> {
                    notepadDao.markSynced(ack.id, ack.driveFileId, ack.updatedAt)
                    notepadDao.markTombstoneSynced(ack.id)
                }
                DrivePath.KIND_TASK -> {
                    taskDao.markSynced(ack.id, ack.updatedAt)
                    taskDao.markTombstoneSynced(ack.id)
                }
                // else: forward-compat — unknown kind, skip silently.
            }
        }
    }

    override suspend fun lastAppliedManifestEtag(): String? {
        // Etag-based skip-pull is a v2 optimization. v1 always pulls.
        // Returning null here means the worker fetches the manifest
        // every pass — same as today's behavior.
        return null
    }

    override suspend fun setLastAppliedManifestEtag(etag: String) {
        // No-op until v2 etag tracking lands; see above.
    }

    @Suppress("unused") // referenced by future helpers
    private fun nowIso(): String = IsoClock.nowIso()
}
