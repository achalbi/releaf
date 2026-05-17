/*
 * StoryImageExporter.kt
 *
 * Stories Phase 4 — composites a story into a single tall PNG.
 * Mirror of iOS `StoryImageExporter.swift`; capped at 12 items, with
 * a "+ N more in the app" footer when truncated.
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
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import app.quickink.mobile.data.story.StoryEntity
import app.quickink.mobile.data.storyitem.StoryItemEntity
import java.io.File
import java.io.FileOutputStream

object StoryImageExporter {

    private const val MAX_ITEMS = 12
    private const val WIDTH     = 1080f
    private const val PAD       = 56f

    fun export(
        context: Context,
        story: StoryEntity,
        items: List<StoryItemEntity>,
        previewUris: Map<String, String> = emptyMap(),
    ): File {
        val limited = items.take(MAX_ITEMS)
        val overflow = (items.size - limited.size).coerceAtLeast(0)
        val height   = computeHeight(limited, overflow > 0)

        val bitmap = Bitmap.createBitmap(WIDTH.toInt(), height.toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        draw(canvas, story, limited, overflow, height, previewUris)

        val dir = File(context.cacheDir, "stories-exports").apply { if (!exists()) mkdirs() }
        val file = File(dir, "${sanitizeFileName(story.title)}.png")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bitmap.recycle()
        return file
    }

    private fun computeHeight(items: List<StoryItemEntity>, hasOverflow: Boolean): Float {
        var total = 560f + 60f
        items.forEach { total += rowHeight(it) }
        if (hasOverflow) total += 56f
        total += 200f
        return total
    }

    private fun rowHeight(item: StoryItemEntity): Float = when (item.kind) {
        StoryItemEntity.Kind.TEXT_BLOCK.raw        -> 120f
        StoryItemEntity.Kind.HANDWRITTEN_NOTE.raw  -> 96f
        StoryItemEntity.Kind.DATE_DIVIDER.raw      -> 56f
        StoryItemEntity.Kind.PLACE_PIN.raw         -> 48f
        StoryItemEntity.Kind.VOICE_CLIP.raw        -> 96f
        else -> when (item.layout) {
            StoryItemEntity.Layout.HALF.raw -> 320f
            StoryItemEntity.Layout.GRID.raw -> 240f
            else                             -> 520f
        }
    }

    private fun draw(canvas: Canvas, story: StoryEntity, items: List<StoryItemEntity>, overflow: Int, height: Float, previewUris: Map<String, String>) {
        // Cream background
        canvas.drawColor(Color.parseColor("#FBF6EE"))

        // Cover
        val coverRect = RectF(PAD, PAD, WIDTH - PAD, PAD + 480f)
        val (startCol, endCol) = coverColors(story.coverStyle)
        val coverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                coverRect.left, coverRect.top, coverRect.right, coverRect.bottom,
                startCol, endCol, Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRoundRect(coverRect, 24f, 24f, coverPaint)

        // Title at bottom-left
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 60f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.NORMAL)
        }
        canvas.drawText(story.title, coverRect.left + 32f, coverRect.bottom - 100f, titlePaint)
        if (!story.subtitle.isNullOrEmpty()) {
            val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#333333")
                textSize = 36f
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.ITALIC)
            }
            canvas.drawText(story.subtitle, coverRect.left + 32f, coverRect.bottom - 40f, subPaint)
        }

        // Attribution
        var y = coverRect.bottom + 40f
        val attrPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.DKGRAY
            textSize = 22f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.ITALIC)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("${items.size} items", WIDTH / 2f, y, attrPaint)
        y += 50f

        // Items + markers
        val markers = StoryDayMarkers.derive(items).associateBy { it.precedingItemId }
        for (item in items) {
            markers[item.id]?.let { marker ->
                val mp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#C65A3E")
                    textSize = 20f
                    typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.ITALIC)
                    letterSpacing = 0.05f
                }
                canvas.drawText(marker.label, PAD, y + 16f, mp)
                y += 40f
            }
            val rect = RectF(PAD, y, WIDTH - PAD, y + rowHeight(item))
            drawRow(canvas, item, rect, previewUris)
            y = rect.bottom + 16f
        }

        if (overflow > 0) {
            val font = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.GRAY
                textSize = 20f
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.ITALIC)
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("+ $overflow more in the app", WIDTH / 2f, y + 24f, font)
            y += 56f
        }

        // End-card + footer
        y += 32f
        val endRect = RectF(PAD, y, WIDTH - PAD, y + 96f)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1f; color = Color.LTGRAY
        }
        canvas.drawRoundRect(endRect, 24f, 24f, stroke)
        val endPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.DKGRAY
            textSize = 22f
            letterSpacing = 0.15f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.ITALIC)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("— THE END —", WIDTH / 2f, endRect.centerY() + 8f, endPaint)
        y = endRect.bottom + 24f
        val footerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.GRAY
            textSize = 18f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.ITALIC)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("— Made with QuickInk —", WIDTH / 2f, y + 8f, footerPaint)
    }

    private fun drawRow(canvas: Canvas, item: StoryItemEntity, rect: RectF, previewUris: Map<String, String> = emptyMap()) {
        when (item.kind) {
            StoryItemEntity.Kind.TEXT_BLOCK.raw -> {
                drawWrappedText(canvas, item.text.orEmpty(), rect, 22f, italic = false, color = Color.BLACK)
            }
            StoryItemEntity.Kind.HANDWRITTEN_NOTE.raw -> {
                val rule = Paint().apply { color = Color.parseColor("#C65A3E") }
                canvas.drawRect(rect.left, rect.top, rect.left + 4f, rect.bottom, rule)
                drawWrappedText(
                    canvas, item.text.orEmpty(),
                    RectF(rect.left + 24f, rect.top + 8f, rect.right, rect.bottom),
                    28f, italic = true, color = Color.DKGRAY,
                )
            }
            StoryItemEntity.Kind.DATE_DIVIDER.raw -> {
                val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#C65A3E")
                    textSize = 22f
                    typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.ITALIC)
                    letterSpacing = 0.12f
                }
                canvas.drawText(item.text ?: "Date divider", rect.left, rect.top + 24f, paint)
            }
            StoryItemEntity.Kind.PLACE_PIN.raw -> {
                val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.DKGRAY
                    textSize = 22f
                    typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.ITALIC)
                }
                canvas.drawText("📍 ${item.text.orEmpty()}", rect.left, rect.top + 24f, paint)
            }
            StoryItemEntity.Kind.VOICE_CLIP.raw -> {
                val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE; strokeWidth = 1f; color = Color.LTGRAY
                }
                canvas.drawRoundRect(rect, 16f, 16f, stroke)
                val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.DKGRAY
                    textSize = 20f
                    typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.ITALIC)
                }
                canvas.drawText("🎙  ${item.caption ?: "Voice clip"}",
                    rect.left + 24f, rect.centerY() + 6f, paint)
            }
            else -> {
                val photoHeight = (rect.height() - 60f).coerceAtLeast(120f)
                val photoRect = RectF(rect.left, rect.top, rect.right, rect.top + photoHeight)
                val bmp = item.refId?.let { previewUris[it] }?.let { loadBitmap(it) }
                if (bmp != null) {
                    canvas.save()
                    val clip = Path().apply { addRoundRect(photoRect, 16f, 16f, Path.Direction.CW) }
                    canvas.clipPath(clip)
                    val dst = aspectFill(bmp.width, bmp.height, photoRect)
                    canvas.drawBitmap(bmp, null, dst, null)
                    canvas.restore()
                    bmp.recycle()
                } else {
                    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#EAE5DA") }
                    canvas.drawRoundRect(photoRect, 16f, 16f, fill)
                }
                if (!item.caption.isNullOrEmpty()) {
                    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.DKGRAY
                        textSize = 20f
                        typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.ITALIC)
                    }
                    canvas.drawText(item.caption, rect.left, photoRect.bottom + 24f, paint)
                }
            }
        }
    }

    private fun loadBitmap(uri: String): Bitmap? = runCatching {
        val parsed = if (uri.startsWith("file://")) Uri.parse(uri) else null
        val path = parsed?.path ?: uri
        BitmapFactory.decodeFile(path)
    }.getOrNull()

    private fun aspectFill(w: Int, h: Int, target: RectF): RectF {
        if (w <= 0 || h <= 0) return target
        val scale = maxOf(target.width() / w, target.height() / h)
        val outW = w * scale
        val outH = h * scale
        val cx = (target.left + target.right) / 2f
        val cy = (target.top + target.bottom) / 2f
        return RectF(cx - outW / 2f, cy - outH / 2f, cx + outW / 2f, cy + outH / 2f)
    }

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
            .setLineSpacing(6f, 1f)
            .setIncludePad(false)
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

    private fun sanitizeFileName(name: String): String {
        val out = name.map { c -> if (c in "/\\:*?\"<>|") '-' else c }.joinToString("").trim()
        return out.ifBlank { "Story" }
    }
}
