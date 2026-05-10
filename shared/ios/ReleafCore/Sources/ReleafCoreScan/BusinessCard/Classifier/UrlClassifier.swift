/*
 * UrlClassifier.swift  — mirror of UrlClassifier.kt.
 */

import Foundation

public struct UrlClassifier: Classifier {

    public init() {}

    public func classify(layout: LayoutContext, weights: ScoringWeights) -> [FieldCandidate] {
        var out: [FieldCandidate] = []
        for (idx, block) in layout.blocks.enumerated() {
            let text = block.text
            let nsText = text as NSString
            let fullRange = NSRange(location: 0, length: nsText.length)

            // Pass 1: scheme.
            for m in Self.schemeRegex.matches(in: text, range: fullRange) {
                let raw = nsText.substring(with: m.range)
                out.append(FieldCandidate(
                    sourceBlockIndex: idx,
                    text:             cleanTrailing(raw),
                    kind:             .website,
                    score:            weights.websiteBase + engineConfidenceBonus(block, weights)
                ))
            }

            // Pass 2: www.
            for m in Self.wwwRegex.matches(in: text, range: fullRange) {
                let raw = nsText.substring(with: m.range)
                let withoutWww = String(raw.dropFirst(4))
                if text.contains("://" + withoutWww) { continue }
                out.append(FieldCandidate(
                    sourceBlockIndex: idx,
                    text:             cleanTrailing(raw),
                    kind:             .website,
                    score:            weights.websiteBase + engineConfidenceBonus(block, weights)
                ))
            }

            // Pass 3: bare domain with known TLD.
            for m in Self.bareDomainRegex.matches(in: text, range: fullRange) {
                guard m.numberOfRanges >= 3 else { continue }
                let tldRange = m.range(at: 2)
                let tld = nsText.substring(with: tldRange).lowercased()
                if !Self.commonTlds.contains(tld) { continue }
                // Reject if preceded by '@' — that's an email host.
                let start = m.range.location
                if start > 0 {
                    let prev = nsText.substring(with: NSRange(location: start - 1, length: 1))
                    if prev == "@" { continue }
                }
                let raw = nsText.substring(with: m.range)
                out.append(FieldCandidate(
                    sourceBlockIndex: idx,
                    text:             cleanTrailing(raw).lowercased(),
                    kind:             .website,
                    score:            (weights.websiteBase - 1.0) + engineConfidenceBonus(block, weights)
                ))
            }
        }
        return out
    }

    private static let schemeRegex: NSRegularExpression = {
        // swiftlint:disable:next force_try
        try! NSRegularExpression(pattern: #"https?://\S+"#, options: [.caseInsensitive])
    }()

    private static let wwwRegex: NSRegularExpression = {
        // swiftlint:disable:next force_try
        try! NSRegularExpression(
            pattern: #"www\.[A-Za-z0-9\-._~]+\.[A-Za-z]{2,24}(?:/\S*)?"#,
            options: [.caseInsensitive]
        )
    }()

    private static let bareDomainRegex: NSRegularExpression = {
        // swiftlint:disable:next force_try
        try! NSRegularExpression(
            pattern: #"\b([A-Za-z0-9](?:[A-Za-z0-9\-]*[A-Za-z0-9])?(?:\.[A-Za-z0-9\-]+)*)\.([A-Za-z]{2,24})\b"#
        )
    }()

    private static let commonTlds: Set<String> = [
        "com", "co", "in", "org", "net", "io", "app", "ai", "dev",
        "gov", "edu", "biz", "info", "us", "uk", "de", "fr", "jp",
        "ca", "au", "br", "ru", "cn", "tech", "design", "studio",
        "store", "online", "site", "xyz", "me",
    ]

    private func cleanTrailing(_ s: String) -> String {
        var out = s
        while let last = out.last, ".,;:)]".contains(last) { out.removeLast() }
        return out
    }
}
