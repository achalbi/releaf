/*
 * TreesSavedStripView.swift
 * Ambient "trees saved" strip — always-visible single-line stat that
 * sits above the shelf filter row on the variant-1 shelves screen.
 * A tree glyph (3-tier evergreen) sways gently on idle; the number
 * counts up to its target on first appear.
 *
 * Formula: trees = (notes + photos + scans + voice + contacts) / 8000
 * Rationale: ~8000 pages per mature tree (Conservatree heuristic).
 */

import SwiftUI
import ReleafDesignSystem

// MARK: - Metrics

/// Aggregated capture counts that drive the "trees saved" figure.
/// Populate from `CaptureRepository` in the view model. For the
/// variant-1 screen the caller builds this from the already-loaded
/// notebook summaries plus whatever mode-typed capture counts the
/// domain layer exposes.
public struct TreesSavedMetrics: Equatable {
    public let notes: Int
    public let photos: Int
    public let scans: Int
    public let voice: Int
    public let contacts: Int
    public let locations: Int

    public init(
        notes: Int,
        photos: Int = 0,
        scans: Int = 0,
        voice: Int = 0,
        contacts: Int = 0,
        locations: Int = 0
    ) {
        self.notes = notes
        self.photos = photos
        self.scans = scans
        self.voice = voice
        self.contacts = contacts
        self.locations = locations
    }

    /// Total captures across all six modes. Pages count as "notes".
    public var total: Int {
        notes + photos + scans + voice + contacts + locations
    }

    /// Trees-saved figure. ~8,333 sheets ≈ 1 tree (Conservatree heuristic).
    public var trees: Double {
        Double(total) / TreesSavedMetrics.capturesPerTree
    }

    /// Fractional progress (0…1) toward the *next* whole tree.
    public var progressToNextTree: Double {
        let remainder = Double(total).truncatingRemainder(
            dividingBy: TreesSavedMetrics.capturesPerTree
        )
        return remainder / TreesSavedMetrics.capturesPerTree
    }

    public static let capturesPerTree: Double = 8333
}

// MARK: - Strip view

public struct TreesSavedStripView: View {
    let metrics: TreesSavedMetrics

    @State private var displayedTrees: Double = 0
    @State private var didAnimate: Bool = false

    public init(metrics: TreesSavedMetrics) {
        self.metrics = metrics
    }

    public var body: some View {
        HStack(spacing: AppSpacing.s3) {
            TreeGlyph()
                .frame(width: 36, height: 44)

            VStack(alignment: .leading, spacing: 2) {
                HStack(alignment: .firstTextBaseline, spacing: 6) {
                    Text(formatTreesStrip(displayedTrees))
                        .font(.system(size: 30))
                        .foregroundStyle(AppColors.themeGreenDeep)
                        .monospacedDigit()
                    Text("trees saved")
                        .font(AppText.meta)
                        .foregroundStyle(AppColors.textSecondary)
                }
                // The total spans both notebook pages AND notepad
                // entries (plus their attachments + contacts + locations)
                // — the "kept digital" copy framing captures the user
                // impact: each count is a paper page that didn't get
                // printed.
                Text("\(formattedTotal) captures kept digital")
                    .font(AppText.tag)
                    .foregroundStyle(AppColors.textTertiary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            VStack(alignment: .trailing, spacing: 4) {
                Text("NEXT TREE")
                    .font(AppText.eyebrow)
                    .tracking(AppLetterSpacing.eyebrow)
                    .foregroundStyle(AppColors.textSecondary)
                ZStack(alignment: .leading) {
                    Capsule()
                        .fill(AppColors.subtle)
                        .frame(width: 52, height: 4)
                    Capsule()
                        .fill(AppColors.themeGreenPrimary)
                        .frame(width: 52 * CGFloat(metrics.progressToNextTree), height: 4)
                }
                Text(String(format: "%.1f to go", max(0, 1 - metrics.progressToNextTree)))
                    .font(AppText.tag)
                    .foregroundStyle(AppColors.textSecondary)
            }
        }
        .padding(.horizontal, AppSpacing.s4)
        .padding(.vertical, AppSpacing.s3)
        .background(
            RoundedRectangle(cornerRadius: AppRadius.md, style: .continuous)
                .fill(AppColors.cardSolid)
        )
        .overlay(
            RoundedRectangle(cornerRadius: AppRadius.md, style: .continuous)
                .stroke(AppColors.themeGreenDeep.opacity(0.25), lineWidth: 0.5)
        )
        .onAppear {
            guard !didAnimate else { return }
            didAnimate = true
            withAnimation(.easeOut(duration: 0.9)) {
                displayedTrees = metrics.trees
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(accessibilitySummary))
    }

    private var formattedTotal: String {
        let f = NumberFormatter()
        f.numberStyle = .decimal
        return f.string(from: NSNumber(value: metrics.total)) ?? "\(metrics.total)"
    }

    private var accessibilitySummary: String {
        "\(formatTreesStrip(metrics.trees)) trees saved from \(formattedTotal) captures"
    }
}

/// Trees number formatter — up to 3 decimal places, trailing zeros
/// dropped (so "3.2", "3.25", "3.251" but never "3.200").
private func formatTreesStrip(_ value: Double) -> String {
    let f = NumberFormatter()
    f.numberStyle = .decimal
    f.minimumFractionDigits = 0
    f.maximumFractionDigits = 3
    f.usesGroupingSeparator = false
    return f.string(from: NSNumber(value: value)) ?? String(value)
}

// MARK: - Tree glyph

/// Three-tier evergreen with a subtle 5-second idle sway. Uses
/// absolute hex colors because the tree has a fixed visual identity
/// independent of theme.
private struct TreeGlyph: View {
    @State private var swayRight: Bool = false

    private let canopyTop    = Color(red: 0.478, green: 0.659, blue: 0.455) // #7AA874
    private let canopyMid    = Color(red: 0.357, green: 0.549, blue: 0.322) // #5B8C52
    private let canopyBottom = Color(red: 0.243, green: 0.420, blue: 0.231) // #3E6B3B
    private let trunk        = Color(red: 0.243, green: 0.165, blue: 0.094) // #3E2A18

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
        .rotationEffect(.degrees(swayRight ? 1.5 : -1.0), anchor: .bottom)
        .animation(
            .easeInOut(duration: 2.6).repeatForever(autoreverses: true),
            value: swayRight
        )
        .onAppear { swayRight = true }
    }
}

// MARK: - Preview

#Preview("Ambient strip · 3.2 trees") {
    VStack(spacing: AppSpacing.s4) {
        TreesSavedStripView(metrics: TreesSavedMetrics(
            notes: 4280, photos: 18400, scans: 1820, voice: 980, contacts: 120
        ))
        TreesSavedStripView(metrics: TreesSavedMetrics(notes: 428))
        TreesSavedStripView(metrics: TreesSavedMetrics(notes: 0))
    }
    .padding()
    .background(AppColors.canvas)
}
