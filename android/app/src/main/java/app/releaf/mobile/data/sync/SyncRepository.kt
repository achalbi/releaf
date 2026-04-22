/*
 * SyncRepository.kt
 *
 * Orchestrates a single push pass: read every dirty row across the four
 * entity tables, upload live rows as JSON to Drive, trash tombstoned rows
 * that were previously uploaded, then write /Releaf/manifest.json and
 * update the local `sync_state` key-value store.
 *
 * Design notes:
 *
 *   - Push-only for v1. We don't yet reconcile Drive → local; that's its
 *     own phase (pull + conflict resolution via conflict_stub).
 *
 *   - Flat Drive layout. One subfolder per entity type under `/Releaf/`;
 *     `{row.id}.json` is the filename. Parent refs ride inside the payload
 *     (see SyncPayloads). This means the worker can upload rows in any
 *     order — no need to create the notebook before its chapters.
 *
 *   - Race-safe. `markSynced` guards on the `updated_at` snapshot we read
 *     at the start of the row's turn; a concurrent user edit bumps
 *     updated_at, the WHERE misses, and the row stays dirty for the next
 *     pass. See NotepadDao.markSynced for the design note.
 *
 *   - Partial-progress tolerant. Per-row failures are caught and counted,
 *     not rethrown — so one unhappy row doesn't poison the rest of the
 *     pass. The dirty flag stays set on failed rows, so the next pass
 *     retries them automatically.
 *
 *   - Tombstone for a never-synced row (driveFileId == null) is a no-op
 *     on the Drive side; we just clear `dirty` locally.
 */

package app.releaf.mobile.data.sync

import app.releaf.mobile.data.common.IsoClock
import app.releaf.mobile.data.drive.DriveClient
import app.releaf.mobile.data.drive.DriveError
import app.releaf.mobile.data.notebook.ChapterDao
import app.releaf.mobile.data.notebook.NotebookDao
import app.releaf.mobile.data.notebook.PageDao
import app.releaf.mobile.data.notepad.NotepadDao
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/**
 * Summary of one push pass. Logged by the worker and will feed the UI's
 * "last synced" badge. `failed` rows are still dirty locally — they'll
 * be retried on the next pass.
 */
data class PushResult(
    val uploaded: Int,
    val tombstoned: Int,
    val failed: Int,
) {
    val touched: Int get() = uploaded + tombstoned + failed
}

