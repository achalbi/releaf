/*
 * ImportArtifacts.kt
 *
 * Bridge between the system photo picker (PickMultipleVisualMedia)
 * and `ScanFlowController`. Takes the URIs the user picked from the
 * gallery, copies each as a JPEG into AttachmentStorage, renders a
 * single multi-page PDF wrapping every page, and returns the
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
 * res bitmaps in heap simultaneously. The PdfDocument accumulates
 * pages cheaply (it holds the page rendering as compressed JPEG).
 *
 * Mirror of iOS `ImportArtifacts.swift`.
 */

package app.quickink.mobile.features.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import app.releaf.mobile.data.common.AttachmentStorage
import app.releaf.mobile.data.common.Uuidv7
import app.releaf.shared.scan.DocumentScanResult
import java.io.File

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

fun buildImportArtifacts(context: Context, sourceUris: List<Uri>): DocumentScanResult? {
    if (sourceUris.isEmpty()) return null
    val resolver = context.contentResolver

    // Pass 1 — copy every picked photo into AttachmentStorage as a
    // JPEG. We do this first (separately from PDF rendering) so the
    // page-uris list is fully formed before we touch the PDF
    // builder. If a single copy fails we abort the whole import
    // rather than silently dropping pages, since a partial result
    // would silently misorder a multi-page document.
    val jpegUris = sourceUris.map { src ->
        AttachmentStorage.copyIntoStorage(context, src, "jpg") ?: return null
    }

    // Pass 2 — render each picked photo into a PdfDocument page.
    // Each iteration: decode → drawBitmap → finishPage → recycle,
    // so peak heap is one bitmap, not N. If decoding any page
    // fails (corrupt image, unsupported format) we degrade to a
    // PDF-less result rather than aborting — the JPEGs are still
    // useful for OCR and library preview. The else-branch below is
    // hit when the very first page fails to decode and we never
    // open a PDF context at all.
    val pdfUri: Uri? = try {
        val doc = PdfDocument()
        var pageNumber = 1
        for ((index, src) in sourceUris.withIndex()) {
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
            // Every picked photo failed to decode — close the empty
            // doc and return null PDF so the JPEG list is still
            // surfaced to the user.
            doc.close()
            null
        } else {
            val dest = File(AttachmentStorage.directory(context), "${Uuidv7.generate()}.pdf")
            dest.outputStream().use { out -> doc.writeTo(out) }
            doc.close()
            Uri.fromFile(dest)
        }
    } catch (_: Exception) {
        null
    }

    return DocumentScanResult(
        pdfUri     = pdfUri,
        previewUri = jpegUris.first(),
        pageUris   = jpegUris,
    )
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
