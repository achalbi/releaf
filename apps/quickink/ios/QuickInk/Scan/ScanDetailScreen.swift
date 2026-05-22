/*
 * ScanDetailScreen.swift
 *
 * Full-bleed viewer for a single capture row. Renders the captured
 * PDF via PDFKit (`PDFKitView`) for multi-page scrolling + native
 * pinch-to-zoom; falls back to the first-page preview JPEG when the
 * PDF URI is missing or unreadable. Top bar carries Share / Delete
 * affordances; recognised text loads lazily under a "Show extracted
 * text" toggle (per the brief — OCR is secondary).
 */

import SwiftUI
import AVFoundation
import AVKit
import Combine
import GRDB
import PDFKit
import CoreLocation
import ReleafCoreData
import ReleafCoreScan

private enum PDFStorageKind: Sendable {
    case raw
    case compressed

    static let legacyCompressedSignature = "<< /Type /Catalog /Pages 2 0 R >>"
}

struct ScanDetailScreen: View {

    let captureId: String
    let userId: String
    let onBack: () -> Void

    @StateObject private var categoriesVM: TagListViewModel

    @State private var capture: CaptureSummary?
    @State private var showDeleteConfirm = false
    /// Drives the retag action sheet. Tapping the category pill (or
    /// the "Tag scan" affordance for an untagged capture) sets this
    /// to true; the sheet's options call `applyRetag(_:)`.
    @State private var showRetagSheet = false
    @State private var imageZoomScale: CGFloat = 1.0
    /// Pre-rasterised page bitmaps for the page-turn viewer. Loaded
    /// lazily once we know the capture has more than one page;
    /// single-page captures keep using `PDFKitView` (cheaper, comes
    /// with pinch-zoom for free).
    @State private var pageImages: [UIImage] = []
    /// Title editor — the Save button persists `titleDraft` via
    /// [CaptureRepository.setTitle]; Cancel discards it.
    @State private var showTitleEditor = false
    @State private var titleDraft = ""
    /// Notes editor — opens a full-sheet text editor on the
    /// detail screen's Notes card. Save persists via
    /// [CaptureRepository.setNotes]; Cancel discards `notesDraft`.
    @State private var showNotesEditor = false
    @State private var notesDraft = ""
    /// Drives the fullscreen flipbook viewer (`FullscreenPdfViewer`).
    /// Set true by the overlay button on the inline preview; cleared
    /// by the cover's close affordance or a system back-swipe.
    @State private var showFullscreenViewer = false
    /// Set true by the "Play video" CTA on the video card. Cleared
    /// when the user dismisses the player sheet. Only ever non-nil
    /// for hold-to-record Photo-mode captures (video_uri set).
    @State private var videoPlayerURL: URL? = nil
    /// Selected page index for the thumbnail strip (0-based). Drives
    /// the highlighted thumbnail and which page is shown in the
    /// preview. Defaults to 0 (first page).
    @State private var selectedPageIndex: Int = 0
    /// On-disk size of the capture's PDF in bytes, loaded lazily on
    /// appear so the Details card can show "2.4 MB" etc. Nil until
    /// resolved or when the file isn't readable.
    @State private var pdfFileSize: Int64? = nil
    /// Whether the local PDF is one of QuickInk's compressed
    /// JPEG-backed PDFs or a raw scanner/import PDF. Nil until the
    /// local file marker has been read.
    @State private var pdfStorageKind: PDFStorageKind? = nil
    /// Extracted contact for the in-flight Business Card review
    /// sheet. Set on tap of "Add to contact"; nil means the sheet
    /// is dismissed. Wrapped in `IdentifiedExtraction` so it works
    /// with `.sheet(item:)`.
    @State private var businessCardExtraction: IdentifiedExtraction? = nil
    /// User-edited form data, set when the user taps Save in the
    /// review sheet. Triggers presentation of the system
    /// CNContactViewController.
    @State private var pendingContactForm: IdentifiedForm? = nil
    /// Temp-file URLs for the "Share as Image" workflow. Set after
    /// the PDF pages have been rasterised to JPEGs; presenting the
    /// `.sheet(item:)` against this opens UIActivityViewController
    /// over those files. Nil while idle or while rendering is in
    /// flight.
    @State private var imageShareItems: IdentifiedURLs? = nil
    /// True while [prepareImageShare] is rasterising pages. Drives
    /// the row's label ("Preparing…") and disables further taps so
    /// a double-tap doesn't queue two renders.
    @State private var isPreparingImageShare = false
    /// Identifiable wrapper around the rasterised pages handed to
    /// the WhatsApp-style editor before the share sheet opens.
    /// Wrapping (instead of holding `[UIImage]?` directly) gives
    /// `.fullScreenCover(item:)` a stable identity so it doesn't
    /// re-present on every state read.
    @State private var pendingEditorBundle: EditorPagesBundle? = nil
    /// PDF-share activity items, set by [shareDocumentTo] when the
    /// person-chip action sheet's "Share document" row fires. Drives
    /// the same `ActivityView` flow as image-share, just with the
    /// `.pdf` URL.
    @State private var pdfShareItems: IdentifiedURLs? = nil

    /// Workspace v1 — folder picker presentation. Tapping the
    /// Actions card's "Move to folder" row flips this.
    @State private var showFolderPicker = false
    /// Workspace v1 — tag picker presentation. Tapping "Manage
    /// tags" opens the bottom sheet.
    @State private var showTagPicker = false
    /// Workspace Places — place picker presentation. Tapping
    /// "Manage places" opens the picker sheet.
    @State private var showPlacePicker = false
    /// Workspace People — person picker presentation. Tapping
    /// "Manage people" opens the picker sheet.
    @State private var showPeoplePicker = false
    /// Tag ids attached to this capture, oldest-first. Replaces the
    /// pre-A.3c `captures.category` read for the primary-label
    /// badge + the Business Card behavior switch.
    @State private var attachedTagIds: [String] = []
    @State private var tagIdsCancellable: AnyCancellable? = nil
    /// Workspace Places — attached location ids (oldest-first) +
    /// the full user-scoped list, so the details card can resolve
    /// ids to names without a per-chip fetch.
    @State private var attachedLocationIds: [String] = []
    @State private var allLocations: [LocationEntity] = []
    @State private var locationIdsCancellable: AnyCancellable? = nil
    @State private var allLocationsCancellable: AnyCancellable? = nil
    /// Workspace People — attached person ids + the full user-scoped
    /// list. Mirrors the Locations pair.
    @State private var attachedPersonIds: [String] = []
    @State private var allPeople: [PersonEntity] = []
    @State private var personIdsCancellable: AnyCancellable? = nil
    @State private var allPeopleCancellable: AnyCancellable? = nil
    /// Drives the person-chip bottom sheet (Share / Edit). Set when
    /// the user taps an existing person chip; cleared on dismiss.
    @State private var personActionTarget: PersonEntity? = nil
    /// Set when the user taps "Edit person" in the chip action sheet.
    /// Drives presentation of the in-place [PersonEditorView].
    @State private var personEditorTarget: PersonEntity? = nil
    /// Workspace v1 — debouncer for the Continue card signal. The
    /// PDF reader writes last_opened_* 500ms after the user lands
    /// on a page so a quick flip-through doesn't pollute Home.
    @State private var lastOpenedDebounceTask: Task<Void, Never>? = nil

    init(
        captureId: String,
        userId: String,
        onBack: @escaping () -> Void
    ) {
        self.captureId = captureId
        self.userId = userId
        self.onBack = onBack
        _categoriesVM = StateObject(
            wrappedValue: TagListViewModel(userId: userId)
        )
    }

