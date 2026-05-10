/*
 * BusinessCardParser.swift
 *
 * Lightweight heuristic parser that pulls a contact name and any
 * mobile numbers out of a business-card OCR blob. Used by the
 * "Add to contact" action on `ScanDetailScreen` when the capture's
 * category is Business Card. Mirror of Android's
 * `BusinessCardParser.kt`.
 *
 * Phone matching:
 *   - Picks any digit run (with optional inline whitespace, dashes,
 *     dots, parens) that, after stripping non-digits, normalises to
 *     a 10-digit Indian mobile, a +91-prefixed 12-digit number, or
 *     a 0-prefixed 11-digit number (the leading 0 is dropped).
 *   - De-duplicates and preserves first-seen order.
 *
 * Name picking:
 *   - First non-empty line that has at least 2 letters AND no
 *     digits. Business cards typically lead with the contact's
 *     name, so the heuristic is "first line that looks like a name".
 *   - Returns `nil` when no line qualifies; the caller hands the
 *     empty name to the system contact UI and the user can fill it
 *     in manually.
 */

import Foundation

enum BusinessCardParser {

    /// Result of parsing a card's OCR blob: the best-guess name
    /// (may be `nil`) and the de-duplicated list of phone numbers
    /// (in normalised form — 10 digits, or "+91…" for international).
    struct Parsed: Equatable {
        let name: String?
        let phones: [String]
    }

    /// Parse the supplied OCR text. Whitespace-tolerant — pages can
    /// be joined with newlines or any other separator, the parser
    /// re-splits on lines internally.
    static func parse(_ ocr: String) -> Parsed {
        let lines = ocr
            .components(separatedBy: .newlines)
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }

        let phones = extractPhones(from: lines)
        let name   = pickName(from: lines)
        return Parsed(name: name, phones: phones)
    }

    // MARK: - Phone extraction

    /// Regex matches any run starting with an optional `+`, then a
    /// digit, then 8–18 chars of digits + common phone separators
    /// (space, dash, dot, parens), then a digit. The post-clean step
    /// validates the digit count, so the regex can be loose without
    /// dragging in junk.
    private static let phoneRegex: NSRegularExpression? = {
        try? NSRegularExpression(
            pattern: #"\+?\d[\d\s\-.()]{8,18}\d"#
        )
    }()

    private static func extractPhones(from lines: [String]) -> [String] {
        guard let regex = phoneRegex else { return [] }
        var seen = Set<String>()
        var phones: [String] = []
        for line in lines {
            let nsLine = line as NSString
            let matches = regex.matches(in: line, range: NSRange(location: 0, length: nsLine.length))
            for match in matches {
                let raw = nsLine.substring(with: match.range)
                guard let normalised = normalisePhone(raw) else { continue }
                if !seen.contains(normalised) {
                    seen.insert(normalised)
                    phones.append(normalised)
                }
            }
        }
        return phones
    }

    /// Strip non-digit chars, then accept the result as a phone if:
    ///   - 10 digits → Indian mobile (no country code) — return as-is
    ///   - 12 digits prefixed by `91` → return as `+<digits>`
    ///   - 11 digits prefixed by `0` → drop the leading 0
    /// Anything else → `nil`. Matches the user's brief: "10 digit
    /// number or prefixed by +91".
    static func normalisePhone(_ raw: String) -> String? {
        let digits = raw.unicodeScalars
            .filter { CharacterSet.decimalDigits.contains($0) }
            .map { String($0) }
            .joined()
        switch digits.count {
        case 10:
            return digits
        case 11 where digits.hasPrefix("0"):
            return String(digits.dropFirst())
        case 12 where digits.hasPrefix("91"):
            return "+" + digits
        default:
            return nil
        }
    }

    // MARK: - Name picking

    /// Heuristic: first line with ≥ 2 letters and no digits. Business
    /// cards lead with the contact's name, and digits typically only
    /// appear in phone / fax / address lines.
    private static func pickName(from lines: [String]) -> String? {
        lines.first { line in
            let letters = line.unicodeScalars.contains { CharacterSet.letters.contains($0) }
            let digits  = line.unicodeScalars.contains { CharacterSet.decimalDigits.contains($0) }
            let letterCount = line.unicodeScalars.reduce(0) { count, scalar in
                count + (CharacterSet.letters.contains(scalar) ? 1 : 0)
            }
            return letters && !digits && letterCount >= 2
        }
    }
}
