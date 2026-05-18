/*
 * LanguagesScreen.swift
 *
 * Onboarding step 4/4 — lets the user pick the languages they'll
 * speak in voice notes. Drives the transcription pipeline: with one
 * language picked we hand it to the recognizer directly; with
 * multiple, the recognizer falls back to Locale.current / first-
 * allowlist heuristics today.
 *
 * Defaults are seeded by `TranscriptionLanguages.defaultAllowlist`
 * (device locale + English) so a user in India sees Hindi + English
 * already checked. The user must keep at least one chip selected
 * before the Continue CTA enables — a zero-pick state breaks the
 * downstream pipeline.
 *
 * The picked set lives on `OnboardingState.selectedLanguageCodes`
 * during the flow and gets committed into
 * `profile_settings.transcription_languages` once SignIn succeeds
 * and we know the user id.
 *
 * Mirror of Android `LanguagesScreen.kt`.
 */

import SwiftUI

struct LanguagesScreen: View {
    @ObservedObject var state: OnboardingState
    let onContinue: () -> Void

    var body: some View {
        OnboardingScaffold(
            title:       "What will you",
            titleAccent: "speak?",
            subtitle:    "Pick the languages you'll speak in voice notes — we'll transcribe each recording in one of them. You can change this in Settings later.",
            ctaLabel:    "Continue",
            stepIndex:   3,
            totalSteps:  4,
            onContinue:  onContinue
        ) {
            chipGrid
        }
    }

    @ViewBuilder
    private var chipGrid: some View {
        OnboardingLanguageFlow(spacing: QuickInkSpacing.s2) {
            ForEach(TranscriptionLanguages.supported) { language in
                LanguageOnboardingChip(
                    language: language,
                    selected: state.selectedLanguageCodes.contains(language.code),
                    onTap:    { toggle(language) }
                )
            }
        }
        .padding(.horizontal, QuickInkSpacing.s5)
    }

    private func toggle(_ language: TranscriptionLanguage) {
        if state.selectedLanguageCodes.contains(language.code) {
            // Block the last-chip deselect — a zero-pick state would
            // break the LID pipeline downstream.
            guard state.selectedLanguageCodes.count > 1 else { return }
            state.selectedLanguageCodes.remove(language.code)
        } else {
            state.selectedLanguageCodes.insert(language.code)
        }
    }
}

private struct LanguageOnboardingChip: View {
    let language: TranscriptionLanguage
    let selected: Bool
    let onTap: () -> Void

    var body: some View {
        let bg: Color = selected ? QuickInkColors.accent : Color.white.opacity(0.85)
        let borderColor: Color = selected ? QuickInkColors.accent : QuickInkColors.accent.opacity(0.25)
        let textColor: Color = selected ? QuickInkColors.textOnAccent : QuickInkColors.ink
        let nativeColor: Color = selected
            ? QuickInkColors.textOnAccent.opacity(0.85)
            : QuickInkColors.inkSoft
        let shape = Capsule(style: .continuous)
        Button(action: onTap) {
            VStack(alignment: .center, spacing: 2) {
                Text(language.englishName)
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(textColor)
                if language.nativeName != language.englishName {
                    Text(language.nativeName)
                        .font(.system(size: 11))
                        .foregroundStyle(nativeColor)
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .background(bg)
            .clipShape(shape)
            .overlay(shape.stroke(borderColor, lineWidth: 1))
        }
        .buttonStyle(.plain)
    }
}

/// Minimal wrapping HStack — places each subview at its natural size
/// and wraps to a new row when the next item won't fit. Same shape as
/// Settings' `TranscriptionFlowLayout`; kept private to onboarding so
/// the two flows can drift independently if needed.
private struct OnboardingLanguageFlow: Layout {
    let spacing: CGFloat

    init(spacing: CGFloat = 8) {
        self.spacing = spacing
    }

    func sizeThatFits(
        proposal: ProposedViewSize,
        subviews: Subviews,
        cache: inout ()
    ) -> CGSize {
        let maxWidth = proposal.width ?? .infinity
        var rowWidth: CGFloat = 0
        var rowHeight: CGFloat = 0
        var totalHeight: CGFloat = 0
        for sub in subviews {
            let size = sub.sizeThatFits(.unspecified)
            if rowWidth + size.width > maxWidth, rowWidth > 0 {
                totalHeight += rowHeight + spacing
                rowWidth = size.width
                rowHeight = size.height
            } else {
                rowWidth += size.width + (rowWidth > 0 ? spacing : 0)
                rowHeight = max(rowHeight, size.height)
            }
        }
        if rowWidth > 0 || rowHeight > 0 {
            totalHeight += rowHeight
        }
        return CGSize(width: proposal.width ?? maxWidth, height: totalHeight)
    }

    func placeSubviews(
        in bounds: CGRect,
        proposal: ProposedViewSize,
        subviews: Subviews,
        cache: inout ()
    ) {
        var x = bounds.minX
        var y = bounds.minY
        var rowHeight: CGFloat = 0
        for sub in subviews {
            let size = sub.sizeThatFits(.unspecified)
            if x + size.width > bounds.maxX, x > bounds.minX {
                y += rowHeight + spacing
                x = bounds.minX
                rowHeight = 0
            }
            sub.place(
                at: CGPoint(x: x, y: y),
                proposal: ProposedViewSize(width: size.width, height: size.height)
            )
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
    }
}
