/*
 * CompressedImagePdfWriter.kt
 *
 * Small image-only PDF writer used by the scanner save path. Android's
 * PdfDocument API does not expose image JPEG quality, so this writer
 * builds the minimal PDF structure directly. It embeds color pages as
 * downscaled JPEG streams and text-like pages as 1-bit Flate-compressed
 * image streams. That gives the save pipeline an explicit size budget
 * without changing OCR inputs.
 *
 * Mirror of `CompressedImagePdfWriter.swift` in ReleafCoreScan.
 */

package app.releaf.shared.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import app.releaf.mobile.data.common.AttachmentStorage
import app.releaf.mobile.data.common.Uuidv7
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

object CompressedImagePdfWriter {

    const val PDF_MARKER: String = "QuickInk-Compressed-PDF"
    const val DEFAULT_MAX_LONG_EDGE: Int = 1800
    const val DEFAULT_JPEG_QUALITY: Int = 82
    const val DEFAULT_TARGET_PAGE_BYTES: Int = 250 * 1024

    private const val PDF_PAGE_OVERHEAD_BUDGET_BYTES: Int = 8 * 1024
    private const val MIN_IMAGE_TARGET_BYTES: Int = 24 * 1024

    fun writeToAttachment(
        context: Context,
        imageUris: List<Uri>,
        maxLongEdge: Int = DEFAULT_MAX_LONG_EDGE,
        jpegQuality: Int = DEFAULT_JPEG_QUALITY,
        targetPageBytes: Int = DEFAULT_TARGET_PAGE_BYTES,
    ): Uri? {
        if (imageUris.isEmpty()) return null
        val dest = File(AttachmentStorage.directory(context), "${Uuidv7.generate()}.pdf")
        return runCatching {
            write(
                context = context,
                imageUris = imageUris,
                output = dest,
                maxLongEdge = maxLongEdge,
                jpegQuality = jpegQuality,
                targetPageBytes = targetPageBytes,
            )
            Uri.fromFile(dest)
        }.getOrElse {
            dest.delete()
            null
        }
    }

    fun write(
        context: Context,
        imageUris: List<Uri>,
        output: File,
        maxLongEdge: Int = DEFAULT_MAX_LONG_EDGE,
        jpegQuality: Int = DEFAULT_JPEG_QUALITY,
        targetPageBytes: Int = DEFAULT_TARGET_PAGE_BYTES,
    ) {
        require(imageUris.isNotEmpty()) { "imageUris must be non-empty" }

        val pageBudget = targetPageBytes.coerceAtLeast(1)
        val pages = imageUris.map { uri ->
            encodePage(
                context = context,
                uri = uri,
                maxLongEdge = maxLongEdge.coerceAtLeast(1),
                jpegQuality = jpegQuality.coerceIn(1, 100),
                targetPageBytes = pageBudget,
            )
        }
        require(pages.isNotEmpty()) { "no pages could be encoded" }

        val pdfBytes = ByteArrayOutputStream().use { stream ->
            writePdf(pages, stream)
            stream.toByteArray()
        }
        require(pdfBytes.size.toLong() < pageBudget.toLong() * pages.size) {
            "compressed PDF exceeded ${pageBudget}B/page budget"
        }

        output.outputStream().use { out ->
            out.write(pdfBytes)
        }
    }

    private data class Page(
        val width: Int,
        val height: Int,
        val image: PdfImage,
    ) {
        val imageBytes: ByteArray get() = image.bytes
    }

    private sealed class PdfImage {
        abstract val bytes: ByteArray

        abstract fun dictionary(width: Int, height: Int): String

        data class Jpeg(
            override val bytes: ByteArray,
        ) : PdfImage() {
            override fun dictionary(width: Int, height: Int): String =
                "<< /Type /XObject /Subtype /Image /Width $width /Height $height " +
                    "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode " +
                    "/Length ${bytes.size} >>"
        }

