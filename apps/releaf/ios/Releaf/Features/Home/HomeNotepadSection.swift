/*
 * HomeNotepadSection.swift
 * Compact "Notepad" card for the Home tab — header eyebrow with a
 * "Open →" affordance on top, and a 4-up stats bar below aggregating
 * all notepad pages (ENTRIES / PHOTOS / TODOS / TODAY). Mirrors the
 * visual treatment of [HomeShelvesSection] so the Home tab reads as
 * two parallel summary cards.
 */

import SwiftUI
import ReleafDesignSystem

public struct HomeNotepadSection: View {
    let totalEntries: Int
    let totalPhotos: Int
    let openTodos: Int
    let todayCount: Int
    let onOpenAll: () -> Void

    public init(
        totalEntries: Int,
        totalPhotos: Int,
        openTodos: Int,
        todayCount: Int,
        onOpenAll: @escaping () -> Void
    ) {
        self.totalEntries = totalEntries
        self.totalPhotos = totalPhotos
        self.openTodos = openTodos
        self.todayCount = todayCount
        self.onOpenAll = onOpenAll
    }

    public var body: some View {
        Button(action: onOpenAll) {
            VStack(alignment: .leading, spacing: AppSpacing.s4) {
                header
                StatsGrid(
                    entries:    totalEntries,
                    photos:     totalPhotos,
                    openTodos:  openTodos,
                    today:      todayCount
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
            Text("NOTEPAD · \(totalEntries)")
                .font(AppText.eyebrow)
                .tracking(AppLetterSpacing.eyebrow)
                .foregroundStyle(AppColors.themeGreenDeep)
            Spacer()
            Text("Open  →")
                .font(AppText.button)
                .foregroundStyle(AppColors.coral)
        }
    }
}

// MARK: - Stats grid

private struct StatsGrid: View {
    let entries: Int
    let photos: Int
    let openTodos: Int
    let today: Int

    var body: some View {
        HStack(spacing: AppSpacing.s2) {
            StatCard(label: "ENTRIES", value: "\(entries)",
                     background: AppColors.greenSoft,
                     border: AppColors.themeGreenBorderSoft)
            StatCard(label: "PHOTOS", value: "\(photos)",
                     background: AppColors.cardSolid,
                     border: AppColors.borderDefault)
            StatCard(label: "TODOS", value: "\(openTodos)",
                     valueColor: AppColors.coral,
                     background: AppColors.cardSolid,
                     border: AppColors.borderDefault)
            StatCard(label: "TODAY", value: "\(today)",
                     background: AppColors.coralSoft)
        }
    }
}

private struct StatCard: View {
    let label: String
    let value: String
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
            Text(value)
                .font(.system(size: 26, design: .serif))
                .foregroundStyle(valueColor)
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
