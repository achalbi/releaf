/*
 * BusinessCardExtractor.kt
 *
 * Public façade for the business-card extraction pipeline. App-side
 * code calls this with the OCR blocks for a card; gets back a
 * structured [ExtractedContact]. No mutable state — the extractor is
 * thread-safe and can be called from any coroutine context.
 *
 * Architecturally a thin wrapper over [ExtractionPipeline]; lives at
 * the package root so callers don't need to import internal pipeline
 * / classifier types directly.
 *
 * Mirror of `BusinessCardExtractor.swift` in `ReleafCoreScan.BusinessCard`.
 */

package app.releaf.shared.scan.businesscard

import app.releaf.shared.scan.OcrBlock
import app.releaf.shared.scan.businesscard.pipeline.ExtractionPipeline

object BusinessCardExtractor {

    /**
     * Run extraction on a card's recognised blocks.
     *
     * @param blocks Ordered list as emitted by the OCR pipeline.
     *   Empty input returns [ExtractedContact.empty].
     * @param weights Optional override of the scoring weights.
     *   Defaults to [ScoringWeights]'s out-of-the-box calibration.
     * @param keepTrace When true, the returned contact carries an
     *   [ExtractionTrace] for debug / benchmark output. False by
     *   default — production callers don't need the trace and would
     *   only pay the allocation cost.
     */
    fun extract(
        blocks: List<OcrBlock>,
        weights: ScoringWeights = ScoringWeights(),
        keepTrace: Boolean = false,
    ): ExtractedContact = ExtractionPipeline(weights = weights).extract(blocks, keepTrace)
}
