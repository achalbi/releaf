import Foundation

/// Deterministic sample data used to render the Recents screen offline.
///
/// Every page is attachment-backed (`.photo` or `.voice`) — the
/// `.journal` and `.mood` page flavours have been retired along
/// with the corresponding `CaptureType` cases. A notes-only mock
/// page would be expressed as `type: nil`.
enum MockData {

    // MARK: - Helpers

    static let dayIdFormatter: DateFormatter = {
        let f = DateFormatter()
        f.calendar = Calendar(identifier: .gregorian)
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "UTC")
        f.dateFormat = "yyyy-MM-dd"
        return f
    }()

    /// Build a deterministic Date for `(year, month, day, hour, minute)` in UTC.
    static func date(_ year: Int, _ month: Int, _ day: Int, _ hour: Int = 0, _ minute: Int = 0) -> Date {
        var c = DateComponents()
        c.year = year
        c.month = month
        c.day = day
        c.hour = hour
        c.minute = minute
        c.timeZone = TimeZone(identifier: "UTC")
        return Calendar(identifier: .gregorian).date(from: c) ?? Date()
    }

    static func dayId(_ year: Int, _ month: Int, _ day: Int) -> String {
        dayIdFormatter.string(from: date(year, month, day))
    }

    // MARK: - Today (2026-04-26)

    static let todayId = dayId(2026, 4, 26)

    static let todayPages: [RecentsPage] = [
        RecentsPage(
            id: "p-26-1",
            dayId: todayId,
            type: .photo,
            source: .camera,
            createdAt: date(2026, 4, 26, 7, 32),
            title: "morning light through the window",
            description: "the new shoots in the planter caught the sun before the kettle boiled.",
            tags: [.home, .personal],
            mediaURL: nil
        ),
        RecentsPage(
            id: "p-26-2",
            dayId: todayId,
            type: .photo,
            source: .scan,
            createdAt: date(2026, 4, 26, 13, 4),
            title: "lunch receipt — old yard cafe",
            description: "scanned for the books. tagging recipes — they listed the herb mix on the back.",
            tags: [.recipes, .work],
            mediaURL: nil
        ),
        RecentsPage(
            id: "p-26-3",
            dayId: todayId,
            type: .voice,
            source: .native,
            createdAt: date(2026, 4, 26, 20, 15),
            title: "voice note — evening review",
            description: "talked through tomorrow's plan while doing the dishes. better than typing it out.",
            tags: [.personal, .home],
            durationSec: 42
        )
    ]

    static let today: RecentsDay = RecentsDay(
        id: todayId,
        date: date(2026, 4, 26),
        theme: "jatamansi",
        pages: todayPages
    )

    // MARK: - Earlier days

    static let earlier: [RecentsDay] = [
        // Apr 25 — featured / tall
        RecentsDay(
            id: dayId(2026, 4, 25),
            date: date(2026, 4, 25),
            theme: "daily capture",
            pages: [
                RecentsPage(id: "p-25-1", dayId: dayId(2026,4,25), type: .photo, source: .camera,  createdAt: date(2026,4,25, 8, 0), title: "balcony herbs", description: "first basil leaves of the season.",                       tags: [.home]),
                RecentsPage(id: "p-25-2", dayId: dayId(2026,4,25), type: .photo, source: .library, createdAt: date(2026,4,25,12,15), title: "lunch with k",  description: "imported from camera roll.",                              tags: [.personal]),
                RecentsPage(id: "p-25-3", dayId: dayId(2026,4,25), type: .voice, source: .native,  createdAt: date(2026,4,25,15,45), title: "voice memo",    description: "thoughts while walking back from the post office.",        tags: [.personal], durationSec: 67)
            ]
        ),
        // Apr 24
        RecentsDay(
            id: dayId(2026, 4, 24),
            date: date(2026, 4, 24),
            theme: "hello",
            pages: [
                RecentsPage(id: "p-24-1", dayId: dayId(2026,4,24), type: .photo, source: .camera,  createdAt: date(2026,4,24, 9, 0), title: "first cup",  description: "",                      tags: [.home]),
                RecentsPage(id: "p-24-2", dayId: dayId(2026,4,24), type: .photo, source: .library, createdAt: date(2026,4,24,14, 0), title: "old photo",  description: "imported from album.",  tags: [.personal]),
                RecentsPage(id: "p-24-3", dayId: dayId(2026,4,24), type: .voice, source: .native,  createdAt: date(2026,4,24,22, 0), title: "ramble",     description: "",                      tags: [.personal], durationSec: 38)
            ]
        ),
        // Apr 23
        RecentsDay(
            id: dayId(2026, 4, 23),
            date: date(2026, 4, 23),
            theme: "xoriant games day",
            pages: [
                RecentsPage(id: "p-23-1", dayId: dayId(2026,4,23), type: .photo, source: .camera, createdAt: date(2026,4,23,12, 0), title: "table tennis", description: "office tournament round one.", tags: [.work])
            ]
        ),
        // Apr 22
        RecentsDay(
            id: dayId(2026, 4, 22),
            date: date(2026, 4, 22),
            theme: "twak",
            pages: [
                RecentsPage(id: "p-22-1", dayId: dayId(2026,4,22), type: .voice, source: .native, createdAt: date(2026,4,22,19, 5), title: "voice — twak", description: "trying to define the feeling out loud.", tags: [.personal])
            ]
        ),
        // Apr 21 — empty
        RecentsDay(
            id: dayId(2026, 4, 21),
            date: date(2026, 4, 21),
            theme: "",
            pages: []
        ),
        // Apr 20 — empty
        RecentsDay(
            id: dayId(2026, 4, 20),
            date: date(2026, 4, 20),
            theme: "",
            pages: []
        )
    ]

    // MARK: - WeekPulse — 7 days through today, oldest → newest

    static let weekPulse: [RecentsWeekDay] = {
        let counts = [1, 2, 1, 4, 5, 3, 5]
        // 7 days ending on 2026-04-26
        let dates: [Date] = (0..<7).map { i in
            date(2026, 4, 20 + i)
        }
        return zip(dates, counts).map { (d, c) in
            RecentsWeekDay(date: d, pageCount: c, isToday: Calendar(identifier: .gregorian).isDate(d, inSameDayAs: date(2026, 4, 26)))
        }
    }()

    // MARK: - Totals

    static let totals = RecentsTotals(
        dayStreak: 12,
        bloomedThisMonth: 22,
        daysInMonth: 30,
        topTheme: .personal
    )

    // MARK: - Bundled DayStats

    static let dayStats = RecentsDayStats(
        today: today,
        weekPulse: weekPulse,
        earlier: earlier,
        totals: totals
    )
}
