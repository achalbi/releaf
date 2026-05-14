/*
 * DaylightHero.swift
 *
 * Home-screen card that sits directly above `SustainabilityHero`,
 * answering the casual "how much daylight do I have left today?"
 * question with a glance — sunrise time, sunset time, and a meter
 * whose position is *the answer* rather than decoration.
 *
 * Layout (B2 design direction):
 *
 *   ┌────────────────────────┐  ┌────────────────────────┐
 *   │ ☀  Sunrise   5:54 AM   │  │ ☀  Sunset    7:06 PM   │
 *   └────────────────────────┘  └────────────────────────┘
 *     8h 49m in                              4h 23m left
 *     ─────────●───────────────────────────────────────
 *
 * Two warm-tinted "split tiles" carry the times; a slim meter
 * underneath shows progress through the day with a `now` dot
 * positioned at the current daylight fraction. Elapsed time
 * appears on the meter's left, remaining on the right.
 *
 * Behavior:
 *   - Before sunrise: meter empty, "Daylight starts in Xh Ym"
 *     replaces the elapsed label; remaining shows the day's
 *     total daylight ("13h 12m today").
 *   - After sunset:  meter full,  "Day ended" on the left,
 *     "Sunrise in Xh Ym" on the right.
 *   - During daylight: elapsed/remaining are minute-precise,
 *     dot is at `elapsed / total`.
 *
 * Time source: `sunTimesFor(_:)` in `Calendar/RahuKala.swift` —
 * the same USNO-1990 calculator the panchanga uses for Rahu Kala,
 * anchored to Mysuru. If we add per-user lat/lon later, pass it
 * through here.
 *
 * The `now` indicator is animated only enough to feel alive — a
 * subtle 3.4 s sun-glyph pulse on each tile's ring. The meter's
 * dot position is the *actual* now (recomputed on a 60 s `TimelineView`
 * tick), not a CSS-style sweep, so it stays correct without
 * relying on the screen being open.
 *
 * Counterpart: Android `DaylightHero` in `HomeScreen.kt`.
 */

import SwiftUI
import Foundation

// MARK: - Daylight model

/// One day's daylight snapshot for a given `now`. Pre-computes the
/// fractional position the now-marker should sit at and the two
/// flanking labels so the view layer is presentational only.
struct DaylightSnapshot: Equatable {
    enum Phase: Equatable {
        case beforeSunrise
        case daytime
        case afterSunset
        /// Polar fallback — sunrise/sunset couldn't be resolved.
        /// View renders a "—" state instead of a meter.
        case unresolved
    }

    let phase: Phase
    let sunrise: Date?
    let sunset: Date?
    let now: Date
    /// 0.0 = exactly at sunrise, 1.0 = exactly at sunset, clamped.
    /// Caller uses this to position the now-marker on the meter.
    let dayProgress: Double
    /// "8h 49m in" / "Day starts in 1h 14m" / "Day ended"
    let leadingLabel: String
    /// "4h 23m left" / "13h 12m today" / "Sunrise in 9h 48m"
    let trailingLabel: String
}

/// Build a `DaylightSnapshot` for `now`. Sunrise/sunset come from
/// the shared `sunTimesFor` helper. `latitude` / `longitude` come
/// from `DaylightLocationStore` (threaded through HomeScreen) so
/// the hero's times match the user-location-anchored status bar
/// above it; nil falls back to Mysuru, matching the panchanga
/// card's default anchor.
func computeDaylight(
    now: Date,
    latitude: Double? = nil,
    longitude: Double? = nil
) -> DaylightSnapshot {
    let lat = latitude  ?? 12.2958
    let lng = longitude ?? 76.6394
    let times = sunTimesFor(now, latitude: lat, longitude: lng)
    guard let sunrise = times.sunrise, let sunset = times.sunset else {
        return DaylightSnapshot(
            phase:         .unresolved,
            sunrise:       times.sunrise,
            sunset:        times.sunset,
            now:           now,
            dayProgress:   0,
            leadingLabel:  "—",
            trailingLabel: "—"
        )
    }
    let total = max(1, sunset.timeIntervalSince(sunrise))

    if now < sunrise {
        let untilRise = sunrise.timeIntervalSince(now)
        return DaylightSnapshot(
            phase:         .beforeSunrise,
            sunrise:       sunrise,
            sunset:        sunset,
            now:           now,
            dayProgress:   0,
            leadingLabel:  "Day starts in \(formatDuration(untilRise))",
            trailingLabel: "\(formatDuration(total)) today"
        )
    }
    if now > sunset {
        // After sunset, point at *tomorrow's* sunrise — that's the
        // information the user actually wants in the "remaining"
        // slot. Falls back to nil-of-times silently if the next
        // day's solar calc fails (only at polar latitudes).
        let tomorrow = now.addingTimeInterval(24 * 3600)
        let tomorrowSunrise = sunTimesFor(tomorrow, latitude: lat, longitude: lng).sunrise
        let trailing: String
        if let next = tomorrowSunrise {
            trailing = "Sunrise in \(formatDuration(next.timeIntervalSince(now)))"
        } else {
            trailing = "\(formatDuration(total)) today"
        }
        return DaylightSnapshot(
            phase:         .afterSunset,
            sunrise:       sunrise,
            sunset:        sunset,
            now:           now,
            dayProgress:   1,
            leadingLabel:  "Day ended",
            trailingLabel: trailing
        )
    }

    let elapsed = now.timeIntervalSince(sunrise)
    let remaining = sunset.timeIntervalSince(now)
    let progress = (elapsed / total).clamped(to: 0...1)
    return DaylightSnapshot(
        phase:         .daytime,
        sunrise:       sunrise,
        sunset:        sunset,
        now:           now,
        dayProgress:   progress,
        leadingLabel:  "\(formatDuration(elapsed)) in",
        trailingLabel: "\(formatDuration(remaining)) left"
    )
}