    var body: some View {
        // The global `QuickInkTimeBar` in `QuickInkRoot` already
        // sits above this screen — no inline status strip needed.
        ScrollView {
            VStack(alignment: .leading, spacing: QuickInkSpacing.s5) {
                if let capture {
                    // Title block — large, prominent, with breadcrumb
                    titleHeader(for: capture)
                        .padding(.horizontal, QuickInkSpacing.s5)

                    // Preview block — full-bleed within margins
                    previewBlock(for: capture)
                        .padding(.horizontal, QuickInkSpacing.s5)

                    // Page thumbnails strip (only when multi-page)
                    if capture.pageCount > 1 {
                        pageThumbnailsStrip(for: capture)
                    }

                    // Video card — three states, gated on the
                    // pair (video_uri, video_drive_file_id):
                    //
                    //   - Both unset                → no card
                    //     (this capture never had a video).
                    //   - video_uri resolves on disk → real
                    //     "Play recorded clip" card with the
                    //     AVPlayer launcher.
                    //   - Drive id set but local file not yet
                    //     here → placeholder "Downloading…"
                    //     card so cross-device receivers know
                    //     the clip is on its way (the binary-
                    //     restore pass fills the URI in on
                    //     the next sync).
                    videoCardSection(for: capture)
                        .padding(.horizontal, QuickInkSpacing.s5)

                    // Details card — full width now that the
                    // Actions card has moved to the more-menu
                    // dropdown anchored next to the fullscreen
                    // chip on the preview.
                    detailsCard(for: capture)
                        .frame(maxWidth: .infinity, alignment: .topLeading)
                        .padding(.horizontal, QuickInkSpacing.s5)

                    // Document notes — free-form text the user
                    // can type directly into the scan. Tapping
                    // the card opens a full editor sheet; the
                    // voice-note transcript editor also appends
                    // here, so notes accumulate from both
                    // surfaces.
                    notesCard(for: capture)
                        .padding(.horizontal, QuickInkSpacing.s5)

                    // Voice notes — full-width section below the
                    // Details row. Owns its own list +
                    // recorder sheet; persists rows through
                    // `voice_notes` with a foreign key to this
                    // capture, so deletes cascade with the scan.
                    // The `onNotesChanged` callback fires after
                    // Copy-to-notes or the transcript editor's
                    // append so the Notes card above refreshes
                    // without waiting for a screen revisit.
                    VoiceNoteSection(
                        captureId:      captureId,
                        userId:         userId,
                        onNotesChanged: { Task { await loadCapture() } }
                    )
                        .padding(.horizontal, QuickInkSpacing.s5)
                } else {
                    loadingSkeleton
                        .padding(.horizontal, QuickInkSpacing.s5)
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(QuickInkColors.bg.ignoresSafeArea())
        .task {
            // Start the categories observation first (synchronous,
            // returns immediately) so it's already emitting by the
            // time the retag sheet's content closure evaluates —
            // otherwise a fast double-tap (open detail → tap pill)
            // can flash an empty picker for a frame.
            categoriesVM.start()
            // Subscribe to the live tag-id list for this capture so
            // the primary-tag badge + the Business Card mode switch
            // refresh the moment the user retags. Replaces the
            // pre-A.3c `captures.category` read.
            if tagIdsCancellable == nil {
                tagIdsCancellable = CaptureTagRepository()
                    .observeTagIds(captureId: captureId)
                    .receive(on: DispatchQueue.main)
                    .sink(
                        receiveCompletion: { _ in },
                        receiveValue: { ids in attachedTagIds = ids }
                    )
            }
            // Workspace Places + People — subscribe to both the
            // attached-id lists and the user-scoped full lists so the
            // details card's chip rows render names + the picker
            // sheets re-emit the moment the user attaches a new row.
            if locationIdsCancellable == nil {
                locationIdsCancellable = LocationRepository()
                    .observeLocationIds(captureId: captureId)
                    .receive(on: DispatchQueue.main)
                    .sink(
                        receiveCompletion: { _ in },
                        receiveValue: { ids in attachedLocationIds = ids }
                    )
            }
            if allLocationsCancellable == nil {
                allLocationsCancellable = LocationRepository()
                    .observe(userId: userId)
                    .receive(on: DispatchQueue.main)
                    .sink(
                        receiveCompletion: { _ in },
                        receiveValue: { rows in allLocations = rows }
                    )
            }
            if personIdsCancellable == nil {
                personIdsCancellable = PersonRepository()
                    .observePersonIds(captureId: captureId)
                    .receive(on: DispatchQueue.main)
                    .sink(
                        receiveCompletion: { _ in },
                        receiveValue: { ids in attachedPersonIds = ids }
                    )
            }
            if allPeopleCancellable == nil {
                allPeopleCancellable = PersonRepository()
                    .observe(userId: userId)
                    .receive(on: DispatchQueue.main)
                    .sink(
                        receiveCompletion: { _ in },
                        receiveValue: { rows in allPeople = rows }
                    )
            }
            await loadCapture()
            // File size depends on the resolved capture (we need
            // pdf_uri before we can stat the file) so it runs after
            // loadCapture lands.
            await loadFileSize()
            await loadPdfStorageKind()
            // Backfill the reverse-geocoded place name on captures
            // whose coordinates landed without a locality at scan
            // time (rate-limited CLGeocoder, offline, remote area).
            // Runs once on every Details open; CLGeocoder's own
            // rate-limit naturally caps the retry frequency.
            await retryReverseGeocodeIfNeeded()
            // Workspace v1 — register a "just opened" Continue
            // signal on first appearance. Subsequent page changes
            // refresh via .onChange below.
            scheduleLastOpenedWrite()
        }
        .onChange(of: selectedPageIndex) { _ in
            scheduleLastOpenedWrite()
        }
        .alert(deleteDialogTitle, isPresented: $showDeleteConfirm) {
            Button("Cancel", role: .cancel) {}
            Button("Delete", role: .destructive) {
                Task { await deleteCapture() }
            }
        } message: {
            Text(deleteDialogMessage)
        }
        // Retag picker — tapping the category pill (or the "Tag
        // scan" affordance) opens this. One button per active
        // category plus a "Remove tag" affordance when the capture
        // already has one. Each button calls `applyRetag(_:)` which
        // persists via `CaptureRepository.setCategory(...)` and
        // refreshes the in-screen capture state so the pill updates
        // immediately.
        // Workspace v1 — tag picker on "Manage tags".
        .sheet(isPresented: $showTagPicker) {
            TagPickerSheet(
                captureId: captureId,
                userId:    userId,
                onDismiss: { showTagPicker = false }
            )
            .presentationDetents([.large])
        }
        // Workspace Places — picker on "Manage places".
        .sheet(isPresented: $showPlacePicker) {
            LocationPickerSheet(
                userId:    userId,
                captureId: captureId,
                onDismiss: { showPlacePicker = false }
            )
            .presentationDetents([.large])
        }
        // Workspace People — picker on "Manage people".
        .sheet(isPresented: $showPeoplePicker) {
            PeoplePickerSheet(
                userId:    userId,
                captureId: captureId,
                onDismiss: { showPeoplePicker = false }
            )
            .presentationDetents([.large])
        }
        // Workspace People — chip-tap action sheet (Share / Edit).
        .confirmationDialog(
            personActionTarget?.name ?? "",
            isPresented: Binding(
                get: { personActionTarget != nil },
                set: { if !$0 { personActionTarget = nil } }
            ),
            titleVisibility: .visible
        ) {
            if let person = personActionTarget {
                let phone = person.contactPhone?.trimmingCharacters(in: .whitespaces)
                let email = person.contactEmail?.trimmingCharacters(in: .whitespaces)
                let canShare = !(phone?.isEmpty ?? true) || !(email?.isEmpty ?? true)
                if canShare {
                    Button("Share document") {
                        let target = person
                        personActionTarget = nil
                        Task { await shareDocumentTo(person: target) }
                    }
                }
                Button("Edit person") {
                    let target = person
                    personActionTarget = nil
                    personEditorTarget = target
                }
                Button("Cancel", role: .cancel) { personActionTarget = nil }
            }
        }
        .sheet(item: $personEditorTarget) { person in
            PersonEditorView(
                mode:     .edit(person: person),
                onSubmit: { name, phone, email, lookupKey, photoUri in
                    Task {
                        if !name.isEmpty, name != person.name {
                            try? await PersonRepository().rename(id: person.id, newName: name)
                        }
                        let nextPhone = phone?.isEmpty == true ? nil : phone
                        let nextEmail = email?.isEmpty == true ? nil : email
                        if nextPhone != person.contactPhone ||
                           nextEmail != person.contactEmail ||
                           lookupKey != person.contactLookupKey ||
                           photoUri  != person.contactPhotoUri {
                            try? await PersonRepository().setContactLink(
                                id:        person.id,
                                lookupKey: lookupKey,
                                phone:     nextPhone,
                                email:     nextEmail,
                                photoUri:  photoUri
                            )
                        }
                    }
                    personEditorTarget = nil
                },
                onCancel: { personEditorTarget = nil }
            )
            .presentationDetents([.medium])
        }
        // Workspace v1 — folder picker on "Move to folder".
        .sheet(isPresented: $showFolderPicker) {
            FolderPickerSheet(
                userId:          userId,
                currentFolderId: capture?.folderId,
                onPickFolder:    { folder in
                    let cid = captureId
                    Task {
                        try? await CaptureRepository().setFolder(
                            captureId: cid,
                            folderId:  folder.id,
                        )
                        // Refresh in-screen capture so the
                        // Details card reflects the new folder.
                        await reloadCapture()
                    }
                    showFolderPicker = false
                },
                onDismiss:       { showFolderPicker = false }
            )
            .presentationDetents([.medium])
        }
        .confirmationDialog(
            "Tag scan as",
            isPresented: $showRetagSheet,
            titleVisibility: .visible
        ) {
            ForEach(categoriesVM.categories, id: \.id) { cat in
                Button(cat.name) {
                    Task { await applyRetag(cat.name) }
                }
            }
            if let current = primaryTagName, !current.isEmpty {
                Button("Remove tag", role: .destructive) {
                    Task { await applyRetag(nil) }
                }
            }
            Button("Cancel", role: .cancel) {}
        }
        // Title editor — system-styled alert with an inline TextField.
        // Save persists via `CaptureRepository.setTitle(...)`; Cancel
        // drops the draft. Trimming-blank-to-nil keeps the Library
        // card's OCR/category/"Untitled" fallback chain intact.
        .alert("Edit title", isPresented: $showTitleEditor) {
            TextField("Untitled scan", text: $titleDraft)
            Button("Cancel", role: .cancel) {}
            Button("Save") {
                Task { await applyTitle(titleDraft) }
            }
        } message: {
            Text("\(titleDraft.count) characters")
                .font(.caption)
                .foregroundStyle(QuickInkColors.inkSoft)
        }
        // Notes editor — full sheet with a multi-line TextEditor so
        // long notes (and pasted blocks from the voice-transcript
        // editor) have room to breathe. Save persists via
        // `CaptureRepository.setNotes(...)`; Cancel drops the draft.
        .sheet(isPresented: $showNotesEditor) {
            NavigationStack {
                VStack(alignment: .leading, spacing: QuickInkSpacing.s2) {
                    Text("Capture notes about this scan. Voice-note transcripts also get appended here.")
                        .font(QuickInkText.caption)
                        .foregroundStyle(QuickInkColors.muted)
                        .padding(.horizontal, QuickInkSpacing.s4)
                        .padding(.top, QuickInkSpacing.s3)

                    TextEditor(text: $notesDraft)
                        .font(QuickInkText.body)
                        .scrollContentBackground(.hidden)
                        .padding(QuickInkSpacing.s2)
                        .background(QuickInkColors.borderSoft.opacity(0.4))
                        .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous))
                        .overlay(
                            RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous)
                                .stroke(QuickInkColors.border, lineWidth: 1)
                        )
                        .padding(.horizontal, QuickInkSpacing.s4)
                        .padding(.bottom, QuickInkSpacing.s4)
                }
                .frame(maxHeight: .infinity, alignment: .top)
                .background(QuickInkColors.bg.ignoresSafeArea())
                .navigationTitle("Notes")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Cancel") { showNotesEditor = false }
                            .foregroundStyle(QuickInkColors.inkSoft)
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Save") {
                            let draft = notesDraft
                            showNotesEditor = false
                            Task { await applyNotes(draft) }
                        }
                        .foregroundStyle(QuickInkColors.accent)
                    }
                }
            }
            .presentationDetents([.medium, .large])
            .presentationDragIndicator(.visible)
        }
        // Business Card review sheet — opens when extraction lands
        // (businessCardExtraction != nil). Shows every extracted
        // field as an editable form so the user can fix any
        // mis-classifications before the system contact form takes
        // over. On Save we stash the edited form and the next sheet
        // (`pendingContactForm` != nil) presents CNContactViewController.
        .sheet(item: $businessCardExtraction) { wrapper in
            AddContactReviewSheet(
                extracted: wrapper.extracted,
                onCancel:  { businessCardExtraction = nil },
                onConfirm: { edited in
                    businessCardExtraction = nil
                    // Tiny delay so the review sheet finishes
                    // dismissing before the contact form rises —
                    // SwiftUI doesn't gracefully chain two .sheet
                    // presentations on the same view.
                    Task { @MainActor in
                        try? await Task.sleep(nanoseconds: 350_000_000)
                        pendingContactForm = IdentifiedForm(form: edited)
                    }
                }
            )
        }
        // System contact-creation form — opens once the user has
        // saved the review sheet. Splits the comma-joined phones /
        // emails / urls back out before passing them in.
        .sheet(item: $pendingContactForm) { wrapper in
            let form = wrapper.form
            AddContactSheet(
                name:        form.name.isEmpty ? nil : form.name,
                phones:      splitCsv(form.phones),
                company:     form.company.isEmpty ? nil : form.company,
                designation: form.designation.isEmpty ? nil : form.designation,
                emails:      splitCsv(form.emails),
                urls:        splitCsv(form.websites),
                address:     form.address.isEmpty ? nil : form.address,
                onDismiss:   { pendingContactForm = nil }
            )
            .ignoresSafeArea()
        }
        // Share-as-Image sheet — opens once the editor commits the
        // (possibly cropped / annotated) pages and we've written
        // them to temp JPEGs. The wrapper struct's `id` cycles every
        // render so a second share-as-image tap re-presents the
        // sheet rather than no-opping on identical state.
        .sheet(item: $imageShareItems) { wrapper in
            ActivityView(activityItems: wrapper.urls)
        }
        // PDF share sheet — driven by the person-chip "Share document"
        // action (and any future per-recipient share). The system
        // share sheet doesn't expose Android-style recipient
        // pre-targeting, so this is a plain UIActivityViewController
        // around the PDF URL.
        .sheet(item: $pdfShareItems) { wrapper in
            ActivityView(activityItems: wrapper.urls)
        }
        // WhatsApp-style image editor — fullscreen cover the user
        // walks through before the share sheet. Crop + pencil per
        // page; Done writes the edited images to temp files and
        // hands them to the share sheet above.
        // Photo-mode hold-to-record clip player. Mounted as a
        // `.fullScreenCover` (not `.sheet`) so the player goes
        // edge-to-edge — the sheet form factor inserts a drag
        // handle + rounded top corners + a status-bar gap that
        // visually bracket the video with top + bottom padding.
        // Full-screen cover is the right presentation for an
        // immersive video player.
        .fullScreenCover(item: Binding(
            get: { videoPlayerURL.map { IdentifiedURL(url: $0) } },
            set: { videoPlayerURL = $0?.url }
        )) { wrapper in
            CaptureVideoPlayerSheet(url: wrapper.url)
        }
        .fullScreenCover(item: $pendingEditorBundle) { bundle in
            ImageEditorScreen(
                pages: bundle.pages,
                onCancel: { pendingEditorBundle = nil },
                onDone: { edited in
                    pendingEditorBundle = nil
                    let id = captureId
                    let notes = capture?.notes?
                        .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                    Task.detached(priority: .userInitiated) {
                        let withNotes = ScanDetailScreen.embedNotesFooter(
                            on: edited,
                            notes: notes
                        )
                        let urls = ScanDetailScreen.writeJpegsToTemp(withNotes, base: id)
                        await MainActor.run {
                            if !urls.isEmpty {
                                imageShareItems = IdentifiedURLs(urls: urls)
                            }
                        }
                    }
                }
            )
        }
        // Fullscreen flipbook viewer — opens when the user taps the
        // overlay fullscreen button on the inline preview. Only
        // meaningful when a real PDF resolves on disk; the
        // `pdfURL(from:)` helper returns nil when the file isn't
        // there, so we guard the cover behind the same check the
        // inline view uses (otherwise an opened cover could land on
        // an empty `FullscreenPdfViewer` and hang on its loader).
        .fullScreenCover(isPresented: $showFullscreenViewer) {
            if let pdfURL = pdfURL(from: capture) {
                FullscreenPdfViewer(
                    pdfURL: pdfURL,
                    onDismiss: { showFullscreenViewer = false }
                )
            } else {
                // Defensive — cover was opened against a vanished
                // file. Auto-close so the user isn't stuck on a
                // black screen with no visible affordance to bail.
                Color.black
                    .ignoresSafeArea()
                    .onAppear { showFullscreenViewer = false }
            }
        }
    }

    // MARK: - Preview

    /// Picks the best available preview surface for the capture:
    /// (1) Multi-page captures → `PageTurnPdfView` with the swipe +
    ///     book-flip animation. Loads page bitmaps lazily on first
    ///     appear.
    /// (2) Single-page captures → PDFKit-rendered scrollable view
    ///     (pinch-to-zoom comes free).
    /// (3) Pinch-zoomable first-page JPEG fallback when no PDF.
    /// (4) Paper-toned placeholder when nothing is available.
    /// Resolves the playable video file:// URL for the capture
    /// when the .mov / .mp4 is locally present. Returns nil when
    /// the row isn't a video, the URI is empty, or the file isn't
    /// on disk (e.g. cross-device receive window before the
    /// binary-restore pass lands the clip).
    private func playableVideoURL(for capture: CaptureSummary) -> URL? {
        guard let url = Self.resolvedLocalURL(for: capture.videoUri),
              FileManager.default.fileExists(atPath: url.path)
        else { return nil }
        return url
    }

    /// Tap handler for the preview block. Routes to the video
    /// player when the capture has a playable clip, otherwise
    /// falls through to the fullscreen PDF viewer (the existing
    /// behaviour for stills / scans / imports).
    private func handlePreviewTap(for capture: CaptureSummary) {
        if let url = playableVideoURL(for: capture) {
            videoPlayerURL = url
        } else {
            showFullscreenViewer = true
        }
    }

    @ViewBuilder
    private func previewBlock(for capture: CaptureSummary) -> some View {
        if let pdfURL = pdfURL(from: capture) {
            if capture.pageCount > 1 {
                pageTurnViewer(for: pdfURL, capture: capture)
                    .contentShape(Rectangle())
                    .onTapGesture { handlePreviewTap(for: capture) }
                    .overlay(alignment: .topTrailing) { topRightChips(for: capture) }
                    .overlay(alignment: .center)    { videoPlayOverlay(for: capture) }
            } else {
                PDFKitView(
                    url: pdfURL,
                    backgroundColor: QuickInkColors.bg,
                    interactionsEnabled: false
                )
                    .frame(maxWidth: .infinity)
                    .frame(height: pdfPreviewHeight(for: capture))
                    .background(QuickInkColors.bg)
                    .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous))
                    .contentShape(Rectangle())
                    .onTapGesture { handlePreviewTap(for: capture) }
                    .overlay(alignment: .topTrailing) { topRightChips(for: capture) }
                    .overlay(alignment: .center)    { videoPlayOverlay(for: capture) }
            }
        } else if let image = loadedPreviewImage(for: capture) {
            Image(uiImage: image)
                .resizable()
                .scaledToFit()
                .scaleEffect(imageZoomScale)
                .gesture(
                    MagnificationGesture()
                        .onChanged { value in
                            imageZoomScale = max(1.0, min(value.magnitude, 4.0))
                        }
                        .onEnded { _ in
                            withAnimation(.easeOut(duration: 0.2)) {
                                imageZoomScale = max(1.0, min(imageZoomScale, 4.0))
                            }
                        }
                )
                .onTapGesture(count: 2) {
                    withAnimation(.easeInOut(duration: 0.2)) {
                        imageZoomScale = imageZoomScale > 1.0 ? 1.0 : 2.0
                    }
                }
                .frame(maxWidth: .infinity)
                .background(QuickInkColors.bg)
                .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous))
        } else {
            ZStack {
                QuickInkColors.paper2
                Image(systemName: "doc.text")
                    .font(.system(size: 64))
                    .foregroundStyle(QuickInkColors.muted)
            }
            .frame(maxWidth: .infinity)
            .frame(height: 320)
            .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous))
        }
    }

    /// Center-aligned circular play button overlaid on the preview
    /// when the capture is a hold-to-record video with the .mov /
    /// .mp4 locally available. Visual hint that "this is playable"
    /// + a tap target that opens the full-screen player. Skipped
    /// when the row isn't a video OR when the video file hasn't
    /// been downloaded yet (the Video pending card below the
    /// preview tells the user it's on its way).
    @ViewBuilder
    private func videoPlayOverlay(for capture: CaptureSummary) -> some View {
        if let url = playableVideoURL(for: capture) {
            Button(action: { videoPlayerURL = url }) {
                ZStack {
                    Circle()
                        .fill(Color.black.opacity(0.55))
                        .frame(width: 72, height: 72)
                    Image(systemName: "play.fill")
                        .font(.system(size: 30, weight: .bold))
                        .foregroundStyle(.white)
                        // Pull the icon ~3pt right so the visual
                        // centre of the triangle lands on the
                        // disc's geometric centre (Apple's play
                        // glyph is left-weighted).
                        .offset(x: 3)
                }
                .shadow(color: Color.black.opacity(0.35), radius: 12, x: 0, y: 4)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Play recorded video")
        }
    }

    /// Top-trailing chip cluster overlaid on the preview block —
    /// fullscreen viewer on the left and a more-actions ellipsis
    /// menu on the right. The menu holds every per-capture action
    /// (Add to contact, Share as Image, Export as PDF, Move to
    /// folder, Manage tags, Delete) so the body doesn't need a
    /// separate Actions card.
    @ViewBuilder
    private func topRightChips(for capture: CaptureSummary) -> some View {
        HStack(spacing: QuickInkSpacing.s2) {
            fullscreenChip
            moreActionsMenu(for: capture)
        }
        .padding(QuickInkSpacing.s3)
    }

    /// Pill button that opens [showFullscreenViewer] against the
    /// current capture's PDF. Mirror of Android's
    /// `Icons.Filled.Fullscreen` chip on `PageTurnPdfView` /
    /// `PdfPagesView` — same dark-on-light contrast (ink @ 55% with
    /// a white icon) so the chip stays unmistakeable on top of the
    /// white scan surface. Sized to match Releaf's overflow buttons:
    /// 40pt container, 16pt icon — compact enough to leave the
    /// preview dominant.
    @ViewBuilder
    private var fullscreenChip: some View {
        Button(action: { showFullscreenViewer = true }) {
            Image(systemName: "arrow.up.left.and.arrow.down.right")
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(QuickInkColors.textOnAccent)
                .frame(width: 32, height: 32)
                .background(QuickInkColors.ink.opacity(0.55))
                .clipShape(Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("View fullscreen")
        .accessibilityHint("Expands the scan to fill the screen")
    }

    /// Ellipsis chip that surfaces the actions previously housed in
    /// the inline Actions card. Built on SwiftUI's `Menu` so the
    /// dropdown lives in the platform overlay and dismisses on
    /// outside-tap automatically. Items are split into three
    /// sections — business-card extraction, share/export, and
    /// destination — so the native menu shows separators between
    /// related groups. Delete sits in its own destructive section
    /// at the bottom.
    @ViewBuilder
    private func moreActionsMenu(for capture: CaptureSummary) -> some View {
        Menu {
            if isBusinessCard(capture) {
                Section {
                    Button {
                        Task { await openAddContactSheet() }
                    } label: {
                        Label("Add to contact", systemImage: "person.crop.circle.badge.plus")
                    }
                }
            }

            Section {
                // Video subtype = a capture whose source is "video"
                // or a legacy "photo" row with a video URI. For these, the
                // only useful artifact is the recorded clip
                // itself; the image/PDF pair makes no sense.
                // Swap them for a single "Share video" ShareLink.
                let isVideo = isVideoCapture(for: capture)
                if isVideo {
                    if let videoURL = Self.resolvedLocalURL(for: capture.videoUri) {
                        ShareLink(item: videoURL) {
                            Label("Share video", systemImage: "video")
                        }
                    }
                } else {
                    if canShareAsImage(capture) {
                        Button {
                            Task { await prepareImageShare() }
                        } label: {
                            Label(
                                isPreparingImageShare ? "Preparing…" : "Share as Image",
                                systemImage: "photo"
                            )
                        }
                        .disabled(isPreparingImageShare)
                    }
                    if let pdfURL = shareablePdfURL(from: capture) {
                        ShareLink(item: pdfURL) {
                            Label("Export as PDF", systemImage: "arrow.down.doc")
                        }
                    }
                }
            }

            Section {
                Button { showFolderPicker = true } label: {
                    Label("Move to folder", systemImage: "folder")
                }
                Button { showTagPicker = true } label: {
                    Label("Manage tags", systemImage: "tag")
                }
            }

            Section {
                Button(role: .destructive) {
                    showDeleteConfirm = true
                } label: {
                    Label("Delete", systemImage: "trash")
                }
            }
        } label: {
            Image(systemName: "ellipsis")
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(QuickInkColors.textOnAccent)
                .frame(width: 32, height: 32)
                .background(QuickInkColors.ink.opacity(0.55))
                .clipShape(Circle())
        }
        .menuOrder(.fixed)
        .accessibilityLabel("More actions")
        .accessibilityHint("Open the actions menu for this item")
    }

    /// Heuristic height for the embedded PDFView. Single-page scans
    /// take a short slab; multi-page scans get a taller scrollable
    /// surface so the user sees more than one page at a time without
    /// having to scroll the outer ScrollView. Caps at 720pt so the
    /// inner scroll still has somewhere to go on tall screens.
    private func pdfPreviewHeight(for capture: CaptureSummary) -> CGFloat {
        let perPage: CGFloat = 360
        return min(perPage * CGFloat(max(capture.pageCount, 1)) + 24, 720)
    }

    /// Multi-page page-turn viewer. Rasterises every PDF page once
    /// on first appear; subsequent recompositions re-use the cached
    /// bitmaps. Wrapped in a fixed-aspect frame so the swipe gesture
    /// has predictable bounds inside the outer ScrollView.
    @ViewBuilder
    private func pageTurnViewer(for pdfURL: URL, capture: CaptureSummary) -> some View {
        Group {
            if pageImages.isEmpty {
                ZStack {
                    QuickInkColors.surface
                    ProgressView()
                        .tint(QuickInkColors.accent)
                }
                .frame(maxWidth: .infinity)
                .aspectRatio(0.707, contentMode: .fit) // A4-ish portrait ratio
                .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous)
                        .stroke(QuickInkColors.border, lineWidth: 1)
                )
                .task(id: pdfURL.path) {
                    // Off-main rasterisation so the UI stays
                    // responsive during the initial render. PDFKit's
                    // `thumbnail(of:for:)` is synchronous; running it
                    // in a Task keeps the main thread free for the
                    // page indicator + back button.
                    let url = pdfURL
                    let images: [UIImage] = await Task.detached(priority: .userInitiated) {
                        guard let doc = PDFDocument(url: url) else { return [] }
                        return doc.renderPageImages(scale: 2.0)
                    }.value
                    self.pageImages = images
                }
            } else {
                PageTurnPdfView(
                    pageImages:         pageImages,
                    currentPage:        $selectedPageIndex,
                    interactionsEnabled: false
                )
                    .frame(maxWidth: .infinity)
                    .aspectRatio(pageAspectRatio, contentMode: .fit)
                    .background(QuickInkColors.surface)
                    .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous)
                            .stroke(QuickInkColors.border, lineWidth: 1)
                    )
            }
        }
    }

    /// Aspect ratio of the first rasterised page — used to size the
    /// page-turn viewer's frame. Falls back to A4 portrait when no
    /// pages have rendered yet.
    private var pageAspectRatio: CGFloat {
        guard let first = pageImages.first, first.size.height > 0 else { return 0.707 }
        return first.size.width / first.size.height
    }

    /// Build a `URL` from `captures.pdf_uri`. Returns `nil` when the
    /// stored URI is empty or doesn't resolve to a file we can read
    /// (in which case the caller falls back to the JPEG preview).
    /// Used by `PDFKitView` — fail-closed so we don't try to render
    /// a missing PDF.
    private func pdfURL(from capture: CaptureSummary?) -> URL? {
        guard let raw = capture?.pdfUri, !raw.isEmpty else { return nil }
        let url: URL?
        if let parsed = URL(string: raw), parsed.isFileURL {
            url = parsed
        } else {
            url = URL(fileURLWithPath: raw)
        }
        guard let url, FileManager.default.fileExists(atPath: url.path) else { return nil }
        return url
    }

    /// Best-effort URL for the share sheet — returns the parsed URL
    /// even when the file no longer exists on disk (legacy capture,
    /// temp dir cleaned, etc.). The system share sheet renders an
    /// error itself rather than silently disappearing the button.
    private func shareablePdfURL(from capture: CaptureSummary?) -> URL? {
        guard let raw = capture?.pdfUri, !raw.isEmpty else { return nil }
        if let parsed = URL(string: raw) { return parsed }
        return URL(fileURLWithPath: raw)
    }

    /// Large title header at the top of the detail screen, matching
    /// the mockup: prominent display title with an inline edit
    /// pencil, followed by a breadcrumb row (date • pages • category).
    /// Tap on the title opens the title editor alert.
    @ViewBuilder
    private func titleHeader(for capture: CaptureSummary) -> some View {
        let displayed: String? = {
            let trimmed = capture.title?.trimmingCharacters(in: .whitespaces) ?? ""
            return trimmed.isEmpty ? nil : trimmed
        }()
        VStack(alignment: .leading, spacing: QuickInkSpacing.s2) {
            Button {
                titleDraft = capture.title ?? ""
                showTitleEditor = true
            } label: {
                HStack(alignment: .firstTextBaseline, spacing: QuickInkSpacing.s2) {
                    Text(displayed ?? "Add a title")
                        .font(QuickInkText.display)
                        .foregroundStyle(displayed != nil ? QuickInkColors.ink : QuickInkColors.accent)
                        .lineLimit(2)
                        .multilineTextAlignment(.leading)
                        .fixedSize(horizontal: false, vertical: true)
                    Image(systemName: "pencil")
                        .font(.system(size: 20, weight: .medium))
                        .foregroundStyle(QuickInkColors.muted)
                }
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Edit title")

            // Breadcrumb row: date • pages • category
            breadcrumbRow(for: capture)
        }
    }

    /// Compact breadcrumb under the title — shows date, page count,
    /// and category (when present) separated by middle dots. Each
    /// item leads with a small SF Symbol icon for visual scanning.
    @ViewBuilder
    private func breadcrumbRow(for capture: CaptureSummary) -> some View {
        HStack(spacing: QuickInkSpacing.s2) {
            HStack(spacing: QuickInkSpacing.s1) {
                Image(systemName: "calendar")
                    .font(.system(size: 14, weight: .medium))
                Text(friendlyDate(capture.createdAt))
                    .font(QuickInkText.meta)
            }
            .foregroundStyle(QuickInkColors.inkSoft)

            Text("•").foregroundStyle(QuickInkColors.muted).font(QuickInkText.meta)

            HStack(spacing: QuickInkSpacing.s1) {
                Image(systemName: "doc")
                    .font(.system(size: 14, weight: .medium))
                Text("\(capture.pageCount) page\(capture.pageCount == 1 ? "" : "s")")
                    .font(QuickInkText.meta)
            }
            .foregroundStyle(QuickInkColors.inkSoft)

            // Pre-A.3c this row carried a "folder" breadcrumb tied
            // to the legacy `captures.category` slot. Post-drop the
            // primary label lives on the dedicated pill / Details
            // row so this breadcrumb no longer duplicates the signal.
        }
    }

    @ViewBuilder
    private func tagPill(for capture: CaptureSummary) -> some View {
        let hasTag = !(primaryTagName ?? "").isEmpty
        Button {
            showRetagSheet = true
        } label: {
            HStack(spacing: QuickInkSpacing.s1) {
                Image(systemName: "tag")
                    .font(.system(size: 11, weight: .medium))
                Text(hasTag ? (primaryTagName ?? "") : "Tag scan")
                    .font(QuickInkText.caption)
            }
            .foregroundStyle(hasTag ? QuickInkColors.accent : QuickInkColors.inkSoft)
            .padding(.horizontal, QuickInkSpacing.s3)
            .padding(.vertical, QuickInkSpacing.s2)
            .background(hasTag ? QuickInkColors.accentSoft : QuickInkColors.borderSoft)
            .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.pill, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(hasTag ? "Category: \(primaryTagName ?? "")" : "Add category")
        .accessibilityHint("Tap to change the category")
    }

    // MARK: - Page thumbnails

    /// Horizontal scrollable strip of page thumbnails — one numbered
    /// chip per page, with the currently selected page highlighted in
    /// the accent color. Tap a chip to navigate to that page (drives
    /// `selectedPageIndex`). Only rendered for multi-page captures;
    /// single-page scans don't need it.
    ///
    /// Uses the cached `pageImages` rasterised by [pageTurnViewer]
    /// when available; falls back to a simple numbered placeholder
    /// while the rasterisation is in flight, so the strip never
    /// renders as empty.
    @ViewBuilder
    private func pageThumbnailsStrip(for capture: CaptureSummary) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: QuickInkSpacing.s3) {
                ForEach(0..<capture.pageCount, id: \.self) { index in
                    pageThumbnail(index: index)
                }
            }
            .padding(.horizontal, QuickInkSpacing.s5)
        }
    }

    /// Single thumbnail chip in [pageThumbnailsStrip]. Draws the
    /// rasterised page bitmap when available (cached on `pageImages`)
    /// or a paper-toned placeholder when the rasterisation hasn't
    /// landed yet. The selected chip gets an accent border and a
    /// rounded badge with its page number.
    @ViewBuilder
    private func pageThumbnail(index: Int) -> some View {
        let isSelected = (index == selectedPageIndex)
        Button {
            selectedPageIndex = index
        } label: {
            ZStack(alignment: .bottomTrailing) {
                Group {
                    if index < pageImages.count {
                        Image(uiImage: pageImages[index])
                            .resizable()
                            .scaledToFill()
                    } else {
                        ZStack {
                            QuickInkColors.paper2
                            Image(systemName: "doc.text")
                                .font(.system(size: 20))
                                .foregroundStyle(QuickInkColors.muted)
                        }
                    }
                }
                .frame(width: 64, height: 80)
                .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.sm, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: QuickInkRadius.sm, style: .continuous)
                        .stroke(isSelected ? QuickInkColors.accent : QuickInkColors.border, lineWidth: isSelected ? 2 : 1)
                )

                // Page number badge (bottom-trailing)
                Text("\(index + 1)")
                    .font(QuickInkText.caption)
                    .foregroundStyle(isSelected ? QuickInkColors.textOnAccent : QuickInkColors.ink)
                    .frame(width: 22, height: 22)
                    .background(isSelected ? QuickInkColors.accent : QuickInkColors.surface)
                    .clipShape(Circle())
                    .overlay(Circle().stroke(QuickInkColors.border.opacity(0.6), lineWidth: 0.5))
                    .offset(x: 6, y: 6)
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Page \(index + 1)\(isSelected ? ", selected" : "")")
    }

    // MARK: - Video card

    /// Decides whether to render the "video is downloading"
    /// placeholder below the preview. The playable case is now
    /// surfaced via a play-button overlay on the preview itself
    /// (`videoPlayOverlay`) — no second card needed.
    @ViewBuilder
    private func videoCardSection(for capture: CaptureSummary) -> some View {
        let localURL = Self.resolvedLocalURL(for: capture.videoUri)
        let hasLocal = localURL.map {
            FileManager.default.fileExists(atPath: $0.path)
        } ?? false
        let hasDriveId = !(capture.videoDriveFileId?
                            .trimmingCharacters(in: .whitespaces) ?? "").isEmpty

        if !hasLocal, hasDriveId {
            videoPendingCard
        }
    }

    /// Parse a `video_uri` row value into a usable file:// URL.
    /// Returns nil for empty or unparseable values.
    private static func resolvedLocalURL(for videoUri: String?) -> URL? {
        guard let raw = videoUri?.trimmingCharacters(in: .whitespaces),
              !raw.isEmpty else { return nil }
        if let parsed = URL(string: raw), parsed.isFileURL { return parsed }
        return URL(fileURLWithPath: raw)
    }

    /// Placeholder card — shown on a receiver device whose row
    /// has a `video_drive_file_id` but whose local .mov / .mp4
    /// hasn't been downloaded yet. The `QuickInkBinarySync`
    /// restore pass fills `video_uri` in on its next run; this
    /// card flips to the play-button overlay on the preview
    /// automatically on the next re-render. Tap is disabled to
    /// make clear there's nothing to play yet.
    @ViewBuilder
    private var videoPendingCard: some View {
        VStack(alignment: .leading, spacing: 0) {
            videoCardHeader

            HStack(spacing: QuickInkSpacing.s3) {
                ProgressView()
                    .progressViewStyle(.circular)
                    .tint(QuickInkColors.accent)
                    .frame(width: 32, height: 32)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Downloading video…")
                        .font(QuickInkFont.ui(13, weight: .medium))
                        .foregroundStyle(QuickInkColors.ink)
                    Text("Restoring from Drive — try again in a moment.")
                        .font(QuickInkText.caption)
                        .foregroundStyle(QuickInkColors.muted)
                }
                Spacer()
            }
            .padding(QuickInkSpacing.s3)
        }
        .frame(maxWidth: .infinity, alignment: .topLeading)
        .background(QuickInkColors.surface)
        .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous)
                .stroke(QuickInkColors.border, lineWidth: 1)
        )
        .accessibilityLabel("Video downloading from Drive")
    }

    @ViewBuilder
    private var videoCardHeader: some View {
        HStack(spacing: QuickInkSpacing.s2) {
            Image(systemName: "play.rectangle.fill")
                .font(.system(size: 16, weight: .medium))
                .foregroundStyle(QuickInkColors.inkSoft)
            Text("Video")
                .font(QuickInkFont.ui(13, weight: .semibold))
                .foregroundStyle(QuickInkColors.ink)
            Spacer()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, QuickInkSpacing.s3)
        .padding(.vertical, QuickInkSpacing.s2)
        .background(QuickInkColors.borderSoft)
    }

    // MARK: - Details card

    /// Free-form document notes card. Renders the existing notes
    /// when present (preserving line breaks); shows an empty-state
    /// prompt otherwise. Tap anywhere on the card to open the
    /// editor sheet. The same `captures.notes` column is appended
    /// to by the voice-note transcript editor, so this card
    /// accumulates content from both surfaces.
    @ViewBuilder
    private func notesCard(for capture: CaptureSummary) -> some View {
        let trimmed = capture.notes?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let hasNotes = !trimmed.isEmpty
        let titleText = capture.title?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let subject = titleText.isEmpty ? "QuickInk notes" : titleText

        VStack(alignment: .leading, spacing: 0) {
            // Heading on a soft grey strip — matches the
            // Details and Voice notes cards.
            HStack(spacing: QuickInkSpacing.s2) {
                Image(systemName: "square.and.pencil")
                    .font(.system(size: 16, weight: .medium))
                    .foregroundStyle(QuickInkColors.inkSoft)
                Text("Notes")
                    .font(QuickInkFont.ui(13, weight: .semibold))
                    .foregroundStyle(QuickInkColors.ink)
                Spacer()
                if hasNotes {
                    ShareLink(
                        item: trimmed,
                        subject: Text(subject)
                    ) {
                        HStack(spacing: QuickInkSpacing.s1) {
                            Image(systemName: "square.and.arrow.up")
                                .font(.system(size: 11, weight: .semibold))
                            Text("Share")
                                .font(QuickInkText.caption)
                        }
                        .foregroundStyle(QuickInkColors.accent)
                        .padding(.horizontal, QuickInkSpacing.s2)
                        .padding(.vertical, 4)
                        .background(QuickInkColors.accentSoft)
                        .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.pill, style: .continuous))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Share notes")
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, QuickInkSpacing.s3)
            .padding(.vertical, QuickInkSpacing.s2)
            .background(QuickInkColors.borderSoft)

            VStack(alignment: .leading, spacing: QuickInkSpacing.s2) {
                if hasNotes {
                    Text(trimmed)
                        .font(QuickInkFont.ui(11, weight: .regular))
                        .foregroundStyle(QuickInkColors.ink)
                        .multilineTextAlignment(.leading)
                        .lineLimit(8)
                        .fixedSize(horizontal: false, vertical: true)
                        .frame(maxWidth: .infinity, alignment: .leading)
                } else {
                    Text("Tap to add notes for this scan. Voice-note transcripts also land here.")
                        .font(QuickInkText.caption)
                        .foregroundStyle(QuickInkColors.muted)
                        .multilineTextAlignment(.leading)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            .padding(QuickInkSpacing.s3)
            .contentShape(Rectangle())
            .onTapGesture {
                notesDraft = capture.notes ?? ""
                showNotesEditor = true
            }
        }
        .frame(maxWidth: .infinity, alignment: .topLeading)
        .background(QuickInkColors.surface)
        .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous)
                .stroke(QuickInkColors.border, lineWidth: 1)
        )
        .accessibilityLabel(hasNotes ? "Edit notes" : "Add notes")
    }

    /// Structured details card matching the mockup: rows for File
    /// type / Size / Created / Location / Tags, each with a label on
    /// the left and value on the right. Header has a small
    /// document.text icon + "Details" label per the mockup.
    @ViewBuilder
    private func detailsCard(for capture: CaptureSummary) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            // Heading sits on a soft grey strip that spans the
            // card's full inner width. Padding is local to the strip
            // so the rows below keep their existing inset.
            HStack(spacing: QuickInkSpacing.s2) {
                Image(systemName: "doc.text")
                    .font(.system(size: 16, weight: .medium))
                    .foregroundStyle(QuickInkColors.inkSoft)
                Text("Details")
                    .font(QuickInkFont.ui(13, weight: .semibold))
                    .foregroundStyle(QuickInkColors.ink)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, QuickInkSpacing.s3)
            .padding(.vertical, QuickInkSpacing.s2)
            .background(QuickInkColors.borderSoft)

            VStack(spacing: QuickInkSpacing.s2) {
                detailRow(label: "File type", value: fileTypeLabel(for: capture))
                detailRow(label: "Size", value: pdfFileSize.map(formatBytes) ?? "—")
                detailRow(
                    label: "Folder",
                    value: primaryTagName ?? "Unsorted",
                    valueColor: primaryTagName != nil ? QuickInkColors.accent : QuickInkColors.inkSoft
                )
                // Geographic Area / City / Address rows — hidden
                // when the capture has no reverse-geocoded place
                // name (older rows, location toggle off, denied
                // permission, or a failed geocode). Coordinates
                // without a place name aren't surfaced here — they'd
                // read as raw numbers, not useful for the average
                // user. Dedupe at render time so existing rows
                // where the geocoder fell back to the city for both
                // fields don't show identical Area + City rows.
                if let address = capture.address?.trimmingCharacters(in: .whitespaces),
                   !address.isEmpty {
                    detailRow(label: "Address", value: address)
                }
                let names = LocationService.dedupePlaceNames(
                    locality:    capture.locality,
                    subLocality: capture.subLocality
                )
                if let subLocality = names.subLocality, !subLocality.isEmpty {
                    detailRow(label: "Area", value: subLocality)
                }
                if let locality = names.locality, !locality.isEmpty {
                    detailRow(label: "City", value: locality)
                }
                tagsRow(for: capture)
                placesRow(for: capture)
                peopleRow(for: capture)
            }
            .padding(QuickInkSpacing.s3)
        }
        .background(QuickInkColors.surface)
        .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous)
                .stroke(QuickInkColors.border, lineWidth: 1)
        )
    }

    /// One label/value row inside [detailsCard]. Label is muted,
    /// left-aligned; value is ink, right-aligned. `valueColor` lets
    /// callers override (e.g. accent color for the Location link).
    /// Sized at 13pt so the card reads at body comfort rather than
    /// the 10pt caption used for confidence badges elsewhere.
    @ViewBuilder
    private func detailRow(
        label: String,
        value: String,
        valueColor: Color = QuickInkColors.ink
    ) -> some View {
        HStack(alignment: .firstTextBaseline) {
            Text(label)
                .font(QuickInkFont.ui(11, weight: .medium))
                .foregroundStyle(QuickInkColors.inkSoft)
            Spacer()
            Text(value)
                .font(QuickInkFont.ui(11, weight: .medium))
                .foregroundStyle(valueColor)
                .multilineTextAlignment(.trailing)
                .lineLimit(2)
        }
    }

    /// "Tags" row inside [detailsCard]. Renders the existing tag pill
    /// plus a "+" affordance to add/change category, matching the
    /// mockup's tag chips.
    @ViewBuilder
    private func tagsRow(for capture: CaptureSummary) -> some View {
        HStack(alignment: .firstTextBaseline) {
            Text("Tags")
                .font(QuickInkFont.ui(11, weight: .medium))
                .foregroundStyle(QuickInkColors.inkSoft)
            Spacer()
            HStack(spacing: QuickInkSpacing.s1) {
                if let tag = primaryTagName, !tag.isEmpty {
                    Button {
                        showRetagSheet = true
                    } label: {
                        Text(tag)
                            .font(QuickInkFont.ui(11, weight: .medium))
                            .foregroundStyle(QuickInkColors.accent)
                            .padding(.horizontal, QuickInkSpacing.s2)
                            .padding(.vertical, 4)
                            .background(QuickInkColors.accentSoft)
                            .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.pill, style: .continuous))
                    }
                    .buttonStyle(.plain)
                }
                Button {
                    showRetagSheet = true
                } label: {
                    Image(systemName: "plus")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(QuickInkColors.inkSoft)
                        .frame(width: 26, height: 26)
                        .background(QuickInkColors.borderSoft)
                        .clipShape(Circle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Add tag")
            }
        }
    }

    /// "Places" row inside [detailsCard]. Shows one pill per attached
    /// location with a trailing "+" affordance. Mirror of Android's
    /// `LocationsRow`. Tapping a pill OR the "+" opens the picker.
    @ViewBuilder
    private func placesRow(for capture: CaptureSummary) -> some View {
        HStack(alignment: .firstTextBaseline) {
            Text("Places")
                .font(QuickInkFont.ui(11, weight: .medium))
                .foregroundStyle(QuickInkColors.inkSoft)
            Spacer()
            HStack(spacing: QuickInkSpacing.s1) {
                ForEach(attachedLocationNames, id: \.self) { name in
                    Button {
                        showPlacePicker = true
                    } label: {
                        Text(name)
                            .font(QuickInkFont.ui(11, weight: .medium))
                            .foregroundStyle(QuickInkColors.accent)
                            .padding(.horizontal, QuickInkSpacing.s2)
                            .padding(.vertical, 4)
                            .background(QuickInkColors.accentSoft)
                            .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.pill, style: .continuous))
                    }
                    .buttonStyle(.plain)
                }
                Button {
                    showPlacePicker = true
                } label: {
                    Image(systemName: "plus")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(QuickInkColors.inkSoft)
                        .frame(width: 26, height: 26)
                        .background(QuickInkColors.borderSoft)
                        .clipShape(Circle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Add place")
            }
        }
    }

    /// "People" row inside [detailsCard]. Same pattern as `placesRow`
    /// but each chip tap opens a Share/Edit action sheet for that
    /// person. Mirror of Android's `PeopleRow`.
    @ViewBuilder
    private func peopleRow(for capture: CaptureSummary) -> some View {
        HStack(alignment: .firstTextBaseline) {
            Text("People")
                .font(QuickInkFont.ui(11, weight: .medium))
                .foregroundStyle(QuickInkColors.inkSoft)
            Spacer()
            HStack(spacing: QuickInkSpacing.s1) {
                ForEach(attachedPeople) { person in
                    Button {
                        personActionTarget = person
                    } label: {
                        Text(person.name)
                            .font(QuickInkFont.ui(11, weight: .medium))
                            .foregroundStyle(QuickInkColors.accent)
                            .padding(.horizontal, QuickInkSpacing.s2)
                            .padding(.vertical, 4)
                            .background(QuickInkColors.accentSoft)
                            .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.pill, style: .continuous))
                    }
                    .buttonStyle(.plain)
                }
                Button {
                    showPeoplePicker = true
                } label: {
                    Image(systemName: "plus")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(QuickInkColors.inkSoft)
                        .frame(width: 26, height: 26)
                        .background(QuickInkColors.borderSoft)
                        .clipShape(Circle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Add person")
            }
        }
    }

    /// Names of every location currently attached to this capture,
    /// resolved in the same order the join rows arrived. Drops ids
    /// whose location row hasn't loaded yet (or was soft-deleted).
    private var attachedLocationNames: [String] {
        let byId = Dictionary(uniqueKeysWithValues: allLocations.map { ($0.id, $0) })
        return attachedLocationIds.compactMap { byId[$0]?.name }
    }

    /// People currently attached to this capture, resolved in the
    /// same order the join rows arrived. Mirror of
    /// `attachedLocationNames`.
    private var attachedPeople: [PersonEntity] {
        let byId = Dictionary(uniqueKeysWithValues: allPeople.map { ($0.id, $0) })
        return attachedPersonIds.compactMap { byId[$0] }
    }

    /// Hand the capture's PDF to the system share sheet. Mirror of
    /// Android's `shareCapturePdfWithPerson` — iOS doesn't surface
    /// Android-style recipient pre-targeting on `UIActivityViewController`,
    /// so the system share sheet opens with just the PDF and the
    /// user picks the recipient inside whichever app they choose.
    private func shareDocumentTo(person: PersonEntity) async {
        guard let capture = capture,
              let url = shareablePdfURL(from: capture) else { return }
        await MainActor.run {
            pdfShareItems = IdentifiedURLs(urls: [url])
        }
    }

    // MARK: - Actions card

    /// Quick-actions card matching the mockup: header + rows for
    /// Share as Image, Export as PDF, Move to folder, Delete (plus
    /// a business-card-only "Add to contact" row at the top). Each
    /// row is a full-width tappable button with an SF Symbol on the
    /// left.
    @ViewBuilder
    private func actionsCard(for capture: CaptureSummary) -> some View {
        VStack(alignment: .leading, spacing: QuickInkSpacing.s2) {
            HStack(spacing: QuickInkSpacing.s2) {
                Image(systemName: "square.grid.2x2")
                    .font(.system(size: 16, weight: .medium))
                    .foregroundStyle(QuickInkColors.inkSoft)
                Text("Actions")
                    .font(QuickInkFont.ui(13, weight: .semibold))
                    .foregroundStyle(QuickInkColors.ink)
            }

            VStack(spacing: 0) {
                // Business-card-only "Add to contact" row. Sits at
                // the top of the Actions list because it's the most
                // common action a user takes on a scanned card.
                if isBusinessCard(capture) {
                    Button {
                        Task { await openAddContactSheet() }
                    } label: {
                        actionRowContent(icon: "person.crop.circle.badge.plus", label: "Add to contact")
                    }
                    .buttonStyle(.plain)
                    actionDivider
                }

                // Video subtype gets a single "Share video" row in
                // place of the Share-as-Image / Export-as-PDF pair —
                // the PDF/image artifacts make no sense for a clip
                // whose only meaningful output is the recorded .mov.
                let isVideo = isVideoCapture(for: capture)
                if isVideo {
                    if let videoURL = Self.resolvedLocalURL(for: capture.videoUri) {
                        ShareLink(item: videoURL) {
                            actionRowContent(icon: "video", label: "Share video")
                        }
                        actionDivider
                    }
                } else {
                    if canShareAsImage(capture) {
                        Button {
                            Task { await prepareImageShare() }
                        } label: {
                            actionRowContent(
                                icon: "photo",
                                label: isPreparingImageShare ? "Preparing…" : "Share as Image"
                            )
                        }
                        .buttonStyle(.plain)
                        .disabled(isPreparingImageShare)
                        actionDivider
                    }

                    if let pdfURL = shareablePdfURL(from: capture) {
                        ShareLink(item: pdfURL) {
                            actionRowContent(icon: "arrow.down.doc", label: "Export as PDF")
                        }
                        actionDivider
                    }
                }

                Button { showFolderPicker = true } label: {
                    actionRowContent(icon: "folder", label: "Move to folder")
                }
                .buttonStyle(.plain)

                actionDivider

                Button { showTagPicker = true } label: {
                    actionRowContent(icon: "tag", label: "Manage tags")
                }
                .buttonStyle(.plain)

                actionDivider

                Button { showPlacePicker = true } label: {
                    actionRowContent(icon: "mappin.and.ellipse", label: "Manage places")
                }
                .buttonStyle(.plain)

                actionDivider

                Button { showPeoplePicker = true } label: {
                    actionRowContent(icon: "person.2", label: "Manage people")
                }
                .buttonStyle(.plain)

                actionDivider

                Button { showDeleteConfirm = true } label: {
                    actionRowContent(icon: "trash", label: "Delete", isDestructive: true)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(QuickInkSpacing.s3)
        .background(QuickInkColors.surface)
        .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous)
                .stroke(QuickInkColors.border, lineWidth: 1)
        )
    }

    /// The visual content of an action row — icon + label. Used both
    /// directly (inside `Button`) and as the label of a ShareLink.
    @ViewBuilder
    private func actionRowContent(icon: String, label: String, isDestructive: Bool = false) -> some View {
        HStack(spacing: QuickInkSpacing.s2) {
            Image(systemName: icon)
                .font(.system(size: 16, weight: .medium))
                .foregroundStyle(isDestructive ? QuickInkColors.danger : QuickInkColors.inkSoft)
                .frame(width: 22)
            Text(label)
                .font(QuickInkText.caption)
                .foregroundStyle(isDestructive ? QuickInkColors.danger : QuickInkColors.ink)
                .lineLimit(1)
                .truncationMode(.tail)
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.vertical, QuickInkSpacing.s2)
        .contentShape(Rectangle())
    }

    /// Subtle divider between rows in the [actionsCard]. Drawn as a
    /// 1pt rectangle in the soft border color so adjacent rows feel
    /// grouped rather than floating.
    private var actionDivider: some View {
        Rectangle()
            .fill(QuickInkColors.borderSoft)
            .frame(height: 1)
    }

    // MARK: - Loading skeleton

    /// Placeholder shown while `loadCapture()` is in flight. Mirrors
    /// the resolved layout (preview slab, title, breadcrumb, details
    /// card) so the screen doesn't visually jump when data lands.
    @ViewBuilder
    private var loadingSkeleton: some View {
        VStack(alignment: .leading, spacing: QuickInkSpacing.s4) {
            RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous)
                .fill(QuickInkColors.borderSoft)
                .frame(height: 320)

            RoundedRectangle(cornerRadius: QuickInkRadius.sm, style: .continuous)
                .fill(QuickInkColors.borderSoft)
                .frame(height: 32)
                .frame(maxWidth: 240)

            RoundedRectangle(cornerRadius: QuickInkRadius.sm, style: .continuous)
                .fill(QuickInkColors.borderSoft)
                .frame(height: 16)
                .frame(maxWidth: 180)
        }
        .redacted(reason: .placeholder)
    }

    // MARK: - Tag display

    /// Earliest-attached active tag name on this capture, looked up
    /// against `categoriesVM.categories` for the display label. The
    /// pre-A.3c `captures.category` field's role lives here now; it
    /// drives the primary-label badge, the Details "Folder" row, the
    /// retag-sheet `current` selection, and the Business Card mode
    /// switch.
    private var primaryTagName: String? {
        let byId = Dictionary(uniqueKeysWithValues: categoriesVM.categories.map { ($0.id, $0.name) })
        return attachedTagIds.compactMap { byId[$0] }.first
    }

    // MARK: - File metadata helpers

    /// Resolve the file-type label for the Details row. For local
    /// PDFs, surface whether the saved artifact is QuickInk's
    /// compressed PDF or a raw scanner/import PDF. Video captures
    /// keep their user-facing "Video" label because the playable
    /// movie is the primary artifact.
    private func fileTypeLabel(for capture: CaptureSummary) -> String {
        let isPhotoSource  = capture.source == "photo"
        let isImportSource = capture.source == "import"
        if isVideoCapture(for: capture)              { return "Video" }
        if isPhotoSource                             { return "Photo" }
        if pdfURL(from: capture) != nil {
            switch pdfStorageKind {
            case .compressed: return "Compressed PDF document"
            case .raw:        return "PDF document"
            case .none:       return "PDF document"
            }
        }
        if isImportSource                            { return "Image" }
        if loadedPreviewImage(for: capture) != nil   { return "Image" }
        return "Document"
    }

    private func isVideoCapture(for capture: CaptureSummary) -> Bool {
        let hasVideoArtifact = (capture.videoUri?.isEmpty == false) ||
            (capture.videoDriveFileId?.isEmpty == false)
        return capture.source == "video" ||
            (capture.source == "photo" && hasVideoArtifact)
    }

    private var deleteDialogTitle: String {
        guard let capture else { return "Delete this capture?" }
        return "Delete this \(deleteNoun(for: capture))?"
    }

    private var deleteDialogMessage: String {
        guard let capture else {
            return "This capture and its related notes will be removed from this device and your other devices on the next sync."
        }
        let related = isVideoCapture(for: capture)
            ? "related notes"
            : "recognised text and related notes"
        return "This \(deleteNoun(for: capture)) and its \(related) will be removed from this device and your other devices on the next sync."
    }

    private func deleteNoun(for capture: CaptureSummary) -> String {
        if isVideoCapture(for: capture) { return "video" }
        switch capture.source {
        case "photo":  return "photo"
        case "import": return "imported item"
        default:       return "scan"
        }
    }

    /// Format a byte count as "1.2 MB" / "340 KB" using the system
    /// formatter so the locale-aware separator is correct.
    private func formatBytes(_ bytes: Int64) -> String {
        let formatter = ByteCountFormatter()
        formatter.countStyle = .file
        return formatter.string(fromByteCount: bytes)
    }

    /// True for scans whose primary tag is "business-card". Drives
    /// the conditional "Add to contact" action row. Case-insensitive
    /// so "business-card" / "Business-Card" / "BUSINESS-CARD" all
    /// trip the gate. Reads through `primaryTagName` (the post-A.3c
    /// replacement for `captures.category`).
    private func isBusinessCard(_ capture: CaptureSummary) -> Bool {
        (primaryTagName ?? "").lowercased() == "business-card"
    }

    /// Run the bbox-aware [BusinessCardExtractor] over the capture's
    /// stored OCR blocks and open the editable review sheet. Empty
    /// or missing OCR → opens the sheet with empty fields and the
    /// user fills them in by hand.
    private func openAddContactSheet() async {
        let extracted: ExtractedContact = await loadAndExtract()
        await MainActor.run {
            businessCardExtraction = IdentifiedExtraction(extracted: extracted)
        }
    }

    /// Read every OCR row's `blocks_json` for the capture, decode the
    /// `OcrBlock` lists, and run the extractor. Decoder is permissive
    /// — a single malformed row doesn't fail the rest of the pages.
    private func loadAndExtract() async -> ExtractedContact {
        let dbQueue = QuickInkDatabase.shared.dbQueue
        do {
            let payloads: [String] = try await dbQueue.read { db -> [String] in
                try String.fetchAll(db, sql: """
                    SELECT blocks_json FROM ocr_results
                    WHERE capture_id = ? AND deleted_at IS NULL
                    ORDER BY page_index ASC
                    """, arguments: [captureId])
            }
            let decoder = JSONDecoder()
            var allBlocks: [OcrBlock] = []
            for raw in payloads {
                guard let data = raw.data(using: .utf8) else { continue }
                if let blocks = try? decoder.decode([OcrBlock].self, from: data) {
                    allBlocks.append(contentsOf: blocks)
                }
            }
            return BusinessCardExtractor.extract(allBlocks)
        } catch {
            return ExtractedContact.empty
        }
    }

    /// Lazy load the on-disk PDF size so the Details row can show it.
    /// Best-effort — leaves `pdfFileSize = nil` (Details renders "—")
    /// if the file isn't readable.
    private func loadFileSize() async {
        guard let url = pdfURL(from: capture) else {
            self.pdfFileSize = nil
            return
        }
        let size = await Task.detached(priority: .utility) { () -> Int64? in
            do {
                let attrs = try FileManager.default.attributesOfItem(atPath: url.path)
                return attrs[.size] as? Int64
            } catch {
                return nil
            }
        }.value
        self.pdfFileSize = size
    }

    /// Reads the small marker QuickInk writes into compressed PDFs.
    /// Unmarked local PDFs are treated as raw scanner/import PDFs.
    private func loadPdfStorageKind() async {
        guard let url = pdfURL(from: capture) else {
            self.pdfStorageKind = nil
            return
        }
        let kind = await Task.detached(priority: .utility) { () -> PDFStorageKind? in
            guard let handle = try? FileHandle(forReadingFrom: url) else { return nil }
            defer { try? handle.close() }
            guard let data = try? handle.read(upToCount: 4096) else { return nil }
            let marker = Data(CompressedImagePdfWriter.pdfMarker.utf8)
            let legacySignature = Data(PDFStorageKind.legacyCompressedSignature.utf8)
            let dctDecode = Data("/DCTDecode".utf8)
            let firstImageName = Data("/Im1".utf8)
            let isMarkedCompressed = data.range(of: marker) != nil
            let isLegacyCompressed = data.range(of: legacySignature) != nil &&
                data.range(of: dctDecode) != nil &&
                data.range(of: firstImageName) != nil
            return (isMarkedCompressed || isLegacyCompressed) ? .compressed : .raw
        }.value
        self.pdfStorageKind = kind
    }

    // MARK: - Share as image

    /// True when there's *something* we can rasterise for the
    /// Share-as-Image row — either a PDF on disk (rendered per-page)
    /// or the legacy single-frame preview JPEG. Drives whether the
    /// row appears at all; if neither is available we hide it rather
    /// than showing a row that fails on tap.
    private func canShareAsImage(_ capture: CaptureSummary) -> Bool {
        if pdfURL(from: capture) != nil { return true }
        return loadedPreviewImage(for: capture) != nil
    }

    /// Rasterise the capture's pages to in-memory UIImages and hand
    /// them to the WhatsApp-style editor. The editor's onDone
    /// callback writes the final (cropped / annotated) images to
    /// temp JPEGs and presents the system share sheet. Guarded by
    /// [isPreparingImageShare] so a double-tap doesn't queue a
    /// second render in parallel.
    private func prepareImageShare() async {
        guard !isPreparingImageShare else { return }
        isPreparingImageShare = true
        defer { isPreparingImageShare = false }
        let images = await renderImages()
        guard !images.isEmpty else { return }
        pendingEditorBundle = EditorPagesBundle(pages: images)
    }

    /// Rasterise the capture's pages into UIImages held in memory.
    /// Multi-page PDFs return one image per page; image-only
    /// (PDF-less) captures fall back to the preview JPEG. Empty
    /// array means "nothing to share" — caller bails.
    private func renderImages() async -> [UIImage] {
        if let pdfURL = pdfURL(from: capture) {
            return await Task.detached(priority: .userInitiated) {
                guard let doc = PDFDocument(url: pdfURL) else { return [] }
                return doc.renderPageImages(scale: 2.0)
            }.value
        }
        if let cap = capture, let img = loadedPreviewImage(for: cap) {
            return [img]
        }
        return []
    }

    /// Bake the capture's notes onto the first shared page as a
    /// caption-bar footer. Blank notes are a no-op so the original
    /// images pass through unchanged. Only page 1 gets the footer —
    /// recipients of multi-page scans see the note once, attached to
    /// the visually-primary page.
    private static func embedNotesFooter(
        on images: [UIImage],
        notes: String
    ) -> [UIImage] {
        guard !notes.isEmpty, let first = images.first else { return images }
        let composited = drawNotesFooter(on: first, notes: notes)
        var out = images
        out[0] = composited
        return out
    }

    /// Compose `image` with a white footer bar containing `notes`.
    /// Footer width matches the image; height grows with the wrapped
    /// text up to a cap of ~30% of the image height (overflow tail
    /// truncated with an ellipsis). Renders at the source image's
    /// scale so JPEG encoding lands at the same DPI as the page.
    private static func drawNotesFooter(on image: UIImage, notes: String) -> UIImage {
        let width  = image.size.width
        let height = image.size.height
        let pad: CGFloat        = max(24, width * 0.04)
        let headerSize: CGFloat = max(16, width * 0.022)
        let bodySize: CGFloat   = max(20, width * 0.028)

        let headerFont = UIFont.systemFont(ofSize: headerSize, weight: .semibold)
        let bodyFont   = UIFont.systemFont(ofSize: bodySize,   weight: .regular)
        let para = NSMutableParagraphStyle()
        para.lineSpacing = bodySize * 0.18

        let headerAttrs: [NSAttributedString.Key: Any] = [
            .font: headerFont,
            .foregroundColor: UIColor(white: 0.45, alpha: 1),
            .kern: 1.4,
        ]
        let bodyAttrs: [NSAttributedString.Key: Any] = [
            .font: bodyFont,
            .foregroundColor: UIColor(white: 0.12, alpha: 1),
            .paragraphStyle: para,
        ]

        let headerString = NSAttributedString(string: "NOTES", attributes: headerAttrs)
        let bodyString   = NSAttributedString(string: notes, attributes: bodyAttrs)

        let textWidth = width - pad * 2
        let headerHeight = ceil(headerString.size().height)
        let headerToBody: CGFloat = bodySize * 0.5
        let maxBodyHeight = max(bodySize * 4, height * 0.28)
        let bodyBounds = bodyString.boundingRect(
            with: CGSize(width: textWidth, height: .greatestFiniteMagnitude),
            options: [.usesLineFragmentOrigin, .usesFontLeading],
            context: nil
        )
        let bodyHeight  = min(ceil(bodyBounds.height), maxBodyHeight)
        let footerHeight = pad + headerHeight + headerToBody + bodyHeight + pad
        let outputSize  = CGSize(width: width, height: height + footerHeight)

        let format = UIGraphicsImageRendererFormat.default()
        format.scale = image.scale
        format.opaque = true
        let renderer = UIGraphicsImageRenderer(size: outputSize, format: format)
        return renderer.image { ctx in
            UIColor.white.setFill()
            ctx.fill(CGRect(origin: .zero, size: outputSize))
            image.draw(in: CGRect(x: 0, y: 0, width: width, height: height))

            // Thin separator between the scan and the notes bar.
            UIColor(white: 0.86, alpha: 1).setFill()
            ctx.fill(CGRect(x: 0, y: height, width: width, height: 1))

            let headerOrigin = CGPoint(x: pad, y: height + pad)
            headerString.draw(at: headerOrigin)

            let bodyRect = CGRect(
                x: pad,
                y: height + pad + headerHeight + headerToBody,
                width: textWidth,
                height: bodyHeight
            )
            bodyString.draw(with: bodyRect, options: [.usesLineFragmentOrigin], context: nil)
        }
    }

    /// Write a batch of UIImages out as JPEGs in a fresh per-call temp
    /// subdirectory. The unique-per-call subdir keeps file names
    /// (`page-1.jpg`, `page-2.jpg`, …) human-readable in the iOS share
    /// sheet preview without clobbering a previous share's files when
    /// the user opens the same scan twice in quick succession.
    private static func writeJpegsToTemp(_ images: [UIImage], base: String) -> [URL] {
        let dirName = "quickink-share-\(base)-\(UUID().uuidString.prefix(8))"
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(dirName)
        do {
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        } catch {
            return []
        }
        var urls: [URL] = []
        for (index, image) in images.enumerated() {
            guard let data = image.jpegData(compressionQuality: 0.92) else { continue }
            let url = dir.appendingPathComponent("page-\(index + 1).jpg")
            if (try? data.write(to: url)) != nil {
                urls.append(url)
            }
        }
        return urls
    }

    // MARK: - Load

    /// Soft-delete the in-view capture and dismiss back to Home.
    /// The sync worker mirrors the tombstone to Drive on its next
    /// pass; the home rail's `deleted_at IS NULL` filter hides the
    /// row immediately.
    private func deleteCapture() async {
        do {
            try await CaptureRepository().softDelete(id: captureId)
            await QuickInkSyncEnvironment.shared.refreshPendingPushState()
            onBack()
        } catch {
            print("ScanDetailScreen.deleteCapture failed: \(error)")
        }
    }

    /// Persist a primary-tag change for this capture and refresh
    /// the in-screen state. Pass `nil` / blank to clear all
    /// attached tags. Best-effort — a transient SQL failure leaves
    /// the pill where it was; the user can re-tap to retry.
    private func applyRetag(_ category: String?) async {
        do {
            try await CaptureRepository().attachOrEnsurePrimaryTag(
                captureId: captureId,
                userId:    userId,
                name:      category
            )
            // Refresh the loaded `capture` so the pill flips —
            // simpler than mutating the optional struct in place,
            // and the SELECT is cheap. The tag observation
            // pipeline (`tagIdsCancellable`) updates the badge
            // independently.
            await loadCapture()
        } catch {
            print("ScanDetailScreen.applyRetag failed: \(error)")
        }
    }

    /// Persist a title edit. Empty / whitespace-only input is stored
    /// as `nil` so the Library card falls back to its OCR/category/
    /// "Untitled" cascade. Refreshes `capture` after the write so the
    /// header + title row update without a manual reload.
    private func applyTitle(_ raw: String) async {
        let trimmed = raw.trimmingCharacters(in: .whitespaces)
        let next: String? = trimmed.isEmpty ? nil : trimmed
        do {
            try await CaptureRepository().setTitle(
                captureId: captureId,
                title:     next
            )
            await loadCapture()
        } catch {
            print("ScanDetailScreen.applyTitle failed: \(error)")
        }
    }

    /// Persist a notes edit. The repository trims + collapses empty
    /// input to nil so the card's empty-state branch reads correctly
    /// after a "clear all" edit. Refreshes `capture` so the card
    /// updates without a manual reload.
    private func applyNotes(_ raw: String) async {
        do {
            try await CaptureRepository().setNotes(
                captureId: captureId,
                notes:     raw
            )
            await loadCapture()
        } catch {
            print("ScanDetailScreen.applyNotes failed: \(error)")
        }
    }

    private func loadCapture() async {
        let dbQueue = QuickInkDatabase.shared.dbQueue
        do {
            let result = try await dbQueue.read { db -> CaptureSummary? in
                try CaptureSummary.fetchOne(db, sql: """
                    SELECT id, title, preview_uri, pdf_uri, page_count,
                           created_at, source, latitude, longitude,
                           locality, sub_locality, address, notes,
                           folder_id, last_opened_at, last_opened_page,
                           last_opened_device, video_uri, video_drive_file_id
                    FROM captures
                    WHERE id = ? AND deleted_at IS NULL
                    LIMIT 1
                    """, arguments: [captureId])
            }
            self.capture = result
        } catch {
            print("ScanDetailScreen.loadCapture failed: \(error)")
        }
    }

    /// Convenience alias used by the folder-picker callback. Same
    /// SELECT as the first-load path; SwiftUI re-renders the Details
    /// card off the @State change.
    private func reloadCapture() async {
        await loadCapture()
    }

    // MARK: - Workspace v1 Continue card signal

    /// Schedule a debounced write of last_opened_* — runs 500ms
    /// after the user lands on a page so a quick swipe doesn't
    /// churn the row.
    private func scheduleLastOpenedWrite() {
        lastOpenedDebounceTask?.cancel()
        let cid = captureId
        let page = selectedPageIndex + 1
        let device = WorkspaceDeviceId.current
        lastOpenedDebounceTask = Task {
            try? await Task.sleep(nanoseconds: 500_000_000)
            if Task.isCancelled { return }
            try? await CaptureRepository().setLastOpened(
                captureId: cid,
                openedAt:  IsoClock.nowIso(),
                page:      page,
                deviceId:  device,
            )
        }
    }

    /// When the capture has lat/lon but no place names — typical
    /// outcome when `CLGeocoder` was rate-limited or offline at scan
    /// time — retry the reverse geocode here and persist the
    /// result. Runs in the background; the UI reloads via
    /// [loadCapture] when the new values land. No retry tracking
    /// state: CLGeocoder's own rate-limit caps the retry frequency,
    /// and an opened-twice-in-a-row screen is fine to ask twice.
    private func retryReverseGeocodeIfNeeded() async {
        guard let cap = capture else {
            print("[Location] retry: no capture loaded, skip")
            return
        }
        print("[Location] retry: row state lat=\(cap.latitude.map { "\($0)" } ?? "nil") lon=\(cap.longitude.map { "\($0)" } ?? "nil") locality=\(cap.locality ?? "nil") subLocality=\(cap.subLocality ?? "nil") address=\(cap.address ?? "nil")")
        guard let lat = cap.latitude, let lon = cap.longitude else {
            print("[Location] retry: no coordinates, nothing to backfill")
            return
        }
        let hasLocality    = !(cap.locality?.trimmingCharacters(in: .whitespaces).isEmpty ?? true)
        let hasSubLocality = !(cap.subLocality?.trimmingCharacters(in: .whitespaces).isEmpty ?? true)
        let hasAddress     = !(cap.address?.trimmingCharacters(in: .whitespaces).isEmpty ?? true)
        // Skip when every geocoded field is already populated.
        // Otherwise re-run — we want to backfill any of the three
        // that the original geocode missed.
        if hasLocality && hasSubLocality && hasAddress {
            print("[Location] retry: already have locality + subLocality + address, skip")
            return
        }

        let clLocation = CLLocation(latitude: lat, longitude: lon)
        guard let placemark = try? await CLGeocoder().reverseGeocodeLocation(clLocation).first else {
            print("[Location] retry: geocode failed")
            return
        }
        print("[Location] retry: placemark fields: name=\(placemark.name ?? "nil") thoroughfare=\(placemark.thoroughfare ?? "nil") subThoroughfare=\(placemark.subThoroughfare ?? "nil") subLocality=\(placemark.subLocality ?? "nil") locality=\(placemark.locality ?? "nil") subAdministrativeArea=\(placemark.subAdministrativeArea ?? "nil") administrativeArea=\(placemark.administrativeArea ?? "nil") postalCode=\(placemark.postalCode ?? "nil") country=\(placemark.country ?? "nil") isoCountryCode=\(placemark.isoCountryCode ?? "nil")")
        print("[Location] retry: placemark raw locality=\(placemark.locality ?? "nil") subLocality=\(placemark.subLocality ?? "nil")")
        // Same region-aware derivation + dedupe as the write path
        // in LocationService — for Indian placemarks promote the
        // metropolitan name (subAdministrativeArea, suffix-
        // stripped) into the "City" slot and demote locality to
        // "Area"; elsewhere keep the geocoder's own labelling.
        let derived = LocationService.derivePlaceNames(from: placemark)
        print("[Location] retry: derive → locality=\(derived.locality ?? "nil") subLocality=\(derived.subLocality ?? "nil")")
        let names = LocationService.dedupePlaceNames(
            locality:    derived.locality,
            subLocality: derived.subLocality
        )
        let newAddress = LocationService.formatAddress(from: placemark)
        print("[Location] retry: dedupe → locality=\(names.locality ?? "nil") subLocality=\(names.subLocality ?? "nil") address=\(newAddress ?? "nil")")
        // Bail when the retry yields nothing useful — saves a
        // pointless write + a no-op sync push.
        guard names.locality != nil || names.subLocality != nil || newAddress != nil else {
            print("[Location] retry: nothing useful to persist, skip")
            return
        }

        do {
            try await CaptureRepository().updateLocation(
                captureId:   captureId,
                locality:    names.locality    ?? cap.locality,
                subLocality: names.subLocality ?? cap.subLocality,
                address:     newAddress        ?? cap.address
            )
            print("[Location] retry: persisted update for capture=\(captureId)")
            await loadCapture()
        } catch {
            print("[Location] retry: persist failed \(error)")
        }
    }

    private func loadedPreviewImage(for capture: CaptureSummary) -> UIImage? {
        guard let raw = capture.previewUri, !raw.isEmpty else { return nil }
        let path: String? = {
            if let url = URL(string: raw), url.isFileURL { return url.path }
            return raw
        }()
        guard let path else { return nil }
        return UIImage(contentsOfFile: path)
    }

    private func friendlyDate(_ iso: String) -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let date = formatter.date(from: iso) {
            let f = DateFormatter()
            f.dateStyle = .medium
            f.timeStyle = .short
            return f.string(from: date)
        }
        return iso
    }
}

