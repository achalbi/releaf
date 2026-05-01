/*
 * NotepadEditorScreen.swift
 *
 * Single-entry editor with an edit ⇄ preview toggle. Parity with Android's
 * NotepadEditorScreen — same five feature sections below the body
 * (photos, scans, contacts, todos, location) so the two surfaces feel
 * identical.
 *
 * - Edit mode: a plain-text TextField (title) + WYSIWYG `RichTextEditor`
 *   (notes) with a bold/italic/underline toolbar pinned at the bottom,
 *   plus the five `EditorSections` rendered below. Notes round-trip
 *   through canonical CommonMark (per schema) — the editor shows
 *   formatting inline while the on-disk string stays `**bold**`/`*italic*`.
 * - Preview mode: title renders inline as a serif PageTitle heading; the
 *   body is split into paragraph blocks and each block goes through
 *   `AttributedString(markdown:)` so **bold**, *italic*, `code`, and
 *   links render.
 *
 * Save semantics:
 *   - VM.save() is idempotent. Back tap fires it, then pops; onDisappear
 *     fires it again, which no-ops because the snapshot matches.
 *   - DELETE soft-deletes via the repo and calls dismiss() to pop.
 *
 * Composition: the outer `NotepadEditorScreen` reads the signed-in user
 * from the environment AuthStore and hands the id to a private inner
 * view that owns the `NotepadEditorViewModel`. Splitting lets the VM's
 * `@StateObject` capture a real user id at init time.
 *
 * Preview-toggle state is UI-only and lives in `@State` — no reason to
 * bloat the VM with it.
 */

import SwiftUI
import ReleafDesignSystem
import ReleafData

public struct NotepadEditorScreen: View {
    @EnvironmentObject private var authStore: AuthStore

    /// `entryId` is either a UUIDv7 or the sentinel
    /// `NotepadEditorViewModel.newEntryId` for a brand-new draft.
    private let entryId: String
    private let repository: NotepadRepository
    /// Optional capture mode the screen should focus on opening — when
    /// set, the editor opens in edit mode and scrolls to the matching
    /// feature section (Photos / Scans / Voice / Todo / Contacts /
    /// Location). Drives the "open page details at the right tab"
    /// affordance from the Recents new-entry picker.
    private let initialMode: CaptureMode?

    public init(
        entryId: String,
        repository: NotepadRepository = NotepadRepository(),
        initialMode: CaptureMode? = nil
    ) {
        self.entryId = entryId
        self.repository = repository
        self.initialMode = initialMode
    }

    public var body: some View {
        Group {
            if let session = authStore.session {
                EditorContent(
                    entryId: entryId,
                    repository: repository,
                    userId: session.userId,
                    initialMode: initialMode
                )
            } else {
                // Defensive — the list view gates on signed-in before
                // pushing the editor route, so this branch should be
                // unreachable in production.
                EmptyView()
            }
        }
        // Notebook-feel canvas — cream fill + 24pt dot-grid texture
        // behind all editor content. Specs from the Releaf Branding
        // template (matches the Android `DotGridBackground`
        // composable). `ignoresSafeArea` so the pattern reads through
        // the status bar area too.
        .background(DotGridBackground().ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .hidesBottomBar()
    }
}

// MARK: - Inner content (owns the VM)

private struct EditorContent: View {
    @Environment(\.dismiss) private var dismiss
    @StateObject private var vm: NotepadEditorViewModel
    @StateObject private var richTextController = RichTextEditorController()
    // Overview (grid) is the default — parity with Android and matches
    // the PageDetailView design on the Home tab. Users flip to the list
    // icon when they want the single-scroll rich-text editor.
    @State private var editorMode: EditorMode = .overview
    @State private var showDeleteDialog: Bool = false
    @State private var showMergeSheet: Bool = false
    @State private var showMoveSheet: Bool = false
    /// Daily-plant info sheet — opened on tap of the composed-header
    /// title block. Mirrors the same affordance on PageDetailView so
    /// the editorial plant rotation is reachable from both editors.
    @State private var showPlantInfo: Bool = false
    /// Category picker sheet — opened by the chip below the title.
    @State private var showCategoryPicker: Bool = false

    /// Today's rotated plant. Pulled once at init; the rotation is
    /// per-day deterministic so a stored let is fine for the screen's
    /// lifetime.
    private let plant: DailyPlant = DailyPlants.forToday()

