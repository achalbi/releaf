/*
 * SearchablePdfExporter.kt
 *
 * Builds a multi-page PDF from `[PageContent]` (per-page image +
 * `OcrResult`) with an invisible text layer overlaid on top of each
 * image. Search and copy/paste in any conforming PDF reader pull
 * text from the overlay; the page renders visually as the source
 * image alone.
 *
 * Per QUICKINK_PROPOSAL.md §6.3, this is the prototype path behind
 * the `searchablePdfExportEnabled` feature flag — the v1 default
 * export uses ML Kit's built-in `RESULT_FORMAT_PDF` from the
 * document scanner. The flag wiring + the Settings → "Experimental"
 * toggle land with the QuickInk MVP; this file ships the exporter
 * contract + impl so the toggle has something to invoke when it's
 * flipped on.
 *
 * Mirror of `SearchablePdfExporter.swift` in `ReleafCoreScan`.
 *
 * Implementation:
 *
 *   - `android.graphics.pdf.PdfDocument` for the page rendering.
 *     Page size is the image's pixel dimensions interpreted as PDF
 *     points (matches the convention iOS uses; both platforms emit
 *     PDFs with arbitrary per-page sizes, which every conforming
 *     reader handles).
 *
 *   - Invisible text via `Paint.color = Color.TRANSPARENT`. Stock
 *     Android `PdfDocument` doesn't expose PDF rendering modes
 *     directly; we rely on the text being added to the page's
 *     content stream regardless of paint alpha. PDF text-extraction
 *     libraries (and Spotlight / Android system search) read text
 *     from the content stream — alpha is a render-time concern.
 *
 *     Caveat: some PDF readers optimize out fully transparent text
 *     in their text-extraction path. If that turns out to be a
 *     real problem in dev-build smoke tests, the path forward is
 *     swapping to PDFBox-Android (Apache 2.0; ~5MB AAR) and using
 *     proper rendering-mode-3 invisible text. Out of scope for the
 *     prototype; a feature-flagged dev-only feature is fine with
 *     the simpler approach.
 *
 *   - Granularity: line-level only (`OcrBlock.Kind.Line`).
 *     Paragraph-level blocks would lump multiple lines into one
 *     hit; word-level (which neither engine produces today anyway)
 *     would lose word-spacing context for search-and-copy.
 *
 *   - Coordinate translation: OCR's normalized 0..1 top-left
 *     bboxes → PDF points with Y staying top-left (Android Canvas
 *     and `PdfDocument` both use top-left origin, unlike Core
 *     Graphics on iOS — saves us a Y-flip the iOS impl needs).
 */

package app.releaf.shared.scan

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import java.io.File
import java.io.IOException

class SearchablePdfExporter(private val context: Context) {

    /**
     * Builds a PDF from `pages` and writes it to `output`. Each
     * element of `pages` becomes one PDF page with the image
     * rendered visually and the OCR text overlaid invisibly.
     *
     * Throws [ExportException.ImageUnreadable] when a page's image
     * Uri can't be decoded, or [ExportException.PdfWriteFailed]
     * when the file write itself fails (disk full, permission
     * denied, etc.). Empty `pages` is a precondition error — most
     * PDF readers reject 0-page files; fail early instead.
     */
    fun export(pages: List<PageContent>, output: File) {
        require(pages.isNotEmpty()) { "pages must be non-empty" }

        val document = PdfDocument()
        try {
            for ((index, page) in pages.withIndex()) {
                drawPage(document, pageNumber = index + 1, page = page)
            }

            try {
                output.outputStream().use { stream ->
                    document.writeTo(stream)
                }
            } catch (e: IOException) {
                throw ExportException.PdfWriteFailed(e.message.orEmpty(), e)
            }
        } finally {
            document.close()
        }
    }

    // ─── Page rendering ──────────────────────────────────────────

