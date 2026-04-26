/*
 * SplashScreen.swift
 * Full-bleed launch splash. Matches the Releaf Branding Figma spec (live
 * DOM extraction, April 2026):
 *   - Linear gradient background, theme-primary → theme-deep, top-left
 *     to bottom-right.
 *   - 120pt leaf (canonical 24-viewport SVG, filled + cream outline +
 *     vein) with a subtle pulse.
 *   - "Releaf" wordmark, sans-serif 48pt medium, tracking -1.2pt in
 *     OnAccent cream.
 *   - "The notebook that grows back." tagline, sans-serif 18pt, OnAccent
 *     @ 90% opacity.
 *   - Three bouncing 8pt cream loading dots.
 */

import SwiftUI
import ReleafDesignSystem

public struct SplashScreen: View {
    @Environment(\.accentPalette) private var accent

    public init() {}

    public var body: some View {
        ZStack {
            LinearGradient(
                colors: [accent.primary, accent.deep],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            .ignoresSafeArea()

            VStack(spacing: 0) {
                ReleafLogo(
                    size: 120,
                    filled: true,
                    outlineColor: AppColors.onAccent,
                    fillGradientStart: accent.primary,
                    fillGradientEnd: accent.deep,
                    lineWidth: 2
                )

                Spacer().frame(height: AppSpacing.s8)

                Text("Releaf")
                    .font(.system(size: 48))
                    .tracking(-1.2)
                    .foregroundStyle(AppColors.onAccent)

                Spacer().frame(height: AppSpacing.s3)

                Text("The notebook that grows back.")
                    .font(.system(size: 18))
                    .foregroundStyle(AppColors.onAccent.opacity(0.9))
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, AppSpacing.s6)

                Spacer().frame(height: 64)

                LoadingDots()
            }
        }
    }
}

private struct LoadingDots: View {
    @State private var phase: Double = 0

    var body: some View {
        HStack(spacing: AppSpacing.s2) {
            ForEach(0..<3, id: \.self) { index in
                Circle()
                    .fill(AppColors.onAccent)
                    .frame(width: 8, height: 8)
                    .opacity(opacity(for: index))
            }
        }
        .onAppear {
            withAnimation(.linear(duration: 0.9).repeatForever(autoreverses: true)) {
                phase = 1
            }
        }
    }

    private func opacity(for index: Int) -> Double {
        let offset = Double(index) * 0.15
        let raw = (phase + offset).truncatingRemainder(dividingBy: 1)
        return 0.25 + 0.75 * abs(sin(raw * .pi))
    }
}

#Preview {
    SplashScreen()
}
