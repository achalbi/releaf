/*
 * TemplateDetectionTest.kt
 *
 * Pure-JVM tests for printed-page template routing: the seed mirror
 * vs `page_templates.json`, the `quickink://` mark parser, both
 * detectors, zone extraction and the date parser. Mirror of iOS
 * `TemplateDetectionTests.swift`.
 */

package app.quickink.mobile.scan.templates

import app.quickink.mobile.features.scan.handwriting.OcrLine
import app.quickink.mobile.features.scan.templates.CompositeTemplateDetector
import app.quickink.mobile.features.scan.templates.DateParser
import app.quickink.mobile.features.scan.templates.HeaderWordsTemplateDetector
import app.quickink.mobile.features.scan.templates.MarkTemplateDetector
import app.quickink.mobile.features.scan.templates.PageZone
import app.quickink.mobile.features.scan.templates.PrintedPageTemplate
import app.quickink.mobile.features.scan.templates.PrintedPageTemplateRegistry
import app.quickink.mobile.features.scan.templates.PrintedPageTemplates
import app.quickink.mobile.features.scan.templates.TemplateFieldExtractor
import app.quickink.mobile.features.scan.templates.TemplateMark
import app.quickink.mobile.features.scan.templates.TemplateMatch
import app.quickink.mobile.features.scan.templates.TemplatePage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

class TemplateDetectionTest {

    private val registry = PrintedPageTemplateRegistry.default

    private fun line(i: Int, text: String, x: Double, y: Double, w: Double = 0.3, h: Double = 0.03) =
        OcrLine(i, text, x, y, w, h)

    // ─── Seed mirror ────────────────────────────────────────────────

    /** Walk up from the working dir to the repo's seed file. */
    private fun seedJson(): String {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val f = File(dir, "shared/design-system/seeds/page_templates.json")
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        error("page_templates.json not found above ${File("").absolutePath}")
    }

    @Test
    fun `hard-coded seed mirrors page_templates json`() {
        val parsed = PrintedPageTemplateRegistry.fromJson(seedJson()).templates
        assertEquals(PrintedPageTemplates.seed, parsed)
    }

    @Test
    fun `seed covers the default tags and seeded folders`() {
        assertEquals(listOf("meeting-notes", "journal", "todo", "ideas"), registry.templates.map { it.id })
        assertEquals(listOf("meetings", "journal", "todo", "ideas"), registry.templates.map { it.tags.first() })
        val seededFolders = setOf(
            "inbox", "archive", "finance", "medical", "family", "travel",
            "events", "legal", "lifestyle", "learning", "projects", "ideas",
        )
        assertTrue(registry.templates.all { it.folderId in seededFolders })
    }

    @Test
    fun `fromJson skips incomplete templates and defaults optional fields`() {
        val reg = PrintedPageTemplateRegistry.fromJson(
            """{"templates":[{"id":"x"},{"id":"a","name":"A","extra":1}]}""",
        )
        val a = reg.templates.single()
        assertEquals("a", a.id)
        assertNull(a.titleZone)
        assertFalse(a.hasCheckboxColumn)
        assertEquals(PageZone.TOP_BAND, a.headerZone)
    }

    // ─── Mark ───────────────────────────────────────────────────────

    @Test
    fun `mark parser accepts the assumed format`() {
        assertEquals(TemplateMark("meeting-notes", null), TemplateMark.parse("quickink://t/meeting-notes"))
        assertEquals(TemplateMark("journal", 42), TemplateMark.parse(" QUICKINK://t/Journal/p/42 "))
        assertEquals(TemplateMark("todo", 7), TemplateMark.parse("quickink://t/todo/p/7/"))
    }

    @Test
    fun `mark parser rejects other payloads`() {
        for (p in listOf(
            "https://quickink.app/t/todo", "quickink://x/todo", "quickink://t/",
            "quickink://t/todo/p/", "quickink://t/todo/p/abc", "quickink://t/../etc", "",
        )) {
            assertNull(p, TemplateMark.parse(p))
        }
    }

    @Test
    fun `mark detector resolves known ids and ignores unknown ones`() {
        val det = MarkTemplateDetector(registry)
        val hit = det.detect(TemplatePage(emptyList(), listOf("https://example.com", "quickink://t/ideas/p/3")))!!
        assertEquals("ideas", hit.template.id)
        assertEquals(TemplateMatch.Method.Mark, hit.method)
        assertEquals(3, hit.pageNumber)
        assertNull(det.detect(TemplatePage(emptyList(), listOf("quickink://t/future-template"))))
    }

    // ─── Header words ───────────────────────────────────────────────

    @Test
    fun `header detector matches printed header in the top band`() {
        val page = TemplatePage(listOf(line(0, "QuickInk · Meeting Notes", 0.05, 0.02, 0.5)))
        val hit = HeaderWordsTemplateDetector(registry).detect(page)!!
        assertEquals("meeting-notes", hit.template.id)
        assertEquals(TemplateMatch.Method.HeaderWords, hit.method)
    }

