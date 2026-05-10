/*
 * ExtractionPipeline.kt
 *
 * Orchestrates the extraction flow: classifier fan-out → candidate
 * collection → field resolution → final [ExtractedContact].
 *
 * The pipeline is the only piece that knows the shape of the output
 * — every classifier returns a flat candidate list, and the pipeline
 * decides which candidates win. This keeps the classifier interface
 * simple (single method, single return type) and lets the resolver
 * apply cross-field rules (e.g. "if NAME and DESIGNATION fired on
 * the same block, drop the lower-scoring one").
 *
 * Address is special-cased: AddressClassifier needs the
 * PostcodeClassifier's results, so the pipeline runs postcodes
 * first, then constructs AddressClassifier with that index set.
 */

package app.releaf.shared.scan.businesscard.pipeline

import app.releaf.shared.scan.OcrBlock
import app.releaf.shared.scan.businesscard.ExtractedContact
import app.releaf.shared.scan.businesscard.ExtractionTrace
import app.releaf.shared.scan.businesscard.FieldCandidate
import app.releaf.shared.scan.businesscard.FieldKind
import app.releaf.shared.scan.businesscard.ScoringWeights
import app.releaf.shared.scan.businesscard.classifier.AddressClassifier
import app.releaf.shared.scan.businesscard.classifier.Classifier
import app.releaf.shared.scan.businesscard.classifier.CompanyClassifier
import app.releaf.shared.scan.businesscard.classifier.DesignationClassifier
import app.releaf.shared.scan.businesscard.classifier.EmailClassifier
import app.releaf.shared.scan.businesscard.classifier.NameClassifier
import app.releaf.shared.scan.businesscard.classifier.PhoneClassifier
import app.releaf.shared.scan.businesscard.classifier.PostcodeClassifier
import app.releaf.shared.scan.businesscard.classifier.UrlClassifier

