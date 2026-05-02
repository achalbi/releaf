/*
 * OnboardingScaffold.swift
 *
 * Shared layout shell for the 3 onboarding screens — hero glyph,
 * editorial heading, supporting copy, 3-dot page indicator, and
 * a CTA. Centralized so a brand pass redesigns the shell once.
 *
 * Mirrors the JSX mockup's onboarding-1 layout: tall hero area
 * with illustration centered ~40% from top, serif heading, ink-
 * soft body copy, soft accent dots, and a coral pill CTA at the
 * bottom safe-area inset.
 *
 * The illustration view is provided via a `@ViewBuilder` so each
 * step can substitute its own (notebook+scan-line, camera, cloud).
 */

import SwiftUI

struct OnboardingScaffold<Illustration: View>: View {

    let title: String
    let subtitle: String
    let ctaLabel: String
    let stepIndex: Int      // 0-based
    let totalSteps: Int
    let onContinue: () -> Void
    let illustration: () -> Illustration

    init(
        title: String,
        subtitle: String,
        ctaLabel: String,
        stepIndex: Int,
        totalSteps: Int = 3,
        onContinue: @escaping () -> Void,
        @ViewBuilder illustration: @escaping () -> Illustration
    ) {
        self.title = title
        self.subtitle = subtitle
        self.ctaLabel = ctaLabel
        self.stepIndex = stepIndex
        self.totalSteps = totalSteps
        self.onContinue = onContinue
        self.illustration = illustration
    }

    var body: some View {
        VStack(spacing: 0) {
            Spacer(minLength: 0)

            // Illustration area — fills ~45% of the vertical space.
            illustration()
                .frame(maxWidth: .infinity)
                .frame(height: 320)

            Spacer(minLength: QuickInkSpacing.s4)

            // Editorial copy — serif heading + ink-soft subtitle.
            VStack(spacing: QuickInkSpacing.s3) {
                Text(title)
                    .font(QuickInkText.display)
                    .foregroundStyle(QuickInkColors.ink)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, QuickInkSpacing.s5)

                Text(subtitle)
                    .font(QuickInkText.body)
                    .foregroundStyle(QuickInkColors.inkSoft)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, QuickInkSpacing.s7)
            }

            Spacer()

            // Page indicator dots — coral when active, accent-soft
            // when idle. Sized small so they read as polish, not
            // navigation. (Tappable variant for back-step is a
            // follow-up; today the flow is forward-only.)
            HStack(spacing: QuickInkSpacing.s2) {
                ForEach(0..<totalSteps, id: \.self) { i in
                    Circle()
                        .fill(i == stepIndex ? QuickInkColors.accent : QuickInkColors.border)
                        .frame(width: i == stepIndex ? 24 : 8, height: 8)
                        .animation(.easeInOut(duration: 0.18), value: stepIndex)
                }
            }
            .padding(.bottom, QuickInkSpacing.s5)

            // CTA — coral pill with white label.
            Button(action: onContinue) {
                Text(ctaLabel)
                    .font(QuickInkText.label)
                    .foregroundStyle(QuickInkColors.textOnAccent)
                    .quickInkCTA()
            }
            .padding(.horizontal, QuickInkSpacing.s5)
            .padding(.bottom, QuickInkSpacing.s7)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(QuickInkColors.bg.ignoresSafeArea())
    }
}

// MARK: - Hero illustrations

/// Onboarding step 1 — layered notebook + page with a coral scan
/// line and detection corners. Mirror of the JSX mockup's hero.
struct NotebookScanIllustration: View {
    /// Slow up-and-down sweep. Driven from the View's onAppear,
    /// loops for the lifetime of the onboarding screen.
    @State private var sweepOffset: CGFloat = -90

