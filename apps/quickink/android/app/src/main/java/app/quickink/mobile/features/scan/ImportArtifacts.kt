/*
 * ImportArtifacts.kt
 *
 * Bridge between the system photo picker (PickMultipleVisualMedia)
 * and `ScanFlowController`. Takes the URIs the user picked from the
 * gallery, writes each as a bounded JPEG into AttachmentStorage,
 * renders a single multi-page PDF wrapping every page, and returns the
 * `DocumentScanResult` shape `controller.onScanComplete` already
 * expects (pdfUri / previewUri / pageUris).
 *
 * Also handles the share-target path — when the user picks QuickInk
 * from the system share sheet for an image or a PDF, MainActivity
 * stashes the URIs in a [PendingShare] and MainShell hands them to
 * either `buildImportArtifacts` (images) or `buildPdfImportArtifact`
 * (PDFs). Both produce the same `DocumentScanResult` so downstream
 * code (capture row, OCR, Library card) doesn't care which surface
 * the import came from — only `source = "import"` distinguishes
 * imports from camera scans on the row.
 *
 * Why one PDF, not N: every other capture path in the app stores a
 * multi-page PDF as the canonical "document", with per-page JPEGs as
 * previews / OCR inputs. The Library, the detail screen, and the
 * Drive sync all key off the PDF row, so imports without a PDF
 * would diverge — they'd render as previews with a missing detail
 * view. Imports flow through the same downstream code as a scan
 * regardless of page count.
 *
 * Memory: each bitmap is decoded → drawn → recycled before the
 * next iteration, so picking 30 large photos doesn't pin 30 full-
 * res bitmaps in heap simultaneously. The compressed PDF writer
 * also encodes one downscaled JPEG-backed page at a time before
 * assembling the final PDF bytes.
 *
 * Mirror of iOS `ImportArtifacts.swift`.
 */

package app.quickink.mobile.features.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.net.Uri
import android.os.ParcelFileDescriptor
import app.quickink.mobile.features.settings.SettingsPreferences
import app.releaf.mobile.data.common.AttachmentStorage
import app.releaf.mobile.data.common.Uuidv7
import app.releaf.shared.scan.CompressedImagePdfWriter
import app.releaf.shared.scan.DocumentScanResult
import java.io.File
import kotlin.math.roundToInt

/**
 * URIs handed off from a system share intent (ACTION_SEND /
 * ACTION_SEND_MULTIPLE) to the Compose tree. MainActivity builds
 * this from the incoming [android.content.Intent] and exposes it as
 * a Compose state read by `QuickInkRoot` → `MainShell`. Once
 * `MainShell` consumes it and hands the artifacts to
 * `ScanFlowController.onScanComplete`, the activity clears the
 * field via `onPendingShareConsumed` so a config change or
 * re-composition doesn't re-import.
 *
 * `isPdf` discriminates which builder to invoke:
 *  - false → `buildImportArtifacts(context, uris)` — multi-image
 *    pack-into-one-PDF path
 *  - true  → `buildPdfImportArtifact(context, uris.first())` —
 *    copy-the-PDF + render-pages-as-JPEGs path
 *
 * Multiple PDFs in a single share aren't supported (no
 * SEND_MULTIPLE/application-pdf intent filter); the list is sized
 * for symmetry with the image path.
 */
data class PendingShare(
    val uris: List<Uri>,
    val isPdf: Boolean,
)

private const val IMPORT_PAGE_MAX_LONG_EDGE_PX: Int = 1800
private const val IMPORT_PAGE_JPEG_QUALITY: Int = 82

fun buildImportArtifacts(
    context: Context,
    sourceUris: List<Uri>,
    compressedPdfEnabled: Boolean = SettingsPreferences(context).compressedPdfSavesEnabled,
): DocumentScanResult? {
    if (sourceUris.isEmpty()) return null

    // Pass 1 — write every picked photo into AttachmentStorage as a
    // bounded page JPEG. System camera / gallery images can arrive at
    // full sensor resolution, so copying bytes directly can leave
    // 10-25MB page artifacts in the capture row. Re-encoding here
    // keeps preview, OCR input, sync, and share payloads within the
    // same compact budget as the compressed PDF path.
    val jpegUris = sourceUris.map { src ->
        writeImportPageJpeg(context, src) ?: return null
    }

    // Pass 2 — render the picked photos into a PDF. Some platform
    // encoders already produce surprisingly compact PDFs, so when
    // compression is enabled we keep the smaller of the optimized and
    // raw artifacts instead of blindly returning the re-encoded copy.
    // If PDF rendering fails, degrade to a JPEG-only result rather
    // than aborting.
    val pdfUri: Uri? =
        if (compressedPdfEnabled) {
            val compressed = CompressedImagePdfWriter.writeToAttachment(context, jpegUris)
            val raw = buildRawImagePdf(context, jpegUris)
            chooseSmallerPdfAttachment(compressed, raw)
        } else {
            buildRawImagePdf(context, jpegUris)
        }

    return DocumentScanResult(
        pdfUri     = pdfUri,
        previewUri = jpegUris.first(),
        pageUris   = jpegUris,
    )
}

