/*
 * ScanReviewScreen.swift
 *
 * Shown after the user finishes a scan. Layout (top → bottom):
 *
 *   1. Big category-button grid  — the user picks a category
 *      (or none) for the in-flight capture. Tap-to-toggle
 *      persists immediately via `controller.setCategory(name)`.
 *   2. Status indicator          — small progress / saved /
 *      failed badge. The hero used to be the progress UI; now
 *      it sits beneath the actionable affordances.
 *   3. Done button                — terminal-state-only.
 *
 * `captures.category` is per-capture, so the user can change
 * their mind any number of times during review.
 */

import SwiftUI
import Combine
import GRDB
import ReleafCoreDesignSystem

struct ScanReviewScreen: View {
    @ObservedObject var controller: ScanFlowController
    let userId: String

    @StateObject private var categoriesVM: TagListViewModel
    @State private var folders: [FolderEntity] = []
    @State private var foldersCancellable: AnyCancellable? = nil
    /// Subscribed to `voice_notes` for the in-flight capture so the
    /// suggester can re-run when a transcript lands on a clip the
    /// user recorded on the pre-review pane.
    @State private var voiceNotesCancellable: AnyCancellable? = nil
    @State private var voiceTranscriptCount: Int = 0
    /// Every name the suggester has emitted for the in-flight
    /// capture, in emit order. The visible "SUGGESTED FROM THIS
    /// SCAN" strip is computed as this list minus `acceptedNames`,
    /// so unselecting a tag in the TAGS section immediately puts it
    /// back without waiting for the suggester to re-emit it (which
    /// can fail when other suggestions filled the 4-slot budget).
    /// Reset on captureId change.
    @State private var proposedNames: [String] = []
    /// Tags the user has accepted from the suggestions strip during
    /// this review session, ordered by accept time. Backs the
    /// "TAGS" section below the paper-size row.
    @State private var acceptedNames: [String] = []
    /// Whether the "Add tag" alert is showing, with its in-flight
    /// draft text. Reset on save/cancel.
    @State private var showAddTagDialog: Bool = false
    @State private var newTagDraft: String = ""
    /// Names the user has explicitly detached this session. Guards
    /// auto-attach: a suggestion that matches an existing tag is
    /// auto-attached only if the user hasn't already removed it. Set
    /// is independent of `proposedNames` so a name that landed
    /// unmatched on an early refresh (when `categoriesVM.categories`
    /// was still loading) can still auto-attach once the categories
    /// observable catches up.
    @State private var dismissedNames: Set<String> = []
    /// Whether the suggestions strip is showing all rows or just the
    /// first two. Toggled by the chevron under the strip. Reset on
    /// captureId change.
    @State private var suggestionsExpanded: Bool = false

    /// Visible suggestion strip — proposals not currently attached.
    private var suggestedNames: [String] {
        let attached = Set(acceptedNames)
        return proposedNames.filter { !attached.contains($0) }
    }

    /// Optional callback to return to the voice-note pane. Nil when
    /// the review screen is mounted as a standalone surface; non-nil
    /// when [ScanCaptureSurface] is sequencing voice-note → review
    /// so we can offer a Back arrow that lets the user redo or
    /// extend the dictation.
    let onBack: (() -> Void)?

    init(
        controller: ScanFlowController,
        userId: String,
        onBack: (() -> Void)? = nil
    ) {
        self.controller = controller
        self.userId = userId
        self.onBack = onBack
        _categoriesVM = StateObject(
            wrappedValue: TagListViewModel(userId: userId)
        )
    }

    /// Workspace v1 Phase E.2 — captureId in flight, if any. Set
    /// by the controller's State.recognizing / .complete cases.
    private var inflightCaptureId: String? {
        switch controller.state {
        case .recognizing(let captureId, _, _): return captureId
        case .complete(let captureId, _, _):    return captureId
        default:                                return nil
        }
    }

    /// Page-completion count for the in-flight capture. Used to
    /// re-trigger `refreshSuggestions` as each OCR page lands so
    /// the chips appear once there's text to match — observing
    /// captureId alone fires before OCR has written anything.
    private var recognitionProgress: Int {
        switch controller.state {
        case .recognizing(_, _, let completed): return completed
        case .complete(_, let total, _):        return total
        default:                                return 0
        }
    }

