/*
 * StoriesShelfScreen.swift
 *
 * The Stories tab — §7.1 of design/stories-mockup-v3.html.
 *
 *   ┌─────────────────────────────────────────┐
 *   │  Stories                                │
 *   │  ⌕  Search your stories                 │
 *   │                                         │
 *   │  ┌─ SUGGESTED · TODAY ────────────┐    │
 *   │  │ ▓▓▓ ▓ ▓                         │    │
 *   │  │ Tokyo, May 4–7                  │    │
 *   │  │ 12 photos and 3 receipts…       │    │
 *   │  │ Not interested      Open prev → │    │
 *   │  └─────────────────────────────────┘    │
 *   │                                         │
 *   │  Your stories                           │
 *   │  ┌────────────────────────────────┐    │
 *   │  │ ▓▓│ Mira's first month       › │    │  ← story card
 *   │  │   │ 14 items · Apr 2026        │    │
 *   │  │   │ [Public link]              │    │
 *   │  └────────────────────────────────┘    │
 *   │                                  ╭───╮ │
 *   │                                  │ + │ │  ← FAB
 *   │                                  ╰───╯ │
 *   └─────────────────────────────────────────┘
 *
 * Phase 1 surface — reads from `StoriesShelfViewModel`. Suggestion
 * hero card is shown only when a real suggestion exists (Phase 5
 * fills the cache); when the shelf is empty AND there is no
 * suggestion we render a calm placeholder hero introducing the
 * feature. Creation flow + editor land in Phase 2.
 *
 * Mirror of Android `StoriesShelfScreen.kt`.
 */

import SwiftUI

struct StoriesShelfScreen: View {

    let userId: String
    /// Phase 2 navigation hook — opens the story in the editor.
    /// Called both when the user taps a shelf card (passes its id)
    /// AND when the "+" FAB lands after creating a fresh draft.
    var onOpenStory: (String) -> Void = { _ in }
    /// Phase 5 — push the suggestion preview screen.
    var onOpenSuggestionPreview: (String) -> Void = { _ in }

    @StateObject private var vm: StoriesShelfViewModel
    @State private var searchDraft: String = ""

    init(
        userId: String,
        onOpenStory: @escaping (String) -> Void = { _ in },
        onOpenSuggestionPreview: @escaping (String) -> Void = { _ in }
    ) {
        self.userId = userId
        self.onOpenStory = onOpenStory
        self.onOpenSuggestionPreview = onOpenSuggestionPreview
        _vm = StateObject(wrappedValue: StoriesShelfViewModel(userId: userId))
    }

    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            QuickInkColors.bg.ignoresSafeArea()

            ScrollView(showsIndicators: false) {
                VStack(alignment: .leading, spacing: QuickInkSpacing.s4) {
                    Text("Stories")
                        .font(QuickInkText.display)
                        .foregroundStyle(QuickInkColors.ink)
                        .padding(.top, QuickInkSpacing.s2)

                    searchBar

                    if let suggestion = vm.suggestion {
                        suggestionHero(suggestion: suggestion)
                    } else if vm.rows.isEmpty {
                        emptyStateHero
                    }

                    if !vm.rows.isEmpty {
                        sectionHeader("Your stories")
                        VStack(spacing: QuickInkSpacing.s2 + 2) {
                            ForEach(vm.rows, id: \.story.id) { row in
                                Button(action: { onOpenStory(row.story.id) }) {
                                    StoryShelfCard(row: row)
                                }
                                .buttonStyle(.plain)
                            }
                        }
                    }

                    Color.clear.frame(height: QuickInkBottomNavReservedHeight)
                }
                .padding(.horizontal, QuickInkSpacing.s4)
            }