// MARK: - Daylight hero card

struct DaylightHero: View {
    /// Optional fixed `now` for previews / tests. Live screen passes
    /// `nil` so a `TimelineView(.periodic)` drives a 60 s recompute.
    let fixedNow: Date?
    /// User's location, threaded from `DaylightLocationStore` via
    /// HomeScreen so the hero's sunrise/sunset match the status bar
    /// above. `nil` for either falls back to Mysuru.
    let latitude:  Double?
    let longitude: Double?

    init(
        fixedNow: Date? = nil,
        latitude: Double? = nil,
        longitude: Double? = nil
    ) {
        self.fixedNow  = fixedNow
        self.latitude  = latitude
        self.longitude = longitude
    }

    var body: some View {
        // 60 s tick is enough — the meter position drifts by ~1 px
        // per minute on a 320 pt-wide meter, so faster updates would
        // be invisible. The pulse animations on the tile rings are
        // pure CSS-style auto-reversed easing and don't need the
        // timeline tick.
        TimelineView(.periodic(from: Date(), by: 60)) { context in
            let now = fixedNow ?? context.date
            content(for: computeDaylight(now: now, latitude: latitude, longitude: longitude))
        }
    }

    @ViewBuilder
    private func content(for snapshot: DaylightSnapshot) -> some View {
        // Soft warm tints carry the sunrise-vs-sunset identity via
        // *background colour*, not text colour. Earlier pass used
        // `leafYellowDeep` for both the icon and the meter labels
        // — but a saturated yellow on yellow has zero contrast, and
        // on the cream canvas underneath the meter labels read as a
        // faint amber smudge. Text now routes through `ink` /
        // `inkSoft` (the warm-brown tokens that auto-flip in dark
        // mode), so the daylight card has the same legibility
        // ceiling the rest of the app does.
        let sunBg     = QuickInkColors.leafYellowBase.opacity(0.20)
        let sunBorder = QuickInkColors.leafYellowBase.opacity(0.55)
        let setBg     = QuickInkColors.coralBase.opacity(0.18)
        let setBorder = QuickInkColors.coralBase.opacity(0.45)

        VStack(spacing: QuickInkSpacing.s2) {
            // Two split tiles — sunrise on the left, sunset on the
            // right. Equal-width via `frame(maxWidth: .infinity)` so
            // the pair always splits the card in two regardless of
            // dynamic-type stretch.
            HStack(spacing: QuickInkSpacing.s2) {
                splitTile(
                    label:    "Sunrise",
                    time:     formattedTime(snapshot.sunrise),
                    icon:     "sunrise.fill",
                    ringFill: QuickInkColors.leafYellowBase,
                    bg:       sunBg,
                    border:   sunBorder,
                    pulsePhase: 0
                )
                splitTile(
                    label:    "Sunset",
                    time:     formattedTime(snapshot.sunset),
                    icon:     "sunset.fill",
                    ringFill: QuickInkColors.coralBase,
                    bg:       setBg,
                    border:   setBorder,
                    // 1.7 s phase offset on the second pulse so the
                    // two tiles don't breathe in lockstep — feels
                    // alive without being synchronous.
                    pulsePhase: 1.7
                )
            }

            // Elapsed / remaining row + meter. Polar fallback
            // (`unresolved` phase) collapses to a single muted dash
            // so the card still occupies its slot without a broken
            // meter.
            if snapshot.phase == .unresolved {
                HStack {
                    Text("Sunrise unavailable")
                        .font(QuickInkText.caption)
                        .foregroundStyle(QuickInkColors.muted)
                    Spacer()
                }
                .padding(.horizontal, QuickInkSpacing.s1)
            } else {
                VStack(spacing: 4) {
                    HStack {
                        Text(snapshot.leadingLabel)
                            .font(QuickInkText.caption)
                            .foregroundStyle(QuickInkColors.inkSoft)
                            .textCase(.uppercase)
                            .tracking(0.4)
                        Spacer()
                        Text(snapshot.trailingLabel)
                            .font(QuickInkText.caption)
                            .foregroundStyle(QuickInkColors.inkSoft)
                            .textCase(.uppercase)
                            .tracking(0.4)
                    }
                    .padding(.horizontal, QuickInkSpacing.s1)

                    daylightMeter(progress: snapshot.dayProgress)
                }
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(accessibilityLabel(for: snapshot))
    }

    // MARK: - Subviews

    /// One half of the split row — a soft-tinted pill with an
    /// icon ring on the left and stacked label/time on the right.
    /// `pulsePhase` is the seconds-of-delay applied to the ring's
    /// breathing animation so the two tiles aren't in lockstep.
    ///
    /// Icon and caption both route through `QuickInkColors.ink` —
    /// the warm-brown primary text colour that auto-flips in dark
    /// mode. Earlier passes used the per-side deep tones
    /// (`leafYellowDeep` / `coralDeep`) which gave near-zero
    /// contrast on the matching-hue ring fill; the sunrise glyph
    /// effectively disappeared into the amber circle. Background
    /// colour alone carries sunrise-vs-sunset identity.
    @ViewBuilder
    private func splitTile(
        label: String,
        time: String,
        icon: String,
        ringFill: Color,
        bg: Color,
        border: Color,
        pulsePhase: Double
    ) -> some View {
        HStack(spacing: QuickInkSpacing.s2) {
            ZStack {
                Circle()
                    .fill(ringFill)
                    .frame(width: 30, height: 30)
                Image(systemName: icon)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(QuickInkColors.ink)
            }
            .modifier(BreathingPulse(phase: pulsePhase))

            VStack(alignment: .leading, spacing: 1) {
                Text(label.uppercased())
                    .font(QuickInkText.caption)
                    .tracking(0.5)
                    .foregroundStyle(QuickInkColors.inkSoft)
                Text(time)
                    .font(QuickInkText.editorial)
                    .foregroundStyle(QuickInkColors.ink)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, QuickInkSpacing.s3)
        .padding(.vertical, QuickInkSpacing.s2)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous)
                .fill(bg)
        )
        .overlay(
            RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous)
                .strokeBorder(border, lineWidth: 1)
        )
    }

