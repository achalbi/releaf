import SwiftUI
import ReleafDesignSystem

/// 2-column grid of past pages — one card per `RecentsPage`. The
/// caller passes the list pre-sorted by `updatedAt` desc; the most-
/// recently-modified page is auto-promoted to a tall card spanning
/// two rows on the left, the rest flow as regular tiles in column-2
/// / paired rows below.
///
/// Empty days don't surface here anymore — the grid is a *page* feed,
/// so days with no captures simply contribute zero cards.
struct EarlierGrid: View {
    let pages: [RecentsPage]
    /// Tap a card to open that specific page. Default is no-op so
    /// the screen can drop the grid in without forcing the host to
    /// wire up navigation.
    var onOpenPage: (RecentsPage) -> Void = { _ in }

    var body: some View {
        if pages.isEmpty {
            EmptyView()
        } else {
            content
        }
    }

    private var content: some View {
        let featured = pages.first!
        let rest = Array(pages.dropFirst())
        // Right column hosts the next two pages, then any remaining
        // pages flow in 2-up rows below.
        let rightTop = rest.first
        let rightBottom = rest.dropFirst().first
        let below = rest.count > 2 ? Array(rest[2...]) : []

        return VStack(spacing: 12) {
            HStack(alignment: .top, spacing: 12) {
                // Tall featured (most-recently-modified page).
                Button { onOpenPage(featured) } label: {
                    PageCard(page: featured, variant: .tall)
                }
                .buttonStyle(.plain)
                .frame(maxWidth: .infinity)

                VStack(spacing: 12) {
                    if let p = rightTop {
                        Button { onOpenPage(p) } label: {
                            PageCard(page: p, variant: .regular)
                        }
                        .buttonStyle(.plain)
                    }
                    if let p = rightBottom {
                        Button { onOpenPage(p) } label: {
                            PageCard(page: p, variant: .regular)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .top)
            }
            // Remaining cards in 2-up pairs.
            ForEach(Array(stride(from: 0, to: below.count, by: 2)), id: \.self) { idx in
                HStack(alignment: .top, spacing: 12) {
                    Button { onOpenPage(below[idx]) } label: {
                        PageCard(page: below[idx], variant: .regular)
                    }
                    .buttonStyle(.plain)
                    .frame(maxWidth: .infinity)
                    if idx + 1 < below.count {
                        Button { onOpenPage(below[idx + 1]) } label: {
                            PageCard(page: below[idx + 1], variant: .regular)
                        }
                        .buttonStyle(.plain)
                        .frame(maxWidth: .infinity)
                    } else {
                        Color.clear.frame(maxWidth: .infinity)
                    }
                }
            }
        }
    }
}

// MARK: - PageCard

struct PageCard: View {
    enum Variant { case tall, regular }

    let page: RecentsPage
    let variant: Variant

    private static let dateFmt: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.calendar = Calendar(identifier: .gregorian)
        f.timeZone = TimeZone(identifier: "UTC")
        f.dateFormat = "MMM d"
        return f
    }()

    private static let timeFmt: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.calendar = Calendar(identifier: .gregorian)
        f.timeZone = TimeZone(identifier: "UTC")
        f.dateFormat = "h:mm a"
        return f
    }()

    var body: some View {
        switch variant {
        case .tall:    tallCard
        case .regular: regularCard
        }
    }

    // MARK: Tall

    private var tallCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            dateLabel
            Text(page.title.isEmpty ? "untitled" : page.title)
                .font(Typography.h2Font)
                .foregroundColor(.textGreen)
                .lineLimit(2)
                .fixedSize(horizontal: false, vertical: true)

            if let description = description {
                Text(description)
                    .font(Typography.captionFont)
                    .foregroundColor(.textGreen)
                    .lineLimit(5)
                    .fixedSize(horizontal: false, vertical: true)
            }

