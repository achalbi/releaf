/*
 * TemplateDetection.kt
 *
 * How a scanned page is matched to a printed QuickInk template, and
 * how the template's title / date are read off it.
 *
 * ASSUMPTION — nothing in the repo specifies how a printed page
 * identifies itself. This code assumes a QR code in a page corner
 * encoding `quickink://t/<templateId>` with an optional
 * `/p/<pageNumber>` (see `page_templates.json` → `_meta.mark_format`).
 * It needs confirming against the printed-book design. The detector
 * is an interface so a different mark (or none) swaps in without
 * touching the scan flow:
 *
 *   - [MarkTemplateDetector]       reads the printed mark. The QR is
 *     decoded on-device by the platform (ML Kit barcode scanning on
 *     Android, Vision on iOS) and handed in as raw payload strings,
 *     which keeps this file pure and unit-testable.
 *   - [HeaderWordsTemplateDetector] fallback: the template's printed
 *     header words found in the OCR text near the top of the page.
 *   - [CompositeTemplateDetector]  first match wins.
 *
 * Mirror of iOS `TemplateDetection.swift`.
 */

package app.quickink.mobile.features.scan.templates

import app.quickink.mobile.features.scan.handwriting.OcrLine
import java.time.DateTimeException
import java.time.LocalDate

// ─── Printed mark ───────────────────────────────────────────────────

/** Parsed `quickink://t/<templateId>[/p/<pageNumber>]` payload. */
data class TemplateMark(val templateId: String, val pageNumber: Int?) {
    companion object {
        private val PATTERN = Regex(
            """^quickink://t/([a-z0-9][a-z0-9_-]{0,63})(?:/p/(\d{1,6}))?/?$""",
            RegexOption.IGNORE_CASE,
        )

        /** Null for anything that isn't a QuickInk template mark. */
        fun parse(payload: String): TemplateMark? {
            val m = PATTERN.find(payload.trim()) ?: return null
            return TemplateMark(
                templateId = m.groupValues[1].lowercase(),
                pageNumber = m.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull(),
            )
        }
    }
}

// ─── Detector interface ─────────────────────────────────────────────

/** Everything a detector may look at for one page. */
data class TemplatePage(
    val lines: List<OcrLine>,
    /** Raw barcode payloads decoded on-device from the page image. */
    val barcodePayloads: List<String> = emptyList(),
)

data class TemplateMatch(
    val template: PrintedPageTemplate,
    val method: Method,
    /** Printed page number when the mark carried one. */
    val pageNumber: Int? = null,
) {
    enum class Method { Mark, HeaderWords }
}

fun interface PageTemplateDetector {
    fun detect(page: TemplatePage): TemplateMatch?
}

/** Reads the printed `quickink://t/…` mark. Unknown template ids → no match. */
class MarkTemplateDetector(private val registry: PrintedPageTemplateRegistry) : PageTemplateDetector {
    override fun detect(page: TemplatePage): TemplateMatch? {
        for (payload in page.barcodePayloads) {
            val mark = TemplateMark.parse(payload) ?: continue
            val template = registry.byId(mark.templateId) ?: continue
            return TemplateMatch(template, TemplateMatch.Method.Mark, mark.pageNumber)
        }
        return null
    }
}

/**
 * Fallback: a template's printed header phrase found in the OCR text
 * of lines whose centre lies in the template's header band. Every
 * word of the phrase must be present (order-free, case- and
 * punctuation-insensitive). The longest matching phrase wins; a tie
 * between templates is treated as no match rather than a guess.
 */
class HeaderWordsTemplateDetector(private val registry: PrintedPageTemplateRegistry) : PageTemplateDetector {
    override fun detect(page: TemplatePage): TemplateMatch? {
        var best: PrintedPageTemplate? = null
        var bestLen = 0
        var tie = false
        for (template in registry.templates) {
            val words = page.lines
                .filter { template.headerZone.contains(it.centerX, it.centerY) }
                .flatMap { tokens(it.text) }
                .toSet()
            if (words.isEmpty()) continue
            for (phrase in template.headerPhrases) {
                val need = tokens(phrase)
                if (need.size < 2 || !words.containsAll(need)) continue
                when {
                    need.size > bestLen -> { best = template; bestLen = need.size; tie = false }
                    need.size == bestLen && best?.id != template.id -> tie = true
                }
            }
        }
        val match = best ?: return null
        return if (tie) null else TemplateMatch(match, TemplateMatch.Method.HeaderWords)
    }

    companion object {
        /** Lowercase word tokens; hyphens / apostrophes collapse ("To-Do" → "todo"). */
        fun tokens(text: String): List<String> =
            text.lowercase()
                .replace(Regex("""[-'’]"""), "")
                .split(Regex("""[^\p{L}\p{N}]+"""))
                .filter { it.isNotEmpty() }
    }
}

