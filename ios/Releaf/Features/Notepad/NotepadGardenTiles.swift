/*
 * NotepadGardenTiles.swift
 *
 * The Recents view — a 2-column ragged masonry of "garden plot" tiles
 * for older days, with today's plot rendered as a full-width hero tile
 * up top in deep canopy + coral border. Tile color encodes capture
 * density (mint = 1, leaf-green = 2–3, deep canopy = 4+) and tiny dots
 * inside the tile count the captures. Empty days appear as hollow
 * dashed-outline tiles with "no entry" so gaps stay legible.
 */

import SwiftUI
import ReleafDesignSystem
import ReleafData

private let deepFill   = Color(red: 0x3E / 255.0, green: 0x6B / 255.0, blue: 0x3B / 255.0)
private let midFill    = Color(red: 0x7A / 255.0, green: 0xA8 / 255.0, blue: 0x74 / 255.0)
private let lightFill  = Color(red: 0xC0 / 255.0, green: 0xDD / 255.0, blue: 0x97 / 255.0)
private let mintFill   = Color(red: 0xD9 / 255.0, green: 0xED / 255.0, blue: 0xE2 / 255.0)
private let emptyStrk  = Color(red: 0x1E / 255.0, green: 0x59 / 255.0, blue: 0x43 / 255.0)
private let mutedDark  = Color(red: 0x88 / 255.0, green: 0x87 / 255.0, blue: 0x80 / 255.0)
private let inkOnDark  = Color(red: 0xFF / 255.0, green: 0xF8 / 255.0, blue: 0xEE / 255.0)
private let inkOnLight = Color(red: 0x1E / 255.0, green: 0x59 / 255.0, blue: 0x43 / 255.0)
private let creamSoft  = Color(red: 0xFC / 255.0, green: 0xEA / 255.0, blue: 0xE0 / 255.0)
private let mintSoft   = Color(red: 0xD9 / 255.0, green: 0xED / 255.0, blue: 0xE2 / 255.0)

private func tileFill(for density: DayDensity) -> Color {
    switch density {
    case .empty: return Color.clear
    case .light: return mintFill
    case .mid:   return midFill
    case .deep:  return deepFill
    }
}

private func ink(for density: DayDensity) -> Color {
    switch density {
    case .deep, .mid: return inkOnDark
    default:          return inkOnLight
    }
}

private func tileMinHeight(for density: DayDensity) -> CGFloat {
    switch density {
    case .empty: return 56
    case .light: return 76
    case .mid:   return 92
    case .deep:  return 112
    }
}

public struct NotepadGardenTiles: View {
    let today: DayCount
    let earlier: [DayCount]
    let onTodayTap: () -> Void
    let onDayTap: (DayCount) -> Void

    public init(
        today: DayCount,
        earlier: [DayCount],
        onTodayTap: @escaping () -> Void,
        onDayTap: @escaping (DayCount) -> Void
    ) {
        self.today = today
        self.earlier = earlier
        self.onTodayTap = onTodayTap
        self.onDayTap = onDayTap
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: AppSpacing.s4) {
            TodayHeroTile(today: today, onTap: onTodayTap)

            if !earlier.isEmpty {
                Text("EARLIER IN \(monthLabel(today.date))")
                    .font(AppText.eyebrow)
                    .foregroundStyle(AppColors.themeGreenDeep)

                EarlierMasonry(days: earlier, onDayTap: onDayTap)
            }
        }
    }
}

// MARK: - Today hero tile