    var body: some View {
        ZStack {
            // Back notebook silhouette — slightly tilted, with a
            // visible spine + cover to evoke a Moleskine-shaped
            // reusable notebook.
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .fill(QuickInkColors.paper1)
                .overlay(
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .stroke(QuickInkColors.border, lineWidth: 1)
                )
                .frame(width: 220, height: 280)
                .rotationEffect(.degrees(-6))
                .offset(x: -16, y: 8)
                .shadow(color: QuickInkColors.ink.opacity(0.10), radius: 12, x: 0, y: 6)

            // Front "page" — the one being scanned. Lined paper.
            ZStack {
                QuickInkLinedPaper(tone: QuickInkColors.surface, lineSpacing: 14, lineOpacity: 0.10)
                // Coral margin line — the iconic mockup detail.
                HStack(spacing: 0) {
                    Rectangle()
                        .fill(QuickInkColors.accent.opacity(0.7))
                        .frame(width: 1.5)
                        .padding(.leading, 28)
                    Spacer()
                }
                .padding(.vertical, 12)
                // Faint title text on the page.
                VStack(alignment: .leading, spacing: 8) {
                    Text("Ideas")
                        .font(QuickInkFont.handwritten(22))
                        .foregroundStyle(QuickInkColors.ink.opacity(0.7))
                    Text("notebook synced.")
                        .font(QuickInkFont.handwritten(16))
                        .foregroundStyle(QuickInkColors.ink.opacity(0.5))
                }
                .padding(.leading, 40)
                .padding(.top, 24)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            }
            .frame(width: 200, height: 260)
            .background(QuickInkColors.surface)
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .stroke(QuickInkColors.border, lineWidth: 1)
            )
            .rotationEffect(.degrees(2))
            .offset(x: 20, y: -8)
            .shadow(color: QuickInkColors.ink.opacity(0.08), radius: 8, x: 0, y: 4)

            // Scan line — animated coral horizontal sweep.
            Rectangle()
                .fill(
                    LinearGradient(
                        colors: [
                            QuickInkColors.accent.opacity(0),
                            QuickInkColors.accent.opacity(0.6),
                            QuickInkColors.accent,
                            QuickInkColors.accent.opacity(0.6),
                            QuickInkColors.accent.opacity(0),
                        ],
                        startPoint: .leading,
                        endPoint: .trailing
                    )
                )
                .frame(width: 200, height: 2)
                .offset(x: 20, y: sweepOffset)
                .shadow(color: QuickInkColors.accent.opacity(0.5), radius: 6, x: 0, y: 0)

            // Detection corners — four coral L-shapes around the
            // scanning page bounds.
            ZStack {
                detectionCorner(rotation: 0)        // top-left
                    .offset(x: -80, y: -120)
                detectionCorner(rotation: 90)       // top-right
                    .offset(x:  120, y: -120)
                detectionCorner(rotation: 270)      // bottom-left
                    .offset(x: -80, y:  104)
                detectionCorner(rotation: 180)      // bottom-right
                    .offset(x:  120, y:  104)
            }
            .offset(x: 20, y: -8)
        }
        .onAppear {
            withAnimation(.easeInOut(duration: 1.6).repeatForever(autoreverses: true)) {
                sweepOffset = 110
            }
        }
    }

    @ViewBuilder
    private func detectionCorner(rotation: Double) -> some View {
        Path { path in
            path.move(to: CGPoint(x: 0, y: 14))
            path.addLine(to: CGPoint(x: 0, y: 0))
            path.addLine(to: CGPoint(x: 14, y: 0))
        }
        .stroke(QuickInkColors.accent, style: StrokeStyle(lineWidth: 2.5, lineCap: .round, lineJoin: .round))
        .frame(width: 14, height: 14)
        .rotationEffect(.degrees(rotation))
    }
}

/// Onboarding step 2 — camera viewfinder ring with a small page
/// glyph centered in it.
struct CameraIllustration: View {
    var body: some View {
        ZStack {
            // Outer dashed ring — viewfinder feel.
            Circle()
                .stroke(
                    QuickInkColors.accent.opacity(0.35),
                    style: StrokeStyle(lineWidth: 2, lineCap: .round, dash: [4, 8])
                )
                .frame(width: 240, height: 240)

            // Inner soft fill.
            Circle()
                .fill(QuickInkColors.accentSoft)
                .frame(width: 200, height: 200)

            // Page glyph in center — uses the same notebook page
            // pattern as step 1 so the visual language is coherent.
            ZStack {
                QuickInkLinedPaper(tone: QuickInkColors.surface, lineSpacing: 10, lineOpacity: 0.12)
                HStack(spacing: 0) {
                    Rectangle()
                        .fill(QuickInkColors.accent.opacity(0.7))
                        .frame(width: 1)
                        .padding(.leading, 14)
                    Spacer()
                }
                .padding(.vertical, 8)
            }
            .frame(width: 100, height: 130)
            .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 6, style: .continuous)
                    .stroke(QuickInkColors.border, lineWidth: 1)
            )
            .rotationEffect(.degrees(-4))