class ExtractionPipeline(
    private val weights: ScoringWeights = ScoringWeights(),
    /**
     * Classifiers run on every pass. Default is the canonical set
     * minus AddressClassifier — that one is constructed inside
     * `extract` because it needs the PostcodeClassifier's output.
     * Callers wanting a different set (tests, benchmarks) can
     * supply their own list.
     */
    private val classifiers: List<Classifier> = listOf(
        EmailClassifier(),
        PhoneClassifier(),
        UrlClassifier(),
        PostcodeClassifier(),
        NameClassifier(),
        DesignationClassifier(),
        CompanyClassifier(),
    ),
) {

    /** Run the pipeline. Set [keepTrace] to populate `result.trace`. */
    fun extract(blocks: List<OcrBlock>, keepTrace: Boolean = false): ExtractedContact {
        if (blocks.isEmpty()) return ExtractedContact.empty
        val timings = mutableMapOf<String, Long>()

        // ML Kit emits both Paragraph- and Line-grained blocks for
        // the same text. The Paragraph block concatenates every line
        // it covers with `\n`s — which means it contains every
        // keyword on the card *and* spans a giant bbox, so every
        // classifier scores it sky-high (companySuffix hit ✓, name
        // top-position ✓, address postcode ✓ — all on the same
        // paragraph block). Filter to Line granularity when any
        // Line block is present; otherwise fall back to whatever the
        // engine gave us. iOS Vision only emits Line, so this is a
        // no-op there.
        val preferred = if (blocks.any { it.kind == OcrBlock.Kind.Line }) {
            blocks.filter { it.kind == OcrBlock.Kind.Line }
        } else {
            blocks
        }
        if (preferred.isEmpty()) return ExtractedContact.empty

        val tLayout = System.nanoTime()
        val layout = layoutOf(preferred)
        timings["layout"] = System.nanoTime() - tLayout

        // Run base classifiers.
        val tClass = System.nanoTime()
        val baseCandidates = classifiers.flatMap { it.classify(layout, weights) }
        timings["classifiers"] = System.nanoTime() - tClass

        // Run AddressClassifier with knowledge of which blocks
        // postcode-classified positively.
        val postcodeBlocks = baseCandidates
            .filter { it.kind == FieldKind.POSTCODE }
            .map { it.sourceBlockIndex }
            .toSet()
        val tAddress = System.nanoTime()
        val addressCandidates = AddressClassifier(postcodeBlocks).classify(layout, weights)
        timings["address"] = System.nanoTime() - tAddress

        val all = baseCandidates + addressCandidates

        // Drop candidates whose own text reads as the category
        // name (or one of its OCR-error aliases). On a Business
        // Card capture, ML Kit can OCR a line that literally says
        // "Business Card" — and that single line trips the
        // designation vocab ("business") + the company-suffix
        // ("card" isn't a suffix, but "business" can read as
        // intent), polluting whichever field's resolver picks it.
        // Removing these here keeps the classifiers vocabulary-
        // driven for legitimate hits ("Business Development
        // Manager") while still excluding the category-string-
        // as-field anti-pattern.
        val filtered = all.filterNot { it.text.normalisedForStopCheck() in CATEGORY_STOP_WORDS }

        // Resolve each field.
        val tResolve = System.nanoTime()
        val resolved = resolve(filtered, layout)
        timings["resolve"] = System.nanoTime() - tResolve

        return resolved.copy(
            trace = if (keepTrace) ExtractionTrace(all, timings) else null,
        )
    }

    /**
     * Pick the winning candidate(s) per field given the full pool.
     * Single-value fields (NAME / COMPANY / DESIGNATION / ADDRESS)
     * take the top-scoring candidate above the per-kind threshold;
     * multi-value fields (PHONE / EMAIL / WEBSITE) take all
     * candidates above their threshold, deduplicated.
     *
     * When the same block has competing single-value claims (e.g.
     * NAME and COMPANY both fired on the top-of-card text), the
     * higher-scoring kind wins that block and the other kinds skip
     * it. This avoids a name-vs-company battle being decided by
     * the priority order alone.
     */
    private fun resolve(
        candidates: List<FieldCandidate>,
        layout: LayoutContext,
    ): ExtractedContact {
        // Index candidates by kind for the per-kind selectors.
        val byKind = candidates.groupBy { it.kind }

        val emails = pickMulti(
            byKind[FieldKind.EMAIL].orEmpty(),
            min = weights.minEmailScore,
        )
        val phones = pickMulti(
            byKind[FieldKind.PHONE].orEmpty(),
            min = weights.minPhoneScore,
        )
        val websites = pickMulti(
            byKind[FieldKind.WEBSITE].orEmpty(),
            min = weights.minWebsiteScore,
        )

        // Greedy resolution across the three competing single-value
        // kinds (NAME / COMPANY / DESIGNATION). Sort their union by
        // score descending; for each candidate, claim it iff neither
        // the source block nor the kind has been claimed yet.
        // Outcome: a single block can only host one kind, and the
        // strongest cross-kind signal wins each contested block.
        // Address resolves separately because its candidates are
        // cluster-indexed, not single-block.
        val singleValueKinds = setOf(
            FieldKind.NAME, FieldKind.COMPANY, FieldKind.DESIGNATION,
        )
        val minScores = mapOf(
            FieldKind.NAME        to weights.minNameScore,
            FieldKind.COMPANY     to weights.minCompanyScore,
            FieldKind.DESIGNATION to weights.minDesignationScore,
        )
        val claimedBlocks = mutableSetOf<Int>()
        val claimedKinds  = mutableMapOf<FieldKind, FieldCandidate>()
        val singleValueSorted = candidates
            .filter { it.kind in singleValueKinds && it.score >= (minScores[it.kind] ?: Double.MAX_VALUE) }
            .sortedByDescending { it.score }
        for (c in singleValueSorted) {
            if (c.kind in claimedKinds) continue
            if (c.sourceBlockIndex in claimedBlocks) continue
            claimedKinds[c.kind] = c
            claimedBlocks += c.sourceBlockIndex
        }
        val name        = claimedKinds[FieldKind.NAME]
        val company     = claimedKinds[FieldKind.COMPANY]
        val designation = claimedKinds[FieldKind.DESIGNATION]

        val address = pickSingle(
            byKind[FieldKind.ADDRESS].orEmpty(),
            min = weights.minAddressScore,
            avoidBlocks = emptySet(),  // address can overlap — uses cluster index
        )

        // Aggregate confidence: mean of populated fields' scores
        // normalised by an empirical "max plausible score" of 25.
        val populated = listOfNotNull(
            name?.score, company?.score, designation?.score, address?.score,
        ) +
            (byKind[FieldKind.PHONE].orEmpty().filter { it.score >= weights.minPhoneScore }.map { it.score }) +
            (byKind[FieldKind.EMAIL].orEmpty().filter { it.score >= weights.minEmailScore }.map { it.score }) +
            (byKind[FieldKind.WEBSITE].orEmpty().filter { it.score >= weights.minWebsiteScore }.map { it.score })

        val confidence = if (populated.isEmpty()) 0.0
        else (populated.average() / 25.0).coerceIn(0.0, 1.0)

        return ExtractedContact(
            name        = name?.text,
            company     = company?.text,
            designation = designation?.text,
            phones      = phones,
            emails      = emails,
            websites    = websites,
            address     = address?.text,
            confidence  = confidence,
        )
    }

    private fun pickSingle(
        candidates: List<FieldCandidate>,
        min: Double,
        avoidBlocks: Set<Int>,
    ): FieldCandidate? = candidates
        .filter { it.score >= min && it.sourceBlockIndex !in avoidBlocks }
        .maxByOrNull { it.score }

    companion object {
        /**
         * Strings that should never surface as an extracted field on
         * a Business Card capture. Compared against each candidate's
         * normalised text (lower-case, alphanumerics only) so
         * "Business Card", "Business-Card", "businesscard.", and the
         * "8usiness" OCR-error aliases all collapse to the same
         * key. Mirrors the auto-categorisation alias set in
         * `ScanFlowController.aliasesFor("Business Card")`.
         */
        private val CATEGORY_STOP_WORDS: Set<String> = setOf(
            "businesscard",
            "card",
            "business",
            "8usinesscard",
            "8usiness",
        )

        private fun String.normalisedForStopCheck(): String =
            lowercase().filter { it.isLetterOrDigit() }
    }

    private fun pickMulti(
        candidates: List<FieldCandidate>,
        min: Double,
    ): List<String> {
        // De-dupe by normalised text, keep first-seen order from the
        // candidates list (already in block order).
        val seen = mutableSetOf<String>()
        val out  = mutableListOf<String>()
        for (c in candidates) {
            if (c.score < min) continue
            val key = c.text.lowercase()
            if (seen.add(key)) out += c.text
        }
        return out
    }
}
