/*
 * SystemCameraCapture.kt
 *
 * Helpers for the footer Sundial Photo / Video rays when they launch
 * the platform camera app instead of QuickInk's in-app CameraX
 * surfaces. The returned media is promoted into the same capture
 * pipeline as the existing photo/video surfaces:
 *
 *   Photo -> JPEG/PDF artifacts, source="photo", paperSize=Custom.
 *   Video -> first-frame JPEG/PDF artifacts, source="video",
 *            + raw .mp4 pinned on the capture row's video_uri.
 */

package app.quickink.mobile.features.scan

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.content.FileProvider
import app.quickink.mobile.data.capture.CaptureRepository
import app.releaf.mobile.data.common.AttachmentStorage
import app.releaf.mobile.data.common.Uuidv7
import app.releaf.shared.scan.DocumentScanResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

data class SystemCameraOutput(
    val file: File,
    val contentUri: Uri,
)

fun createSystemCameraOutput(
    context: Context,
    extension: String,
): SystemCameraOutput {
    val cleanExtension = extension.trim().trimStart('.').ifBlank { "tmp" }
    val cameraDir = File(context.cacheDir, "camera").apply { mkdirs() }
    val file = File(cameraDir, "capture_${System.currentTimeMillis()}.$cleanExtension")
    val authority = "${context.packageName}.fileprovider"
    return SystemCameraOutput(
        file       = file,
        contentUri = FileProvider.getUriForFile(context, authority, file),
    )
}

suspend fun commitSystemCameraPhotoCapture(
    context: Context,
    controller: ScanFlowController,
    photoFile: File,
): Boolean {
    val result = try {
        withContext(Dispatchers.IO) {
            buildImportArtifacts(context, listOf(Uri.fromFile(photoFile)))
        }
    } finally {
        photoFile.delete()
    } ?: return false

    withContext(Dispatchers.Main) {
        controller.onScanComplete(
            result    = result,
            source    = "photo",
            paperSize = PaperSize.Custom,
        )
    }
    return true
}

suspend fun commitSystemCameraVideoCapture(
    context: Context,
    controller: ScanFlowController,
    captureRepository: CaptureRepository,
    videoFile: File,
): Boolean {
    val artifact = try {
        withContext(Dispatchers.IO) {
            buildSystemCameraVideoArtifact(context, videoFile)
        }
    } finally {
        videoFile.delete()
    } ?: return false

    withContext(Dispatchers.Main) {
        controller.onScanComplete(
            result    = artifact.scanResult,
            source    = "video",
            paperSize = PaperSize.Custom,
        )
    }

    val captureId = withTimeoutOrNull(8_000L) {
        controller.state
            .filterIsInstance<ScanFlowController.State.Recognizing>()
            .first()
            .captureId
    }

    if (captureId != null) {
        withContext(Dispatchers.IO) {
            captureRepository.setVideoUri(captureId, artifact.videoUri.toString())
        }
    }
    return true
}

private data class SystemCameraVideoArtifact(
    val scanResult: DocumentScanResult,
    val videoUri: Uri,
)

private fun buildSystemCameraVideoArtifact(
    context: Context,
    videoFile: File,
): SystemCameraVideoArtifact? {
    if (!videoFile.exists() || videoFile.length() <= 0L) return null

    val storedVideo = File(AttachmentStorage.directory(context), "${Uuidv7.generate()}.mp4")
    return try {
        videoFile.copyTo(storedVideo, overwrite = true)
        val firstFrameFile = extractVideoFirstFrame(context, videoFile) ?: run {
            storedVideo.delete()
            return null
        }
        try {
            val result = buildImportArtifacts(context, listOf(Uri.fromFile(firstFrameFile)))
                ?: run {
                    storedVideo.delete()
                    return null
                }
            SystemCameraVideoArtifact(
                scanResult = result,
                videoUri   = Uri.fromFile(storedVideo),
            )
        } finally {
            firstFrameFile.delete()
        }
    } catch (_: Exception) {
        storedVideo.delete()
        null
    }
}

private fun extractVideoFirstFrame(context: Context, videoFile: File): File? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(videoFile.absolutePath)
        val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            ?: retriever.getFrameAtTime()
            ?: return null
        val outDir = File(context.cacheDir, "system-camera-frames").apply { mkdirs() }
        val outFile = File(outDir, "frame_${System.currentTimeMillis()}.jpg")
        outFile.outputStream().use { out ->
            frame.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        frame.recycle()
        outFile
    } catch (_: Exception) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}
