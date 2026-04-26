/*
 * TreesSavedHeroView.swift
 * Celebratory "trees saved" hero card for the Home tab — deep-green
 * slab with a coral headline number, a three-tree cluster on the
 * right, a segmented composition bar, and a five-mode legend.
 *
 * Use on: Home tab (always visible). For the Notebooks tab
 * (HomeScreenVariant1) use the compact `TreesSavedStripView`
 * instead — the library screen doesn't need a big slab.
 *
 * Formula: trees = (notes + photos + scans + voice + contacts) / 8000
 * Rationale: ~8000 pages per mature tree (Conservatree heuristic).
 */

import SwiftUI
import ReleafDesignSystem
import ReleafData

public struct TreesSavedHeroView: View {
    let counts: CaptureCountsByMode

    @State private var displayedTrees: Double = 0
    @State private var displayedCaptures: Double = 0
    @State private var barsFilled: Bool = false
    @State private var didAnimate: Bool = false

    public init(counts: CaptureCountsByMode) {
        self.counts = counts
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: AppSpacing.s3) {
            HStack(alignment: .top) {
                Text("TREES SAVED")
                    .font(AppText.eyebrow)
                    .tracking(AppLetterSpacing.eyebrow)
                    .foregroundStyle(Color(hex: 0xD9EDE2))

                Spacer(minLength: AppSpacing.s3)

                HStack(alignment: .bottom, spacing: 2) {
                    TreeGlyph(height: 44)
                    TreeGlyph(height: 54)
                    TreeGlyph(height: 40)
                }
            }

            Text(formatTrees(displayedTrees))
                .font(.system(size: 72))
                .foregroundStyle(Color(hex: 0xE77850))
                .monospacedDigit()
                .kerning(-2)

            Text("\(formattedCaptures) captures kept digital · Every ~8,333 sheets = 1 tree")
                .font(AppText.meta)
                .foregroundStyle(Color(hex: 0xD9EDE2))
                .lineLimit(2)
                .fixedSize(horizontal: false, vertical: true)

            CompositionBar(counts: counts, filled: barsFilled)
                .frame(height: 8)
                .padding(.top, AppSpacing.s1)

            LegendGrid(counts: counts)
                .padding(.top, AppSpacing.s2)
        }
        .padding(AppSpacing.s5)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: AppRadius.lg, style: .continuous)
                .fill(Color(hex: 0x1E5943))
        )
        .onAppear {
            guard !didAnimate else { return }
            didAnimate = true
            withAnimation(.easeOut(duration: 1.4)) {
                displayedTrees = counts.trees
                displayedCaptures = Double(counts.total)
            }
            withAnimation(.easeOut(duration: 0.9).delay(0.4)) {
                barsFilled = true
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(accessibilitySummary))
    }

    // MARK: - Derived

    /// Trees-saved: captures / 8000. Keep the divisor in one place so
    /// the label and the number can't drift apart.
    private var capturesPerTree: Double { 8000 }

    private var formattedCaptures: String {
        let rounded = Int(displayedCaptures.rounded())
        let f = NumberFormatter()
        f.numberStyle = .decimal
        return f.string(from: NSNumber(value: rounded)) ?? "\(rounded)"
    }

    private var accessibilitySummary: String {
        "\(formatTrees(counts.trees)) trees saved from \(counts.total) captures"
    }
}

/// Trees number formatter — up to 3 decimal places, trailing zeros
/// dropped (so "3.2", "3.25", "3.251" but never "3.200").
private func formatTrees(_ value: Double) -> String {
    let f = NumberFormatter()
    f.numberStyle = .decimal
    f.minimumFractionDigits = 0
    f.maximumFractionDigits = 3
    f.usesGroupingSeparator = false
    return f.string(from: NSNumber(value: value)) ?? String(value)
}

private extension CaptureCountsByMode {
    /// Same formula the strip uses — hoisted here so the hero and the
    /// strip stay in lockstep. Divisor: ~8,333 sheets ≈ 1 tree
    /// (Conservatree heuristic).
    var trees: Double { Double(total) / 8333 }
}

// MARK: - Legend grid

private struct LegendGrid: View {
    let counts: CaptureCountsByMode

    private let items: [(String, Int, UInt32)]
    init(counts: CaptureCountsByMode) {
        self.counts = counts
        self.items = [
            ("Notes",     counts.notes,     0x7AA874),
            ("Photos",    counts.photos,    0xF4C430),
            ("Scans",     counts.scans,     0xE77850),
            ("Voice",     counts.voice,     0xFCEAE0),
            ("Contacts",  counts.contacts,  0xD9EDE2),
            ("Locations", counts.locations, 0xB8956A),
        ]
    }

