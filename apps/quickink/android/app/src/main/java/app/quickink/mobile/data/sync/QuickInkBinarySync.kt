/*
 * QuickInkBinarySync.kt
 *
 * Phase 6 — back up scanned PDFs + preview JPEGs to Drive. Sits
 * alongside `SyncRepository` (which only ships JSON metadata) and
 * runs in the same scheduler tick:
 *
 *   1. uploadAndCascade  — every active capture missing a Drive
 *      binary id gets read from local storage and uploaded; the
 *      resulting Drive file id is stamped onto the local row.
 *      Tombstoned rows with non-null Drive ids are trashed and
 *      their ids nulled out.
 *   2. restorePending    — for every active capture whose row has
 *      a Drive id but no readable local file (fresh-device restore
 *      from JSON metadata only), the binary is downloaded back
 *      into local storage and `pdf_uri` / `preview_uri` are
 *      rewritten to the new local path.
 *
 * Drive layout matches `QuickInkSyncDataSource`'s JSON paths:
 *   Thoughtbasics/QuickInk/<yyyy>/<mm>/<dd>/<id>.json    (metadata)
 *   Thoughtbasics/QuickInk/<yyyy>/<mm>/<dd>/<id>.pdf     (binary)
 *   Thoughtbasics/QuickInk/<yyyy>/<mm>/<dd>/<id>.jpg     (preview)
 *
 * Mirror of iOS `QuickInkBinarySync.swift`.
 */

package app.quickink.mobile.data.sync

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.util.Log
import app.quickink.mobile.data.capture.CaptureDao
import app.quickink.mobile.data.capture.CaptureEntity
import app.quickink.mobile.data.profile.ProfileSettingsDao
import app.quickink.mobile.data.voicenote.VoiceNoteDao
import app.quickink.mobile.data.voicenote.VoiceNoteEntity
import app.releaf.mobile.data.common.AttachmentStorage
import app.releaf.mobile.data.common.Uuidv7
import app.releaf.mobile.data.drive.DriveClient
import app.releaf.mobile.data.drive.DriveError
import app.releaf.mobile.data.drive.DriveFile
import app.releaf.mobile.data.drive.findFileAtPath
import app.releaf.mobile.data.drive.uploadBinaryAtPath
import java.io.ByteArrayOutputStream
import java.io.File

private const val TAG = "QuickInkSync"

data class QuickInkBinarySyncResult(
    val attempted: Int,
    val completed: Int,
    val failed: Int,
)

private data class MutableBinaryStats(
    var attempted: Int = 0,
    var completed: Int = 0,
    var failed: Int = 0,
) {
    fun toResult(): QuickInkBinarySyncResult = QuickInkBinarySyncResult(
        attempted = attempted,
        completed = completed,
        failed = failed,
    )
}

