/*
 * ReLeafStrip.swift
 *
 * Two-tile strip that sits between the CaptureTabBar and the AT A
 * GLANCE grid on the page Overview. Reuses the same card vocabulary
 * as StatTile so it reads as a section the page always had — not a
 * new component class.
 *
 * Left tile  — SHEETS SAVED + tree silhouette in the bottom-right
 * Right tile — FOREST + five-segment progress dotline
 *
 * Tapping the "RE-LEAF" eyebrow above the tiles fires `onShowDetail`,
 * which the parent wires to the PaperSavedSheet. Eyebrow is the only
 * tap target; the tiles themselves are read-only.
 */

import SwiftUI

public struct ReLeafStrip: View {
    public let impact: ReleafImpact
    public let onShowDetail: () -> Void
    /// Optional override for the eyebrow + SHEETS SAVED label
    /// tint. When nil, the strip stays in its default
    /// themeGreenDeep tone — appropriate for a Re-Leaf metric.
    /// PageDetail passes the parent-notebook color when set so
    /// the strip reads as part of the surface family.
    /// The tree silhouette + progress dots intentionally stay
    /// green: those visuals stand for "trees" semantically and
    /// shouldn't morph with notebook color.
    public let accentOverride: Color?

    public init(
        impact: ReleafImpact,
        onShowDetail: @escaping () -> Void = {},
        accentOverride: Color? = nil
    ) {
        self.impact = impact
        self.onShowDetail = onShowDetail
        self.accentOverride = accentOverride
    }

    public var body: some View {
        let eyebrowTint = accentOverride ?? AppColors.themeGreenDeep
        VStack(alignment: .leading, spacing: AppSpacing.s2) {
            Button(action: onShowDetail) {
                HStack(spacing: AppSpacing.s1) {
                    Text("RE-LEAF")
                        .font(AppText.eyebrow)
                        .tracking(AppLetterSpacing.eyebrow)
                        .foregroundStyle(eyebrowTint)
                    Image(systemName: "info.circle")
                        .font(.system(size: 10))
                        .foregroundStyle(eyebrowTint.opacity(0.55))
                }
            }
            .buttonStyle(.plain)
            .accessibilityHint("Show how paper saved is counted")

            HStack(spacing: AppSpacing.s2) {
                SheetsSavedTile(value: impact.formattedSheets, accentTint: eyebrowTint)
                ForestTile(impact: impact)
            }
        }
    }
}

// MARK: - SHEETS SAVED tile

private struct SheetsSavedTile: View {
    let value: String
    /// Eyebrow tint. Defaults to the green theme; ReLeafStrip
    /// passes through the override when one was supplied so the
    /// tile's eyebrow matches the parent strip's eyebrow.
    let accentTint: Color

    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            VStack(alignment: .leading, spacing: AppSpacing.s2) {
                Text("SHEETS SAVED")
                    .font(AppText.eyebrow)
                    .tracking(AppLetterSpacing.eyebrow)
                    .foregroundStyle(accentTint)
                Text(value)
                    .font(.system(size: 32, design: .serif))
                    .foregroundStyle(AppColors.textPrimary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
                Text("paper not printed")
                    .font(AppText.tag)
                    .foregroundStyle(AppColors.textTertiary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(AppSpacing.s4)

            TreeSilhouette()
                .frame(width: 56, height: 52)
                .padding(.trailing, -6)
                .padding(.bottom, -6)
                .opacity(0.95)
                .accessibilityHidden(true)
        }
        .frame(maxWidth: .infinity)
        .background(AppColors.cardSolid)
        .overlay(
            RoundedRectangle(cornerRadius: AppRadius.lg, style: .continuous)
                .stroke(AppColors.borderDefault, lineWidth: 1)
        )
        .clipShape(RoundedRectangle(cornerRadius: AppRadius.lg, style: .continuous))
        .appShadow(.sm)
    }
}

// MARK: - FOREST tile

private struct ForestTile: View {
    let impact: ReleafImpact

