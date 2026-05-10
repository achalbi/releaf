/*
 * EmailClassifier.kt
 *
 * Identifies blocks that contain an email address. Regex hit alone
 * is high-signal (false-positive rate is near-zero on real cards),
 * so we lean heavily on the base score and add only a small engine-
 * confidence bonus on top.
 *
 * RFC 5322 in full is overkill here — the tighter pattern below
 * accepts the practical subset that appears on cards (printed
 * emails). One classifier emits one candidate per match; multi-
 * email blocks (rare) emit one per address.
 */

package app.releaf.shared.scan.businesscard.classifier

import app.releaf.shared.scan.businesscard.FieldCandidate
import app.releaf.shared.scan.businesscard.FieldKind
import app.releaf.shared.scan.businesscard.ScoringWeights
import app.releaf.shared.scan.businesscard.pipeline.LayoutContext
import app.releaf.shared.scan.businesscard.pipeline.engineConfidenceBonus

class EmailClassifier : Classifier {

    override fun classify(layout: LayoutContext, weights: ScoringWeights): List<FieldCandidate> {
        val out = mutableListOf<FieldCandidate>()
        for ((idx, block) in layout.blocks.withIndex()) {
            for (match in EMAIL_REGEX.findAll(block.text)) {
                val score = weights.emailBase + engineConfidenceBonus(block, weights)
                out += FieldCandidate(
                    sourceBlockIndex = idx,
                    text             = match.value.lowercase().trim(),
                    kind             = FieldKind.EMAIL,
                    score            = score,
                )
            }
        }
        return out
    }

    companion object {
        // Pragmatic email regex. Accepts standard local-part + domain
        // with TLD; rejects unbalanced punctuation. Not RFC-strict.
        private val EMAIL_REGEX = Regex(
            """[A-Za-z0-9](?:[A-Za-z0-9._%+\-]*[A-Za-z0-9])?@[A-Za-z0-9](?:[A-Za-z0-9.\-]*[A-Za-z0-9])?\.[A-Za-z]{2,24}"""
        )
    }
}
