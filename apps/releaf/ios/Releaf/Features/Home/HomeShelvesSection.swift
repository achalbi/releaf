/*
 * HomeShelvesSection.swift
 * Compact "Notebooks" card for the Home tab — header eyebrow with a
 * "See all →" affordance on top, and a 4-up stats bar below
 * (STREAK / BOOKS / PAGES / OPEN TODOS). No filter chips, no shelf
 * hero cards on Home — the full library browsing lives on the
 * Notebooks tab.
 */

import SwiftUI
import ReleafDesignSystem
import ReleafData

public struct HomeShelvesSection: View {
    let notebooks: [Notebook]
    let openTodos: Int
    let onOpenAll: () -> Void

    public init(
        notebooks: [Notebook],
        openTodos: Int = 0,
        onOpenAll: @escaping () -> Void
    ) {
        self.notebooks = notebooks
        self.openTodos = openTodos
        self.onOpenAll = onOpenAll
    }

    public var body: some View {
        Button(action: onOpenAll) {
            VStack(alignment: .leading, spacing: AppSpacing.s4) {
                header
                StatsGrid(
                    streak:    computeStreak(notebooks: notebooks),
                    books:     notebooks.count,
                    pages:     notebooks.reduce(0) { $0 + $1.pageCount },
                    openTodos: openTodos
                )
            }
            .padding(AppSpacing.s5)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(
                RoundedRectangle(cornerRadius: AppRadius.lg, style: .continuous)
                    .fill(AppColors.cardSolid)
            )
            .overlay(
                RoundedRectangle(cornerRadius: AppRadius.lg, style: .continuous)
                    .stroke(AppColors.borderDefault, lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
    }

    private var header: some View {
        HStack(alignment: .firstTextBaseline) {
            Text("NOTEBOOKS · \(notebooks.count)")
                .font(AppText.eyebrow)
                .tracking(AppLetterSpacing.eyebrow)
                .foregroundStyle(AppColors.themeGreenDeep)
            Spacer()
            Text("See all  →")
                .font(AppText.button)
                .foregroundStyle(AppColors.coral)
        }
    }
}

// MARK: - Stats grid (ported from the Shelf tab)

private struct StatsGrid: View {
    let streak: Int
    let books: Int
    let pages: Int
    let openTodos: Int

    var body: some View {
        HStack(spacing: AppSpacing.s2) {
            StatCard(label: "STREAK", value: "\(streak)", suffix: "d",
                     background: AppColors.coralSoft)
            StatCard(label: "BOOKS", value: "\(books)",
                     background: AppColors.greenSoft,
                     border: AppColors.themeGreenBorderSoft)
            StatCard(label: "PAGES", value: "\(pages)",
                     background: AppColors.cardSolid,
                     border: AppColors.borderDefault)
            StatCard(label: "OPEN TODOS", value: "\(openTodos)",
                     valueColor: AppColors.coral,
                     background: AppColors.cardSolid,
                     border: AppColors.borderDefault)
        }
    }
}

private struct StatCard: View {
    let label: String
    let value: String
    var suffix: String? = nil
    var valueColor: Color = AppColors.textPrimary
    let background: Color
    var border: Color? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: AppSpacing.s2) {
            Text(label)
                .font(AppText.eyebrow)
                .tracking(AppLetterSpacing.eyebrow)
                .foregroundStyle(AppColors.themeGreenDeep)
                .lineLimit(1)
                .minimumScaleFactor(0.85)
            HStack(alignment: .lastTextBaseline, spacing: 3) {
                Text(value)
                    .font(.system(size: 26, design: .serif))
                    .foregroundStyle(valueColor)
                if let suffix {
                    Text(suffix)
                        .font(AppText.meta)
                        .foregroundStyle(AppColors.textSecondary)
                }
            }
        }
        .padding(AppSpacing.s3)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: AppRadius.md, style: .continuous)
                .fill(background)
        )
        .overlay(
            RoundedRectangle(cornerRadius: AppRadius.md, style: .continuous)
                .stroke(border ?? .clear, lineWidth: border == nil ? 0 : 1)
        )
    }
}

/// Best-effort streak — counts the number of distinct days in the
/// last 30 on which any notebook was updated. Mirrors the Android
/// Variant1 implementation so both platforms render the same number.
private func computeStreak(notebooks: [Notebook]) -> Int {
    guard !notebooks.isEmpty else { return 0 }
    let now = Date()
    let floor = Calendar.current.date(byAdding: .day, value: -30, to: now) ?? now
    let days = notebooks.compactMap { nb -> Date? in
        nb.updatedAt < floor ? nil : Calendar.current.startOfDay(for: nb.updatedAt)
    }
    return Set(days).count
}