    /// Slim 8 pt-tall meter with a filled portion up to `progress`
    /// and a rayed-sun "now" indicator overlaid at the same x. Track
    /// uses a soft amber-tinted background so the empty portion
    /// still reads as part of the daylight palette.
    @ViewBuilder
    private func daylightMeter(progress: Double) -> some View {
        GeometryReader { geo in
            let width = geo.size.width
            let dotSize: CGFloat = 18
            // Clamp the dot center so it never escapes the track
            // visually even with sub-pixel rounding.
            let dotX = (progress * Double(width)).clamped(
                to: Double(dotSize / 2) ... Double(width - dotSize / 2)
            )
            ZStack(alignment: .leading) {
                // Track.
                Capsule()
                    .fill(QuickInkColors.leafYellowBase.opacity(0.30))
                    .frame(height: 8)
                // Filled portion (elapsed).
                Capsule()
                    .fill(QuickInkColors.leafYellowDeep)
                    .frame(width: max(0, CGFloat(progress) * width), height: 8)
                // Now indicator — rayed sun. Disc matches the fill;
                // rays carry the contrast so the marker reads against
                // both halves of the track.
                let rayColor = QuickInkColors.coralDeep
                ZStack {
                    Circle()
                        .fill(QuickInkColors.leafYellowDeep)
                        .frame(width: 8, height: 8)
                    ForEach(0..<8, id: \.self) { i in
                        Capsule()
                            .fill(rayColor)
                            .frame(width: 2, height: 3.5)
                            .offset(y: -(4 + 1.5 + 1.75))
                            .rotationEffect(.degrees(Double(i) * 45))
                    }
                }
                .frame(width: dotSize, height: dotSize)
                .offset(x: CGFloat(dotX) - dotSize / 2)
            }
            .frame(height: 18, alignment: .center)
        }
        .frame(height: 18)
    }

