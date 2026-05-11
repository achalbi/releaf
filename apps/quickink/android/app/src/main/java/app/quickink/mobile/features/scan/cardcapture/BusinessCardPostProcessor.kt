/*
 * BusinessCardPostProcessor.kt
 *
 * Hand-off point between the Business Card capture surface and
 * the existing OCR + contact-extraction pipeline. Given a
 * captured Bitmap (full-resolution still from CameraX) and the
 * most recent valid quad in still-image coordinates, this
 * post-processor:
 *
 *   1. Perspective-corrects the source corners to (0,0)→
 *      (1011,0)→(1011,637)→(0,637) using Android's
 *      `Matrix.setPolyToPoly` + Canvas (GPU-backed on most
 *      devices; faster than the per-pixel Kotlin warp in
 *      CardImageOps).
 *   2. Saves the result as a JPEG inside AttachmentStorage.
 *   3. Builds a [DocumentScanResult] with the warped JPEG as
 *      the single page + preview, and routes it through
 *      [ScanFlowController.onScanComplete] with
 *      `category = "Business Card"` so the existing scan-detail
 *      screen picks up the BusinessCardExtractor flow.
 *
 * Manual capture (user tapped the shutter without a valid
 * stability lock): callers pass `quadInBitmap = null` and we
 * fall back to the guide rect as the "card quad" — the user
 * was free-handing it, so a center-of-frame heuristic is the
 * best we can do without rejecting their tap.
 *
 * The CardImageOps perspective-warp implementation lives in
 * pure Kotlin so it's unit-testable on the JVM; the Android
 * Matrix path here is the production code path that runs on
 * the GPU.
 */

package app.quickink.mobile.features.scan.cardcapture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import app.quickink.mobile.features.scan.ScanFlowController
import app.releaf.mobile.data.common.AttachmentStorage
import app.releaf.mobile.data.common.Uuidv7
import app.releaf.shared.scan.DocumentScanResult
import java.io.File

object BusinessCardPostProcessor {

    /** Warped output size — spec callout. */
    const val OUTPUT_WIDTH  = 1012
    const val OUTPUT_HEIGHT = 638

    /**
     * Run the full post-process and hand the resulting capture
     * to the controller. Returns the warped JPEG's file:// URI
     * so the caller (the surface) can show an in-screen confirm
     * animation if it wants.
     *
     * The user-visible region of the bitmap is the FILL_CENTER
     * center crop whose aspect matches [viewWidth]:[viewHeight]
     * (the on-screen canvas behind the overlay). The guide rect
     * we crop to is the 70%-of-width / 1.586:1 / 45%-vertical
     * sub-rect inside THAT visible region — identical math to
     * the overlay's draw + the detector's IoU check, so what
     * gets warped is exactly what the user framed.
     *
     * Threading: the bitmap warp + JPEG encode are CPU-bound
     * (Canvas + Bitmap.compress); the controller's
     * `onScanComplete` kicks coroutines internally so it's
     * non-blocking. Call from a background thread.
     */
    fun process(
        context: Context,
        source: Bitmap,
        quadInBitmap: DetectedQuad?,
        viewWidth: Float,
        viewHeight: Float,
        controller: ScanFlowController,
    ): Uri? {
        val guideInBitmap = computeGuideInBitmap(source, viewWidth, viewHeight)
        val quad = quadInBitmap ?: guideInBitmap.asQuad()
        val warped = warpToCardSize(source, quad) ?: return null
        val jpegUri = saveJpeg(context, warped) ?: return null
        // Source bitmap is owned by us at this point — recycle to
        // avoid holding a multi-megapixel allocation while OCR
        // runs. The warped output is in `jpegUri`; the source's
        // pixels are no longer needed.
        if (!source.isRecycled) source.recycle()
        if (!warped.isRecycled) warped.recycle()

        // Build a DocumentScanResult that the existing controller
        // can swallow whole. pdfUri stays null — business card
        // captures don't ship a PDF artifact (the Library card
        // renders fine from the preview JPEG, and the export
        // sheet can synthesize a PDF later if needed). pageUris
        // is a single-entry list with the warped JPEG; OCR reads
        // file:// URIs the same way it reads content:// ones.
        val result = DocumentScanResult(
            pdfUri     = null,
            previewUri = jpegUri,
            pageUris   = listOf(jpegUri),
        )
        controller.onScanComplete(
            result   = result,
            category = "Business Card",
        )
        return jpegUri
    }

    /**
     * Perspective-warp [source] using [quad] as the source
     * corners and (0,0)→(1011,0)→(1011,637)→(0,637) as the
     * destination corners. Output is a fresh [Bitmap] sized to
     * [OUTPUT_WIDTH] × [OUTPUT_HEIGHT].
     */
    fun warpToCardSize(source: Bitmap, quad: DetectedQuad): Bitmap? {
        val srcPoints = floatArrayOf(
            quad.tl.x, quad.tl.y,
            quad.tr.x, quad.tr.y,
            quad.br.x, quad.br.y,
            quad.bl.x, quad.bl.y,
        )
        val dstPoints = floatArrayOf(
            0f,                                0f,
            (OUTPUT_WIDTH - 1).toFloat(),      0f,
            (OUTPUT_WIDTH - 1).toFloat(),      (OUTPUT_HEIGHT - 1).toFloat(),
            0f,                                (OUTPUT_HEIGHT - 1).toFloat(),
        )
        val matrix = Matrix()
        // 4-point poly → poly. Android computes the homography
        // internally — same arithmetic as CardImageOps.getPerspectiveTransform
        // but routed through Skia's GPU path on the subsequent
        // Canvas draw.
        if (!matrix.setPolyToPoly(srcPoints, 0, dstPoints, 0, 4)) return null

        val out = Bitmap.createBitmap(
            OUTPUT_WIDTH,
            OUTPUT_HEIGHT,
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(out)
        val paint = Paint().apply {
            isFilterBitmap = true     // bilinear sampling
            isAntiAlias    = true
            isDither       = true
        }
        canvas.drawBitmap(source, matrix, paint)
        return out
    }

    /**
     * Compute the in-bitmap guide rect that corresponds to the
     * on-screen overlay, accounting for the FILL_CENTER center
     * crop the PreviewView applies. Same arithmetic as the
     * detector's analyzer-guide computation; centralized in
     * [CardImageOps] so both sides agree.
     */
    fun computeGuideInBitmap(source: Bitmap, viewWidth: Float, viewHeight: Float): GuideRect {
        val visible = CardImageOps.visibleRectForViewAspect(
            imageWidth  = source.width,
            imageHeight = source.height,
            viewWidth   = viewWidth,
            viewHeight  = viewHeight,
        )
        return CardImageOps.guideRectInside(visible)
    }

    /**
     * Save [bitmap] as JPEG into [AttachmentStorage.directory] and
     * return the file:// URI. Quality 92 — high enough that the
     * text edges in the warped card stay crisp for ML Kit's
     * Latin-script text recognizer, low enough that a typical
     * card lands at ~150 KB on disk.
     */
    fun saveJpeg(context: Context, bitmap: Bitmap): Uri? {
        val dest = File(
            AttachmentStorage.directory(context),
            "${Uuidv7.generate()}.jpg",
        )
        return runCatching {
            dest.outputStream().use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)) return null
            }
            Uri.fromFile(dest)
        }.getOrNull()
    }
}
