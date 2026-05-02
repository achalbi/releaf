/*
 * QuickInkSyncDataSource.kt
 *
 * QuickInk's implementation of `:shared:sync`'s `SyncDataSource`
 * interface. Mirror of Releaf's `ReleafSyncDataSource.kt` but for
 * QuickInk's three entity kinds: notepad_entries, captures,
 * ocr_results.
 *
 * Pagination policy: same as Releaf's — single-batch,
 * `nextCursor = null`. Paginated implementations would land here
 * when a real QuickInk user accumulates enough dirty rows that one
 * Drive payload exceeds ~4 MB.
 *
 * The OCR-blocks JSON encoding uses the round-trip in
 * `QuickInkSyncPayloads`: rows store `blocks_json` as a string
 * column, payloads carry it as a first-class JSON array on the
 * wire. Cross-app readers see the OCR blocks structurally without
 * a string-unwrap step.
 *
 * `lastAppliedManifestEtag` is a v1 no-op (returns null, set is
 * also no-op) — matches Releaf's stance. v2 etag-skip tracking
 * lands when the protocol does.
 *
 * See:
 *   - shared/android/shared/sync/.../SyncDataSource.kt — the protocol
 *   - apps/releaf/.../sync/ReleafSyncDataSource.kt — reference impl
 *   - QUICKINK_PROPOSAL.md §1 — design rationale
 */

package app.quickink.mobile.data.sync

import app.quickink.mobile.data.capture.CaptureDao
import app.quickink.mobile.data.category.CategoryDao
import app.quickink.mobile.data.ocr.OcrResultDao
import app.releaf.mobile.data.notepad.NotepadDao
import app.releaf.mobile.data.sync.CanonicalJson
import app.releaf.mobile.data.sync.sha256Hex
import app.releaf.mobile.data.sync.DirtyBatch
import app.releaf.mobile.data.sync.DirtyEntry
import app.releaf.mobile.data.sync.DrivePath
import app.releaf.mobile.data.sync.PendingTombstone
import app.releaf.mobile.data.sync.RemoteTombstone
import app.releaf.mobile.data.sync.RemoteUpsert
import app.releaf.mobile.data.sync.SchemaVersion
import app.releaf.mobile.data.sync.SyncAck
import app.releaf.mobile.data.sync.SyncCursor
import app.releaf.mobile.data.sync.SyncDataSource
import app.releaf.mobile.data.sync.SyncJson
import app.releaf.mobile.data.sync.TombstoneBatch
import kotlinx.serialization.json.Json

