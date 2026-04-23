/*
 * OnboardingWizard.swift
 *
 * 10-step first-run tour. Presented as a bottom sheet on phone and a
 * centred card on iPad. Visual + copy parity with
 * docs/onboarding/source/_onboarding_wizard.html.erb — "Inkcreate"
 * renamed to "Releaf" in every string.
 *
 * Dismiss (skip / finish / CTA card tap) writes the current timestamp
 * to `@AppStorage("onboarding.completedAt")`; the auto-show check at
 * the root is `completedAt == 0`.
 */

import SwiftUI
import ReleafDesignSystem

public enum OnboardingCta {
    case notebook
    case notepad
}

public struct OnboardingWizard: View {
    @AppStorage("onboarding.completedAt") private var completedAt: Double = 0
    @State private var step: Int = 1

    let onDismiss: () -> Void
    let onCta: (OnboardingCta) -> Void

    private let totalSteps = 10

    public init(
        onDismiss: @escaping () -> Void,
        onCta: @escaping (OnboardingCta) -> Void
    ) {
        self.onDismiss = onDismiss
        self.onCta = onCta
    }

    public var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                HStack {
                    Spacer()
                    Button {
                        markCompleteAndDismiss()
                    } label: {
                        Text("Skip ✕")
                            .font(OnboardTokens.skip)
                            .foregroundStyle(OnboardTokens.textSubtle)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 4)
                    }
                    .buttonStyle(.plain)
                }

                Dots(current: step, total: totalSteps) { step = $0 }
                    .padding(.bottom, 24)

                stepContent

                actionRow
                    .padding(.top, 4)
            }
            .padding(.horizontal, 28)
            .padding(.top, 16)
            .padding(.bottom, 28)
            .frame(maxWidth: 440)
            .frame(maxWidth: .infinity)
        }
        .background(OnboardTokens.modalBg)
        .presentationDragIndicator(.hidden)
        .presentationDetents([.large])
        .ignoresSafeArea(.keyboard)
    }

    @ViewBuilder private var stepContent: some View {
        VStack(spacing: 0) {
            illustration
                .padding(.bottom, 20)

            if let badge = content.badge {
                Text(badge)
                    .font(OnboardTokens.badge)
                    .tracking(0.6)
                    .foregroundStyle(OnboardTokens.textSubtle)
                    .padding(.bottom, 8)
            }

            Text(content.headline)
                .font(OnboardTokens.headline)
                .foregroundStyle(OnboardTokens.textPrimary)
                .multilineTextAlignment(.center)
                .padding(.bottom, 10)

            Text(renderBody(content.body))
                .font(OnboardTokens.body)
                .foregroundStyle(OnboardTokens.textBody)
                .lineSpacing(6)
                .multilineTextAlignment(.center)
                .padding(.bottom, 24)

            if step == totalSteps {
                ctaCards
                    .padding(.bottom, 20)
            }
        }
    }

    @ViewBuilder private var illustration: some View {
        switch step {
        case 1:  WelcomeIllustration()
        case 2:  NotebooksIllustration()
        case 3:  NotepadIllustration()
        case 4:  PhotosIllustration()
        case 5:  VoiceIllustration()
        case 6:  TodoIllustration()
        case 7:  ScanIllustration()
        case 8:  MigrateIllustration()
        case 9:  BackupIllustration()
        case 10: DoneIllustration()
        default: EmptyView()
        }
    }

    @ViewBuilder private var actionRow: some View {
        if step == totalSteps {
            HStack(spacing: 10) {
                GhostButton(text: "← Back") { back() }
                PrimaryButton(text: "Let's go ✓") { markCompleteAndDismiss() }
            }
        } else {
            HStack(spacing: 10) {
                Spacer()
                if step > 1 {
                    GhostButton(text: "← Back") { back() }
                }
                PrimaryButton(text: step == 1 ? "Get started →" : "Next →") { advance() }
            }
        }
    }

    private var ctaCards: some View {
        VStack(spacing: 12) {
            CtaCard(icon: "📓", leadingLine: "Create a", trailingLine: "Notebook") {
                completedAt = Date().timeIntervalSince1970
                onCta(.notebook)
                onDismiss()
            }
            CtaCard(icon: "📅", leadingLine: "Open today's", trailingLine: "Notepad") {
                completedAt = Date().timeIntervalSince1970
                onCta(.notepad)
                onDismiss()
            }
        }
    }

    // MARK: - Actions

    private func advance() {
        if step < totalSteps {
            step += 1
        } else {
            markCompleteAndDismiss()
        }
    }

    private func back() {
        if step > 1 { step -= 1 }
    }

    private func markCompleteAndDismiss() {
        completedAt = Date().timeIntervalSince1970
        onDismiss()
    }

    // MARK: - Copy

    private var content: StepCopy { stepCopy[step - 1] }
}

// MARK: - Reusable chrome

private struct Dots: View {
    @Environment(\.accentPalette) private var accent
    let current: Int
    let total: Int
    let onTap: (Int) -> Void

    var body: some View {
        HStack(spacing: 8) {
            ForEach(1...total, id: \.self) { i in
                let active = i == current
                let done   = i < current
                let color: Color = {
                    if active { return accent.primary }
                    if done   { return accent.primary.opacity(0.45) }
                    return OnboardTokens.borderRest
                }()
                RoundedRectangle(cornerRadius: 4)
                    .fill(color)
                    .frame(width: active ? 24 : 8, height: 8)
                    .contentShape(Rectangle())
                    .onTapGesture { onTap(i) }
                    .animation(.easeInOut(duration: 0.2), value: current)
            }
        }
    }
}

