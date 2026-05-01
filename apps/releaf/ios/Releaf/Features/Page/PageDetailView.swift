/*
 * PageDetailView.swift
 * One page, seven capture modes. Horizontal tab row; content switches with selection.
 */

import SwiftUI
import UIKit
import ReleafDesignSystem
import ReleafData

public struct PageDetailView: View {
    @StateObject private var viewModel: PageDetailViewModel
    @ObservedObject private var prefs: UiPreferences = .shared
    @State private var selected: CaptureMode = .overview
    @Environment(\.dismiss) private var dismiss

    public init(pageId: String) {
        _viewModel = StateObject(wrappedValue: PageDetailViewModel(pageId: pageId))
    }

    /// Bridges the persisted `prefs.state.pageViewMode` into the
    /// `Binding<PageViewMode>` the toggle expects. Get reads from
    /// prefs; set writes through `setPageViewMode(_:)` so the
    /// choice survives a cold start.
    private var viewModeBinding: Binding<PageViewMode> {
        Binding(
            get: { prefs.state.pageViewMode },
            set: { prefs.setPageViewMode($0) }
        )
    }

    public var body: some View {
        ZStack {
            DotGridBackground().ignoresSafeArea()
            content
        }
        .navigationBarTitleDisplayMode(.inline)
        .hidesBottomBar()
        .task { await viewModel.load() }
    }

    @ViewBuilder private var content: some View {
        switch viewModel.state {
        case .loading:
            ProgressView().tint(AppColors.coral)

        case .failed(let message):
            VStack(spacing: AppSpacing.s3) {
                Text(message)
                    .font(AppText.body)
                    .foregroundStyle(AppColors.textSecondary)
                AppButton("Try again", variant: .secondary) {
                    Task { await viewModel.load() }
                }
                .fixedSize(horizontal: true, vertical: false)
            }

        case .loaded(let page):
            Loaded(
                viewModel: viewModel,
                page: page,
                selected: $selected,
                viewMode: viewModeBinding,
                onBack: { dismiss() }
            )
        }
    }
}

// MARK: - Loaded

private struct Loaded: View {
    @ObservedObject var viewModel: PageDetailViewModel
    let page: Page
    @Binding var selected: CaptureMode
    @Binding var viewMode: PageViewMode
    @State private var showingPlantInfo = false
    let onBack: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            header
                .padding(.horizontal, AppSpacing.s4)
                .padding(.top, AppSpacing.s4)
                .padding(.bottom, AppSpacing.s3)

            if let archivedAt = page.archivedAt {
                ArchivedBanner(
                    archivedAt: archivedAt,
                    onRestore: { Task { await viewModel.restorePage() } }
                )
                .padding(.horizontal, AppSpacing.s4)
                .padding(.bottom, AppSpacing.s3)
            }

            // Tint the active-segment indicator with the parent
            // notebook's color when one is set so the section
            // switcher reads as part of the same family as the
            // header eyebrow + notes-card chrome.
            CaptureTabBar(
                selected: $selected,
                accentOverride: viewModel.parentNotebook?.colorToken
                    .map { ShelfTheme.palette(for: $0).background }
            )