private fun writeImportPageJpeg(context: Context, sourceUri: Uri): Uri? {
    val bitmap = decodeImportPageBitmap(context, sourceUri, IMPORT_PAGE_MAX_LONG_EDGE_PX)
        ?: return AttachmentStorage.copyIntoStorage(context, sourceUri, "jpg")

    var dest: File? = null
    return try {
        val file = File(AttachmentStorage.directory(context), "${Uuidv7.generate()}.jpg")
        dest = file
        val wrote = file.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, IMPORT_PAGE_JPEG_QUALITY, out)
        }
        if (wrote) {
            Uri.fromFile(file)
        } else {
            file.delete()
            null
        }
    } catch (_: Exception) {
        dest?.delete()
        null
    } finally {
        bitmap.recycle()
    }
}

private fun decodeImportPageBitmap(
    context: Context,
    sourceUri: Uri,
    maxLongEdge: Int,
): Bitmap? {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(sourceUri)?.use { input ->
        BitmapFactory.decodeStream(input, null, bounds)
    }

    val srcW = bounds.outWidth
    val srcH = bounds.outHeight
    if (srcW <= 0 || srcH <= 0) return null

    val decoded = resolver.openInputStream(sourceUri)?.use { input ->
        BitmapFactory.decodeStream(
            input,
            null,
            BitmapFactory.Options().apply {
                inSampleSize = importPageSampleSize(srcW, srcH, maxLongEdge)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        )
    } ?: return null

    val orientation = readExifOrientation(context, sourceUri)
    val oriented = applyExifOrientation(decoded, orientation)
    if (oriented !== decoded) decoded.recycle()

    val opaque = makeOpaque(oriented)
    if (opaque !== oriented) oriented.recycle()

    val scaled = scaleToLongEdge(opaque, maxLongEdge)
    if (scaled !== opaque) opaque.recycle()
    return scaled
}

private fun importPageSampleSize(width: Int, height: Int, maxLongEdge: Int): Int {
    var sampleSize = 1
    val longest = maxOf(width, height)
    while (longest / (sampleSize * 2) >= maxLongEdge) {
        sampleSize *= 2
    }
    return sampleSize
}

private fun readExifOrientation(context: Context, sourceUri: Uri): Int =
    runCatching {
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            ExifInterface(input).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        } ?: ExifInterface.ORIENTATION_NORMAL
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
            matrix.setRotate(180f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.setRotate(90f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.setRotate(-90f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
        else -> return bitmap
    }

    return runCatching {
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }.getOrElse { bitmap }
}

private fun makeOpaque(bitmap: Bitmap): Bitmap {
    if (!bitmap.hasAlpha()) return bitmap
    val opaque = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(opaque)
    canvas.drawColor(Color.WHITE)
    canvas.drawBitmap(bitmap, 0f, 0f, null)
    return opaque
}

private fun scaleToLongEdge(bitmap: Bitmap, maxLongEdge: Int): Bitmap {
    val longest = maxOf(bitmap.width, bitmap.height)
    if (longest <= maxLongEdge) return bitmap
    val scale = maxLongEdge.toFloat() / longest
    val targetW = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
    val targetH = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
}

private fun buildRawImagePdf(context: Context, sourceUris: List<Uri>): Uri? {
    val resolver = context.contentResolver
    val doc = PdfDocument()
    return try {
        var pageNumber = 1
        for (src in sourceUris) {
            val bitmap = resolver.openInputStream(src)?.use { input ->
                BitmapFactory.decodeStream(input)
            } ?: continue
            val pageInfo = PdfDocument.PageInfo
                .Builder(bitmap.width, bitmap.height, pageNumber)
                .create()
            val page = doc.startPage(pageInfo)
            page.canvas.drawBitmap(bitmap, 0f, 0f, null)
            doc.finishPage(page)
            bitmap.recycle()
            pageNumber += 1
        }

        if (pageNumber == 1) {
            null
        } else {
            val dest = File(AttachmentStorage.directory(context), "${Uuidv7.generate()}.pdf")
            dest.outputStream().use { out -> doc.writeTo(out) }
            Uri.fromFile(dest)
        }
    } catch (_: Exception) {
        null
    } finally {
        doc.close()
    }
}

private fun chooseSmallerPdfAttachment(compressed: Uri?, raw: Uri?): Uri? {
    if (compressed == null) return raw
    if (raw == null) return compressed

    val compressedSize = localFileSize(compressed)
    val rawSize = localFileSize(raw)
    val keepCompressed = compressedSize == null ||
        rawSize == null ||
        compressedSize <= rawSize

    val discarded = if (keepCompressed) raw else compressed
    AttachmentStorage.deleteIfLocal(discarded.toString())

    return if (keepCompressed) compressed else raw
}

private fun localFileSize(uri: Uri): Long? {
    if (uri.scheme != "file") return null
    val path = uri.path ?: return null
    return File(path).takeIf { it.exists() }?.length()
}

/**
 * Share-target PDF path. Copies the source PDF into
 * AttachmentStorage as the canonical document, then rasterises each
 * page to a JPEG (also in AttachmentStorage) so the existing OCR
 * pipeline — which is JPEG-only via ML Kit's `InputImage.fromFilePath`
 * — has something to feed pages to. Returns a `DocumentScanResult`
 * shaped exactly like the camera scanner / photo-picker paths.
 *
 * Render fidelity: 2× the PdfRenderer's reported page width/height,
 * matching `renderPdfPages` (the on-screen viewer). Pulling the
 * full-res rasterisation into JPEG at quality 90 keeps the per-page
 * file in the few-hundred-KB range for typical letter-size pages
 * while giving ML Kit enough resolution to read body text reliably.
 *
 * Memory: render → compress → recycle one page at a time so a
 * 30-page PDF doesn't pin 30 ARGB bitmaps in heap simultaneously.
 *
 * Failure modes:
 *  - Initial copy fails (corrupt source, no read perm) → return null,
 *    caller surfaces nothing (matches the photo-picker path).
 *  - PdfRenderer throws on a page (encrypted PDF, malformed object) →
 *    we catch, return null. The orphaned PDF copy is an acceptable
 *    leak, same as the half-written PdfDocument case in the image
 *    path above.
 */
fun buildPdfImportArtifact(context: Context, sourceUri: Uri): DocumentScanResult? {
    // 1. Copy the source PDF into AttachmentStorage so the capture
    //    row points at a stable file:// URI we own. ContentResolver
    //    URIs from a share intent only stay valid for the life of
    //    the foreground task — Drive sync, the detail screen, and
    //    the OCR pass all read from disk later.
    val pdfUri = AttachmentStorage.copyIntoStorage(context, sourceUri, "pdf") ?: return null

    val pageUris = try {
        renderPdfToJpegPages(context, pdfUri)
    } catch (_: Exception) {
        return null
    }
    if (pageUris.isEmpty()) return null

    return DocumentScanResult(
        pdfUri     = pdfUri,
        previewUri = pageUris.first(),
        pageUris   = pageUris,
    )
}

/**
 * Walk every page of [pdfUri] with PdfRenderer, render to a
 * white-backed ARGB bitmap at 2×, compress to JPEG, recycle, and
 * return the on-disk URIs in page order. The white erase before
 * `page.render(...)` matches the on-screen viewer (`PdfPagesView`)
 * — without it, transparent regions in the PDF render as black,
 * which both looks broken and hurts OCR on transparent-bg PDFs.
 */
private fun renderPdfToJpegPages(context: Context, pdfUri: Uri): List<Uri> {
    val pfd: ParcelFileDescriptor = context.contentResolver
        .openFileDescriptor(pdfUri, "r")
        ?: return emptyList()

    val results = mutableListOf<Uri>()
    PdfRenderer(pfd).use { renderer ->
        for (i in 0 until renderer.pageCount) {
            renderer.openPage(i).use { page ->
                val w = page.width  * 2
                val h = page.height * 2
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                val dest = File(AttachmentStorage.directory(context), "${Uuidv7.generate()}.jpg")
                dest.outputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }
                bitmap.recycle()
                results.add(Uri.fromFile(dest))
            }
        }
    }
    pfd.close()
    return results
}