private struct PrimaryButton: View {
    @Environment(\.accentPalette) private var accent
    let text: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(text)
                .font(OnboardTokens.button)
                .foregroundStyle(.white)
                .padding(.horizontal, 20)
                .padding(.vertical, 10)
                .background(accent.primary)
                .clipShape(RoundedRectangle(cornerRadius: 10))
        }
        .buttonStyle(.plain)
    }
}

private struct GhostButton: View {
    let text: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(text)
                .font(OnboardTokens.button)
                .foregroundStyle(OnboardTokens.textMuted)
                .padding(.horizontal, 20)
                .padding(.vertical, 10)
                .overlay(
                    RoundedRectangle(cornerRadius: 10)
                        .stroke(OnboardTokens.borderRest, lineWidth: 1.5)
                )
        }
        .buttonStyle(.plain)
    }
}

private struct CtaCard: View {
    @Environment(\.accentPalette) private var accent
    let icon: String
    let leadingLine: String
    let trailingLine: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 10) {
                Text(icon).font(.system(size: 22))
                VStack(alignment: .leading, spacing: 0) {
                    Text(leadingLine)
                        .font(OnboardTokens.ctaLabel)
                        .foregroundStyle(OnboardTokens.textPrimary)
                    Text(trailingLine)
                        .font(OnboardTokens.ctaLabelBold)
                        .foregroundStyle(OnboardTokens.textPrimary)
                }
                Spacer()
                Text("→")
                    .font(.system(size: 16))
                    .foregroundStyle(accent.primary)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 14)
            .frame(maxWidth: .infinity)
            .background(OnboardTokens.cardBg)
            .overlay(
                RoundedRectangle(cornerRadius: 14)
                    .stroke(OnboardTokens.borderRest, lineWidth: 2)
            )
            .clipShape(RoundedRectangle(cornerRadius: 14))
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Step copy

private struct StepCopy {
    let badge: String?
    let headline: String
    let body: String
}

private let stepCopy: [StepCopy] = [
    StepCopy(
        badge: nil,
        headline: "Welcome to Releaf",
        body: "Your workspace for projects, daily notes, voice recordings, scanned documents, and action items — all in one place."
    ),
    StepCopy(
        badge: "Step 1 of 9",
        headline: "Notebooks for projects",
        body: "Create a notebook for each project. Organise it into **chapters**, then add **pages** with rich notes, photos, and voice recordings — everything in context."
    ),
    StepCopy(
        badge: "Step 2 of 9",
        headline: "Notepad for daily capture",
        body: "The Notepad gives each day its own **page**. Jot quick thoughts, record voice notes, scan documents, or build a to-do list — without creating a notebook and chapter first."
    ),
    StepCopy(
        badge: "Step 3 of 9",
        headline: "Photos, your way",
        body: "Add photos directly to any page. Choose your preferred **capture quality** in Settings — balance between image clarity and storage size to suit your workflow."
    ),
    StepCopy(
        badge: "Step 4 of 9",
        headline: "Voice notes",
        body: "Record a voice note on any page with one tap. Play it back inline, or generate a **transcription on demand** to turn speech into searchable text."
    ),
    StepCopy(
        badge: "Step 5 of 9",
        headline: "To-do lists",
        body: "Add a checklist to any page. Set a **reminder** on an item to get notified at the right time, or **promote it to a Task** when it needs tracking across your workspace."
    ),
    StepCopy(
        badge: "Step 6 of 9",
        headline: "Scan → PDF in seconds",
        body: "Point your camera at any document. Releaf detects the edges, lets you crop and enhance it, then saves it as a PDF attached to your page."
    ),
    StepCopy(
        badge: "Step 7 of 9",
        headline: "Move pages into a Notebook",
        body: "When a Notepad page grows into something worth keeping, move it. Tap **Migrate to Notebook** on any Notepad page to place it into the right chapter — structure added, nothing lost."
    ),
    StepCopy(
        badge: "Step 8 of 9",
        headline: "Backed up to Google Drive",
        body: "Connect your Google Drive in Settings and Releaf will **back up your data automatically** — notes, voice recordings, photos, and scanned documents all kept safe in your own Drive."
    ),
    StepCopy(
        badge: "Step 9 of 9",
        headline: "You're all set!",
        body: "Start where it makes sense for you. Everything else will become clear as you go."
    ),
]

// Minimal `**bold**` → AttributedString renderer. The body copy uses
// that syntax verbatim and the web source emits a single <strong> per
// segment, so a literal toggle is enough without pulling in a markdown
// dep.
private func renderBody(_ source: String) -> AttributedString {
    var result = AttributedString()
    var i = source.startIndex
    var bold = false
    while i < source.endIndex {
        let next = source.index(after: i)
        if next < source.endIndex && source[i] == "*" && source[next] == "*" {
            bold.toggle()
            i = source.index(after: next)
            continue
        }
        var ch = AttributedString(String(source[i]))
        if bold {
            ch.font = Font.system(size: 15, weight: .semibold)
        }
        result.append(ch)
        i = source.index(after: i)
    }
    return result
}
