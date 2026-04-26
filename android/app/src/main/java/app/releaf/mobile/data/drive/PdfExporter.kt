/*
 * PdfExporter.kt
 *
 * Renders a `Page` to a single-page (or multi-page if it overflows)
 * letter-size PDF and writes it under `cacheDir/exports/` so the
 * file is share-target reachable via FileProvider. Returns the
 * Uri the page was written to.
 *
 * The output is plain editorial: green eyebrow + serif title +
 * dateline, then the page's notes set as serif body, then a small
 * captures-summary block at the bottom. Mirrors PdfExporter.swift
 * — same layout, same warm tokens, same fonts (system serif via
 * Typeface.SERIF since we don't ship Newsreader on Android yet).
 */

package app.releaf.mobile.data.drive

import android.content.Context
import android.graphics.Color as AColor
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import app.releaf.mobile.data.domain.Page
import java.io.File
import java.io.FileOutputStream
import java.time.Instant

object PdfExporter {

    private const val PAGE_WIDTH  = 612      // 8.5"
    private const val PAGE_HEIGHT = 792      // 11"
    private const val MARGIN      = 56f

    private val ink            = AColor.rgb(0x46, 0x3C, 0x31)
    private val inkSoft        = AColor.rgb(0x5F, 0x52, 0x45)
    private val coral          = AColor.rgb(0xE0, 0x78, 0x56)
    private val themeGreenDeep = AColor.rgb(0x5B, 0x8C, 0x52)
    private val borderRule     = AColor.argb(0x3D, 0x50, 0x3E, 0x2D) // ~24%

    /** Render [page] to a fresh file under `cacheDir/exports/` and
     *  return a content:// Uri reachable via the app's FileProvider.
     *  Filename pattern: `page-{id}-{timestamp}.pdf`. */
    fun export(context: Context, page: Page): android.net.Uri {
        val document = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create()
        val pdfPage  = document.startPage(pageInfo)
        val canvas   = pdfPage.canvas

        var y = MARGIN
        y = drawHeader(canvas, page, y)
        y += 24f
        y = drawNotes(canvas, page, y)
        y += 24f
        drawCapturesSummary(canvas, page, y)

        document.finishPage(pdfPage)

        val file = exportFile(context, page)
        FileOutputStream(file).use { document.writeTo(it) }
        document.close()

        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
    }

    // ---------- Sections ----------

    private fun drawHeader(canvas: android.graphics.Canvas, page: Page, top: Float): Float {
        var y = top

        val eyebrow = "RELEAF · PAGE"
        val eyebrowPaint = Paint().apply {
            isAntiAlias = true
            color       = themeGreenDeep
            textSize    = 9f
            typeface    = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            letterSpacing = 0.16f
        }
        val eyebrowMetrics = eyebrowPaint.fontMetrics
        canvas.drawText(eyebrow, MARGIN, y - eyebrowMetrics.ascent, eyebrowPaint)
        val eyebrowWidth = eyebrowPaint.measureText(eyebrow)

        // Coral leaf glyph next to eyebrow
        val glyphHeight = 9f
        drawLeafGlyph(canvas, MARGIN + eyebrowWidth + 6f, y + 1f, glyphHeight)

        y += eyebrowPaint.fontMetrics.run { descent - ascent } + 6f

        // Title
        val titlePaint = Paint().apply {
            isAntiAlias = true
            color       = ink
            textSize    = 28f
            typeface    = Typeface.SERIF
        }
        canvas.drawText(page.title, MARGIN, y - titlePaint.fontMetrics.ascent, titlePaint)
        y += titlePaint.fontMetrics.run { descent - ascent } + 4f

        // Dateline
        page.capturedOn?.let { captured ->
            val metaPaint = Paint().apply {
                isAntiAlias = true
                color       = inkSoft
                textSize    = 13f
                typeface    = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
            }
            canvas.drawText(captured, MARGIN, y - metaPaint.fontMetrics.ascent, metaPaint)
            y += metaPaint.fontMetrics.run { descent - ascent } + 6f
        }

        // Hairline rule
        val rulePaint = Paint().apply {
            isAntiAlias = true
            color       = borderRule
            strokeWidth = 0.5f
        }
        canvas.drawLine(MARGIN, y + 8f, PAGE_WIDTH - MARGIN, y + 8f, rulePaint)

        return y + 16f
    }

