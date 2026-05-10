/*
 * PhoneClassifier.swift  — mirror of PhoneClassifier.kt.
 */

import Foundation

public struct PhoneClassifier: Classifier {

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
                guard let normalised = Self.normalisePhone(raw) else { continue }
                let score = weights.phoneBase + engineConfidenceBonus(block, weights)
                out.append(FieldCandidate(
                    sourceBlockIndex: idx,
                    text:             normalised,
                    kind:             .phone,
                    score:            score
                ))
            }
        }
        return out
    }

    /// 10 → as-is. 11 with leading 0 → drop. 12 with leading 91 →
    /// `+<digits>`. Anything else → nil.
    public static func normalisePhone(_ raw: String) -> String? {
        let digits = raw.unicodeScalars
            .filter { CharacterSet.decimalDigits.contains($0) }
            .map { String($0) }
            .joined()
        switch digits.count {
        case 10:                                        return digits
        case 11 where digits.hasPrefix("0"):            return String(digits.dropFirst())
        case 12 where digits.hasPrefix("91"):           return "+" + digits
        default:                                        return nil
        }
    }

    private static let regex: NSRegularExpression = {
        // swiftlint:disable:next force_try
        try! NSRegularExpression(pattern: #"\+?\d[\d\s\-.()]{8,18}\d"#)
    }()
}
