/*
 * AutoTagSuggester.kt
 *
 * Phase E v1 — keyword-rule heuristics over OCR text. Returns a
 * ranked list of suggested tag names for a capture. No ML, no
 * server — just a hand-tuned static table of (regex|keyword) →
 * tag-name mappings. Cheap to run; ~15 rules.
 *
 * Trigger sites (Phase E.1):
 *   - [TagPickerSheet] surfaces these as an "AI-SUGGESTED" chip
 *     row when the capture has OCR text. Accepted chips write a
 *     `capture_tags` row with `source = "ai-suggested"`.
 *   - ScanReviewScreen integration is a follow-up — same suggester,
 *     different host.
 *
 * Rules are case-insensitive on the OCR side. Tag names are
 * already normalised to lowercase + hyphens by the picker (see
 * [normalizeTagName]) so the rule's emitted name lands in the
 * canonical form.
 *
 * Mirror of `AutoTagSuggester.swift` (iOS Phase E pass).
 */

package app.quickink.mobile.features.workspace

object AutoTagSuggester {

    /**
     * One suggestion rule. Match modes:
     *   - [Rule.Keyword]: case-insensitive substring match.
     *   - [Rule.Phrase]:  case-insensitive substring against any
     *                     of the listed phrases; first hit wins.
     *   - [Rule.Regex]:   raw regex match, case-insensitive.
     */
    sealed class Rule(val tag: String) {
        class Keyword(tag: String, val word: String) : Rule(tag)
        class Phrase(tag: String, val phrases: List<String>) : Rule(tag)
        class Regex(tag: String, val pattern: kotlin.text.Regex) : Rule(tag)
    }

    /**
     * Hand-tuned doc-type rules. Order matters only for ties —
     * dedupe happens at the end. Keep this list under ~20 entries
     * so a maintainer can hold the whole table in their head.
     */
    private val docTypeRules: List<Rule> = listOf(
        // Invoices / billing
        Rule.Phrase(
            tag     = "invoice",
            phrases = listOf("invoice", "amount due", "bill to", "tax invoice"),
        ),
        Rule.Phrase(
            tag     = "receipt",
            phrases = listOf("receipt", "thank you for your purchase", "subtotal"),
        ),
        Rule.Phrase(
            tag     = "paid",
            phrases = listOf("paid in full", "payment received", "transaction complete"),
        ),
        Rule.Phrase(
            tag     = "overdue",
            phrases = listOf("past due", "overdue", "remit promptly"),
        ),

        // Contracts / legal
        Rule.Phrase(
            tag     = "contract",
            phrases = listOf(
                "this agreement",
                "msa",
                "master services agreement",
                "nda",
                "non-disclosure",
                "non disclosure",
                "letter of intent",
            ),
        ),
        Rule.Phrase(
            tag     = "signed",
            phrases = listOf("signed by", "/s/", "executed by", "duly authorized"),
        ),
        Rule.Phrase(
            tag     = "unsigned",
            phrases = listOf(
                "signature page intentionally left blank",
                "sign here",
                "to be signed",
            ),
        ),
        Rule.Phrase(
            tag     = "legal",
            phrases = listOf("attorney-client", "governing law", "jurisdiction shall"),
        ),

        // Meetings / notes
        Rule.Phrase(
            tag     = "meeting",
            phrases = listOf("meeting notes", "agenda", "action items", "attendees"),
        ),

        // Business cards
        Rule.Regex(
            tag     = "business-card",
            // Cheap heuristic: short line with first.last@domain
            // alongside a phone-y string. ML Kit's card pipeline
            // catches the strong cases; this picks up uncategorized
            // captures that look like cards.
            pattern = kotlin.text.Regex(
                """([A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}).*?(\+?\d[\d \-()]{6,})""",
                kotlin.text.RegexOption.DOT_MATCHES_ALL,
            ),
        ),
    )

    /** Vendor-name rules — fire on common provider names. Useful
     *  when those tags already exist for the user (we cross-check
     *  against `existingTagNames` in [suggest]) so we don't pollute
     *  the user's tag set with unbidden names. */
    private val vendorRules: List<Rule> = listOf(
        Rule.Keyword(tag = "aws",     word = "amazon web services"),
        Rule.Keyword(tag = "aws",     word = "aws"),
        Rule.Keyword(tag = "figma",   word = "figma"),
        Rule.Keyword(tag = "notion",  word = "notion"),
        Rule.Keyword(tag = "vercel",  word = "vercel"),
        Rule.Keyword(tag = "linear",  word = "linear app"),
        Rule.Keyword(tag = "stripe",  word = "stripe"),
        Rule.Keyword(tag = "github",  word = "github"),
        Rule.Keyword(tag = "google",  word = "google cloud"),
    )

    /**
     * Run rules over [ocrText] and return suggested tag names.
     * Auto-quarterly tags fire only when [existingTagNames]
     * already contains the matching `#q{N}-{YYYY}` — we don't
     * auto-create generic time tags unprompted (brief §5).
     */
    fun suggest(
        ocrText: String?,
        existingTagNames: Set<String>,
        currentlyAttached: Set<String>,
        captureDateIso: String? = null,
    ): List<String> {
        val text = ocrText.orEmpty().lowercase()
        if (text.isBlank()) return emptyList()

        val hits = linkedSetOf<String>()

        docTypeRules.forEach { rule ->
            if (matches(rule, text)) hits += rule.tag
        }

        // Vendor rules — only emit if the vendor tag already
        // exists in the user's namespace. Avoids leaking unsolicited
        // tag names into someone's tag list.
        vendorRules.forEach { rule ->
            if (matches(rule, text) && rule.tag in existingTagNames) {
                hits += rule.tag
            }
        }

        // Time heuristic — capture's createdAt quarter. Only
        // suggest when the user already has the matching tag.
        captureDateIso?.let { iso ->
            val quarterTag = quarterTagFor(iso) ?: return@let
            if (quarterTag in existingTagNames) hits += quarterTag
        }

        // Subtract anything already attached so the chip strip
        // doesn't surface noise.
        return hits.filter { it !in currentlyAttached }
    }

    private fun matches(rule: Rule, lowerText: String): Boolean = when (rule) {
        is Rule.Keyword -> rule.word.lowercase() in lowerText
        is Rule.Phrase  -> rule.phrases.any { it.lowercase() in lowerText }
        is Rule.Regex   -> rule.pattern.containsMatchIn(lowerText)
    }

    /**
     * `#q{N}-{YYYY}` derived from an ISO timestamp. Returns null
     * for malformed input.
     */
    private fun quarterTagFor(iso: String): String? {
        val date = runCatching { java.time.Instant.parse(iso).atZone(java.time.ZoneId.systemDefault()).toLocalDate() }
            .getOrNull() ?: return null
        val q = (date.monthValue - 1) / 3 + 1
        return "q$q-${date.year}"
    }
}