    var body: some View {
        let columns = [GridItem(.flexible(), spacing: AppSpacing.s3),
                       GridItem(.flexible(), spacing: AppSpacing.s3),
                       GridItem(.flexible(), spacing: AppSpacing.s3)]
        LazyVGrid(columns: columns, alignment: .leading, spacing: 6) {
            ForEach(items, id: \.0) { (label, count, hex) in
                HStack(spacing: 6) {
                    Circle()
                        .fill(Color(hex: hex))
                        .frame(width: 7, height: 7)
                    Text(label)
                        .font(AppText.tag)
                        .foregroundStyle(Color(hex: 0xD9EDE2))
                    Text(count.formattedDecimal)
                        .font(AppText.tag)
                        .foregroundStyle(AppColors.onAccent)
                }
            }
        }
    }
}

// MARK: - Composition bar

private struct CompositionBar: View {
    let counts: CaptureCountsByMode
    let filled: Bool

    var body: some View {
        GeometryReader { proxy in
            let total = max(1, counts.total)
            let segs: [(UInt32, Double)] = [
                (0x7AA874, Double(counts.notes)),
                (0xF4C430, Double(counts.photos)),
                (0xE77850, Double(counts.scans)),
                (0xFCEAE0, Double(counts.voice)),
                (0xD9EDE2, Double(counts.contacts)),
                (0xB8956A, Double(counts.locations)),
            ]
            let width = proxy.size.width
            HStack(spacing: 0) {
                ForEach(Array(segs.enumerated()), id: \.offset) { idx, seg in
                    let w = filled ? CGFloat(seg.1 / Double(total)) * width : 0
                    Rectangle()
                        .fill(Color(hex: seg.0))
                        .frame(width: w)
                }
                // Trailing track
                Rectangle()
                    .fill(AppColors.onAccent.opacity(0.12))
            }
            .clipShape(Capsule())
        }
    }
}

// MARK: - Tree glyph

/// Three-tier evergreen. Fixed palette — the tree doesn't theme.
private struct TreeGlyph: View {
    let height: CGFloat

    private let canopyTop    = Color(hex: 0x7AA874)
    private let canopyMid    = Color(hex: 0x5B8C52)
    private let canopyBottom = Color(hex: 0x3E6B3B)
    private let trunk        = Color(hex: 0x3E2A18)

    var body: some View {
        Canvas { ctx, size in
            let w = size.width
            let h = size.height

            var top = Path()
            top.move(to: CGPoint(x: w * 0.50, y: h * 0.04))
            top.addLine(to: CGPoint(x: w * 0.25, y: h * 0.44))
            top.addLine(to: CGPoint(x: w * 0.75, y: h * 0.44))
            top.closeSubpath()
            ctx.fill(top, with: .color(canopyTop))

            var mid = Path()
            mid.move(to: CGPoint(x: w * 0.50, y: h * 0.28))
            mid.addLine(to: CGPoint(x: w * 0.15, y: h * 0.68))
            mid.addLine(to: CGPoint(x: w * 0.85, y: h * 0.68))
            mid.closeSubpath()
            ctx.fill(mid, with: .color(canopyMid))

            var bot = Path()
            bot.move(to: CGPoint(x: w * 0.50, y: h * 0.44))
            bot.addLine(to: CGPoint(x: w * 0.06, y: h * 0.88))
            bot.addLine(to: CGPoint(x: w * 0.94, y: h * 0.88))
            bot.closeSubpath()
            ctx.fill(bot, with: .color(canopyBottom))

            let trunkRect = CGRect(x: w * 0.44, y: h * 0.88, width: w * 0.12, height: h * 0.12)
            ctx.fill(Path(trunkRect), with: .color(trunk))
        }
        .frame(width: height * 0.8, height: height)
    }
}

// MARK: - Helpers

private extension Color {
    init(hex: UInt32, alpha: Double = 1) {
        let r = Double((hex >> 16) & 0xFF) / 255
        let g = Double((hex >> 8)  & 0xFF) / 255
        let b = Double( hex        & 0xFF) / 255
        self.init(.sRGB, red: r, green: g, blue: b, opacity: alpha)
    }
}

private extension Int {
    var formattedDecimal: String {
        let f = NumberFormatter()
        f.numberStyle = .decimal
        return f.string(from: NSNumber(value: self)) ?? "\(self)"
    }
}

// MARK: - Preview

#Preview("Hero · 3.2 trees") {
    VStack(spacing: AppSpacing.s4) {
        TreesSavedHeroView(counts: CaptureCountsByMode(
            notes: 4280, photos: 18400, scans: 1820, voice: 980, contacts: 120
        ))
        TreesSavedHeroView(counts: CaptureCountsByMode(notes: 428))
    }
    .padding()
    .background(AppColors.canvas)
}
