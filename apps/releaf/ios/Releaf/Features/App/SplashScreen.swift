/*
 * SplashScreen.swift
 * Full-bleed launch splash derived from the April 2026 Releaf marketing
 * material:
 *   - Deep green gradient background with faint dot-grid texture.
 *   - Cream-filled diagonal leaf with deep-green vein.
 *   - Lowercase serif "releaf" wordmark.
 *   - "WRITE. ERASE. REPEAT." loop tagline.
 */

import SwiftUI
import ReleafDesignSystem

public struct SplashScreen: View {
    private let brandGreenTop = Color(hex: 0x0F5A35)
    private let brandGreenBottom = Color(hex: 0x062F1D)

    public init() {}

    public var body: some View {
        ZStack {
            LinearGradient(
                colors: [brandGreenTop, Color(hex: 0x0B4328), brandGreenBottom],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            .ignoresSafeArea()

            SplashDotGrid()
                .ignoresSafeArea()

            VStack(spacing: 0) {
                ReleafLogoSolid(
                    size: 136,
                    leafColor: AppColors.onAccent,
                    veinColor: brandGreenBottom,
                    veinWidth: 4
                )

                Spacer().frame(height: 28)

                Text("releaf")
                    .font(.system(size: 64, weight: .regular, design: .serif))
                    .tracking(-1)
                    .foregroundStyle(AppColors.onAccent)

                Spacer().frame(height: AppSpacing.s2)

                Text("WRITE. ERASE. REPEAT.")
                    .font(.system(size: 18, weight: .bold, design: .default))
                    .tracking(2.2)
                    .foregroundStyle(AppColors.onAccent)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, AppSpacing.s6)

                Spacer().frame(height: AppSpacing.s3)

                Text("Reusable notebook + smart app companion.")
                    .font(.system(size: 17))
                    .foregroundStyle(AppColors.onAccent.opacity(0.82))
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, AppSpacing.s6)

                Spacer().frame(height: 72)

                LoadingDots()
            }
        }
    }
}

private struct SplashDotGrid: View {
    var body: some View {
        Canvas { context, size in
            let step: CGFloat = 18
            var path = Path()
            var y: CGFloat = 0
            while y <= size.height {
                var x: CGFloat = 0
                while x <= size.width {
                    path.addEllipse(in: CGRect(x: x, y: y, width: 2, height: 2))
                    x += step
                }
                y += step
            }
            context.fill(path, with: .color(AppColors.onAccent.opacity(0.07)))
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
