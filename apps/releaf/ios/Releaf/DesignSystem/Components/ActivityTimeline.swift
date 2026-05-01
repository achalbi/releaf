/*
 * ActivityTimeline.swift
 *
 * Bramble-garland activity timeline. A serpentine vine runs down the
 * left of a cream card. Each entry is a 5-petal flower in one of the
 * four leaf themes; between entries the vine carries a stout leaf and
 * a berry trio sitting on the vine line.
 *
 * Source spec: design-system/timeline-vine-bramble-garland.html.
 *
 * Layout is fixed-height: each entry occupies `entryStride` (90pt) so
 * the vine bezier can be plotted from known y positions. If you need a
 * dynamic-height variant, the cleanest path is to measure rows with a
 * preference key and compute the path on the fly — kept out of v1 to
 * keep the visual diff against the static reference at zero.
 */

import SwiftUI

// MARK: - Public model

public enum ActivityProminence: Sendable {
    /// Featured entry — bigger flower (today, captured highlights).
    case featured
    /// Routine entry — smaller flower.
    case routine
}

public struct ActivityEntry: Identifiable, Sendable {
    public let id: UUID
    public let date: String
    public let title: String
    public let preview: String?
    public let theme: AccentPaletteID
    public let prominence: ActivityProminence

    public init(
        id: UUID = UUID(),
        date: String,
        title: String,
        preview: String? = nil,
        theme: AccentPaletteID,
        prominence: ActivityProminence = .routine
    ) {
        self.id = id
        self.date = date
        self.title = title
        self.preview = preview
        self.theme = theme
        self.prominence = prominence
    }
}

// MARK: - Component

public struct ActivityTimeline: View {
    public let entries: [ActivityEntry]
    public let header: String
    public let showsArrow: Bool

    /// Width of the leading column reserved for the vine + flower marker.
    private let leadColumn: CGFloat = 64
    /// Where the vine sits inside each row (x within the row's leading column).
    private let vineX: CGFloat = 46
    /// Half-width of each vine bulge (controlX offset from vineX).
    private let bulge: CGFloat = 20
    /// Vertical distance between entry centers.
    private let entryStride: CGFloat = 64
    /// Visible flower marker size — used to offset it onto the vine line.
    private let markerSize: CGFloat = 34
    /// Length of the trailing vine stub past the last entry.
    private let trailingTail: CGFloat = 40

