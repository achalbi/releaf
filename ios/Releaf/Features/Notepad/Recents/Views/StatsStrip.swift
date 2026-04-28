import SwiftUI
import ReleafDesignSystem

/// Three-stat header card. bgSurfaceMuted background, vertical dividers between cells.
struct StatsStrip: View {
    let totals: RecentsTotals
    /// Three-letter month name (e.g. "Apr"). Driven by the screen so the
    /// "Bloomed in <month>" copy follows the calendar instead of being frozen.
    let monthLabel: String

    var body: some View {
        // Asymmetric weights — the middle cell ("Bloomed in <month>")
        // gets the longest label, so we trade ~10% off each side cell
        // to let the middle cell breathe (no awkward wrapping on
        // narrow screens, "X / 30" suffix sits comfortably).
        GeometryReader { geo in
            // Subtract 2pt for the two 1pt-wide dividers, then split
            // the remaining width using the 0.9 / 1.2 / 0.9 ratio.
            let cellSpace = max(0, geo.size.width - 2)
            let r: CGFloat = 0.9 + 1.2 + 0.9
            let side = cellSpace * (0.9 / r)
            let mid  = cellSpace * (1.2 / r)
            HStack(spacing: 0) {
                statCell(
                    number: "\(totals.dayStreak)",
                    suffix: nil,
                    label: "Day streak"
                )
                .frame(width: side)
                divider
                statCell(
                    number: "\(totals.bloomedThisMonth)",
                    suffix: "/\(totals.daysInMonth)",
                    label: "Bloomed in \(monthLabel)"
                )
                .frame(width: mid)
                divider
                statCell(
                    number: totals.topTheme?.label ?? "—",
                    suffix: nil,
                    label: "Top theme"
                )
                .frame(width: side)
            }
        }
        // Strip height matches cell content (number + label + spacing
        // ≈ 37pt). The outer .padding(.vertical, 14) below adds the
        // ~28pt of breathing room.
        .frame(height: 40)
        .padding(.vertical, 14)
        .padding(.horizontal, 14)
        .background(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .fill(Color.bgSurfaceMuted)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .stroke(Color.borderFaint, lineWidth: 1)
        )
    }

    // MARK: - Cells

    @ViewBuilder
    private func statCell(number: String, suffix: String?, label: String) -> some View {
        VStack(alignment: .center, spacing: 4) {
            HStack(alignment: .firstTextBaseline, spacing: 2) {
                Text(number)
                    .font(Typography.statNumberFont)
                    .foregroundColor(.textPrimary)
                if let suffix {
                    // Suffix shares the serif family of
                    // `Typography.statNumberFont` so "<n>/<total>"
                    // reads as one typographic cluster — large
                    // serif numeral with a smaller, lighter serif
                    // denominator beside it.
                    Text(suffix)
                        .font(.system(size: 12, weight: .regular, design: .serif))
                        .foregroundColor(.textGreenMuted)
                }
            }
            Text(label.uppercased())
                .font(Typography.microWideFont)
                .kerning(1.2)
                .foregroundColor(.textGreenMuted)
        }
        .frame(maxWidth: .infinity)
    }

    private var divider: some View {
        Rectangle()
            .fill(Color.borderDivider)
            .frame(width: 1, height: 28)
    }
}

#if DEBUG
#Preview {
    StatsStrip(totals: MockData.totals, monthLabel: "Apr")
        .padding()
        .background(Color.bgCanvas)
}
#endif
