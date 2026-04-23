/*
 * AppButton.swift
 * Primary / secondary (outline) / text button variants.
 */

import SwiftUI

public enum AppButtonVariant {
    case primary
    case secondary
    case text
}

public struct AppButton: View {
    public let title: String
    public let variant: AppButtonVariant
    public let icon: Image?
    public let action: () -> Void

    public init(
        _ title: String,
        variant: AppButtonVariant = .primary,
        icon: Image? = nil,
        action: @escaping () -> Void
    ) {
        self.title = title
        self.variant = variant
        self.icon = icon
        self.action = action
    }

    public var body: some View {
        Button(action: action) {
            HStack(spacing: AppSpacing.s2) {
                if let icon = icon {
                    icon
                        .renderingMode(.template)
                        .resizable()
                        .frame(width: 16, height: 16)
                }
                Text(title)
                    .font(AppText.button)
            }
            .padding(.horizontal, AppSpacing.s6)
            .padding(.vertical, AppSpacing.s3)
            .frame(maxWidth: variant == .text ? nil : .infinity)
            .foregroundStyle(foreground)
            .background(background)
            .overlay(
                RoundedRectangle(cornerRadius: AppRadius.lg)
                    .stroke(borderColor, lineWidth: borderWidth)
            )
            .clipShape(RoundedRectangle(cornerRadius: AppRadius.lg))
        }
        .buttonStyle(.plain)
    }

    private var foreground: Color {
        switch variant {
        case .primary:   return AppColors.onPrimary
        case .secondary: return AppColors.textPrimary
        case .text:      return AppColors.coral
        }
    }

    private var background: Color {
        switch variant {
        case .primary:   return AppColors.actionPrimary
        case .secondary: return AppColors.cardSolid
        case .text:      return .clear
        }
    }

    private var borderColor: Color {
        switch variant {
        case .primary:   return .clear
        case .secondary: return AppColors.borderStrong
        case .text:      return .clear
        }
    }

    private var borderWidth: CGFloat {
        switch variant {
        case .secondary: return 1
        default:         return 0
        }
    }
}

#Preview {
    ZStack {
        AppColors.canvas.ignoresSafeArea()
        VStack(spacing: AppSpacing.s3) {
            AppButton("Continue") {}
            AppButton("Cancel", variant: .secondary) {}
            AppButton("Learn more", variant: .text) {}
        }
        .padding()
    }
}