            Spacer(minLength: 0)
            // Same surface order as the hero pip row, but coloured
            // for the leaf-on-leaf tall card. Each pill carries the
            // canonical `CaptureMode.systemIcon` plus the count, so
            // the user can see *what* the page contains and *how
            // much* at a glance — independent of the footer's
            // category + total tally below.
            earlierCapturePips
            footer(primaryColor: .textGreen, mutedColor: .textGreenMuted)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .fill(Color.bgFeatured)
        )
    }

    /// Capture-pip row used by the tall card. Mirrors the hero pip
    /// row's shape (icon + count, capsule bg) but with the leaf-on-
    /// leaf palette tuned for the tall card's `bgFeatured` (#DDEACD)
    /// surface. Pills with a zero count are skipped; if every count
    /// is zero the row collapses entirely so the spacing above the
    /// footer doesn't gain dead height.
    @ViewBuilder
    private var earlierCapturePips: some View {
        let counts = page.captureCounts
        if counts.total > 0 {
            HStack(spacing: 6) {
                if counts.photos    > 0 { earlierPip(CaptureMode.photos.systemIcon,   count: counts.photos) }
                if counts.scans     > 0 { earlierPip(CaptureMode.scans.systemIcon,    count: counts.scans) }
                if counts.voice     > 0 { earlierPip(CaptureMode.voice.systemIcon,    count: counts.voice) }
                if counts.todos     > 0 { earlierPip(CaptureMode.todo.systemIcon,     count: counts.todos) }
                if counts.contacts  > 0 { earlierPip(CaptureMode.contacts.systemIcon, count: counts.contacts) }
                if counts.locations > 0 { earlierPip(CaptureMode.location.systemIcon, count: counts.locations) }
                if counts.notes     > 0 { earlierPip("note.text",                     count: counts.notes) }
            }
            .padding(.bottom, 4)
        }
    }

    /// Slightly darker leaf tint than `bgFeatured` (#DDEACD) so the
    /// pill is legible without going loud. Deep-green icon matches
    /// the card's title typography; the count digit drops to
    /// `Color.green600` so the icon stays the primary read.
    private func earlierPip(_ systemName: String, count: Int) -> some View {
        HStack(spacing: 4) {
            Image(systemName: systemName)
                .font(.system(size: 11, weight: .medium))
                .foregroundColor(.green800)
            Text("\(count)")
                .font(.system(size: 11, weight: .medium))
                .foregroundColor(.green600)
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 4)
        .background(Capsule().fill(Color.green200))
    }

    // MARK: Regular

    private var regularCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            dateLabel
            Text(page.title.isEmpty ? "untitled" : page.title)
                .font(.system(size: 15, weight: .regular, design: .serif))
                .foregroundColor(.textPrimary)
                .lineLimit(2)
                .fixedSize(horizontal: false, vertical: true)
            if let description = description {
                Text(description)
                    .font(Typography.captionFont)
                    .foregroundColor(.textMuted)
                    .lineLimit(3)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 0)
            footer(primaryColor: .textGreen, mutedColor: .textMuted)
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .fill(Color.bgSurface)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .stroke(Color.borderFaint, lineWidth: 1)
        )
    }

    // MARK: Shared

    private var dateLabel: some View {
        Text(headerLabel.uppercased())
            .font(Typography.microWideFont)
            .kerning(1.4)
            .foregroundColor(.textGreenMuted)
    }

    private var headerLabel: String {
        let date = PageCard.dateFmt.string(from: page.createdAt)
        let time = PageCard.timeFmt.string(from: page.createdAt)
        return "\(date) · \(time)"
    }

    private var description: String? {
        let trimmed = page.description.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }

    /// Bottom row: page category on the left, total capture count on
    /// the right. An amber dot precedes the category for imported
    /// pages. Both halves drop out gracefully when empty so the row
    /// never looks lopsided.
    @ViewBuilder
    private func footer(primaryColor: Color, mutedColor: Color) -> some View {
        let category = page.tags.first?.label
        let total = page.captureCounts.total
        if category != nil || total > 0 {
            HStack(spacing: 6) {
                if page.isImported {
                    Circle()
                        .fill(Color.accentImport)
                        .frame(width: 6, height: 6)
                }
                if let category {
                    Text(category.uppercased())
                        .font(Typography.microWideFont)
                        .kerning(1.2)
                        .foregroundColor(primaryColor)
                }
                Spacer(minLength: 0)
                if total > 0 {
                    Text("\(total) \(total == 1 ? "CAPTURE" : "CAPTURES")")
                        .font(Typography.microWideFont)
                        .kerning(1.2)
                        .foregroundColor(mutedColor)
                }
            }
        }
    }
}

#if DEBUG
#Preview {
    let pages = MockData.earlier
        .flatMap { $0.pages }
        .sorted { $0.updatedAt > $1.updatedAt }
    return EarlierGrid(pages: pages)
        .padding()
        .background(Color.bgCanvas)
}
#endif
