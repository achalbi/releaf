/*
 * AddressClassifier.kt
 *
 * Builds the multi-line address output. Different shape from the
 * other classifiers — the candidates it emits are *clusters* of
 * blocks, not single blocks, because addresses span 2–4 lines.
 *
 * Strategy:
 *   1. Identify "address-eligible" blocks: bottom-half blocks that
 *      contain a postcode hit (from PostcodeClassifier upstream) OR
 *      contain ≥ 1 comma + don't match any other strong-signal
 *      classifier (email / URL / phone).
 *   2. Cluster eligible blocks that are vertically adjacent.
 *   3. Emit one candidate per cluster, joining the constituent
 *      blocks' text with `\n`.
 *
 * The pipeline picks the highest-scoring cluster as the address.
 */

package app.releaf.shared.scan.businesscard.classifier

import app.releaf.shared.scan.businesscard.FieldCandidate
import app.releaf.shared.scan.businesscard.FieldKind
import app.releaf.shared.scan.businesscard.ScoringWeights
import app.releaf.shared.scan.businesscard.pipeline.LayoutContext
import app.releaf.shared.scan.businesscard.pipeline.bottomPositionBonus
import app.releaf.shared.scan.businesscard.pipeline.engineConfidenceBonus
import app.releaf.shared.scan.businesscard.pipeline.verticallyAdjacent

class AddressClassifier(
    private val postcodeBlockIndices: Set<Int> = emptySet(),
) : Classifier {

    override fun classify(layout: LayoutContext, weights: ScoringWeights): List<FieldCandidate> {
        // 1. Mark eligible blocks.
        val eligible = layout.blocks.withIndex().filter { (idx, block) ->
            val text = block.text
            // Strong-signal blocks (email/url/phone) are NEVER address.
            if (CONTAINS_EMAIL.containsMatchIn(text)) return@filter false
            if (CONTAINS_URL.containsMatchIn(text))   return@filter false
            if (CONTAINS_PHONE.containsMatchIn(text)) return@filter false
            val hasPostcode = idx in postcodeBlockIndices
            val hasComma    = text.contains(',')
            val isBottom    = (block.bbox.y + block.bbox.height / 2.0) > 0.5
            hasPostcode || (hasComma && isBottom)
        }
        if (eligible.isEmpty()) return emptyList()

        // 2. Cluster vertically-adjacent eligible blocks. Sort by
        //    y so adjacency only looks forward.
        val sorted = eligible.sortedBy { it.value.bbox.y }
        val clusters = mutableListOf<MutableList<IndexedValue<app.releaf.shared.scan.OcrBlock>>>()
        for (entry in sorted) {
            val last = clusters.lastOrNull()?.lastOrNull()
            if (last != null && verticallyAdjacent(last.value.bbox, entry.value.bbox, weights)) {
                clusters.last().add(entry)
            } else {
                clusters += mutableListOf(entry)
            }
        }

        // 3. Score each cluster.
        return clusters.map { cluster ->
            val firstIndex = cluster.first().index
            val joinedText = cluster.joinToString("\n") { it.value.text.trim() }
            val anchorBlock = cluster.first().value

            var score = weights.addressBase
            // Weighted average bottom-position bonus across the
            // cluster — addresses sitting truly at the bottom score
            // higher than ones in the middle.
            val avgBottomBonus = cluster
                .map { bottomPositionBonus(it.value, weights) }
                .average()
            score += avgBottomBonus
            // Postcode in any block of the cluster is a strong tell.
            if (cluster.any { it.index in postcodeBlockIndices }) score += 4.0
            // Engine-confidence average across the cluster.
            score += cluster.map { engineConfidenceBonus(it.value, weights) }.average()
            // Multi-line clusters are more address-like than single-line.
            if (cluster.size >= 2) score += 1.5

            FieldCandidate(
                sourceBlockIndex = firstIndex,
                text             = joinedText,
                kind             = FieldKind.ADDRESS,
                score            = score,
            )
        }
    }

    companion object {
        private val CONTAINS_EMAIL = Regex("""@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")
        private val CONTAINS_URL   = Regex("""(?:https?://|www\.)""", RegexOption.IGNORE_CASE)
        private val CONTAINS_PHONE = Regex("""\+?\d[\d\s\-.()]{7,}\d""")
    }
}
