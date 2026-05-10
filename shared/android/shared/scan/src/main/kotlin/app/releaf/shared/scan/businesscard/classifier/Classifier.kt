/*
 * Classifier.kt
 *
 * Strategy interface every entity classifier implements. The pipeline
 * runs each classifier against the same [LayoutContext] and collects
 * the union of their candidates — adding a new entity type means
 * dropping a new file in this directory and registering it in
 * [BusinessCardExtractor], no other code changes.
 */

package app.releaf.shared.scan.businesscard.classifier

import app.releaf.shared.scan.businesscard.FieldCandidate
import app.releaf.shared.scan.businesscard.ScoringWeights
import app.releaf.shared.scan.businesscard.pipeline.LayoutContext

interface Classifier {
    /**
     * Inspect every block in [layout] and emit zero or more
     * candidates for the kind(s) this classifier handles. Multiple
     * candidates per block are allowed — the pipeline's resolver
     * picks the highest-scoring one per (kind, block) pair.
     */
    fun classify(layout: LayoutContext, weights: ScoringWeights): List<FieldCandidate>
}
