/*
 * TemplateDetection.swift
 *
 * How a scanned page is matched to a printed QuickInk template, and
 * how the template's title / date are read off it.
 *
 * ASSUMPTION — nothing in the repo specifies how a printed page
 * identifies itself. This code assumes a QR code in a page corner
 * encoding `quickink://t/<templateId>` with an optional
 * `/p/<pageNumber>` (see `page_templates.json` → `_meta.mark_format`).
 * It needs confirming against the printed-book design. Detection is
 * behind a protocol so a different mark swaps in without touching
 * the scan flow:
 *
 *   - `MarkTemplateDetector`        the printed mark; the QR is
 *     decoded on-device by Vision (`VisionBarcodeReader`) and handed
 *     in as raw payload strings, keeping this file pure.
 *   - `HeaderWordsTemplateDetector` fallback: printed header words in
 *     the OCR text near the top of the page.
 *   - `CompositeTemplateDetector`   first match wins.
 *
 * Mirror of Android `TemplateDetection.kt`.
 */

import Foundation

// MARK: - Printed mark

/// Parsed `quickink://t/<templateId>[/p/<pageNumber>]` payload.
public struct TemplateMark: Equatable, Sendable {
    public let templateId: String
    public let pageNumber: Int?

    public init(templateId: String, pageNumber: Int?) {
        self.templateId = templateId
        self.pageNumber = pageNumber
    }

    private static let pattern = try! NSRegularExpression(
        pattern: #"^quickink://t/([a-z0-9][a-z0-9_-]{0,63})(?:/p/(\d{1,6}))?/?$"#,
        options: [.caseInsensitive]
    )

    /// Nil for anything that isn't a QuickInk template mark.
    public static func parse(_ payload: String) -> TemplateMark? {
        let s = payload.trimmingCharacters(in: .whitespacesAndNewlines)
        let range = NSRange(s.startIndex..., in: s)
        guard let m = pattern.firstMatch(in: s, range: range),
              let idRange = Range(m.range(at: 1), in: s)
        else { return nil }
        let page = Range(m.range(at: 2), in: s).flatMap { Int(s[$0]) }
        return TemplateMark(templateId: s[idRange].lowercased(), pageNumber: page)
    }
}

// MARK: - Detector protocol

/// Everything a detector may look at for one page.
public struct TemplatePage: Sendable {
    public let lines: [OcrLine]
    /// Raw barcode payloads decoded on-device from the page image.
    public let barcodePayloads: [String]

    public init(lines: [OcrLine], barcodePayloads: [String] = []) {
        self.lines = lines
        self.barcodePayloads = barcodePayloads
    }
}

public struct TemplateMatch: Equatable, Sendable {
    public enum Method: Equatable, Sendable { case mark, headerWords }

    public let template: PrintedPageTemplate
    public let method: Method
    /// Printed page number when the mark carried one.
    public let pageNumber: Int?

    public init(template: PrintedPageTemplate, method: Method, pageNumber: Int? = nil) {
        self.template = template
        self.method = method
        self.pageNumber = pageNumber
    }
}

public protocol PageTemplateDetector: Sendable {
    func detect(_ page: TemplatePage) -> TemplateMatch?
}

/// Reads the printed `quickink://t/…` mark. Unknown template ids → no match.
public struct MarkTemplateDetector: PageTemplateDetector {
    let registry: PrintedPageTemplateRegistry

    public init(registry: PrintedPageTemplateRegistry) { self.registry = registry }

    public func detect(_ page: TemplatePage) -> TemplateMatch? {
        for payload in page.barcodePayloads {
            guard let mark = TemplateMark.parse(payload),
                  let template = registry.byId(mark.templateId)
            else { continue }
            return TemplateMatch(template: template, method: .mark, pageNumber: mark.pageNumber)
        }
        return nil
    }
}

