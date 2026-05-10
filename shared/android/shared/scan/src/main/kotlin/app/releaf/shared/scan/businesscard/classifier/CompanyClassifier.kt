/*
 * CompanyClassifier.kt
 *
 * Identifies the block bearing the company / organisation name.
 * Strongest signal: a known company-suffix (Pvt Ltd, Inc, Tech, …)
 * as the trailing token. Weaker signals: large font, top-half
 * position, mostly title-case (or all-caps as a brand wordmark).
 *
 * Score:
 *   + companyBase
 *   + companySuffixBonus on suffix vocabulary hit
 *   + largeTextBonus
 *   + topPositionBonus
 *   + engine confidence (× weight)
 *   + 1.5 if all-caps (common for brand wordmarks)
 *   - 4.0 if block contains email / URL / phone signals
 *   - companyPunctuationPenalty per ',' / ';' / '.' run > 1 (those
 *     read as address / contact lines, not company)
 */

package app.releaf.shared.scan.businesscard.classifier

import app.releaf.shared.scan.businesscard.FieldCandidate
import app.releaf.shared.scan.businesscard.FieldKind
import app.releaf.shared.scan.businesscard.ScoringWeights
import app.releaf.shared.scan.businesscard.pipeline.LayoutContext
import app.releaf.shared.scan.businesscard.pipeline.engineConfidenceBonus
import app.releaf.shared.scan.businesscard.pipeline.largeTextBonus
import app.releaf.shared.scan.businesscard.pipeline.topPositionBonus
import app.releaf.shared.scan.businesscard.vocab.CompanySuffixVocab

class CompanyClassifier : Classifier {

    override fun classify(layout: LayoutContext, weights: ScoringWeights): List<FieldCandidate> {
        val out = mutableListOf<FieldCandidate>()
        for ((idx, block) in layout.blocks.withIndex()) {
            val text = block.text.replace('\n', ' ').trim()
            if (text.isEmpty()) continue

            val normalised = text.lowercase().replace(Regex("[^a-z0-9 ]"), " ")
                .replace(Regex("\\s+"), " ").trim()
            val tokens = normalised.split(' ').filter { it.isNotEmpty() }
            if (tokens.isEmpty()) continue

            // Suffix vocabulary checks.
            var suffixHit = false
            val lastToken = tokens.last()
            val lastTwo = if (tokens.size >= 2) "${tokens[tokens.size - 2]} $lastToken" else ""
            if (lastToken in CompanySuffixVocab.singleTokens) suffixHit = true
            if (lastTwo in CompanySuffixVocab.multiTokens)    suffixHit = true

            // No suffix? Still a candidate, but base only — relies
            // on layout to push it above the threshold.
            var score = weights.companyBase
            if (suffixHit) score += weights.companySuffixBonus

            score += largeTextBonus(block, layout, weights)
            score += topPositionBonus(block, weights)
            score += engineConfidenceBonus(block, weights)

            // Brand wordmark hint — many Indian companies print the
            // brand all-caps in a large weight.
            if (text == text.uppercase() && text.length >= 3 && tokens.size <= 5) {
                score += 1.5
            }

            // Hard exclusions — same as NameClassifier.
            if (CONTAINS_EMAIL.containsMatchIn(text)) score -= 4.0
            if (CONTAINS_URL.containsMatchIn(text))   score -= 4.0
            if (CONTAINS_PHONE.containsMatchIn(text)) score -= 4.0

            // Heavy punctuation reads as an address line.
            val commaCount = text.count { it == ',' }
            if (commaCount >= 1) score -= weights.companyPunctuationPenalty * commaCount

            out += FieldCandidate(
                sourceBlockIndex = idx,
                text             = text,
                kind             = FieldKind.COMPANY,
                score            = score,
            )
        }
        return out
    }

    companion object {
        private val CONTAINS_EMAIL = Regex("""@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")
        private val CONTAINS_URL   = Regex("""(?:https?://|www\.)""", RegexOption.IGNORE_CASE)
        private val CONTAINS_PHONE = Regex("""\+?\d[\d\s\-.()]{7,}\d""")
    }
}
