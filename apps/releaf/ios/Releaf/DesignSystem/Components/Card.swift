/*
 * Card.swift
 * Surface container — cardSolid fill, md radius, hairline border.
 */

import SwiftUI

public struct Card<Content: View>: View {
    public let padding: CGFloat
    public let radius: CGFloat
    public let content: () -> Content

    public init(
        padding: CGFloat = AppSpacing.s4,
        radius: CGFloat = AppRadius.md,
        @ViewBuilder content: @escaping () -> Content
    ) {
        self.padding = padding
        self.radius = radius
        self.content = content
    }

    public var body: some View {
        content()
            .padding(padding)
            .background(AppColors.cardSolid)
            .overlay(
                RoundedRectangle(cornerRadius: radius, style: .continuous)
                    .stroke(AppColors.borderDefault, lineWidth: 1)
            )
            .clipShape(RoundedRectangle(cornerRadius: radius, style: .continuous))
    }
}

#Preview {
    ZStack {
        AppColors.canvas.ignoresSafeArea()
        Card {
            VStack(alignment: .leading, spacing: AppSpacing.s2) {
                Text("RELEAF")
                    .font(AppText.eyebrow)
                    .tracking(AppLetterSpacing.eyebrow)
                    .foregroundStyle(AppColors.coral)
                Text("Card component")
                    .font(AppText.sectionTitle)
                    .foregroundStyle(AppColors.textPrimary)
                Text("Cream card with a hairline border over the canvas.")
                    .font(AppText.body)
                    .foregroundStyle(AppColors.textSecondary)
            }
        }
        .padding()
    }
}
