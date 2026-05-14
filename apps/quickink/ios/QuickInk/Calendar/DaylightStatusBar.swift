/*
 * DaylightStatusBar.swift
 *
 * Custom status bar that sits above the app chrome and shows where
 * the user is in the current daylight (or nighttime) phase. Layout
 * locked through the design iteration in chat — left/right corner
 * labels ("SUNRISE" + time, "SUNSET" + time), center "NOW" + current
 * time, a 3px progress bar with a sun (day) or crescent moon
 * (night) marker, and a `<elapsed> in · <remaining> left` caption
 * row underneath.
 *
 * Color palette is intentionally fixed (gold day, ink-black night,
 * coral 8-ray sun, dark-grey crescent moon) rather than themed —
 * the bar is one continuous visual moment and the user-pickable
 * accent isn't a good fit for "where is the sun right now."
 *
 * Mirror of Android's `DaylightStatusBar.kt`. Spec must stay in
 * sync between the two.
 */

import SwiftUI

#if canImport(UIKit)
import UIKit
#endif

/// Status-bar-height component, ~60pt tall, that renders the
/// current solar-phase meter. `latitude` and `longitude` come from
/// the host (usually `QuickInkRoot` after a `LocationService` fix);
/// pass `nil` to render the empty-state shell while the location
/// fetch is in flight.
public struct DaylightStatusBar: View {

    public let latitude:  Double?
    public let longitude: Double?

    public init(latitude: Double?, longitude: Double?) {
        self.latitude  = latitude
        self.longitude = longitude
    }

    public var body: some View {
        // TimelineView re-renders every minute so the dot position
        // and the center clock both glide forward without us having
        // to wire up a Timer / @State date. `.periodic(by: 60)`
        // is available back to iOS 15 — preferred over the iOS-17-only
        // `.everyMinute` since QuickInk's deployment floor is below
        // that.
        TimelineView(.periodic(from: .now, by: 60)) { ctx in
            if let lat = latitude, let lng = longitude {
                DaylightStatusBarContent(
                    phase: SolarPhaseCalculator.compute(
                        latitude:  lat,
                        longitude: lng,
                        at:        ctx.date
                    )
                )
            } else {
                // No location yet — render the row at its final
                // height so the layout doesn't jump when the fix
                // arrives. The shell shows just the gray track.
                DaylightStatusBarShell()
            }
        }
    }
}

// MARK: - Content (with a real phase)

private struct DaylightStatusBarContent: View {

    let phase: SolarPhase

    var body: some View {
        VStack(spacing: 0) {
            labelsRow
            timesRow
            barRow
            captionsRow
        }
        .padding(.horizontal, DaylightStatusBarMetrics.horizontalPadding)
        .padding(.vertical,   DaylightStatusBarMetrics.verticalPadding)
        .frame(maxWidth: .infinity)
        .background(DaylightStatusBarMetrics.canvas)
    }

    // MARK: Rows

    private var labelsRow: some View {
        HStack {
            Text(phase.phase == .day ? "SUNRISE" : "SUNSET")
            Spacer()
            Text("NOW")
            Spacer()
            Text(phase.phase == .day ? "SUNSET" : "SUNRISE")
        }
        .font(.system(size: 8, weight: .regular))
        .tracking(0.8)
        .foregroundColor(DaylightStatusBarMetrics.labelGray)
        .fixedSize(horizontal: false, vertical: true)
    }

    private var timesRow: some View {
        HStack {
            Text(Self.formatClock(phase.anchorLeft))
                .font(.system(size: 11, design: .serif))
            Spacer()
            Text(Self.formatClock(phase.now))
                .font(.system(size: 13, design: .serif))
            Spacer()
            Text(Self.formatClock(phase.anchorRight))
                .font(.system(size: 11, design: .serif))
        }
        .foregroundColor(DaylightStatusBarMetrics.ink)
        .fixedSize(horizontal: false, vertical: true)
    }

    private var barRow: some View {
        Canvas { context, size in
            DaylightStatusBarMetrics.draw(
                in:       context,
                size:     size,
                fraction: phase.fraction,
                isDay:    phase.phase == .day
            )
        }
        .frame(height: DaylightStatusBarMetrics.barRowHeight)
        .accessibilityLabel(Self.accessibilityLabel(for: phase))
    }

    private var captionsRow: some View {
        GeometryReader { geo in
            let fraction = CGFloat(phase.fraction)
            let elapsedCenter   = geo.size.width * fraction   / 2
            let remainingCenter = geo.size.width * (fraction + 1) / 2
            ZStack(alignment: .topLeading) {
                Text(Self.formatDuration(phase.elapsed) + " in")
                    .position(x: elapsedCenter, y: 4.5)
                Text(Self.formatDuration(phase.remaining) + " left")
                    .position(x: remainingCenter, y: 4.5)
            }
            .font(.system(size: 8, weight: .regular))
            .foregroundColor(DaylightStatusBarMetrics.labelGray)
        }
        .frame(height: 9)
    }