// MARK: - Sheet identity wrappers

/// SwiftUI's `.sheet(item:)` requires `Identifiable`. The shared
/// `ExtractedContact` is `Equatable` only — wrapping here keeps the
/// shared module unchanged.
private struct IdentifiedExtraction: Identifiable {
    let id = UUID()
    let extracted: ExtractedContact
}

/// Same wrapper as [IdentifiedExtraction] for the user-edited form
/// before it lands in the system contact-creation sheet.
private struct IdentifiedForm: Identifiable {
    let id = UUID()
    let form: EditableContact
}

/// Identifiable wrapper around the temp-file URLs produced for the
/// Share-as-Image flow. The fresh `id` per instance ensures
/// `.sheet(item:)` re-presents when the user shares twice in a row.
private struct IdentifiedURLs: Identifiable {
    let id = UUID()
    let urls: [URL]
}

/// Identifiable wrapper around the video-clip URL for the player
/// sheet. Used so `.sheet(item:)` re-presents on a fresh `videoUri`
/// even when the underlying string is identical to the previous
/// presentation.
private struct IdentifiedURL: Identifiable {
    let id = UUID()
    let url: URL
}

/// Edge-to-edge video player for the hold-to-record Photo-mode
/// clip. Wraps AVKit's `VideoPlayer` (iOS 14+) over a black
/// background that ignores safe areas, with the close button as
/// a top-trailing overlay rather than a sibling row above the
/// player — keeps the video flush with the screen edges instead
/// of sitting under a 50pt header row. AVKit handles
/// play/pause/scrub controls automatically (tap-to-reveal).
private struct CaptureVideoPlayerSheet: View {
    let url: URL
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ZStack(alignment: .topTrailing) {
            Color.black.ignoresSafeArea()
            AVKit.VideoPlayer(player: AVPlayer(url: url))
                .ignoresSafeArea()
            Button(action: { dismiss() }) {
                Image(systemName: "xmark")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(width: 36, height: 36)
                    .background(Color.black.opacity(0.45))
                    .clipShape(Circle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Close video player")
            .padding(QuickInkSpacing.s4)
        }
        .preferredColorScheme(.dark)
        .statusBarHidden(true)
    }
}

/// Identifiable wrapper around the rasterised pages so the
/// `.fullScreenCover(item:)` modifier can detect a fresh editor
/// session even when the underlying `[UIImage]` array shape is the
/// same as a previous render.
private struct EditorPagesBundle: Identifiable {
    let id = UUID()
    let pages: [UIImage]
}

/// Lightweight UIKit bridge for UIActivityViewController. SwiftUI's
/// ShareLink only takes statically-resolvable items, but the
/// Share-as-Image flow renders pages on demand — so we drive it via
/// the bridged activity controller against the rendered file URLs.
private struct ActivityView: UIViewControllerRepresentable {
    let activityItems: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: activityItems, applicationActivities: nil)
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}

/// Split a comma- (or newline-) separated string into trimmed,
/// non-empty entries. Used by the contact-form sheet to expand the
/// review form's flat-string fields back into the structured arrays
/// `AddContactSheet` expects.
private func splitCsv(_ s: String) -> [String] {
    s.split(whereSeparator: { $0 == "," || $0 == "\n" })
        .map { $0.trimmingCharacters(in: .whitespaces) }
        .filter { !$0.isEmpty }
}