        data class BitonalFlate(
            override val bytes: ByteArray,
        ) : PdfImage() {
            override fun dictionary(width: Int, height: Int): String =
                "<< /Type /XObject /Subtype /Image /Width $width /Height $height " +
                    "/ColorSpace /DeviceGray /BitsPerComponent 1 /Decode [0 1] " +
                    "/Filter /FlateDecode /Length ${bytes.size} >>"
        }
    }

    private fun encodePage(
        context: Context,
        uri: Uri,
        maxLongEdge: Int,
        jpegQuality: Int,
        targetPageBytes: Int,
    ): Page {
        val bitmap = decodeScaledBitmap(context, uri, maxLongEdge)
            ?: throw IllegalArgumentException("Image at $uri could not be decoded")
        val opaque = makeOpaque(bitmap)
        if (opaque !== bitmap) bitmap.recycle()

        try {
            val imageBudget = (targetPageBytes - PDF_PAGE_OVERHEAD_BUDGET_BYTES)
                .coerceAtLeast(MIN_IMAGE_TARGET_BYTES)
            var smallestJpeg: Page? = null
            var selectedJpeg: Page? = null

            for (preset in encodingPresets(maxLongEdge, jpegQuality)) {
                val pageBitmap = scaledToLongEdge(opaque, preset.maxLongEdge)
                var hitBudget = false
                try {
                    val bytes = encodeJpeg(pageBitmap, preset.jpegQuality)
                    val page = Page(
                        width = pageBitmap.width,
                        height = pageBitmap.height,
                        image = PdfImage.Jpeg(bytes),
                    )
                    if (smallestJpeg == null || page.imageBytes.size < smallestJpeg.imageBytes.size) {
                        smallestJpeg = page
                    }
                    if (selectedJpeg == null && page.imageBytes.size <= imageBudget) {
                        selectedJpeg = page
                        hitBudget = true
                    }
                } finally {
                    if (pageBitmap !== opaque) pageBitmap.recycle()
                }
                if (hitBudget) break
            }

            var selected = selectedJpeg
                ?: smallestJpeg
                ?: throw IllegalArgumentException("Image at $uri could not be encoded")

            val analysis = analyzeDocumentScan(opaque)
            if (analysis.supportsBitonal) {
                val bitonal = encodeBestBitonalPage(
                    source = opaque,
                    threshold = analysis.threshold,
                    maxLongEdge = maxLongEdge,
                    imageBudget = imageBudget,
                    jpegBaseline = selected,
                )
                if (bitonal != null) selected = bitonal
            }

            return selected
        } finally {
            opaque.recycle()
        }
    }

    private data class EncodingPreset(
        val maxLongEdge: Int,
        val jpegQuality: Int,
    )

    private fun encodingPresets(maxLongEdge: Int, jpegQuality: Int): List<EncodingPreset> {
        val requestedEdge = maxLongEdge.coerceAtLeast(1)
        val requestedQuality = jpegQuality.coerceIn(1, 100)
        return listOf(
            EncodingPreset(requestedEdge, requestedQuality),
            EncodingPreset(requestedEdge, minOf(requestedQuality, 76)),
            EncodingPreset(minOf(requestedEdge, 1600), minOf(requestedQuality, 72)),
            EncodingPreset(minOf(requestedEdge, 1400), minOf(requestedQuality, 68)),
            EncodingPreset(minOf(requestedEdge, 1200), minOf(requestedQuality, 62)),
            EncodingPreset(minOf(requestedEdge, 1000), minOf(requestedQuality, 56)),
            EncodingPreset(minOf(requestedEdge, 850), minOf(requestedQuality, 50)),
            EncodingPreset(minOf(requestedEdge, 720), minOf(requestedQuality, 44)),
            EncodingPreset(minOf(requestedEdge, 640), minOf(requestedQuality, 38)),
            EncodingPreset(minOf(requestedEdge, 560), minOf(requestedQuality, 32)),
            EncodingPreset(minOf(requestedEdge, 480), minOf(requestedQuality, 28)),
            EncodingPreset(minOf(requestedEdge, 360), minOf(requestedQuality, 24)),
        ).distinct()
    }

