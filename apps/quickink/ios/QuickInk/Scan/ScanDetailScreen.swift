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
import GRDB
import PDFKit

struct ScanDetailScreen: View {

    let captureId: String
    let userId: String
    let onBack: () -> Void

    @StateObject private var categoriesVM: CategoryListViewModel

    @State private var capture: CaptureSummary?
    @State private var ocrPages: [OcrPagePreview] = []
    @State private var isLoadingOcr = false
    @State private var showOcr = false
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
    /// Per-page OCR edit state. `editingPageId` is the row currently
    /// in edit mode (only one at a time); `ocrDraft` is the in-flight
    /// text. Both reset when Save commits or Cancel discards.
    @State private var editingPageId: String? = nil
    @State private var ocrDraft = ""
    /// Drives the fullscreen flipbook viewer (`FullscreenPdfViewer`).
    /// Set true by the overlay button on the inline preview; cleared
    /// by the cover's close affordance or a system back-swipe.
    @State private var showFullscreenViewer = false

    init(captureId: String, userId: String, onBack: @escaping () -> Void) {
        self.captureId = captureId
        self.userId = userId
        self.onBack = onBack
        _categoriesVM = StateObject(
            wrappedValue: CategoryListViewModel(userId: userId)
        )
    }

    var body: some View {
        VStack(spacing: 0) {
            topBar

            ScrollView {
                VStack(alignment: .leading, spacing: QuickInkSpacing.s5) {
                    if let capture {
                        previewBlock(for: capture)
                        titleSection(for: capture)
                        metaBlock(for: capture)
                        ocrSection
                    } else {
                        ProgressView()
                            .frame(maxWidth: .infinity)
                            .padding(.top, QuickInkSpacing.s8)
                    }
                }
                .padding(.horizontal, QuickInkSpacing.s5)
                .padding(.top, QuickInkSpacing.s4)
                .padding(.bottom, QuickInkSpacing.s8)
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
            await loadCapture()
        }
        .alert("Delete this scan?", isPresented: $showDeleteConfirm) {
            Button("Cancel", role: .cancel) {}
            Button("Delete", role: .destructive) {
                Task { await deleteCapture() }
            }
        } message: {
            Text("The scan and its recognised text will be removed from this device and your other devices on the next sync.")
        }
        // Retag picker — tapping the category pill (or the "Tag
        // scan" affordance) opens this. One button per active
        // category plus a "Remove tag" affordance when the capture
        // already has one. Each button calls `applyRetag(_:)` which
        // persists via `CaptureRepository.setCategory(...)` and
        // refreshes the in-screen capture state so the pill updates
        // immediately.
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
            if let current = capture?.category, !current.isEmpty {
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

    /// Computed top-bar title — prefers the user-set `title`, falls
    /// back to the category, then to the generic "Scan" label so the
    /// header is never empty.
    private var displayedTopBarTitle: String {
        if let t = capture?.title?.trimmingCharacters(in: .whitespaces),
           !t.isEmpty {
            return t
        }
        return capture?.category ?? "Scan"
    }

    // MARK: - Top bar

    @ViewBuilder
    private var topBar: some View {
        HStack(spacing: 0) {
            Button(action: onBack) {
                Image(systemName: "chevron.left")
                    .font(.system(size: 18))
                    .foregroundStyle(QuickInkColors.ink)
                    .padding(QuickInkSpacing.s3)
            }
            .accessibilityLabel("Back")

            Text(displayedTopBarTitle)
                .font(QuickInkText.pageTitle)
                .foregroundStyle(QuickInkColors.ink)
                .lineLimit(1)
                .truncationMode(.tail)

            Spacer()

            // Share/Export PDF — visible whenever we have a non-empty
            // pdf URI on the capture row. We deliberately do NOT
            // file-exists-check here: an empty share sheet is
            // recoverable (user gets a system error) but a missing
            // button is dead-end. ShareLink handles the URL itself.
            if let pdfURL = shareablePdfURL(from: capture) {
                ShareLink(item: pdfURL) {
                    Image(systemName: "square.and.arrow.up")
                        .font(.system(size: 18))
                        .foregroundStyle(QuickInkColors.ink)
                        .padding(QuickInkSpacing.s3)
                }
                .accessibilityLabel("Share scan")
            }

            Button(action: { showDeleteConfirm = true }) {
                Image(systemName: "trash")
                    .font(.system(size: 18))
                    .foregroundStyle(QuickInkColors.danger)
                    .padding(QuickInkSpacing.s3)
            }
            .accessibilityLabel("Delete scan")
        }
        .padding(.horizontal, QuickInkSpacing.s2)
        .padding(.top, QuickInkSpacing.s2)
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
    @ViewBuilder
    private func previewBlock(for capture: CaptureSummary) -> some View {
        if let pdfURL = pdfURL(from: capture) {
            if capture.pageCount > 1 {
                pageTurnViewer(for: pdfURL, capture: capture)
                    .overlay(alignment: .topTrailing) { fullscreenChip }
            } else {
                PDFKitView(url: pdfURL, backgroundColor: QuickInkColors.surface)
                    .frame(maxWidth: .infinity)
                    .frame(height: pdfPreviewHeight(for: capture))
                    .background(QuickInkColors.surface)
                    .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous)
                            .stroke(QuickInkColors.border, lineWidth: 1)
                    )
                    .overlay(alignment: .topTrailing) { fullscreenChip }
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
                .background(QuickInkColors.surface)
                .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous)
                        .stroke(QuickInkColors.border, lineWidth: 1)
                )
        } else {
            ZStack {
                QuickInkColors.paper2
                Image(systemName: "doc.text.fill")
                    .font(.system(size: 64))
                    .foregroundStyle(QuickInkColors.muted)
            }
            .frame(maxWidth: .infinity)
            .frame(height: 320)
            .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous))
        }
    }

    /// Top-trailing pill button that opens [showFullscreenViewer]
    /// against the current capture's PDF. Mirror of Android's
    /// `Icons.Filled.Fullscreen` chip on `PageTurnPdfView` /
    /// `PdfPagesView` — same dark-on-light contrast (ink @ 55% with
    /// a white icon) so the chip stays unmistakeable on top of the
    /// white scan surface.
    @ViewBuilder
    private var fullscreenChip: some View {
        Button(action: { showFullscreenViewer = true }) {
            Image(systemName: "arrow.up.left.and.arrow.down.right")
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(QuickInkColors.textOnAccent)
                .frame(width: 40, height: 40)
                .background(QuickInkColors.ink.opacity(0.55))
                .clipShape(Circle())
        }
        .buttonStyle(.plain)
        .padding(QuickInkSpacing.s3)
        .accessibilityLabel("View fullscreen")
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
                PageTurnPdfView(pageImages: pageImages)
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

    /// Editable title row, sitting between the preview and the
    /// metadata pills. Shows the persisted title when one is set;
    /// otherwise renders an "Untitled scan" placeholder in muted ink
    /// so the empty state is clearly an affordance, not a label.
    /// Whole row is a Button — tap opens the title editor alert.
    @ViewBuilder
    private func titleSection(for capture: CaptureSummary) -> some View {
        let displayed: String? = {
            let trimmed = capture.title?.trimmingCharacters(in: .whitespaces) ?? ""
            return trimmed.isEmpty ? nil : trimmed
        }()
        Button {
            titleDraft = capture.title ?? ""
            showTitleEditor = true
        } label: {
            HStack(spacing: QuickInkSpacing.s3) {
                Text(displayed ?? "Untitled scan")
                    .font(QuickInkText.heading)
                    .foregroundStyle(displayed != nil ? QuickInkColors.ink : QuickInkColors.muted)
                    .lineLimit(2)
                    .multilineTextAlignment(.leading)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Image(systemName: "pencil")
                    .font(.system(size: 14, weight: .medium))
                    .foregroundStyle(QuickInkColors.muted)
            }
            .padding(QuickInkSpacing.s4)
            .background(QuickInkColors.surface)
            .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous)
                    .stroke(QuickInkColors.border, lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Edit title")
    }

    @ViewBuilder
    private func metaBlock(for capture: CaptureSummary) -> some View {
        HStack(spacing: QuickInkSpacing.s2) {
            metaPill(text: friendlyDate(capture.createdAt))
            if capture.pageCount > 1 {
                metaPill(text: "\(capture.pageCount) pages")
            }
            // Category affordance — a tappable pill the user can
            // hit to retag the saved scan. When the capture already
            // has a tag, the pill renders the tag with the accent
            // treatment; when it doesn't, we fall back to a muted
            // "+ Tag scan" affordance so retagging is still
            // discoverable. Both routes open the same retag sheet.
            tagPill(for: capture)
            Spacer()
        }
    }

    @ViewBuilder
    private func tagPill(for capture: CaptureSummary) -> some View {
        let hasTag = !(capture.category ?? "").isEmpty
        Button {
            showRetagSheet = true
        } label: {
            HStack(spacing: QuickInkSpacing.s1) {
                Image(systemName: hasTag ? "tag.fill" : "tag")
                    .font(.system(size: 11, weight: .medium))
                Text(hasTag ? (capture.category ?? "") : "Tag scan")
                    .font(QuickInkText.caption)
            }
            .foregroundStyle(hasTag ? QuickInkColors.accent : QuickInkColors.inkSoft)
            .padding(.horizontal, QuickInkSpacing.s3)
            .padding(.vertical, QuickInkSpacing.s2)
            .background(hasTag ? QuickInkColors.accentSoft : QuickInkColors.borderSoft)
            .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.pill, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(hasTag ? "Edit tag" : "Add tag")
    }

    @ViewBuilder
    private func metaPill(text: String, accent: Bool = false) -> some View {
        Text(text)
            .font(QuickInkText.caption)
            .foregroundStyle(accent ? QuickInkColors.accent : QuickInkColors.inkSoft)
            .padding(.horizontal, QuickInkSpacing.s3)
            .padding(.vertical, QuickInkSpacing.s2)
            .background(accent ? QuickInkColors.accentSoft : QuickInkColors.borderSoft)
            .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.pill, style: .continuous))
    }

    // MARK: - OCR

    @ViewBuilder
    private var ocrSection: some View {
        VStack(alignment: .leading, spacing: QuickInkSpacing.s3) {
            Button(action: toggleOcr) {
                HStack {
                    Image(systemName: showOcr ? "chevron.down" : "chevron.right")
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(QuickInkColors.muted)
                    Text(showOcr ? "Hide extracted text" : "Show extracted text")
                        .font(QuickInkText.body)
                        .foregroundStyle(QuickInkColors.ink)
                    Spacer()
                    if isLoadingOcr {
                        ProgressView()
                            .scaleEffect(0.7)
                    }
                }
                .padding(QuickInkSpacing.s4)
                .background(QuickInkColors.surface)
                .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous)
                        .stroke(QuickInkColors.border, lineWidth: 1)
                )
            }
            .buttonStyle(.plain)

            if showOcr {
                if ocrPages.isEmpty {
                    Text("No text recognised on this scan.")
                        .font(QuickInkText.meta)
                        .foregroundStyle(QuickInkColors.inkSoft)
                        .padding(QuickInkSpacing.s4)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(QuickInkColors.surface)
                        .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous))
                } else {
                    VStack(alignment: .leading, spacing: QuickInkSpacing.s3) {
                        ForEach(ocrPages) { page in
                            ocrPageCard(for: page)
                        }
                    }
                }
            }
        }
    }

    /// One page card inside [ocrSection]. Read mode renders the OCR
    /// text with `.textSelection(.enabled)` so the user can copy it;
    /// tapping the pencil flips into edit mode where the text becomes
    /// a `TextEditor`. Save persists via
    /// [CaptureRepository.setOcrText]; Cancel discards the draft and
    /// returns to read mode. Local edit state lives at the screen
    /// level (only one row editable at a time) so navigating between
    /// pages doesn't bleed drafts.
    @ViewBuilder
    private func ocrPageCard(for page: OcrPagePreview) -> some View {
        let isEditing = (editingPageId == page.id)
        VStack(alignment: .leading, spacing: QuickInkSpacing.s2) {
            HStack(spacing: QuickInkSpacing.s2) {
                Text("Page \(page.pageIndex + 1)")
                    .font(QuickInkText.eyebrow)
                    .tracking(QuickInkLetterSpacing.eyebrow)
                    .foregroundStyle(QuickInkColors.muted)
                Spacer()
                if isEditing {
                    Button {
                        editingPageId = nil
                        ocrDraft = ""
                    } label: {
                        Image(systemName: "xmark")
                            .font(.system(size: 14, weight: .medium))
                            .foregroundStyle(QuickInkColors.muted)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Cancel edit")
                    Button {
                        let snapshot = ocrDraft
                        Task { await applyOcrEdit(pageId: page.id, text: snapshot) }
                    } label: {
                        Image(systemName: "checkmark")
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundStyle(QuickInkColors.accent)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Save text")
                } else {
                    Button {
                        ocrDraft = page.text
                        editingPageId = page.id
                    } label: {
                        Image(systemName: "pencil")
                            .font(.system(size: 14, weight: .medium))
                            .foregroundStyle(QuickInkColors.muted)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Edit text")
                }
            }

            if isEditing {
                TextEditor(text: $ocrDraft)
                    .font(QuickInkText.body)
                    .foregroundStyle(QuickInkColors.ink)
                    .frame(minHeight: 120)
                    .scrollContentBackground(.hidden)
                    .background(QuickInkColors.bg)
                    .overlay(
                        RoundedRectangle(cornerRadius: QuickInkRadius.sm, style: .continuous)
                            .stroke(QuickInkColors.borderSoft, lineWidth: 1)
                    )
                    .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.sm, style: .continuous))
            } else {
                Text(page.text)
                    .font(QuickInkText.body)
                    .foregroundStyle(QuickInkColors.ink)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .textSelection(.enabled)
            }
        }
        .padding(QuickInkSpacing.s4)
        .background(QuickInkColors.surface)
        .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous)
                .stroke(QuickInkColors.border, lineWidth: 1)
        )
    }

    private func toggleOcr() {
        if showOcr {
            showOcr = false
        } else {
            showOcr = true
            if ocrPages.isEmpty {
                Task { await loadOcr() }
            }
        }
    }

    // MARK: - Load

    /// Soft-delete the in-view capture and dismiss back to Home.
    /// The sync worker mirrors the tombstone to Drive on its next
    /// pass; the home rail's `deleted_at IS NULL` filter hides the
    /// row immediately.
    private func deleteCapture() async {
        do {
            try await CaptureRepository().softDelete(id: captureId)
            onBack()
        } catch {
            print("ScanDetailScreen.deleteCapture failed: \(error)")
        }
    }

    /// Persist a category change for this capture and refresh the
    /// in-screen state so the pill flips immediately. `nil` clears
    /// the tag. Best-effort — a transient SQL failure leaves the
    /// pill where it was; the user can re-tap to retry.
    private func applyRetag(_ category: String?) async {
        do {
            try await CaptureRepository().setCategory(
                captureId: captureId,
                category:  category
            )
            // Refresh the loaded `capture` so the pill flips —
            // simpler than mutating the optional struct in place,
            // and the SELECT is cheap.
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

    /// Persist an OCR text edit for a single page row. Refreshes
    /// `ocrPages` after the write so the page card flips back to
    /// read mode with the new content. Best-effort — a transient
    /// failure leaves the draft visible so the user can retry.
    private func applyOcrEdit(pageId: String, text: String) async {
        do {
            try await CaptureRepository().setOcrText(
                ocrResultId: pageId,
                text:        text
            )
            editingPageId = nil
            ocrDraft = ""
            await loadOcr()
        } catch {
            print("ScanDetailScreen.applyOcrEdit failed: \(error)")
        }
    }

    private func loadCapture() async {
        let dbQueue = QuickInkDatabase.shared.dbQueue
        do {
            let result = try await dbQueue.read { db -> CaptureSummary? in
                try CaptureSummary.fetchOne(db, sql: """
                    SELECT id, title, preview_uri, pdf_uri, category, page_count, created_at, source
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

    private func loadOcr() async {
        isLoadingOcr = true
        defer { isLoadingOcr = false }

        let dbQueue = QuickInkDatabase.shared.dbQueue
        do {
            let pages = try await dbQueue.read { db -> [OcrPagePreview] in
                try OcrPagePreview.fetchAll(db, sql: """
                    SELECT id, page_index, text
                    FROM ocr_results
                    WHERE capture_id = ? AND deleted_at IS NULL
                    ORDER BY page_index ASC
                    """, arguments: [captureId])
            }
            self.ocrPages = pages
        } catch {
            print("ScanDetailScreen.loadOcr failed: \(error)")
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

/// Lightweight projection of `ocr_results` rows used by the
/// detail viewer's OCR section. Carries `id` so per-page edits
/// can persist back via `CaptureRepository.setOcrText(...)`. The
/// full row's `blocks_json` and engine metadata aren't surfaced.
private struct OcrPagePreview: Codable, FetchableRecord, Identifiable, Sendable {
    let id: String
    let pageIndex: Int
    let text: String

    enum CodingKeys: String, CodingKey {
        case id
        case pageIndex = "page_index"
        case text
    }
}
