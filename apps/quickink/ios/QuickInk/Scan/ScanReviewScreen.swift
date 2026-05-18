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

    // People + Places sections. The picker sheets handle commit on
    // their own Done button; this screen only needs the user's full
    // list (to look up names) and the set of currently-attached IDs
    // (to render the chip strip + know what to highlight).
    @State private var allPeople: [PersonEntity] = []
    @State private var allLocations: [LocationEntity] = []
    @State private var attachedPersonIds: Set<String> = []
    @State private var attachedLocationIds: Set<String> = []
    @State private var peopleCancellable: AnyCancellable? = nil
    @State private var locationsCancellable: AnyCancellable? = nil
    @State private var attachedPeopleCancellable: AnyCancellable? = nil
    @State private var attachedLocationsCancellable: AnyCancellable? = nil
    @State private var showPeoplePicker: Bool = false
    @State private var showLocationPicker: Bool = false
    // Capture lat/lon — read once per captureId; drives the
    // distance-based place auto-attach below. Null when the capture
    // landed without a location fix (offline, denied permission, etc).
    @State private var captureLat: Double? = nil
    @State private var captureLon: Double? = nil
    // Locations the user has explicitly detached this session. Guards
    // the auto-attach effect from re-attaching a place the user just
    // removed because they didn't want it on this scan.
    @State private var dismissedLocationIds: Set<String> = []
    // Same guard for the "Me" auto-attach — a person the user
    // explicitly detached this session must not be silently re-attached
    // by the default-person effect.
    @State private var dismissedPersonIds: Set<String> = []
    /// Place auto-attach radius. 150m covers GPS jitter on a typical
    /// urban fix; tighter than this misses across-the-street snaps,
    /// looser starts grabbing neighbors.
    private let autoAttachRadiusMeters: Double = 150

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
                    if !isFailed, let cid = inflightCaptureId {
                        peopleSection(captureId: cid)
                        placesSection(captureId: cid)
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
            if peopleCancellable == nil {
                peopleCancellable = PersonRepository()
                    .observe(userId: userId)
                    .receive(on: DispatchQueue.main)
                    .sink(
                        receiveCompletion: { _ in },
                        receiveValue: { rows in allPeople = rows }
                    )
            }
            if locationsCancellable == nil {
                locationsCancellable = LocationRepository()
                    .observe(userId: userId)
                    .receive(on: DispatchQueue.main)
                    .sink(
                        receiveCompletion: { _ in },
                        receiveValue: { rows in allLocations = rows }
                    )
            }
            if let cid = inflightCaptureId {
                subscribeAttachedJoins(captureId: cid)
                await refreshLocationAutoAttach(captureId: cid)
                await refreshSelfAutoAttach(captureId: cid)
            }
            await refreshSuggestions()
        }
        .onChange(of: inflightCaptureId) { newId in
            proposedNames = []
            dismissedNames = []
            suggestionsExpanded = false
            voiceTranscriptCount = 0
            voiceNotesCancellable?.cancel()
            attachedPersonIds = []
            attachedLocationIds = []
            attachedPeopleCancellable?.cancel()
            attachedLocationsCancellable?.cancel()
            captureLat = nil
            captureLon = nil
            dismissedLocationIds = []
            dismissedPersonIds = []
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
                subscribeAttachedJoins(captureId: cid)
                Task {
                    await refreshLocationAutoAttach(captureId: cid)
                    await refreshSelfAutoAttach(captureId: cid)
                }
            }
            Task { await refreshSuggestions() }
        }
        .onChange(of: allLocations.count) { _ in
            if let cid = inflightCaptureId {
                Task { await refreshLocationAutoAttach(captureId: cid) }
            }
        }
        .onChange(of: allPeople.count) { _ in
            if let cid = inflightCaptureId {
                Task { await refreshSelfAutoAttach(captureId: cid) }
            }
        }
        .sheet(isPresented: $showPeoplePicker) {
            if let cid = inflightCaptureId {
                PeoplePickerSheet(
                    userId:    userId,
                    captureId: cid,
                    onDismiss: { showPeoplePicker = false }
                )
                .presentationDetents([.large])
            }
        }
        .sheet(isPresented: $showLocationPicker) {
            if let cid = inflightCaptureId {
                LocationPickerSheet(
                    userId:    userId,
                    captureId: cid,
                    onDismiss: { showLocationPicker = false }
                )
                .presentationDetents([.large])
            }
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

    // MARK: - People + Places

    /// All people in the user's namespace as inline toggle chips, with
    /// attached chips rendered accent-filled. Tap toggles attach /
    /// detach against the in-flight capture. The "+ ADD" header button
    /// opens [PeoplePickerSheet] so the user can create a new person
    /// from a typed name when the inline list doesn't have what they
    /// want yet.
    @ViewBuilder
    private func peopleSection(captureId: String) -> some View {
        VStack(alignment: .leading, spacing: QuickInkSpacing.s2) {
            HStack {
                Text("PEOPLE")
                    .font(QuickInkText.eyebrow)
                    .tracking(QuickInkLetterSpacing.eyebrow)
                    .foregroundStyle(QuickInkColors.muted)
                Spacer()
                Button(action: { showPeoplePicker = true }) {
                    HStack(spacing: 3) {
                        Image(systemName: "plus")
                            .font(.system(size: 10, weight: .bold))
                        Text("ADD PERSON")
                            .font(.system(size: 10, weight: .semibold))
                            .tracking(1.2)
                    }
                    .foregroundStyle(QuickInkColors.accentDeep)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Add person")
            }
            if allPeople.isEmpty {
                Text("No people yet. Tap “Add person” to create one.")
                    .font(.system(size: 12))
                    .foregroundColor(QuickInkColors.muted)
            } else {
                ChipFlowLayout(spacing: 6) {
                    ForEach(orderedPeople(), id: \.id) { person in
                        entityToggleChip(
                            name:     person.name,
                            selected: attachedPersonIds.contains(person.id)
                        ) {
                            Task { await togglePerson(person, captureId: captureId) }
                        }
                    }
                }
            }
        }
    }

    /// All places in the user's namespace as inline toggle chips,
    /// mirroring [peopleSection]. Place auto-attach by capture lat/lon
    /// runs alongside, so a place near the capture's GPS fix is
    /// already filled-in by the time the user lands here.
    @ViewBuilder
    private func placesSection(captureId: String) -> some View {
        VStack(alignment: .leading, spacing: QuickInkSpacing.s2) {
            HStack {
                Text("PLACES")
                    .font(QuickInkText.eyebrow)
                    .tracking(QuickInkLetterSpacing.eyebrow)
                    .foregroundStyle(QuickInkColors.muted)
                Spacer()
                Button(action: { showLocationPicker = true }) {
                    HStack(spacing: 3) {
                        Image(systemName: "plus")
                            .font(.system(size: 10, weight: .bold))
                        Text("ADD PLACE")
                            .font(.system(size: 10, weight: .semibold))
                            .tracking(1.2)
                    }
                    .foregroundStyle(QuickInkColors.accentDeep)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Add place")
            }
            if allLocations.isEmpty {
                Text("No places yet. Tap “Add place” to create one.")
                    .font(.system(size: 12))
                    .foregroundColor(QuickInkColors.muted)
            } else {
                ChipFlowLayout(spacing: 6) {
                    ForEach(orderedLocations(), id: \.id) { loc in
                        entityToggleChip(
                            name:     loc.name,
                            selected: attachedLocationIds.contains(loc.id)
                        ) {
                            Task { await toggleLocation(loc, captureId: captureId) }
                        }
                    }
                }
            }
        }
    }

    /// Toggle chip mirroring [tagToggleChip] visually so the three
    /// inline picker surfaces (tags, people, places) read as one
    /// family. Filled-accent when selected, outlined otherwise.
    @ViewBuilder
    private func entityToggleChip(
        name: String,
        selected: Bool,
        onTap: @escaping () -> Void
    ) -> some View {
        Button(action: onTap) {
            Text(name)
                .font(.system(size: 11.5, weight: .medium))
                .foregroundColor(
                    selected
                    ? QuickInkColors.textOnAccent
                    : QuickInkColors.ink
                )
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
        .accessibilityLabel(name)
        .accessibilityAddTraits(selected ? [.isSelected] : [])
    }

    /// Attached-first ordering — keeps tapped chips at the head of the
    /// strip so the user can see what's already linked at a glance.
    /// Within each partition, walks `allPeople` so creation order
    /// stays stable frame-to-frame.
    private func orderedPeople() -> [PersonEntity] {
        let selected   = allPeople.filter { attachedPersonIds.contains($0.id) }
        let unselected = allPeople.filter { !attachedPersonIds.contains($0.id) }
        return selected + unselected
    }

    private func orderedLocations() -> [LocationEntity] {
        let selected   = allLocations.filter { attachedLocationIds.contains($0.id) }
        let unselected = allLocations.filter { !attachedLocationIds.contains($0.id) }
        return selected + unselected
    }

    private func togglePerson(_ person: PersonEntity, captureId: String) async {
        let repo = PersonRepository()
        if attachedPersonIds.contains(person.id) {
            try? await repo.detachPerson(captureId: captureId, personId: person.id)
            // Remember the explicit dismissal so the "Me" auto-attach
            // doesn't put the same row back behind the user's back.
            dismissedPersonIds.insert(person.id)
        } else {
            try? await repo.attachPerson(captureId: captureId, personId: person.id)
            dismissedPersonIds.remove(person.id)
        }
    }

    private func toggleLocation(_ loc: LocationEntity, captureId: String) async {
        let repo = LocationRepository()
        if attachedLocationIds.contains(loc.id) {
            try? await repo.detachLocation(captureId: captureId, locationId: loc.id)
            // Remember the explicit dismissal so the auto-attach
            // effect doesn't reattach the same place behind the
            // user's back on the next refresh.
            dismissedLocationIds.insert(loc.id)
        } else {
            try? await repo.attachLocation(captureId: captureId, locationId: loc.id)
            // Re-attach by deliberate user action clears the previous
            // dismissal so the matcher treats this place as freshly
            // desired going forward.
            dismissedLocationIds.remove(loc.id)
        }
    }

    /// Subscribes to the capture-scoped person/location join tables.
    /// Called from `.task` on initial mount and from the captureId
    /// `onChange` so the strip refreshes when a new in-flight capture
    /// appears mid-flow.
    private func subscribeAttachedJoins(captureId: String) {
        attachedPeopleCancellable = PersonRepository()
            .observePersonIds(captureId: captureId)
            .receive(on: DispatchQueue.main)
            .sink(
                receiveCompletion: { _ in },
                receiveValue: { ids in attachedPersonIds = Set(ids) }
            )
        attachedLocationsCancellable = LocationRepository()
            .observeLocationIds(captureId: captureId)
            .receive(on: DispatchQueue.main)
            .sink(
                receiveCompletion: { _ in },
                receiveValue: { ids in attachedLocationIds = Set(ids) }
            )
    }

    /// Auto-attach the seeded "Me" person to the in-flight capture
    /// so the common case (a scan that's about the user themselves)
    /// lands with one less tap. Idempotent: skips if "Me" was renamed
    /// away, already attached, or explicitly detached this session.
    /// Match is case-insensitive on the literal seed name.
    private func refreshSelfAutoAttach(captureId: String) async {
        guard let me = allPeople.first(where: { $0.name.caseInsensitiveCompare("Me") == .orderedSame }) else { return }
        if attachedPersonIds.contains(me.id) { return }
        if dismissedPersonIds.contains(me.id) { return }
        try? await PersonRepository().attachPerson(
            captureId: captureId,
            personId:  me.id,
        )
    }

    /// Read the capture's lat/lon once and run the place auto-attach.
    /// Called from `.task`, from the captureId `onChange`, and when
    /// `allLocations.count` changes (so a place created after the
    /// review screen mounted can still auto-attach).
    private func refreshLocationAutoAttach(captureId: String) async {
        if captureLat == nil || captureLon == nil {
            let coords: (Double, Double)? = await Task.detached(priority: .userInitiated) {
                let dbQueue = QuickInkDatabase.shared.dbQueue
                return try? await dbQueue.read { db -> (Double, Double)? in
                    let row = try Row.fetchOne(db, sql: """
                        SELECT latitude, longitude FROM captures
                        WHERE id = ? LIMIT 1
                        """, arguments: [captureId])
                    guard let row,
                          let lat: Double = row["latitude"],
                          let lon: Double = row["longitude"] else { return nil }
                    return (lat, lon)
                }
            }.value
            if let coords {
                captureLat = coords.0
                captureLon = coords.1
            }
        }
        guard let lat = captureLat, let lon = captureLon else { return }
        let repo = LocationRepository()
        for loc in allLocations {
            guard let placeLat = loc.latitude,
                  let placeLon = loc.longitude else { continue }
            let distance = haversineMeters(
                lat1: lat,        lon1: lon,
                lat2: placeLat,   lon2: placeLon,
            )
            if distance <= autoAttachRadiusMeters,
               !attachedLocationIds.contains(loc.id),
               !dismissedLocationIds.contains(loc.id) {
                try? await repo.attachLocation(captureId: captureId, locationId: loc.id)
            }
        }
    }

    /// Great-circle distance in meters. Equatorial radius of Earth at
    /// 6_371_000m — the eccentricity correction doesn't matter at the
    /// 150m radius we match within.
    private func haversineMeters(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ) -> Double {
        let R = 6_371_000.0
        let dLat = (lat2 - lat1) * .pi / 180
        let dLon = (lon2 - lon1) * .pi / 180
        let a = sin(dLat / 2) * sin(dLat / 2)
            + cos(lat1 * .pi / 180) * cos(lat2 * .pi / 180)
            * sin(dLon / 2) * sin(dLon / 2)
        let c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
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
