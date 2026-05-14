/*
 * PanchangaEntity.swift
 *
 * One row of the bundled Vontikoppal / Mysore Panchanga dataset
 * (`Resources/panchanga_2026_27.csv`). Each row maps a single
 * Gregorian date to the lunar reckoning that Karnataka's Smarta
 * tradition uses: lunar month (`masa`), bright/dark fortnight
 * (`paksha`), lunar day (`thithi` + `thithiNum`), and a free-form
 * `specialDay` column carrying festival or observance names.
 *
 * A handful of dates carry TWO rows because two tithis can land on
 * the same Gregorian date when the lunar day rolls over near
 * midnight. The repository returns a list per date for that reason —
 * never assume one row per date.
 *
 * Port of Releaf Android's `PanchangaEntity.kt`. Same field shape;
 * GRDB-backed instead of Room, so the entity is a plain Codable +
 * FetchableRecord (matches QuickInk's existing CaptureSummary pattern).
 */

import Foundation
import GRDB

public struct PanchangaEntity: Codable, FetchableRecord, PersistableRecord, Equatable, Sendable, Identifiable {
    /// Composite key `"<date>#<thithi_num>"` so dates with two tithis
    /// remain distinguishable. Idempotent insert key on bootstrap.
    public let id: String

    /// ISO Gregorian date, e.g. "2026-03-19".
    public let date: String

    /// Lunar month, e.g. "Chaitra", "Vaishakha", "Nija Jyeshtha".
    public let masa: String

    /// "Shukla" (waxing), "Krishna" (waning), rarely "Shukla/Krishna"
    /// on transition days.
    public let paksha: String

    /// Lunar-day name, e.g. "Pratipada", "Ekadashi", "Purnima".
    public let thithi: String

    /// Numeric tithi as TEXT — preserved verbatim because the CSV
    /// carries dual values like "5/6" on transition days.
    public let thithiNum: String

    /// Festival or observance text. Empty string when none.
    public let specialDay: String

    /// Lower-cased mirror of `specialDay` for case-insensitive LIKE.
    /// Computed on insert; never written from the UI.
    public let specialDayLowercase: String

    public init(
        id: String,
        date: String,
        masa: String,
        paksha: String,
        thithi: String,
        thithiNum: String,
        specialDay: String,
        specialDayLowercase: String
    ) {
        self.id = id
        self.date = date
        self.masa = masa
        self.paksha = paksha
        self.thithi = thithi
        self.thithiNum = thithiNum
        self.specialDay = specialDay
        self.specialDayLowercase = specialDayLowercase
    }

    public static let databaseTableName = "panchanga"

    public enum CodingKeys: String, CodingKey {
        case id
        case date
        case masa
        case paksha
        case thithi
        case thithiNum            = "thithi_num"
        case specialDay           = "special_day"
        case specialDayLowercase  = "special_day_lc"
    }
}