class SyncRepository(
    private val notepadDao: NotepadDao,
    private val notebookDao: NotebookDao,
    private val chapterDao: ChapterDao,
    private val pageDao: PageDao,
    private val syncStateDao: SyncStateDao,
    private val driveClient: DriveClient,
    private val json: Json = DefaultJson,
) {

    /**
     * Push every locally-dirty row to Drive, then write the manifest and
     * bump `sync_state`. Safe to call repeatedly — the `dirty = 1`
     * predicate keeps us from re-uploading unchanged rows, and the
     * `updated_at` snapshot guard in markSynced handles races with the
     * UI.
     *
     * @param userId      Owner of the notepad rows (and manifest).
     * @param deviceId    This device's stable install-id, recorded in
     *                    the manifest for future multi-device diagnostics.
     * @param accessToken OAuth token for Drive. The caller is responsible
     *                    for refreshing before calling.
     */
    suspend fun pushDirty(
        userId: String,
        deviceId: String,
        accessToken: String,
    ): PushResult {
        // ---- ensure the folder tree exists ---------------------------------
        // Folder ensures are idempotent; running them every pass is cheap
        // and means we heal from a user-deleted folder on the Drive web UI.
        val root = driveClient.ensureRootFolder(ROOT_FOLDER_NAME, accessToken)
        val notepadFolderId  = driveClient.ensureFolder(NOTEPAD_FOLDER,  root.id, accessToken).id
        val notebookFolderId = driveClient.ensureFolder(NOTEBOOK_FOLDER, root.id, accessToken).id
        val chapterFolderId  = driveClient.ensureFolder(CHAPTER_FOLDER,  root.id, accessToken).id
        val pageFolderId     = driveClient.ensureFolder(PAGE_FOLDER,     root.id, accessToken).id

        var uploaded = 0
        var tombstoned = 0
        var failed = 0

        fun record(outcome: Outcome) = when (outcome) {
            Outcome.Uploaded   -> uploaded++
            Outcome.Tombstoned -> tombstoned++
            Outcome.Failed     -> failed++
        }

        // ---- notepad ----
        for (row in notepadDao.dirtyRows()) {
            record(
                push(
                    rowId        = row.id,
                    driveFileId  = row.driveFileId,
                    updatedAt    = row.updatedAt,
                    deletedAt    = row.deletedAt,
                    folderId     = notepadFolderId,
                    payloadBytes = { json.encodeToString(row.toPayload()).encodeToByteArray() },
                    accessToken  = accessToken,
                    markSynced   = notepadDao::markSynced,
                    markTombstoneSynced = notepadDao::markTombstoneSynced,
                )
            )
        }

        // ---- notebooks ----
        for (row in notebookDao.dirtyRows()) {
            record(
                push(
                    rowId        = row.id,
                    driveFileId  = row.driveFileId,
                    updatedAt    = row.updatedAt,
                    deletedAt    = row.deletedAt,
                    folderId     = notebookFolderId,
                    payloadBytes = { json.encodeToString(row.toPayload()).encodeToByteArray() },
                    accessToken  = accessToken,
                    markSynced   = notebookDao::markSynced,
                    markTombstoneSynced = notebookDao::markTombstoneSynced,
                )
            )
        }

        // ---- chapters ----
        for (row in chapterDao.dirtyRows()) {
            record(
                push(
                    rowId        = row.id,
                    driveFileId  = row.driveFileId,
                    updatedAt    = row.updatedAt,
                    deletedAt    = row.deletedAt,
                    folderId     = chapterFolderId,
                    payloadBytes = { json.encodeToString(row.toPayload()).encodeToByteArray() },
                    accessToken  = accessToken,
                    markSynced   = chapterDao::markSynced,
                    markTombstoneSynced = chapterDao::markTombstoneSynced,
                )
            )
        }

        // ---- pages ----
        for (row in pageDao.dirtyRows()) {
            record(
                push(
                    rowId        = row.id,
                    driveFileId  = row.driveFileId,
                    updatedAt    = row.updatedAt,
                    deletedAt    = row.deletedAt,
                    folderId     = pageFolderId,
                    payloadBytes = { json.encodeToString(row.toPayload()).encodeToByteArray() },
                    accessToken  = accessToken,
                    markSynced   = pageDao::markSynced,
                    markTombstoneSynced = pageDao::markTombstoneSynced,
                )
            )
        }

        // ---- manifest + sync_state -----------------------------------------
        // Counts are "best local guess at what lives in Drive" — not a
        // strict post-condition. If any rows failed this pass they're still
        // missing from Drive; the next pass's manifest will correct this.
        val nowIso = IsoClock.nowIso()
        val counts = mapOf(
            NOTEPAD_FOLDER  to notepadDao.countActive(userId),
            NOTEBOOK_FOLDER to notebookDao.countActive(),
            CHAPTER_FOLDER  to chapterDao.countActive(),
            PAGE_FOLDER     to pageDao.countActive(),
        )
        val manifest = SyncManifest(
            userId        = userId,
            deviceId      = deviceId,
            lastSyncedAt  = nowIso,
            entityCounts  = counts,
        )
        val manifestBytes = json.encodeToString(manifest).encodeToByteArray()
        driveClient.uploadJson(manifestBytes, MANIFEST_FILE, root.id, accessToken)

        syncStateDao.upsert(SyncStateEntity(
            key       = SyncStateKeys.LAST_FULL_SYNC_AT,
            value     = nowIso,
            updatedAt = nowIso,
        ))
        syncStateDao.upsert(SyncStateEntity(
            key       = SyncStateKeys.MANIFEST_CHECKSUM,
            value     = sha256Hex(manifestBytes),
            updatedAt = nowIso,
        ))
        syncStateDao.upsert(SyncStateEntity(
            key       = SyncStateKeys.PENDING_COUNT,
            value     = failed.toString(),
            updatedAt = nowIso,
        ))

        return PushResult(uploaded = uploaded, tombstoned = tombstoned, failed = failed)
    }

    /**
     * Push one row — upload live payload or trash tombstoned file. Catches
     * DriveError to keep a single bad row from poisoning the rest of the
     * pass; caller counts failures via the returned [Outcome].
     */
    private suspend fun push(
        rowId: String,
        driveFileId: String?,
        updatedAt: String,
        deletedAt: String?,
        folderId: String,
        payloadBytes: () -> ByteArray,
        accessToken: String,
        markSynced: suspend (id: String, driveFileId: String, updatedAtSnapshot: String) -> Int,
        markTombstoneSynced: suspend (id: String) -> Int,
    ): Outcome = try {
        if (deletedAt != null) {
            // Never-synced rows have no driveFileId — nothing to trash on
            // Drive, we just clear the dirty flag so the tombstone stops
            // being picked up.
            driveFileId?.let { driveClient.trash(it, accessToken) }
            markTombstoneSynced(rowId)
            Outcome.Tombstoned
        } else {
            val file = driveClient.uploadJson(
                data       = payloadBytes(),
                filename   = "$rowId.json",
                parentId   = folderId,
                accessToken = accessToken,
            )
            markSynced(rowId, file.id, updatedAt)
            Outcome.Uploaded
        }
    } catch (_: DriveError) {
        Outcome.Failed
    }

    private enum class Outcome { Uploaded, Tombstoned, Failed }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return buildString(digest.size * 2) {
            for (b in digest) {
                val v = b.toInt() and 0xff
                append(HEX[v ushr 4])
                append(HEX[v and 0x0f])
            }
        }
    }

    companion object {
        private const val ROOT_FOLDER_NAME = "Releaf"
        private const val NOTEPAD_FOLDER   = "notepad_entries"
        private const val NOTEBOOK_FOLDER  = "notebooks"
        private const val CHAPTER_FOLDER   = "chapters"
        private const val PAGE_FOLDER      = "pages"
        private const val MANIFEST_FILE    = "manifest.json"

        private const val HEX = "0123456789abcdef"

        /** Default JSON for sync payloads. Compact, tolerant, explicit. */
        val DefaultJson: Json = Json {
            prettyPrint       = false
            ignoreUnknownKeys = true
            encodeDefaults    = true
        }
    }
}
