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
 *   + nameSalutationBonus        — first token is a known honorific
 *                                  (Mr / Mrs / Dr / Sri / Smt / …);
 *                                  near-positive identification
 *   - nameDigitsPenalty          — any digit in the block
 *   - nameTokenCountPenalty      — token count outside [1, 4]
 *   - 6.0                        — block contains email / URL / phone
 *                                  signals (definitely not the name)
 *   - largestTextPenaltyForName  — block height is at / near
 *                                  layout.maxHeight (only on multi-
 *                                  block layouts). Biggest text on a
 *                                  card is almost always the company
 *                                  wordmark; this nudges that block
 *                                  toward COMPANY without forbidding
 *                                  it as NAME outright.
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

            // Salutation hint — first token is a known honorific
            // ("Mr.", "Mrs", "Dr", "Sri"). Near-positive identification
            // since these never appear on company / address / phone
            // lines.
            if (tokens.isNotEmpty() && SALUTATIONS.contains(stripPunct(tokens.first().lowercase()))) {
                score += weights.nameSalutationBonus
            }

            // All-caps acronym blocks ("XYZ TECHNOLOGIES") tend to
            // be company / department, not name. Soft penalty.
            if (text == text.uppercase() && text.length > 4) score -= 2.0

            // Largest-text penalty — when a card has multiple blocks
            // and this one is at / near the tallest, it's far more
            // likely the company wordmark than the person's name.
            // 95% of max-height triggers — leaves the bonus space for
            // ties (multiple blocks at the same large size, e.g.
            // company line + a tagline).
            if (layout.blocks.size > 1 &&
                layout.maxHeight > 0.0 &&
                block.bbox.height >= 0.95 * layout.maxHeight) {
                score -= weights.largestTextPenaltyForName
            }

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

        /**
         * Lowercased, punctuation-stripped salutation set. Compared
         * against the first token of the block. Common English titles
         * + Indian honorifics; extend with caution — false positives
         * are essentially impossible since none of these read as
         * company / address / phone tokens.
         */
        private val SALUTATIONS: Set<String> = setOf(
            "mr", "mrs", "ms", "mx",
            "dr", "doctor",
            "prof", "professor",
            "sri", "shri", "smt", "shrimati",
            "kumari", "miss",
            "rev", "reverend",
            "hon", "honorable",
            "sir", "madam",
            "col", "lt", "capt", "maj",
        )

        /** True for "John", "John-Paul", "O'Connor", "Mc'Donald". */
        private fun isTitleCased(token: String): Boolean {
            // Strip trailing punctuation first.
            val clean = token.trimEnd { !it.isLetterOrDigit() && it != '\'' && it != '-' }
            if (clean.isEmpty()) return false
            val first = clean.first()
            return first.isLetter() && first.isUpperCase()
        }

        /** Strip non-alphanumeric chars so "Mr." / "Mr" / "Mr," all match. */
        private fun stripPunct(token: String): String =
            token.filter { it.isLetterOrDigit() }
    }
}
