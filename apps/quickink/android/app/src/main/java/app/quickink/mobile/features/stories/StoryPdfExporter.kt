/*
 * StoryPdfExporter.kt
 *
 * Stories Phase 4 — renders a story + items into a US-Letter
 * `PdfDocument`. Mirror of iOS `StoryPdfExporter.swift`; see that
 * file's header for the per-page item count rule (full=1, half=2,
 * grid=4) + the layout choices.
 */

package app.quickink.mobile.features.stories

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import app.quickink.mobile.data.story.StoryEntity
import app.quickink.mobile.data.storyitem.StoryItemEntity
import java.io.File
import java.io.FileOutputStream

object StoryPdfExporter {

    private const val PAGE_WIDTH  = 612   // US Letter @ 72 DPI
    private const val PAGE_HEIGHT = 792
    private const val MARGIN      = 48f

    /**
     * Render the story to a temp PDF. Returns the on-disk file ready
     * for FileProvider sharing.
     *
     * `previewUris` maps each capture-backed `StoryItemEntity.refId`
     * to its on-disk `preview_uri`. The exporter loads + embeds the
     * bitmap when present; misses fall back to a cream-box.
     */
    fun export(
        context: Context,
        story: StoryEntity,
        items: List<StoryItemEntity>,
        previewUris: Map<String, String> = emptyMap(),
    ): File {
        val doc = PdfDocument()
        try {
            // Cover page.
            renderPage(doc, pageNum = 1) { canvas, _ ->
                drawCover(canvas, story, items)
            }

            // Item pages.
            val chunks = paginate(items)
            chunks.forEachIndexed { index, chunk ->
                renderPage(doc, pageNum = 2 + index) { canvas, _ ->
                    drawChunk(canvas, chunk, items, previewUris)
                }
            }

            // End-card page.
            renderPage(doc, pageNum = 2 + chunks.size) { canvas, _ ->
                drawEndCard(canvas)
            }

            val dir = File(context.cacheDir, "stories-exports").apply { if (!exists()) mkdirs() }
            val file = File(dir, "${sanitizeFileName(story.title)}.pdf")
            FileOutputStream(file).use { out -> doc.writeTo(out) }
            return file
        } finally {
            doc.close()
        }
    }

