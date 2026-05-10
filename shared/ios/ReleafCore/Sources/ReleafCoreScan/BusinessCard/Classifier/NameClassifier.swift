/*
 * NameClassifier.swift  — mirror of NameClassifier.kt.
 */

import Foundation

public struct NameClassifier: Classifier {

    public init() {}

    public func classify(layout: LayoutContext, weights: ScoringWeights) -> [FieldCandidate] {
        var out: [FieldCandidate] = []
        for (idx, block) in layout.blocks.enumerated() {
            let text = block.text.replacingOccurrences(of: "\n", with: " ")
                .trimmingCharacters(in: .whitespaces)
            if text.isEmpty { continue }

            var score = weights.nameBase
            score += largeTextBonus(block, layout, weights)
            score += topPositionBonus(block, weights)
            score += engineConfidenceBonus(block, weights)

            // Hard exclusions.
            if Self.containsEmail(text) { score -= 6.0 }
            if Self.containsUrl(text)   { score -= 6.0 }
            if Self.containsPhone(text) { score -= 6.0 }

            // Digits in the block.
            if text.contains(where: { $0.isNumber }) {
                score -= weights.nameDigitsPenalty
            }

            // Token count.
            let tokens = text.split(whereSeparator: { $0.isWhitespace }).map(String.init)
            if tokens.isEmpty || tokens.count > 4 {
                score -= weights.nameTokenCountPenalty
            }

            // Title-case hint.
            if !tokens.isEmpty, tokens.allSatisfy(Self.isTitleCased) {
                score += 2.0
            }

            // Salutation hint — first token is a known honorific
            // ("Mr.", "Mrs", "Dr", "Sri"). Near-positive identification.
            if let first = tokens.first,
               Self.salutations.contains(Self.stripPunct(first.lowercased())) {
                score += weights.nameSalutationBonus
            }

            // All-caps acronym → soft penalty.
            if text == text.uppercased(), text.count > 4 {
                score -= 2.0
            }

            // Largest-text penalty — biggest text on a card is
            // almost always the company wordmark, not the name.
            if layout.blocks.count > 1,
               layout.maxHeight > 0,
               block.bbox.height >= 0.95 * layout.maxHeight {
                score -= weights.largestTextPenaltyForName
            }

            out.append(FieldCandidate(
                sourceBlockIndex: idx,
                text:             text,
                kind:             .name,
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

    private static func isTitleCased(_ token: String) -> Bool {
        var clean = token
        while let last = clean.last,
              !last.isLetter && !last.isNumber && last != "'" && last != "-" {
            clean.removeLast()
        }
        guard let first = clean.first else { return false }
        return first.isLetter && first.isUppercase
    }

    /// Lowercased, punctuation-stripped salutation set. Mirror of
    /// the Android list — extend in parity.
    private static let salutations: Set<String> = [
        "mr", "mrs", "ms", "mx",
        "dr", "doctor",
        "prof", "professor",
        "sri", "shri", "smt", "shrimati",
        "kumari", "miss",
        "rev", "reverend",
        "hon", "honorable",
        "sir", "madam",
        "col", "lt", "capt", "maj",
    ]

    private static func stripPunct(_ token: String) -> String {
        token.filter { $0.isLetter || $0.isNumber }
    }
}
