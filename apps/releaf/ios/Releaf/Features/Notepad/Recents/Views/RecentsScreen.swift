import SwiftUI
import ReleafDesignSystem

/// Top-level composition for the redesigned Recents screen. Designed to drop
/// into any SwiftUI host (e.g. `WindowGroup { RecentsScreen() }` or another
/// container's `body`).
public struct RecentsScreen: View {

    private let stats: RecentsDayStats
    private let onOpenPage:    (RecentsPage) -> Void
    private let onPickMode:    (CaptureMode) -> Void

    public init(
        stats: RecentsDayStats? = nil,
        onOpenPage: @escaping (RecentsPage) -> Void = { _ in },
        /// Picker cells in the new-entry slot (Photo / Scan / Voice /
        /// Todo / Contact) and the new-entry footer CTA all funnel
        /// through this callback. The host wires it to whatever
        /// "compose new" routing it has — typically opening the editor.
        /// Once the editor learns to focus a specific tab, the
        /// `CaptureMode` forwarded here is the right value.
        onPickMode: @escaping (CaptureMode) -> Void = { _ in }
    ) {
        // Default to mock data so previews and standalone hosts render
        // out of the box. Real callers pass `RecentsAdapter.fromState(...)`.
        self.stats = stats ?? MockData.dayStats
        self.onOpenPage = onOpenPage
        self.onPickMode = onPickMode
    }

    public var body: some View {
        // Host (`NotepadView`) already wraps this branch in a `ScrollView`,
        // so RecentsScreen is rendered as an inline `VStack` — no own
        // ScrollView, no overlay bottom nav. Nesting ScrollViews would
        // produce a janky UX where the inner scroll fights the outer.
        //
        // The host also already renders the eyebrow, H1, Day/Recents
        // toggle, and category filter row above this branch, so this
        // screen starts straight at the stats strip and never repeats them.
        VStack(alignment: .leading, spacing: 18) {
            // -14pt horizontal cancels the parent VStack's 14pt margin
            // entirely, so the strip spans the full recents column
            // (only the host screen's outer padding shows on the
            // sides), wider than the today hero card below it.
            StatsStrip(totals: stats.totals, monthLabel: monthLabel)
                .padding(.horizontal, -14)

            sectionLabel("TODAY")
            TodayHero(
                day: stats.today,
                onOpenPage: onOpenPage,
                onPickMode: onPickMode
            )

            sectionLabel("THIS WEEK")
            WeekPulse(days: stats.weekPulse)

            sectionLabel("EARLIER IN \(earlierMonthLabel)")
            EarlierGrid(
                pages: earlierPages,
                onOpenPage: onOpenPage
            )

            // Footnote legend explaining the amber dot — only renders
            // when at least one visible page (today or earlier) is
            // actually imported. Hidden when there's nothing to
            // explain so the screen doesn't accumulate a permanently-
            // visible remark for the typical user with no imported
            // pages.
            if anyImportedVisible {
                importedLegend
                    .padding(.top, -2)
            }
        }
        .padding(.horizontal, 14)
        .padding(.top, 16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.bgCanvas)
    }

    private var anyImportedVisible: Bool {
        let todayPages = stats.today?.pages ?? []
        return (todayPages + earlierPages).contains(where: { $0.isImported })
    }

    /// Small footnote — amber dot + caption — explaining that pages
    /// marked with the same dot in the EarlierGrid card footers were
    /// imported from the photo library or a document scan.
    private var importedLegend: some View {
        HStack(spacing: 6) {
            Circle()
                .fill(Color.accentImport)
                .frame(width: 6, height: 6)
            Text("Imported from library or a scan")
                .font(.system(size: 11, weight: .regular))
                .foregroundColor(.textMuted)
        }
    }

    private var monthLabel: String {
        let date = stats.today?.date ?? stats.weekPulse.last?.date ?? Date()
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "MMM"
        return f.string(from: date)
    }

    private var earlierMonthLabel: String {
        let date = stats.earlier.first?.date ?? stats.today?.date ?? Date()
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "MMMM"
        return f.string(from: date).uppercased()
    }

    /// Flattened, last-modified-desc list of past pages, capped at
    /// 14 cards. The EarlierGrid is a page feed now (one card per
    /// page), not a day grid — empty days simply contribute zero
    /// cards.
    private var earlierPages: [RecentsPage] {
        Array(
            stats.earlier
                .flatMap(\.pages)
                .sorted(by: { $0.updatedAt > $1.updatedAt })
                .prefix(14)
        )
    }

    // (Brand label, "Recent garden" H1, and Day/Recents pill toggle
    // were intentionally removed: the host already renders all three
    // above this branch, and rendering them again here produced two
    // visually-identical headers stacked on top of each other.)

    // MARK: - Section label

    private func sectionLabel(_ text: String) -> some View {
        Text(text)
            .font(Typography.microWideFont)
            .kerning(1.6)
            .foregroundColor(.textGreenMuted)
            .padding(.top, 6)
    }

    // (Tag filtering removed alongside the local TagChips row; the
    // host's category filter row above this branch already narrows
    // entries before they reach `stats`.)
}

#if DEBUG
#Preview {
    RecentsScreen()
}
#endif
