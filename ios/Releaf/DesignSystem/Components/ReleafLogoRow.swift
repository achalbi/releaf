/*
 * ReleafLogoRow.swift
 * Horizontal brand mark — leaf + "Releaf" serif wordmark. Matches the
 * four sizes in the Releaf Branding spec's "App Logo Variations" (xs /
 * sm / md / lg). Leaf color flows from the active accent palette; the
 * wordmark flows from text-primary.
 */

import SwiftUI

public enum ReleafLogoSize {
    case xs, sm, md, lg

    public var leaf: CGFloat {
        switch self {
        case .xs: return 14
        case .sm: return 20
        case .md: return 32
        case .lg: return 52
        }
    }

    public var stroke: CGFloat {
        switch self {
        case .xs: return 1.5
        case .sm: return 2
        case .md: return 2.5
        case .lg: return 3
        }
    }

    public var wordmarkSize: CGFloat {
        switch self {
        case .xs: return 13
        case .sm: return 17
        case .md: return 26
        case .lg: return 42
        }
    }

    public var gap: CGFloat {
        switch self {
        case .xs: return AppSpacing.s1
        case .sm, .md: return AppSpacing.s2
        case .lg: return AppSpacing.s3
        }
    }
}

public struct ReleafLogoRow: View {
    @Environment(\.accentPalette) private var accent

    public let size: ReleafLogoSize
    public let leafGradientStart: Color?
    public let leafGradientEnd: Color?
    public let wordmarkColor: Color

    public init(
        size: ReleafLogoSize = .md,
        leafGradientStart: Color? = nil,
        leafGradientEnd: Color? = nil,
        wordmarkColor: Color = AppColors.textPrimary
    ) {
        self.size = size
        self.leafGradientStart = leafGradientStart
        self.leafGradientEnd = leafGradientEnd
        self.wordmarkColor = wordmarkColor
    }

    public var body: some View {
        HStack(spacing: size.gap) {
            ReleafLogo(
                size: size.leaf,
                filled: true,
                fillGradientStart: leafGradientStart ?? accent.primary,
                fillGradientEnd: leafGradientEnd ?? accent.deep,
                lineWidth: size.stroke
            )
            Text("Releaf")
                .font(.system(size: size.wordmarkSize, weight: .medium, design: .serif))
                .foregroundStyle(wordmarkColor)
        }
    }
}

#Preview {
    VStack(alignment: .leading, spacing: AppSpacing.s4) {
        ReleafLogoRow(size: .xs)
        ReleafLogoRow(size: .sm)
        ReleafLogoRow(size: .md)
        ReleafLogoRow(size: .lg)
    }
    .padding()
    .background(AppColors.canvas)
}
