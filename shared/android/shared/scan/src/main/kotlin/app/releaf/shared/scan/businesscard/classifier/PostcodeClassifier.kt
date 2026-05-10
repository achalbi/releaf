/*
 * PostcodeClassifier.kt
 *
 * Tags blocks containing a recognisable postal code. Used by the
 * AddressClassifier as a strong "this block is part of the address"
 * signal — addresses are otherwise hard to identify by content
 * alone (multiline mixed text, no fixed format).
 *
 * Recognises:
 *   - Indian PIN: 6-digit run, with the standard "first digit 1-9"
 *     constraint.
 *   - US ZIP: 5-digit run (with optional ZIP+4 suffix).
 *   - UK postcode: alphanumeric pattern.
 *
 * Falsely matching some random 5-/6-digit run is fine — the
 * candidate is consumed by AddressClassifier as a soft hint, not as
 * a directly-emitted output field.
 */

package app.releaf.shared.scan.businesscard.classifier

import app.releaf.shared.scan.businesscard.FieldCandidate
import app.releaf.shared.scan.businesscard.FieldKind
import app.releaf.shared.scan.businesscard.ScoringWeights
import app.releaf.shared.scan.businesscard.pipeline.LayoutContext

class PostcodeClassifier : Classifier {

    override fun classify(layout: LayoutContext, weights: ScoringWeights): List<FieldCandidate> {
        val out = mutableListOf<FieldCandidate>()
        for ((idx, block) in layout.blocks.withIndex()) {
            val text = block.text
            val matched =
                IN_PIN_REGEX.containsMatchIn(text) ||
                US_ZIP_REGEX.containsMatchIn(text) ||
                UK_POSTCODE_REGEX.containsMatchIn(text)
            if (matched) {
                out += FieldCandidate(
                    sourceBlockIndex = idx,
                    text             = text,
                    kind             = FieldKind.POSTCODE,
                    score            = weights.postcodeBase,
                )
            }
        }
        return out
    }

    companion object {
        private val IN_PIN_REGEX      = Regex("""\b[1-9]\d{5}\b""")
        private val US_ZIP_REGEX      = Regex("""\b\d{5}(?:-\d{4})?\b""")
        private val UK_POSTCODE_REGEX = Regex(
            """\b[A-Za-z]{1,2}\d[A-Za-z\d]?\s?\d[A-Za-z]{2}\b"""
        )
    }
}
