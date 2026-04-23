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

    public init(
        entryId: String,
        repository: NotepadRepository = NotepadRepository()
    ) {
        self.entryId = entryId
        self.repository = repository
    }

    public var body: some View {
        Group {
            if let session = authStore.session {
                EditorContent(
                    entryId: entryId,
                    repository: repository,
                    userId: session.userId
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

    init(entryId: String, repository: NotepadRepository, userId: String) {
        _vm = StateObject(wrappedValue: NotepadEditorViewModel(
            repository: repository,
            entryId: entryId,
            userId: userId
        ))
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
            onRemoveAttachment: { id in vm.removeAttachment(id: id) }
        )
    }

    // MARK: Top bar

    private var topBar: some View {
        HStack(spacing: AppSpacing.s3) {
            Breadcrumbs([
                BreadcrumbSegment(label: "Notepad") {
                    flushBeforeExit()
                    vm.save()
                    dismiss()
                },
                BreadcrumbSegment(label: Self.formatEntryDateLabel(vm.entryDate)),
            ])
            .frame(maxWidth: .infinity, alignment: .leading)

            // Both modes share `richTextController` — the controller's
            // UITextView keeps the buffer consistent across mode
            // switches — so no flush is needed on toggle. (Back-tap
            // + onDisappear still flush for persistence.)
            EditorModeIconToggle(mode: $editorMode)

            if vm.entry != nil {
                Button {
                    // Confirmation guard — the actual soft-delete
                    // runs only after the user confirms in the alert.
                    showDeleteDialog = true
                } label: {
                    Text("Delete")
                        .font(AppText.button)
                        .foregroundStyle(AppColors.danger)
                }
                .buttonStyle(.plain)
            }

            // Overflow menu for Merge + Move-to-notebook — surfaces
            // both destination-changing actions without requiring the
            // user to scroll to the bottom of the editor. Gated on the
            // same "there's something to act on" check MergeSection
            // uses.
            if vm.canSave || vm.entry != nil {
                Menu {
                    Button("Merge with another page") {
                        showMergeSheet = true
                    }
                    Button("Move to notebook") {
                        showMoveSheet = true
                    }
                } label: {
                    Image(systemName: "ellipsis")
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundStyle(AppColors.textSecondary)
                        .frame(width: 32, height: 32)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, AppSpacing.s4)
        .padding(.top, AppSpacing.s3)
        .padding(.bottom, AppSpacing.s3)
    }

    /// `YYYY-MM-DD` → "Today" / "Yesterday" / "Apr 21, 2026". Matches
    /// the labels `EntryDateRow` uses so the breadcrumb and the date
    /// chip below render the same text.
    private static func formatEntryDateLabel(_ iso: String) -> String {
        let parser = DateFormatter()
        parser.dateFormat = "yyyy-MM-dd"
        parser.locale     = Locale(identifier: "en_US_POSIX")
        parser.timeZone   = .current
        guard let date = parser.date(from: iso) else {
            return iso.isEmpty ? "Today" : iso
        }
        let cal = Calendar.current
        if cal.isDateInToday(date)     { return "Today" }
        if cal.isDateInYesterday(date) { return "Yesterday" }
        let display = DateFormatter()
        display.dateStyle = .medium
        return display.string(from: date)
    }

    /// Push the latest rich-text markdown into the VM before popping.
    /// Both editor modes bind the same controller's `UITextView`, so
    /// this is mode-agnostic — whichever mode the user was last in
    /// holds the freshest buffer.
    private func flushBeforeExit() {
        guard let tv = richTextController.textView else { return }
        vm.notes = MarkdownBridge.serialize(tv.attributedText)
    }
}

// MARK: - Editor body (rich-text body + feature sections)

private struct EditorBody: View {
    @ObservedObject var vm: NotepadEditorViewModel
    @ObservedObject var richTextController: RichTextEditorController

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
    private static let big = Font.system(size: 32, weight: .semibold, design: .serif)

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
                tintColor: UIColor(AppColors.coral)
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

#Preview {
    NavigationStack {
        NotepadEditorScreen(
            entryId: NotepadEditorViewModel.newEntryId,
            repository: NotepadRepository(database: ReleafDatabase(inMemory: true))
        )
    }
    .environmentObject(AuthStore(client: StubGoogleAuthClient()))
}
