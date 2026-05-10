/*
 * BusinessCardExtractorTest.kt
 *
 * Golden-card unit tests for the shared
 * `app.releaf.shared.scan.businesscard.BusinessCardExtractor`. The
 * extractor itself lives in `:shared:scan`; the tests live here
 * because that's where the existing JUnit + serialization test
 * harness already exists.
 *
 * Each test constructs a synthetic `OcrBlock` list with realistic
 * bbox positions for a typical business-card layout and asserts the
 * extractor produces the expected fields. No network, no DB, no
 * mocks — pure unit tests of the heuristic pipeline.
 */

package app.quickink.mobile.scan

import app.releaf.shared.scan.OcrBbox
import app.releaf.shared.scan.OcrBlock
import app.releaf.shared.scan.businesscard.BusinessCardExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BusinessCardExtractorTest {

    @Test fun emptyInput_yieldsEmptyContact() {
        val out = BusinessCardExtractor.extract(emptyList())
        assertNull(out.name)
        assertNull(out.company)
        assertEquals(0.0, out.confidence, 0.0001)
    }

    @Test fun typicalIndianCard_extractsAllFields() {
        // Layout (top → bottom):
        //   Name  (large, top)
        //   Designation (smaller, just below name — vocab hit)
        //   Company (medium, mid-top — suffix vocab hit)
        //   Phone (small, mid)
        //   Email (small, mid)
        //   Address line 1 (small, bottom, comma)
        //   Address line 2 (small, bottom, postcode)
        val blocks = listOf(
            block(0, "Aarav Sharma",          y = 0.05, h = 0.10),
            block(1, "Senior Software Engineer", y = 0.18, h = 0.06),
            block(2, "Acme Technologies Pvt Ltd", y = 0.30, h = 0.07),
            block(3, "+91 98765 43210",       y = 0.45, h = 0.04),
            block(4, "aarav@acme.tech",       y = 0.52, h = 0.04),
            block(5, "12, MG Road,",          y = 0.78, h = 0.04),
            block(6, "Bangalore 560001",      y = 0.85, h = 0.04),
        )

        val out = BusinessCardExtractor.extract(blocks)

        assertEquals("Aarav Sharma", out.name)
        assertEquals("Senior Software Engineer", out.designation)
        assertEquals("Acme Technologies Pvt Ltd", out.company)
        assertEquals(listOf("+919876543210"), out.phones)
        assertEquals(listOf("aarav@acme.tech"), out.emails)
        assertNotNull(out.address)
        assertTrue(out.address!!.contains("MG Road"))
        assertTrue(out.address!!.contains("560001"))
        assertTrue(out.confidence > 0.0)
    }

    @Test fun phoneNormalisation_handlesAllAcceptedFormats() {
        val cases = listOf(
            "9876543210"          to "9876543210",
            "98765 43210"         to "9876543210",
            "98765-43210"         to "9876543210",
            "(987) 654-3210"      to "9876543210",
            // Leading 0 (trunk prefix) is preserved verbatim — the
            // user wants the on-card form intact in the saved
            // contact, not silently rewritten.
            "09876543210"         to "09876543210",
            "+91 9876543210"      to "+919876543210",
            "+91-98765-43210"     to "+919876543210",
            "919876543210"        to "+919876543210",
        )
        for ((input, expected) in cases) {
            val blocks = listOf(block(0, input, y = 0.5, h = 0.05))
            val out = BusinessCardExtractor.extract(blocks)
            assertEquals(
                "expected '$expected' for input '$input', got ${out.phones}",
                listOf(expected),
                out.phones,
            )
        }
    }

    @Test fun phoneNormalisation_rejectsInvalidLengths() {
        val invalid = listOf(
            "12345",            // too short
            "123456789",        // 9 digits
            "98765 43210 12",   // 12 digits w/o 91 prefix
            "1234567890123456", // way too long
        )
        for (input in invalid) {
            val blocks = listOf(block(0, "Phone: $input", y = 0.5, h = 0.05))
            val out = BusinessCardExtractor.extract(blocks)
            assertTrue("Did not expect to parse '$input' but got ${out.phones}", out.phones.isEmpty())
        }
    }

    @Test fun multiplePhones_areExtractedAndDeduped() {
        val blocks = listOf(
            block(0, "Cell: 9876543210 / Office: 9988776655", y = 0.5, h = 0.04),
            block(1, "Mobile: +91 9876543210", y = 0.55, h = 0.04),  // dup
        )
        val out = BusinessCardExtractor.extract(blocks)
        assertTrue(out.phones.contains("9876543210") || out.phones.contains("+919876543210"))
        assertTrue(out.phones.contains("9988776655"))
    }

    @Test fun email_isLowerCasedAndDeduped() {
        val blocks = listOf(
            block(0, "AARAV@ACME.TECH",          y = 0.4, h = 0.04),
            block(1, "Email: aarav@acme.tech",   y = 0.45, h = 0.04),
        )
        val out = BusinessCardExtractor.extract(blocks)
        assertEquals(1, out.emails.size)
        assertEquals("aarav@acme.tech", out.emails.first())
    }

    @Test fun designationVocab_picksTitleEvenWhenLayoutIsAmbiguous() {
        val blocks = listOf(
            block(0, "Some Generic Heading", y = 0.05, h = 0.10),
            block(1, "Vice President of Engineering", y = 0.20, h = 0.04),
            block(2, "+91 9876543210", y = 0.50, h = 0.04),
        )
        val out = BusinessCardExtractor.extract(blocks)
        assertEquals("Vice President of Engineering", out.designation)
    }

    @Test fun company_suffixHitWinsOverNamePosition() {
        // Block at top is large but contains suffix → company candidate
        // beats name candidate for that block.
        val blocks = listOf(
            block(0, "ACME TECHNOLOGIES PVT LTD", y = 0.05, h = 0.12),
            block(1, "Aarav Sharma",              y = 0.20, h = 0.06),
            block(2, "Director",                  y = 0.27, h = 0.04),
        )
        val out = BusinessCardExtractor.extract(blocks)
        assertEquals("ACME TECHNOLOGIES PVT LTD", out.company)
        assertEquals("Aarav Sharma", out.name)
        assertEquals("Director", out.designation)
    }

    @Test fun website_recognised_inAllForms() {
        val blocks = listOf(
            block(0, "https://acme.tech", y = 0.5, h = 0.04),
            block(1, "www.acme.in", y = 0.55, h = 0.04),
            block(2, "acme.com", y = 0.60, h = 0.04),
        )
        val out = BusinessCardExtractor.extract(blocks)
        // All three should appear (deduped if identical, but they're not).
        assertTrue(out.websites.any { it.contains("acme.tech") })
        assertTrue(out.websites.any { it.contains("acme.in") })
        assertTrue(out.websites.any { it.contains("acme.com") })
    }

    @Test fun address_clustersAdjacentBottomLines() {
        val blocks = listOf(
            block(0, "Aarav Sharma",                y = 0.05, h = 0.10),
            block(1, "Director",                    y = 0.18, h = 0.05),
            block(2, "12, MG Road, Indiranagar,",   y = 0.75, h = 0.04),
            block(3, "Bangalore, KA 560038",        y = 0.81, h = 0.04),
        )
        val out = BusinessCardExtractor.extract(blocks)
        assertNotNull(out.address)
        // Multi-line — both lines joined with newline.
        assertTrue(out.address!!.contains("\n"))
        assertTrue(out.address!!.contains("MG Road"))
        assertTrue(out.address!!.contains("560038"))
    }

    @Test fun emailHostNotMisidentifiedAsBareDomainWebsite() {
        val blocks = listOf(
            block(0, "aarav@acme.com", y = 0.5, h = 0.04),
        )
        val out = BusinessCardExtractor.extract(blocks)
        assertEquals(listOf("aarav@acme.com"), out.emails)
        // The "acme.com" inside the email shouldn't surface as a website.
        assertTrue(out.websites.isEmpty())
    }

    @Test fun mlKitDualEmission_paragraphBlockDoesNotPoisonClassifiers() {
        // ML Kit Latin v2 emits BOTH paragraph- and line-grained
        // blocks for every text region. The paragraph block is a
        // multi-line concatenation that covers a giant bbox and
        // contains every keyword on the card — without filtering to
        // Line, every classifier scores it sky-high (largeText
        // bonus + companySuffix hit + topPosition + …) and the
        // user gets the full card text as the company / name /
        // address. Filter must keep the result identical to the
        // line-only baseline.
        val lineBlocks = listOf(
            block(0, "Aarav Sharma",                y = 0.05, h = 0.10),
            block(1, "Senior Software Engineer",    y = 0.18, h = 0.06),
            block(2, "Acme Technologies Pvt Ltd",   y = 0.30, h = 0.07),
            block(3, "+91 98765 43210",             y = 0.45, h = 0.04),
            block(4, "aarav@acme.tech",             y = 0.52, h = 0.04),
            block(5, "12, MG Road,",                y = 0.78, h = 0.04),
            block(6, "Bangalore 560001",            y = 0.85, h = 0.04),
        )
        // Synthetic paragraph block — same content concatenated,
        // larger bbox spanning the whole text region.
        val paragraphBlock = OcrBlock(
            text       = lineBlocks.joinToString("\n") { it.text },
            bbox       = OcrBbox(x = 0.05, y = 0.05, width = 0.6, height = 0.85),
            confidence = null,
            language   = "en",
            kind       = OcrBlock.Kind.Paragraph,
        )
        val withParagraph = listOf(paragraphBlock) + lineBlocks
        val out = BusinessCardExtractor.extract(withParagraph)

        // Same output as the line-only baseline — paragraph filtered out.
        assertEquals("Aarav Sharma", out.name)
        assertEquals("Senior Software Engineer", out.designation)
        assertEquals("Acme Technologies Pvt Ltd", out.company)
        assertEquals(listOf("+919876543210"), out.phones)
        assertEquals(listOf("aarav@acme.tech"), out.emails)
    }

    @Test fun categoryNameStringsAreNeverSurfacedAsFields() {
        // OCR sometimes picks up the words "Business Card" off the
        // card itself (sample / template cards literally print it,
        // and ML Kit can also stitch the ink-on-paper title that
        // way). Without a stop-word filter the line trips the
        // designation vocab ("business") and lands as the
        // designation field — exactly the wrong thing to surface
        // since it's the category name, not a designation.
        val variations = listOf(
            "Business Card",
            "BUSINESS CARD",
            "business card",
            "Business-Card",
            "businesscard",
            "Business Card.",
            "Card",
            "card",
            "8usiness Card",   // OCR error
        )
        for (text in variations) {
            val blocks = listOf(
                block(0, "Aarav Sharma",        y = 0.05, h = 0.10),
                block(1, text,                  y = 0.18, h = 0.04),  // the offender
                block(2, "Acme Pvt Ltd",        y = 0.30, h = 0.06),
                block(3, "+91 9876543210",      y = 0.45, h = 0.04),
            )
            val out = BusinessCardExtractor.extract(blocks)
            assertEquals("Aarav Sharma", out.name)
            assertEquals("Acme Pvt Ltd", out.company)
            // Designation MUST NOT be the category-name string.
            assertTrue(
                "designation should not contain '$text', got '${out.designation}'",
                out.designation == null ||
                    !out.designation!!.lowercase().filter { it.isLetterOrDigit() }
                        .let { stripped ->
                            stripped == "businesscard" ||
                            stripped == "card" ||
                            stripped == "business" ||
                            stripped == "8usinesscard" ||
                            stripped == "8usiness"
                        }
            )
        }
    }

    @Test fun nameDirectlyAboveDesignation_winsOverIsolatedBigFontElsewhere() {
        // Two equally-plausible name candidates:
        //   - Block 0: a big-font line up top with no designation
        //              directly below it (no adjacency bonus).
        //   - Block 2: a smaller line followed by a designation
        //              directly underneath (adjacency bonus +4 for
        //              name AND for designation).
        // Without the adjacency bonus block 0 wins by virtue of
        // being bigger and at the top. With the bonus block 2 wins
        // because the (name, designation) pair is the dominant
        // pattern on real cards.
        val blocks = listOf(
            // Big-font line at the top — no designation under it.
            block(0, "ACME RESEARCH LABS",        y = 0.05, h = 0.10),
            // Some random spacer block.
            block(1, "estd. 1998",                y = 0.18, h = 0.04),
            // The actual name + designation pair, smaller font.
            block(2, "Aarav Sharma",              y = 0.35, h = 0.05),
            // Designation immediately below — adjacency gap < 0.10.
            block(3, "Senior Software Engineer",  y = 0.42, h = 0.04),
            block(4, "+91 9876543210",            y = 0.55, h = 0.04),
        )
        val out = BusinessCardExtractor.extract(blocks)
        assertEquals("Aarav Sharma", out.name)
        assertEquals("Senior Software Engineer", out.designation)
    }

    @Test fun salutation_anchorsNameSelectionEvenWhenLayoutFavoursAnotherBlock() {
        // Block 0 is the bigger / topmost line — without the
        // salutation bonus it would win NAME on layout alone.
        // Block 2 has "Mr." — should win regardless because that's
        // a near-positive identification of a name line.
        val blocks = listOf(
            block(0, "Acme Studios",       y = 0.05, h = 0.10),  // big, top — wordmark
            block(1, "Founder",            y = 0.18, h = 0.05),
            block(2, "Mr. Aarav Sharma",   y = 0.28, h = 0.06),  // smaller — but salutation
        )
        val out = BusinessCardExtractor.extract(blocks)
        // Salutation pulls block 2 to the top of the NAME pool.
        assertEquals("Mr. Aarav Sharma", out.name)
    }

    @Test fun salutation_recognisedAcrossCommonForms() {
        val variants = listOf(
            "Mr Aarav Sharma",
            "Mr. Aarav Sharma",
            "Mrs. Priya Patel",
            "Ms Riya Mehta",
            "Dr. Kavya Iyer",
            "Sri Vikram Singh",
            "Smt. Lata Verma",
            "Prof. R.K. Narayan",
        )
        for (text in variants) {
            val blocks = listOf(
                block(0, "Acme Pvt Ltd",  y = 0.05, h = 0.12),
                block(1, text,            y = 0.30, h = 0.05),
                block(2, "Director",      y = 0.38, h = 0.04),
            )
            val out = BusinessCardExtractor.extract(blocks)
            assertEquals("expected name from '$text'", text, out.name)
        }
    }

    @Test fun biggestFontFavoursCompanyOverName() {
        // No suffix vocab match on either block — both COMPANY
        // candidates rely on layout alone. The biggest-text penalty
        // for NAME nudges the largest block to COMPANY.
        val blocks = listOf(
            // Big-font wordmark at the top — no "Pvt Ltd" suffix.
            block(0, "QuickInk",         y = 0.05, h = 0.12),
            // Smaller name + designation pair below.
            block(1, "Aarav Sharma",     y = 0.28, h = 0.06),
            block(2, "Engineer",         y = 0.36, h = 0.05),
        )
        val out = BusinessCardExtractor.extract(blocks)
        assertEquals("QuickInk", out.company)
        assertEquals("Aarav Sharma", out.name)
        assertEquals("Engineer", out.designation)
    }

    @Test fun fallsBackToWhateverEngineEmitsWhenNoLineBlocks() {
        // Defensive: an engine that emits only paragraph-grained
        // (or word-grained) blocks shouldn't return empty — fall
        // back to whatever's there.
        val blocks = listOf(
            OcrBlock(
                text       = "Aarav Sharma",
                bbox       = OcrBbox(x = 0.05, y = 0.05, width = 0.6, height = 0.10),
                confidence = null,
                language   = "en",
                kind       = OcrBlock.Kind.Paragraph,
            ),
        )
        val out = BusinessCardExtractor.extract(blocks)
        assertEquals("Aarav Sharma", out.name)
    }

    // -------- helpers --------

    /** Synthetic block at the supplied y / height. Width fixed at 0.6. */
    private fun block(
        index: Int,
        text: String,
        y: Double,
        h: Double,
    ): OcrBlock = OcrBlock(
        text       = text,
        bbox       = OcrBbox(x = 0.05, y = y, width = 0.6, height = h),
        confidence = null,                      // ML Kit Play-Services 19.0.1
        language   = "en",
        kind       = OcrBlock.Kind.Line,
    )
}
