/*
 * EmailClassifier.swift  — mirror of EmailClassifier.kt
 */

import Foundation

public struct EmailClassifier: Classifier {

    public init() {}

    public func classify(layout: LayoutContext, weights: ScoringWeights) -> [FieldCandidate] {
        var out: [FieldCandidate] = []
        for (idx, block) in layout.blocks.enumerated() {
            let nsText = block.text as NSString
            let matches = Self.regex.matches(
                in: block.text,
                range: NSRange(location: 0, length: nsText.length)
            )
            for m in matches {
                let raw = nsText.substring(with: m.range)
                let score = weights.emailBase + engineConfidenceBonus(block, weights)
                out.append(FieldCandidate(
                    sourceBlockIndex: idx,
                    text:             raw.lowercased().trimmingCharacters(in: .whitespaces),
                    kind:             .email,
                    score:            score
                ))
            }
        }
        return out
    }

    // Pragmatic email regex — accepts the practical subset that
    // appears on cards. Not RFC-strict.
    private static let regex: NSRegularExpression = {
        let pattern = #"[A-Za-z0-9](?:[A-Za-z0-9._%+\-]*[A-Za-z0-9])?@[A-Za-z0-9](?:[A-Za-z0-9.\-]*[A-Za-z0-9])?\.[A-Za-z]{2,24}"#
        // swiftlint:disable:next force_try
        return try! NSRegularExpression(pattern: pattern)
    }()
}
