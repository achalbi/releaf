import Foundation

// MARK: - Tag

public enum Tag: String, CaseIterable, Codable, Hashable {
    case home, work, recipes, personal

    public var label: String {
        switch self {
        case .home:     return "Home"
        case .work:     return "Work"
        case .recipes:  return "Recipes"
        case .personal: return "Personal"
        }
    }
}

// MARK: - TagFilter

public enum TagFilter: Hashable {
    case all
    case tag(Tag)

    public var label: String {
        switch self {
        case .all:           return "All"
        case .tag(let tag):  return tag.label
        }
    }
}

// MARK: - Capture types

/// Dominant capture flavour of a page — used as a hint by the
/// adapter and mock data, never as the source of truth. The page's
/// full content mix lives in `CaptureCounts` (photos, scans, voice,
/// todos, contacts, locations).
///
/// Only attachment-backed flavours have a value here. Notes-only
/// pages and empty pages have `RecentsPage.type == nil`.
public enum CaptureType: String, Codable, Hashable {
    case photo, voice
}

public enum PageSource: String, Codable, Hashable {
    case camera, library, scan, native
}

// MARK: - RecentsPage

public struct RecentsPage: Identifiable, Hashable {
    public let id: String
    public let dayId: String
    /// Dominant capture flavour. `nil` for notes-only and empty
    /// pages — the multi-surface mix is what the UI actually reads
    /// via `captureCounts`.
    public let type: CaptureType?
    public let source: PageSource
    public let createdAt: Date
    /// Last-modified timestamp — drives the EarlierGrid sort so the
    /// most-recently-touched page lands first / in the tall slot.
    /// Defaults to `createdAt` for mock data; the adapter overrides
    /// with the underlying NotepadEntry's `updatedAt`.
    public let updatedAt: Date
    public let title: String
    public let description: String
    public let tags: [Tag]
    public let mediaURL: URL?
    public let durationSec: Int?
    /// Per-page capture mix driving the hero pip row. Mock pages
    /// default to a single capture derived from `type`; the real
    /// adapter overrides with attachment-level counts so an entry
    /// with photo + voice + todos contributes a tick to each pip
    /// when that page is active.
    public let captureCounts: CaptureCounts

    public init(
        id: String = UUID().uuidString,
        dayId: String,
        type: CaptureType?,
        source: PageSource,
        createdAt: Date,
        updatedAt: Date? = nil,
        title: String,
        description: String,
        tags: [Tag] = [],
        mediaURL: URL? = nil,
        durationSec: Int? = nil,
        captureCounts: CaptureCounts? = nil
    ) {
        self.id = id
        self.dayId = dayId
        self.type = type
        self.source = source
        self.createdAt = createdAt
        self.updatedAt = updatedAt ?? createdAt
        self.title = title
        self.description = description
        self.tags = tags
        self.mediaURL = mediaURL
        self.durationSec = durationSec
        self.captureCounts = captureCounts ?? CaptureCounts.single(type: type)
    }

    /// Pages whose media originates outside the app (library or scan import).
    public var isImported: Bool {
        source == .library || source == .scan
    }
}

// MARK: - CaptureCounts

/// Per-page capture tally — one field per surface where the page can
/// carry user content. Six map to the picker cells / `CaptureMode`
/// (minus `.overview`) plus a seventh for the page's own notes body:
///
///   * `photos`    — camera attachments
///   * `scans`     — scanned-document attachments (kept distinct from
///                   photos: a scanned receipt and a snapshot are two
///                   different deliberate captures)
///   * `voice`     — voice memos
///   * `todos`     — items in the page's todo list
///   * `contacts`  — saved contacts on the page
///   * `locations` — pinned locations on the page
///   * `notes`     — 0 or 1, signals whether the page's free-text
///                   body is non-blank (the body is a single field on
///                   the entry, not a list, so this is binary)
///
/// The hero's pip row renders one pip per non-zero field; the
/// EarlierGrid card footer renders the sum.
public struct CaptureCounts: Hashable {
    public let photos:    Int
    public let scans:     Int
    public let voice:     Int
    public let todos:     Int
    public let contacts:  Int
    public let locations: Int
    public let notes:     Int

