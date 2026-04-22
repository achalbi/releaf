/*
 * PagePreviewRow.swift
 *
 * Compact row for a single page preview. Appears inside chapter sections
 * in notebook detail and on the home screen's "Continue" list.
 *
 * Layout:
 *   [icon chip]  Title                     [thumbnail
 *                Description                with photo
 *                Updated · 2 hours ago       count badge]
 *
 * Pure presentation — no tap handling. Wrap the row in a
 * `NavigationLink(value:)` or a `Button` at the call site to make it
 * tappable. Inside a NavigationLink, set `.buttonStyle(.plain)` on the
 * link so the row's own visuals show through.
 *
 * Ported from Inkcreate mobile DS.
 */

import SwiftUI

public struct PagePreviewRow: View {
    public let title: String
    public let description: String?
    public let meta: String
    public let systemIcon: String
    public let thumbnailImage: Image?
    public let photoCount: Int

    public init(
        title: String,
        description: String? = nil,
        meta: String,
        systemIcon: String = "doc.text",
        thumbnailImage: Image? = nil,
        photoCount: Int = 0
    ) {
        self.title = title
        self.description = description
        self.meta = meta
        self.systemIcon = systemIcon
        self.thumbnailImage = thumbnailImage
        self.photoCount = photoCount
    }

    public var body: some View {
        HStack(alignment: .top, spacing: AppSpacing.s3) {
            iconChip

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(AppText.button)
                    .foregroundColor(AppColors.textPrimary)
                    .lineLimit(1)
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
            .frame(maxWidth: .infinity, alignment: .leading)

            thumbnail
        }
        .padding(AppSpacing.s3)
        .background(
            RoundedRectangle(cornerRadius: AppRadius.md, style: .continuous)
                .fill(AppColors.cardSolid)
        )
        .overlay(
            RoundedRectangle(cornerRadius: AppRadius.md, style: .continuous)
                .stroke(AppColors.borderDefault, lineWidth: 1)
        )
        .appShadow(.xs)
        .contentShape(Rectangle())
    }

    // MARK: Pieces

    private var iconChip: some View {
        ZStack {
            RoundedRectangle(cornerRadius: AppRadius.sm, style: .continuous)
                .fill(AppColors.coralSoft)
            Image(systemName: systemIcon)
                .font(.system(size: 16, weight: .semibold))
                .foregroundColor(AppColors.coralDeep)
        }
        .frame(width: 36, height: 36)
    }

    @ViewBuilder
    private var thumbnail: some View {
        if thumbnailImage != nil || photoCount > 0 {
            ZStack(alignment: .bottomTrailing) {
                RoundedRectangle(cornerRadius: AppRadius.sm, style: .continuous)
                    .fill(AppColors.subtle)
                if let thumbnailImage {
                    thumbnailImage
                        .resizable()
                        .aspectRatio(contentMode: .fill)
                        .clipShape(RoundedRectangle(cornerRadius: AppRadius.sm, style: .continuous))
                }
                if photoCount > 1 {
                    Text("+\(photoCount - 1)")
                        .font(AppText.tag)
                        .foregroundColor(AppColors.textOnAccent)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 2)
                        .background(Capsule().fill(AppColors.textPrimary.opacity(0.72)))
                        .padding(4)
                }
            }
            .frame(width: 52, height: 52)
        }
    }
}

#if DEBUG
struct PagePreviewRow_Previews: PreviewProvider {
    static var previews: some View {
        VStack(spacing: AppSpacing.s2) {
            PagePreviewRow(
                title: "Sunday, Apr 19 — Page 1",
                description: "Standup notes, follow-ups on the auth rework.",
                meta: "Updated 2 hours ago",
                photoCount: 3
            )
            PagePreviewRow(
                title: "Saturday grocery run",
                meta: "Updated yesterday"
            )
        }
        .padding(AppSpacing.s4)
        .background(AppColors.canvas)
        .frame(width: 390)
    }
}
#endif