    /// Optional capture mode delivered from the route layer. When set,
    /// `editorMode` initialises to `.edit` and the EditorBody scrolls
    /// to the matching section once content loads.
    private let initialMode: CaptureMode?

    init(
        entryId: String,
        repository: NotepadRepository,
        userId: String,
        initialMode: CaptureMode? = nil
    ) {
        _vm = StateObject(wrappedValue: NotepadEditorViewModel(
            repository: repository,
            entryId: entryId,
            userId: userId
        ))
        // Editor opens in Overview (grid) by default — that's the
        // expected entry point. `initialMode` is threaded into the
        // OverviewPane so the matching CaptureTabBar tab is the
        // initially-selected one.
        self.initialMode = initialMode
    }

    var body: some View {
        VStack(spacing: 0) {
            topBar

            // Title + date chip share one row at screen level: title
            // on the left (expanding), date chip flush right (directly
            // under the Delete button). Living outside the mode
            // branches keeps the title sticky above both Edit and
            // Overview content.
            if !vm.isLoading {
                HStack(alignment: .center, spacing: AppSpacing.s3) {
                    TitleField(
                        value: Binding(get: { vm.title }, set: { vm.title = $0 })
                    )
                    .frame(maxWidth: .infinity, alignment: .leading)
                    EntryDateRow(
                        entryDate: Binding(get: { vm.entryDate }, set: { vm.entryDate = $0 })
                    )
                }
                .padding(.horizontal, AppSpacing.s4)
                .padding(.bottom, AppSpacing.s2)

                // Category chip — sits directly below the title row.
                // Tap opens the picker sheet; the chip itself shows
                // the current category (predefined or custom) or an
                // "Add category" placeholder when uncategorised.
                CategoryChip(
                    category: vm.category,
                    onTap:    { showCategoryPicker = true }
                )
                .padding(.horizontal, AppSpacing.s4)
                .padding(.bottom, AppSpacing.s3)
            }

            if vm.isLoading {
                Spacer()
                ProgressView().tint(AppColors.coral)
                Spacer()
            } else if editorMode == .overview {
                overviewPane
            } else {
                EditorBody(vm: vm, richTextController: richTextController)
                // Toolbar sits at the bottom edge — SwiftUI's default
                // keyboard avoidance pushes it above the keyboard when
                // the user's typing in the rich-text field.
                RichTextFormatBar(controller: richTextController)
            }
        }
        // `simultaneousGesture` runs alongside child gestures without
        // eating them — taps on buttons / fields still work, but any
        // tap anywhere on the screen also blurs whatever text field
        // is currently first responder. Without this the title kept
        // its blinking caret after the user moved on to something
        // non-focusable (section card, scroll padding).
        .simultaneousGesture(
            TapGesture().onEnded {
                UIApplication.shared.sendAction(
                    #selector(UIResponder.resignFirstResponder),
                    to: nil, from: nil, for: nil
                )
            }
        )
        .task { await vm.bootstrap() }
        .onDisappear { vm.save() }
        .alert("Delete this entry?", isPresented: $showDeleteDialog) {
            Button("Cancel", role: .cancel) { }
            Button("Delete", role: .destructive) {
                vm.delete { dismiss() }
            }
        } message: {
            Text("It'll move to the trash and stop showing in the list. You can still undo this from the list screen.")
        }
        // Top-bar entry point for Merge pages. Hosts the same
        // MergeSection shown inline at the end of the editor so the
        // action is reachable without scrolling. Dismisses by popping
        // back on success (the entry is either refreshed or the
        // secondary was soft-deleted — either way the list is the
        // right place to land).
        .sheet(isPresented: $showMergeSheet) {
            NavigationStack {
                ScrollView {
                    MergeSection(
                        loadOtherEntries: { await vm.loadOtherEntries() },
                        enabled: vm.canSave || vm.entry != nil,
                        onMerge: { otherId, keepThisAsPrimary in
                            vm.merge(
                                otherId: otherId,
                                keepThisAsPrimary: keepThisAsPrimary
                            ) { _ in
                                showMergeSheet = false
                                dismiss()
                            }
                        }
                    )
                    .padding(AppSpacing.s4)
                }
                .background(AppColors.canvas)
                .navigationTitle("Merge pages")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Close") { showMergeSheet = false }
                    }
                }
            }
        }
        // Top-bar entry point for Move-to-notebook. Mirrors the merge
        // sheet — wraps whatever "Move" UI the notepad editor already
        // exposes. For now we delegate to the inline MoveToNotebook
        // surface inside OverviewPane; if a dedicated Section ships
        // later we can host it directly here, same as Merge.
        .sheet(isPresented: $showMoveSheet) {
            NavigationStack {
                VStack(spacing: AppSpacing.s4) {
                    Text("Move to notebook")
                        .font(AppText.sectionTitle)
                        .foregroundStyle(AppColors.textPrimary)
                    Text("This action is still in progress on iOS. Use the Android build for now, or tap Close to return.")
                        .font(AppText.body)
                        .foregroundStyle(AppColors.textSecondary)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, AppSpacing.s6)
                    Spacer()
                }
                .padding(.top, AppSpacing.s8)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(AppColors.canvas)
                .navigationTitle("Move to notebook")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Close") { showMoveSheet = false }
                    }
                }
            }
        }
        // Plant-of-the-day info sheet. Surfaced by tapping the title
        // block in the composed top zone. Same shape as the equivalent
        // sheet on PageDetailView so the editorial layer reads
        // consistently across both editors.
        .sheet(isPresented: $showPlantInfo) {
            NotepadDailyPlantInfoSheet(
                plant: plant,
                onClose: { showPlantInfo = false }
            )
        }
        // Category picker sheet — opened by the CategoryChip below the
        // title row. Predefined chips + a free-form text field; Clear
        // wipes back to nil (uncategorised).
        .sheet(isPresented: $showCategoryPicker) {
            CategoryPickerSheet(
                current: vm.category,
                onPick: { picked in
                    let trimmed = picked?
                        .trimmingCharacters(in: .whitespacesAndNewlines)
                    vm.category = (trimmed?.isEmpty == false) ? trimmed : nil
                    showCategoryPicker = false
                },
                onCancel: { showCategoryPicker = false }
            )
        }
    }

    // MARK: Overview

    private var overviewPane: some View {
        OverviewPane(
            notes:              Binding(get: { vm.notes }, set: { vm.notes = $0 }),
            richTextController: richTextController,
            contacts:           vm.contacts,
            todos:              vm.todos,
            locations:          vm.locations,
            attachments:        vm.attachments,
            onAddContact:       { name in vm.addContact(name: name) },
            onRemoveContact:    { id in vm.removeContact(id: id) },
            onAddTodo:          { text in vm.addTodo(text: text) },
            onToggleTodo:       { id in vm.toggleTodo(id: id) },
            onRemoveTodo:       { id in vm.removeTodo(id: id) },
            onAddLocation:      { lat, lng, address in vm.addLocation(lat: lat, lng: lng, address: address) },
            onRemoveLocation:   { id in vm.removeLocation(id: id) },
            onAddPhoto:         { uri in vm.addAttachment(type: Attachment.typePhoto, uri: uri) },
            onAddScan:          { uri, preview in
                vm.addAttachment(type: Attachment.typeScan, uri: uri, previewUri: preview)
            },
            onAddVoiceNote:     { uri, durationMs in
                vm.addVoiceNote(uri: uri, durationMs: durationMs)
            },
            onTranscribeVoiceNote: { uri, transcript in
                vm.updateVoiceTranscript(uri: uri, transcript: transcript)
            },
            onRemoveAttachment: { id in vm.removeAttachment(id: id) },
            // Forwarded from the route layer — when the Recents
            // new-entry picker opened this editor with a specific
            // mode, OverviewPane lands on the matching CaptureTabBar
            // tab. Defaults to .overview when nil.
            initialSelected: initialMode
        )
    }

    // MARK: Top bar (composed top zone)
    //
    // Slot 1: leaf-glyph eyebrow ("NOTEPAD · TODAY"), tappable as Back —
    //         flushes the rich-text buffer + saves before popping so the
    //         tap behaves identically to the prior Breadcrumbs "Notepad"
    //         segment.
    // Slot 2: PageViewToggle pill — the visual matches PageDetailView.
    //         Since the notepad's existing modes are Edit ⇄ Overview
    //         (not List ⇄ Grid), the binding translates one to the
    //         other (Overview ⇄ Grid, Edit ⇄ List) so the body still
    //         flips between EditorBody and OverviewPane exactly as
    //         before — only the chrome around the toggle changed.
    // Slot 3: PageOverflowButton — gated on the same "there's something
    //         to act on" check the prior Menu used. Holds Merge / Move
    //         and folds the prior inline Delete into the same menu so
    //         the trailing edge has a single round button.
    // Slot 4: Auto-rotated daily plant title; tap opens the info sheet.

    private var topBar: some View {
        VStack(alignment: .leading, spacing: AppSpacing.s4) {
            HStack(alignment: .center) {
                LeafEyebrow(
                    "notepad · today",
                    onTap: {
                        flushBeforeExit()
                        vm.save()
                        dismiss()
                    }
                )
                Spacer()
                HStack(spacing: AppSpacing.s2) {
                    PageViewToggle(selected: editorModeAsViewMode)
                    if vm.canSave || vm.entry != nil {
                        PageOverflowButton {
                            Button {
                                showMergeSheet = true
                            } label: { Label("Merge with another page", systemImage: "rectangle.on.rectangle") }
                            Button {
                                showMoveSheet = true
                            } label: { Label("Move to notebook", systemImage: "tray.and.arrow.up") }
                            if vm.entry != nil {
                                Divider()
                                Button(role: .destructive) {
                                    // Confirmation guard — the actual
                                    // soft-delete runs only after the
                                    // user confirms in the alert.
                                    showDeleteDialog = true
                                } label: { Label("Delete entry", systemImage: "trash") }
                            }
                        }
                    }
                }
            }

            // Tappable plant title block. Sanskrit name (32pt serif)
            // + English parenthetical (16pt serif italic muted) share
            // a baseline via Text concatenation. Italic subtitle below
            // carries epithet · usedFor. Whole block is the hit target
            // so a tap anywhere opens the info sheet.
            Button {
                showPlantInfo = true
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
        }
        .padding(.horizontal, AppSpacing.s4)
        .padding(.top, AppSpacing.s4)
        .padding(.bottom, AppSpacing.s3)
    }

    /// Translates the notepad's `EditorMode` to the PageViewToggle's
    /// `PageViewMode` and back. Lets the toggle's visual match the
    /// page-detail header pill while the underlying body switch
    /// stays exactly as it was (`overview` ↔ `grid`, `edit` ↔ `list`).
    private var editorModeAsViewMode: Binding<PageViewMode> {
        Binding(
            get: { editorMode == .overview ? .grid : .list },
            set: { editorMode = ($0 == .grid) ? .overview : .edit }
        )
    }

    /// Title row rendered as one Text composition so the Sanskrit
    /// name and the parenthetical English share a baseline. Sanskrit
    /// at 32pt serif, English at 16pt serif italic muted — same
    /// treatment used by `PageDetailView.titleLine(plant:)` so both
    /// editor surfaces render the day's title identically.
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

    /// Push the latest rich-text markdown into the VM before popping.
    /// Both editor modes bind the same controller's `UITextView`, so
    /// this is mode-agnostic — whichever mode the user was last in
    /// holds the freshest buffer.
    private func flushBeforeExit() {
        // `serializedMarkdown()` returns nil when no UITextView is wired
        // (e.g. the editor was never mounted, or we're on the macOS
        // stub) — leave `vm.notes` as-is in that case.
        if let latest = richTextController.serializedMarkdown() {
            vm.notes = latest
        }
    }
}