    var body: some View {
        let readout = impact.treeReadout
        VStack(alignment: .leading, spacing: AppSpacing.s2) {
            Text("FOREST")
                .font(AppText.eyebrow)
                .tracking(AppLetterSpacing.eyebrow)
                .foregroundStyle(AppColors.textSecondary)
            Text(readout.number)
                .font(.system(size: 32, design: .serif))
                .foregroundStyle(AppColors.textPrimary)
                .lineLimit(1)
                .minimumScaleFactor(0.5)
            Text(readout.unit)
                .font(AppText.tag)
                .foregroundStyle(AppColors.textTertiary)
            ForestProgressDots(treeFraction: impact.treeFraction)
                .padding(.top, AppSpacing.s1)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(AppSpacing.s4)
        .background(AppColors.cardSolid)
        .overlay(
            RoundedRectangle(cornerRadius: AppRadius.lg, style: .continuous)
                .stroke(AppColors.borderDefault, lineWidth: 1)
        )
        .clipShape(RoundedRectangle(cornerRadius: AppRadius.lg, style: .continuous))
        .appShadow(.sm)
    }
}

// MARK: - Five-segment progress dotline

private struct ForestProgressDots: View {
    /// 0.0 → empty, 1.0 → all five segments lit.
    let treeFraction: Double

    private static let segmentCount = 5

    var body: some View {
        HStack(spacing: 3) {
            ForEach(0..<Self.segmentCount, id: \.self) { idx in
                let threshold = Double(idx + 1) / Double(Self.segmentCount)
                Capsule()
                    .fill(color(for: threshold))
                    .frame(height: 5)
            }
        }
    }

    private func color(for threshold: Double) -> Color {
        if treeFraction >= threshold {
            // Filled segments shade darker as you move right — the
            // visual hint is "growing toward a full tree".
            switch threshold {
            case 1.0:  return AppColors.green
            case 0.8:  return AppColors.themeGreenDeep
            default:   return AppColors.themeGreenPrimary
            }
        }
        return AppColors.themeGreenPrimary.opacity(0.25)
    }
}

// MARK: - Tree silhouette

private struct TreeSilhouette: View {
    var body: some View {
        Canvas { context, size in
            // Trunk
            let trunkRect = CGRect(
                x: size.width * 0.46,
                y: size.height * 0.62,
                width: size.width * 0.08,
                height: size.height * 0.34
            )
            context.fill(
                Path(roundedRect: trunkRect, cornerRadius: 1.5),
                with: .color(Color(hex: 0x8B7355))
            )

            // Canopy — three overlapping ellipses, deeper greens behind.
            let canopyBack = Path(ellipseIn: CGRect(
                x: size.width * 0.10,
                y: size.height * 0.05,
                width: size.width * 0.80,
                height: size.height * 0.65
            ))
            context.fill(canopyBack, with: .color(AppColors.themeGreenPrimary))

            let canopyRight = Path(ellipseIn: CGRect(
                x: size.width * 0.45,
                y: size.height * 0.0,
                width: size.width * 0.55,
                height: size.height * 0.50
            ))
            context.fill(canopyRight, with: .color(AppColors.themeGreenDeep))

            let canopyLeft = Path(ellipseIn: CGRect(
                x: size.width * 0.0,
                y: size.height * 0.05,
                width: size.width * 0.50,
                height: size.height * 0.45
            ))
            context.fill(canopyLeft, with: .color(AppColors.themeGreenDeep))
        }
    }
}

// MARK: - Preview

#if DEBUG
struct ReLeafStrip_Previews: PreviewProvider {
    static var previews: some View {
        VStack(spacing: AppSpacing.s4) {
            ReLeafStrip(
                impact: ReleafImpact(
                    photos: 2, voiceNotes: 1, todoItems: 1,
                    scans: 2, contacts: 1, places: 1, notes: 1
                )
            )
            ReLeafStrip(
                impact: ReleafImpact(
                    photos: 84, voiceNotes: 24, todoItems: 47,
                    scans: 52, contacts: 8, places: 14, notes: 38
                )
            )
        }
        .padding(AppSpacing.s4)
        .background(AppColors.canvas)
        .previewDisplayName("ReLeafStrip — single page vs aggregate")
    }
}
#endif