    public init(
        entries: [ActivityEntry],
        header: String = "Activity",
        showsArrow: Bool = false
    ) {
        self.entries = entries
        self.header = header
        self.showsArrow = showsArrow
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            // Eyebrow header — matches the YOUR LIBRARY / CONTACTS
            // pattern from the other Home cards.
            HStack(alignment: .firstTextBaseline) {
                Text(header)
                    .font(AppText.eyebrow)
                    .tracking(AppLetterSpacing.eyebrow)
                    .foregroundStyle(AppColors.themeGreenDeep)
                Spacer()
                if showsArrow {
                    Text("\u{2192}")
                        .font(AppText.button)
                        .foregroundStyle(AppColors.coral)
                }
            }
            .padding(.horizontal, AppSpacing.s4)
            .padding(.top, AppSpacing.s4)
            .padding(.bottom, AppSpacing.s2)

            Rectangle()
                .fill(AppColors.borderDefault)
                .frame(height: 0.5)
                .padding(.horizontal, AppSpacing.s4)

            ZStack(alignment: .topLeading) {
                Canvas { context, _ in
                    drawVine(in: context)
                    drawGarland(in: context)
                }
                .frame(height: timelineHeight)

                VStack(spacing: 0) {
                    ForEach(entries) { entry in
                        entryRow(entry)
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(AppColors.cardSolid)
        .overlay(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .stroke(AppColors.borderDefault, lineWidth: 0.5)
        )
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

    // MARK: - Layout helpers

    /// Total height of the canvas drawing area: room for every row plus a
    /// short tail so the vine doesn't end abruptly at the last flower.
    private var timelineHeight: CGFloat {
        guard !entries.isEmpty else { return 0 }
        return CGFloat(entries.count) * entryStride + trailingTail
    }

    /// The y at which the vine should reach each entry's flower center.
    private func vineY(for index: Int) -> CGFloat {
        entryStride / 2 + CGFloat(index) * entryStride
    }

    @ViewBuilder
    private func entryRow(_ entry: ActivityEntry) -> some View {
        HStack(alignment: .top, spacing: 0) {
            // Leading column: explicitly position the marker at (vineX, mid)
            // so it lands on the vine line drawn by the Canvas underneath.
            ZStack(alignment: .topLeading) {
                Color.clear
                FlowerMarker(theme: entry.theme, prominence: entry.prominence)
                    .frame(width: markerSize, height: markerSize)
                    .offset(
                        x: vineX - markerSize / 2,
                        y: entryStride / 2 - markerSize / 2
                    )
            }
            .frame(width: leadColumn, height: entryStride)

            VStack(alignment: .leading, spacing: 2) {
                Text(entry.date)
                    .font(.system(size: 11))
                    .foregroundStyle(AppColors.textSecondary)
                Text(entry.title)
                    .font(.system(size: 14))
                    .fontWeight(.medium)
                    .foregroundStyle(AppColors.textPrimary)
                if let preview = entry.preview {
                    Text(preview)
                        .font(.system(size: 12))
                        .foregroundStyle(AppColors.textSecondary)
                        .lineLimit(1)
                        .truncationMode(.tail)
                }
            }
            .padding(.leading, 12)
            .padding(.top, (entryStride - 50) / 2)
            .padding(.trailing, AppSpacing.s4)

            Spacer(minLength: 0)
        }
        .frame(height: entryStride)
    }

    // MARK: - Drawing

    private func drawVine(in context: GraphicsContext) {
        guard !entries.isEmpty else { return }
        var path = Path()
        path.move(to: CGPoint(x: vineX, y: 0))
        for index in entries.indices {
            let y = vineY(for: index)
            if index == 0 {
                path.addLine(to: CGPoint(x: vineX, y: y))
            } else {
                let prevY = vineY(for: index - 1)
                let bulgeRight = (index - 1) % 2 == 0
                let controlX = bulgeRight ? vineX + bulge : vineX - bulge
                let controlY = (prevY + y) / 2
                path.addQuadCurve(
                    to: CGPoint(x: vineX, y: y),
                    control: CGPoint(x: controlX, y: controlY)
                )
            }
        }
        path.addLine(to: CGPoint(x: vineX, y: timelineHeight))

        context.stroke(
            path,
            with: .color(AppColors.themeGreenDeep),
            style: StrokeStyle(lineWidth: 1.8, lineCap: .round)
        )
    }

    private func drawGarland(in context: GraphicsContext) {
        // For each gap (between entry i and entry i+1) place one leaf at the
        // bulge apex and one berry trio further along the vine below the apex.
        for gap in 0..<(entries.count - 1) {
            let bulgeRight = gap % 2 == 0
            let prevY = vineY(for: gap)
            let nextY = vineY(for: gap + 1)
            let apexY = (prevY + nextY) / 2

            // Leaf base sits on the vine apex at vineX +/- bulge/2; tip
            // angled outward 40deg.
            let leafBaseX = bulgeRight ? vineX + bulge / 2 : vineX - bulge / 2
            let leafBase = CGPoint(x: leafBaseX, y: apexY - 14)
            let leafAngle = Angle.degrees(bulgeRight ? 40 : -40)
            drawLeaf(in: context, at: leafBase, angle: leafAngle)

            // Berries sit on the vine 30pt below the apex; vine x there is
            // approximately vineX +/- (bulge/2 - 2).
            let berryX = bulgeRight ? vineX + bulge / 2 : vineX - bulge / 2
            let berryCenter = CGPoint(x: berryX, y: apexY + 30)
            let berryColor: Color = bulgeRight ? AppColors.coral700 : AppColors.themeDryDeep
            drawBerryTrio(in: context, at: berryCenter, color: berryColor, mirrored: !bulgeRight)
        }
    }

    private func drawLeaf(in context: GraphicsContext, at base: CGPoint, angle: Angle) {
        var leaf = Path()
        leaf.move(to: .zero)
        leaf.addQuadCurve(to: CGPoint(x: 0, y: -48), control: CGPoint(x: -13, y: -26))
        leaf.addQuadCurve(to: .zero, control: CGPoint(x: 13, y: -26))
        leaf.closeSubpath()

        var midrib = Path()
        midrib.move(to: CGPoint(x: 0, y: -6))
        midrib.addLine(to: CGPoint(x: 0, y: -42))

        let transform = CGAffineTransform(translationX: base.x, y: base.y)
            .rotated(by: angle.radians)

        context.fill(leaf.applying(transform), with: .color(AppColors.themeGreenPrimary))
        context.stroke(
            leaf.applying(transform),
            with: .color(AppColors.themeGreenDeep),
            lineWidth: 1.0
        )
        context.stroke(
            midrib.applying(transform),
            with: .color(Color(hex: 0x463C31, alpha: 0.4)),
            lineWidth: 0.8
        )
    }

    private func drawBerryTrio(in context: GraphicsContext, at center: CGPoint, color: Color, mirrored: Bool) {
        // Cluster offsets — mirrored on the x-axis for left bulges so the
        // trio leans toward the card interior, not into the padding.
        let signX: CGFloat = mirrored ? -1 : 1
        let offsets: [(CGPoint, CGFloat)] = [
            (CGPoint(x: center.x,                  y: center.y),       7.2),
            (CGPoint(x: center.x + 10.8 * signX,   y: center.y + 6.8), 6.8),
            (CGPoint(x: center.x - 5.2 * signX,    y: center.y + 10.0), 6.2),
        ]
        for (point, radius) in offsets {
            let circle = Path(ellipseIn: CGRect(
                x: point.x - radius,
                y: point.y - radius,
                width: radius * 2,
                height: radius * 2
            ))
            context.fill(circle, with: .color(color))
        }
    }
}

// MARK: - Flower marker

private struct FlowerMarker: View {
    let theme: AccentPaletteID
    let prominence: ActivityProminence

    var body: some View {
        let palette = AccentPalettes.forID(theme)
        let isFeatured = prominence == .featured
        let petalRx: CGFloat = isFeatured ? 10.8 : 9.6
        let petalRy: CGFloat = isFeatured ? 15.6 : 14.0
        let centerR: CGFloat = isFeatured ? 6.8 : 5.6
        let petalCenterY: CGFloat = isFeatured ? -17.0 : -15.4
        let centerColor: Color = theme == .yellow ? AppColors.coral700 : AppColors.themeYellowPrimary

        ZStack {
            ForEach(0..<5, id: \.self) { index in
                Petal(rx: petalRx, ry: petalRy, centerY: petalCenterY)
                    .fill(palette.primary)
                    .overlay(
                        Petal(rx: petalRx, ry: petalRy, centerY: petalCenterY)
                            .stroke(palette.deep, lineWidth: 1.0)
                    )
                    .rotationEffect(.degrees(Double(index) * 72))
            }
            Circle()
                .fill(centerColor)
                .frame(width: centerR * 2, height: centerR * 2)
        }
        .frame(width: 34, height: 34)
    }
}

private struct Petal: Shape {
    let rx: CGFloat
    let ry: CGFloat
    let centerY: CGFloat

    func path(in rect: CGRect) -> Path {
        let cx = rect.midX
        let cy = rect.midY + centerY
        return Path(ellipseIn: CGRect(
            x: cx - rx,
            y: cy - ry,
            width: rx * 2,
            height: ry * 2
        ))
    }
}

// MARK: - Preview

#Preview {
    ZStack {
        AppColors.canvas.ignoresSafeArea()
        ActivityTimeline(entries: [
            ActivityEntry(
                date: "Today",
                title: "Journaled 3 entries",
                preview: "Morning page on procrastination",
                theme: .coral,
                prominence: .featured
            ),
            ActivityEntry(
                date: "Yesterday",
                title: "Walked 2.4 mi",
                preview: "Loop around the lake before dinner",
                theme: .green
            ),
            ActivityEntry(
                date: "Mar 22",
                title: "Captured a quote",
                preview: "On finding meaning in routine",
                theme: .yellow,
                prominence: .featured
            ),
            ActivityEntry(
                date: "Mar 20",
                title: "Photo journal",
                preview: "Spring shoots in the back garden",
                theme: .dry
            ),
            ActivityEntry(
                date: "Mar 18",
                title: "Voice memo",
                preview: "Notes for the new project pitch",
                theme: .green
            ),
        ])
        .padding()
    }
}
