import SwiftUI
import ReleafDesignSystem

/// 7 vertical-bar cells showing the week's page counts.
struct WeekPulse: View {
    let days: [RecentsWeekDay]

    private static let weekdayLetterFormatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.calendar = Calendar(identifier: .gregorian)
        f.timeZone = TimeZone(identifier: "UTC")
        f.dateFormat = "EEEEE" // narrow weekday — single letter
        return f
    }()

    private static let dayNumberFormatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.calendar = Calendar(identifier: .gregorian)
        f.timeZone = TimeZone(identifier: "UTC")
        f.dateFormat = "d"
        return f
    }()

    var body: some View {
        HStack(alignment: .bottom, spacing: 6) {
            ForEach(days) { day in
                cell(for: day)
                    .frame(maxWidth: .infinity)
            }
        }
        .padding(.vertical, 6)
    }

    // MARK: - Cell

    @ViewBuilder
    private func cell(for day: RecentsWeekDay) -> some View {
        let height: CGFloat = 44
        let radius: CGFloat = 12
        let fillRatio = min(1.0, max(0.0, Double(day.pageCount) / 6.0))

        VStack(spacing: 8) {
            // Stout cell — track + fill both span the full cell width so
            // the strip reads as a row of pills (matches the reference
            // design). Fill rises from the bottom; tier color encodes
            // the day's page count.
            ZStack(alignment: .bottom) {
                RoundedRectangle(cornerRadius: radius, style: .continuous)
                    .fill(Color.bgChip)
                    .frame(maxWidth: .infinity)
                    .frame(height: height)

                if day.pageCount > 0 {
                    RoundedRectangle(cornerRadius: radius, style: .continuous)
                        .fill(tier(for: day.pageCount))
                        .frame(maxWidth: .infinity)
                        .frame(height: max(10, height * CGFloat(fillRatio)))
                }
            }
            .frame(height: height)

            VStack(spacing: 4) {
                Text(WeekPulse.weekdayLetterFormatter.string(from: day.date))
                    .font(Typography.microFont)
                    .foregroundColor(day.isToday ? .textGreen : .textMuted)
                Text(WeekPulse.dayNumberFormatter.string(from: day.date))
                    .font(.system(size: 13, weight: day.isToday ? .medium : .regular))
                    .foregroundColor(day.isToday ? .textGreen : .textSecondary)
            }
        }
    }

    /// Color tiers by daily page count.
    private func tier(for count: Int) -> Color {
        switch count {
        case 0:        return .clear
        case 1...2:    return .green200
        case 3...5:    return .green400
        default:       return .green800 // 6+
        }
    }
}

#if DEBUG
#Preview {
    WeekPulse(days: MockData.weekPulse)
        .padding()
        .background(Color.bgCanvas)
}
#endif