class CompositeTemplateDetector(private val detectors: List<PageTemplateDetector>) : PageTemplateDetector {
    override fun detect(page: TemplatePage): TemplateMatch? = detectors.firstNotNullOfOrNull { it.detect(page) }

    companion object {
        /** Mark first, header words second. */
        fun standard(registry: PrintedPageTemplateRegistry = PrintedPageTemplateRegistry.default) =
            CompositeTemplateDetector(listOf(MarkTemplateDetector(registry), HeaderWordsTemplateDetector(registry)))
    }
}

// ─── Title / date zones ─────────────────────────────────────────────

data class TemplateFields(
    val title: String?,
    /** Raw text found in the date zone (label stripped). */
    val dateText: String?,
    /** `yyyy-MM-dd` when [dateText] parsed, else null. */
    val dateIso: String?,
)

object TemplateFieldExtractor {

    private const val MAX_TITLE_CHARS = 80
    private val LABEL = Regex("""^\s*(title|topic|subject|date|day)\s*[:\-–.]\s*""", RegexOption.IGNORE_CASE)

    fun extract(template: PrintedPageTemplate, lines: List<OcrLine>): TemplateFields {
        val title = template.titleZone?.let { zoneText(it, lines) }
            ?.take(MAX_TITLE_CHARS)?.trim()?.takeIf { it.isNotEmpty() }
        val dateText = template.dateZone?.let { zoneText(it, lines) }?.takeIf { it.isNotEmpty() }
        return TemplateFields(title, dateText, dateText?.let { DateParser.parseIso(it) })
    }

    /** Lines whose box centre is in [zone], in reading order, label stripped. */
    fun zoneText(zone: PageZone, lines: List<OcrLine>): String =
        lines.filter { zone.contains(it.centerX, it.centerY) }
            .sortedWith(compareBy({ it.y }, { it.x }))
            .joinToString(" ") { it.text.trim() }
            .replaceFirst(LABEL, "")
            .replace(Regex("""\s+"""), " ")
            .trim()
}

/**
 * Lenient handwritten-date parser → `yyyy-MM-dd`. Accepts
 * `2026-10-14`, `14/10/2026`, `14.10.26`, `14 Oct 2026`,
 * `Oct 14, 2026`, `14th October 2026`. Numeric dates are read
 * day-first (the common format outside the US) unless the first
 * number can't be a month and the second can't be a day. Two-digit
 * years are 20xx.
 */
object DateParser {
    private val YMD = Regex("""(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})""")
    private val DMY = Regex("""(\d{1,2})[-/.](\d{1,2})[-/.](\d{2,4})""")
    private val D_MON_Y = Regex("""(\d{1,2})(?:st|nd|rd|th)?\s+([a-z]{3,9})\.?,?\s+(\d{2,4})""", RegexOption.IGNORE_CASE)
    private val MON_D_Y = Regex("""([a-z]{3,9})\.?\s+(\d{1,2})(?:st|nd|rd|th)?,?\s+(\d{2,4})""", RegexOption.IGNORE_CASE)
    private val MONTHS = listOf(
        "january", "february", "march", "april", "may", "june",
        "july", "august", "september", "october", "november", "december",
    )

    fun parseIso(text: String): String? {
        YMD.find(text)?.let { m ->
            return iso(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
        }
        DMY.find(text)?.let { m ->
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            val y = year(m.groupValues[3])
            return if (b > 12 && a <= 12) iso(y, a, b) else iso(y, b, a)
        }
        D_MON_Y.find(text)?.let { m ->
            val month = month(m.groupValues[2]) ?: return@let
            return iso(year(m.groupValues[3]), month, m.groupValues[1].toInt())
        }
        MON_D_Y.find(text)?.let { m ->
            val month = month(m.groupValues[1]) ?: return@let
            return iso(year(m.groupValues[3]), month, m.groupValues[2].toInt())
        }
        return null
    }

    /**
     * Whether a parsed page date is sensible to file the scan under:
     * not more than a day in the future (clock skew / timezones) and
     * not more than five years back (likely a misread).
     */
    fun isPlausibleEntryDate(iso: String, today: LocalDate): Boolean {
        val d = try { LocalDate.parse(iso) } catch (_: Exception) { return false }
        return !d.isAfter(today.plusDays(1)) && !d.isBefore(today.minusYears(5))
    }

    private fun year(raw: String): Int = raw.toInt().let { if (raw.length == 2) 2000 + it else it }

    /** Full name or any prefix of it ≥ 3 letters ("oct", "sept"); "mayor" is not May. */
    private fun month(raw: String): Int? {
        val key = raw.lowercase()
        if (key.length < 3) return null
        val i = MONTHS.indexOfFirst { it.startsWith(key) }
        return if (i < 0) null else i + 1
    }

    private fun iso(y: Int, m: Int, d: Int): String? = try {
        LocalDate.of(y, m, d).toString()
    } catch (_: DateTimeException) {
        null
    }
}
