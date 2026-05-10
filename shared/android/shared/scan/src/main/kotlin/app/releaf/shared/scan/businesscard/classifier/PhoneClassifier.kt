/*
 * PhoneClassifier.kt
 *
 * Phone-number extractor. Extends the digit-run scanning the older
 * `BusinessCardParser` used: regex matches loose digit groups, the
 * normaliser then accepts only Indian-mobile-shaped results
 * (10 digits, "+91…" / "91…" 12, or 0-prefixed 11). Same accept set
 * the existing `Add to contact` flow already understands.
 *
 * Per the brief, this stays close to the user-stated rules
 * ("10 digit number or prefixed by +91") rather than over-reaching
 * to international formats we can't validate offline.
 */

package app.releaf.shared.scan.businesscard.classifier

import app.releaf.shared.scan.businesscard.FieldCandidate
import app.releaf.shared.scan.businesscard.FieldKind
import app.releaf.shared.scan.businesscard.ScoringWeights
import app.releaf.shared.scan.businesscard.pipeline.LayoutContext
import app.releaf.shared.scan.businesscard.pipeline.engineConfidenceBonus

class PhoneClassifier : Classifier {

    override fun classify(layout: LayoutContext, weights: ScoringWeights): List<FieldCandidate> {
        val out = mutableListOf<FieldCandidate>()
        for ((idx, block) in layout.blocks.withIndex()) {
            for (match in PHONE_REGEX.findAll(block.text)) {
                val normalised = normalisePhone(match.value) ?: continue
                val score = weights.phoneBase + engineConfidenceBonus(block, weights)
                out += FieldCandidate(
                    sourceBlockIndex = idx,
                    text             = normalised,
                    kind             = FieldKind.PHONE,
                    score            = score,
                )
            }
        }
        return out
    }

    companion object {
        /**
         * Loose digit-run regex with optional `+`, then a digit, then
         * 8–18 chars of digit-or-separator, then a digit. Validation
         * happens in [normalisePhone] — keeps the regex cheap.
         */
        private val PHONE_REGEX = Regex("""\+?\d[\d\s\-.()]{8,18}\d""")

        /**
         * 10 → as-is. 11 with leading 0 → drop the 0. 12 with leading 91
         * → "+<digits>". Anything else → null. Mirror of iOS / older
         * `BusinessCardParser.normalisePhone`.
         */
        fun normalisePhone(raw: String): String? {
            val digits = raw.filter { it.isDigit() }
            return when {
                digits.length == 10                            -> digits
                digits.length == 11 && digits.startsWith("0")  -> digits.drop(1)
                digits.length == 12 && digits.startsWith("91") -> "+$digits"
                else                                           -> null
            }
        }
    }
}
