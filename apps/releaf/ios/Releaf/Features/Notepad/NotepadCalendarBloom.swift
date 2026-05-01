/*
 * NotepadCalendarBloom.swift
 *
 * The Day view's centerpiece — a 5×7 grid of small pines, one per day
 * of the current month. Empty days render as hollow tree silhouettes;
 * days with entries fill the canopy in three tinted greens (light →
 * mid → deep) keyed off the day's `DayDensity`. Today is a coral pine
 * with a coral ring.
 *
 * Tree silhouette + color ramp mirror TreesSavedHeroView.TreeGlyph and
 * the side-drawer canopy header so the three surfaces feel like the
 * same forest at different framings.
 */

import SwiftUI
import ReleafDesignSystem

private let emptyFill   = Color(red: 0xFA / 255.0, green: 0xF1 / 255.0, blue: 0xE6 / 255.0)
private let lightFill   = Color(red: 0xC0 / 255.0, green: 0xDD / 255.0, blue: 0x97 / 255.0)
private let midFill     = Color(red: 0x7A / 255.0, green: 0xA8 / 255.0, blue: 0x74 / 255.0)
private let deepFill    = Color(red: 0x3E / 255.0, green: 0x6B / 255.0, blue: 0x3B / 255.0)
private let todayCoral  = Color(red: 0xE7 / 255.0, green: 0x78 / 255.0, blue: 0x50 / 255.0)
private let todayCoralDark = Color(red: 0x99 / 255.0, green: 0x3C / 255.0, blue: 0x1D / 255.0)
private let trunkColor  = Color(red: 0x3E / 255.0, green: 0x2A / 255.0, blue: 0x18 / 255.0)
private let trunkSoft   = Color(red: 0x5F / 255.0, green: 0x4A / 255.0, blue: 0x2D / 255.0)
private let coralHalo   = Color(red: 0xFC / 255.0, green: 0xEA / 255.0, blue: 0xE0 / 255.0)
private let greenLine   = Color(red: 0x1E / 255.0, green: 0x59 / 255.0, blue: 0x43 / 255.0)
private let selectionRing = Color(red: 0x5B / 255.0, green: 0x8C / 255.0, blue: 0x52 / 255.0)
private let selectionHalo = Color(red: 0xD9 / 255.0, green: 0xED / 255.0, blue: 0xE2 / 255.0)

private func fill(for density: DayDensity) -> Color {
    switch density {
    case .empty: return emptyFill
    case .light: return lightFill
    case .mid:   return midFill
    case .deep:  return deepFill
    }
}

public struct NotepadCalendarBloom: View {
    let leadingBlanks: Int
    let days: [DayCount]
    let today: Date
    let onDayTap: (DayCount) -> Void
    let showLegend: Bool
    let showWeekdayStrip: Bool
    let selectedDate: Date?

    public init(
        leadingBlanks: Int,
        days: [DayCount],
        today: Date,
        onDayTap: @escaping (DayCount) -> Void,
        showLegend: Bool = true,
        showWeekdayStrip: Bool = true,
        selectedDate: Date? = nil
    ) {
        self.leadingBlanks = leadingBlanks
        self.days = days
        self.today = today
        self.onDayTap = onDayTap
        self.showLegend = showLegend
        self.showWeekdayStrip = showWeekdayStrip
        self.selectedDate = selectedDate
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if showWeekdayStrip {
                NotepadCalendarWeekdayStrip()
            }

            // Tree grid — pad with leading + trailing blanks so the 7-wide layout
            // aligns with the weekday columns.
            let cells: [DayCount?] = leadingNils + days.map { Optional($0) } + trailingNils
            let rows = cells.count / 7
            VStack(spacing: 0) {
                ForEach(0..<rows, id: \.self) { row in
                    HStack(spacing: 0) {
                        ForEach(0..<7, id: \.self) { col in
                            let cell = cells[row * 7 + col]
                            let cal = Calendar.current
                            let isToday = cell.map { cal.isDate($0.date, inSameDayAs: today) } ?? false
                            // Selection wins over "today" for the ring —
                            // when today is the selected day (default on
                            // open), the green ring shows. Today's coral
                            // canopy fill still marks the date.
                            let isSelected: Bool = {
                                guard let sel = selectedDate, let c = cell else { return false }
                                return cal.isDate(c.date, inSameDayAs: sel)
                            }()
                            TreeCell(
                                day: cell,
                                isToday: isToday,
                                isSelected: isSelected,
                                onTap: { c in onDayTap(c) }
                            )
                            .frame(maxWidth: .infinity, minHeight: 36)
                        }
                    }
                }
            }

            // Legend (optional — hidden when used inside the 3-month
            // carousel where only the centered month should show its
            // legend, rendered separately by the caller).
            if showLegend {
                LegendRow()
                    .padding(.top, 4)
            }
        }
    }


    // MARK: - Padding

    private var leadingNils: [DayCount?] {
        Array(repeating: nil, count: leadingBlanks)
    }

    private var trailingNils: [DayCount?] {
        let total = leadingBlanks + days.count
        let pad = (7 - total % 7) % 7
        return Array(repeating: nil, count: pad)
    }

    private static let weekdayInitials = ["S", "M", "T", "W", "T", "F", "S"]
}

// MARK: - Tree cell

private struct TreeCell: View {
    let day: DayCount?
    let isToday: Bool
    let isSelected: Bool
    let onTap: (DayCount) -> Void