    // MARK: Formatters

    private static func formatClock(_ date: Date) -> String {
        let f = DateFormatter()
        f.dateFormat = "h:mm a"
        f.amSymbol   = "am"
        f.pmSymbol   = "pm"
        f.locale     = Locale(identifier: "en_US_POSIX")
        return f.string(from: date)
    }

    /// Format an interval as "Hh Mm" — drops the hour part when zero
    /// so freshly-after-sunrise reads "12m in" rather than "0h 12m in".
    private static func formatDuration(_ interval: TimeInterval) -> String {
        let totalSeconds = max(0, Int(interval))
        let hours   = totalSeconds / 3600
        let minutes = (totalSeconds % 3600) / 60
        if hours == 0 { return "\(minutes)m" }
        return "\(hours)h \(minutes)m"
    }

    private static func accessibilityLabel(for phase: SolarPhase) -> String {
        let elapsed   = formatDuration(phase.elapsed)
        let remaining = formatDuration(phase.remaining)
        let kind      = phase.phase == .day ? "daylight" : "nighttime"
        return "\(elapsed) into \(kind), \(remaining) remaining."
    }
}

// MARK: - Empty shell (no location yet)

private struct DaylightStatusBarShell: View {
    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text("SUNRISE"); Spacer(); Text("NOW"); Spacer(); Text("SUNSET")
            }
            .font(.system(size: 8, weight: .regular))
            .tracking(0.8)
            .foregroundColor(DaylightStatusBarMetrics.labelGray)
            .fixedSize(horizontal: false, vertical: true)

            HStack {
                Text("—:—").font(.system(size: 11, design: .serif))
                Spacer()
                Text("—:—").font(.system(size: 13, design: .serif))
                Spacer()
                Text("—:—").font(.system(size: 11, design: .serif))
            }
            .foregroundColor(DaylightStatusBarMetrics.ink.opacity(0.4))
            .frame(height: 13)
            .padding(.vertical, -1)

            Canvas { context, size in
                let track = DaylightStatusBarMetrics.trackRect(size: size)
                context.fill(
                    Path(roundedRect: track, cornerRadius: track.height / 2),
                    with: .color(DaylightStatusBarMetrics.track)
                )
            }
            .frame(height: DaylightStatusBarMetrics.barRowHeight)

            // Spacer to match captions row height in the real content,
            // so the layout doesn't shift once location resolves.
            Color.clear.frame(height: 9)
        }
        .padding(.horizontal, DaylightStatusBarMetrics.horizontalPadding)
        .padding(.vertical,   DaylightStatusBarMetrics.verticalPadding)
        .frame(maxWidth: .infinity)
        .background(DaylightStatusBarMetrics.canvas)
    }
}

// MARK: - Drawing primitives + tokens

/// Pulled into a struct of constants so the canvas drawing and the
/// SwiftUI layout share one set of numbers — every tweak ("make the
/// sun a bit bigger") lands in exactly one place.
private enum DaylightStatusBarMetrics {

    static let horizontalPadding: CGFloat = 22
    static let verticalPadding:   CGFloat = 0

    /// Total height reserved for the bar row (track + marker). Tall
    /// enough that the marker's halo doesn't clip the row above or
    /// below.
    static let barRowHeight: CGFloat = 22

    static let trackHeight: CGFloat = 5

    // Brand colors for the meter. Fixed rather than themed — see
    // file header.
    static let canvas       = Color(red: 0.984, green: 0.965, blue: 0.933)  // #FBF6EE
    static let track        = Color(red: 0.898, green: 0.867, blue: 0.816)  // #E5DDD0
    static let dayFill      = Color(red: 0.918, green: 0.718, blue: 0.204)  // #EAB734
    static let nightFill    = Color(red: 0.102, green: 0.102, blue: 0.102)  // #1A1A1A
    static let sun          = Color(red: 0.847, green: 0.353, blue: 0.188)  // #D85A30
    static let moon         = Color(red: 0.290, green: 0.290, blue: 0.290)  // #4A4A4A
    static let labelGray    = Color(red: 0.533, green: 0.529, blue: 0.502)  // #888780
    static let ink          = Color(red: 0.173, green: 0.173, blue: 0.165)  // #2C2C2A

    static func trackRect(size: CGSize) -> CGRect {
        let y = (size.height - trackHeight) / 2
        return CGRect(x: 0, y: y, width: size.width, height: trackHeight)
    }

    /// Single entry point for the canvas — draws track, fill, and
    /// marker in z-order so callers don't deal with primitives.
    static func draw(
        in context: GraphicsContext,
        size:       CGSize,
        fraction:   Double,
        isDay:      Bool
    ) {
        let trackRect = trackRect(size: size)
        let centerY   = trackRect.midY

        // 1. Track
        context.fill(
            Path(roundedRect: trackRect, cornerRadius: trackHeight / 2),
            with: .color(track)
        )

        // 2. Fill
        let fillWidth = size.width * CGFloat(fraction)
        if fillWidth > 0 {
            let fillRect = CGRect(
                x:      0,
                y:      trackRect.minY,
                width:  fillWidth,
                height: trackHeight
            )
            context.fill(
                Path(roundedRect: fillRect, cornerRadius: trackHeight / 2),
                with: .color(isDay ? dayFill : nightFill)
            )
        }

        // 3. Marker
        let markerX = fillWidth
        if isDay {
            drawSun(in: context, center: CGPoint(x: markerX, y: centerY))
        } else {
            drawMoon(in: context, center: CGPoint(x: markerX, y: centerY))
        }
    }

