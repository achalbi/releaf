/*
 * CompanyClassifier.swift  — mirror of CompanyClassifier.kt.
 */

import Foundation

public struct CompanyClassifier: Classifier {

    public init() {}

    public func classify(layout: LayoutContext, weights: ScoringWeights) -> [FieldCandidate] {
        var out: [FieldCandidate] = []
        for (idx, block) in layout.blocks.enumerated() {
            let text = block.text.replacingOccurrences(of: "\n", with: " ")
                .trimmingCharacters(in: .whitespaces)
            if text.isEmpty { continue }

            let cleaned = text
                .lowercased()
                .replacingOccurrences(of: #"[^a-z0-9 ]"#, with: " ", options: .regularExpression)
                .replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
                .trimmingCharacters(in: .whitespaces)
            let tokens = cleaned.split(separator: " ").map(String.init)
            if tokens.isEmpty { continue }

            // Suffix vocabulary checks.
            var suffixHit = false
            let lastToken = tokens.last!
            if CompanySuffixVocab.singleTokens.contains(lastToken) { suffixHit = true }
            if tokens.count >= 2 {
                let lastTwo = "\(tokens[tokens.count - 2]) \(lastToken)"
                if CompanySuffixVocab.multiTokens.contains(lastTwo) { suffixHit = true }
            }

            var score = weights.companyBase
            if suffixHit { score += weights.companySuffixBonus }

            score += largeTextBonus(block, layout, weights)
            score += topPositionBonus(block, weights)
            score += engineConfidenceBonus(block, weights)

            // Brand wordmark.
            if text == text.uppercased(), text.count >= 3, tokens.count <= 5 {
                score += 1.5
            }

            // Exclusions.
            if Self.containsEmail(text) { score -= 4.0 }
            if Self.containsUrl(text)   { score -= 4.0 }
            if Self.containsPhone(text) { score -= 4.0 }

            // Heavy punctuation = address.
            let commaCount = text.filter { $0 == "," }.count
            if commaCount >= 1 {
                score -= weights.companyPunctuationPenalty * Double(commaCount)
            }

            out.append(FieldCandidate(
                sourceBlockIndex: idx,
                text:             text,
                kind:             .company,
                score:            score
            ))
        }
        return out
    }

    private static let emailRegex: NSRegularExpression = {
        // swiftlint:disable:next force_try
        try! NSRegularExpression(pattern: #"@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}"#)
    }()
    private static let urlRegex: NSRegularExpression = {
        // swiftlint:disable:next force_try
        try! NSRegularExpression(pattern: #"(?:https?://|www\.)"#, options: [.caseInsensitive])
    }()
    private static let phoneRegex: NSRegularExpression = {
        // swiftlint:disable:next force_try
        try! NSRegularExpression(pattern: #"\+?\d[\d\s\-.()]{7,}\d"#)
    }()

    private static func containsEmail(_ s: String) -> Bool {
        emailRegex.firstMatch(in: s, range: NSRange(location: 0, length: (s as NSString).length)) != nil
    }
    private static func containsUrl(_ s: String) -> Bool {
        urlRegex.firstMatch(in: s, range: NSRange(location: 0, length: (s as NSString).length)) != nil
    }
    private static func containsPhone(_ s: String) -> Bool {
        phoneRegex.firstMatch(in: s, range: NSRange(location: 0, length: (s as NSString).length)) != nil
    }
}
