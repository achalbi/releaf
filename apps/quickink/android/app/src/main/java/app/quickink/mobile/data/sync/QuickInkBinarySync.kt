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
import android.net.Uri
import app.quickink.mobile.data.capture.CaptureDao
import app.quickink.mobile.data.capture.CaptureEntity
import app.releaf.mobile.data.common.AttachmentStorage
import app.releaf.mobile.data.common.Uuidv7
import app.releaf.mobile.data.drive.DriveClient
import app.releaf.mobile.data.drive.DriveError
import app.releaf.mobile.data.drive.uploadBinaryAtPath
import java.io.File

class QuickInkBinarySync(
    private val context: Context,
    private val captureDao: CaptureDao,
    private val driveClient: DriveClient,
    private val driveRootFolderName: String = "Thoughtbasics/QuickInk",
) {

    /**
     * Run one upload-and-cascade pass for the given user. Idempotent:
     * captures already mirrored to Drive are skipped; tombstoned
     * rows whose Drive ids are already nulled are skipped.
     */
    suspend fun uploadAndCascade(userId: String, accessToken: String) {
        val pending = captureDao.pendingBinaryRows(userId)
        if (pending.isEmpty()) return

        val root = driveClient.ensureRootFolder(driveRootFolderName, accessToken)

        for (row in pending) {
            if (row.deletedAt != null) {
                cascadeTombstone(row, accessToken)
            } else {
                uploadLive(row, root.id, accessToken)
            }
        }
    }

    private suspend fun cascadeTombstone(row: CaptureEntity, accessToken: String) {
        // Best-effort — a 404 from Drive is fine (file already gone).
        // Always null the local id so we don't keep retrying.
        row.pdfDriveFileId?.let { id ->
            runCatching { driveClient.trash(id, accessToken) }
            captureDao.setPdfDriveFileId(row.id, null)
        }
        row.previewDriveFileId?.let { id ->
            runCatching { driveClient.trash(id, accessToken) }
            captureDao.setPreviewDriveFileId(row.id, null)
        }
    }

    private suspend fun uploadLive(row: CaptureEntity, rootFolderId: String, accessToken: String) {
        val bucket = dateBucket(row.createdAt)

        if (row.pdfDriveFileId == null) {
            val bytes = readLocalBytes(row.pdfUri)
            if (bytes != null) {
                runCatching {
                    driveClient.uploadBinaryAtPath(
                        data         = bytes,
                        contentType  = "application/pdf",
                        relativePath = "$bucket/${row.id}.pdf",
                        rootFolderId = rootFolderId,
                        accessToken  = accessToken,
                    )
                }.getOrNull()?.let { driveFile ->
                    captureDao.setPdfDriveFileId(row.id, driveFile.id)
                }
            }
        }

        if (row.previewDriveFileId == null && !row.previewUri.isNullOrBlank()) {
            val bytes = readLocalBytes(row.previewUri)
            if (bytes != null) {
                runCatching {
                    driveClient.uploadBinaryAtPath(
                        data         = bytes,
                        contentType  = "image/jpeg",
                        relativePath = "$bucket/${row.id}.jpg",
                        rootFolderId = rootFolderId,
                        accessToken  = accessToken,
                    )
                }.getOrNull()?.let { driveFile ->
                    captureDao.setPreviewDriveFileId(row.id, driveFile.id)
                }
            }
        }
    }

    /**
     * Pull binaries from Drive for any active capture whose row has
     * a Drive id but no readable local file. Used after a fresh-
     * device restore: JSON metadata syncs first, then this fills in
     * the actual PDFs + previews.
     */
    suspend fun restorePending(userId: String, accessToken: String) {
        val rows = captureDao.activeRows(userId)
        if (rows.isEmpty()) return

        for (row in rows) {
            if (row.pdfDriveFileId != null && !localFileExists(row.pdfUri)) {
                runCatching {
                    driveClient.downloadBytes(row.pdfDriveFileId, accessToken)
                }.getOrNull()?.let { bytes ->
                    val newUri = writeBytes(bytes, "pdf")
                    if (newUri != null) {
                        captureDao.setPdfUri(row.id, newUri.toString())
                    }
                }
            }
            if (row.previewDriveFileId != null && !localFileExists(row.previewUri)) {
                runCatching {
                    driveClient.downloadBytes(row.previewDriveFileId, accessToken)
                }.getOrNull()?.let { bytes ->
                    val newUri = writeBytes(bytes, "jpg")
                    if (newUri != null) {
                        captureDao.setPreviewUri(row.id, newUri.toString())
                    }
                }
            }
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────

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
}