    var body: some View {
        VStack(spacing: 0) {
            if let onBack {
                topBar(onBack: onBack)
            }
            ScrollView {
                VStack(spacing: QuickInkSpacing.s5) {
                    if !suggestedNames.isEmpty, !isFailed,
                       let cid = inflightCaptureId {
                        suggestionsStrip(captureId: cid)
                    }
                    if !folders.isEmpty, !isFailed {
                        folderButtonsGrid
                    }
                    if !isFailed {
                        paperSizeChipRow
                    }
                    if !isFailed,
                       !categoriesVM.categories.isEmpty,
                       inflightCaptureId != nil {
                        tagsSection
                    }
                    statusIndicator
                }
                .padding(.horizontal, QuickInkSpacing.s5)
                .padding(.top, QuickInkSpacing.s8)
                .padding(.bottom, QuickInkSpacing.s5)
            }

            if !isRecognizing {
                Button(action: { controller.dismiss() }) {
                    Text("Done")
                        .font(AppText.body)
                        .foregroundStyle(AppColors.textOnAccent)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, AppSpacing.s3)
                        .background(AppColors.themeGreenPrimary)
                        .clipShape(Capsule())
                }
                .padding(.horizontal, AppSpacing.s5)
                .padding(.bottom, AppSpacing.s5)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(AppColors.canvas.ignoresSafeArea())
        .task {
            categoriesVM.start()
            if foldersCancellable == nil {
                foldersCancellable = FolderRepository()
                    .observe(userId: userId)
                    .receive(on: DispatchQueue.main)
                    .sink(
                        receiveCompletion: { _ in },
                        receiveValue: { rows in folders = rows }
                    )
            }
            if voiceNotesCancellable == nil, let cid = inflightCaptureId {
                voiceNotesCancellable = VoiceNoteRepository()
                    .observeForCapture(cid)
                    .receive(on: DispatchQueue.main)
                    .sink(
                        receiveCompletion: { _ in },
                        receiveValue: { rows in
                            voiceTranscriptCount = rows
                                .filter { !($0.transcription ?? "").isEmpty }
                                .count
                        }
                    )
            }
            await refreshSuggestions()
        }
        .onChange(of: inflightCaptureId) { newId in
            proposedNames = []
            dismissedNames = []
            suggestionsExpanded = false
            voiceTranscriptCount = 0
            voiceNotesCancellable?.cancel()
            if let cid = newId {
                voiceNotesCancellable = VoiceNoteRepository()
                    .observeForCapture(cid)
                    .receive(on: DispatchQueue.main)
                    .sink(
                        receiveCompletion: { _ in },
                        receiveValue: { rows in
                            voiceTranscriptCount = rows
                                .filter { !($0.transcription ?? "").isEmpty }
                                .count
                        }
                    )
            }
            Task { await refreshSuggestions() }
        }
        .onChange(of: recognitionProgress) { _ in Task { await refreshSuggestions() } }
        .onChange(of: voiceTranscriptCount) { _ in Task { await refreshSuggestions() } }
        .onChange(of: categoriesVM.categories.count) { _ in Task { await refreshSuggestions() } }
    }

    /// Slim top bar with a back arrow that returns to the voice-
    /// note pane. Rendered only when [ScanCaptureSurface] passes an
    /// `onBack` callback, so standalone presentations of the review
    /// screen remain chrome-free.
    @ViewBuilder
    private func topBar(onBack: @escaping () -> Void) -> some View {
        HStack {
            Button(action: onBack) {
                HStack(spacing: 4) {
                    Image(systemName: "chevron.left")
                        .font(.system(size: 13, weight: .semibold))
                    Text("Back")
                        .font(.system(size: 14, weight: .medium))
                }
                .foregroundStyle(QuickInkColors.accentDeep)
                .padding(.horizontal, QuickInkSpacing.s2)
                .padding(.vertical, 6)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Back to voice note")
            Spacer()
        }
        .padding(.horizontal, QuickInkSpacing.s4)
        .padding(.top, QuickInkSpacing.s4)
    }

    /// Workspace v1 Phase E.2 — read OCR text for the in-flight
    /// capture, run the suggester, surface chips above the
    /// category grid. Wraps to multiple rows; clipped to 2 rows by
    /// default with a chevron toggle when there's more.
    @ViewBuilder
    private func suggestionsStrip(captureId: String) -> some View {
        let chipRows = estimatedRows(suggestedNames)
        let needsToggle = chipRows > 2
        VStack(alignment: .leading, spacing: QuickInkSpacing.s2) {
            Text("SUGGESTED FROM THIS SCAN")
                .font(.system(size: 10, weight: .bold))
                .tracking(1.2)
                .foregroundStyle(QuickInkColors.accentDeep)
            ChipFlowLayout(
                spacing: 6,
                maxRows: (needsToggle && !suggestionsExpanded) ? 2 : nil,
            ) {
                ForEach(suggestedNames, id: \.self) { name in
                    Button(action: { Task { await accept(name: name, captureId: captureId) } }) {
                        HStack(spacing: 3) {
                            Text("+")
                                .font(.system(size: 10, weight: .bold))
                                .foregroundColor(QuickInkColors.accentDeep)
                            Text("#")
                                .font(.system(size: 11.5, weight: .bold))
                                .foregroundColor(QuickInkColors.accent.opacity(0.7))
                            Text(name)
                                .font(.system(size: 11.5, weight: .medium))
                                .foregroundColor(QuickInkColors.accentDeep)
                        }
                        .padding(.horizontal, 9)
                        .padding(.vertical, 4)
                        .background(Color.white.opacity(0.85), in: Capsule())
                        .overlay(Capsule().stroke(QuickInkColors.accent.opacity(0.25), lineWidth: 1))
                    }
                    .buttonStyle(.plain)
                }
            }
            .clipped()
            if needsToggle {
                Button(action: {
                    withAnimation(.easeInOut(duration: 0.18)) {
                        suggestionsExpanded.toggle()
                    }
                }) {
                    HStack(spacing: 4) {
                        Image(systemName: suggestionsExpanded ? "chevron.up" : "chevron.down")
                            .font(.system(size: 10, weight: .bold))
                        Text(suggestionsExpanded ? "Show less" : "Show all (\(suggestedNames.count))")
                            .font(.system(size: 11, weight: .semibold))
                    }
                    .foregroundColor(QuickInkColors.accentDeep)
                    .padding(.top, 2)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(QuickInkSpacing.s3)
        .background(QuickInkColors.accentSoft.opacity(0.4), in: RoundedRectangle(cornerRadius: 14))
    }

    /// Rough chip-row prediction so the chevron only renders when
    /// the strip actually overflows. Uses approximate widths
    /// derived from chip text length — close enough for the
    /// 11.5pt chips this view produces on a phone-width container.
    /// Off-by-one is fine: a false positive shows the chevron when
    /// rows already fit (toggling does nothing visible), a false
    /// negative hides the chevron and the last row gets clipped.
    private func estimatedRows(_ names: [String]) -> Int {
        guard !names.isEmpty else { return 0 }
        // Phone-width minus side gutters minus the chip strip's
        // internal padding. Tuned for a 360pt-wide container.
        let availableWidth: CGFloat = 320
        let spacing: CGFloat = 6
        // chip pad + "+" + "#" + glyph spacing ≈ 36pt overhead;
        // chip text ≈ 7pt per char at 11.5pt font.
        let chipOverhead: CGFloat = 36
        let charWidth: CGFloat = 7
        var rowWidth: CGFloat = 0
        var rows = 1
        for name in names {
            let w = chipOverhead + CGFloat(name.count) * charWidth
            if rowWidth + w + (rowWidth > 0 ? spacing : 0) > availableWidth {
                rows += 1
                rowWidth = w
            } else {
                rowWidth += w + (rowWidth > 0 ? spacing : 0)
            }
        }
        return rows
    }

    private func refreshSuggestions() async {
        guard let captureId = inflightCaptureId else { return }
        let names: [String] = await Task.detached(priority: .userInitiated) {
            let dbQueue = QuickInkDatabase.shared.dbQueue
            let ocrText: String? = (try? await dbQueue.read { db -> String? in
                try String.fetchOne(db, sql: """
                    SELECT text FROM ocr_results
                    WHERE capture_id = ? AND deleted_at IS NULL
                    ORDER BY page_index ASC
                    LIMIT 1
                    """, arguments: [captureId])
            })
            // Pre-review voice notes contribute their transcript to the
            // suggester input so dictation drives the chips alongside
            // the first-page OCR text. Concatenated with a newline so
            // the keyword fallback treats both pools as one bag of
            // tokens (the rules don't care about word boundaries
            // between sources). Null when no transcripts have landed
            // yet — the screen re-runs as `setTranscription` fires.
            let voiceTranscripts: [String] = (try? await dbQueue.read { db -> [String] in
                try String.fetchAll(db, sql: """
                    SELECT transcription FROM voice_notes
                    WHERE capture_id = ?
                      AND deleted_at IS NULL
                      AND transcription IS NOT NULL
                      AND transcription <> ''
                    ORDER BY created_at ASC
                    """, arguments: [captureId])
            }) ?? []
            let combinedText: String? = {
                let parts = ([ocrText] + voiceTranscripts.map { Optional($0) })
                    .compactMap { $0 }
                    .filter { !$0.isEmpty }
                guard !parts.isEmpty else { return nil }
                return parts.joined(separator: "\n")
            }()
            let createdAt: String? = (try? await dbQueue.read { db -> String? in
                try String.fetchOne(db, sql: """
                    SELECT created_at FROM captures WHERE id = ? LIMIT 1
                    """, arguments: [captureId])
            })
            let allNames = await MainActor.run { Set(self.categoriesVM.categories.map(\.name)) }
            let attached = await MainActor.run { Set(self.acceptedNames) }
            return AutoTagSuggester.suggest(
                ocrText:           combinedText,
                existingTagNames:  allNames,
                currentlyAttached: attached,
                captureDateIso:    createdAt,
            )
        }.value

        // Walk new suggester output:
        //   - Any name that matches an existing tag, isn't already
        //     attached, and the user hasn't explicitly detached this
        //     session → auto-attach silently so the user sees it in
        //     the TAGS section pre-selected. Gating on
        //     `dismissedNames` (rather than "have we seen this name
        //     before?") lets late-arriving categories trigger an
        //     auto-attach on a subsequent refresh: a suggestion that
        //     landed unmatched while `categoriesVM.categories` was
        //     still loading can still be promoted later.
        //   - Add every emitted name to `proposedNames` so the
        //     derived `suggestedNames` strip surfaces it whenever
        //     it's not currently attached. This makes detach light
        //     up the chip in the strip immediately without depending
        //     on the suggester re-emitting it.
        let existingByName = Dictionary(
            uniqueKeysWithValues: categoriesVM.categories.map { ($0.name, $0) }
        )
        for name in names {
            if !proposedNames.contains(name) {
                proposedNames.append(name)
            }
            if let tag = existingByName[name],
               !acceptedNames.contains(name),
               !dismissedNames.contains(name) {
                try? await CaptureTagRepository().attachTag(
                    captureId: captureId,
                    tagId:     tag.id,
                    source:    "ai-suggested",
                )
                acceptedNames.append(name)
            }
        }
    }

    private func accept(name: String, captureId: String) async {
        let tag = try? await TagRepository().findOrCreate(userId: userId, name: name)
        guard let tag else { return }
        try? await CaptureTagRepository().attachTag(
            captureId: captureId,
            tagId:     tag.id,
            source:    "ai-suggested",
        )
        if !acceptedNames.contains(name) {
            acceptedNames.append(name)
        }
        // Re-attach by deliberate user action clears any previous
        // dismissal so future auto-attach runs (e.g. transcript
        // landing) treat the name as freshly desired.
        dismissedNames.remove(name)
    }

    private func detach(name: String, captureId: String) async {
        if let tag = try? await TagRepository().findByName(userId: userId, name: name) {
            try? await CaptureTagRepository().detachTag(
                captureId: captureId,
                tagId:     tag.id,
            )
        }
        acceptedNames.removeAll { $0 == name }
        // Remember the explicit dismissal so subsequent suggester
        // runs don't re-auto-attach this name behind the user's
        // back. The derived `suggestedNames` strip still surfaces
        // it (proposedNames still contains the name), so the user
        // can re-add deliberately.
        dismissedNames.insert(name)
    }

    private var isRecognizing: Bool {
        if case .recognizing = controller.state { return true }
        return false
    }

    private var isFailed: Bool {
        if case .failed = controller.state { return true }
        return false
    }

    // MARK: - Folder buttons

    /// Two-column grid of folder buttons. Each button writes the
    /// capture's `folder_id` through `ScanFlowController
    /// .setFolder`. The selected button paints with the folder's
    /// stored color so the picker reads the same as the
    /// corresponding folder tile on the Workspace home.
    @ViewBuilder
    private var folderButtonsGrid: some View {
        VStack(alignment: .leading, spacing: QuickInkSpacing.s3) {
            Text("FOLDER")
                .font(QuickInkText.eyebrow)
                .tracking(QuickInkLetterSpacing.eyebrow)
                .foregroundStyle(QuickInkColors.muted)

            LazyVGrid(
                columns: [
                    GridItem(.flexible(), spacing: QuickInkSpacing.s3),
                    GridItem(.flexible(), spacing: QuickInkSpacing.s3),
                ],
                spacing: QuickInkSpacing.s3
            ) {
                ForEach(folders, id: \.id) { folder in
                    folderButton(
                        folder:   folder,
                        selected: folder.id == controller.selectedFolderId
                    )
                }
            }
        }
    }

    @ViewBuilder
    private func folderButton(folder: FolderEntity, selected: Bool) -> some View {
        // Folder color drives the active fill so the button reads
        // the same as the corresponding folder tile on Workspace
        // home. Falls back to the accent if the stored hex doesn't
        // parse.
        let folderColor = colorFromHex(folder.color) ?? QuickInkColors.accent
        Button(action: {
            controller.setFolder(folder.id)
        }) {
            HStack(spacing: QuickInkSpacing.s2) {
                if !selected {
                    RoundedRectangle(cornerRadius: 2, style: .continuous)
                        .fill(folderColor)
                        .frame(width: 10, height: 10)
                }
                Text(folder.name)
                    .font(QuickInkText.cardTitle)
                    .foregroundStyle(selected ? QuickInkColors.textOnAccent : QuickInkColors.ink)
                    .multilineTextAlignment(.center)
                    .lineLimit(2)
            }
            .frame(maxWidth: .infinity, minHeight: 44)
            .padding(.horizontal, QuickInkSpacing.s2)
            .padding(.vertical, QuickInkSpacing.s2)
            .background(
                RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous)
                    .fill(selected ? folderColor : QuickInkColors.surface)
            )
            .overlay(
                RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous)
                    .strokeBorder(
                        selected ? folderColor : QuickInkColors.border,
                        lineWidth: 1
                    )
            )
        }
        .buttonStyle(.plain)
        .accessibilityLabel(folder.name)
        .accessibilityAddTraits(selected ? [.isSelected] : [])
    }

    // MARK: - Paper-size chip

    /// Four-up chip row letting the user disambiguate the auto-
    /// detected paper-size class (A4 / A5 / Letter / Custom). The
    /// auto-classifier seeds the selection from the first page's
    /// rectified aspect ratio + the user's last pick — A4 vs A5
    /// can't be told apart from ratio alone (both 1:√2 by ISO
    /// design), so this is the user's escape hatch.
    ///
    /// `.card` isn't surfaced here because card-shaped captures
    /// flow through the dedicated business-card capture surface,
    /// which writes `.card` directly without going through this
    /// review screen.
    @ViewBuilder
    private var paperSizeChipRow: some View {
        VStack(alignment: .leading, spacing: QuickInkSpacing.s2) {
            Text("PAPER SIZE")
                .font(QuickInkText.eyebrow)
                .tracking(QuickInkLetterSpacing.eyebrow)
                .foregroundStyle(QuickInkColors.muted)

            HStack(spacing: 6) {
                ForEach([PaperSize.a4, .a5, .letter, .card, .custom], id: \.rawValue) { size in
                    paperSizeChip(size: size,
                                  selected: controller.selectedPaperSize == size)
                }
            }
        }
    }

    @ViewBuilder
    private func paperSizeChip(size: PaperSize, selected: Bool) -> some View {
        let label: String = {
            switch size {
            case .a4:     return "A4"
            case .a5:     return "A5"
            case .letter: return "Letter"
            case .custom: return "Custom"
            case .card:   return "Card"
            }
        }()
        Button(action: { controller.setPaperSize(size) }) {
            Text(label)
                .font(.system(size: 12, weight: .medium))
                .foregroundStyle(selected
                                 ? QuickInkColors.textOnAccent
                                 : QuickInkColors.ink)
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(
                    Capsule(style: .continuous)
                        .fill(selected
                              ? QuickInkColors.accent
                              : Color.white.opacity(0.85))
                )
                .overlay(
                    Capsule(style: .continuous)
                        .stroke(
                            selected
                            ? QuickInkColors.accent
                            : QuickInkColors.accent.opacity(0.25),
                            lineWidth: 1
                        )
                )
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Paper size \(label)")
        .accessibilityAddTraits(selected ? [.isSelected] : [])
    }

    // MARK: - Tags section

    /// All tags in the user's namespace, rendered as toggle chips.
    /// Selected (attached) chips paint accent-filled; unselected
    /// chips paint outlined. Tap toggles attach/detach against the
    /// in-flight capture via `accept` / `detach`. Lives below the
    /// paper-size chip row so it complements the AI suggestions
    /// strip above the folder grid — suggestions surface tags the
    /// user might not have thought of, this section lets them pick
    /// from their own set.
    @ViewBuilder
    private var tagsSection: some View {
        VStack(alignment: .leading, spacing: QuickInkSpacing.s2) {
            HStack {
                Text("TAGS")
                    .font(QuickInkText.eyebrow)
                    .tracking(QuickInkLetterSpacing.eyebrow)
                    .foregroundStyle(QuickInkColors.muted)
                Spacer()
                Button(action: {
                    newTagDraft = ""
                    showAddTagDialog = true
                }) {
                    HStack(spacing: 3) {
                        Image(systemName: "plus")
                            .font(.system(size: 10, weight: .bold))
                        Text("ADD TAG")
                            .font(.system(size: 10, weight: .semibold))
                            .tracking(1.2)
                    }
                    .foregroundStyle(QuickInkColors.accentDeep)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Add tag")
            }
            ChipFlowLayout(spacing: 6) {
                ForEach(orderedTags(), id: \.id) { tag in
                    tagToggleChip(
                        name:     tag.name,
                        selected: acceptedNames.contains(tag.name)
                    )
                }
            }
        }
        .alert("New tag", isPresented: $showAddTagDialog) {
            TextField("Tag name", text: $newTagDraft)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled(true)
            Button("Cancel", role: .cancel) {
                newTagDraft = ""
            }
            Button("Add") {
                let raw = newTagDraft
                newTagDraft = ""
                Task { await createAndAttachTag(rawName: raw) }
            }
        } message: {
            Text("Lowercase, hyphens for spaces — e.g. \"meeting-notes\".")
        }
    }

    /// Normalize the raw name to canonical kebab form, create the
    /// tag (or reuse an existing one), and attach it to the
    /// in-flight capture as a manual pick. No-op for empty / blank
    /// input after trimming.
    private func createAndAttachTag(rawName: String) async {
        let normalized = normalizeTagName(rawName)
        guard !normalized.isEmpty,
              let captureId = inflightCaptureId else { return }
        let tag = try? await TagRepository().findOrCreate(
            userId: userId,
            name:   normalized
        )
        guard let tag else { return }
        try? await CaptureTagRepository().attachTag(
            captureId: captureId,
            tagId:     tag.id,
            source:    "manual",
        )
        if !acceptedNames.contains(normalized) {
            acceptedNames.append(normalized)
        }
        dismissedNames.remove(normalized)
    }

    /// `categoriesVM.categories` with selected (currently-attached)
    /// tags pulled to the front. Order within each partition matches
    /// the underlying creation order so the chip strip stays stable
    /// frame-to-frame and the user's mental map of where each tag
    /// lives doesn't shuffle on every accept/detach.
    private func orderedTags() -> [TagEntity] {
        let attached = Set(acceptedNames)
        let selected   = categoriesVM.categories.filter { attached.contains($0.name) }
        let unselected = categoriesVM.categories.filter { !attached.contains($0.name) }
        return selected + unselected
    }

    @ViewBuilder
    private func tagToggleChip(name: String, selected: Bool) -> some View {
        Button(action: {
            guard let cid = inflightCaptureId else { return }
            Task {
                if selected {
                    await detach(name: name, captureId: cid)
                } else {
                    await accept(name: name, captureId: cid)
                }
            }
        }) {
            HStack(spacing: 3) {
                Text("#")
                    .font(.system(size: 11.5, weight: .bold))
                    .foregroundColor(
                        selected
                        ? Color.white.opacity(0.7)
                        : QuickInkColors.accent.opacity(0.7)
                    )
                Text(name)
                    .font(.system(size: 11.5, weight: .medium))
                    .foregroundColor(
                        selected
                        ? QuickInkColors.textOnAccent
                        : QuickInkColors.ink
                    )
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 5)
            .background(
                Capsule(style: .continuous)
                    .fill(selected ? QuickInkColors.accent : Color.white.opacity(0.85))
            )
            .overlay(
                Capsule(style: .continuous)
                    .stroke(
                        selected
                        ? QuickInkColors.accent
                        : QuickInkColors.accent.opacity(0.25),
                        lineWidth: 1
                    )
            )
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Tag \(name)")
        .accessibilityAddTraits(selected ? [.isSelected] : [])
    }

    // MARK: - Status

    @ViewBuilder
    private var statusIndicator: some View {
        switch controller.state {
        case .idle:
            EmptyView()

        case .recognizing(_, let total, let completed):
            HStack(spacing: QuickInkSpacing.s2) {
                ProgressView()
                    .tint(QuickInkColors.accent)
                Text("Recognizing page \(completed) of \(total)")
                    .font(QuickInkText.body)
                    .foregroundStyle(QuickInkColors.inkSoft)
            }
            .frame(maxWidth: .infinity)

        case .complete(_, let total, let success):
            HStack(spacing: QuickInkSpacing.s2) {
                Image(systemName: "checkmark.circle.fill")
                    .font(.system(size: 18))
                    .foregroundStyle(QuickInkColors.success)
                Text("Saved — text on \(success) of \(total) pages")
                    .font(QuickInkText.body)
                    .foregroundStyle(QuickInkColors.inkSoft)
            }
            .frame(maxWidth: .infinity)

        case .failed(let message):
            VStack(spacing: QuickInkSpacing.s2) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .font(.system(size: 32))
                    .foregroundStyle(QuickInkColors.warning)
                Text("Couldn't save")
                    .font(QuickInkText.heading)
                    .foregroundStyle(QuickInkColors.ink)
                Text(message)
                    .font(QuickInkText.body)
                    .foregroundStyle(QuickInkColors.inkSoft)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, QuickInkSpacing.s5)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, QuickInkSpacing.s5)
        }
    }
}

/// Wraps chip-style subviews onto multiple rows so the chip strips
/// handle users with more than a handful of tags. The proposed
/// width clamps each row; subviews keep their ideal size.
///
/// `maxRows` clips the layout's reported height to that many rows.
/// Subviews beyond the cap are still placed by `placeSubviews` (at
/// their natural y-offset) but fall outside the reported height —
/// the caller must apply `.clipped()` to hide them. Pass nil for
/// no cap.
private struct ChipFlowLayout: Layout {
    let spacing: CGFloat
    let maxRows: Int?

    init(spacing: CGFloat, maxRows: Int? = nil) {
        self.spacing = spacing
        self.maxRows = maxRows
    }

    func sizeThatFits(
        proposal: ProposedViewSize,
        subviews: Subviews,
        cache: inout ()
    ) -> CGSize {
        let maxWidth = proposal.width ?? .infinity
        let rowHeights = rowHeights(subviews: subviews, maxWidth: maxWidth)
        let visibleHeights: [CGFloat]
        if let cap = maxRows {
            visibleHeights = Array(rowHeights.prefix(cap))
        } else {
            visibleHeights = rowHeights
        }
        let totalHeight = visibleHeights.reduce(0, +)
            + CGFloat(max(visibleHeights.count - 1, 0)) * spacing
        return CGSize(
            width: proposal.width ?? maxWidth,
            height: totalHeight
        )
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

    /// Walks the wrap and returns the height of each row.
    private func rowHeights(subviews: Subviews, maxWidth: CGFloat) -> [CGFloat] {
        var heights: [CGFloat] = []
        var rowWidth: CGFloat = 0
        var rowHeight: CGFloat = 0
        for sub in subviews {
            let size = sub.sizeThatFits(.unspecified)
            if rowWidth + size.width > maxWidth, rowWidth > 0 {
                heights.append(rowHeight)
                rowWidth = size.width
                rowHeight = size.height
            } else {
                rowWidth += size.width + (rowWidth > 0 ? spacing : 0)
                rowHeight = max(rowHeight, size.height)
            }
        }
        if rowWidth > 0 || rowHeight > 0 {
            heights.append(rowHeight)
        }
        return heights
    }
}
