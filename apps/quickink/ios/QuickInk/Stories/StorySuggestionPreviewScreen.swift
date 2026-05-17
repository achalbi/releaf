/*
 * StorySuggestionPreviewScreen.swift
 *
 * Stories Phase 5 — the §7.2 mockup. The shelf's "Open preview →"
 * link pushes here with the suggestion id; we re-run the engine
 * deterministically to recover the cluster, then render:
 *
 *   ← Stories
 *   Captures, May 4–7                  ← serif title
 *   ┌ reason box ────────────────────┐
 *   │ 10 scans and 3 photos, May 4–7 │
 *   └────────────────────────────────┘
 *   ┌── WHAT IT'LL LOOK LIKE ────────┐
 *   │ ┌── sample cover ────────────┐ │
 *   │ │ MAY 2026                   │ │
 *   │ │ Captures, May 4–7          │ │
 *   │ └────────────────────────────┘ │
 *   │ ┌── first page card ─────────┐ │
 *   │ │ [photo]                    │ │
 *   │ │ Caption from first item    │ │
 *   │ └────────────────────────────┘ │
 *   │ 13 items · ~3 pages   Swap cover│
 *   └────────────────────────────────┘
 *   What's inside, in order:
 *   [strip of thumbs]
 *   [ Edit first ]  [ Make story ]
 *
 * Mirror of Android `StorySuggestionPreviewScreen.kt`.
 */

import GRDB
import SwiftUI

struct StorySuggestionPreviewScreen: View {

    let suggestionId: String
    let userId: String
    var onBack: () -> Void
    /// Called when "Make story" lands — pushes the editor at the
    /// returned story id.
    var onOpenStory: (String) -> Void

    @State private var suggestion: StorySuggestion? = nil
    @State private var loadFailed: Bool = false
    @State private var creating: Bool = false
    @State private var toast: String? = nil
    /// `(previewUri, caption)` for the cluster's first capture.
    /// Loaded once after the engine returns; the first-page card
    /// renders this directly (or falls back to a cream placeholder
    /// + canned italic when the capture has no preview on disk).
    @State private var firstPreview: (uri: String?, caption: String?)? = nil

    private let repository = StoryRepository()

