/*
 * PrintedPageTemplates.swift
 *
 * Registry of page templates for the printed QuickInk notebook. A
 * scanned page that identifies as one of these is filed into the
 * template's folder, gets its tags, and has its title / date read
 * from fixed zones on the page (see `TemplateDetection.swift`).
 *
 * Canonical source: `shared/design-system/seeds/page_templates.json`.
 * Like the workspace seed this is a hard-coded mirror;
 * `TemplateDetectionTests` parses the JSON and fails when the two
 * drift. `PrintedPageTemplateRegistry.fromJSON` is the same parser,
 * so a downloadable / user-defined set plugs in without code changes.
 *
 * Mirror of Android `PrintedPageTemplates.kt`.
 */

import Foundation

/// Normalised page rectangle: 0..1, origin top-left.
public struct PageZone: Equatable, Sendable {
    public let x: Double
    public let y: Double
    public let width: Double
    public let height: Double

    public init(x: Double, y: Double, width: Double, height: Double) {
        self.x = x
        self.y = y
        self.width = width
        self.height = height
    }

    public func contains(_ px: Double, _ py: Double) -> Bool {
        px >= x && px <= x + width && py >= y && py <= y + height
    }

    /// Default printed-header band: the top 8% of the page.
    public static let topBand = PageZone(x: 0, y: 0, width: 1, height: 0.08)
}

public struct PrintedPageTemplate: Equatable, Sendable {
    /// Stable id; what the printed mark encodes (`quickink://t/<id>`).
    public let id: String
    public let name: String
    /// Destination folder — a stable id from `workspace_seed.json`.
    public let folderId: String?
    /// Tag names applied to the capture; the first is the primary tag.
    public let tags: [String]
    public let titleZone: PageZone?
    public let dateZone: PageZone?
    /// Informational for now: checkbox → to-do extraction runs on
    /// every page whether or not it came from a template.
    public let hasCheckboxColumn: Bool
    /// Band the printed header words are searched in (fallback detector).
    public let headerZone: PageZone
    /// Printed header phrases; every word of one must appear in the
    /// header band. Include a word handwriting won't produce ("quickink").
    public let headerPhrases: [String]

    public init(
        id: String,
        name: String,
        folderId: String?,
        tags: [String],
        titleZone: PageZone?,
        dateZone: PageZone?,
        hasCheckboxColumn: Bool,
        headerZone: PageZone = .topBand,
        headerPhrases: [String] = []
    ) {
        self.id = id
        self.name = name
        self.folderId = folderId
        self.tags = tags
        self.titleZone = titleZone
        self.dateZone = dateZone
        self.hasCheckboxColumn = hasCheckboxColumn
        self.headerZone = headerZone
        self.headerPhrases = headerPhrases
    }
}

public struct PrintedPageTemplateRegistry: Sendable {
    public let templates: [PrintedPageTemplate]
    private let index: [String: PrintedPageTemplate]

    public init(templates: [PrintedPageTemplate]) {
        self.templates = templates
        var index: [String: PrintedPageTemplate] = [:]
        for t in templates where index[t.id.lowercased()] == nil { index[t.id.lowercased()] = t }
        self.index = index
    }

    public func byId(_ id: String) -> PrintedPageTemplate? { index[id.lowercased()] }

    /// The built-in set — mirror of `page_templates.json`.
    public static let `default` = PrintedPageTemplateRegistry(templates: PrintedPageTemplates.seed)

    /// Parse a `page_templates.json` document. Templates missing an
    /// `id` or `name` are skipped; unknown keys are ignored.
    public static func fromJSON(_ data: Data) -> PrintedPageTemplateRegistry {
        guard let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let list = root["templates"] as? [[String: Any]]
        else { return PrintedPageTemplateRegistry(templates: []) }

        func zone(_ v: Any?) -> PageZone? {
            guard let z = v as? [String: Any],
                  let x = (z["x"] as? NSNumber)?.doubleValue,
                  let y = (z["y"] as? NSNumber)?.doubleValue,
                  let w = (z["width"] as? NSNumber)?.doubleValue,
                  let h = (z["height"] as? NSNumber)?.doubleValue
            else { return nil }
            return PageZone(x: x, y: y, width: w, height: h)
        }

        let templates: [PrintedPageTemplate] = list.compactMap { o in
            guard let id = o["id"] as? String, let name = o["name"] as? String else { return nil }
            return PrintedPageTemplate(
                id: id,
                name: name,
                folderId: o["folder_id"] as? String,
                tags: (o["tags"] as? [Any])?.compactMap { $0 as? String } ?? [],
                titleZone: zone(o["title_zone"]),
                dateZone: zone(o["date_zone"]),
                hasCheckboxColumn: (o["has_checkbox_column"] as? Bool) ?? false,
                headerZone: zone(o["header_zone"]) ?? .topBand,
                headerPhrases: (o["header_phrases"] as? [Any])?.compactMap { $0 as? String } ?? []
            )
        }
        return PrintedPageTemplateRegistry(templates: templates)
    }
}

/// Hard-coded mirror of `shared/design-system/seeds/page_templates.json`.
public enum PrintedPageTemplates {
    private static let title = PageZone(x: 0.08, y: 0.08, width: 0.60, height: 0.08)
    private static let date = PageZone(x: 0.70, y: 0.08, width: 0.24, height: 0.08)

    public static let seed: [PrintedPageTemplate] = [
        PrintedPageTemplate(
            id: "meeting-notes", name: "Meeting notes", folderId: "projects", tags: ["meetings"],
            titleZone: title, dateZone: date, hasCheckboxColumn: true,
            headerPhrases: ["quickink meeting notes"]
        ),
        PrintedPageTemplate(
            id: "journal", name: "Journal", folderId: "lifestyle", tags: ["journal"],
            titleZone: title, dateZone: date, hasCheckboxColumn: false,
            headerPhrases: ["quickink journal"]
        ),
        PrintedPageTemplate(
            id: "todo", name: "To-do", folderId: "inbox", tags: ["todo"],
            titleZone: title, dateZone: date, hasCheckboxColumn: true,
            headerPhrases: ["quickink todo", "quickink to do"]
        ),
        PrintedPageTemplate(
            id: "ideas", name: "Ideas", folderId: "ideas", tags: ["ideas"],
            titleZone: title, dateZone: date, hasCheckboxColumn: false,
            headerPhrases: ["quickink ideas"]
        ),
    ]
}