// MARK: - Editor body (rich-text body + feature sections)

private struct EditorBody: View {
    @ObservedObject var vm: NotepadEditorViewModel
    @ObservedObject var richTextController: RichTextEditorController
    // Used by the merge action below (and any future "pop after
    // mutation" CTA in this body). Lives here on the inner view so
    // it's resolved against the same NavigationStack the screen-level
    // dismiss reads from.
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: AppSpacing.s4) {
                // Title and date both live at screen level now (above
                // this scroll) so they stay sticky while the body
                // scrolls. The body starts straight at the notes.
                NotesField(
                    markdown: Binding(get: { vm.notes }, set: { vm.notes = $0 }),
                    controller: richTextController
                )

                // Feature sections — parity with Android. Each owns its own
                // capture flow (PhotosPicker, CoreLocation, VNDocumentCameraViewController)
                // and calls back into the VM for the actual state changes.
                PhotosSection(
                    photos:   vm.attachments.filter { $0.type == Attachment.typePhoto },
                    onAdd:    { uri in vm.addAttachment(type: Attachment.typePhoto, uri: uri) },
                    onRemove: { id in vm.removeAttachment(id: id) }
                )
                ScansSection(
                    scans:    vm.attachments.filter { $0.type == Attachment.typeScan },
                    onAdd:    { uri, preview in
                        vm.addAttachment(type: Attachment.typeScan, uri: uri, previewUri: preview)
                    },
                    onRemove: { id in vm.removeAttachment(id: id) }
                )
                VoiceSection(
                    notes:         vm.attachments.filter { $0.type == Attachment.typeVoice },
                    onAdd:         { uri, durationMs in
                        vm.addVoiceNote(uri: uri, durationMs: durationMs)
                    },
                    onTranscribed: { uri, transcript in
                        vm.updateVoiceTranscript(uri: uri, transcript: transcript)
                    },
                    onRemove:      { id in vm.removeAttachment(id: id) }
                )
                ContactsSection(
                    contacts: vm.contacts,
                    onAdd:    { name in vm.addContact(name: name) },
                    onRemove: { id in vm.removeContact(id: id) }
                )
                TodosSection(
                    todos:    vm.todos,
                    onAdd:    { text in vm.addTodo(text: text) },
                    onToggle: { id in vm.toggleTodo(id: id) },
                    onRemove: { id in vm.removeTodo(id: id) }
                )
                LocationSection(
                    locations: vm.locations,
                    onAdd:     { lat, lng, address in vm.addLocation(lat: lat, lng: lng, address: address) },
                    onRemove:  { id in vm.removeLocation(id: id) }
                )
                MergeSection(
                    loadOtherEntries: { await vm.loadOtherEntries() },
                    // Merging requires something to merge from. A fresh,
                    // untouched draft has nothing to hand over — disable
                    // the CTA until the user's typed (or attached)
                    // anything. The VM's merge() also flushes first.
                    enabled: vm.canSave || vm.entry != nil,
                    onMerge: { otherId, keepThisAsPrimary in
                        vm.merge(
                            otherId: otherId,
                            keepThisAsPrimary: keepThisAsPrimary
                        ) { _ in
                            // Either this page was the secondary (now
                            // soft-deleted) or the primary (safe to
                            // re-open from the list); pop back either
                            // way to let the list refresh.
                            dismiss()
                        }
                    }
                )

                // Bottom clearance so the keyboard doesn't hide the last
                // line of the body.
                Color.clear.frame(height: AppSpacing.s10)
            }
            .padding(.horizontal, AppSpacing.s4)
        }
    }
}