    private fun bitonalPresets(maxLongEdge: Int): List<Int> {
        val requestedEdge = maxLongEdge.coerceAtLeast(1)
        return listOf(
            requestedEdge,
            minOf(requestedEdge, 1600),
            minOf(requestedEdge, 1400),
            minOf(requestedEdge, 1200),
            minOf(requestedEdge, 1000),
            minOf(requestedEdge, 850),
            minOf(requestedEdge, 720),
            minOf(requestedEdge, 640),
            minOf(requestedEdge, 560),
            minOf(requestedEdge, 480),
            minOf(requestedEdge, 360),
        ).distinct()
    }

    private fun encodeBestBitonalPage(
        source: Bitmap,
        threshold: Int,
        maxLongEdge: Int,
        imageBudget: Int,
        jpegBaseline: Page,
    ): Page? {
        var smallest: Page? = null
        var selected: Page? = null

        for (edge in bitonalPresets(maxLongEdge)) {
            val pageBitmap = scaledToLongEdge(source, edge)
            var hitBudget = false
            try {
                val bytes = encodeBitonalFlate(pageBitmap, threshold)
                val page = Page(
                    width = pageBitmap.width,
                    height = pageBitmap.height,
                    image = PdfImage.BitonalFlate(bytes),
                )
                if (smallest == null || page.imageBytes.size < smallest.imageBytes.size) {
                    smallest = page
                }
                if (selected == null && page.imageBytes.size <= imageBudget) {
                    selected = page
                    hitBudget = true
                }
            } finally {
                if (pageBitmap !== source) pageBitmap.recycle()
            }
            if (hitBudget) break
        }

        val candidate = selected ?: smallest ?: return null
        val jpegSize = jpegBaseline.imageBytes.size
        val bitonalSize = candidate.imageBytes.size
        return if (bitonalSize < jpegSize) candidate else null
    }

    private fun scaledToLongEdge(source: Bitmap, maxLongEdge: Int): Bitmap {
        val longEdge = maxOf(source.width, source.height)
        if (longEdge <= maxLongEdge) return source

        val scale = maxLongEdge.toFloat() / longEdge
        val targetW = (source.width * scale).toInt().coerceAtLeast(1)
        val targetH = (source.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, targetW, targetH, true)
    }