            fab
        }
        .task {
            vm.start()
        }
    }

    // MARK: - Subviews

    private var searchBar: some View {
        HStack(spacing: QuickInkSpacing.s2) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 15))
                .foregroundStyle(QuickInkColors.inkSoft)
            TextField("Search your stories", text: $searchDraft)
                .font(.system(size: 14))
                .foregroundStyle(QuickInkColors.ink)
                .textInputAutocapitalization(.never)
                .disableAutocorrection(true)
        }
        .padding(.horizontal, QuickInkSpacing.s3)
        .padding(.vertical, QuickInkSpacing.s3 - 2)
        .background(
            RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous)
                .fill(QuickInkColors.surface)
        )
        .overlay(
            RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous)
                .strokeBorder(QuickInkColors.border, lineWidth: 1)
        )
    }

    private func suggestionHero(suggestion: StorySuggestion) -> some View {
        VStack(alignment: .leading, spacing: QuickInkSpacing.s2) {
            Text("SUGGESTED · TODAY")
                .font(.system(size: 10, weight: .medium))
                .tracking(1.5)
                .foregroundStyle(QuickInkColors.accentDeep)

            previewStrip

            Text(suggestion.reason)
                .font(QuickInkText.editorial)
                .foregroundStyle(QuickInkColors.ink)
                .lineLimit(2)

            HStack {
                Button("Not interested") {
                    vm.dismissSuggestion()
                }
                .font(QuickInkText.bodyItalic)
                .foregroundStyle(QuickInkColors.muted)
                .buttonStyle(.plain)
                Spacer()
                Button("Open preview →") {
                    onOpenSuggestionPreview(suggestion.id)
                }
                .font(.system(size: 12, weight: .medium))
                .foregroundStyle(QuickInkColors.accent)
                .buttonStyle(.plain)
            }
            .padding(.top, QuickInkSpacing.s1)
            .overlay(alignment: .top) {
                Rectangle()
                    .fill(QuickInkColors.border)
                    .frame(height: 0.5)
            }
        }
        .padding(QuickInkSpacing.s3)
        .background(
            RoundedRectangle(cornerRadius: QuickInkRadius.lg, style: .continuous)
                .fill(QuickInkColors.surface)
        )
        .overlay(
            RoundedRectangle(cornerRadius: QuickInkRadius.lg, style: .continuous)
                .strokeBorder(QuickInkColors.border, lineWidth: 1)
        )
    }

    /// Calm placeholder shown when the shelf has no stories AND the
    /// suggestion engine hasn't produced anything yet. Introduces the
    /// feature without faking a suggestion.
    private var emptyStateHero: some View {
        VStack(alignment: .leading, spacing: QuickInkSpacing.s2) {
            Text("YOUR STORIES")
                .font(.system(size: 10, weight: .medium))
                .tracking(1.5)
                .foregroundStyle(QuickInkColors.accentDeep)

            Text("Curate a moment.")
                .font(QuickInkText.editorial)
                .foregroundStyle(QuickInkColors.ink)

            Text("Pick a few scans, photos, or notes and assemble them into a story you can share.")
                .font(QuickInkText.bodyItalic)
                .foregroundStyle(QuickInkColors.inkSoft)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(QuickInkSpacing.s4)
        .background(
            RoundedRectangle(cornerRadius: QuickInkRadius.lg, style: .continuous)
                .fill(QuickInkColors.surface)
        )
        .overlay(
            RoundedRectangle(cornerRadius: QuickInkRadius.lg, style: .continuous)
                .strokeBorder(QuickInkColors.border, lineWidth: 1)
        )
    }

    /// Mockup §7.1 preview-strip — a 2:1:1 grid of paper / paper / coral
    /// thumbnails standing in for "the first three items of the
    /// suggested cluster". Phase 5 will render real captures here.
    private var previewStrip: some View {
        HStack(spacing: 4) {
            RoundedRectangle(cornerRadius: 4)
                .fill(QuickInkColors.paper1)
                .frame(maxWidth: .infinity)
            RoundedRectangle(cornerRadius: 4)
                .fill(QuickInkColors.paper3)
                .frame(maxWidth: .infinity)
            RoundedRectangle(cornerRadius: 4)
                .fill(QuickInkColors.accent.opacity(0.55))
                .frame(maxWidth: .infinity)
        }
        .frame(height: 70)
        .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
    }

    private func sectionHeader(_ text: String) -> some View {
        Text(text)
            .font(QuickInkText.editorial)
            .foregroundStyle(QuickInkColors.ink)
            .padding(.top, QuickInkSpacing.s1)
    }

    /// Lifted coral disc, bottom-right. Phase 1 stubs the create
    /// action; the real flow comes in Phase 2.
    private var fab: some View {
        Button(action: {
            Task {
                if let id = await vm.createDraft() {
                    onOpenStory(id)
                }
            }
        }) {
            ZStack {
                Circle()
                    .fill(LinearGradient(
                        colors: [QuickInkColors.accent, QuickInkColors.accentDeep],
                        startPoint: .top,
                        endPoint: .bottom
                    ))
                    .frame(width: 56, height: 56)
                    .shadow(color: QuickInkColors.accent.opacity(0.35), radius: 12, x: 0, y: 6)
                Image(systemName: "plus")
                    .font(.system(size: 24, weight: .semibold))
                    .foregroundStyle(QuickInkColors.textOnAccent)
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Create a new story")
        // Sit clear of the bottom-nav FAB centre while still reading
        // as "on the same level" — matches the mockup's bottom-right
        // anchor (~24 from edges, lifted above the nav).
        .padding(.trailing, QuickInkSpacing.s4)
        .padding(.bottom, QuickInkBottomNavReservedHeight - 32)
    }
}

// MARK: - Story shelf card

private struct StoryShelfCard: View {
    let row: StoryShelfRow

    var body: some View {
        HStack(spacing: QuickInkSpacing.s3) {
            cover
            VStack(alignment: .leading, spacing: 2) {
                Text(row.story.title)
                    .font(QuickInkText.editorial)
                    .foregroundStyle(QuickInkColors.ink)
                    .lineLimit(1)
                Text(metaLine)
                    .font(.system(size: 11))
                    .foregroundStyle(QuickInkColors.inkSoft)
                sharePill
            }
            Spacer(minLength: 0)
        }
        .padding(QuickInkSpacing.s2 + 2)
        .background(
            RoundedRectangle(cornerRadius: QuickInkRadius.md + 2, style: .continuous)
                .fill(QuickInkColors.surface)
        )
        .overlay(
            RoundedRectangle(cornerRadius: QuickInkRadius.md + 2, style: .continuous)
                .strokeBorder(QuickInkColors.border, lineWidth: 1)
        )
    }

    // MARK: cover

    private var cover: some View {
        RoundedRectangle(cornerRadius: 10, style: .continuous)
            .fill(coverFill)
            .frame(width: 56, height: 56)
            .overlay(alignment: .bottomLeading) {
                Text(coverCaption)
                    .font(QuickInkFont.serif(7.5, weight: .medium))
                    .foregroundStyle(coverCaptionTint)
                    .padding(.horizontal, 5)
                    .padding(.bottom, 4)
            }
    }

    /// Phase 1 cover palette — Story.coverStyle picks the colour
    /// (photo + no cover_item_id → paper-warm; gradient → coral wash;
    /// typographic → tan paper). Phase 3's dominant-colour cover will
    /// supersede this with a real hero colour.
    private var coverFill: Color {
        switch row.story.coverStyle {
        case Story.CoverStyle.gradient.rawValue:    return QuickInkColors.accent.opacity(0.7)
        case Story.CoverStyle.typographic.rawValue: return QuickInkColors.paper3
        default:                                    return QuickInkColors.paper1
        }
    }

    private var coverCaptionTint: Color {
        row.story.coverStyle == Story.CoverStyle.gradient.rawValue
            ? QuickInkColors.textOnAccent
            : QuickInkColors.ink
    }

    /// Two-or-three-letter handle pulled from the title (first word).
    /// Mirrors the mockup's "Mira" / "Lisbon" / "Reno" cover labels.
    private var coverCaption: String {
        let first = row.story.title.split(separator: " ").first.map(String.init) ?? row.story.title
        return String(first.prefix(8))
    }

    // MARK: meta + pill

    /// "14 items · Apr 2026" — item count + month-year of the latest
    /// item, or "no items yet" when the shelf row hasn't accumulated
    /// any children yet.
    private var metaLine: String {
        let countPart: String = {
            switch row.itemCount {
            case 0:  return "no items yet"
            case 1:  return "1 item"
            default: return "\(row.itemCount) items"
            }
        }()
        guard let isoLatest = row.latestItemAt,
              let monthYear = Self.monthYear(fromIso: isoLatest)
        else { return countPart }
        return "\(countPart) · \(monthYear)"
    }

    @ViewBuilder
    private var sharePill: some View {
        switch row.story.shareMode {
        case Story.ShareMode.publicLink.rawValue:
            pillView(text: "Public link", tone: .live)
        case Story.ShareMode.exported.rawValue,
             Story.ShareMode.inApp.rawValue:
            pillView(text: "Shared", tone: .live)
        case Story.ShareMode.private.rawValue
            where row.story.status == Story.Status.draft.rawValue:
            pillView(text: "Draft", tone: .neutral)
        default:
            EmptyView()
        }
    }

    private enum PillTone { case live, neutral }

    private func pillView(text: String, tone: PillTone) -> some View {
        let bg = tone == .live ? QuickInkColors.accentSoft : QuickInkColors.borderSoft
        let fg = tone == .live ? QuickInkColors.accent     : QuickInkColors.inkSoft
        return Text(text)
            .font(.system(size: 10, weight: .medium))
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .background(bg, in: Capsule())
            .foregroundStyle(fg)
            .padding(.top, 4)
    }

    // MARK: - Helpers

    private static let isoParser: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()

    private static let monthYearFmt: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "MMM yyyy"
        return f
    }()

    private static func monthYear(fromIso iso: String) -> String? {
        // The schema stores fractional-seconds ISO-8601; older
        // GRDB defaults (and Drive payloads from earlier clients)
        // can land without the fraction. Parse the strict form
        // first, fall back to the non-fractional formatter so
        // either shape renders.
        if let d = isoParser.date(from: iso) { return monthYearFmt.string(from: d) }
        let fallback = ISO8601DateFormatter()
        fallback.formatOptions = [.withInternetDateTime]
        if let d = fallback.date(from: iso) { return monthYearFmt.string(from: d) }
        return nil
    }
}

#if DEBUG
struct StoriesShelfScreen_Previews: PreviewProvider {
    static var previews: some View {
        StoriesShelfScreen(userId: "preview-user")
    }
}
#endif
