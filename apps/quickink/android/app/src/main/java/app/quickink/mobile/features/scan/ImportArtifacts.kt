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
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import android.net.Uri
import app.releaf.mobile.data.common.AttachmentStorage
import app.releaf.mobile.data.common.Uuidv7
import app.releaf.shared.scan.DocumentScanResult
import java.io.File

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