    public init(
        photos:    Int = 0,
        scans:     Int = 0,
        voice:     Int = 0,
        todos:     Int = 0,
        contacts:  Int = 0,
        locations: Int = 0,
        notes:     Int = 0
    ) {
        self.photos    = photos
        self.scans     = scans
        self.voice     = voice
        self.todos     = todos
        self.contacts  = contacts
        self.locations = locations
        self.notes     = notes
    }

    /// Sum across every surface — used by EarlierGrid cards to show
    /// a single "X captures" tally per page.
    public var total: Int {
        photos + scans + voice + todos + contacts + locations + notes
    }

    /// Element-wise sum — used by the adapter to derive a day-level
    /// total from its pages' counts.
    public static func + (lhs: CaptureCounts, rhs: CaptureCounts) -> CaptureCounts {
        CaptureCounts(
            photos:    lhs.photos    + rhs.photos,
            scans:     lhs.scans     + rhs.scans,
            voice:     lhs.voice     + rhs.voice,
            todos:     lhs.todos     + rhs.todos,
            contacts:  lhs.contacts  + rhs.contacts,
            locations: lhs.locations + rhs.locations,
            notes:     lhs.notes     + rhs.notes
        )
    }

    /// Counts for a page whose dominant flavour is `type`. Used by
    /// mock data where each page corresponds 1:1 to a single capture,
    /// and as the default per-page count on `RecentsPage` when the
    /// caller hasn't computed attachment-level counts. A `nil` type
    /// — notes-only page — yields a single notes tick.
    public static func single(type: CaptureType?) -> CaptureCounts {
        switch type {
        case .photo: return CaptureCounts(photos: 1)
        case .voice: return CaptureCounts(voice: 1)
        case .none:  return CaptureCounts(notes:  1)
        }
    }

    /// Fallback derivation from a page list — sums each page's
    /// `captureCounts`.
    public static func from(pages: [RecentsPage]) -> CaptureCounts {
        pages.reduce(CaptureCounts()) { acc, p in acc + p.captureCounts }
    }
}

// MARK: - RecentsDay

public struct RecentsDay: Identifiable, Hashable {
    /// `id` is a `yyyy-MM-dd` string; serves as the foreign key on `RecentsPage.dayId`.
    public let id: String
    public let date: Date
    public let theme: String
    public let pages: [RecentsPage]
    /// Capture counts for the pip row. Pre-computed from raw
    /// attachment data when sourced from the adapter; falls back to a
    /// derivation from `pages` for mock construction.
    public let captureCounts: CaptureCounts

    public init(
        id: String,
        date: Date,
        theme: String,
        pages: [RecentsPage],
        captureCounts: CaptureCounts? = nil
    ) {
        self.id = id
        self.date = date
        self.theme = theme
        self.pages = pages
        self.captureCounts = captureCounts ?? CaptureCounts.from(pages: pages)
    }

}

// MARK: - RecentsWeekDay

public struct RecentsWeekDay: Identifiable, Hashable {
    public var id: Date { date }
    public let date: Date
    public let pageCount: Int
    public let isToday: Bool

    public init(date: Date, pageCount: Int, isToday: Bool) {
        self.date = date
        self.pageCount = pageCount
        self.isToday = isToday
    }
}

// MARK: - RecentsTotals

public struct RecentsTotals: Hashable {
    public let dayStreak: Int
    public let bloomedThisMonth: Int
    public let daysInMonth: Int
    public let topTheme: Tag?

    public init(dayStreak: Int, bloomedThisMonth: Int, daysInMonth: Int, topTheme: Tag?) {
        self.dayStreak = dayStreak
        self.bloomedThisMonth = bloomedThisMonth
        self.daysInMonth = daysInMonth
        self.topTheme = topTheme
    }
}

// MARK: - RecentsDayStats

public struct RecentsDayStats: Hashable {
    public let today: RecentsDay?
    public let weekPulse: [RecentsWeekDay]
    public let earlier: [RecentsDay]
    public let totals: RecentsTotals

    public init(today: RecentsDay?, weekPulse: [RecentsWeekDay], earlier: [RecentsDay], totals: RecentsTotals) {
        self.today = today
        self.weekPulse = weekPulse
        self.earlier = earlier
        self.totals = totals
    }
}
