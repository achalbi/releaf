/*
 * Breadcrumbs.swift
 *
 * Hierarchical location indicator used in place of a plain back button.
 * Each segment other than the last is tappable (acts as up-navigation);
 * the terminal segment renders as a muted "current location" label.
 *
 * Mobile convention is to cap at 2-3 segments — longer chains don't
 * fit a top bar cleanly. Callers decide how much hierarchy to show.
 * Mirror of Android's `Breadcrumbs` Composable.
 */

import SwiftUI

public struct BreadcrumbSegment: Identifiable {
    /// Stable identity for SwiftUI's ForEach. Index-based ids are safe
    /// here because segments are mutated as a whole list rather than
    /// reordered.
    public let id: Int
    public let label: String
    public let onTap: (() -> Void)?

    public init(id: Int = 0, label: String, onTap: (() -> Void)? = nil) {
        self.id = id
        self.label = label
        self.onTap = onTap
    }
}

public struct Breadcrumbs: View {
    private let segments: [BreadcrumbSegment]

    /// Caller passes segments in parent → child order. The last
    /// segment's `onTap` should be nil (current location).
    public init(_ segments: [BreadcrumbSegment]) {
        // Re-id segments by position so `ForEach` keys are unique
        // without making the caller bookkeep indices.
        self.segments = segments.enumerated().map { idx, seg in
            BreadcrumbSegment(id: idx, label: seg.label, onTap: seg.onTap)
        }
    }

    public var body: some View {
        HStack(spacing: AppSpacing.s1) {
            ForEach(segments) { segment in
                if segment.id > 0 {
                    Text("›")
                        .font(AppText.button)
                        .foregroundStyle(AppColors.textTertiary)
                }
                segmentView(segment)
            }
        }
        .frame(minHeight: 44, alignment: .leading)
    }

    @ViewBuilder
    private func segmentView(_ segment: BreadcrumbSegment) -> some View {
        if let onTap = segment.onTap {
            Button(action: onTap) {
                Text(segment.label)
                    .font(AppText.button)
                    .foregroundStyle(AppColors.coral)
                    .lineLimit(1)
                    .truncationMode(.tail)
            }
            .buttonStyle(.plain)
        } else {
            Text(segment.label)
                .font(AppText.button)
                .foregroundStyle(AppColors.textSecondary)
                .lineLimit(1)
                .truncationMode(.tail)
        }
    }
}
