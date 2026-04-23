/*
 * NotesExport.kt
 *
 * Save a sub-page snapshot to the page's own Photos section as a JPG.
 *
 * The JPG lands in the app's private attachments directory (same
 * place `AttachmentStorage` puts scans + voice notes). A `file://`
 * URI to the written file is returned so the VM can attach it as a
 * `Attachment.TYPE_PHOTO` — at which point it shows up in the
 * PhotosSection alongside picker-sourced photos.
 *
 * Nothing is written to the device gallery / MediaStore here. This
 * is an *in-page* attachment, not a phone-wide export.
 */

package app.releaf.mobile.data.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Result of a sub-page → Photos-section export. */
sealed class ExportResult {
    /** Successful write; `uri` is the `file://` URI to pass into
     *  `Attachment.uri` when creating the TYPE_PHOTO row. */
    data class Saved(val uri: Uri) : ExportResult()
    /** IO / encode failure. `cause.message` is safe to surface in a toast. */
    data class Failed(val cause: Throwable) : ExportResult()
}

object NotesExport {

    /** JPG quality used when compressing the snapshot. 90% is the
     *  sweet spot for notes — strokes stay crisp and file size stays
     *  under ~500KB for a typical page. */
    private const val JPEG_QUALITY = 90

    /**
     * Compress `image` to a JPG inside the app's attachments
     * directory. The file is owned by us; cleanup on attachment
     * removal goes through `AttachmentStorage.deleteIfLocal`.
     *
     * Runs on IO — cheap enough that the caller can `await` from a
     * LaunchedEffect without blocking the frame.
     */
    suspend fun saveSubPageToPageAttachments(
        context: Context,
        image: ImageBitmap,
    ): ExportResult = withContext(Dispatchers.IO) {
        runCatching {
            // Flatten onto a white canvas first. The Box we capture has
            // a theme-aware `.background()` modifier that sits OUTSIDE
            // the recorded GraphicsLayer — so the layer buffer itself
            // is transparent everywhere the children didn't paint.
            // JPG has no alpha, so those pixels would compress to
            // BLACK without this step. Flattening onto white gives
            // the export a consistent "paper" look regardless of the
            // device's light/dark theme.
            //
            // `GraphicsLayer.toImageBitmap()` returns a HARDWARE-backed
            // bitmap on most devices — trying to draw that onto a
            // software Canvas throws IllegalStateException with
            // "Software rendering doesn't support hardware bitmaps".
            // Copy into ARGB_8888 first to get a software bitmap we
            // can composite into.
            val rawSource = image.asAndroidBitmap()
            val source = if (rawSource.config == Bitmap.Config.HARDWARE) {
                rawSource.copy(Bitmap.Config.ARGB_8888, /* isMutable = */ false)
                    ?: error("Could not copy hardware bitmap to ARGB_8888")
            } else {
                rawSource
            }
            val flattened = Bitmap.createBitmap(
                source.width,
                source.height,
                Bitmap.Config.ARGB_8888,
            )
            Canvas(flattened).apply {
                drawColor(Color.WHITE)
                drawBitmap(source, 0f, 0f, Paint())
            }

            val dir = AttachmentStorage.directory(context)
            val file = File(dir, "${Uuidv7.generate()}.jpg")
            file.outputStream().use { stream ->
                flattened.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
            }
            ExportResult.Saved(uri = Uri.fromFile(file))
        }.getOrElse { ExportResult.Failed(it) }
    }
}
