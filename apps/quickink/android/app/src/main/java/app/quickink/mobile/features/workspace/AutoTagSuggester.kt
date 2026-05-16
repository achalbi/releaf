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
     * auto-create generic time tags unprompted (brief §5). When the
     * rule pass yields fewer than [TARGET_SUGGESTIONS] hits we top
     * up with the most frequent meaningful words from the OCR text
     * so generic scans (no invoice/receipt/contract keywords) still
     * surface chips.
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

        if (hits.size < TARGET_SUGGESTIONS) {
            val exclude = hits + currentlyAttached
            topKeywords(text, TARGET_SUGGESTIONS - hits.size, exclude).forEach {
                hits += it
            }
        }

        // Subtract anything already attached so the chip strip
        // doesn't surface noise.
        return hits.filter { it !in currentlyAttached }
    }

    private const val TARGET_SUGGESTIONS = 12

    /**
     * Top-frequency words from [lowerText] minus stopwords, short
     * tokens (< 4 chars), pure digits, and anything already in
     * [exclude]. Ties broken alphabetically so the order is stable.
     */
    private fun topKeywords(
        lowerText: String,
        limit: Int,
        exclude: Set<String>,
    ): List<String> {
        if (limit <= 0) return emptyList()
        val counts = HashMap<String, Int>()
        lowerText.split(LETTER_SPLIT).forEach { token ->
            if (token.length >= 4 && token !in stopwords && token !in exclude) {
                counts[token] = (counts[token] ?: 0) + 1
            }
        }
        return counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(limit)
            .map { it.key }
    }

    private val LETTER_SPLIT = kotlin.text.Regex("[^a-z]+")

    /**
     * Common English words plus document-scaffolding noise we never
     * want to surface as tag suggestions. Kept inline rather than a
     * resource file because the list is short and rarely changes.
     */
    private val stopwords: Set<String> = setOf(
        "about","across","after","again","against","all","also","although","always","another",
        "any","anyone","anything","anywhere","are","around","because","been","before","behind",
        "being","below","beside","between","both","each","either","ever","every","everyone",
        "everything","everywhere","from","have","having","here","hers","herself","himself",
        "into","its","itself","just","like","made","make","makes","many","more","most","much",
        "must","myself","never","next","none","nothing","once","only","other","others","ours",
        "ourselves","over","said","same","several","should","since","some","someone","something",
        "somewhere","still","such","take","taken","than","that","them","themselves","then",
        "there","these","they","this","those","through","thus","under","until","upon","very",
        "was","were","what","when","where","whether","which","while","whilst","who","whom",
        "whose","with","within","without","would","your","yours","yourself","yourselves",
        // Document scaffolding noise — never useful as tags.
        "page","pages","copy","copies","document","documents","file","files","scan","scans",
        "scanned","date","time","name","dear","sincerely","regards","subject","subjects",
    )

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
