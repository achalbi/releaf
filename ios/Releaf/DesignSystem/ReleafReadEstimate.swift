/*
 * ReleafReadEstimate.swift
 *
 * Pure value-type that turns a list of note bodies into a word
 * count and a casual-reading-time estimate. Used by PageDetail to
 * show a small "2 min read · 187 words" chip under the title block.
 *
 * Same target rules as ReleafImpact — no Data layer import; call
 * sites pull the body strings off a Page and pass them in.
 *
 * Reading speed defaults to 200 words/minute, the value most
 * style guides cite for casual prose. Adjustable via the init
 * parameter when call sites want a slower (technical) or faster
 * (skimming) cadence.
 */

import Foundation

public struct ReleafReadEstimate: Equatable, Sendable {
    public let words: Int
    /// Estimated minutes to read at the configured pace. Always at
    /// least 1 when there's any content; 0 when there are no words.
    public let minutes: Int

    public init(noteBodies: [String], wordsPerMinute: Int = 200) {
        let total = noteBodies.reduce(0) { acc, body in
            acc + Self.wordsIn(body)
        }
        self.words = total
        self.minutes = total == 0 ? 0 : max(1, Int((Double(total) / Double(wordsPerMinute)).rounded()))
    }

    /// Compact summary string for chip display. Returns nil when
    /// the page has no text content — caller hides the chip.
    public var summary: String? {
        guard words > 0 else { return nil }
        let wordLabel = "\(words) word\(words == 1 ? "" : "s")"
        return "\(minutes) min read · \(wordLabel)"
    }

    /// Whitespace-tolerant word counter. Splits on whitespace +
    /// punctuation and drops empties; close enough for chip
    /// display, doesn't try to be a linguistic tokenizer.
    private static func wordsIn(_ body: String) -> Int {
        body
            .components(separatedBy: .whitespacesAndNewlines)
            .filter { !$0.isEmpty }
            .count
    }
}
