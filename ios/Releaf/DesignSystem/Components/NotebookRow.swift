/*
 * NotebookRow.swift
 *
 * List row for a notebook.
 *
 * Layout:
 *   [icon chip] Title  [Ch. 1]           [Active] ›
 *               Description
 *               2 chapters · 3 pages · Updated 10 days ago
 *
 * Ported from Inkcreate mobile DS.
 */

import SwiftUI

public struct NotebookRow: View {
    public let title: String
    public let chapterTag: String?
    public let description: String?
    public let meta: String
    public let isActive: Bool
    public let systemIcon: String
    public let onTap: () -> Void

    public init(
        title: String,
        chapterTag: String? = nil,
        description: String? = nil,
        meta: String,
        isActive: Bool = false,
        systemIcon: String = "book.closed",
        onTap: @escaping () -> Void = {}
    ) {
        self.title = title
        self.chapterTag = chapterTag
        self.description = description
        self.meta = meta
        self.isActive = isActive
        self.systemIcon = systemIcon
        self.onTap = onTap
    }

    public var body: some View {
        Button(action: onTap) {
            HStack(alignment: .top, spacing: AppSpacing.s3) {
                iconChip

                VStack(alignment: .leading, spacing: AppSpacing.s1) {
                    HStack(spacing: AppSpacing.s2) {
                        Text(title)
                            .font(AppText.button)
                            .foregroundColor(AppColors.textPrimary)
                            .lineLimit(1)
                        if let chapterTag {
                            chapterTagPill(chapterTag)
                        }
                        Spacer(minLength: AppSpacing.s2)
                        if isActive { activePill }
                        Image(systemName: "chevron.right")
                            .font(.system(size: 13))
                            .foregroundColor(AppColors.textTertiary)
                    }

                    if let description {
                        Text(description)
                            .font(AppText.meta)
                            .foregroundColor(AppColors.textSecondary)
                            .lineLimit(2)
                    }

                    Text(meta)
                        .font(AppText.meta)
                        .foregroundColor(AppColors.textTertiary)
                        .lineLimit(1)
                }
            }
            .padding(AppSpacing.s4)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(
                RoundedRectangle(cornerRadius: AppRadius.md, style: .continuous)
                    .fill(AppColors.cardSolid)
            )
            .overlay(
                RoundedRectangle(cornerRadius: AppRadius.md, style: .continuous)
                    .stroke(AppColors.borderDefault, lineWidth: 1)
            )
            .appShadow(.sm)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    // MARK: Pieces

    private var iconChip: some View {
        ZStack {
            RoundedRectangle(cornerRadius: AppRadius.sm, style: .continuous)
                .fill(AppColors.coralSoft)
            Image(systemName: systemIcon)
                .font(.system(size: 18))
                .foregroundColor(AppColors.coralDeep)
        }
        .frame(width: 40, height: 40)
    }

    private func chapterTagPill(_ text: String) -> some View {
        Text(text)
            .font(AppText.tag)
            .foregroundColor(AppColors.coralDeep)
            .padding(.horizontal, AppSpacing.s2)
            .padding(.vertical, 2)
            .background(Capsule().fill(AppColors.coralSoft))
    }

    private var activePill: some View {
        HStack(spacing: 4) {
            Circle().fill(AppColors.success).frame(width: 6, height: 6)
            Text("Active")
                .font(AppText.tag)
                .foregroundColor(AppColors.success)
        }
        .padding(.horizontal, AppSpacing.s2)
        .padding(.vertical, 2)
        .background(Capsule().fill(AppColors.successSoft))
    }
}

#if DEBUG
struct NotebookRow_Previews: PreviewProvider {
    static var previews: some View {
        VStack(spacing: AppSpacing.s2) {
            NotebookRow(
                title: "Observability",
                chapterTag: "Ch. 1",
                description: "Dashboards, alerts, and on-call runbooks.",
                meta: "2 chapters · 3 pages · Updated 10 days ago",
                isActive: true
            )
            NotebookRow(
                title: "Daily journal",
                meta: "1 chapter · 12 pages · Updated today"
            )
        }
        .padding(AppSpacing.s4)
        .background(AppColors.canvas)
        .frame(width: 390)
    }
}
#endif
