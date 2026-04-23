/*
 * PhotosToPdf.kt
 *
 * Combine a list of photo URIs into a single PDF, one photo per page,
 * and write it into the app's attachments dir. Consumer is the Photos
 * section's "Combine to PDF" multi-select flow — the resulting
 * `file://` URI is handed to `viewModel.addAttachment(TYPE_SCAN, …)`
 * so the PDF shows up alongside real document scans, classified under
 * the catch-all GENERAL category (no OCR text → default category).
 *
 * Uses `android.graphics.pdf.PdfDocument` (AOSP, no extra dep).
 * Bitmaps are decoded one at a time and recycled after drawing so a
 * 20-photo pile doesn't balloon memory.
 */

package app.releaf.mobile.data.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Result of a combine-to-PDF pass. Callers surface the message in a toast. */
sealed class CombineToPdfResult {
    data class Success(val pdfUri: Uri, val previewUri: Uri?) : CombineToPdfResult()
    data class Failed(val cause: Throwable) : CombineToPdfResult()
}

object PhotosToPdf {

    /** Max width/height for a decoded photo page. Most phones take
     *  8–12MP images (4000+ px wide). PDFs with full-res pages are
     *  slow to render on-device and bloat file size for no visual
     *  benefit — 2000px long-edge reads as sharp at every zoom level
     *  a reader uses. */
    private const val MAX_EDGE_PX = 2000

    /**
     * Render [photoUris] into a single PDF saved in
     * `AttachmentStorage.directory(context)`. Returns the PDF's URI
     * on success + the first photo's URI as a preview (so the scan
     * tile has a thumbnail). Non-blocking — callers await from a
     * coroutine scope.
     */
    suspend fun combine(
        context: Context,
        photoUris: List<Uri>,
    ): CombineToPdfResult = withContext(Dispatchers.IO) {
        if (photoUris.isEmpty()) {
            return@withContext CombineToPdfResult.Failed(
                IllegalArgumentException("No photos selected"),
            )
        }
        runCatching {
            val document = PdfDocument()
            try {
                var pagesWritten = 0
                photoUris.forEachIndexed { index, uri ->
                    val bitmap = decodeDownscaled(context, uri) ?: return@forEachIndexed
                    val pageInfo = PdfDocument.PageInfo
                        .Builder(bitmap.width, bitmap.height, pagesWritten + 1)
                        .create()
                    val page = document.startPage(pageInfo)
                    page.canvas.drawBitmap(bitmap, 0f, 0f, null)
                    document.finishPage(page)
                    bitmap.recycle()
                    pagesWritten++
                }
                if (pagesWritten == 0) {
                    // Every decode failed — an empty PDF crashes
                    // PdfRenderer later. Surface the failure now so
                    // the toast can tell the user why.
                    error("Couldn't decode any of the selected photos")
                }
                val outFile = File(
                    AttachmentStorage.directory(context),
                    "${Uuidv7.generate()}.pdf",
                )
                outFile.outputStream().use { document.writeTo(it) }
                CombineToPdfResult.Success(
                    pdfUri     = Uri.fromFile(outFile),
                    previewUri = photoUris.firstOrNull(),
                )
            } finally {
                document.close()
            }
        }.getOrElse { CombineToPdfResult.Failed(it) }
    }

    /**
     * Decode a photo URI into a bitmap, downscaled so the long edge
     * is at most [MAX_EDGE_PX]. Two-pass decode: first `inJustDecodeBounds`
     * to read dimensions (into the Options object — this pass returns
     * null by contract, that's not a failure signal); then a second
     * pass with an inSampleSize that gets us under the cap.
     */
    private fun decodeDownscaled(context: Context, uri: Uri): Bitmap? = runCatching {
        // Bounds pass. `decodeStream` with `inJustDecodeBounds = true`
        // always returns null but populates `outWidth` / `outHeight` on
        // the Options as a side-effect — that side-effect is the whole
        // point. We intentionally ignore the return value.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val openedBounds = openInputStream(context, uri)
        if (openedBounds == null) return@runCatching null
        openedBounds.use { BitmapFactory.decodeStream(it, null, bounds) }

        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return@runCatching null
        var sample = 1
        val longEdge = maxOf(w, h)
        while ((longEdge / sample) > MAX_EDGE_PX) sample *= 2

        val decoded = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        openInputStream(context, uri)?.use { BitmapFactory.decodeStream(it, null, decoded) }
    }.getOrNull()

    private fun openInputStream(context: Context, uri: Uri): java.io.InputStream? =
        runCatching {
            when (uri.scheme) {
                "content" -> context.contentResolver.openInputStream(uri)
                "file"    -> File(uri.path ?: return@runCatching null).inputStream()
                else      -> null
            }
        }.getOrNull()
}