    private fun encodeJpeg(bitmap: Bitmap, jpegQuality: Int): ByteArray {
        return ByteArrayOutputStream().use { stream ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, stream)) {
                throw IllegalArgumentException("Bitmap could not be encoded")
            }
            stream.toByteArray()
        }
    }

    private fun encodeBitonalFlate(bitmap: Bitmap, threshold: Int): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val bytesPerRow = (width + 7) / 8
        val packed = ByteArray(bytesPerRow * height)

        for (y in 0 until height) {
            val rowOffset = y * bytesPerRow
            for (x in 0 until width) {
                val color = bitmap.getPixel(x, y)
                val luminance = luminance(
                    red = Color.red(color),
                    green = Color.green(color),
                    blue = Color.blue(color),
                )
                if (luminance > threshold) {
                    val byteIndex = rowOffset + x / 8
                    val bit = 7 - (x % 8)
                    packed[byteIndex] = (packed[byteIndex].toInt() or (1 shl bit)).toByte()
                }
            }
        }

        return deflate(packed)
    }

    private fun deflate(bytes: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        return try {
            ByteArrayOutputStream().use { stream ->
                DeflaterOutputStream(stream, deflater).use { out ->
                    out.write(bytes)
                }
                stream.toByteArray()
            }
        } finally {
            deflater.end()
        }
    }

    private fun decodeScaledBitmap(context: Context, uri: Uri, maxLongEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        } ?: return null

        val srcW = bounds.outWidth
        val srcH = bounds.outHeight
        if (srcW <= 0 || srcH <= 0) return null

        var sampleSize = 1
        while ((srcW / sampleSize) > maxLongEdge * 2 || (srcH / sampleSize) > maxLongEdge * 2) {
            sampleSize *= 2
        }

        val decode = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val raw = context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, decode)
        } ?: return null

        val longEdge = maxOf(raw.width, raw.height)
        if (longEdge <= maxLongEdge) return raw

        val scale = maxLongEdge.toFloat() / longEdge
        val targetW = (raw.width * scale).toInt().coerceAtLeast(1)
        val targetH = (raw.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(raw, targetW, targetH, true)
        if (scaled !== raw) raw.recycle()
        return scaled
    }

    private fun makeOpaque(source: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(source, 0f, 0f, null)
        return out
    }

    private data class DocumentScanAnalysis(
        val supportsBitonal: Boolean,
        val threshold: Int,
    )

    private fun analyzeDocumentScan(bitmap: Bitmap): DocumentScanAnalysis {
        val histogram = IntArray(256)
        var sampleStep = 1
        while ((bitmap.width / sampleStep) * (bitmap.height / sampleStep) > 12_000) {
            sampleStep *= 2
        }

        var samples = 0
        var chromaticSamples = 0
        for (y in 0 until bitmap.height step sampleStep) {
            for (x in 0 until bitmap.width step sampleStep) {
                val color = bitmap.getPixel(x, y)
                val red = Color.red(color)
                val green = Color.green(color)
                val blue = Color.blue(color)
                val luminance = luminance(red, green, blue)
                histogram[luminance] += 1
                samples += 1

                val maxChannel = maxOf(red, green, blue)
                val minChannel = minOf(red, green, blue)
                if (maxChannel - minChannel > 36 && maxChannel > 72) {
                    chromaticSamples += 1
                }
            }
        }

        if (samples == 0) return DocumentScanAnalysis(false, 180)

        val threshold = otsuThreshold(histogram, samples)
        val stats = thresholdStats(histogram, samples, threshold)
        val chromaticRatio = chromaticSamples.toDouble() / samples.toDouble()
        val foregroundRatio = stats.foregroundCount.toDouble() / samples.toDouble()
        val backgroundRatio = stats.backgroundCount.toDouble() / samples.toDouble()
        val contrast = stats.backgroundMean - stats.foregroundMean

        val supportsBitonal =
            chromaticRatio <= 0.10 &&
                foregroundRatio in 0.003..0.55 &&
                backgroundRatio >= 0.40 &&
                contrast >= 55.0 &&
                threshold in 70..230

        return DocumentScanAnalysis(
            supportsBitonal = supportsBitonal,
            threshold = threshold,
        )
    }

    private data class ThresholdStats(
        val foregroundCount: Int,
        val backgroundCount: Int,
        val foregroundMean: Double,
        val backgroundMean: Double,
    )

    private fun thresholdStats(
        histogram: IntArray,
        total: Int,
        threshold: Int,
    ): ThresholdStats {
        var foregroundCount = 0
        var backgroundCount = 0
        var foregroundSum = 0L
        var backgroundSum = 0L

        histogram.forEachIndexed { luminance, count ->
            if (luminance <= threshold) {
                foregroundCount += count
                foregroundSum += luminance.toLong() * count.toLong()
            } else {
                backgroundCount += count
                backgroundSum += luminance.toLong() * count.toLong()
            }
        }

        return ThresholdStats(
            foregroundCount = foregroundCount,
            backgroundCount = backgroundCount,
            foregroundMean = if (foregroundCount == 0) 0.0 else foregroundSum.toDouble() / foregroundCount,
            backgroundMean = if (backgroundCount == 0) 255.0 else backgroundSum.toDouble() / backgroundCount,
        )
    }

    private fun otsuThreshold(histogram: IntArray, total: Int): Int {
        var sum = 0L
        histogram.forEachIndexed { luminance, count ->
            sum += luminance.toLong() * count.toLong()
        }

        var backgroundWeight = 0
        var backgroundSum = 0L
        var bestVariance = -1.0
        var bestThreshold = 180

        histogram.forEachIndexed { threshold, count ->
            backgroundWeight += count
            backgroundSum += threshold.toLong() * count.toLong()
            if (backgroundWeight == 0) return@forEachIndexed

            val foregroundWeight = total - backgroundWeight
            if (foregroundWeight == 0) return@forEachIndexed

            val foregroundSum = sum - backgroundSum
            val backgroundMean = backgroundSum.toDouble() / backgroundWeight.toDouble()
            val foregroundMean = foregroundSum.toDouble() / foregroundWeight.toDouble()
            val diff = backgroundMean - foregroundMean
            val variance = backgroundWeight.toDouble() * foregroundWeight.toDouble() * diff * diff
            if (variance > bestVariance) {
                bestVariance = variance
                bestThreshold = threshold
            }
        }

        return bestThreshold
    }

    private fun luminance(red: Int, green: Int, blue: Int): Int =
        ((red * 299) + (green * 587) + (blue * 114)) / 1000

    private fun writePdf(pages: List<Page>, out: OutputStream) {
        var position = 0L
        val offsets = mutableListOf<Long>()

        fun writeAscii(value: String) {
            val bytes = value.toByteArray(StandardCharsets.US_ASCII)
            out.write(bytes)
            position += bytes.size
        }

        fun writeBytes(bytes: ByteArray) {
            out.write(bytes)
            position += bytes.size
        }

        fun beginObject(id: Int) {
            offsets += position
            writeAscii("$id 0 obj\n")
        }

        val pageObjectIds = pages.indices.map { 3 + it * 3 }
        val objectCount = 2 + pages.size * 3

        writeAscii("%PDF-1.4\n%$PDF_MARKER\n")

        beginObject(1)
        writeAscii("<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")

        beginObject(2)
        writeAscii("<< /Type /Pages /Count ${pages.size} /Kids [")
        for (pageObjectId in pageObjectIds) {
            writeAscii(" $pageObjectId 0 R")
        }
        writeAscii(" ] >>\nendobj\n")

        pages.forEachIndexed { index, page ->
            val pageObjectId = 3 + index * 3
            val contentObjectId = pageObjectId + 1
            val imageObjectId = pageObjectId + 2
            val imageName = "Im${index + 1}"
            val content = "q\n${page.width} 0 0 ${page.height} 0 0 cm\n/$imageName Do\nQ\n"

            beginObject(pageObjectId)
            writeAscii(
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${page.width} ${page.height}] " +
                    "/Resources << /XObject << /$imageName $imageObjectId 0 R >> >> " +
                    "/Contents $contentObjectId 0 R >>\nendobj\n",
            )

            beginObject(contentObjectId)
            writeAscii("<< /Length ${content.toByteArray(StandardCharsets.US_ASCII).size} >>\nstream\n")
            writeAscii(content)
            writeAscii("endstream\nendobj\n")

            beginObject(imageObjectId)
            writeAscii("${page.image.dictionary(page.width, page.height)}\nstream\n")
            writeBytes(page.imageBytes)
            writeAscii("\nendstream\nendobj\n")
        }

        val xrefStart = position
        writeAscii("xref\n0 ${objectCount + 1}\n")
        writeAscii("0000000000 65535 f \n")
        offsets.forEach { offset ->
            writeAscii(String.format(Locale.US, "%010d 00000 n \n", offset))
        }
        writeAscii(
            "trailer\n<< /Size ${objectCount + 1} /Root 1 0 R >>\n" +
                "startxref\n$xrefStart\n%%EOF\n",
        )
    }
}