class QuickInkSyncDataSource(
    private val notepadDao: NotepadDao,
    private val captureDao: CaptureDao,
    private val ocrResultDao: OcrResultDao,
    private val categoryDao: CategoryDao,
    private val userId: String,
    private val json: Json = SyncJson,
) : SyncDataSource {

    // ─── Identity ──────────────────────────────────────────────────────

    /**
     * Drive folder layout — `Thoughtbasics/QuickInk/...`. The
     * "Thoughtbasics" wrapper holds future thoughts-line apps; the
     * QuickInk subfolder is the actual app root that all paths
     * (captures, ocr_results, notepad_entries, categories,
     * tombstones, manifest.json) hang off. `ensureRootFolder` on
     * the Drive client walks slash-separated paths automatically.
     */
    override val driveRootFolderName: String = "Thoughtbasics/QuickInk"

    override val schemaVersion: SchemaVersion = SchemaVersion.CURRENT

    override val appId: String = "quickink"

    // ─── Outbound: collect dirty rows ──────────────────────────────────

    override suspend fun nextDirtyBatch(after: SyncCursor?, limit: Int): DirtyBatch {
        // Single-batch implementation — see file header.
        if (after != null) return DirtyBatch(emptyList(), nextCursor = null)

        val entries = mutableListOf<DirtyEntry>()

        // ---- notepad entries (user-scoped) ----
        val notepadRows = (notepadDao.activeRows(userId) + notepadDao.dirtyRows()
            .filter { it.deletedAt == null && it.userId == userId })
            .distinctBy { it.id }
        for (row in notepadRows) {
            val payload = row.toV2Payload()
            val elem = json.encodeToJsonElement(NotepadEntryPayloadV2.serializer(), payload)
            val bytes = CanonicalJson.encodeToBytes(elem)
            entries += DirtyEntry(
                kind          = DrivePath.KIND_NOTEPAD_ENTRY,
                id            = row.id,
                drivePath     = DrivePath.notepadEntry(row.entryDate, row.id),
                payload       = bytes,
                payloadSha256 = sha256Hex(bytes),
                updatedAt     = row.updatedAt,
            )
        }

        // ---- captures (user-scoped) ----
        val captureRows = (captureDao.activeRows(userId) + captureDao.dirtyRows()
            .filter { it.deletedAt == null && it.userId == userId })
            .distinctBy { it.id }
        for (row in captureRows) {
            val payload = row.toV2Payload()
            val elem = json.encodeToJsonElement(CapturePayloadV2.serializer(), payload)
            val bytes = CanonicalJson.encodeToBytes(elem)
            entries += DirtyEntry(
                kind          = DrivePath.KIND_CAPTURE,
                id            = row.id,
                drivePath     = DrivePath.quickInkCapture(row.createdAt, row.id),
                payload       = bytes,
                payloadSha256 = sha256Hex(bytes),
                updatedAt     = row.updatedAt,
            )
        }

        // ---- categories (user-scoped) ----
        for (row in categoryDao.dirtyRows()
            .filter { it.deletedAt == null && it.userId == userId }) {
            val payload = row.toV1Payload()
            val elem = json.encodeToJsonElement(CategoryPayloadV1.serializer(), payload)
            val bytes = CanonicalJson.encodeToBytes(elem)
            entries += DirtyEntry(
                kind          = DrivePath.KIND_CATEGORY,
                id            = row.id,
                drivePath     = DrivePath.category(row.id),
                payload       = bytes,
                payloadSha256 = sha256Hex(bytes),
                updatedAt     = row.updatedAt,
            )
        }

        // ---- ocr_results (no direct user scoping; FK to captures
        //      handles cascade. For multi-user-per-install futures,
        //      filter by joining against captures.user_id).
        val ocrRows = (ocrResultDao.activeRows() + ocrResultDao.dirtyRows()
            .filter { it.deletedAt == null })
            .distinctBy { it.id }
        for (row in ocrRows) {
            val payload = row.toV2Payload()
            val elem = json.encodeToJsonElement(OcrResultPayloadV2.serializer(), payload)
            val bytes = CanonicalJson.encodeToBytes(elem)
            entries += DirtyEntry(
                kind          = DrivePath.KIND_OCR_RESULT,
                id            = row.id,
                drivePath     = DrivePath.quickInkOcrResult(
                    createdAt = row.createdAt,
                    captureId = row.captureId,
                    pageIndex = row.pageIndex,
                ),
                payload       = bytes,
                payloadSha256 = sha256Hex(bytes),
                updatedAt     = row.updatedAt,
            )
        }

        return DirtyBatch(entries, nextCursor = null)
    }

    override suspend fun nextTombstoneBatch(after: SyncCursor?, limit: Int): TombstoneBatch {
        if (after != null) return TombstoneBatch(emptyList(), nextCursor = null)

        val entries = mutableListOf<PendingTombstone>()

        for (row in notepadDao.dirtyRows().filter { it.deletedAt != null && it.userId == userId }) {
            entries += PendingTombstone(
                kind      = DrivePath.KIND_NOTEPAD_ENTRY,
                id        = row.id,
                deletedAt = row.deletedAt ?: row.updatedAt,
            )
        }
        for (row in captureDao.dirtyRows().filter { it.deletedAt != null && it.userId == userId }) {
            entries += PendingTombstone(
                kind      = DrivePath.KIND_CAPTURE,
                id        = row.id,
                deletedAt = row.deletedAt ?: row.updatedAt,
            )
        }
        for (row in categoryDao.dirtyRows().filter { it.deletedAt != null && it.userId == userId }) {
            entries += PendingTombstone(
                kind      = DrivePath.KIND_CATEGORY,
                id        = row.id,
                deletedAt = row.deletedAt ?: row.updatedAt,
            )
        }
        for (row in ocrResultDao.dirtyRows().filter { it.deletedAt != null }) {
            entries += PendingTombstone(
                kind      = DrivePath.KIND_OCR_RESULT,
                id        = row.id,
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
            DrivePath.KIND_NOTEPAD_ENTRY -> {
                val p = json.decodeFromString(NotepadEntryPayloadV2.serializer(), text)
                notepadDao.upsert(p.toEntity(driveFileId = driveFileId))
            }
            DrivePath.KIND_CAPTURE -> {
                val p = json.decodeFromString(CapturePayloadV2.serializer(), text)
                captureDao.insert(p.toEntity(driveFileId = driveFileId))
            }
            DrivePath.KIND_OCR_RESULT -> {
                val p = json.decodeFromString(OcrResultPayloadV2.serializer(), text)
                ocrResultDao.insert(p.toEntity(driveFileId = driveFileId))
            }
            DrivePath.KIND_CATEGORY -> {
                val p = json.decodeFromString(CategoryPayloadV1.serializer(), text)
                categoryDao.insert(p.toEntity(driveFileId = driveFileId))
            }
            else -> {
                // Forward-compat: kind we don't recognize, skip.
            }
        }
    }

    override suspend fun applyRemoteTombstone(tombstone: RemoteTombstone) {
        val nowIso = tombstone.deletedAt
        when (tombstone.kind) {
            DrivePath.KIND_NOTEPAD_ENTRY -> notepadDao.softDelete(tombstone.id, nowIso)
            DrivePath.KIND_CAPTURE       -> captureDao.softDelete(tombstone.id, nowIso)
            // Phase 4 follow-up — `ocrResultDao.softDelete` lands
            // alongside the rest of the DAO sync surface so the
            // KIND_OCR_RESULT remote-tombstone branch isn't a
            // no-op anymore. In v1, the FK `captures(id) ON
            // DELETE CASCADE` plus Drive's tombstone-by-id model
            // means typical remote→local deletes still flow as
            // "capture tombstoned → SQLite cascade deletes
            // children", so this row-level path is rare. Wiring
            // it now anyway keeps the data source's tombstone
            // handling complete for the search-from-trash / undo
            // flows that may surface single-page tombstones later.
            DrivePath.KIND_OCR_RESULT    -> ocrResultDao.softDelete(tombstone.id, nowIso)
            DrivePath.KIND_CATEGORY      -> categoryDao.softDelete(tombstone.id, nowIso)
        }
    }

    // ─── Bookkeeping ───────────────────────────────────────────────────

    override suspend fun markSynced(acks: List<SyncAck>) {
        // Same dual-call pattern as ReleafSyncDataSource — each DAO
        // method has a WHERE clause that matches only the right row
        // state, so calling both is safe and at most one mutates.
        for (ack in acks) {
            when (ack.kind) {
                DrivePath.KIND_NOTEPAD_ENTRY -> {
                    notepadDao.markSynced(ack.id, ack.driveFileId, ack.updatedAt)
                    notepadDao.markTombstoneSynced(ack.id)
                }
                DrivePath.KIND_CAPTURE -> {
                    captureDao.markSynced(ack.id, ack.driveFileId, ack.updatedAt)
                    captureDao.markTombstoneSynced(ack.id)
                }
                DrivePath.KIND_OCR_RESULT -> {
                    ocrResultDao.markSynced(ack.id, ack.driveFileId, ack.updatedAt)
                    ocrResultDao.markTombstoneSynced(ack.id)
                }
                DrivePath.KIND_CATEGORY -> {
                    categoryDao.markSynced(ack.id, ack.driveFileId, ack.updatedAt)
                    categoryDao.markTombstoneSynced(ack.id)
                }
                // else: forward-compat — unknown kind, skip silently.
            }
        }
    }

    override suspend fun lastAppliedManifestEtag(): String? {
        // Etag-based skip-pull is a v2 optimization. v1 always pulls.
        // Same as Releaf's stance.
        return null
    }

    override suspend fun setLastAppliedManifestEtag(etag: String) {
        // No-op until v2 etag tracking lands.
    }
}