private struct TitleField: View {
    @Binding var value: String

    // Oversized serif so the title reads as the primary heading on the
    // screen. Uses the same weight + design as `pageTitle` so the
    // typography still feels cohesive — only the size grows.
    private static let big = Font.system(size: 32, design: .serif)

    var body: some View {
        // Placeholder shown via `prompt` so cursor positioning is native.
        TextField(
            "",
            text: $value,
            prompt: Text("Title").foregroundColor(AppColors.textTertiary)
        )
        .font(Self.big)
        .foregroundStyle(AppColors.textPrimary)
        .tint(AppColors.coral)
        .textInputAutocapitalization(.sentences)
        .submitLabel(.next)
    }
}

/// Rich-text body. Wraps `RichTextEditor` (UIViewRepresentable) with a
/// placeholder overlay so the editor still feels like the rest of the
/// form. The RichTextEditor has its inner scroll disabled and expands
/// to content height, so the outer ScrollView handles scrolling.
private struct NotesField: View {
    @Binding var markdown: String
    @ObservedObject var controller: RichTextEditorController

    var body: some View {
        ZStack(alignment: .topLeading) {
            if markdown.isEmpty {
                Text("Start typing…")
                    .font(AppText.body)
                    .foregroundStyle(AppColors.textTertiary)
                    .allowsHitTesting(false)
            }
            RichTextEditor(
                markdown: $markdown,
                controller: controller,
                tintColor: AppColors.coral
            )
            .frame(minHeight: 240, alignment: .top)
        }
    }
}