/// Fallback: a template's printed header phrase in the OCR text of
/// lines centred in its header band. Every word must be present
/// (order-free, case- and punctuation-insensitive). Longest phrase
/// wins; a tie between templates is no match rather than a guess.
public struct HeaderWordsTemplateDetector: PageTemplateDetector {
    let registry: PrintedPageTemplateRegistry

    public init(registry: PrintedPageTemplateRegistry) { self.registry = registry }

    public func detect(_ page: TemplatePage) -> TemplateMatch? {
        var best: PrintedPageTemplate?
        var bestLen = 0
        var tie = false
        for template in registry.templates {
            let words = Set(page.lines
                .filter { template.headerZone.contains($0.centerX, $0.centerY) }
                .flatMap { Self.tokens($0.text) })
            if words.isEmpty { continue }
            for phrase in template.headerPhrases {
                let need = Self.tokens(phrase)
                guard need.count >= 2, need.allSatisfy(words.contains) else { continue }
                if need.count > bestLen {
                    best = template; bestLen = need.count; tie = false
                } else if need.count == bestLen, best?.id != template.id {
                    tie = true
                }
            }
        }
        guard let best, !tie else { return nil }
        return TemplateMatch(template: best, method: .headerWords)
    }

    /// Lowercase word tokens; hyphens / apostrophes collapse ("To-Do" → "todo").
    public static func tokens(_ text: String) -> [String] {
        let collapsed = text.lowercased().filter { $0 != "-" && $0 != "'" && $0 != "’" }
        return collapsed
            .split(whereSeparator: { !($0.isLetter || $0.isNumber) })
            .map(String.init)
    }
}

public struct CompositeTemplateDetector: PageTemplateDetector {
    let detectors: [any PageTemplateDetector]

    public init(_ detectors: [any PageTemplateDetector]) { self.detectors = detectors }

    public func detect(_ page: TemplatePage) -> TemplateMatch? {
        for d in detectors { if let m = d.detect(page) { return m } }
        return nil
    }

    /// Mark first, header words second.
    public static func standard(_ registry: PrintedPageTemplateRegistry = .default) -> CompositeTemplateDetector {
        CompositeTemplateDetector([MarkTemplateDetector(registry: registry), HeaderWordsTemplateDetector(registry: registry)])
    }
}

// MARK: - Title / date zones

public struct TemplateFields: Equatable, Sendable {
    public let title: String?
    /// Raw text found in the date zone (label stripped).
    public let dateText: String?
    /// `yyyy-MM-dd` when `dateText` parsed, else nil.
    public let dateIso: String?
}

public enum TemplateFieldExtractor {
    private static let maxTitleChars = 80
    private static let label = try! NSRegularExpression(
        pattern: #"^\s*(title|topic|subject|date|day)\s*[:\-–.]\s*"#,
        options: [.caseInsensitive]
    )

    public static func extract(_ template: PrintedPageTemplate, lines: [OcrLine]) -> TemplateFields {
        let title = template.titleZone
            .map { String(zoneText($0, lines: lines).prefix(maxTitleChars)).trimmingCharacters(in: .whitespaces) }
            .flatMap { $0.isEmpty ? nil : $0 }
        let dateText = template.dateZone
            .map { zoneText($0, lines: lines) }
            .flatMap { $0.isEmpty ? nil : $0 }
        return TemplateFields(title: title, dateText: dateText, dateIso: dateText.flatMap(DateParser.parseIso))
    }

    /// Lines whose box centre is in `zone`, in reading order, label stripped.
    public static func zoneText(_ zone: PageZone, lines: [OcrLine]) -> String {
        let joined = lines
            .filter { zone.contains($0.centerX, $0.centerY) }
            .sorted { ($0.y, $0.x) < ($1.y, $1.x) }
            .map { $0.text.trimmingCharacters(in: .whitespacesAndNewlines) }
            .joined(separator: " ")
        let range = NSRange(joined.startIndex..., in: joined)
        let unlabelled = label.stringByReplacingMatches(in: joined, range: range, withTemplate: "")
        return unlabelled
            .split(whereSeparator: { $0.isWhitespace })
            .joined(separator: " ")
    }
}