    @Test
    fun `header detector normalises to-do spellings`() {
        for (text in listOf("QUICKINK  TO-DO", "QuickInk To Do", "quickink todo")) {
            val hit = HeaderWordsTemplateDetector(registry).detect(TemplatePage(listOf(line(0, text, 0.05, 0.02))))
            assertEquals(text, "todo", hit?.template?.id)
        }
    }

    @Test
    fun `handwritten heading without the printed brand word is not a template`() {
        val page = TemplatePage(listOf(line(0, "Meeting notes", 0.05, 0.02)))
        assertNull(HeaderWordsTemplateDetector(registry).detect(page))
    }

    @Test
    fun `header words below the header band are ignored`() {
        val page = TemplatePage(listOf(line(0, "QuickInk Journal", 0.05, 0.5)))
        assertNull(HeaderWordsTemplateDetector(registry).detect(page))
    }

    @Test
    fun `ambiguous header matches resolve to no template`() {
        val page = TemplatePage(listOf(line(0, "QuickInk Journal Ideas", 0.05, 0.02)))
        assertNull(HeaderWordsTemplateDetector(registry).detect(page))
    }

    @Test
    fun `composite prefers the mark over header words`() {
        val page = TemplatePage(
            lines = listOf(line(0, "QuickInk Journal", 0.05, 0.02)),
            barcodePayloads = listOf("quickink://t/todo"),
        )
        val hit = CompositeTemplateDetector.standard(registry).detect(page)!!
        assertEquals("todo", hit.template.id)
        assertEquals(TemplateMatch.Method.Mark, hit.method)
    }

    // ─── Zones ──────────────────────────────────────────────────────

    @Test
    fun `title and date come from lines centred in their zones`() {
        val t = registry.byId("meeting-notes")!!
        val lines = listOf(
            line(0, "QuickInk Meeting Notes", 0.05, 0.02, 0.5),
            line(1, "Topic: Q4 roadmap", 0.10, 0.09, 0.40),
            line(2, "review", 0.52, 0.09, 0.10),
            line(3, "Date: 14/10/2026", 0.72, 0.09, 0.20),
            line(4, "Attendees: Sam, Priya", 0.10, 0.30, 0.50),
        )
        val f = TemplateFieldExtractor.extract(t, lines)
        assertEquals("Q4 roadmap review", f.title)
        assertEquals("14/10/2026", f.dateText)
        assertEquals("2026-10-14", f.dateIso)
    }

    @Test
    fun `empty zones yield nulls`() {
        val f = TemplateFieldExtractor.extract(registry.byId("journal")!!, listOf(line(0, "body", 0.1, 0.5)))
        assertNull(f.title)
        assertNull(f.dateText)
        assertNull(f.dateIso)
    }

    @Test
    fun `template without zones extracts nothing`() {
        val bare = PrintedPageTemplate("x", "X", null, emptyList(), null, null, false)
        assertEquals(null, TemplateFieldExtractor.extract(bare, listOf(line(0, "a", 0.1, 0.1))).title)
    }

    // ─── Dates ──────────────────────────────────────────────────────

    @Test
    fun `date parser handles common handwritten formats`() {
        val cases = mapOf(
            "2026-10-14" to "2026-10-14",
            "14/10/2026" to "2026-10-14",
            "14.10.26" to "2026-10-14",
            "4-3-2026" to "2026-03-04",
            "10/14/2026" to "2026-10-14", // second number can't be a month → US order
            "14 Oct 2026" to "2026-10-14",
            "14th October, 2026" to "2026-10-14",
            "Oct 14, 2026" to "2026-10-14",
            "Sept 3 2026" to "2026-09-03",
            "Tue 14 Oct 26" to "2026-10-14",
        )
        for ((input, expected) in cases) assertEquals(input, expected, DateParser.parseIso(input))
    }

    @Test
    fun `date parser rejects non-dates and impossible dates`() {
        for (input in listOf("", "Q4 roadmap", "31/02/2026", "14 Mayor 2026", "2026-13-01", "call at 10.30")) {
            assertNull(input, DateParser.parseIso(input))
        }
    }

    @Test
    fun `entry date plausibility window`() {
        val today = LocalDate.of(2026, 10, 14)
        assertTrue(DateParser.isPlausibleEntryDate("2026-10-14", today))
        assertTrue(DateParser.isPlausibleEntryDate("2026-10-15", today))
        assertTrue(DateParser.isPlausibleEntryDate("2022-01-01", today))
        assertFalse(DateParser.isPlausibleEntryDate("2026-10-20", today))
        assertFalse(DateParser.isPlausibleEntryDate("2019-01-01", today))
        assertFalse(DateParser.isPlausibleEntryDate("garbage", today))
    }
}