    // MARK: - Helpers

    private func formattedTime(_ date: Date?) -> String {
        guard let date else { return "—" }
        return Self.timeFormatter.string(from: date)
    }

    private func accessibilityLabel(for snapshot: DaylightSnapshot) -> String {
        let rise = formattedTime(snapshot.sunrise)
        let set  = formattedTime(snapshot.sunset)
        switch snapshot.phase {
        case .beforeSunrise:
            return "Sunrise at \(rise), sunset at \(set). \(snapshot.leadingLabel)."
        case .daytime:
            return "Sunrise at \(rise), sunset at \(set). \(snapshot.trailingLabel)."
        case .afterSunset:
            return "Sunrise was at \(rise), sunset was at \(set). \(snapshot.trailingLabel)."
        case .unresolved:
            return "Sunrise and sunset unavailable for this location."
        }
    }

    /// IST 12-hour formatter — matches Mysuru/IST anchor used by
    /// `sunTimesFor`. Hoisted to file scope so each row render
    /// doesn't allocate a fresh formatter.
    private static let timeFormatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "Asia/Kolkata")
            ?? TimeZone(secondsFromGMT: 5 * 3600 + 30 * 60)!
        // 12-hour with am/pm: "5:54 AM". The lowercase `a` symbol
        // forces locale-independent "AM"/"PM" output.
        f.dateFormat = "h:mm a"
        return f
    }()
}

// MARK: - Breathing-pulse modifier

/// Auto-reversing scale animation used on the two tile rings so
/// they read as "alive" without competing for attention. Looks
/// closest to the prototype's `@keyframes pulse-warm` — gentle 8 %
/// scale swing on a 3 s cycle.
private struct BreathingPulse: ViewModifier {
    let phase: Double

    @State private var scale: CGFloat = 1.0

    func body(content: Content) -> some View {
        content
            .scaleEffect(scale)
            .onAppear {
                // Stagger the start via `phase` so two adjacent
                // pulses don't begin synchronously. `delay` shifts
                // the autoreverse cycle without restarting it.
                withAnimation(
                    .easeInOut(duration: 1.6)
                        .repeatForever(autoreverses: true)
                        .delay(phase)
                ) {
                    scale = 1.08
                }
            }
    }
}

// MARK: - Duration formatting

/// "8h 49m" / "13m" / "0m" — minute-precise, drops the leading
/// "Xh" when under an hour. Used by the daylight labels so the
/// elapsed and remaining strings have a consistent shape.
private func formatDuration(_ seconds: TimeInterval) -> String {
    let total = Int(max(0, seconds).rounded())
    let hours = total / 3600
    let minutes = (total % 3600) / 60
    if hours == 0 { return "\(minutes)m" }
    return "\(hours)h \(minutes)m"
}

private extension Comparable {
    func clamped(to range: ClosedRange<Self>) -> Self {
        min(max(self, range.lowerBound), range.upperBound)
    }
}

// MARK: - Previews

#if DEBUG
struct DaylightHero_Previews: PreviewProvider {
    /// Pin previews to a known IST date so the formatted times
    /// in Xcode's preview canvas match across reruns. May 14 2026
    /// at Mysuru: sunrise ≈ 06:01 IST, sunset ≈ 18:42 IST.
    private static let day: Date = {
        let cal = Calendar(identifier: .gregorian)
        var comps = DateComponents()
        comps.timeZone = TimeZone(identifier: "Asia/Kolkata")
        comps.year = 2026
        comps.month = 5
        comps.day = 14
        return cal.date(from: comps) ?? Date()
    }()

    static func at(_ hour: Int, _ minute: Int = 0) -> Date {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "Asia/Kolkata")!
        return cal.date(bySettingHour: hour, minute: minute, second: 0, of: day) ?? day
    }

    static var previews: some View {
        VStack(spacing: 16) {
            DaylightHero(fixedNow: at(5, 0))   // before sunrise
            DaylightHero(fixedNow: at(9, 30))  // morning
            DaylightHero(fixedNow: at(13, 0))  // mid-day
            DaylightHero(fixedNow: at(17, 30)) // approaching sunset
            DaylightHero(fixedNow: at(21, 0))  // after sunset
        }
        .padding()
        .background(QuickInkColors.bg)
        .previewDisplayName("DaylightHero — across the day")
    }
}
#endif
