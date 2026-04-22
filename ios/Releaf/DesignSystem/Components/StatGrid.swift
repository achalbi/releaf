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

    public init(
        id: String = UUID().uuidString,
        label: String,
        value: String,
        tone: Tone = .neutral
    ) {
        self.id = id
        self.label = label
        self.value = value
        self.tone = tone
    }
}

// MARK: - StatGrid

public struct StatGrid: View {
    private let items: [StatItem]

    public init(items: [StatItem]) {
        self.items = items
    }

    public var body: some View {
        HStack(alignment: .top, spacing: AppSpacing.s3) {
            ForEach(items) { item in
                StatCard(item: item)
                    .frame(maxWidth: .infinity)
            }
        }
    }
}

// MARK: - Card

private struct StatCard: View {
    let item: StatItem

    var body: some View {
        VStack(alignment: .leading, spacing: AppSpacing.s3) {
            Text(item.label.uppercased())
                .font(AppText.eyebrow)
                .tracking(AppLetterSpacing.eyebrow)
                .foregroundColor(toneLabel)
                .lineLimit(2)

            Text(item.value)
                .font(AppText.statNumber)
                .foregroundColor(AppColors.textPrimary)
                .minimumScaleFactor(0.5)
                .lineLimit(1)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(AppSpacing.s4)
        .background(
            RoundedRectangle(cornerRadius: AppRadius.lg, style: .continuous)
                .fill(AppColors.cardSolid)
        )
        .overlay(
            RoundedRectangle(cornerRadius: AppRadius.lg, style: .continuous)
                .stroke(AppColors.borderDefault, lineWidth: 1)
        )
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