// MARK: - Preview body (markdown render)

private struct PreviewBody: View {
    let title: String
    let notes: String

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: AppSpacing.s3) {
                Text("NOTEPAD")
                    .font(AppText.eyebrow)
                    .tracking(AppLetterSpacing.eyebrow)
                    .foregroundStyle(AppColors.coral)

                if !title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    Text(title)
                        .font(AppText.pageTitle)
                        .foregroundStyle(AppColors.textPrimary)
                }

                if notes.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    Text("Nothing to preview yet.")
                        .font(AppText.body)
                        .foregroundStyle(AppColors.textTertiary)
                } else {
                    MarkdownBody(notes: notes)
                }

                Color.clear.frame(height: AppSpacing.s10)
            }
            .padding(.horizontal, AppSpacing.s4)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

/// Lightweight markdown renderer. Splits on blank lines into blocks, then
/// hands each block to `AttributedString(markdown:)` with inline-only
/// interpretation so **bold**, *italic*, `code`, and links render while
/// preserving intra-paragraph newlines.
///
/// Full block rendering (headings, lists) is a known gap — Android uses
/// mikepenz/Markdown which does structural rendering. Tracking as a
/// follow-up rather than pulling a dependency into iOS right now.
private struct MarkdownBody: View {
    let notes: String

