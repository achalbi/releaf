/*
 * PostcodeClassifier.swift  — mirror of PostcodeClassifier.kt.
 */

import Foundation

public struct PostcodeClassifier: Classifier {

    public init() {}

    public func classify(layout: LayoutContext, weights: ScoringWeights) -> [FieldCandidate] {
        var out: [FieldCandidate] = []
        for (idx, block) in layout.blocks.enumerated() {
            let text = block.text
            let nsText = text as NSString
            let range = NSRange(location: 0, length: nsText.length)
            let matched =
                Self.inPin.firstMatch(in: text, range: range)     != nil ||
                Self.usZip.firstMatch(in: text, range: range)     != nil ||
                Self.ukPostcode.firstMatch(in: text, range: range) != nil
            if matched {
                out.append(FieldCandidate(
                    sourceBlockIndex: idx,
                    text:             text,
                    kind:             .postcode,
                    score:            weights.postcodeBase
                ))
            }
        }
        return out
    }

    private static let inPin: NSRegularExpression = {
        // swiftlint:disable:next force_try
        try! NSRegularExpression(pattern: #"\b[1-9]\d{5}\b"#)
    }()
    private static let usZip: NSRegularExpression = {
        // swiftlint:disable:next force_try
        try! NSRegularExpression(pattern: #"\b\d{5}(?:-\d{4})?\b"#)
    }()
    private static let ukPostcode: NSRegularExpression = {
        // swiftlint:disable:next force_try
        try! NSRegularExpression(pattern: #"\b[A-Za-z]{1,2}\d[A-Za-z\d]?\s?\d[A-Za-z]{2}\b"#)
    }()
}
