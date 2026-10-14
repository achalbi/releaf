/*
 * TemplateDetectionTests.swift
 *
 * Printed-page template routing: seed mirror vs `page_templates.json`,
 * the `quickink://` mark parser, both detectors, zone extraction and
 * the date parser. Mirror of Android `TemplateDetectionTest.kt`.
 */

import XCTest
@testable import QuickInkFeatures

final class TemplateDetectionTests: XCTestCase {

    private let registry = PrintedPageTemplateRegistry.default

    private func line(_ i: Int, _ text: String, _ x: Double, _ y: Double, w: Double = 0.3, h: Double = 0.03) -> OcrLine {
        OcrLine(index: i, text: text, x: x, y: y, width: w, height: h)
    }

    // MARK: - Seed mirror

    /// Walk up from this file to the repo's seed JSON.
    private func seedJSON() throws -> Data {
        var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        while dir.path != "/" {
            let f = dir.appendingPathComponent("shared/design-system/seeds/page_templates.json")
            if FileManager.default.fileExists(atPath: f.path) { return try Data(contentsOf: f) }
            dir = dir.deletingLastPathComponent()
        }
        throw XCTSkip("page_templates.json not found above \(#filePath)")
    }

    func testHardCodedSeedMirrorsJSON() throws {
        let parsed = PrintedPageTemplateRegistry.fromJSON(try seedJSON()).templates
        XCTAssertEqual(parsed, PrintedPageTemplates.seed)
    }

    func testSeedCoversDefaultTagsAndSeededFolders() {
        XCTAssertEqual(registry.templates.map(\.id), ["meeting-notes", "journal", "todo", "ideas"])
        XCTAssertEqual(registry.templates.compactMap(\.tags.first), ["meetings", "journal", "todo", "ideas"])
        let seeded: Set<String> = ["inbox", "archive", "finance", "medical", "family", "travel",
                                   "events", "legal", "lifestyle", "learning", "projects", "ideas"]
        XCTAssertTrue(registry.templates.allSatisfy { seeded.contains($0.folderId ?? "") })
    }

