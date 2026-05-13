/*
 * AutoTagSuggester.swift
 *
 * Phase E v1 — keyword-rule heuristics over OCR text. Returns a
 * ranked list of suggested tag names for a capture. No ML, no
 * server. ~15 hand-tuned rules.
 *
 * Mirror of `AutoTagSuggester.kt` in QuickInk's Android target.
 */

import Foundation

public enum AutoTagSuggester {

    public enum Rule {
        case keyword(tag: String, word: String)
        case phrase(tag: String, phrases: [String])
        case regex(tag: String, pattern: NSRegularExpression)

        var tag: String {
            switch self {
            case .keyword(let t, _): return t
            case .phrase(let t, _):  return t
            case .regex(let t, _):   return t
            }
        }
    }

    private static let docTypeRules: [Rule] = {
        var rules: [Rule] = [
            .phrase(tag: "invoice",        phrases: ["invoice", "amount due", "bill to", "tax invoice"]),
            .phrase(tag: "receipt",        phrases: ["receipt", "thank you for your purchase", "subtotal"]),
            .phrase(tag: "paid",           phrases: ["paid in full", "payment received", "transaction complete"]),
            .phrase(tag: "overdue",        phrases: ["past due", "overdue", "remit promptly"]),
            .phrase(tag: "contract",       phrases: [
                "this agreement", "msa", "master services agreement",
                "nda", "non-disclosure", "non disclosure", "letter of intent",
            ]),
            .phrase(tag: "signed",         phrases: ["signed by", "/s/", "executed by", "duly authorized"]),
            .phrase(tag: "unsigned",       phrases: [
                "signature page intentionally left blank", "sign here", "to be signed",
            ]),
            .phrase(tag: "legal",          phrases: ["attorney-client", "governing law", "jurisdiction shall"]),
            .phrase(tag: "meeting",        phrases: ["meeting notes", "agenda", "action items", "attendees"]),
        ]
        if let cardRegex = try? NSRegularExpression(
            pattern: "([A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}).*?(\\+?\\d[\\d \\-()]{6,})",
            options: [.dotMatchesLineSeparators, .caseInsensitive]
        ) {
            rules.append(.regex(tag: "business-card", pattern: cardRegex))
        }
        return rules
    }()

    private static let vendorRules: [Rule] = [
        .keyword(tag: "aws",    word: "amazon web services"),
        .keyword(tag: "aws",    word: "aws"),
        .keyword(tag: "figma",  word: "figma"),
        .keyword(tag: "notion", word: "notion"),
        .keyword(tag: "vercel", word: "vercel"),
        .keyword(tag: "linear", word: "linear app"),
        .keyword(tag: "stripe", word: "stripe"),
        .keyword(tag: "github", word: "github"),
        .keyword(tag: "google", word: "google cloud"),
    ]

    /// Run rules over `ocrText` and return suggested tag names.
    /// Vendor and quarter heuristics only fire when the matching
    /// tag already exists for the user.
    public static func suggest(
        ocrText: String?,
        existingTagNames: Set<String>,
        currentlyAttached: Set<String>,
        captureDateIso: String? = nil
    ) -> [String] {
        let text = (ocrText ?? "").lowercased()
        if text.isEmpty { return [] }
        var hits: [String] = []
        var seen: Set<String> = []
        func add(_ tag: String) {
            if seen.insert(tag).inserted { hits.append(tag) }
        }

        for rule in docTypeRules where matches(rule: rule, in: text) {
            add(rule.tag)
        }
        for rule in vendorRules where matches(rule: rule, in: text) {
            if existingTagNames.contains(rule.tag) { add(rule.tag) }
        }

        if let iso = captureDateIso, let quarterTag = quarterTag(for: iso),
           existingTagNames.contains(quarterTag) {
            add(quarterTag)
        }

        return hits.filter { !currentlyAttached.contains($0) }
    }

    private static func matches(rule: Rule, in lowerText: String) -> Bool {
        switch rule {
        case .keyword(_, let word):
            return lowerText.contains(word.lowercased())
        case .phrase(_, let phrases):
            return phrases.contains { lowerText.contains($0.lowercased()) }
        case .regex(_, let regex):
            let range = NSRange(lowerText.startIndex..., in: lowerText)
            return regex.firstMatch(in: lowerText, options: [], range: range) != nil
        }
    }

    private static func quarterTag(for iso: String) -> String? {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        guard let date = formatter.date(from: iso) ??
                ISO8601DateFormatter().date(from: iso) else {
            return nil
        }
        let cal = Calendar.current
        let month = cal.component(.month, from: date)
        let year  = cal.component(.year,  from: date)
        let q = (month - 1) / 3 + 1
        return "q\(q)-\(year)"
    }
}