    var body: some View {
        Group {
            if let day {
                Button(action: { onTap(day) }) {
                    Canvas { ctx, size in
                        let cx = size.width / 2
                        let cy = size.height / 2 - 2
                        let r = min(size.width, size.height) / 2
                        let halo = Path(ellipseIn: CGRect(x: cx - r, y: cy - r, width: r * 2, height: r * 2))

                        if isSelected {
                            // Selection takes ring priority — works for
                            // today (which is selected by default on
                            // open) and any past/future day the user
                            // taps. Today's coral canopy fill still
                            // distinguishes the date even when its ring
                            // goes green.
                            ctx.fill(halo, with: .color(selectionHalo.opacity(0.55)))
                            ctx.stroke(halo, with: .color(selectionRing.opacity(0.85)), lineWidth: 1.5)
                        } else if isToday {
                            // Today, but not currently the selected day —
                            // user has tapped a past/future day. Coral
                            // ring stays as the "today marker" until
                            // they tap back on today.
                            ctx.fill(halo, with: .color(coralHalo.opacity(0.6)))
                            ctx.stroke(halo, with: .color(todayCoral.opacity(0.85)), lineWidth: 1.5)
                        }

                        drawTree(in: ctx, center: CGPoint(x: cx, y: cy), density: day.density, isToday: isToday)
                    }
                    .frame(width: 28, height: 32)
                }
                .buttonStyle(.plain)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                Color.clear.frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
    }
}

private func drawTree(in ctx: GraphicsContext, center: CGPoint, density: DayDensity, isToday: Bool) {
    let cx = center.x
    let cy = center.y
    var canopy = Path()
    canopy.move(to: CGPoint(x: cx, y: cy - 9))
    canopy.addLine(to: CGPoint(x: cx - 7, y: cy + 5))
    canopy.addLine(to: CGPoint(x: cx + 7, y: cy + 5))
    canopy.closeSubpath()

    let canopyFill: Color = isToday ? todayCoral : fill(for: density)
    let isHollow = !isToday && density == .empty

    ctx.fill(canopy, with: .color(canopyFill))
    if isHollow {
        ctx.stroke(canopy, with: .color(greenLine.opacity(0.22)), lineWidth: 0.6)
    }

    // Trunk
    let trunkFill: Color = {
        if isToday { return todayCoralDark }
        if isHollow { return trunkSoft.opacity(0.4) }
        return trunkColor
    }()
    let trunkRect = CGRect(x: cx - 1.5, y: cy + 5, width: 3, height: 4)
    ctx.fill(Path(trunkRect), with: .color(trunkFill))
}

// MARK: - Legend

/// Public legend strip — callers can render it once below the
/// 3-month carousel where individual calendars hide their own legend.
///
/// When `onTodayTap` is provided, the right-aligned "today" badge
/// becomes a tap target so the host can snap the carousel back to
/// today's month and reselect today after the user has swiped or
/// tapped a different day.
public struct NotepadCalendarLegend: View {
    private let onTodayTap: (() -> Void)?

    public init(onTodayTap: (() -> Void)? = nil) {
        self.onTodayTap = onTodayTap
    }

    public var body: some View { LegendRow(onTodayTap: onTodayTap) }
}

/// Public weekday header strip — extracted so the swipeable carousel
/// can render it once above the pager, matching Android. Without
/// extraction, the prev / next page's own strips bleed into the
/// centered page during transitions.
public struct NotepadCalendarWeekdayStrip: View {
    public init() {}
    public var body: some View {
        HStack(spacing: 0) {
            ForEach(["S", "M", "T", "W", "T", "F", "S"], id: \.self) { letter in
                Text(letter)
                    .font(AppText.tag)
                    .foregroundStyle(AppColors.textSecondary)
                    .frame(maxWidth: .infinity)
            }
        }
    }
}

private struct LegendRow: View {
    let onTodayTap: (() -> Void)?

    init(onTodayTap: (() -> Void)? = nil) {
        self.onTodayTap = onTodayTap
    }

    var body: some View {
        HStack(spacing: 8) {
            Text("none")
                .font(AppText.tag)
                .foregroundStyle(AppColors.textSecondary)

            LegendTree(density: .empty)
            LegendTree(density: .light)
            LegendTree(density: .mid)
            LegendTree(density: .deep)

            Text("3+ captures")
                .font(AppText.tag)
                .foregroundStyle(AppColors.textSecondary)

            Spacer(minLength: 0)

            // Today badge — tappable when [onTodayTap] is provided. The
            // host pairs the click with snapping the carousel back to
            // today's month and reselecting today's tree.
            todayBadge
        }
    }

    @ViewBuilder
    private var todayBadge: some View {
        let badge = HStack(spacing: 6) {
            ZStack {
                Circle()
                    .stroke(todayCoral, lineWidth: 1)
                    .frame(width: 20, height: 20)
                Canvas { ctx, size in
                    drawTree(
                        in: ctx,
                        center: CGPoint(x: size.width / 2, y: size.height / 2 - 2),
                        density: .deep,
                        isToday: true
                    )
                }
                .frame(width: 14, height: 18)
            }

            Text("today")
                .font(AppText.tag)
                .foregroundStyle(onTodayTap != nil ? todayCoralDark : AppColors.textSecondary)
        }
        .padding(.horizontal, 4)
        .padding(.vertical, 2)
        .contentShape(Capsule())

        if let onTodayTap {
            Button(action: onTodayTap) { badge }
                .buttonStyle(.plain)
        } else {
            badge
        }
    }
}

private struct LegendTree: View {
    let density: DayDensity

    var body: some View {
        Canvas { ctx, size in
            drawTree(
                in: ctx,
                center: CGPoint(x: size.width / 2, y: size.height / 2 - 2),
                density: density,
                isToday: false
            )
        }
        .frame(width: 14, height: 18)
    }
}
