/*
 * NameClassifier.kt
 *
 * Heuristic name detection. Names have no syntactic signature, so
 * the score is built up from layout + content priors:
 *   + nameBase                   — base score for any candidate
 *   + largeTextBonus             — proportional to bbox.height vs. max
 *   + topPositionBonus           — proportional to (1 - centreY)
 *   + engine confidence (× weight)
 *   + 2.0 if every alphabetic token starts with an uppercase letter
 *     ("Title Case Hint")
 *   - nameDigitsPenalty          — any digit in the block
 *   - nameTokenCountPenalty      — token count outside [1, 4]
 *   - 6.0                        — block contains email / URL / phone
 *                                  signals (definitely not the name)
 *
 * The pipeline picks the single highest-scoring NAME candidate;
 * minNameScore prunes obvious no-signal cards (returns null name).
 */

package app.releaf.shared.scan.businesscard.classifier

import app.releaf.shared.scan.businesscard.FieldCandidate
import app.releaf.shared.scan.businesscard.FieldKind
import app.releaf.shared.scan.businesscard.ScoringWeights
import app.releaf.shared.scan.businesscard.pipeline.LayoutContext
import app.releaf.shared.scan.businesscard.pipeline.engineConfidenceBonus
import app.releaf.shared.scan.businesscard.pipeline.largeTextBonus
import app.releaf.shared.scan.businesscard.pipeline.topPositionBonus

class NameClassifier : Classifier {

    override fun classify(layout: LayoutContext, weights: ScoringWeights): List<FieldCandidate> {
        val out = mutableListOf<FieldCandidate>()
        for ((idx, block) in layout.blocks.withIndex()) {
            // Single-line within the block — names are almost never
            // multi-line within a single OCR block.
            val text = block.text.replace('\n', ' ').trim()
            if (text.isEmpty()) continue

            var score = weights.nameBase
            score += largeTextBonus(block, layout, weights)
            score += topPositionBonus(block, weights)
            score += engineConfidenceBonus(block, weights)

            // Hard rejections — block contains structural signals
            // that are exclusive with names.
            if (CONTAINS_EMAIL.containsMatchIn(text))   score -= 6.0
            if (CONTAINS_URL.containsMatchIn(text))     score -= 6.0
            if (CONTAINS_PHONE.containsMatchIn(text))   score -= 6.0

            // Digits in the block — names don't contain digits.
            if (text.any { it.isDigit() }) score -= weights.nameDigitsPenalty

            // Token count check.
            val tokens = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (tokens.isEmpty() || tokens.size > 4) {
                score -= weights.nameTokenCountPenalty
            }

            // Title-case hint — every alphabetic token starts upper.
            if (tokens.isNotEmpty() && tokens.all { isTitleCased(it) }) score += 2.0

            // All-caps acronym blocks ("XYZ TECHNOLOGIES") tend to
            // be company / department, not name. Soft penalty.
            if (text == text.uppercase() && text.length > 4) score -= 2.0

            out += FieldCandidate(
                sourceBlockIndex = idx,
                text             = text,
                kind             = FieldKind.NAME,
                score            = score,
            )
        }
        return out
    }

    companion object {
        private val CONTAINS_EMAIL = Regex("""@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")
        private val CONTAINS_URL   = Regex("""(?:https?://|www\.)""", RegexOption.IGNORE_CASE)
        private val CONTAINS_PHONE = Regex("""\+?\d[\d\s\-.()]{7,}\d""")

        /** True for "John", "John-Paul", "O'Connor", "Mc'Donald". */
        private fun isTitleCased(token: String): Boolean {
            // Strip trailing punctuation first.
            val clean = token.trimEnd { !it.isLetterOrDigit() && it != '\'' && it != '-' }
            if (clean.isEmpty()) return false
            val first = clean.first()
            return first.isLetter() && first.isUpperCase()
        }
    }
}
