/*
 * LayoutAnalysis.kt
 *
 * Pure helpers that turn the raw OCR block list into derived
 * statistics every classifier needs (max height, top-quartile cutoff,
 * whether two boxes are vertically adjacent, etc.). Pulled out of the
 * individual classifiers so the calculations happen once per
 * extraction pass instead of once per kind, and so test code can
 * exercise the layout primitives in isolation.
 *
 * No state, no IO — just maths on already-normalised bboxes (0..1,
 * top-left origin, see `OcrBbox`).
 */

package app.releaf.shared.scan.businesscard.pipeline

import app.releaf.shared.scan.OcrBbox
import app.releaf.shared.scan.OcrBlock
import app.releaf.shared.scan.businesscard.ScoringWeights
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Pre-computed snapshot of the card's layout. Constructed once per
 * extraction and threaded through every classifier so we don't pay
 * the linear scans repeatedly.
 */
data class LayoutContext(
    val blocks: List<OcrBlock>,
    /** Max bbox.height across non-empty blocks. 0 when input is empty. */
    val maxHeight: Double,
    /** Mean bbox.height, used for "is this larger than typical body?" heuristics. */
    val meanHeight: Double,
    /** y of the smallest top-edge — useful as a "top of content" anchor. */
    val topY: Double,
    /** y of the largest bottom-edge — "bottom of content" anchor. */
    val bottomY: Double,
)

/** Build a [LayoutContext] from a block list. Pure — no side effects. */
fun layoutOf(blocks: List<OcrBlock>): LayoutContext {
    if (blocks.isEmpty()) {
        return LayoutContext(blocks, 0.0, 0.0, 0.0, 0.0)
    }
    var maxH = 0.0
    var sumH = 0.0
    var topY = Double.POSITIVE_INFINITY
    var botY = Double.NEGATIVE_INFINITY
    for (b in blocks) {
        maxH = max(maxH, b.bbox.height)
        sumH += b.bbox.height
        topY = min(topY, b.bbox.y)
        botY = max(botY, b.bbox.y + b.bbox.height)
    }
    return LayoutContext(
        blocks     = blocks,
        maxHeight  = maxH,
        meanHeight = sumH / blocks.size,
        topY       = topY,
        bottomY    = botY,
    )
}

/**
 * Score in [0, [ScoringWeights.largeTextBonusMax]] proportional to
 * how close the block's height is to the tallest block on the card.
 * The tallest block scores the full bonus; a block at half the max
 * height scores half. Returns 0 when the layout has no signal yet
 * (empty layout / zero-height block).
 */
fun largeTextBonus(block: OcrBlock, layout: LayoutContext, weights: ScoringWeights): Double {
    if (layout.maxHeight <= 0.0) return 0.0
    val ratio = (block.bbox.height / layout.maxHeight).coerceIn(0.0, 1.0)
    return ratio * weights.largeTextBonusMax
}

/**
 * Score in [0, [ScoringWeights.topPositionBonusMax]] favouring blocks
 * in the upper region of the card. Linear falloff: y = 0 → full
 * bonus, y = 1 → 0. Used by NAME / COMPANY classifiers since both
 * print near the top on the vast majority of cards.
 */
fun topPositionBonus(block: OcrBlock, weights: ScoringWeights): Double {
    val centreY = block.bbox.y + (block.bbox.height / 2.0)
    val factor = (1.0 - centreY).coerceIn(0.0, 1.0)
    return factor * weights.topPositionBonusMax
}

/**
 * Bonus when a block sits in the bottom third of the card. Used by
 * ADDRESS — addresses overwhelmingly print at the bottom, often
 * spanning multiple lines. Linear from 0 at y=0.66 to full bonus at
 * y=1.0.
 */
fun bottomPositionBonus(block: OcrBlock, weights: ScoringWeights): Double {
    val centreY = block.bbox.y + (block.bbox.height / 2.0)
    val factor = ((centreY - 0.66) / 0.34).coerceIn(0.0, 1.0)
    return factor * weights.topPositionBonusMax
}

/**
 * Engine-confidence-derived score. ML Kit (Play-Services 19.0.1)
 * emits null per block — treated as neutral 0. Apple Vision always
 * emits a real value, so iOS gets a real bonus.
 */
fun engineConfidenceBonus(block: OcrBlock, weights: ScoringWeights): Double {
    val c = block.confidence ?: return 0.0
    return c.coerceIn(0.0, 1.0) * weights.engineConfidenceWeight
}

/** True when [a] sits within `weights.adjacencyYDistance` directly below [b] (or vice versa). */
fun verticallyAdjacent(a: OcrBbox, b: OcrBbox, weights: ScoringWeights): Boolean {
    val aBottom = a.y + a.height
    val bBottom = b.y + b.height
    val gap = min(abs(b.y - aBottom), abs(a.y - bBottom))
    return gap <= weights.adjacencyYDistance
}

/**
 * Intersection-over-union for two bboxes. Used by the dedup pass to
 * collapse classifiers that all fired on the same block into a
 * single best candidate.
 */
fun iou(a: OcrBbox, b: OcrBbox): Double {
    val xLeft   = max(a.x, b.x)
    val yTop    = max(a.y, b.y)
    val xRight  = min(a.x + a.width, b.x + b.width)
    val yBottom = min(a.y + a.height, b.y + b.height)
    if (xRight < xLeft || yBottom < yTop) return 0.0
    val inter = (xRight - xLeft) * (yBottom - yTop)
    val areaA = a.width * a.height
    val areaB = b.width * b.height
    val union = areaA + areaB - inter
    return if (union <= 0.0) 0.0 else inter / union
}
