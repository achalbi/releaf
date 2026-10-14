/*
 * PageImageLoader.kt
 *
 * Decodes one scanned page into a downsampled bitmap plus the 8-bit
 * grayscale buffer `CheckboxTodoDetector` consumes. The bitmap is
 * kept for the on-device barcode pass (template marks) so each page
 * is decoded once.
 *
 * Must run in the same pass as OCR: the document scanner's page
 * `content://` URIs are only readable for the process lifetime and
 * only the PDF + first-page JPEG are kept afterwards (see
 * `DocumentScannerLauncher.kt`).
 *
 * Orientation: on API 28+ `ImageDecoder` applies EXIF rotation, the
 * same frame ML Kit's `InputImage.fromFilePath` reports boxes in, so
 * the normalised OCR boxes line up with the pixels. Older devices
 * fall back to `BitmapFactory` (no EXIF); scanner output is upright
 * there anyway, only rotated photo-picker imports could misalign.
 */

package app.quickink.mobile.features.scan.handwriting

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import kotlin.math.max
import kotlin.math.roundToInt

class LoadedPage(val bitmap: Bitmap, val gray: GrayImage) {
    fun recycle() {
        if (!bitmap.isRecycled) bitmap.recycle()
    }
}

object PageImageLoader {

    /**
     * Long-side cap. Big enough that a ~4mm checkbox on an A5 page is
     * still ~30px and a corner QR mark decodes; small enough to keep
     * the per-page buffer around 4MB.
     */
    private const val MAX_SIDE = 2000

    /** Returns null when the page can't be read; callers fall back to text-only detection. */
    fun load(context: Context, uri: Uri, maxSide: Int = MAX_SIDE): LoadedPage? = try {
        val bitmap = decode(context, uri, maxSide) ?: return null
        LoadedPage(bitmap, toGray(bitmap))
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }

    private fun decode(context: Context, uri: Uri, maxSide: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val scale = maxSide.toDouble() / max(w, h)
                if (scale < 1.0) decoder.setTargetSize((w * scale).roundToInt(), (h * scale).roundToInt())
                // Software bitmap so getPixels works.
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, opts) }
    }

    /** Rec. 601 luma, the same weighting the camera's Y plane uses. */
    private fun toGray(bitmap: Bitmap): GrayImage {
        val w = bitmap.width
        val h = bitmap.height
        val row = IntArray(w)
        val out = ByteArray(w * h)
        for (y in 0 until h) {
            bitmap.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val c = row[x]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                out[y * w + x] = ((299 * r + 587 * g + 114 * b) / 1000).toByte()
            }
        }
        return GrayImage(out, w, h)
    }
}