private struct TodayHeroTile: View {
    let today: DayCount
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            VStack(alignment: .leading, spacing: AppSpacing.s3) {
                Text("TODAY · \(dayHeader(today.date))")
                    .font(AppText.eyebrow)
                    .foregroundStyle(creamSoft)

                Text(today.entry?.title?.nonEmpty ?? "Today's entry")
                    .font(.system(size: 18, weight: .medium, design: .serif))
                    .foregroundStyle(inkOnDark)

                // Short coral underline below the title — same accent
                // as the Day view's today card so the two surfaces feel
                // visually linked.
                Rectangle()
                    .fill(AppColors.coral.opacity(0.85))
                    .frame(width: 28, height: 1.2)

                if let preview = notesPreview(today.entry, limit: 120) {
                    Text(preview)
                        .font(.system(size: 12, design: .serif))
                        .foregroundStyle(mintSoft)
                        .lineLimit(2)
                }

                if today.captureCount > 0 || today.openTodoCount > 0 {
                    HStack(spacing: AppSpacing.s2) {
                        if today.captureCount > 0 {
                            Text("\(today.captureCount) captures")
                                .font(AppText.tag)
                                .foregroundStyle(creamSoft)
                        }
                        if today.captureCount > 0 && today.openTodoCount > 0 {
                            Text("·").font(AppText.tag).foregroundStyle(creamSoft)
                        }
                        if today.openTodoCount > 0 {
                            Text("\(today.openTodoCount) todos")
                                .font(AppText.tag)
                                .foregroundStyle(creamSoft)
                        }
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(AppSpacing.s5)
            .background(
                RoundedRectangle(cornerRadius: AppRadius.lg)
                    .fill(deepFill)
            )
            .overlay(
                RoundedRectangle(cornerRadius: AppRadius.lg)
                    .stroke(AppColors.coral, lineWidth: 1.4)
            )
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Earlier masonry

private struct EarlierMasonry: View {
    let days: [DayCount]
    let onDayTap: (DayCount) -> Void

    var body: some View {
        // Two-column ragged masonry — alternate days into the column with
        // less accumulated height for a natural "garden plot" rhythm.
        let columns = splitIntoColumns(days)
        HStack(alignment: .top, spacing: AppSpacing.s3) {
            VStack(spacing: AppSpacing.s3) {
                ForEach(columns.left) { day in
                    EarlierTile(day: day, onTap: { onDayTap(day) })
                }
            }
            VStack(spacing: AppSpacing.s3) {
                ForEach(columns.right) { day in
                    EarlierTile(day: day, onTap: { onDayTap(day) })
                }
            }
        }
    }

    private func splitIntoColumns(_ days: [DayCount]) -> (left: [DayCount], right: [DayCount]) {
        var left: [DayCount] = []
        var right: [DayCount] = []
        var leftH: CGFloat = 0
        var rightH: CGFloat = 0
        for d in days {
            let h = tileMinHeight(for: d.density)
            if leftH <= rightH {
                left.append(d); leftH += h
            } else {
                right.append(d); rightH += h
            }
        }
        return (left, right)
    }
}

// MARK: - Earlier tile

private struct EarlierTile: View {
    let day: DayCount
    let onTap: () -> Void

    var body: some View {
        if day.entry == nil {
            HollowTile(label: dayLabel(day.date))
        } else {
            FilledTile(day: day, onTap: onTap)
        }
    }
}

private struct HollowTile: View {
    let label: String

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label)
                .font(AppText.tag)
                .foregroundStyle(mutedDark)
            Text("no entry")
                .font(.system(size: 11, design: .serif))
                .foregroundStyle(mutedDark)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .frame(minHeight: 56, alignment: .leading)
        .padding(AppSpacing.s3)
        .overlay(
            RoundedRectangle(cornerRadius: AppRadius.md)
                .stroke(emptyStrk.opacity(0.25), style: StrokeStyle(lineWidth: 0.5, dash: [2, 2]))
        )
    }
}

private struct FilledTile: View {
    let day: DayCount
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            VStack(alignment: .leading, spacing: 2) {
                Text(dayLabel(day.date))
                    .font(AppText.tag)
                    .foregroundStyle(ink(for: day.density).opacity(0.85))

                Text(displayTitle())
                    .font(.system(size: 12, weight: .medium, design: .serif))
                    .foregroundStyle(ink(for: day.density))
                    .lineLimit(2)
                    .frame(maxWidth: .infinity, alignment: .leading)

                if day.captureCount > 0 {
                    CaptureDots(count: day.captureCount, color: ink(for: day.density))
                        .padding(.top, 2)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .frame(minHeight: tileMinHeight(for: day.density), alignment: .topLeading)
            .padding(AppSpacing.s3)
            .background(
                RoundedRectangle(cornerRadius: AppRadius.md)
                    .fill(tileFill(for: day.density))
            )
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func displayTitle() -> String {
        if let title = day.entry?.title?.nonEmpty {
            return title
        }
        if let preview = notesPreview(day.entry, limit: 40) {
            return preview
        }
        return "Untitled"
    }
}

private struct CaptureDots: View {
    let count: Int
    let color: Color

    var body: some View {
        let visible = min(count, 5)
        HStack(spacing: 2) {
            ForEach(0..<visible, id: \.self) { _ in
                Circle()
                    .fill(color)
                    .frame(width: 4, height: 4)
            }
            if count > 5 {
                Text("+\(count - 5)")
                    .font(.system(size: 8))
                    .foregroundStyle(color)
                    .padding(.leading, 2)
            }
        }
    }
}

// MARK: - Formatting

private func notesPreview(_ entry: NotepadEntry?, limit: Int) -> String? {
    guard let entry else { return nil }
    let line = entry.notes
        .split(whereSeparator: \.isNewline)
        .map { String($0).trimmingCharacters(in: .whitespaces) }
        .first(where: { !$0.isEmpty })
    guard let line else { return nil }
    return String(line.prefix(limit))
}

private func dayLabel(_ date: Date) -> String {
    let fmt = DateFormatter()
    fmt.locale = Locale(identifier: "en_US_POSIX")
    fmt.dateFormat = "MMM d"
    return fmt.string(from: date).uppercased()
}

private func dayHeader(_ date: Date) -> String {
    let fmt = DateFormatter()
    fmt.locale = Locale(identifier: "en_US_POSIX")
    fmt.dateFormat = "EEE · MMM d"
    return fmt.string(from: date).uppercased()
}

private func monthLabel(_ date: Date) -> String {
    let fmt = DateFormatter()
    fmt.locale = Locale(identifier: "en_US_POSIX")
    fmt.dateFormat = "LLLL"
    return fmt.string(from: date).uppercased()
}

// MARK: - Helpers

private extension String {
    var nonEmpty: String? {
        let trimmed = trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
}
