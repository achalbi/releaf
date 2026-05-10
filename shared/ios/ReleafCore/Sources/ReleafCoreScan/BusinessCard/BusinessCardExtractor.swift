/*
 * BusinessCardExtractor.swift  — mirror of BusinessCardExtractor.kt.
 */

import Foundation

public enum BusinessCardExtractor {

    /// Run extraction on a card's recognised blocks.
    public static func extract(
        _ blocks: [OcrBlock],
        weights: ScoringWeights = ScoringWeights(),
        keepTrace: Bool = false
    ) -> ExtractedContact {
        ExtractionPipeline(weights: weights).extract(blocks, keepTrace: keepTrace)
    }
}