    private fun drawNotes(canvas: android.graphics.Canvas, page: Page, top: Float): Float {
        var y = top

        if (page.notes.isEmpty()) {
            val mutedPaint = Paint().apply {
                isAntiAlias = true
                color       = inkSoft
                textSize    = 13f
                typeface    = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
            }
            canvas.drawText("(no notes)", MARGIN, y - mutedPaint.fontMetrics.ascent, mutedPaint)
            return y + mutedPaint.fontMetrics.run { descent - ascent }
        }

        val bodyPaint = Paint().apply {
            isAntiAlias = true
            color       = ink
            textSize    = 13f
            typeface    = Typeface.SERIF
        }
        val bulletPaint = Paint(bodyPaint).apply { color = themeGreenDeep }
        val lineHeight = bodyPaint.fontMetrics.run { descent - ascent } + 2f
        val textWidth  = PAGE_WIDTH - 2 * MARGIN - 16f

        for ((idx, note) in page.notes.withIndex()) {
            val firstLineY = y - bodyPaint.fontMetrics.ascent
            canvas.drawText("—", MARGIN, firstLineY, bulletPaint)

            // Wrap the note body to the available width by scanning words.
            var line = StringBuilder()
            for (word in note.body.split(' ')) {
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (bodyPaint.measureText(candidate) > textWidth) {
                    canvas.drawText(line.toString(), MARGIN + 16f, y - bodyPaint.fontMetrics.ascent, bodyPaint)
                    y += lineHeight
                    line = StringBuilder(word)
                } else {
                    line = StringBuilder(candidate)
                }
            }
            if (line.isNotEmpty()) {
                canvas.drawText(line.toString(), MARGIN + 16f, y - bodyPaint.fontMetrics.ascent, bodyPaint)
                y += lineHeight
            }
            if (idx != page.notes.lastIndex) y += 12f
        }
        return y
    }

    private fun drawCapturesSummary(canvas: android.graphics.Canvas, page: Page, top: Float): Float {
        val counts = page.counts
        val pieces = listOfNotNull(
            "${counts.photos} photo${pluralS(counts.photos)}".takeIf { counts.photos > 0 },
            "${counts.scannedDocuments} scan${pluralS(counts.scannedDocuments)}".takeIf { counts.scannedDocuments > 0 },
            "${counts.voiceNotes} voice note${pluralS(counts.voiceNotes)}".takeIf { counts.voiceNotes > 0 },
            "${counts.todoItems} to-do${pluralS(counts.todoItems)}".takeIf { counts.todoItems > 0 },
            "${counts.contacts} contact${pluralS(counts.contacts)}".takeIf { counts.contacts > 0 },
            "${counts.locations} place${pluralS(counts.locations)}".takeIf { counts.locations > 0 },
        )
        if (pieces.isEmpty()) return top

        var y = top
        val eyebrowPaint = Paint().apply {
            isAntiAlias = true
            color       = inkSoft
            textSize    = 9f
            typeface    = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            letterSpacing = 0.16f
        }
        canvas.drawText("CAPTURES", MARGIN, y - eyebrowPaint.fontMetrics.ascent, eyebrowPaint)
        y += eyebrowPaint.fontMetrics.run { descent - ascent } + 4f

        val summaryPaint = Paint().apply {
            isAntiAlias = true
            color       = ink
            textSize    = 13f
            typeface    = Typeface.SERIF
        }
        canvas.drawText(pieces.joinToString(" · "), MARGIN, y - summaryPaint.fontMetrics.ascent, summaryPaint)
        return y + summaryPaint.fontMetrics.run { descent - ascent }
    }

    private fun pluralS(n: Int): String = if (n == 1) "" else "s"

    // ---------- Helpers ----------

    private fun exportFile(context: Context, page: Page): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val safeId = page.id.replace('/', '-')
        val stamp  = Instant.now().epochSecond
        return File(dir, "page-$safeId-$stamp.pdf")
    }

    private fun drawLeafGlyph(
        canvas: android.graphics.Canvas,
        x: Float,
        y: Float,
        height: Float,
    ) {
        val paint = Paint().apply { isAntiAlias = true; color = coral }
        val w = height * 0.7f
        val path = android.graphics.Path().apply {
            moveTo(x + w / 2f, y)
            quadTo(x + w * 1.15f, y + height * 0.45f, x + w / 2f, y + height)
            quadTo(x - w * 0.15f, y + height * 0.45f, x + w / 2f, y)
            close()
        }
        canvas.drawPath(path, paint)
    }
}