            // Aperture marker on the ring — small coral filled dot.
            Circle()
                .fill(QuickInkColors.accent)
                .frame(width: 10, height: 10)
                .offset(x: 0, y: -120)
        }
    }
}

/// Onboarding step 3 — cloud upload glyph paired with notebook
/// page. Drive backup metaphor.
struct DriveIllustration: View {
    var body: some View {
        ZStack {
            // Soft halo behind the cloud.
            Circle()
                .fill(QuickInkColors.accentSoft)
                .frame(width: 260, height: 260)

            // Cloud silhouette — built from overlapping circles +
            // a base rectangle for the flat bottom edge.
            cloudShape
                .fill(QuickInkColors.surface)
                .frame(width: 200, height: 130)
                .overlay(
                    cloudShape.stroke(QuickInkColors.border, lineWidth: 1.5)
                )
                .shadow(color: QuickInkColors.ink.opacity(0.08), radius: 6, x: 0, y: 3)
                .offset(y: -20)

            // Up-arrow inside the cloud.
            Image(systemName: "arrow.up")
                .font(.system(size: 36, weight: .medium))
                .foregroundStyle(QuickInkColors.accent)
                .offset(y: -22)

            // Small page glyph below the cloud, suggesting upload
            // direction.
            RoundedRectangle(cornerRadius: 4, style: .continuous)
                .fill(QuickInkColors.paper1)
                .frame(width: 50, height: 64)
                .overlay(
                    RoundedRectangle(cornerRadius: 4, style: .continuous)
                        .stroke(QuickInkColors.border, lineWidth: 1)
                )
                .rotationEffect(.degrees(6))
                .offset(x: -10, y: 80)

            RoundedRectangle(cornerRadius: 4, style: .continuous)
                .fill(QuickInkColors.paper2)
                .frame(width: 50, height: 64)
                .overlay(
                    RoundedRectangle(cornerRadius: 4, style: .continuous)
                        .stroke(QuickInkColors.border, lineWidth: 1)
                )
                .rotationEffect(.degrees(-4))
                .offset(x: 30, y: 84)
        }
    }

    /// Hand-built cloud shape — two top arcs + flat bottom.
    private var cloudShape: some Shape {
        Path { path in
            let w: CGFloat = 200
            let h: CGFloat = 130
            path.move(to: CGPoint(x: 30, y: h))
            path.addLine(to: CGPoint(x: w - 30, y: h))
            path.addArc(center: CGPoint(x: w - 30, y: h - 30), radius: 30, startAngle: .degrees(90), endAngle: .degrees(0), clockwise: true)
            path.addArc(center: CGPoint(x: w - 70, y: 50), radius: 50, startAngle: .degrees(0), endAngle: .degrees(-90), clockwise: true)
            path.addArc(center: CGPoint(x: 70, y: 70), radius: 40, startAngle: .degrees(-90), endAngle: .degrees(180), clockwise: true)
            path.addArc(center: CGPoint(x: 30, y: h - 30), radius: 30, startAngle: .degrees(180), endAngle: .degrees(90), clockwise: true)
            path.closeSubpath()
        }
    }
}

#if DEBUG
struct OnboardingScaffold_Previews: PreviewProvider {
    static var previews: some View {
        Group {
            QuickInkPhoneFrame {
                OnboardingScaffold(
                    title: "Your notebook,\ndigitised.",
                    subtitle: "Snap any page. Get auto-cropped, OCR'd, and synced — no fuss.",
                    ctaLabel: "Continue",
                    stepIndex: 0,
                    onContinue: {}
                ) { NotebookScanIllustration() }
            }
            .previewDisplayName("Step 1 — Welcome")

            QuickInkPhoneFrame {
                OnboardingScaffold(
                    title: "Camera access",
                    subtitle: "We use the camera to scan pages. We'll ask the first time you tap a scan.",
                    ctaLabel: "Continue",
                    stepIndex: 1,
                    onContinue: {}
                ) { CameraIllustration() }
            }
            .previewDisplayName("Step 2 — Permissions")

            QuickInkPhoneFrame {
                OnboardingScaffold(
                    title: "Sign in to back up",
                    subtitle: "Notes back up to your Drive so they follow you across devices.",
                    ctaLabel: "Continue with Google",
                    stepIndex: 2,
                    onContinue: {}
                ) { DriveIllustration() }
            }
            .previewDisplayName("Step 3 — Sign in")
        }
    }
}
#endif