class QuickInkBinarySync(
    private val context: Context,
    private val captureDao: CaptureDao,
    private val profileSettingsDao: ProfileSettingsDao,
    private val voiceNoteDao: VoiceNoteDao,
    private val storyVoiceClipDao: app.quickink.mobile.data.storyvoiceclip.StoryVoiceClipDao,
    private val driveClient: DriveClient,
    private val driveRootFolderName: String = "Thoughtbasics/QuickInk",
) {

    /**
     * Run one upload-and-cascade pass for the given user. Idempotent:
     * captures already mirrored to Drive are skipped; tombstoned
     * rows whose Drive ids are already nulled are skipped.
     */
    suspend fun uploadAndCascade(userId: String, accessToken: String): QuickInkBinarySyncResult {
        val stats = MutableBinaryStats()
        val pending = captureDao.pendingBinaryRows(userId)
        val profile = profileSettingsDao.findByUser(userId)
        val profileNeedsUpload = profile != null
            && profile.deletedAt == null
            && profile.photoLocalUri != null
            && profile.photoDriveFileId == null
        if (pending.isEmpty() && !profileNeedsUpload && profile?.deletedAt == null) {
            return stats.toResult()
        }

        val root = driveClient.ensureRootFolder(driveRootFolderName, accessToken)

        for (row in pending) {
            if (row.deletedAt != null) {
                cascadeTombstone(row, accessToken, stats)
            } else {
                uploadLive(row, root.id, accessToken, stats)
            }
        }

        // Profile photo binary mirrors the capture flow but lives at
        // `<root>/profile_settings/<userId>.jpg`. Single per-user file,
        // so we don't need a date-bucket directory.
        if (profile != null) {
            uploadProfilePhotoIfNeeded(profile.id, profile, root.id, accessToken, stats)
        }

        // Voice notes — mirror the capture/PDF upload pattern. The
        // .m4a lands at
        // `<root>/<yyyy>/<mm>/<dd>/<captureId>/voice-<noteId>.m4a`,
        // beside the metadata JSON `QuickInkSyncDataSource` pushes.
        // Tombstoned rows trash their audio binary the same way
        // tombstoned captures trash their PDF.
        val pendingAudio = voiceNoteDao.rowsMissingAudioUpload(userId) +
            voiceNoteDao.dirtyRows().filter { it.userId == userId && it.deletedAt != null && it.audioDriveFileId != null }
        for (row in pendingAudio.distinctBy { it.id }) {
            if (row.deletedAt != null) {
                cascadeVoiceTombstone(row, accessToken, stats)
            } else {
                uploadLiveVoice(row, root.id, accessToken, stats)
            }
        }

        // Story voice clips — same shape as voice_notes, keyed off
        // story_item_id and landing at
        // `<root>/<yyyy>/<mm>/<dd>/<storyItemId>/storyvoice-<id>.m4a`.
        val pendingStoryAudio = storyVoiceClipDao.rowsMissingAudioUpload(userId) +
            storyVoiceClipDao.dirtyRows().filter {
                it.userId == userId && it.deletedAt != null && it.audioDriveFileId != null
            }
        for (row in pendingStoryAudio.distinctBy { it.id }) {
            if (row.deletedAt != null) {
                cascadeStoryVoiceTombstone(row, accessToken, stats)
            } else {
                uploadLiveStoryVoice(row, root.id, accessToken, stats)
            }
        }

        return stats.toResult()
    }

    private suspend fun uploadLiveStoryVoice(
        row: app.quickink.mobile.data.storyvoiceclip.StoryVoiceClipEntity,
        rootFolderId: String,
        accessToken: String,
        stats: MutableBinaryStats,
    ) {
        if (row.audioDriveFileId != null) return
        val bytes = readLocalBytes(row.audioUri)
        if (bytes == null) {
            Log.w(TAG, "binary.upload.storyvoice skipped — local file missing (row=${row.id.take(8)}…)")
            return
        }
        val bucket = dateBucket(row.createdAt)
        uploadBinary(
            label = "storyvoice row=${row.id.take(8)}…",
            stats = stats,
        ) {
            driveClient.uploadBinaryAtPath(
                data         = bytes,
                contentType  = "audio/mp4",
                relativePath = "$bucket/${row.storyItemId}/storyvoice-${row.id}.m4a",
                rootFolderId = rootFolderId,
                accessToken  = accessToken,
            )
        }?.let { driveFile ->
            storyVoiceClipDao.markAudioSynced(
                id          = row.id,
                driveFileId = driveFile.id,
                now         = app.releaf.mobile.data.common.IsoClock.nowIso(),
            )
        }
    }

    private suspend fun cascadeStoryVoiceTombstone(
        row: app.quickink.mobile.data.storyvoiceclip.StoryVoiceClipEntity,
        accessToken: String,
        stats: MutableBinaryStats,
    ) {
        row.audioDriveFileId?.let { id ->
            trashBinary(
                driveFileId  = id,
                label        = "storyvoice tombstone row=${row.id.take(8)}…",
                stats        = stats,
                clearLocalId = {
                    storyVoiceClipDao.markAudioSynced(
                        id          = row.id,
                        driveFileId = "",
                        now         = app.releaf.mobile.data.common.IsoClock.nowIso(),
                    )
                },
            ) { driveClient.trash(id, accessToken) }
        }
    }

    /** Pull story voice-clip .m4a binaries from Drive for any active
     *  row whose `audio_drive_file_id` is set but whose local file is
     *  missing. Mirror of [restorePendingVoiceNotes]. */
    suspend fun restorePendingStoryVoiceClips(userId: String, accessToken: String): QuickInkBinarySyncResult {
        val stats = MutableBinaryStats()
        val rows = storyVoiceClipDao.rowsWithRemoteAudio(userId)
        if (rows.isEmpty()) return stats.toResult()
        Log.i(TAG, "restorePendingStoryVoiceClips: scanning ${rows.size} rows")
        for (row in rows) {
            if (localFileExists(row.audioUri)) continue
            val driveId = row.audioDriveFileId ?: continue
            stats.attempted++
            val rowTag = "row=${row.id.take(8)}… audioDriveId=${driveId.take(12)}…"
            try {
                val bytes = driveClient.downloadBytes(driveId, accessToken)
                val newUri = writeBytes(bytes, "m4a")
                if (newUri != null) {
                    storyVoiceClipDao.setAudioUri(
                        id  = row.id,
                        uri = newUri.toString(),
                        now = app.releaf.mobile.data.common.IsoClock.nowIso(),
                    )
                    stats.completed++
                    Log.i(TAG, "restorePendingStoryVoiceClips.audio ok ($rowTag, ${bytes.size}B)")
                } else {
                    stats.failed++
                }
            } catch (e: DriveError.Unauthenticated) {
                throw e
            } catch (e: DriveError.RateLimited) {
                throw e
            } catch (e: Exception) {
                stats.failed++
                Log.w(TAG, "restorePendingStoryVoiceClips.audio failed ($rowTag): $e")
            }
        }
        return stats.toResult()
    }

    private suspend fun uploadLiveVoice(
        row: VoiceNoteEntity,
        rootFolderId: String,
        accessToken: String,
        stats: MutableBinaryStats,
    ) {
        if (row.audioDriveFileId != null) return
        val bytes = readLocalBytes(row.audioUri)
        if (bytes == null) {
            Log.w(TAG, "binary.upload.voice skipped — local file missing (row=${row.id.take(8)}…)")
            return
        }
        val bucket = dateBucket(row.createdAt)
        uploadBinary(
            label = "voice row=${row.id.take(8)}…",
            stats = stats,
        ) {
            driveClient.uploadBinaryAtPath(
                data         = bytes,
                contentType  = "audio/mp4",
                relativePath = "$bucket/${row.captureId}/voice-${row.id}.m4a",
                rootFolderId = rootFolderId,
                accessToken  = accessToken,
            )
        }?.let { driveFile ->
            voiceNoteDao.markAudioSynced(row.id, driveFile.id)
        }
    }

    private suspend fun cascadeVoiceTombstone(
        row: VoiceNoteEntity,
        accessToken: String,
        stats: MutableBinaryStats,
    ) {
        row.audioDriveFileId?.let { id ->
            trashBinary(
                driveFileId = id,
                label = "voice tombstone row=${row.id.take(8)}…",
                stats = stats,
                clearLocalId = { voiceNoteDao.markAudioSynced(row.id, "") },
            ) { driveClient.trash(id, accessToken) }
        }
    }

    /**
     * Pull voice-note audio binaries from Drive for any active row
     * whose `audio_drive_file_id` is set but whose local file is
     * missing. Mirror of the PDF / preview restore loop above.
     */
    suspend fun restorePendingVoiceNotes(userId: String, accessToken: String): QuickInkBinarySyncResult {
        val stats = MutableBinaryStats()
        val rows = voiceNoteDao.rowsWithRemoteAudio(userId)
        if (rows.isEmpty()) return stats.toResult()
        Log.i(TAG, "restorePendingVoiceNotes: scanning ${rows.size} rows")

        for (row in rows) {
            if (localFileExists(row.audioUri)) continue
            val driveId = row.audioDriveFileId ?: continue
            stats.attempted++
            val rowTag = "row=${row.id.take(8)}… audioDriveId=${driveId.take(12)}…"
            try {
                val bytes = driveClient.downloadBytes(driveId, accessToken)
                val newUri = writeBytes(bytes, "m4a")
                if (newUri != null) {
                    voiceNoteDao.setAudioUri(row.id, newUri.toString())
                    stats.completed++
                    Log.i(TAG, "restorePendingVoiceNotes.audio ok ($rowTag, ${bytes.size}B)")
                } else {
                    stats.failed++
                    Log.w(TAG, "restorePendingVoiceNotes.audio write-failed ($rowTag)")
                }
            } catch (e: DriveError.Unauthenticated) {
                Log.w(TAG, "restorePendingVoiceNotes.audio auth rejected ($rowTag) — rethrowing")
                throw e
            } catch (e: DriveError.RateLimited) {
                Log.w(TAG, "restorePendingVoiceNotes.audio rate limited ($rowTag) — rethrowing")
                throw e
            } catch (e: Exception) {
                stats.failed++
                Log.w(TAG, "restorePendingVoiceNotes.audio failed ($rowTag): $e")
            }
        }
        return stats.toResult()
    }

    private suspend fun uploadProfilePhotoIfNeeded(
        profileId: String,
        profile: app.quickink.mobile.data.profile.ProfileSettingsEntity,
        rootFolderId: String,
        accessToken: String,
        stats: MutableBinaryStats,
    ) {
        // Tombstone path: row was soft-deleted but still has a Drive
        // file. Trash it on Drive and clear the local id so the row
        // can be marked synced.
        if (profile.deletedAt != null) {
            profile.photoDriveFileId?.let { id ->
                trashBinary(
                    driveFileId = id,
                    label = "profilePhoto",
                    stats = stats,
                    clearLocalId = {
                        profileSettingsDao.markPhotoBinarySynced(profileId, null)
                    },
                ) { driveClient.trash(id, accessToken) }
            }
            return
        }

        // Live upload path: a new photo was picked locally but hasn't
        // hit Drive yet. Reads the file, uploads, stamps the Drive
        // file id back onto the row.
        if (profile.photoLocalUri == null) return
        if (profile.photoDriveFileId != null) return

        val bytes = readLocalBytes(profile.photoLocalUri)
        if (bytes == null) {
            Log.w(TAG, "binary.upload.profilePhoto skipped — local file missing")
            return
        }
        uploadBinary(
            label = "profilePhoto",
            stats = stats,
        ) {
            driveClient.uploadBinaryAtPath(
                data         = bytes,
                contentType  = "image/jpeg",
                relativePath = "profile_settings/${profileId}.jpg",
                rootFolderId = rootFolderId,
                accessToken  = accessToken,
            )
        }?.let { driveFile ->
            profileSettingsDao.markPhotoBinarySynced(profileId, driveFile.id)
        }
    }

    private suspend fun cascadeTombstone(
        row: CaptureEntity,
        accessToken: String,
        stats: MutableBinaryStats,
    ) {
        // 404 from Drive is fine (file already gone), but auth,
        // rate-limit, and transient failures must not clear the
        // local id. Keeping the id lets the next sync retry instead
        // of leaking an orphaned binary in Drive.
        row.pdfDriveFileId?.let { id ->
            trashBinary(
                driveFileId = id,
                label = "pdf tombstone row=${row.id.take(8)}…",
                stats = stats,
                clearLocalId = { captureDao.setPdfDriveFileId(row.id, null) },
            ) { driveClient.trash(id, accessToken) }
        }
        row.previewDriveFileId?.let { id ->
            trashBinary(
                driveFileId = id,
                label = "preview tombstone row=${row.id.take(8)}…",
                stats = stats,
                clearLocalId = { captureDao.setPreviewDriveFileId(row.id, null) },
            ) { driveClient.trash(id, accessToken) }
        }
    }

    private suspend fun uploadLive(
        row: CaptureEntity,
        rootFolderId: String,
        accessToken: String,
        stats: MutableBinaryStats,
    ) {
        val bucket = dateBucket(row.createdAt)

        if (row.pdfDriveFileId == null) {
            val bytes = readLocalBytes(row.pdfUri)
            if (bytes != null) {
                uploadBinary(
                    label = "pdf row=${row.id.take(8)}…",
                    stats = stats,
                ) {
                    driveClient.uploadBinaryAtPath(
                        data         = bytes,
                        contentType  = "application/pdf",
                        relativePath = "$bucket/${row.id}.pdf",
                        rootFolderId = rootFolderId,
                        accessToken  = accessToken,
                    )
                }?.let { driveFile ->
                    captureDao.setPdfDriveFileId(row.id, driveFile.id)
                }
            } else {
                Log.w(TAG, "binary.upload.pdf skipped — local file missing (row=${row.id.take(8)}…)")
            }
        }

        if (row.previewDriveFileId == null && !row.previewUri.isNullOrBlank()) {
            val bytes = readLocalBytes(row.previewUri)
            if (bytes != null) {
                uploadBinary(
                    label = "preview row=${row.id.take(8)}…",
                    stats = stats,
                ) {
                    driveClient.uploadBinaryAtPath(
                        data         = bytes,
                        contentType  = "image/jpeg",
                        relativePath = "$bucket/${row.id}.jpg",
                        rootFolderId = rootFolderId,
                        accessToken  = accessToken,
                    )
                }?.let { driveFile ->
                    captureDao.setPreviewDriveFileId(row.id, driveFile.id)
                }
            } else {
                Log.w(TAG, "binary.upload.preview skipped — local file missing (row=${row.id.take(8)}…)")
            }
        }
    }

    /**
     * Pull binaries from Drive for any active capture whose row has
     * a Drive id but no readable local file. Used after a fresh-
     * device restore: JSON metadata syncs first, then this fills in
     * the actual PDFs + previews.
     */
    suspend fun restorePending(userId: String, accessToken: String): QuickInkBinarySyncResult {
        val stats = MutableBinaryStats()
        val rows = captureDao.activeRows(userId)
        Log.i(TAG, "restorePending: scanning ${rows.size} active rows " +
            "for missing binaries (user=${userId.take(8)}…)")

        // Resolve the QuickInk root folder once per pass — every
        // find-by-path lookup below uses it. When no rows actually
        // need a fallback this is wasted work, but the call is
        // cheap (one Drive query) and skipping it would mean a
        // null check inside the loop. Scoped to a runCatching so
        // a transient Drive failure here doesn't kill the whole
        // pass — rows with a real `pdfDriveFileId` can still
        // download via the direct path even when find-by-path is
        // unavailable.
        val rootFolderId: String? = runCatching {
            driveClient.ensureRootFolder(driveRootFolderName, accessToken).id
        }.getOrElse {
            if (it.isBlockingDriveError()) throw it
            Log.w(TAG, "restorePending: ensureRootFolder failed ($it) — " +
                "find-by-path fallback will be skipped this pass")
            null
        }

        var pdfsAttempted   = 0
        var pdfsRecovered   = 0
        var pdfsRelinked    = 0
        var previewsAttempted   = 0
        var previewsRecovered   = 0
        var previewsRelinked    = 0
        var previewsRenderedFromPdf = 0
        for (row in rows) {
            // Per-row state. Updated in-place when a branch
            // successfully writes a new local file so the
            // "render preview from PDF" fallback below sees the
            // up-to-date state without re-querying the DB.
            var localPdfUri: Uri? = row.pdfUri.takeIf { localFileExists(it) }?.let(Uri::parse)
            var localPreviewExists = !row.previewUri.isNullOrBlank() && localFileExists(row.previewUri)

            // ── PDF ──────────────────────────────────────────────
            if (!localFileExists(row.pdfUri)) {
                val effectivePdfDriveId = row.pdfDriveFileId
                    ?: rootFolderId?.let { rootId ->
                        // Find-by-path fallback: the row's
                        // pdfDriveFileId can be null if the
                        // metadata uploaded but the binary id
                        // never got written back (failed mid-
                        // sync, restored from a manifest that
                        // pre-dates the link, etc.). Look for the
                        // canonical `<bucket>/<id>.pdf` filename
                        // under QuickInk; if it's there, link
                        // it on the local row so future restores
                        // and the upload-and-cascade pass don't
                        // re-do the work.
                        val bucket = dateBucket(row.createdAt)
                        val path = "$bucket/${row.id}.pdf"
                        val rowTag = "row=${row.id.take(8)}… path=$path"
                        try {
                            val found = driveClient.findFileAtPath(path, rootId, accessToken)
                            if (found != null) {
                                captureDao.setPdfDriveFileId(row.id, found.id)
                                pdfsRelinked++
                                Log.i(TAG, "restorePending.pdf relinked ($rowTag → driveId=${found.id.take(12)}…)")
                                found.id
                            } else {
                                Log.i(TAG, "restorePending.pdf no-match ($rowTag) — " +
                                    "row has no pdfDriveFileId and no file at canonical path")
                                null
                            }
                        } catch (e: DriveError.Unauthenticated) {
                            Log.w(TAG, "restorePending.pdf find-by-path auth rejected ($rowTag) — rethrowing")
                            throw e
                        } catch (e: DriveError.RateLimited) {
                            Log.w(TAG, "restorePending.pdf find-by-path rate limited ($rowTag) — rethrowing")
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "restorePending.pdf find-by-path failed ($rowTag): $e")
                            null
                        }
                    }

                if (effectivePdfDriveId != null) {
                    pdfsAttempted++
                    stats.attempted++
                    val rowTag = "row=${row.id.take(8)}… pdfDriveId=${effectivePdfDriveId.take(12)}…"
                    try {
                        val bytes = driveClient.downloadBytes(effectivePdfDriveId, accessToken)
                        val newUri = writeBytes(bytes, "pdf")
                        if (newUri != null) {
                            captureDao.setPdfUri(row.id, newUri.toString())
                            localPdfUri = newUri
                            pdfsRecovered++
                            stats.completed++
                            Log.i(TAG, "restorePending.pdf ok ($rowTag, ${bytes.size}B)")
                        } else {
                            // writeBytes failed — disk write threw and was
                            // swallowed inside the helper. Log it so we
                            // notice in case it's something fixable like
                            // a missing attachments directory.
                            Log.w(TAG, "restorePending.pdf write-failed ($rowTag)")
                            stats.failed++
                        }
                    } catch (e: DriveError.Unauthenticated) {
                        Log.w(TAG, "restorePending.pdf auth rejected ($rowTag) — " +
                            "rethrowing so caller can write AUTH_REJECTED")
                        throw e
                    } catch (e: DriveError.RateLimited) {
                        Log.w(TAG, "restorePending.pdf rate limited ($rowTag) — rethrowing")
                        throw e
                    } catch (e: Exception) {
                        stats.failed++
                        Log.w(TAG, "restorePending.pdf failed ($rowTag): $e")
                    }
                }
            }

            // ── Preview JPEG ─────────────────────────────────────
            if (row.previewUri.isNullOrBlank() || !localFileExists(row.previewUri)) {
                val effectivePreviewDriveId = row.previewDriveFileId
                    ?: rootFolderId?.let { rootId ->
                        val bucket = dateBucket(row.createdAt)
                        val path = "$bucket/${row.id}.jpg"
                        val rowTag = "row=${row.id.take(8)}… path=$path"
                        try {
                            val found = driveClient.findFileAtPath(path, rootId, accessToken)
                            if (found != null) {
                                captureDao.setPreviewDriveFileId(row.id, found.id)
                                previewsRelinked++
                                Log.i(TAG, "restorePending.preview relinked ($rowTag → driveId=${found.id.take(12)}…)")
                                found.id
                            } else {
                                // Quiet — many captures legitimately
                                // have no preview JPEG (some create
                                // paths only emit a PDF). No log
                                // here keeps the per-row noise down.
                                null
                            }
                        } catch (e: DriveError.Unauthenticated) {
                            Log.w(TAG, "restorePending.preview find-by-path auth rejected ($rowTag) — rethrowing")
                            throw e
                        } catch (e: DriveError.RateLimited) {
                            Log.w(TAG, "restorePending.preview find-by-path rate limited ($rowTag) — rethrowing")
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "restorePending.preview find-by-path failed ($rowTag): $e")
                            null
                        }
                    }

                if (effectivePreviewDriveId != null) {
                    previewsAttempted++
                    stats.attempted++
                    val rowTag = "row=${row.id.take(8)}… previewDriveId=${effectivePreviewDriveId.take(12)}…"
                    try {
                        val bytes = driveClient.downloadBytes(effectivePreviewDriveId, accessToken)
                        val newUri = writeBytes(bytes, "jpg")
                        if (newUri != null) {
                            captureDao.setPreviewUri(row.id, newUri.toString())
                            localPreviewExists = true
                            previewsRecovered++
                            stats.completed++
                            Log.i(TAG, "restorePending.preview ok ($rowTag, ${bytes.size}B)")
                        } else {
                            Log.w(TAG, "restorePending.preview write-failed ($rowTag)")
                            stats.failed++
                        }
                    } catch (e: DriveError.Unauthenticated) {
                        Log.w(TAG, "restorePending.preview auth rejected ($rowTag) — " +
                            "rethrowing so caller can write AUTH_REJECTED")
                        throw e
                    } catch (e: DriveError.RateLimited) {
                        Log.w(TAG, "restorePending.preview rate limited ($rowTag) — rethrowing")
                        throw e
                    } catch (e: Exception) {
                        stats.failed++
                        Log.w(TAG, "restorePending.preview failed ($rowTag): $e")
                    }
                }
            }

            // ── Render preview from local PDF (final fallback) ──
            // Home + library cards key off `previewUri`. When the
            // original sync only uploaded a PDF (no separate
            // `<id>.jpg`), find-by-path returns null for the
            // preview and the cards render the no-thumbnail
            // placeholder. Generate one locally from the freshly-
            // restored PDF so the cards have something to show.
            // Only runs when:
            //   - we still have no on-disk preview, AND
            //   - we have an on-disk PDF (either pre-existing or
            //     just restored above).
            // Preview that's rendered here stays a local artefact
            // — we don't write `previewDriveFileId`, so the next
            // upload pass can decide whether to push it to Drive.
            if (!localPreviewExists && localPdfUri != null) {
                val rowTag = "row=${row.id.take(8)}…"
                val jpegBytes = renderFirstPageAsJpeg(localPdfUri)
                if (jpegBytes != null) {
                    val newUri = writeBytes(jpegBytes, "jpg")
                    if (newUri != null) {
                        captureDao.setPreviewUri(row.id, newUri.toString())
                        previewsRenderedFromPdf++
                        Log.i(TAG, "restorePending.preview rendered-from-pdf " +
                            "($rowTag, ${jpegBytes.size}B)")
                    } else {
                        Log.w(TAG, "restorePending.preview render-from-pdf write-failed ($rowTag)")
                    }
                } else {
                    Log.w(TAG, "restorePending.preview render-from-pdf failed ($rowTag) — " +
                        "PdfRenderer threw or PDF has 0 pages")
                }
            }
        }

        // Profile photo restore — same shape as captures, single row.
        // If we have a metadata row with a Drive id but no readable
        // local file, pull the binary down and rewrite photo_local_uri.
        // Writes the binary to a stable filename inside the app's
        // attachments dir so future restores can detect the existing
        // local file via `localFileExists` and skip re-downloading.
        val profile = profileSettingsDao.findByUser(userId)
        if (profile != null
            && profile.deletedAt == null
            && !localFileExists(profile.photoLocalUri)
        ) {
            val effectivePhotoDriveId = profile.photoDriveFileId
                ?: rootFolderId?.let { rootId ->
                    val path = "profile_settings/${profile.id}.jpg"
                    try {
                        val found = driveClient.findFileAtPath(path, rootId, accessToken)
                        if (found != null) {
                            profileSettingsDao.markPhotoBinarySynced(profile.id, found.id)
                            Log.i(TAG, "restorePending.profilePhoto relinked (driveId=${found.id.take(12)}…)")
                            found.id
                        } else {
                            null
                        }
                    } catch (e: DriveError.Unauthenticated) {
                        Log.w(TAG, "restorePending.profilePhoto find-by-path auth rejected — rethrowing")
                        throw e
                    } catch (e: DriveError.RateLimited) {
                        Log.w(TAG, "restorePending.profilePhoto find-by-path rate limited — rethrowing")
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "restorePending.profilePhoto find-by-path failed: $e")
                        null
                    }
            }
            if (effectivePhotoDriveId != null) {
                stats.attempted++
                try {
                    val bytes = driveClient.downloadBytes(effectivePhotoDriveId, accessToken)
                    val newUri = writeBytes(bytes, "jpg")
                    if (newUri != null) {
                        // Use upsertLocal rather than a dedicated DAO
                        // method — this is a one-shot post-restore stamp,
                        // no need to add another suspend Query just for
                        // this path. Preserves dirty=false (we don't
                        // want to re-upload) and updates only the local
                        // URI.
                        profileSettingsDao.upsertLocal(profile.copy(photoLocalUri = newUri.toString()))
                        stats.completed++
                        Log.i(TAG, "restorePending.profilePhoto ok (${bytes.size}B)")
                    } else {
                        stats.failed++
                        Log.w(TAG, "restorePending.profilePhoto write-failed")
                    }
                } catch (e: DriveError.Unauthenticated) {
                    Log.w(TAG, "restorePending.profilePhoto auth rejected — rethrowing")
                    throw e
                } catch (e: DriveError.RateLimited) {
                    Log.w(TAG, "restorePending.profilePhoto rate limited — rethrowing")
                    throw e
                } catch (e: Exception) {
                    stats.failed++
                    Log.w(TAG, "restorePending.profilePhoto failed: $e")
                }
            }
        }

        Log.i(TAG, "restorePending: done — " +
            "pdfs $pdfsRecovered/$pdfsAttempted (relinked=$pdfsRelinked), " +
            "previews $previewsRecovered/$previewsAttempted (relinked=$previewsRelinked, " +
            "renderedFromPdf=$previewsRenderedFromPdf), " +
            "driveAttempts=${stats.completed}/${stats.attempted} failed=${stats.failed}")
        return stats.toResult()
    }

    // ─── Helpers ──────────────────────────────────────────────────

    private suspend fun uploadBinary(
        label: String,
        stats: MutableBinaryStats,
        block: suspend () -> DriveFile,
    ): DriveFile? {
        stats.attempted++
        return try {
            val driveFile = block()
            stats.completed++
            driveFile
        } catch (e: DriveError.Unauthenticated) {
            Log.w(TAG, "binary.upload.$label auth rejected — rethrowing")
            throw e
        } catch (e: DriveError.RateLimited) {
            Log.w(TAG, "binary.upload.$label rate limited — rethrowing")
            throw e
        } catch (e: Exception) {
            stats.failed++
            Log.w(TAG, "binary.upload.$label failed: $e")
            null
        }
    }

    private suspend fun trashBinary(
        driveFileId: String,
        label: String,
        stats: MutableBinaryStats,
        clearLocalId: suspend () -> Unit,
        block: suspend () -> Unit,
    ) {
        stats.attempted++
        try {
            block()
            clearLocalId()
            stats.completed++
        } catch (_: DriveError.NotFound) {
            clearLocalId()
            stats.completed++
            Log.i(TAG, "binary.trash.$label already gone (driveId=${driveFileId.take(12)}…)")
        } catch (e: DriveError.Unauthenticated) {
            Log.w(TAG, "binary.trash.$label auth rejected — rethrowing")
            throw e
        } catch (e: DriveError.RateLimited) {
            Log.w(TAG, "binary.trash.$label rate limited — rethrowing")
            throw e
        } catch (e: Exception) {
            stats.failed++
            Log.w(TAG, "binary.trash.$label failed: $e")
        }
    }

    private fun Throwable.isBlockingDriveError(): Boolean =
        this is DriveError.Unauthenticated || this is DriveError.RateLimited

    /**
     * `YYYY/MM/DD` bucket — same shape `DrivePath.quickInkCapture`
     * uses, so binaries land beside their JSON manifest.
     */
    private fun dateBucket(iso: String): String {
        if (iso.length < 10) return "0000/00/00"
        return "${iso.substring(0, 4)}/${iso.substring(5, 7)}/${iso.substring(8, 10)}"
    }

    private fun readLocalBytes(uri: String?): ByteArray? {
        if (uri.isNullOrBlank()) return null
        val parsed = runCatching { Uri.parse(uri) }.getOrNull() ?: return null
        return runCatching {
            context.contentResolver.openInputStream(parsed)?.use { it.readBytes() }
        }.getOrNull()
    }

    private fun localFileExists(uri: String?): Boolean {
        if (uri.isNullOrBlank()) return false
        val parsed = runCatching { Uri.parse(uri) }.getOrNull() ?: return false
        return when (parsed.scheme) {
            "file" -> parsed.path?.let { File(it).exists() } ?: false
            null   -> File(uri).exists()
            else   -> {
                // content:// URIs — best-effort check via openInputStream.
                runCatching {
                    context.contentResolver.openInputStream(parsed)?.use { true } ?: false
                }.getOrDefault(false)
            }
        }
    }

    /// Write bytes into the app's `AttachmentStorage` directory and
    /// return a `file://` URI to the new file. Returns null on IO
    /// failure.
    private fun writeBytes(bytes: ByteArray, ext: String): Uri? = runCatching {
        val dir = AttachmentStorage.directory(context)
        val file = File(dir, "${Uuidv7.generate()}.$ext")
        file.writeBytes(bytes)
        Uri.fromFile(file)
    }.getOrNull()

    /**
     * Render the first page of [pdfFileUri] to a JPEG byte array.
     * Used when a capture's preview JPEG isn't on Drive but its
     * PDF is — we generate a thumbnail locally so home + library
     * cards have something to display.
     *
     * Renders at 2× page density to stay sharp on typical phone
     * screens — same multiplier `renderPdfPages` (the on-screen
     * detail viewer) uses, so the thumbnail and the full view
     * don't diverge in clarity. Compresses at JPEG quality 85
     * (~the upload-side default; visually lossless for paper
     * scans). Returns null on PdfRenderer / IO failure or when
     * the PDF has zero pages.
     */
    private fun renderFirstPageAsJpeg(pdfFileUri: Uri): ByteArray? = runCatching {
        val pfd = context.contentResolver.openFileDescriptor(pdfFileUri, "r") ?: return@runCatching null
        pfd.use { fd ->
            PdfRenderer(fd).use { renderer ->
                if (renderer.pageCount == 0) return@runCatching null
                renderer.openPage(0).use { page ->
                    val w = page.width  * 2
                    val h = page.height * 2
                    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val out = ByteArrayOutputStream()
                    val ok = bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                    bitmap.recycle()
                    if (ok) out.toByteArray() else null
                }
            }
        }
    }.getOrNull()
}