    private fun renderPage(doc: PdfDocument, pageNum: Int, draw: (Canvas, PdfDocument.Page) -> Unit) {
        val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNum).create()
        val page = doc.startPage(pageInfo)
        draw(page.canvas, page)
        doc.finishPage(page)
    }

    // MARK: - Cover

    private fun drawCover(canvas: Canvas, story: StoryEntity, items: List<StoryItemEntity>) {
        val (startCol, endCol) = coverColors(story.coverStyle)
        val coverRect = RectF(MARGIN, 96f, PAGE_WIDTH - MARGIN, 96f + 320f)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                coverRect.left, coverRect.top, coverRect.right, coverRect.bottom,
                startCol, endCol, Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRoundRect(coverRect, 16f, 16f, paint)

        val stampSource = story.timeRangeStart
            ?: items.firstOrNull()?.let { it.occurredAt ?: it.createdAt }
            ?: story.createdAt
        val stamp = monthYearStamp(stampSource) ?: ""
        val stampPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.DKGRAY
            textSize = 10f
            isFakeBoldText = false
            isAntiAlias = true
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.ITALIC)
            letterSpacing = 0.2f
        }
        canvas.drawText(stamp, coverRect.left + 16f, coverRect.top + 30f, stampPaint)

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 26f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.NORMAL)
        }
        val titleY = coverRect.bottom - 56f
        canvas.drawText(story.title, coverRect.left + 16f, titleY, titlePaint)

        if (!story.subtitle.isNullOrEmpty()) {
            val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.DKGRAY
                textSize = 16f
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.ITALIC)
            }
            canvas.drawText(story.subtitle, coverRect.left + 16f, coverRect.bottom - 24f, subPaint)
        }

        val attribution = "${items.size} items"
        val attrPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.DKGRAY
            textSize = 11f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.ITALIC)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(attribution, PAGE_WIDTH / 2f, coverRect.bottom + 24f, attrPaint)
    }

    // MARK: - Chunks

    private data class Chunk(val items: List<StoryItemEntity>, val perPage: Int)

    private fun paginate(items: List<StoryItemEntity>): List<Chunk> {
        val out = mutableListOf<Chunk>()
        var i = 0
        while (i < items.size) {
            val perPage = itemsPerPage(items[i].layout)
            val end = (i + perPage).coerceAtMost(items.size)
            out += Chunk(items.subList(i, end), perPage)
            i = end
        }
        return out
    }

    private fun itemsPerPage(layoutRaw: String): Int = when (layoutRaw) {
        StoryItemEntity.Layout.HALF.raw -> 2
        StoryItemEntity.Layout.GRID.raw -> 4
        else                            -> 1
    }

    private fun drawChunk(canvas: Canvas, chunk: Chunk, allItems: List<StoryItemEntity>, previewUris: Map<String, String>) {
        val markers = StoryDayMarkers.derive(allItems).associateBy { it.precedingItemId }
        val contentTop = MARGIN
        val contentLeft = MARGIN
        val contentRight = PAGE_WIDTH - MARGIN
        val contentBottom = PAGE_HEIGHT - MARGIN
        var y = contentTop

        for (item in chunk.items) {
            markers[item.id]?.let { marker ->
                y = drawDayMarker(canvas, marker.label, y, contentLeft)
            }
            val height = when (chunk.perPage) {
                2 -> (contentBottom - contentTop - 32f) / 2f
                4 -> (contentBottom - contentTop - 32f * 2) / 4f
                else -> contentBottom - y
            }
            val rect = RectF(contentLeft, y, contentRight, y + height)
            drawItem(canvas, item, rect, previewUris)
            y = rect.bottom + 24f
        }
    }

    private fun drawDayMarker(canvas: Canvas, label: String, atY: Float, left: Float): Float {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#C65A3E")
            textSize = 11f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.ITALIC)
            letterSpacing = 0.1f
        }
        canvas.drawText(label, left, atY + 12f, paint)
        return atY + 24f
    }

    private fun drawItem(canvas: Canvas, item: StoryItemEntity, rect: RectF, previewUris: Map<String, String> = emptyMap()) {
        when (item.kind) {
            StoryItemEntity.Kind.TEXT_BLOCK.raw -> {
                val text = item.text.orEmpty()
                drawWrappedText(canvas, text, rect, 14f, italic = false, color = Color.BLACK)
            }
            StoryItemEntity.Kind.HANDWRITTEN_NOTE.raw -> {
                val rule = Paint().apply { color = Color.parseColor("#C65A3E") }
                canvas.drawRect(rect.left, rect.top, rect.left + 2f, rect.top + minOf(rect.height(), 60f), rule)
                drawWrappedText(canvas, item.text.orEmpty(), RectF(rect.left + 14f, rect.top, rect.right, rect.bottom), 18f, italic = true, color = Color.DKGRAY)
            }
            StoryItemEntity.Kind.DATE_DIVIDER.raw -> {
                val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#C65A3E")
                    textSize = 12f
                    typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.ITALIC)
                    letterSpacing = 0.08f
                    textAlign = Paint.Align.CENTER
                }
                canvas.drawText(item.text ?: "Date divider", (rect.left + rect.right) / 2f, rect.top + 16f, paint)
            }
            StoryItemEntity.Kind.PLACE_PIN.raw -> {
                val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.DKGRAY
                    textSize = 13f
                    typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.ITALIC)
                }
                canvas.drawText("📍 ${item.text.orEmpty()}", rect.left, rect.top + 16f, paint)
            }
            StoryItemEntity.Kind.VOICE_CLIP.raw -> {
                val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 1f
                    color = Color.LTGRAY
                }
                val inner = RectF(rect.left, rect.top + 8f, rect.right, rect.bottom - 8f)
                canvas.drawRoundRect(inner, 12f, 12f, stroke)
                val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.DKGRAY
                    textSize = 12f
                    typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.ITALIC)
                }
                canvas.drawText("🎙  ${item.caption ?: "Voice clip — open the app to listen."}", rect.left + 12f, rect.top + 28f, paint)
            }
            else -> {
                val photoHeight = minOf(rect.height() - 30f, 360f)
                val photoRect = RectF(rect.left, rect.top, rect.right, rect.top + photoHeight)
                val bmp = item.refId?.let { previewUris[it] }?.let { loadBitmap(it) }
                if (bmp != null) {
                    canvas.save()
                    val clip = Path().apply { addRoundRect(photoRect, 8f, 8f, Path.Direction.CW) }
                    canvas.clipPath(clip)
                    val dst = aspectFill(bmp.width, bmp.height, photoRect)
                    canvas.drawBitmap(bmp, null, dst, null)
                    canvas.restore()
                    bmp.recycle()
                } else {
                    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#EAE5DA") }
                    canvas.drawRoundRect(photoRect, 8f, 8f, fill)
                }
                if (!item.caption.isNullOrEmpty()) {
                    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.DKGRAY
                        textSize = 12f
                        typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.ITALIC)
                    }
                    canvas.drawText(item.caption, rect.left, photoRect.bottom + 14f, paint)
                }
            }
        }
    }

    /** Load a bitmap from a `file://` URI when the file is present. */
    private fun loadBitmap(uri: String): Bitmap? = runCatching {
        val parsed = if (uri.startsWith("file://")) Uri.parse(uri) else null
        val path = parsed?.path ?: uri
        BitmapFactory.decodeFile(path)
    }.getOrNull()

    /** Aspect-fill rect for a bitmap of `(w, h)` into `target`. */
    private fun aspectFill(w: Int, h: Int, target: RectF): RectF {
        if (w <= 0 || h <= 0) return target
        val scale = maxOf(target.width() / w, target.height() / h)
        val outW = w * scale
        val outH = h * scale
        val cx = (target.left + target.right) / 2f
        val cy = (target.top + target.bottom) / 2f
        return RectF(cx - outW / 2f, cy - outH / 2f, cx + outW / 2f, cy + outH / 2f)
    }

    // MARK: - End-card

    private fun drawEndCard(canvas: Canvas) {
        val rect = RectF(MARGIN, 280f, PAGE_WIDTH - MARGIN, 460f)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
            color = Color.LTGRAY
        }
        canvas.drawRoundRect(rect, 14f, 14f, stroke)
        val end = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.DKGRAY
            textSize = 13f
            letterSpacing = 0.12f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.ITALIC)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("— THE END —", PAGE_WIDTH / 2f, rect.centerY(), end)

        val footer = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.GRAY
            textSize = 11f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.ITALIC)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("— Made with QuickInk · scan, jot, find again. —",
            PAGE_WIDTH / 2f, PAGE_HEIGHT - MARGIN - 16f, footer)
    }

    // MARK: - Helpers

    private fun drawWrappedText(canvas: Canvas, text: String, rect: RectF, size: Float, italic: Boolean, color: Int) {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            this.textSize = size
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.SERIF,
                if (italic) android.graphics.Typeface.ITALIC else android.graphics.Typeface.NORMAL,
            )
        }
        val width = rect.width().toInt().coerceAtLeast(1)
        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setLineSpacing(4f, 1f)
            .build()
        canvas.save()
        canvas.translate(rect.left, rect.top)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun coverColors(coverStyleRaw: String): Pair<Int, Int> = when (coverStyleRaw) {
        StoryEntity.CoverStyle.GRADIENT.raw    -> Color.parseColor("#E07856") to Color.parseColor("#C65A3E")
        StoryEntity.CoverStyle.TYPOGRAPHIC.raw -> Color.parseColor("#EADFCF") to Color.parseColor("#E8DCC4")
        else                                   -> Color.parseColor("#E8DCC4") to Color.parseColor("#F0E4D7")
    }

    private fun monthYearStamp(iso: String): String? = runCatching {
        val dt = java.time.OffsetDateTime.parse(iso)
        java.time.format.DateTimeFormatter.ofPattern("MMM yyyy", java.util.Locale.ENGLISH).format(dt).uppercase()
    }.getOrNull()

    private fun sanitizeFileName(name: String): String {
        val out = name.map { c -> if (c in "/\\:*?\"<>|") '-' else c }.joinToString("").trim()
        return out.ifBlank { "Story" }
    }
}