    var body: some View {
        VStack(alignment: .leading, spacing: AppSpacing.s3) {
            ForEach(Array(blocks.enumerated()), id: \.offset) { _, block in
                Text(attributed(block))
                    .font(AppText.body)
                    .foregroundStyle(AppColors.textPrimary)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
    }

    private var blocks: [String] {
        notes
            .components(separatedBy: "\n\n")
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
    }

    private func attributed(_ raw: String) -> AttributedString {
        var options = AttributedString.MarkdownParsingOptions()
        // Preserve whitespace so intra-paragraph line breaks render.
        options.interpretedSyntax = .inlineOnlyPreservingWhitespace
        if let parsed = try? AttributedString(markdown: raw, options: options) {
            return parsed
        }
        return AttributedString(raw)
    }
}

// MARK: - Daily plant info sheet
//
// Bottom-sheet that explains the day's rotated plant. Reached by
// tapping the title block in the composed top zone. Same shape and
// copy as the equivalent sheet on PageDetailView so the editorial
// surface reads identically across both editors.

private struct NotepadDailyPlantInfoSheet: View {
    let plant: DailyPlant
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
                NotepadInfoBlock(title: "EPITHET",          copy: plant.epithet)
                NotepadInfoBlock(title: "TRADITIONAL USES", copy: plant.usedFor)
            }

            Spacer(minLength: AppSpacing.s4)

            AppButton("Close", variant: .secondary, action: onClose)
                .frame(maxWidth: .infinity)
        }
        .padding(.horizontal, AppSpacing.s5)
        .padding(.vertical, AppSpacing.s5)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(AppColors.cardSolid.ignoresSafeArea())
        .presentationDetents([.medium])
        .presentationDragIndicator(.visible)
    }
}

private struct NotepadInfoBlock: View {
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

// MARK: - Category chip + picker

/// Compact pill that surfaces the entry's current category. Predefined
/// names get the green-soft accent; custom user-typed names get a
/// neutral tint so the predefined set reads as the "official" options
/// at a glance. Tapping the pill opens [CategoryPickerSheet] via the
/// containing screen's `showCategoryPicker` state.
private struct CategoryChip: View {
    let category: String?
    let onTap: () -> Void