    func testFromJSONSkipsIncompleteTemplatesAndDefaultsOptionalFields() {
        let reg = PrintedPageTemplateRegistry.fromJSON(Data(#"{"templates":[{"id":"x"},{"id":"a","name":"A","extra":1}]}"#.utf8))
        XCTAssertEqual(reg.templates.count, 1)
        let a = reg.templates[0]
        XCTAssertEqual(a.id, "a")
        XCTAssertNil(a.titleZone)
        XCTAssertFalse(a.hasCheckboxColumn)
        XCTAssertEqual(a.headerZone, .topBand)
    }

    // MARK: - Mark

    func testMarkParserAcceptsAssumedFormat() {
        XCTAssertEqual(TemplateMark.parse("quickink://t/meeting-notes"), TemplateMark(templateId: "meeting-notes", pageNumber: nil))
        XCTAssertEqual(TemplateMark.parse(" QUICKINK://t/Journal/p/42 "), TemplateMark(templateId: "journal", pageNumber: 42))
        XCTAssertEqual(TemplateMark.parse("quickink://t/todo/p/7/"), TemplateMark(templateId: "todo", pageNumber: 7))
    }

    func testMarkParserRejectsOtherPayloads() {
        for p in ["https://quickink.app/t/todo", "quickink://x/todo", "quickink://t/",
                  "quickink://t/todo/p/", "quickink://t/todo/p/abc", "quickink://t/../etc", ""] {
            XCTAssertNil(TemplateMark.parse(p), p)
        }
    }

    func testMarkDetectorResolvesKnownIdsAndIgnoresUnknown() {
        let det = MarkTemplateDetector(registry: registry)
        let hit = det.detect(TemplatePage(lines: [], barcodePayloads: ["https://example.com", "quickink://t/ideas/p/3"]))
        XCTAssertEqual(hit?.template.id, "ideas")
        XCTAssertEqual(hit?.method, .mark)
        XCTAssertEqual(hit?.pageNumber, 3)
        XCTAssertNil(det.detect(TemplatePage(lines: [], barcodePayloads: ["quickink://t/future-template"])))
    }

    // MARK: - Header words

    func testHeaderDetectorMatchesPrintedHeaderInTopBand() {
        let hit = HeaderWordsTemplateDetector(registry: registry)
            .detect(TemplatePage(lines: [line(0, "QuickInk · Meeting Notes", 0.05, 0.02, w: 0.5)]))
        XCTAssertEqual(hit?.template.id, "meeting-notes")
        XCTAssertEqual(hit?.method, .headerWords)
    }

    func testHeaderDetectorNormalisesToDoSpellings() {
        for text in ["QUICKINK  TO-DO", "QuickInk To Do", "quickink todo"] {
            let hit = HeaderWordsTemplateDetector(registry: registry).detect(TemplatePage(lines: [line(0, text, 0.05, 0.02)]))
            XCTAssertEqual(hit?.template.id, "todo", text)
        }
    }

    func testHandwrittenHeadingWithoutBrandWordIsNotATemplate() {
        XCTAssertNil(HeaderWordsTemplateDetector(registry: registry).detect(TemplatePage(lines: [line(0, "Meeting notes", 0.05, 0.02)])))
    }

    func testHeaderWordsBelowBandAreIgnored() {
        XCTAssertNil(HeaderWordsTemplateDetector(registry: registry).detect(TemplatePage(lines: [line(0, "QuickInk Journal", 0.05, 0.5)])))
    }

    func testAmbiguousHeaderResolvesToNoTemplate() {
        XCTAssertNil(HeaderWordsTemplateDetector(registry: registry).detect(TemplatePage(lines: [line(0, "QuickInk Journal Ideas", 0.05, 0.02)])))
    }

    func testCompositePrefersMarkOverHeaderWords() {
        let page = TemplatePage(lines: [line(0, "QuickInk Journal", 0.05, 0.02)], barcodePayloads: ["quickink://t/todo"])
        let hit = CompositeTemplateDetector.standard(registry).detect(page)
        XCTAssertEqual(hit?.template.id, "todo")
        XCTAssertEqual(hit?.method, .mark)
    }

    // MARK: - Zones

    func testTitleAndDateComeFromLinesCentredInZones() {
        let t = registry.byId("meeting-notes")!
        let lines = [
            line(0, "QuickInk Meeting Notes", 0.05, 0.02, w: 0.5),
            line(1, "Topic: Q4 roadmap", 0.10, 0.09, w: 0.40),
            line(2, "review", 0.52, 0.09, w: 0.10),
            line(3, "Date: 14/10/2026", 0.72, 0.09, w: 0.20),
            line(4, "Attendees: Sam, Priya", 0.10, 0.30, w: 0.50),
        ]
        let f = TemplateFieldExtractor.extract(t, lines: lines)
        XCTAssertEqual(f.title, "Q4 roadmap review")
        XCTAssertEqual(f.dateText, "14/10/2026")
        XCTAssertEqual(f.dateIso, "2026-10-14")
    }

    func testEmptyZonesYieldNil() {
        let f = TemplateFieldExtractor.extract(registry.byId("journal")!, lines: [line(0, "body", 0.1, 0.5)])
        XCTAssertNil(f.title)
        XCTAssertNil(f.dateText)
        XCTAssertNil(f.dateIso)
    }

    func testTemplateWithoutZonesExtractsNothing() {
        let bare = PrintedPageTemplate(id: "x", name: "X", folderId: nil, tags: [], titleZone: nil, dateZone: nil, hasCheckboxColumn: false)
        XCTAssertNil(TemplateFieldExtractor.extract(bare, lines: [line(0, "a", 0.1, 0.1)]).title)
    }

    // MARK: - Dates

    func testDateParserHandlesCommonFormats() {
        let cases: [(String, String)] = [
            ("2026-10-14", "2026-10-14"),
            ("14/10/2026", "2026-10-14"),
            ("14.10.26", "2026-10-14"),
            ("4-3-2026", "2026-03-04"),
            ("10/14/2026", "2026-10-14"),
            ("14 Oct 2026", "2026-10-14"),
            ("14th October, 2026", "2026-10-14"),
            ("Oct 14, 2026", "2026-10-14"),
            ("Sept 3 2026", "2026-09-03"),
            ("Tue 14 Oct 26", "2026-10-14"),
        ]
        for (input, expected) in cases { XCTAssertEqual(DateParser.parseIso(input), expected, input) }
    }

    func testDateParserRejectsNonDates() {
        for input in ["", "Q4 roadmap", "31/02/2026", "14 Mayor 2026", "2026-13-01", "call at 10.30"] {
            XCTAssertNil(DateParser.parseIso(input), input)
        }
    }

    func testEntryDatePlausibilityWindow() {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "UTC")!
        let today = cal.date(from: DateComponents(year: 2026, month: 10, day: 14, hour: 12))!
        XCTAssertTrue(DateParser.isPlausibleEntryDate("2026-10-14", today: today, calendar: cal))
        XCTAssertTrue(DateParser.isPlausibleEntryDate("2026-10-15", today: today, calendar: cal))
        XCTAssertTrue(DateParser.isPlausibleEntryDate("2022-01-01", today: today, calendar: cal))
        XCTAssertFalse(DateParser.isPlausibleEntryDate("2026-10-20", today: today, calendar: cal))
        XCTAssertFalse(DateParser.isPlausibleEntryDate("2019-01-01", today: today, calendar: cal))
        XCTAssertFalse(DateParser.isPlausibleEntryDate("garbage", today: today, calendar: cal))
    }
}