            ScrollView {
                VStack(alignment: .leading, spacing: AppSpacing.s4) {
                    section
                    Spacer(minLength: AppSpacing.s10)
                }
                .padding(AppSpacing.s4)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .alert(
            "Archive this page?",
            isPresented: $viewModel.confirmingArchive
        ) {
            Button("Archive", role: .destructive) {
                Task { await viewModel.confirmArchive() }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("It will move to the archive. You can restore it from there.")
        }
        .sheet(item: $viewModel.shareIntent) { intent in
            ShareSheetView(intent: intent)
        }
        .sheet(isPresented: $viewModel.presentingMoveSheet) {
            MoveToNotebookSheet(
                notebooks: viewModel.availableNotebooks,
                isLoading: viewModel.loadingNotebooks,
                currentNotebookId: page.notebookId,
                chaptersByNotebookId: viewModel.chaptersByNotebookId,
                chaptersLoadingFor: viewModel.chaptersLoadingFor,
                onExpand: { notebookId in
                    Task { await viewModel.loadChapters(forNotebookId: notebookId) }
                },
                onSelect: { notebookId, chapterId in
                    Task { await viewModel.selectNotebook(notebookId, chapterId: chapterId) }
                }
            )
        }
        .sheet(isPresented: $viewModel.presentingTemplateSheet) {
            ApplyTemplateSheet(
                templates: viewModel.availableTemplates,
                isLoading: viewModel.loadingTemplates,
                onSelect: { templateId in
                    Task { await viewModel.selectTemplate(templateId) }
                }
            )
        }
        .sheet(isPresented: $viewModel.presentingTagEditor) {
            EditTagsSheet(
                initialTags: page.tags,
                onSave: { tags in Task { await viewModel.saveTags(tags) } },
                onCancel: { viewModel.presentingTagEditor = false },
                onCopyAll: { tags in viewModel.copyTagsToClipboard(tags) }
            )
        }
        .sheet(isPresented: $showingPlantInfo) {
            let plant = DailyPlants.forToday()
            DailyPlantInfoSheet(
                plant: plant,
                onCopy: {
                    // Compose "name — epithet" for the clipboard.
                    // Toast routing reuses copyTagToClipboard so we
                    // don't fork the toast pipeline for one extra
                    // surface. The view model immediately surfaces
                    // the confirmation pill.
                    viewModel.copyPlantHeadlineToClipboard(plant)
                    showingPlantInfo = false
                },
                onClose: { showingPlantInfo = false }
            )
        }
        .overlay(alignment: .top) {
            if let toast = viewModel.toast {
                ToastView(
                    message: toast.message,
                    actionLabel: toast.actionLabel,
                    onAction: toast.actionKind.map { kind in
                        { Task { await viewModel.performToastAction(kind) } }
                    }
                )
                    .padding(.top, AppSpacing.s4)
                    .transition(.move(edge: .top).combined(with: .opacity))
                    .task {
                        try? await Task.sleep(nanoseconds: 2_400_000_000)
                        withAnimation { viewModel.toast = nil }
                    }
            }
        }
    }

    private var header: some View {
        // Composed top zone — slot 1 leaf eyebrow, slot 2 view toggle
        // + overflow, slot 3 daily plant title (auto-rotated). Slot 4
        // (date pill) is intentionally absent: the day's plant carries
        // the editorial weight of the date, and the eyebrow's "TODAY"
        // states the calendar context.
        let plant = DailyPlants.forToday()
        // Eyebrow tint follows the parent notebook's color so the
        // page reads as part of the same visual family as the
        // notebook it lives in. Nil colorToken → keep the default
        // green chrome.
        let token = viewModel.parentNotebook?.colorToken
        let palette = ShelfTheme.palette(for: token)
        let usesCustomTint = token != nil
        return VStack(alignment: .leading, spacing: AppSpacing.s4) {
            HStack(alignment: .center) {
                LeafEyebrow(
                    "notepad · today",
                    glyphTint: usesCustomTint ? palette.background : nil,
                    labelTint: usesCustomTint ? palette.background : nil,
                    onTap: onBack
                )
                Spacer()
                HStack(spacing: AppSpacing.s2) {
                    PageViewToggle(selected: $viewMode)
                    PageOverflowButton {
                        // Order top-to-bottom by frequency: tag +
                        // duplicate are the lightweight everyday
                        // actions; move / template are heavier
                        // restructuring tools and sit below.
                        Button {
                            viewModel.presentTagEditor()
                        } label: { Label("Edit tags", systemImage: "tag") }
                        Button {
                            Task { await viewModel.duplicatePage() }
                        } label: { Label("Duplicate", systemImage: "plus.square.on.square") }
                        Button {
                            viewModel.presentMoveToNotebook()
                        } label: { Label("Move to notebook", systemImage: "tray.and.arrow.up") }
                        Button {
                            viewModel.presentTemplatePicker()
                        } label: { Label("Apply template", systemImage: "doc.text") }
                        Divider()
                        // Share-related actions live under one
                        // submenu so the top-level overflow stays
                        // compact. SwiftUI's Menu lets us nest
                        // without any extra plumbing.
                        Menu {
                            Button {
                                viewModel.presentShareSheet()
                            } label: { Label("Share", systemImage: "square.and.arrow.up") }
                            Button {
                                Task { await viewModel.exportPDF() }
                            } label: { Label("Export PDF", systemImage: "arrow.down.doc") }
                            Button {
                                viewModel.copyPageLinkToClipboard()
                            } label: { Label("Copy page link", systemImage: "link") }
                        } label: {
                            Label("Share", systemImage: "square.and.arrow.up")
                        }
                        Divider()
                        Button(role: .destructive) {
                            viewModel.archivePage()
                        } label: { Label("Archive page", systemImage: "archivebox") }
                    }
                }
            }
            // Tappable title block — opens the daily-plant info
            // sheet. The whole title + subtitle is the hit target so
            // users who go to read the descriptor land on the same
            // surface that explains the plant in full.
            Button {
                showingPlantInfo = true
            } label: {
                VStack(alignment: .leading, spacing: AppSpacing.s1) {
                    titleLine(plant: plant)
                        .lineLimit(2)
                        .minimumScaleFactor(0.7)
                    Text("\(plant.epithet)  ·  \(plant.usedFor)")
                        .font(.system(size: 14, weight: .regular, design: .serif).italic())
                        .foregroundStyle(AppColors.textSecondary)
                        .lineLimit(3)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityElement(children: .combine)
            .accessibilityLabel(
                Text("\(plant.name)\(plant.commonName.isEmpty ? "" : ", \(plant.commonName)"). \(plant.epithet). Used for \(plant.usedFor).")
            )
            .accessibilityHint("Tap for plant details")

            // Reading-time + word-count chip. Computed off the
            // loaded page's notes; hidden when the page has no
            // text content yet so we don't clutter empty pages
            // with "0 min read".
            if let summary = ReleafReadEstimate(
                noteBodies: page.notes.map { $0.body }
            ).summary {
                Text(summary)
                    .font(AppText.meta)
                    .foregroundStyle(AppColors.textTertiary)
                    .padding(.top, 2)
            }

            // Tag pills surface whatever the page is tagged with.
            // Short-tap → open the tag editor. Long-press → copy the
            // tag string to the clipboard with a confirming toast.
            // Both gestures live on the same pill so users can pick
            // an action without a context menu. Palette tints them
            // to the parent notebook color so tags belong visually.
            if !page.tags.isEmpty {
                TagsRow(
                    tags: page.tags,
                    palette: usesCustomTint ? palette : nil,
                    onTap: { _ in viewModel.presentTagEditor() },
                    onLongPress: { tag in viewModel.copyTagToClipboard(tag) }
                )
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// Title row rendered as one Text composition so the Sanskrit
    /// name and the parenthetical English share a baseline. Sanskrit
    /// at 32pt serif, English in 16pt serif italic muted — keeps the
    /// hierarchy clear without forcing the parenthetical onto its own
    /// line on the longer entries (e.g. "krishnanimba (curry leaf)").
    private func titleLine(plant: DailyPlant) -> some View {
        let primary = Text(plant.name)
            .font(.system(size: 32, weight: .regular, design: .serif))
            .foregroundColor(AppColors.textPrimary)
        if plant.commonName.isEmpty {
            return primary
        }
        let secondary = Text("  (\(plant.commonName))")
            .font(.system(size: 16, weight: .regular, design: .serif).italic())
            .foregroundColor(AppColors.textSecondary)
        return primary + secondary
    }

    @ViewBuilder private var section: some View {
        // Resolve the parent-notebook palette once for everything
        // under the capture-tab switcher. Currently consumed by
        // OverviewSection for the notes-card chrome; other modes
        // can pick it up later when they grow color-aware UI.
        let notebookPalette: ShelfPalette? =
            viewModel.parentNotebook?.colorToken.map(ShelfTheme.palette(for:))
        switch selected {
        case .overview: OverviewSection(
            page: page,
            viewMode: viewMode,
            notebookPalette: notebookPalette
        )
        case .photos:   PagePhotosSection(photos: page.photos)
        case .voice:    PageVoiceSection(notes: page.voiceNotes)
        case .todo:     TodoSection(items: page.todoItems)
        case .scans:    PageScansSection(scans: page.scannedDocuments)
        case .contacts: PageContactsSection(contacts: page.contacts)
        case .location: LocationsSection(pins: page.locations)
        // .notes is editor-only per CaptureMode.swift; no page-detail body
        // is currently defined for it. Placeholder satisfies exhaustive
        // switch; design-reviewed PageNotesSection lands in CAPTURE_TAB_PLAN
        // Phase 4 follow-up.
        case .notes:    EmptyView()
        }
    }
}

// MARK: - Overview
//
// Layout is the Re-Leaf redesign: a two-tile RE-LEAF strip at the
// top, AT A GLANCE 3×2 stat grid where each tile carries its
// capture-mode droplet glyph in the corner, then a NOTES preview card
// at the bottom carrying a green count pill and an italic placeholder
// when nothing has been written. Tapping the RE-LEAF eyebrow opens a
// PaperSavedSheet that explains the math.

private struct OverviewSection: View {
    let page: Page
    let viewMode: PageViewMode
    let notebookPalette: ShelfPalette?
    @State private var showPaperSavedSheet = false

    var body: some View {
        let c = page.counts
        let impact = ReleafImpact(
            photos: c.photos,
            voiceNotes: c.voiceNotes,
            todoItems: c.todoItems,
            scans: c.scannedDocuments,
            contacts: c.contacts,
            places: c.locations,
            notes: page.notes.count
        )

        let stats: [StatItem] = [
            StatItem(label: "Photos",   value: "\(c.photos)",            tone: .green,   mode: .photos),
            StatItem(label: "Scans",    value: "\(c.scannedDocuments)",  tone: .neutral, mode: .scans),
            StatItem(label: "To-do",    value: "\(c.todoItems)",         tone: .green,   mode: .todo),
            StatItem(label: "Contacts", value: "\(c.contacts)",          tone: .info,    mode: .contacts),
            StatItem(label: "Places",   value: "\(c.locations)",         tone: .neutral, mode: .location),
            StatItem(label: "Voice",    value: "\(c.voiceNotes)",        tone: .neutral, mode: .voice),
        ]

        VStack(alignment: .leading, spacing: AppSpacing.s4) {
            ReLeafStrip(
                impact: impact,
                onShowDetail: { showPaperSavedSheet = true },
                accentOverride: notebookPalette?.background
            )

            VStack(alignment: .leading, spacing: AppSpacing.s3) {
                // AT A GLANCE eyebrow tints to the parent-notebook
                // color so the overview surface reads as one
                // family. Nil token → default themeGreenDeep
                // matches the editorial canvas elsewhere.
                Text("AT A GLANCE")
                    .font(AppText.eyebrow)
                    .tracking(AppLetterSpacing.eyebrow)
                    .foregroundStyle(notebookPalette?.background ?? AppColors.themeGreenDeep)

                switch viewMode {
                case .grid:
                    StatGrid(items: Array(stats.prefix(3)), valueDesign: .serif)
                    StatGrid(items: Array(stats.suffix(3)), valueDesign: .serif)
                case .list:
                    StatList(items: stats, valueDesign: .serif)
                }
            }

            // Notes card pulls the parent-notebook palette so its
            // chip + chevron match the rest of the page chrome
            // (header eyebrow already tints the same way). Nil
            // token → default green.
            NotesPreviewCard(
                notes: page.notes,
                palette: notebookPalette
            )
        }
        .sheet(isPresented: $showPaperSavedSheet) {
            PaperSavedSheet(
                photos: c.photos,
                voiceNotes: c.voiceNotes,
                todoItems: c.todoItems,
                scans: c.scannedDocuments,
                contacts: c.contacts,
                places: c.locations,
                notes: page.notes.count,
                accentOverride: notebookPalette?.background,
                onClose: { showPaperSavedSheet = false }
            )
        }
    }
}

// MARK: - Notes preview

private struct NotesPreviewCard: View {
    let notes: [Note]
    /// Optional palette resolved from the page's parent notebook
    /// — when supplied, the page-count chip and the expand
    /// chevron tint to it so the card reads as part of the same
    /// family as the page header. Nil → default soft-green
    /// chrome.
    let palette: ShelfPalette?
    /// Local expand/collapse state. Collapsed shows only the
    /// first non-empty note (3 lines max); expanded reveals every
    /// note in full. The chevron in the header rotates 180° in
    /// the expanded state so it reads as a clear toggle.
    @State private var isExpanded: Bool = false

    var body: some View {
        // Pre-resolve chip + chevron colors once. Same alpha
        // strategy as the chapter section chip so coral/yellow
        // notebooks read consistent with their headers.
        let chipFill: Color = palette.map { $0.background.opacity(0.16) } ?? AppColors.greenSoft
        let chipText: Color = palette?.background ?? AppColors.greenText
        let chevronTint: Color = palette?.background ?? AppColors.themeGreenPrimary

        Card {
            VStack(alignment: .leading, spacing: AppSpacing.s2) {
                HStack(alignment: .firstTextBaseline) {
                    Text("NOTES")
                        .font(AppText.eyebrow)
                        .tracking(AppLetterSpacing.eyebrow)
                        .foregroundStyle(AppColors.textSecondary)
                    Text(pageCountLabel)
                        .font(AppText.tag)
                        .foregroundStyle(chipText)
                        .padding(.horizontal, AppSpacing.s2)
                        .padding(.vertical, 2)
                        .background(
                            Capsule().fill(chipFill)
                        )
                    Spacer()
                    // Expand/collapse chevron — rotates to telegraph
                    // the toggle. Hidden when there's nothing to
                    // expand (≤ 1 short note).
                    if hasMoreToShow {
                        Image(systemName: "chevron.down")
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(chevronTint)
                            .rotationEffect(.degrees(isExpanded ? 180 : 0))
                            .animation(.easeInOut(duration: 0.18), value: isExpanded)
                    }
                }

                if isExpanded && hasMoreToShow {
                    // Full content — every non-empty note rendered
                    // in order with hairline dividers between.
                    VStack(alignment: .leading, spacing: AppSpacing.s3) {
                        ForEach(Array(allNotes.enumerated()), id: \.offset) { idx, body in
                            if idx > 0 {
                                Rectangle()
                                    .fill(AppColors.borderDefault)
                                    .frame(height: 0.5)
                            }
                            Text(body)
                                .font(AppText.body)
                                .foregroundStyle(AppColors.textPrimary)
                        }
                    }
                } else if let preview = previewText {
                    Text(preview)
                        .font(AppText.body)
                        .foregroundStyle(AppColors.textPrimary)
                        .lineLimit(3)
                } else {
                    Text("tap to write notes…")
                        .font(.system(size: 15, weight: .regular, design: .serif).italic())
                        .foregroundStyle(AppColors.textTertiary)
                }

                if hasMoreToShow {
                    Text(isExpanded ? "Tap to collapse" : "Tap to expand")
                        .font(AppText.meta)
                        .foregroundStyle(AppColors.textTertiary)
                        .padding(.top, AppSpacing.s1)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
            .onTapGesture {
                if hasMoreToShow {
                    withAnimation(.easeInOut(duration: 0.18)) {
                        isExpanded.toggle()
                    }
                }
            }
            .accessibilityElement(children: .combine)
            .accessibilityHint(hasMoreToShow
                ? Text(isExpanded ? "Tap to collapse notes" : "Tap to expand notes")
                : Text("")
            )
        }
    }

    private var pageCountLabel: String {
        let n = max(notes.count, 1)
        return "\(n) page\(n == 1 ? "" : "s")"
    }

    /// All non-empty note bodies in order, used by the expanded
    /// rendering. Trimmed defensively so dividers never sit
    /// around a whitespace-only entry.
    private var allNotes: [String] {
        notes
            .map { $0.body.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
    }

    private var previewText: String? {
        allNotes.first
    }

    /// True when there's something the collapsed view can't show:
    /// either multiple notes, or a single note long enough to
    /// have been clipped at the 3-line limit. Roughly 200 chars
    /// is the inflection where the body font wraps to >3 lines
    /// at the page-detail width.
    private var hasMoreToShow: Bool {
        let bodies = allNotes
        if bodies.count > 1 { return true }
        return (bodies.first?.count ?? 0) > 200
    }
}

// MARK: - Photos

private struct PagePhotosSection: View {
    let photos: [Photo]
    var body: some View {
        if photos.isEmpty {
            EmptyState(message: "No photos on this page.")
        } else {
            VStack(spacing: AppSpacing.s3) {
                ForEach(photos) { PhotoTile(photo: $0) }
            }
        }
    }
}

private struct PhotoTile: View {
    let photo: Photo
    var body: some View {
        Card {
            VStack(alignment: .leading, spacing: AppSpacing.s2) {
                RoundedRectangle(cornerRadius: AppRadius.md, style: .continuous)
                    .stroke(AppColors.borderDefault, lineWidth: 1)
                    .frame(height: 180)
                    .overlay(
                        Text(photo.caption ?? "Photo")
                            .font(AppText.meta)
                            .foregroundStyle(AppColors.textTertiary)
                    )
                if let caption = photo.caption {
                    Text(caption)
                        .font(AppText.body)
                        .foregroundStyle(AppColors.textPrimary)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

// MARK: - Voice

private struct PageVoiceSection: View {
    let notes: [VoiceNote]

    var body: some View {
        VStack(spacing: AppSpacing.s4) {
            // Existing notes (most-recent first kept by parent ordering).
            if !notes.isEmpty {
                VStack(spacing: AppSpacing.s3) {
                    ForEach(notes) { VoiceCard(note: $0) }
                }
            }

            // Recording control. Always present so the user can capture
            // a new note without leaving the tab. Persistence is wired
            // upstream — the view model translates the recorded clip
            // into a real VoiceNote and writes it to the page.
            VoicePageRecorder(
                isEmpty: notes.isEmpty,
                onSave: { _ in
                    // TODO: route to the view model so a new VoiceNote
                    // is appended to this page. The clip URL + duration
                    // are persisted; transcription happens async via
                    // `VoiceTranscriber.transcribe(fileURL:)` after the
                    // file write settles.
                },
                onCancel: { /* no-op — cancelled clip already discarded */ }
            )
        }
    }
}

private struct VoiceCard: View {
    let note: VoiceNote
    var body: some View {
        Card {
            VStack(alignment: .leading, spacing: AppSpacing.s2) {
                HStack {
                    Text("Voice note · \(formatDuration(note.durationMs))")
                        .font(AppText.sectionTitle)
                        .foregroundStyle(AppColors.textPrimary)
                    Spacer()
                    Text("▶︎ Play")
                        .font(AppText.button)
                        .foregroundStyle(AppColors.coral)
                }
                if let transcription = note.transcription {
                    Text("\u{201C}\(transcription)\u{201D}")
                        .font(AppText.body.italic())
                        .foregroundStyle(AppColors.textSecondary)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

private func formatDuration(_ ms: Int) -> String {
    let totalSeconds = ms / 1000
    let minutes = totalSeconds / 60
    let seconds = totalSeconds % 60
    return String(format: "%d:%02d", minutes, seconds)
}

// MARK: - Todo

private struct TodoSection: View {
    let items: [TodoItem]
    var body: some View {
        if items.isEmpty {
            EmptyState(message: "Nothing on the to-do list.")
        } else {
            Card {
                VStack(alignment: .leading, spacing: AppSpacing.s3) {
                    ForEach(items.sorted(by: { $0.position < $1.position })) { TodoRow(item: $0) }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
    }
}

private struct TodoRow: View {
    let item: TodoItem
    var body: some View {
        HStack(alignment: .top, spacing: AppSpacing.s2) {
            Image(systemName: item.done ? "checkmark.circle.fill" : "circle")
                .foregroundStyle(item.done ? AppColors.coral : AppColors.textTertiary)
            Text(item.body)
                .font(AppText.body)
                .strikethrough(item.done)
                .foregroundStyle(item.done ? AppColors.textTertiary : AppColors.textPrimary)
        }
    }
}

// MARK: - Scans

private struct PageScansSection: View {
    let scans: [ScannedDocument]
    var body: some View {
        if scans.isEmpty {
            EmptyState(message: "No scanned documents.")
        } else {
            VStack(spacing: AppSpacing.s3) {
                ForEach(scans) { ScanRow(scan: $0) }
            }
        }
    }
}

private struct ScanRow: View {
    let scan: ScannedDocument
    var body: some View {
        Card {
            HStack(spacing: AppSpacing.s3) {
                Image(systemName: "doc.text")
                    .font(.system(size: 24))
                    .foregroundStyle(AppColors.coral)
                VStack(alignment: .leading, spacing: 2) {
                    Text(scan.title)
                        .font(AppText.sectionTitle)
                        .foregroundStyle(AppColors.textPrimary)
                    Text("\(scan.pageCount) page\(scan.pageCount == 1 ? "" : "s")")
                        .font(AppText.meta)
                        .foregroundStyle(AppColors.textSecondary)
                }
                Spacer()
            }
        }
    }
}

// MARK: - Contacts

private struct PageContactsSection: View {
    let contacts: [Contact]
    var body: some View {
        if contacts.isEmpty {
            EmptyState(message: "No contacts pinned to this page.")
        } else {
            VStack(spacing: AppSpacing.s3) {
                ForEach(contacts) { ContactCard(contact: $0) }
            }
        }
    }
}

private struct ContactCard: View {
    let contact: Contact
    var body: some View {
        Card {
            VStack(alignment: .leading, spacing: AppSpacing.s1) {
                Text(contact.name)
                    .font(AppText.sectionTitle)
                    .foregroundStyle(AppColors.textPrimary)
                if let phone = contact.phone {
                    Text(phone).font(AppText.body).foregroundStyle(AppColors.textSecondary)
                }
                if let email = contact.email {
                    Text(email).font(AppText.body).foregroundStyle(AppColors.textSecondary)
                }
                if let notes = contact.notes {
                    Text(notes).font(AppText.meta).foregroundStyle(AppColors.textTertiary)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

// MARK: - Locations

private struct LocationsSection: View {
    let pins: [LocationPin]
    var body: some View {
        if pins.isEmpty {
            EmptyState(message: "No places on this page.")
        } else {
            VStack(spacing: AppSpacing.s3) {
                ForEach(pins) { LocationCard(pin: $0) }
            }
        }
    }
}

private struct LocationCard: View {
    let pin: LocationPin
    var body: some View {
        Card {
            VStack(alignment: .leading, spacing: AppSpacing.s1) {
                Text(pin.name)
                    .font(AppText.sectionTitle)
                    .foregroundStyle(AppColors.textPrimary)
                Text(String(format: "%.4f, %.4f", pin.latitude, pin.longitude))
                    .font(AppText.meta)
                    .foregroundStyle(AppColors.textSecondary)
                if let notes = pin.notes {
                    Text(notes).font(AppText.body).foregroundStyle(AppColors.textPrimary)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

// MARK: - Tag editor

/// Modal sheet for editing the loaded page's tags. Carries its
/// own `@State` for the draft list + the in-progress text input,
/// so the parent only needs to seed the initial list and hand
/// back the final array on Save.
///
/// Submission rules in the input field:
/// - Comma → commits the current text as a tag, clears input
/// - Return / Enter → commits + dismisses keyboard focus
/// - Backspace on empty input → removes the most recent tag
/// (the last rule is left as a follow-up — needs a custom
/// UIKit-backed text field to detect the keystroke)
private struct EditTagsSheet: View {
    let initialTags: [String]
    let onSave: ([String]) -> Void
    let onCancel: () -> Void
    /// Optional copy-all action — when supplied, a small Copy
    /// pill renders next to the eyebrow. Tap copies the current
    /// (in-progress) tag list as a comma-separated string.
    let onCopyAll: (([String]) -> Void)?

    @State private var tags: [String]
    @State private var draft: String = ""
    @FocusState private var inputFocused: Bool
    @Environment(\.dismiss) private var dismiss

    init(
        initialTags: [String],
        onSave: @escaping ([String]) -> Void,
        onCancel: @escaping () -> Void,
        onCopyAll: (([String]) -> Void)? = nil
    ) {
        self.initialTags = initialTags
        self.onSave = onSave
        self.onCancel = onCancel
        self.onCopyAll = onCopyAll
        _tags = State(initialValue: initialTags)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: AppSpacing.s4) {
            HStack(alignment: .center) {
                Text("EDIT TAGS")
                    .font(AppText.eyebrow)
                    .tracking(AppLetterSpacing.eyebrow)
                    .foregroundStyle(AppColors.themeGreenDeep)
                Spacer()
                if let onCopyAll, !tags.isEmpty {
                    // Copy-all pill — pulls the in-progress tag
                    // list (not the original) onto the clipboard
                    // so the user gets what they're looking at,
                    // including unsaved additions.
                    Button {
                        onCopyAll(tags)
                    } label: {
                        HStack(spacing: 4) {
                            Image(systemName: "doc.on.doc")
                                .font(.system(size: 11, weight: .semibold))
                            Text("Copy all")
                                .font(AppText.tag)
                        }
                        .foregroundStyle(AppColors.themeGreenDeep)
                        .padding(.horizontal, AppSpacing.s2)
                        .padding(.vertical, 4)
                        .background(Capsule().fill(AppColors.greenSoft))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(Text("Copy all tags"))
                }
            }
            Text("tag this page")
                .font(.system(size: 28, weight: .regular, design: .serif))
                .foregroundStyle(AppColors.textPrimary)
            Text("Type and press comma or return to add. Tap × to remove.")
                .font(AppText.meta)
                .foregroundStyle(AppColors.textSecondary)

            currentTagsRow
                .padding(.top, AppSpacing.s2)

            inputRow

            HStack(spacing: AppSpacing.s3) {
                AppButton("Cancel", variant: .secondary, action: onCancel)
                AppButton("Save", variant: .primary) {
                    commitDraft()
                    onSave(tags)
                }
            }
            .padding(.top, AppSpacing.s3)
        }
        .padding(.horizontal, AppSpacing.s5)
        .padding(.vertical, AppSpacing.s5)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(AppColors.cardSolid.ignoresSafeArea())
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .onAppear { inputFocused = true }
    }

    @ViewBuilder
    private var currentTagsRow: some View {
        if tags.isEmpty {
            Text("No tags yet.")
                .font(AppText.body)
                .foregroundStyle(AppColors.textTertiary)
        } else {
            // Compact wrapping flow — one row per line, wraps when
            // the tags overflow. SwiftUI doesn't ship a wrap layout
            // out of the box; this uses a Layout introduced in
            // iOS 16. Falls back to a horizontal ScrollView on
            // older OSes — but our floor is iOS 16.
            TagsFlow(spacing: AppSpacing.s2) {
                ForEach(Array(tags.enumerated()), id: \.element) { index, tag in
                    EditableTagPill(label: tag) {
                        if let idx = tags.firstIndex(of: tag) {
                            tags.remove(at: idx)
                        }
                    }
                }
            }
        }
    }

    @ViewBuilder
    private var inputRow: some View {
        HStack(spacing: AppSpacing.s2) {
            Image(systemName: "tag")
                .font(.system(size: 14))
                .foregroundStyle(AppColors.themeGreenDeep)
            TextField("Add a tag", text: $draft)
                .textInputAutocapitalization(.never)
                .focused($inputFocused)
                .onSubmit { commitDraft() }
                .onChange(of: draft) { newValue in
                    // Comma is a commit signal — split off everything
                    // before the comma and commit each segment.
                    if newValue.contains(",") {
                        let parts = newValue.split(separator: ",")
                        for part in parts.dropLast() {
                            appendTag(String(part))
                        }
                        draft = String(parts.last ?? "")
                    }
                }
        }
        .padding(AppSpacing.s3)
        .background(AppColors.inputBg)
        .overlay(
            RoundedRectangle(cornerRadius: AppRadius.sm, style: .continuous)
                .stroke(AppColors.borderDefault, lineWidth: 1)
        )
        .clipShape(RoundedRectangle(cornerRadius: AppRadius.sm, style: .continuous))
    }

    private func commitDraft() {
        appendTag(draft)
        draft = ""
    }

    private func appendTag(_ raw: String) {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        // De-dupe case-insensitively but keep the user's original
        // casing for the first occurrence.
        if !tags.contains(where: { $0.caseInsensitiveCompare(trimmed) == .orderedSame }) {
            tags.append(trimmed)
        }
    }
}

/// Bottom-sheet that explains the day's rotated plant. The page
/// header surfaces it on tap of the title — so users curious
/// about the Sanskrit name (or the epithet line) land on the same
/// canonical surface that names the plant in full and lists what
/// it's traditionally used for.
private struct DailyPlantInfoSheet: View {
    let plant: DailyPlant
    /// Fires when the user taps the Copy pill — the page screen
    /// passes a callback that copies "name — epithet" to the
    /// clipboard and surfaces a confirming toast.
    let onCopy: () -> Void
    let onClose: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: AppSpacing.s4) {
            VStack(alignment: .leading, spacing: AppSpacing.s2) {
                Text("PLANT OF THE DAY")
                    .font(AppText.eyebrow)
                    .tracking(AppLetterSpacing.eyebrow)
                    .foregroundStyle(AppColors.themeGreenDeep)
                Text(plant.name)
                    .font(.system(size: 36, weight: .regular, design: .serif))
                    .foregroundStyle(AppColors.textPrimary)
                if !plant.commonName.isEmpty {
                    Text(plant.commonName)
                        .font(.system(size: 18, weight: .regular, design: .serif).italic())
                        .foregroundStyle(AppColors.textSecondary)
                }
            }

            Divider()

            VStack(alignment: .leading, spacing: AppSpacing.s4) {
                InfoBlock(
                    title: "EPITHET",
                    copy: plant.epithet
                )
                InfoBlock(
                    title: "TRADITIONAL USES",
                    copy: plant.usedFor
                )
            }

            Spacer(minLength: AppSpacing.s4)

            HStack(spacing: AppSpacing.s3) {
                // Copy pill — pulls the plant's headline (name +
                // epithet) onto the clipboard so users can paste
                // it into their own notes, search, or share text.
                Button(action: onCopy) {
                    HStack(spacing: AppSpacing.s2) {
                        Image(systemName: "doc.on.doc")
                            .font(.system(size: 13, weight: .semibold))
                        Text("Copy")
                            .font(AppText.button)
                    }
                    .foregroundStyle(AppColors.themeGreenDeep)
                    .padding(.horizontal, AppSpacing.s4)
                    .padding(.vertical, AppSpacing.s2)
                    .background(Capsule().fill(AppColors.greenSoft))
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text("Copy plant info"))

                AppButton("Close", variant: .secondary, action: onClose)
                    .frame(maxWidth: .infinity)
            }
        }
        .padding(.horizontal, AppSpacing.s5)
        .padding(.vertical, AppSpacing.s5)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(AppColors.cardSolid.ignoresSafeArea())
        .presentationDetents([.medium])
        .presentationDragIndicator(.visible)
    }
}

private struct InfoBlock: View {
    let title: String
    /// Renamed from `body` — collided with SwiftUI's required
    /// `var body: some View` on the View conformance.
    let copy: String

    var body: some View {
        VStack(alignment: .leading, spacing: AppSpacing.s1) {
            Text(title)
                .font(AppText.eyebrow)
                .tracking(AppLetterSpacing.eyebrow)
                .foregroundStyle(AppColors.textSecondary)
            Text(copy)
                .font(AppText.body)
                .foregroundStyle(AppColors.textPrimary)
        }
    }
}

/// Read-only row of tag pills surfaced under the page title. Same
/// soft-green chrome as the editable pill but without the remove
/// affordance. Short-tap a pill → `onTap(label)` (header wires this
/// to open the tag editor). Long-press → `onLongPress(label)`
/// (header wires this to copy the tag to the clipboard). Both
/// surfaces share the same pill so the action set stays compact.
/// Optional `palette` tints the pills to the parent notebook so
/// even tags read as part of the notebook color family.
private struct TagsRow: View {
    let tags: [String]
    let palette: ShelfPalette?
    let onTap: (String) -> Void
    let onLongPress: (String) -> Void

    var body: some View {
        let pillFill: Color = palette.map { $0.background.opacity(0.16) } ?? AppColors.greenSoft
        let pillText: Color = palette?.background ?? AppColors.greenText
        TagsFlow(spacing: AppSpacing.s2) {
            ForEach(tags, id: \.self) { tag in
                Text(tag)
                    .font(AppText.tag)
                    .foregroundStyle(pillText)
                    .padding(.horizontal, AppSpacing.s3)
                    .padding(.vertical, AppSpacing.s1)
                    .background(Capsule().fill(pillFill))
                    .contentShape(Capsule())
                    .onTapGesture { onTap(tag) }
                    .onLongPressGesture(minimumDuration: 0.4) {
                        onLongPress(tag)
                    }
                    .accessibilityElement(children: .combine)
                    .accessibilityLabel(Text("Tag \(tag)"))
                    .accessibilityHint("Tap to edit tags. Long-press to copy.")
            }
        }
        .padding(.top, AppSpacing.s2)
    }
}

private struct EditableTagPill: View {
    let label: String
    let onRemove: () -> Void

    var body: some View {
        HStack(spacing: 6) {
            Text(label)
                .font(AppText.tag)
                .foregroundStyle(AppColors.greenText)
            Button(action: onRemove) {
                Image(systemName: "xmark")
                    .font(.system(size: 9, weight: .semibold))
                    .foregroundStyle(AppColors.greenText)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text("Remove \(label)"))
        }
        .padding(.horizontal, AppSpacing.s3)
        .padding(.vertical, AppSpacing.s1)
        .background(Capsule().fill(AppColors.greenSoft))
    }
}

/// Tiny flow container — wraps children to next row when they
/// overflow horizontally. Uses the `Layout` protocol introduced
/// in iOS 16 (the package's floor).
private struct TagsFlow: Layout {
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
        var totalWidth: CGFloat = 0
        for view in subviews {
            let size = view.sizeThatFits(.unspecified)
            if rowWidth + size.width > maxWidth, rowWidth > 0 {
                totalHeight += rowHeight + spacing
                totalWidth = max(totalWidth, rowWidth - spacing)
                rowWidth = 0
                rowHeight = 0
            }
            rowWidth += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
        totalHeight += rowHeight
        totalWidth = max(totalWidth, rowWidth - spacing)
        return CGSize(width: totalWidth, height: totalHeight)
    }

    func placeSubviews(
        in bounds: CGRect,
        proposal: ProposedViewSize,
        subviews: Subviews,
        cache: inout ()
    ) {
        let maxWidth = bounds.width
        var x: CGFloat = bounds.minX
        var y: CGFloat = bounds.minY
        var rowHeight: CGFloat = 0
        for view in subviews {
            let size = view.sizeThatFits(.unspecified)
            if x + size.width > bounds.maxX, x > bounds.minX {
                y += rowHeight + spacing
                x = bounds.minX
                rowHeight = 0
            }
            view.place(
                at: CGPoint(x: x, y: y),
                proposal: ProposedViewSize(size)
            )
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
    }
}

// MARK: - Archived banner

/// Soft-green banner shown at the top of an archived page. Reuses
/// the existing `greenSoft / greenText` semantic pair so it reads
/// as a *state* indicator rather than an alert. The Restore button
/// is the primary affordance — undoing archive should be one tap.
private struct ArchivedBanner: View {
    let archivedAt: Date
    let onRestore: () -> Void

    var body: some View {
        HStack(alignment: .center, spacing: AppSpacing.s3) {
            Image(systemName: "archivebox.fill")
                .font(.system(size: 14))
                .foregroundStyle(AppColors.greenText)
            VStack(alignment: .leading, spacing: 1) {
                Text("ARCHIVED")
                    .font(AppText.eyebrow)
                    .tracking(AppLetterSpacing.eyebrow)
                    .foregroundStyle(AppColors.greenText)
                Text(archivedAt, format: .relative(presentation: .named))
                    .font(AppText.meta)
                    .foregroundStyle(AppColors.textSecondary)
            }
            Spacer(minLength: AppSpacing.s2)
            Button(action: onRestore) {
                Text("Restore")
                    .font(AppText.button)
                    .foregroundStyle(AppColors.greenText)
                    .padding(.horizontal, AppSpacing.s3)
                    .padding(.vertical, AppSpacing.s1)
                    .background(
                        Capsule().fill(AppColors.cardSolid)
                    )
                    .overlay(
                        Capsule().stroke(AppColors.greenText.opacity(0.4), lineWidth: 1)
                    )
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, AppSpacing.s3)
        .padding(.vertical, AppSpacing.s2)
        .background(
            RoundedRectangle(cornerRadius: AppRadius.md, style: .continuous)
                .fill(AppColors.greenSoft)
        )
        .accessibilityElement(children: .combine)
        .accessibilityLabel(Text("Page is archived. Tap Restore to bring it back."))
    }
}

// MARK: - Action helpers

/// Wraps `UIActivityViewController` for the system share sheet so the
/// view model can present share content without holding any UIKit.
/// On iOS 16+ this could be replaced with a native `ShareLink`, but
/// we use the wrapper so the ShareIntent payload stays a value type.
private struct ShareSheetView: UIViewControllerRepresentable {
    let intent: PageDetailViewModel.ShareIntent

    func makeUIViewController(context: Context) -> UIActivityViewController {
        // When a file URL is present (PDF export path), share the
        // file directly so targets like Mail / Files / iCloud Drive
        // recognize it as a real document. Plain-text share keeps
        // the title + body strings.
        let items: [Any] = if let url = intent.fileURL {
            [intent.title, url]
        } else {
            [intent.title, intent.body]
        }
        return UIActivityViewController(
            activityItems: items,
            applicationActivities: nil
        )
    }

    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}

/// Real Move-to-notebook picker. Renders the current
/// `viewModel.availableNotebooks` as a vertical list of rows; the
/// row for the page's *current* notebook is dimmed and unclickable
/// so users don't no-op-move into the same place. A spinner row
/// shows while the list is loading.
private struct MoveToNotebookSheet: View {
    let notebooks: [Notebook]
    let isLoading: Bool
    let currentNotebookId: String
    let chaptersByNotebookId: [String: [Chapter]]
    let chaptersLoadingFor: Set<String>
    /// Called when the user taps a notebook row to expand it. The
    /// caller wires this to the ViewModel's `loadChapters(...)`.
    let onExpand: (String) -> Void
    /// Called on selection. `chapterId` is nil when the user picks
    /// "Move to top" (the default) and non-nil when they tap a
    /// specific chapter row.
    let onSelect: (String, String?) -> Void

    @State private var expandedNotebookId: String?

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: AppSpacing.s3) {
                Text("MOVE")
                    .font(AppText.eyebrow)
                    .tracking(AppLetterSpacing.eyebrow)
                    .foregroundStyle(AppColors.themeGreenDeep)
                Text("move to notebook")
                    .font(.system(size: 28, weight: .regular, design: .serif))
                    .foregroundStyle(AppColors.textPrimary)
                Text("Pick the notebook this page should live under. The current notebook is dimmed.")
                    .font(AppText.meta)
                    .foregroundStyle(AppColors.textSecondary)
                    .padding(.bottom, AppSpacing.s2)

                if isLoading && notebooks.isEmpty {
                    HStack {
                        Spacer()
                        ProgressView().tint(AppColors.themeGreenPrimary)
                        Spacer()
                    }
                    .padding(.vertical, AppSpacing.s5)
                } else if notebooks.isEmpty {
                    Text("No notebooks yet.")
                        .font(AppText.body)
                        .foregroundStyle(AppColors.textTertiary)
                        .padding(.vertical, AppSpacing.s4)
                } else {
                    VStack(spacing: AppSpacing.s2) {
                        ForEach(notebooks) { notebook in
                            NotebookPickerRow(
                                notebook: notebook,
                                isCurrent: notebook.id == currentNotebookId,
                                isExpanded: expandedNotebookId == notebook.id,
                                chapters: chaptersByNotebookId[notebook.id] ?? [],
                                isLoadingChapters: chaptersLoadingFor.contains(notebook.id),
                                onTap: {
                                    if expandedNotebookId == notebook.id {
                                        expandedNotebookId = nil
                                    } else {
                                        expandedNotebookId = notebook.id
                                        onExpand(notebook.id)
                                    }
                                },
                                onMoveToTop: {
                                    onSelect(notebook.id, nil)
                                },
                                onSelectChapter: { chapterId in
                                    onSelect(notebook.id, chapterId)
                                }
                            )
                        }
                    }
                }

                Spacer(minLength: AppSpacing.s4)
                AppButton("Cancel", variant: .secondary) { dismiss() }
                    .padding(.top, AppSpacing.s2)
            }
            .padding(.horizontal, AppSpacing.s5)
            .padding(.vertical, AppSpacing.s5)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .background(AppColors.cardSolid.ignoresSafeArea())
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }
}

/// One row inside the Move-to-notebook picker. Shows a small color
/// chip keyed off the notebook's `colorToken` (so each notebook is
/// recognisable at a glance), the notebook's title, and a meta line
/// with chapter + page counts. Disabled rendering for the page's
/// current home.
private struct NotebookPickerRow: View {
    let notebook: Notebook
    let isCurrent: Bool
    let isExpanded: Bool
    let chapters: [Chapter]
    let isLoadingChapters: Bool
    let onTap: () -> Void
    let onMoveToTop: () -> Void
    let onSelectChapter: (String) -> Void

    var body: some View {
        VStack(spacing: 0) {
            // Header row — tap toggles expansion (or restores after
            // a re-tap). The current notebook is dimmed but stays
            // expandable so users can see chapters they could move
            // *within* a notebook in a later iteration.
            Button(action: { if !isCurrent { onTap() } }) {
                HStack(alignment: .center, spacing: AppSpacing.s3) {
                    ColorChip(token: notebook.colorToken)
                        .frame(width: 32, height: 32)
                    VStack(alignment: .leading, spacing: 2) {
                        HStack(spacing: AppSpacing.s2) {
                            Text(notebook.title)
                                .font(AppText.button)
                                .foregroundStyle(AppColors.textPrimary)
                            if isCurrent {
                                Text("CURRENT")
                                    .font(AppText.tag)
                                    .foregroundStyle(AppColors.greenText)
                                    .padding(.horizontal, AppSpacing.s2)
                                    .padding(.vertical, 1)
                                    .background(Capsule().fill(AppColors.greenSoft))
                            }
                        }
                        Text("\(notebook.chapterCount) chapter\(notebook.chapterCount == 1 ? "" : "s") · \(notebook.pageCount) page\(notebook.pageCount == 1 ? "" : "s")")
                            .font(AppText.meta)
                            .foregroundStyle(AppColors.textSecondary)
                    }
                    Spacer(minLength: AppSpacing.s2)
                    if !isCurrent {
                        Image(systemName: isExpanded ? "chevron.down" : "chevron.right")
                            .font(.system(size: 13))
                            .foregroundStyle(AppColors.textTertiary)
                    }
                }
                .padding(.horizontal, AppSpacing.s3)
                .padding(.vertical, AppSpacing.s3)
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(isCurrent)

            if isExpanded && !isCurrent {
                expandedSection
            }
        }
        .background(
            RoundedRectangle(cornerRadius: AppRadius.md, style: .continuous)
                .fill(AppColors.canvas)
        )
        .overlay(
            RoundedRectangle(cornerRadius: AppRadius.md, style: .continuous)
                .stroke(AppColors.borderDefault, lineWidth: 1)
        )
        .opacity(isCurrent ? 0.55 : 1.0)
        .accessibilityHint(isCurrent ? "Already in this notebook" : "Tap to choose a chapter")
    }

    @ViewBuilder
    private var expandedSection: some View {
        VStack(spacing: 0) {
            Rectangle()
                .fill(AppColors.borderDefault)
                .frame(height: 0.5)

            // "Move to top" — preserves the v1 default of letting
            // users skip chapter selection and dump the page into
            // the destination's first chapter.
            chapterRow(
                title: "Move to top of notebook",
                meta: "Lands in the first chapter",
                action: onMoveToTop
            )

            if isLoadingChapters {
                HStack {
                    Spacer()
                    ProgressView().tint(AppColors.themeGreenPrimary)
                    Spacer()
                }
                .padding(.vertical, AppSpacing.s3)
            } else if chapters.isEmpty {
                Text("No chapters in this notebook yet.")
                    .font(AppText.meta)
                    .foregroundStyle(AppColors.textTertiary)
                    .padding(.horizontal, AppSpacing.s3 + 32 + AppSpacing.s3)
                    .padding(.vertical, AppSpacing.s3)
            } else {
                ForEach(chapters) { chapter in
                    Rectangle()
                        .fill(AppColors.borderDefault.opacity(0.5))
                        .frame(height: 0.5)
                        .padding(.leading, AppSpacing.s3 + 32 + AppSpacing.s3)
                    chapterRow(
                        title: chapter.title,
                        meta: "\(chapter.pages.count) page\(chapter.pages.count == 1 ? "" : "s")",
                        action: { onSelectChapter(chapter.id) }
                    )
                }
            }
        }
    }

    private func chapterRow(
        title: String,
        meta: String,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(alignment: .center, spacing: AppSpacing.s3) {
                Image(systemName: "tray.and.arrow.down")
                    .font(.system(size: 13))
                    .foregroundStyle(AppColors.themeGreenDeep)
                    .frame(width: 32)
                VStack(alignment: .leading, spacing: 1) {
                    Text(title)
                        .font(AppText.body)
                        .foregroundStyle(AppColors.textPrimary)
                        .lineLimit(1)
                    Text(meta)
                        .font(AppText.tag)
                        .foregroundStyle(AppColors.textTertiary)
                }
                Spacer(minLength: AppSpacing.s2)
                Image(systemName: "chevron.right")
                    .font(.system(size: 12))
                    .foregroundStyle(AppColors.textTertiary)
            }
            .padding(.horizontal, AppSpacing.s3)
            .padding(.vertical, AppSpacing.s2)
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// Small rounded square keyed off the notebook's color token. Falls
/// back to a neutral chip for unknown tokens. Same lookup the
/// shelves UI uses, kept inline so the picker doesn't pull in the
/// shelf-theme machinery for one chip.
private struct ColorChip: View {
    let token: String?

    var body: some View {
        let palette = ShelfTheme.palette(for: token)
        RoundedRectangle(cornerRadius: AppRadius.sm, style: .continuous)
            .fill(palette.background)
    }
}

/// Real Apply-template picker. Renders the curated set of
/// `viewModel.availableTemplates` as a vertical list of rows, each
/// showing the template's icon (mapped through `ShelfTheme`), name,
/// description, and a small "summary" line that surfaces what the
/// template will add (e.g. "3 to-dos · 1 note"). A spinner row
/// shows while the list is loading.
private struct ApplyTemplateSheet: View {
    let templates: [PageTemplate]
    let isLoading: Bool
    let onSelect: (String) -> Void

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: AppSpacing.s3) {
                Text("TEMPLATE")
                    .font(AppText.eyebrow)
                    .tracking(AppLetterSpacing.eyebrow)
                    .foregroundStyle(AppColors.themeGreenDeep)
                Text("apply a template")
                    .font(.system(size: 28, weight: .regular, design: .serif))
                    .foregroundStyle(AppColors.textPrimary)
                Text("Pick a scaffold to add to this page. Template content concats onto your current notes and to-dos — nothing gets overwritten.")
                    .font(AppText.meta)
                    .foregroundStyle(AppColors.textSecondary)
                    .padding(.bottom, AppSpacing.s2)

                if isLoading && templates.isEmpty {
                    HStack {
                        Spacer()
                        ProgressView().tint(AppColors.themeGreenPrimary)
                        Spacer()
                    }
                    .padding(.vertical, AppSpacing.s5)
                } else if templates.isEmpty {
                    Text("No templates yet.")
                        .font(AppText.body)
                        .foregroundStyle(AppColors.textTertiary)
                        .padding(.vertical, AppSpacing.s4)
                } else {
                    VStack(spacing: AppSpacing.s2) {
                        ForEach(templates) { template in
                            TemplatePickerRow(template: template) {
                                onSelect(template.id)
                            }
                        }
                    }
                }

                Spacer(minLength: AppSpacing.s4)
                AppButton("Cancel", variant: .secondary) { dismiss() }
                    .padding(.top, AppSpacing.s2)
            }
            .padding(.horizontal, AppSpacing.s5)
            .padding(.vertical, AppSpacing.s5)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .background(AppColors.cardSolid.ignoresSafeArea())
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }
}

/// One row inside the Apply-template picker. Shows a small green
/// icon chip with the template's SF Symbol (looked up through the
/// existing `ShelfTheme` icon registry), the title, the one-line
/// description, and a small green "summary" tag — e.g. "3 to-dos ·
/// 1 note" or "blank scaffold" — so users see what they're getting
/// before committing.
private struct TemplatePickerRow: View {
    let template: PageTemplate
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(alignment: .top, spacing: AppSpacing.s3) {
                templateIcon
                VStack(alignment: .leading, spacing: 2) {
                    Text(template.title)
                        .font(AppText.button)
                        .foregroundStyle(AppColors.textPrimary)
                    Text(template.description)
                        .font(AppText.meta)
                        .foregroundStyle(AppColors.textSecondary)
                        .lineLimit(2)
                    Text(template.summary)
                        .font(AppText.tag)
                        .foregroundStyle(AppColors.greenText)
                        .padding(.top, 2)
                }
                Spacer(minLength: AppSpacing.s2)
                Image(systemName: "chevron.right")
                    .font(.system(size: 13))
                    .foregroundStyle(AppColors.textTertiary)
                    .padding(.top, AppSpacing.s1)
            }
            .padding(.horizontal, AppSpacing.s3)
            .padding(.vertical, AppSpacing.s3)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(
                RoundedRectangle(cornerRadius: AppRadius.md, style: .continuous)
                    .fill(AppColors.canvas)
            )
            .overlay(
                RoundedRectangle(cornerRadius: AppRadius.md, style: .continuous)
                    .stroke(AppColors.borderDefault, lineWidth: 1)
            )
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityHint("Apply \(template.title)")
    }

    private var templateIcon: some View {
        ZStack {
            RoundedRectangle(cornerRadius: AppRadius.sm, style: .continuous)
                .fill(AppColors.themeGreenPrimary.opacity(0.18))
            Image(systemName: ShelfTheme.iconSystemName(for: template.iconKey))
                .font(.system(size: 16))
                .foregroundColor(AppColors.themeGreenDeep)
        }
        .frame(width: 32, height: 32)
    }
}

/// Tall-but-empty bottom sheet with the page-style chrome. Kept as
/// the fallback shape for any future action whose picker hasn't been
/// designed yet. Currently unused — both Move-to-notebook and
/// Apply-template have real pickers above.
private struct PlaceholderPickerSheet: View {
    @Environment(\.dismiss) private var dismiss
    let title: String
    let eyebrow: String
    let copy: String

    var body: some View {
        VStack(alignment: .leading, spacing: AppSpacing.s3) {
            Text(eyebrow)
                .font(AppText.eyebrow)
                .tracking(AppLetterSpacing.eyebrow)
                .foregroundStyle(AppColors.themeGreenDeep)
            Text(title.lowercased())
                .font(.system(size: 28, weight: .regular, design: .serif))
                .foregroundStyle(AppColors.textPrimary)
            Text(copy)
                .font(AppText.body)
                .foregroundStyle(AppColors.textSecondary)
                .padding(.top, AppSpacing.s2)
            Spacer()
            AppButton("Close", variant: .primary) { dismiss() }
        }
        .padding(.horizontal, AppSpacing.s5)
        .padding(.vertical, AppSpacing.s5)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(AppColors.cardSolid.ignoresSafeArea())
        .presentationDetents([.medium])
        .presentationDragIndicator(.visible)
    }
}

/// Brief auto-dismissing pill at the top of the screen. Plain
/// surface, hairline border, serif body so it doesn't read as a
/// system-Material toast. Bound to `viewModel.toast` and cleared
/// after a 2.4s display. Optional `actionLabel` + `onAction` add a
/// trailing pill (e.g. "Undo" after archive) so the user can act
/// on the toast without chasing a separate menu.
private struct ToastView: View {
    let message: String
    let actionLabel: String?
    let onAction: (() -> Void)?

    init(message: String, actionLabel: String? = nil, onAction: (() -> Void)? = nil) {
        self.message = message
        self.actionLabel = actionLabel
        self.onAction = onAction
    }

    var body: some View {
        HStack(spacing: AppSpacing.s3) {
            Text(message)
                .font(.system(size: 14, weight: .regular, design: .serif))
                .foregroundStyle(AppColors.textPrimary)
            if let label = actionLabel, let onAction {
                Button(action: onAction) {
                    Text(label.uppercased())
                        .font(AppText.tag)
                        .foregroundStyle(AppColors.coral)
                        .padding(.horizontal, AppSpacing.s2)
                        .padding(.vertical, 2)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text(label))
            }
        }
        .padding(.horizontal, AppSpacing.s4)
        .padding(.vertical, AppSpacing.s2)
        .background(AppColors.cardSolid)
        .overlay(
            Capsule().stroke(AppColors.borderDefault, lineWidth: 1)
        )
        .clipShape(Capsule())
        .appShadow(.md)
    }
}

// MARK: - Empty state

private struct EmptyState: View {
    let message: String
    var body: some View {
        HStack {
            Spacer()
            Text(message)
                .font(AppText.body)
                .foregroundStyle(AppColors.textTertiary)
                .padding(.vertical, AppSpacing.s6)
            Spacer()
        }
    }
}

#Preview("Re-Leaf overview · green palette") {
    NavigationStack {
        PageDetailView(pageId: "pg-1")
            .accentPalette(AccentPalettes.green)
    }
}

#Preview("Re-Leaf overview · default palette") {
    NavigationStack {
        PageDetailView(pageId: "pg-1")
    }
}