    /// 8-ray coral sun: small body + 1px cream halo ring + 8 short
    /// rays at the cardinal + ordinal compass points. Geometry copied
    /// from the locked SVG so the prototype and the live component
    /// render identically.
    static func drawSun(in context: GraphicsContext, center: CGPoint) {
        let bodyR:     CGFloat = 4.0
        let haloR:     CGFloat = 6.0   // body + 2px cream stroke
        let rayInner:  CGFloat = 7.0
        let rayOuter:  CGFloat = 10.0
        let rayWidth:  CGFloat = 1.4
        let ord = rayInner / sqrt(2)   // diagonal ray start
        let ord2 = rayOuter / sqrt(2)

        // Halo first so the body's coral pops on the gold fill.
        let haloRect = CGRect(
            x: center.x - haloR, y: center.y - haloR,
            width: haloR * 2, height: haloR * 2
        )
        context.fill(Path(ellipseIn: haloRect), with: .color(canvas))

        // Rays — 4 cardinal + 4 diagonal.
        var rayPath = Path()
        let rays: [(CGPoint, CGPoint)] = [
            // N / S
            (CGPoint(x: 0, y: -rayInner), CGPoint(x: 0, y: -rayOuter)),
            (CGPoint(x: 0, y:  rayInner), CGPoint(x: 0, y:  rayOuter)),
            // E / W
            (CGPoint(x:  rayInner, y: 0), CGPoint(x:  rayOuter, y: 0)),
            (CGPoint(x: -rayInner, y: 0), CGPoint(x: -rayOuter, y: 0)),
            // Diagonals
            (CGPoint(x:  ord, y: -ord), CGPoint(x:  ord2, y: -ord2)),
            (CGPoint(x: -ord, y: -ord), CGPoint(x: -ord2, y: -ord2)),
            (CGPoint(x:  ord, y:  ord), CGPoint(x:  ord2, y:  ord2)),
            (CGPoint(x: -ord, y:  ord), CGPoint(x: -ord2, y:  ord2)),
        ]
        for (start, end) in rays {
            rayPath.move(to:    CGPoint(x: center.x + start.x, y: center.y + start.y))
            rayPath.addLine(to: CGPoint(x: center.x + end.x,   y: center.y + end.y))
        }
        context.stroke(
            rayPath,
            with: .color(sun),
            style: StrokeStyle(lineWidth: rayWidth, lineCap: .round)
        )

        // Body on top of the halo.
        let bodyRect = CGRect(
            x: center.x - bodyR, y: center.y - bodyR,
            width: bodyR * 2, height: bodyR * 2
        )
        context.fill(Path(ellipseIn: bodyRect), with: .color(sun))
    }

    /// Crescent moon: dark-grey disc with a cream punch-out offset
    /// to the upper-right, producing a waxing shape.
    static func drawMoon(in context: GraphicsContext, center: CGPoint) {
        let bodyR:  CGFloat = 6.5
        let cutR:   CGFloat = 5.1
        let cutDx:  CGFloat =  2.3
        let cutDy:  CGFloat = -1.2

        // Halo (separates moon body from the inky bar fill on its
        // left side, matching the sun's halo treatment for
        // consistency).
        let haloR: CGFloat = bodyR + 1.5
        let haloRect = CGRect(
            x: center.x - haloR, y: center.y - haloR,
            width: haloR * 2, height: haloR * 2
        )
        context.fill(Path(ellipseIn: haloRect), with: .color(canvas))

        // Body.
        let bodyRect = CGRect(
            x: center.x - bodyR, y: center.y - bodyR,
            width: bodyR * 2, height: bodyR * 2
        )
        context.fill(Path(ellipseIn: bodyRect), with: .color(moon))

        // Cream cut-out for the crescent shape.
        let cutRect = CGRect(
            x: center.x + cutDx - cutR, y: center.y + cutDy - cutR,
            width: cutR * 2, height: cutR * 2
        )
        context.fill(Path(ellipseIn: cutRect), with: .color(canvas))
    }
}

// MARK: - Previews

#if DEBUG
struct DaylightStatusBar_Previews: PreviewProvider {
    static var previews: some View {
        Group {
            DaylightStatusBar(latitude: 12.97, longitude: 77.59)
                .previewDisplayName("Bangalore — live now")
            DaylightStatusBar(latitude: nil, longitude: nil)
                .previewDisplayName("Loading shell")
        }
        .previewLayout(.sizeThatFits)
    }
}
#endif