    var body: some View {
        ZStack(alignment: .top) {
            QuickInkColors.bg.ignoresSafeArea()
            ScrollView(showsIndicators: false) {
                VStack(alignment: .leading, spacing: QuickInkSpacing.s3) {
                    backRow
                    if let suggestion = suggestion {
                        Text(deriveTitle(suggestion))
                            .font(QuickInkFont.serif(22, weight: .medium))
                            .foregroundStyle(QuickInkColors.ink)
                        reasonBox(suggestion.reason)
                        previewHero(suggestion: suggestion)
                        whatsInside(suggestion: suggestion)
                    } else if loadFailed {
                        Text("That suggestion is no longer available.")
                            .font(QuickInkText.bodyItalic)
                            .foregroundStyle(QuickInkColors.inkSoft)
                            .padding(.top, QuickInkSpacing.s5)
                    } else {
                        ProgressView()
                            .padding(.top, QuickInkSpacing.s5)
                    }
                    Color.clear.frame(height: 96) // breathing room above CTAs
                }
                .padding(.horizontal, QuickInkSpacing.s4)
                .padding(.top, QuickInkSpacing.s2)
            }
            VStack {
                Spacer()
                ctaRow
            }
            if let toast = toast {
                Text(toast)
                    .font(QuickInkText.bodyItalic)
                    .foregroundStyle(QuickInkColors.inkSoft)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 5)
                    .background(Capsule().fill(QuickInkColors.surface))
                    .overlay(Capsule().strokeBorder(QuickInkColors.border, lineWidth: 0.5))
                    .padding(.top, 60)
                    .transition(.move(edge: .top).combined(with: .opacity))
            }
        }
        .task { await load() }
    }

    // MARK: - Pieces

    private var backRow: some View {
        Button(action: onBack) {
            HStack(spacing: 4) {
                Image(systemName: "chevron.left")
                    .font(.system(size: 14, weight: .medium))
                Text("Stories")
                    .font(.system(size: 13, weight: .medium))
            }
            .foregroundStyle(QuickInkColors.inkSoft)
            .padding(.vertical, QuickInkSpacing.s1)
        }
        .buttonStyle(.plain)
    }

    private func reasonBox(_ reason: String) -> some View {
        Text(reason)
            .font(QuickInkFont.serif(12, weight: .regular, italic: true))
            .foregroundStyle(QuickInkColors.inkSoft)
            .lineSpacing(2)
            .padding(11)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(QuickInkColors.borderSoft)
            .clipShape(RoundedRectangle(cornerRadius: 10))
    }

    private func previewHero(suggestion: StorySuggestion) -> some View {
        let title = deriveTitle(suggestion)
        return VStack(alignment: .leading, spacing: QuickInkSpacing.s2 - 2) {
            Text("WHAT IT'LL LOOK LIKE")
                .font(.system(size: 10, weight: .medium))
                .tracking(1.5)
                .foregroundStyle(QuickInkColors.accentDeep)

            // Sample cover (paper-warm gradient + title)
            ZStack(alignment: .bottomLeading) {
                LinearGradient(
                    colors: [QuickInkColors.paper1, QuickInkColors.paper3],
                    startPoint: .topLeading,
                    endPoint:   .bottomTrailing
                )
                VStack(alignment: .leading, spacing: 2) {
                    Text(monthYearStamp(suggestion))
                        .font(QuickInkText.bodyItalic)
                        .tracking(1.5)
                        .foregroundStyle(QuickInkColors.inkSoft)
                        .font(.system(size: 9))
                    Text(title)
                        .font(QuickInkFont.serif(20, weight: .medium))
                        .foregroundStyle(QuickInkColors.ink)
                    Text("a curated story-in-the-making")
                        .font(QuickInkFont.handwritten(14))
                        .foregroundStyle(QuickInkColors.inkSoft)
                }
                .padding(14)
            }
            .frame(height: 110)
            .clipShape(RoundedRectangle(cornerRadius: 10))

            // First-page card — renders the cluster's first capture
            // via the same `StoryCapturePreviewImage` the reader
            // uses. Falls back to a cream placeholder + canned
            // italic when the capture has no preview on disk yet.
            VStack(alignment: .leading, spacing: 5) {
                StoryCapturePreviewImage(
                    uri:          firstPreview?.uri,
                    height:       48,
                    cornerRadius: 4
                )
                Text(firstPreview?.caption ?? "First capture, jet-lagged and curious.")
                    .font(QuickInkFont.serif(11, weight: .regular, italic: true))
                    .foregroundStyle(QuickInkColors.inkSoft)
                    .lineLimit(2)
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 8)
            .background(QuickInkColors.bg)
            .clipShape(RoundedRectangle(cornerRadius: 8))

            // Controls row
            HStack {
                Text("\(suggestion.candidateRefs.count) items · ~\(estimatedPages(suggestion)) pages")
                    .font(QuickInkText.bodyItalic)
                    .foregroundStyle(QuickInkColors.inkSoft)
                Spacer()
                Button("Swap cover") { flashToast("Swap cover ships in Phase 5.1.") }
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(QuickInkColors.accent)
                    .buttonStyle(.plain)
            }
            .font(.system(size: 11))
            .padding(.top, QuickInkSpacing.s2)
            .overlay(alignment: .top) {
                Rectangle().fill(QuickInkColors.border).frame(height: 0.5)
            }
        }
        .padding(QuickInkSpacing.s3 + 2)
        .background(QuickInkColors.surface)
        .overlay(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .strokeBorder(QuickInkColors.border, lineWidth: 1)
        )
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }

    private func whatsInside(suggestion: StorySuggestion) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("What's inside, in order:")
                .font(QuickInkText.bodyItalic)
                .foregroundStyle(QuickInkColors.inkSoft)

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 4) {
                    ForEach(suggestion.candidateRefs.indices, id: \.self) { idx in
                        thumbnail(index: idx)
                    }
                }
                .padding(.vertical, 2)
            }
        }
    }

    private func thumbnail(index: Int) -> some View {
        let palette: [Color] = [
            QuickInkColors.paper1,
            QuickInkColors.accent.opacity(0.55),
            QuickInkColors.surface,
            QuickInkColors.paper3,
            QuickInkColors.paper2,
        ]
        return RoundedRectangle(cornerRadius: 6)
            .fill(palette[index % palette.count])
            .frame(width: 50, height: 50)
            .overlay(
                RoundedRectangle(cornerRadius: 6).strokeBorder(QuickInkColors.border, lineWidth: 1)
            )
    }

    private var ctaRow: some View {
        HStack(spacing: 8) {
            Button("Edit first") {
                makeStory(then: .editFirst)
            }
            .font(.system(size: 13, weight: .medium))
            .foregroundStyle(QuickInkColors.ink)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 11)
            .background(QuickInkColors.surface)
            .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(QuickInkColors.border, lineWidth: 1))
            .clipShape(RoundedRectangle(cornerRadius: 12))
            .buttonStyle(.plain)
            .disabled(suggestion == nil || creating)

            Button(creating ? "Creating…" : "Make story") {
                makeStory(then: .makeStory)
            }
            .font(.system(size: 13, weight: .medium))
            .foregroundStyle(QuickInkColors.textOnAccent)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 11)
            .background(QuickInkColors.accent)
            .clipShape(RoundedRectangle(cornerRadius: 12))
            .buttonStyle(.plain)
            .disabled(suggestion == nil || creating)
        }
        .padding(.horizontal, QuickInkSpacing.s4)
        .padding(.bottom, QuickInkSpacing.s5)
    }

    // MARK: - Logic

    private enum NextAction { case editFirst, makeStory }

    private func load() async {
        let result = try? await StorySuggestionEngine.compute(
            userId:    userId,
            database:  .shared,
            dismissed: []
        )
        if result?.id == suggestionId, let s = result {
            await MainActor.run { self.suggestion = s }
            await loadFirstPreview(captureId: s.candidateRefs.first)
            return
        }
        await MainActor.run { self.loadFailed = true }
    }

    /// Look up the first cluster capture's preview_uri so the
    /// first-page card renders an actual JPEG instead of the cream
    /// placeholder. Per `STORIES_HANDOFF.md` §8 don't-do list:
    /// "must render the actual first page using the same components
    /// the reader uses."
    private func loadFirstPreview(captureId: String?) async {
        guard let captureId = captureId else { return }
        struct Row: FetchableRecord {
            let previewUri: String?
            let title: String?
            init(row: GRDB.Row) {
                previewUri = row["preview_uri"] as String?
                title      = row["title"]       as String?
            }
        }
        let queue = QuickInkDatabase.shared.dbQueue
        let row = (try? await queue.read { db -> Row? in
            try Row.fetchOne(
                db,
                sql: "SELECT preview_uri, title FROM captures WHERE id = ? AND deleted_at IS NULL LIMIT 1",
                arguments: [captureId]
            )
        }) ?? nil
        await MainActor.run {
            firstPreview = (
                uri:     row?.previewUri,
                caption: row?.title?.isEmpty == false ? row?.title : nil
            )
        }
    }

    private func makeStory(then action: NextAction) {
        guard let suggestion = suggestion else { return }
        creating = true
        Task {
            let title = deriveTitle(suggestion)
            let story = try? await repository.insertStory(
                userId:   userId,
                title:    title,
                subtitle: nil
            )
            if let story = story {
                for (idx, captureId) in suggestion.candidateRefs.enumerated() {
                    _ = try? await repository.insertItem(
                        storyId:    story.id,
                        position:   (idx + 1) * 1024,
                        kind:       .document,
                        refId:      captureId,
                        text:       nil,
                        caption:    nil,
                        occurredAt: nil,
                        layout:     .full
                    )
                }
                await MainActor.run {
                    creating = false
                    onOpenStory(story.id)
                }
            } else {
                await MainActor.run {
                    creating = false
                    flashToast("Couldn't create the story.")
                }
            }
            _ = action  // silence unused for now; "Edit first" + "Make story" share the path until v1.1
        }
    }

    private func flashToast(_ message: String) {
        toast = message
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 1_800_000_000)
            toast = nil
        }
    }

    // MARK: - Derived

    private func deriveTitle(_ suggestion: StorySuggestion) -> String {
        // The engine carries a single `reason` string today. The
        // shelf preview synthesises a title from it: strip the trailing
        // ", {date range}" and use the remainder, OR fall back to the
        // reason verbatim when the comma is missing.
        if let lastComma = suggestion.reason.lastIndex(of: ",") {
            let date = suggestion.reason[suggestion.reason.index(after: lastComma)...]
                .trimmingCharacters(in: .whitespaces)
            return "Captures, \(date)"
        }
        return suggestion.reason
    }

    private func monthYearStamp(_ suggestion: StorySuggestion) -> String {
        // The reason ends with the date range. Show only the
        // first month-year tier; we don't have richer info without
        // looking up captures.
        let str = suggestion.reason.uppercased()
        return str
    }

    private func estimatedPages(_ suggestion: StorySuggestion) -> Int {
        max(1, suggestion.candidateRefs.count / 3)
    }
}