/// Lenient handwritten-date parser → `yyyy-MM-dd`. Same formats and
/// day-first rule as Android `DateParser`.
public enum DateParser {
    private static let ymd = try! NSRegularExpression(pattern: #"(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})"#)
    private static let dmy = try! NSRegularExpression(pattern: #"(\d{1,2})[-/.](\d{1,2})[-/.](\d{2,4})"#)
    private static let dMonY = try! NSRegularExpression(
        pattern: #"(\d{1,2})(?:st|nd|rd|th)?\s+([a-z]{3,9})\.?,?\s+(\d{2,4})"#, options: [.caseInsensitive])
    private static let monDY = try! NSRegularExpression(
        pattern: #"([a-z]{3,9})\.?\s+(\d{1,2})(?:st|nd|rd|th)?,?\s+(\d{2,4})"#, options: [.caseInsensitive])
    private static let months = [
        "january", "february", "march", "april", "may", "june",
        "july", "august", "september", "october", "november", "december",
    ]

    public static func parseIso(_ text: String) -> String? {
        if let g = groups(ymd, text), let y = Int(g[0]), let m = Int(g[1]), let d = Int(g[2]) {
            return iso(y, m, d)
        }
        if let g = groups(dmy, text), let a = Int(g[0]), let b = Int(g[1]) {
            let y = year(g[2])
            return (b > 12 && a <= 12) ? iso(y, a, b) : iso(y, b, a)
        }
        if let g = groups(dMonY, text), let m = month(g[1]), let d = Int(g[0]) {
            return iso(year(g[2]), m, d)
        }
        if let g = groups(monDY, text), let m = month(g[0]), let d = Int(g[1]) {
            return iso(year(g[2]), m, d)
        }
        return nil
    }

    /// Not more than a day in the future, not more than five years back.
    public static func isPlausibleEntryDate(_ iso: String, today: Date = Date(), calendar: Calendar = .current) -> Bool {
        let f = DateFormatter()
        f.calendar = Calendar(identifier: .gregorian)
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = calendar.timeZone
        f.dateFormat = "yyyy-MM-dd"
        guard let d = f.date(from: iso),
              let latest = calendar.date(byAdding: .day, value: 1, to: calendar.startOfDay(for: today)),
              let earliest = calendar.date(byAdding: .year, value: -5, to: calendar.startOfDay(for: today))
        else { return false }
        return d <= latest && d >= earliest
    }

    private static func groups(_ re: NSRegularExpression, _ text: String) -> [String]? {
        let range = NSRange(text.startIndex..., in: text)
        guard let m = re.firstMatch(in: text, range: range) else { return nil }
        return (1..<m.numberOfRanges).map { i in
            Range(m.range(at: i), in: text).map { String(text[$0]) } ?? ""
        }
    }

    private static func year(_ raw: String) -> Int {
        let v = Int(raw) ?? 0
        return raw.count == 2 ? 2000 + v : v
    }

    /// Full name or any prefix of it ≥ 3 letters ("oct", "sept"); "mayor" is not May.
    private static func month(_ raw: String) -> Int? {
        let key = raw.lowercased()
        guard key.count >= 3, let i = months.firstIndex(where: { $0.hasPrefix(key) }) else { return nil }
        return i + 1
    }

    private static func iso(_ y: Int, _ m: Int, _ d: Int) -> String? {
        var c = DateComponents()
        c.year = y; c.month = m; c.day = d
        let cal = Calendar(identifier: .gregorian)
        guard (1...12).contains(m), (1...31).contains(d),
              c.isValidDate(in: cal)
        else { return nil }
        return String(format: "%04d-%02d-%02d", y, m, d)
    }
}
