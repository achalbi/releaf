/*
 * DesignationClassifier.swift  — mirror of DesignationClassifier.kt.
 */

import Foundation

public struct DesignationClassifier: Classifier {

    public init() {}

    public func classify(layout: LayoutContext, weights: ScoringWeights) -> [FieldCandidate] {
        var out: [FieldCandidate] = []
        for (idx, block) in layout.blocks.enumerated() {
            let text = block.text.replacingOccurrences(of: "\n", with: " ")
                .trimmingCharacters(in: .whitespaces)
            if text.isEmpty { continue }

            let normalised = text.lowercased()
            let tokens = normalised.split { !$0.isLetter && !$0.isNumber }.map(String.init)

            var hits = 0
            for t in tokens where DesignationVocab.tokens.contains(t) { hits += 1 }
            for phrase in DesignationVocab.phrases where normalised.contains(phrase) { hits += 1 }
            if hits == 0 { continue }

            var score = weights.designationBase
            score += Double(hits) * weights.designationVocabBonus
            score += engineConfidenceBonus(block, weights)
            score += topPositionBonus(block, weights) * 0.5

            if text.contains(where: { $0.isNumber }) { score -= 4.0 }

            out.append(FieldCandidate(
                sourceBlockIndex: idx,
                text:             text,
                kind:             .designation,
                score:            score
            ))
        }
        return out
    }
}
