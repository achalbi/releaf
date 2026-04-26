/*
 * ReleafImpact.swift
 *
 * Pure value-type that turns a page's capture counts into the two
 * numbers the Re-Leaf strip displays: sheets of paper saved and the
 * fraction of a mature pine those sheets represent.
 *
 * The per-capture multipliers + sheets-per-tree constant come from
 * `AppMetrics.generated.swift`, which is emitted from the same JSON
 * the Android side reads — both platforms compute identical numbers.
 *
 * No `Page` import here on purpose: this file is in the DesignSystem
 * target and shouldn't pull in the Data layer. Call sites in the page
 * detail screen pluck the counts off a Page and pass them in.
 */

import Foundation

public struct ReleafImpact: Equatable, Sendable {
    /// Sheets of paper this page (or aggregate) replaced.
    public let sheetsSaved: Double
    /// Fraction of one mature pine. `sheetsSaved / sheetsPerTree`.
    public let treeFraction: Double

    public init(
        photos: Int = 0,
        voiceNotes: Int = 0,
        todoItems: Int = 0,
        scans: Int = 0,
        contacts: Int = 0,
        places: Int = 0,
        notes: Int = 0
    ) {
        let m = AppMetrics.PaperPerCapture.self
        let sheets =
              Double(photos)     * m.photo
            + Double(voiceNotes) * m.voice
            + Double(todoItems)  * m.todo
            + Double(scans)      * m.scan
            + Double(contacts)   * m.contact
            + Double(places)     * m.place
            + Double(notes)      * m.note
        self.sheetsSaved  = sheets
        self.treeFraction = sheets / AppMetrics.sheetsPerTree
    }

    /// Tile-friendly sheets readout — "3.2", "0.5", "12.0".
    public var formattedSheets: String {
        String(format: "%.1f", sheetsSaved)
    }

    /// Tile-friendly tree readout. Switches unit at small values so a
    /// single-page contribution reads as "0.03% of a pine" rather than
    /// "0.00 trees standing", which would look broken.
    public var treeReadout: (number: String, unit: String) {
        if treeFraction >= 0.01 {
            return (String(format: "%.2f", treeFraction), "trees standing")
        } else if treeFraction > 0 {
            let pct = treeFraction * 100
            return (String(format: "%.2f%%", pct), "of one pine")
        } else {
            return ("0.00", "trees standing")
        }
    }
}
