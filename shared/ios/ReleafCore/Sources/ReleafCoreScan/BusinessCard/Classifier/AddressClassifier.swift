/*
 * AddressClassifier.swift  — mirror of AddressClassifier.kt.
 *
 * Same cluster-then-score strategy as Android. Constructed inside the
 * pipeline with the postcode-classified block indices so address
 * scoring can boost clusters that contain a recognised postcode.
 */

import Foundation

public struct AddressClassifier: Classifier {

    private let postcodeBlockIndices: Set<Int>

    public init(postcodeBlockIndices: Set<Int> = []) {
        self.postcodeBlockIndices = postcodeBlockIndices
    }

    public func classify(layout: LayoutContext, weights: ScoringWeights) -> [FieldCandidate] {
        // 1. Eligible blocks.
        let eligible: [(Int, OcrBlock)] = layout.blocks.enumerated().compactMap { idx, block in
            let text = block.text
            if Self.containsEmail(text) { return nil }
            if Self.containsUrl(text)   { return nil }
            if Self.containsPhone(text) { return nil }
            let hasPostcode = postcodeBlockIndices.contains(idx)
            let hasComma    = text.contains(",")
            let isBottom    = (block.bbox.y + block.bbox.height / 2.0) > 0.5
            return (hasPostcode || (hasComma && isBottom)) ? (idx, block) : nil
        }
        if eligible.isEmpty { return [] }

        // 2. Cluster by vertical adjacency. Sort by y first.
        let sorted = eligible.sorted { $0.1.bbox.y < $1.1.bbox.y }
        var clusters: [[(Int, OcrBlock)]] = []
        for entry in sorted {
            if let lastCluster = clusters.last,
               let last = lastCluster.last,
               verticallyAdjacent(last.1.bbox, entry.1.bbox, weights) {
                clusters[clusters.count - 1].append(entry)
            } else {
                clusters.append([entry])
            }
        }

        // 3. Score each cluster.
        return clusters.map { cluster in
            let firstIndex = cluster.first!.0
            let joined = cluster
                .map { $0.1.text.trimmingCharacters(in: .whitespaces) }
                .joined(separator: "\n")

            var score = weights.addressBase
            let avgBottom = cluster.map { bottomPositionBonus($0.1, weights) }.reduce(0, +) / Double(cluster.count)
            score += avgBottom
            if cluster.contains(where: { postcodeBlockIndices.contains($0.0) }) { score += 4.0 }
            let avgConf = cluster.map { engineConfidenceBonus($0.1, weights) }.reduce(0, +) / Double(cluster.count)
            score += avgConf
            if cluster.count >= 2 { score += 1.5 }

            return FieldCandidate(
                sourceBlockIndex: firstIndex,
                text:             joined,
                kind:             .address,
                score:            score
            )
        }
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
