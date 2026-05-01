/*
 * StatGrid.swift
 *
 * 3-column dashboard glance: each cell has a giant number + eyebrow label.
 * Stays 3-up on narrow widths by design; numbers shrink with width so
 * the layout never collapses to 2 columns.
 *
 * Ported from Inkcreate mobile DS.
 */

import SwiftUI

// MARK: - StatItem

public struct StatItem: Identifiable, Equatable {
    public enum Tone: Equatable { case neutral, coral, green, info }

    public let id: String
    public let label: String
    public let value: String
    public let tone: Tone
    /// Optional capture mode — when set, the card carries a small
    /// leaf-droplet glyph in its top-right corner, color-keyed by
    /// `LeafDropletGlyph.tint(for:)`. Used by the page-overview AT A
    /// GLANCE grid so each tile reads as a category, not a stat.
    public let mode: CaptureMode?

    public init(
        id: String = UUID().uuidString,
        label: String,
        value: String,
        tone: Tone = .neutral,
        mode: CaptureMode? = nil
    ) {
        self.id = id
        self.label = label
        self.value = value
        self.tone = tone
        self.mode = mode
    }
}

// MARK: - StatGrid

public struct StatGrid: View {
    private let items: [StatItem]
    private let valueDesign: Font.Design

    /// `valueDesign` controls the typeface used for the big number on
    /// every cell. Default is `.default` (sans) so existing call sites
    /// keep their look. The page Overview opts into `.serif` so its
    /// AT A GLANCE tiles read editorial — same family as the page
    /// title above them.
    public init(items: [StatItem], valueDesign: Font.Design = .default) {
        self.items = items
        self.valueDesign = valueDesign
    }

    public var body: some View {
        HStack(alignment: .top, spacing: AppSpacing.s3) {
            ForEach(items) { item in
                StatCard(item: item, valueDesign: valueDesign)
                    .frame(maxWidth: .infinity)
            }
        }
    }
}

// MARK: - Card

private struct StatCard: View {
    let item: StatItem
    let valueDesign: Font.Design

    /// Treat "0", "—", or empty as a zero-count tile — fades the
    /// card so the non-zero categories pop in the grid. Anything
    /// numeric ≥ 1 keeps full weight.
    private var isEmpty: Bool {
        let trimmed = item.value.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty || trimmed == "—" { return true }
        if let n = Int(trimmed), n == 0 { return true }
        return false
    }

    var body: some View {
        ZStack(alignment: .topTrailing) {
            VStack(alignment: .leading, spacing: AppSpacing.s3) {
                Text(item.label.uppercased())
                    .font(AppText.eyebrow)
                    .tracking(AppLetterSpacing.eyebrow)
                    .foregroundColor(toneLabel)
                    .lineLimit(2)

                Text(item.value)
                    .font(.system(size: 32, design: valueDesign))
                    .foregroundColor(
                        isEmpty ? AppColors.textTertiary : AppColors.textPrimary
                    )
                    .minimumScaleFactor(0.5)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(AppSpacing.s4)

            // Hide the droplet glyph entirely on empty tiles —
            // those categories aren't "active" yet, so giving them
            // a colored corner mark would mis-signal use.
            if !isEmpty, let mode = item.mode {
                LeafDropletGlyph(tint: LeafDropletGlyph.tint(for: mode))
                    .padding(.top, AppSpacing.s3)
                    .padding(.trailing, AppSpacing.s3)
            }
        }
        .background(
            RoundedRectangle(cornerRadius: AppRadius.lg, style: .continuous)
                .fill(AppColors.cardSolid)
        )
        .overlay(
            RoundedRectangle(cornerRadius: AppRadius.lg, style: .continuous)
                .stroke(AppColors.borderDefault, lineWidth: 1)
        )
        // Soft fade on empty tiles. Same shape, lower visual
        // weight — the eye drifts to the active categories first.
        .opacity(isEmpty ? 0.55 : 1.0)
        .appShadow(.sm)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(Text("\(item.label): \(item.value)"))
    }

    private var toneLabel: Color {
        switch item.tone {
        case .neutral: return AppColors.textSecondary
        case .coral:   return AppColors.coralDeep
        case .green:   return AppColors.green
        case .info:    return AppColors.info
        }
    }
}

// MARK: - StatList

/// Vertical companion to `StatGrid`. Renders the same `[StatItem]` as
/// six full-width rows — droplet glyph + label + value — in a single
/// card. Used by the page Overview when the user flips the view
/// toggle to list mode; the stat data is identical, only the
/// presentation density changes.
public struct StatList: View {
    private let items: [StatItem]
    private let valueDesign: Font.Design

    public init(items: [StatItem], valueDesign: Font.Design = .default) {
        self.items = items
        self.valueDesign = valueDesign
    }

    public var body: some View {
        VStack(spacing: 0) {
            ForEach(Array(items.enumerated()), id: \.element.id) { index, item in
                StatRow(item: item, valueDesign: valueDesign)
                if index < items.count - 1 {
                    Rectangle()
                        .fill(AppColors.borderDefault)
                        .frame(height: 0.5)
                }
            }
        }
        .background(
            RoundedRectangle(cornerRadius: AppRadius.lg, style: .continuous)
                .fill(AppColors.cardSolid)
        )
        .overlay(
            RoundedRectangle(cornerRadius: AppRadius.lg, style: .continuous)
                .stroke(AppColors.borderDefault, lineWidth: 1)
        )
        .clipShape(RoundedRectangle(cornerRadius: AppRadius.lg, style: .continuous))
        .appShadow(.sm)
    }
}

private struct StatRow: View {
    let item: StatItem
    let valueDesign: Font.Design

    var body: some View {
        HStack(spacing: AppSpacing.s3) {
            if let mode = item.mode {
                LeafDropletGlyph(tint: LeafDropletGlyph.tint(for: mode), size: 13)
            }
            Text(item.label.uppercased())
                .font(AppText.eyebrow)
                .tracking(AppLetterSpacing.eyebrow)
                .foregroundColor(toneLabel)
            Spacer()
            Text(item.value)
                .font(.system(size: 22, design: valueDesign))
                .foregroundColor(AppColors.textPrimary)
                .lineLimit(1)
        }
        .padding(.horizontal, AppSpacing.s4)
        .padding(.vertical, AppSpacing.s3)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(Text("\(item.label): \(item.value)"))
    }

    private var toneLabel: Color {
        switch item.tone {
        case .neutral: return AppColors.textSecondary
        case .coral:   return AppColors.coralDeep
        case .green:   return AppColors.green
        case .info:    return AppColors.info
        }
    }
}

// MARK: - Preview

#if DEBUG
struct StatGrid_Previews: PreviewProvider {
    static var previews: some View {
        VStack(spacing: AppSpacing.s4) {
            StatGrid(items: [
                StatItem(label: "Notebooks", value: "4",  tone: .coral),
                StatItem(label: "Pages",     value: "3",  tone: .neutral),
                StatItem(label: "Today",     value: "0",  tone: .green),
            ])

            // Edge case: wide numbers on a narrow width
            StatGrid(items: [
                StatItem(label: "Photos",   value: "128",  tone: .coral),
                StatItem(label: "Contacts", value: "42",   tone: .info),
                StatItem(label: "Minutes",  value: "2.4k", tone: .neutral),
            ])
        }
        .padding(AppSpacing.s4)
        .frame(width: 390)
        .background(AppColors.canvas)
        .previewDisplayName("StatGrid")
    }
}
#endif