    var body: some View {
        let display = NotepadCategory.displayName(category)
        // Custom and predefined chips share the same green-soft
        // styling — the user wanted both kinds to read identically
        // rather than predefined looking "official" and customs
        // faded.
        let bg: Color = (display == nil) ? AppColors.cardSolid    : AppColors.greenSoft
        let fg: Color = (display == nil) ? AppColors.textTertiary : AppColors.themeGreenDeep
        Button(action: onTap) {
            HStack(spacing: AppSpacing.s1) {
                Image(systemName: "tag.fill")
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(fg)
                Text(display ?? "Add category")
                    .font(AppText.meta)
                    .foregroundStyle(fg)
            }
            .padding(.horizontal, AppSpacing.s3)
            .padding(.vertical, 6)
            .background(
                RoundedRectangle(cornerRadius: AppSpacing.s3, style: .continuous)
                    .fill(bg)
            )
        }
        .buttonStyle(.plain)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// Category picker sheet. Predefined categories show as a wrapping
/// chip row at the top; below sits a free-form text field so the
/// user can type a custom category. Clear wipes back to nil
/// (uncategorised); Save commits the typed string. Tapping any
/// predefined chip commits and dismisses immediately.
private struct CategoryPickerSheet: View {
    @EnvironmentObject private var uiPrefs: UiPreferences

    let current: String?
    let onPick: (String?) -> Void
    let onCancel: () -> Void

    @State private var customText: String

    init(current: String?, onPick: @escaping (String?) -> Void, onCancel: @escaping () -> Void) {
        self.current = current
        self.onPick = onPick
        self.onCancel = onCancel
        // Pre-fill the text field with the current category iff it's
        // a custom (non-predefined) value — so the user lands on
        // edit-and-save rather than retyping the existing label.
        let canonical = NotepadCategory.displayName(current)
        if let canonical, !NotepadCategory.isPredefined(canonical) {
            _customText = State(initialValue: canonical)
        } else {
            _customText = State(initialValue: "")
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: AppSpacing.s4) {
            HStack {
                Text("Choose category")
                    .font(.system(size: 18, weight: .semibold, design: .serif))
                    .foregroundStyle(AppColors.textPrimary)
                Spacer()
                Button("Cancel", action: onCancel)
                    .foregroundStyle(AppColors.textSecondary)
            }

            // Wrapping chip row of the predefined categories, in the
            // user's preferred display order (Settings → Categories).
            // Tap commits and dismisses. Active chip uses the coral
            // accent so the current selection reads at a glance.
            FlowChips(
                items:    NotepadCategory.applyOrder(
                    userOrder: uiPrefs.state.notepadCategoryOrder,
                    customs:   []
                ),
                selected: NotepadCategory.displayName(current),
                onTap:    { onPick($0) }
            )

            // Custom-category input. Commits on Save (tap or keyboard
            // submit). Trimmed-empty leaves the current value unchanged
            // — use Clear to deliberately uncategorise.
            TextField("Custom category — e.g. Garden, Recipes", text: $customText)
                .textFieldStyle(.roundedBorder)
                .submitLabel(.done)
                .onSubmit {
                    let trimmed = customText.trimmingCharacters(in: .whitespacesAndNewlines)
                    if !trimmed.isEmpty { onPick(trimmed) }
                }

            HStack(spacing: AppSpacing.s3) {
                if NotepadCategory.displayName(current) != nil {
                    Button("Clear", action: { onPick(nil) })
                        .foregroundStyle(AppColors.danger)
                }
                Spacer()
                Button("Save") {
                    let trimmed = customText.trimmingCharacters(in: .whitespacesAndNewlines)
                    if !trimmed.isEmpty { onPick(trimmed) } else { onCancel() }
                }
                .disabled(customText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                .foregroundStyle(AppColors.coral)
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

/// Wrapping chip layout used by the picker sheet. SwiftUI doesn't
/// ship a built-in flow layout on iOS 16, so we use the standard
/// HStack-of-rows pattern via a Layout that wraps when overflow.
/// Implementation here is intentionally minimal — a horizontally
/// scrollable HStack — because the picker only carries 6 predefined
/// chips. If a custom-categories chip row joins later, swap this for
/// a real flow layout.
private struct FlowChips: View {
    let items: [String]
    let selected: String?
    let onTap: (String) -> Void

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: AppSpacing.s2) {
                ForEach(items, id: \.self) { name in
                    let active = selected.map {
                        $0.caseInsensitiveCompare(name) == .orderedSame
                    } ?? false
                    let bg = active ? AppColors.themeGreenDeep : AppColors.greenSoft
                    let fg = active ? Color.white : AppColors.themeGreenDeep
                    Button(action: { onTap(name) }) {
                        Text(name)
                            .font(AppText.meta)
                            .foregroundStyle(fg)
                            .padding(.horizontal, AppSpacing.s3)
                            .padding(.vertical, 6)
                            .background(
                                RoundedRectangle(cornerRadius: AppSpacing.s3, style: .continuous)
                                    .fill(bg)
                            )
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }
}

#Preview {
    NavigationStack {
        NotepadEditorScreen(
            entryId: NotepadEditorViewModel.newEntryId,
            repository: NotepadRepository(database: ReleafDatabase(inMemory: true))
        )
    }
    .environmentObject(AuthStore(client: StubGoogleAuthClient()))
}