    private fun drawPage(
        document: PdfDocument,
        pageNumber: Int,
        page: PageContent,
    ) {
        // Decode the bitmap once. We need its dimensions for the
        // PDF page size and for normalizing OCR bboxes back to
        // pixels. `decodeStream` returns null on unsupported /
        // corrupt formats — fold to ImageUnreadable.
        val bitmap = try {
            context.contentResolver.openInputStream(page.imageUri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        } catch (e: IOException) {
            throw ExportException.ImageUnreadable(page.imageUri, e)
        } ?: throw ExportException.ImageUnreadable(page.imageUri)

        try {
            val pageInfo = PdfDocument.PageInfo
                .Builder(bitmap.width, bitmap.height, pageNumber)
                .create()
            val pdfPage = document.startPage(pageInfo)
            try {
                val canvas = pdfPage.canvas

                // 1. Visible image fill — drawn at the page origin
                // (0, 0) with no paint. Use the positional overload
                // explicitly; the (Bitmap, Matrix?, Paint?) overload's
                // Matrix arg is annotated non-null on newer SDKs, so
                // passing null for both fails overload resolution.
                canvas.drawBitmap(bitmap, 0f, 0f, null)

                // 2. Invisible text overlay — line-grained only.
                val invisible = Paint().apply {
                    color = Color.TRANSPARENT
                    isAntiAlias = false  // moot for invisible glyphs; saves CPU
                }
                for (block in page.ocrResult.blocks) {
                    if (block.kind != OcrBlock.Kind.Line) continue
                    drawInvisibleLine(
                        canvas      = canvas,
                        block       = block,
                        pageWidth   = bitmap.width.toDouble(),
                        pageHeight  = bitmap.height.toDouble(),
                        paint       = invisible,
                    )
                }
            } finally {
                document.finishPage(pdfPage)
            }
        } finally {
            // Don't recycle the bitmap if the caller might want to
            // keep using the underlying file for other paths; close
            // is enough.
            bitmap.recycle()
        }
    }

    private fun drawInvisibleLine(
        canvas: android.graphics.Canvas,
        block: OcrBlock,
        pageWidth: Double,
        pageHeight: Double,
        paint: Paint,
    ) {
        // OCR bbox: normalized 0..1, top-left origin.
        // Android Canvas: pixel coords, top-left origin (top-left
        // matches OCR's frame, no flip needed — unlike Core
        // Graphics' bottom-left convention on iOS).
        val pixelX      = block.bbox.x      * pageWidth
        val pixelY      = block.bbox.y      * pageHeight
        val pixelWidth  = block.bbox.width  * pageWidth
        val pixelHeight = block.bbox.height * pageHeight

        // Font size approximates the bbox height. We don't need a
        // pixel-perfect glyph match — the goal is "search hit
        // highlight roughly covers the visible word".
        val targetSize = pixelHeight.coerceAtLeast(1.0).toFloat()
        paint.textSize = targetSize

        // Width-fit: for very long lines, shrink the size so the
        // glyph run fits the bbox. Keeps search-highlight position
        // roughly correct on long-line cases.
        val measured = paint.measureText(block.text).toDouble()
        if (measured > pixelWidth && measured > 0.0) {
            paint.textSize = (targetSize * (pixelWidth / measured)).toFloat()
        }

        // `drawText` places the baseline at `y`. The bbox's
        // top-left y maps to the line's top edge, so the baseline
        // sits roughly at `y + height`. We use the bottom of the
        // bbox as the baseline approximation — close enough for
        // invisible text whose alignment only needs to satisfy
        // search-highlight overlap.
        val baselineY = (pixelY + pixelHeight).toFloat()
        canvas.drawText(block.text, pixelX.toFloat(), baselineY, paint)
    }

    // ─── Inputs + errors ─────────────────────────────────────────

    /**
     * One page of input — image + its OCR result. The image is
     * rendered as the visible page; the OCR's line-grained blocks
     * are overlaid as invisible text.
     */
    data class PageContent(
        val imageUri:  Uri,
        val ocrResult: OcrResult,
    )

    /**
     * Failure modes the exporter surfaces. Distinct from
     * [OcrException] because the export pipeline is downstream of
     * OCR — by the time we reach this code, recognition has
     * already succeeded.
     */
    sealed class ExportException(message: String, cause: Throwable? = null)
        : Exception(message, cause) {

        /** Image at `uri` couldn't be loaded. */
        class ImageUnreadable(val uri: Uri, cause: Throwable? = null)
            : ExportException("Image at $uri couldn't be loaded", cause)

        /** PDF write failed — disk full, sandbox permissions, etc. */
        class PdfWriteFailed(message: String, cause: Throwable? = null)
            : ExportException("PDF write failed: $message", cause)
    }
}
