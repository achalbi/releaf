/*
 * DesignationClassifier.kt
 *
 * Looks up each block's tokens against [DesignationVocab]. A vocab
 * hit is the strongest signal we have for "this is a job title" —
 * the heuristic priors (font size, position) are weaker for
 * designation than for name / company because titles can sit
 * anywhere on the card and are usually printed at body-text size.
 *
 * Score:
 *   + designationBase
 *   + designationVocabBonus per matched token / phrase
 *   + engine confidence (× weight)
 *   + small position bonus when in upper half (most cards print
 *     title directly under the name, near the top)
 *   - 4.0 if block contains a phone number (designations don't
 *     read as "Director +91 9876…")
 */

package app.releaf.shared.scan.businesscard.classifier

import app.releaf.shared.scan.businesscard.FieldCandidate
import app.releaf.shared.scan.businesscard.FieldKind
import app.releaf.shared.scan.businesscard.ScoringWeights
import app.releaf.shared.scan.businesscard.pipeline.LayoutContext
import app.releaf.shared.scan.businesscard.pipeline.engineConfidenceBonus
import app.releaf.shared.scan.businesscard.pipeline.topPositionBonus
import app.releaf.shared.scan.businesscard.vocab.DesignationVocab

class DesignationClassifier : Classifier {

    override fun classify(layout: LayoutContext, weights: ScoringWeights): List<FieldCandidate> {
        val out = mutableListOf<FieldCandidate>()
        for ((idx, block) in layout.blocks.withIndex()) {
            val text = block.text.replace('\n', ' ').trim()
            if (text.isEmpty()) continue

            val normalised = text.lowercase()
            val tokens = normalised.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }

            // Hit count — both single-token and phrase matches.
            var hits = 0
            for (t in tokens) {
                if (t in DesignationVocab.tokens) hits++
            }
            for (phrase in DesignationVocab.phrases) {
                if (normalised.contains(phrase)) hits++
            }
            if (hits == 0) continue

            var score = weights.designationBase
            score += hits * weights.designationVocabBonus
            score += engineConfidenceBonus(block, weights)
            score += (topPositionBonus(block, weights) * 0.5)   // half of name's bonus

            // Penalty: digits on the line — designations rarely
            // contain digits. Phone-bearing blocks are not titles.
            if (text.contains(Regex("""\d"""))) score -= 4.0

            out += FieldCandidate(
                sourceBlockIndex = idx,
                text             = text,
                kind             = FieldKind.DESIGNATION,
                score            = score,
            )
        }
        return out
    }
}
