/*
 * HomeLibrarySection.swift
 * Unified "Your library" card for the Home tab — left column shows
 * notebook stats (STREAK / BOOKS / PAGES / OPEN TODOS), right column
 * shows notepad stats (ENTRIES / PHOTOS / TODOS / TODAY). Replaces
 * the earlier standalone HomeShelvesSection + HomeNotepadSection
 * stack with a single tappable card, matching prototype B.
 *
 * Tapping the card opens the Notebooks tab by default; a future
 * iteration can split the tap regions so the left column routes to
 * Notebooks and the right routes to Notepad.
 */

import SwiftUI
import ReleafDesignSystem
import ReleafData

public struct HomeLibrarySection: View {
    let notebooks: [Notebook]
    let totalNotepadEntries: Int
    let totalNotepadPhotos: Int
    let openNotepadTodos: Int
    let todayNotepadCount: Int
    let onOpenNotebooks: () -> Void
    let onOpenNotepad: () -> Void

    public init(
        notebooks: [Notebook],
        totalNotepadEntries: Int,
        totalNotepadPhotos: Int,
        openNotepadTodos: Int,
        todayNotepadCount: Int,
        onOpenNotebooks: @escaping () -> Void,
        onOpenNotepad: @escaping () -> Void
    ) {
        self.notebooks = notebooks
        self.totalNotepadEntries = totalNotepadEntries
        self.totalNotepadPhotos = totalNotepadPhotos
        self.openNotepadTodos = openNotepadTodos
        self.todayNotepadCount = todayNotepadCount
        self.onOpenNotebooks = onOpenNotebooks
        self.onOpenNotepad = onOpenNotepad
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: AppSpacing.s4) {
            header
            HStack(alignment: .top, spacing: 0) {
                notebookColumn
                Divider()
                    .frame(width: 1)
                    .overlay(AppColors.borderDefault)
                    .padding(.vertical, AppSpacing.s1)
                notepadColumn
            }
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

    private var header: some View {
        HStack(alignment: .firstTextBaseline) {
            Text("YOUR LIBRARY")
                .font(AppText.eyebrow)
                .tracking(AppLetterSpacing.eyebrow)
                .foregroundStyle(AppColors.themeGreenDeep)
            Spacer()
            Text("→")
                .font(AppText.button)
                .foregroundStyle(AppColors.coral)
        }
    }

    private var notebookColumn: some View {
        Button(action: onOpenNotebooks) {
            VStack(alignment: .leading, spacing: AppSpacing.s3) {
                Text("NOTEBOOKS · \(notebooks.count)")
                    .font(AppText.eyebrow)
                    .tracking(AppLetterSpacing.eyebrow)
                    .foregroundStyle(AppColors.textSecondary)
                StatRow(label: "STREAK", value: "\(computeStreak(notebooks: notebooks))",
                        suffix: "d")
                StatRow(label: "BOOKS", value: "\(notebooks.count)")
                StatRow(label: "PAGES",
                        value: "\(notebooks.reduce(0) { $0 + $1.pageCount })")
                StatRow(label: "OPEN TODOS", value: "0", valueColor: AppColors.coral)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.trailing, AppSpacing.s3)
        }
        .buttonStyle(.plain)
    }

    private var notepadColumn: some View {
        Button(action: onOpenNotepad) {
            VStack(alignment: .leading, spacing: AppSpacing.s3) {
                Text("NOTEPAD · \(totalNotepadEntries)")
                    .font(AppText.eyebrow)
                    .tracking(AppLetterSpacing.eyebrow)
                    .foregroundStyle(AppColors.textSecondary)
                StatRow(label: "ENTRIES", value: "\(totalNotepadEntries)")
                StatRow(label: "PHOTOS", value: "\(totalNotepadPhotos)")
                StatRow(label: "TODOS", value: "\(openNotepadTodos)",
                        valueColor: AppColors.coral)
                StatRow(label: "TODAY", value: "\(todayNotepadCount)")
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.leading, AppSpacing.s3)
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Stat row

private struct StatRow: View {
    let label: String
    let value: String
    var suffix: String? = nil
    var valueColor: Color = AppColors.textPrimary

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label)
                .font(AppText.eyebrow)
                .tracking(AppLetterSpacing.eyebrow)
                .foregroundStyle(AppColors.themeGreenDeep)
                .lineLimit(1)
                .minimumScaleFactor(0.85)
            HStack(alignment: .lastTextBaseline, spacing: 3) {
                Text(value)
                    .font(.system(size: 20, design: .serif))
                    .foregroundStyle(valueColor)
                if let suffix {
                    Text(suffix)
                        .font(AppText.tag)
                        .foregroundStyle(AppColors.textSecondary)
                }
            }
        }
    }
}

private func computeStreak(notebooks: [Notebook]) -> Int {
    guard !notebooks.isEmpty else { return 0 }
    let now = Date()
    let floor = Calendar.current.date(byAdding: .day, value: -30, to: now) ?? now
    let days = notebooks.compactMap { nb -> Date? in
        nb.updatedAt < floor ? nil : Calendar.current.startOfDay(for: nb.updatedAt)
    }
    return Set(days).count
}
