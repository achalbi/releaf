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
import CoreLocation
import ReleafCoreScan

struct ScanDetailScreen: View {

    let captureId: String
    let userId: String
    let onBack: () -> Void
    /// Bottom-nav callbacks. Optional so we keep the current
    /// "navigate to detail and only allow back" path working from
    /// places that don't host a tab bar (e.g. share-extension entry
    /// points). When all five are supplied, the floating bottom nav
    /// renders below the content; otherwise it stays hidden.
    let onHome: (() -> Void)?
    let onLibrary: (() -> Void)?
    let onScan: (() -> Void)?
    let onSearch: (() -> Void)?
    let onSettings: (() -> Void)?

    @StateObject private var categoriesVM: CategoryListViewModel

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
    /// Drives the fullscreen flipbook viewer (`FullscreenPdfViewer`).
    /// Set true by the overlay button on the inline preview; cleared
    /// by the cover's close affordance or a system back-swipe.
    @State private var showFullscreenViewer = false
    /// Selected page index for the thumbnail strip (0-based). Drives
    /// the highlighted thumbnail and which page is shown in the
    /// preview. Defaults to 0 (first page).
    @State private var selectedPageIndex: Int = 0
    /// On-disk size of the capture's PDF in bytes, loaded lazily on
    /// appear so the Details card can show "2.4 MB" etc. Nil until
    /// resolved or when the file isn't readable.
    @State private var pdfFileSize: Int64? = nil
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

    init(
        captureId: String,
        userId: String,
        onBack: @escaping () -> Void,
        onHome: (() -> Void)? = nil,
        onLibrary: (() -> Void)? = nil,
        onScan: (() -> Void)? = nil,
        onSearch: (() -> Void)? = nil,
        onSettings: (() -> Void)? = nil
    ) {
        self.captureId = captureId
        self.userId = userId
        self.onBack = onBack
        self.onHome = onHome
        self.onLibrary = onLibrary
        self.onScan = onScan
        self.onSearch = onSearch
        self.onSettings = onSettings
        _categoriesVM = StateObject(
            wrappedValue: CategoryListViewModel(userId: userId)
        )
    }

    var body: some View {
        VStack(spacing: 0) {
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

                        // Details + Actions cards — side by side, matching
                        // the Drive-style mockup. Both cards stretch to
                        // equal width; on very narrow screens (iPhone SE
                        // 1st gen) the row still fits because the rows
                        // inside each card wrap on long values.
                        HStack(alignment: .top, spacing: QuickInkSpacing.s3) {
                            detailsCard(for: capture)
                                .frame(maxWidth: .infinity, alignment: .topLeading)
                            actionsCard(for: capture)
                                .frame(maxWidth: .infinity, alignment: .topLeading)
                        }
                        .padding(.horizontal, QuickInkSpacing.s5)
                    } else {
                        loadingSkeleton
                            .padding(.horizontal, QuickInkSpacing.s5)
                    }
                }
                .padding(.top, QuickInkSpacing.s4)
                .padding(.bottom, hasBottomNav ? QuickInkBottomNavReservedHeight : QuickInkSpacing.s8)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(QuickInkColors.bg.ignoresSafeArea())
        .safeAreaInset(edge: .bottom, spacing: 0) {
            if hasBottomNav,
               let onHome, let onLibrary, let onScan, let onSearch, let onSettings {
                QuickInkBottomNavBar(
                    activeTab:  .none,
                    onHome:     onHome,
                    onLibrary:  onLibrary,
                    onScan:     onScan,
                    onSearch:   onSearch,
                    onSettings: onSettings
                )
            }
        }
        .task {
            // Start the categories observation first (synchronous,
            // returns immediately) so it's already emitting by the
            // time the retag sheet's content closure evaluates —
            // otherwise a fast double-tap (open detail → tap pill)
            // can flash an empty picker for a frame.
            categoriesVM.start()
            await loadCapture()
            // File size depends on the resolved capture (we need
            // pdf_uri before we can stat the file) so it runs after
            // loadCapture lands.
            await loadFileSize()
            // Backfill the reverse-geocoded place name on captures
            // whose coordinates landed without a locality at scan
            // time (rate-limited CLGeocoder, offline, remote area).
            // Runs once on every Details open; CLGeocoder's own
            // rate-limit naturally caps the retry frequency.
            await retryReverseGeocodeIfNeeded()
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
        } message: {
            Text("\(titleDraft.count) characters")
                .font(.caption)
                .foregroundStyle(QuickInkColors.inkSoft)
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
        // Share-as-Image sheet — opens once [prepareImageShare]
        // finishes rasterising the scan's pages to temp JPEGs. The
        // wrapper struct's `id` cycles every render so a second
        // share-as-image tap re-presents the sheet rather than no-
        // opping on identical state.
        .sheet(item: $imageShareItems) { wrapper in
            ActivityView(activityItems: wrapper.urls)
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

    /// True when all five bottom-nav callbacks are wired, so the
    /// floating QuickInkBottomNavBar should render. Lets the screen
    /// support both nav-aware (open from Library/Home) and minimal
    /// (open from a share extension) hosts without a separate flag.
    private var hasBottomNav: Bool {
        onHome != nil && onLibrary != nil && onScan != nil &&
        onSearch != nil && onSettings != nil
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
                    .contentShape(Rectangle())
                    .onTapGesture { showFullscreenViewer = true }
                    .overlay(alignment: .topTrailing) { fullscreenChip }
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
                    .onTapGesture { showFullscreenViewer = true }
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
                .font(.system(size: 20, weight: .semibold))
                .foregroundStyle(QuickInkColors.textOnAccent)
                .frame(width: 48, height: 48)
                .background(QuickInkColors.ink.opacity(0.55))
                .clipShape(Circle())
        }
        .buttonStyle(.plain)
        .padding(QuickInkSpacing.s3)
        .accessibilityLabel("View fullscreen")
        .accessibilityHint("Expands the scan to fill the screen")
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

            if let category = capture.category, !category.isEmpty {
                Text("•").foregroundStyle(QuickInkColors.muted).font(QuickInkText.meta)
                HStack(spacing: QuickInkSpacing.s1) {
                    Image(systemName: "folder")
                        .font(.system(size: 14, weight: .medium))
                    Text(category)
                        .font(QuickInkText.meta)
                }
                .foregroundStyle(QuickInkColors.inkSoft)
            }
        }
    }

    @ViewBuilder
    private func tagPill(for capture: CaptureSummary) -> some View {
        let hasTag = !(capture.category ?? "").isEmpty
        Button {
            showRetagSheet = true
        } label: {
            HStack(spacing: QuickInkSpacing.s1) {
                Image(systemName: "tag")
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
        .accessibilityLabel(hasTag ? "Category: \(capture.category ?? "")" : "Add category")
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

    // MARK: - Details card

    /// Structured details card matching the mockup: rows for File
    /// type / Size / Created / Location / Tags, each with a label on
    /// the left and value on the right. Header has a small
    /// document.text icon + "Details" label per the mockup.
    @ViewBuilder
    private func detailsCard(for capture: CaptureSummary) -> some View {
        VStack(alignment: .leading, spacing: QuickInkSpacing.s2) {
            HStack(spacing: QuickInkSpacing.s2) {
                Image(systemName: "doc.text")
                    .font(.system(size: 16, weight: .medium))
                    .foregroundStyle(QuickInkColors.inkSoft)
                Text("Details")
                    .font(QuickInkFont.ui(13, weight: .semibold))
                    .foregroundStyle(QuickInkColors.ink)
            }

            VStack(spacing: QuickInkSpacing.s2) {
                detailRow(label: "File type", value: fileTypeLabel(for: capture))
                detailRow(label: "Size", value: pdfFileSize.map(formatBytes) ?? "—")
                detailRow(
                    label: "Folder",
                    value: capture.category ?? "Unsorted",
                    valueColor: capture.category != nil ? QuickInkColors.accent : QuickInkColors.inkSoft
                )
                // Geographic Area / City rows — hidden when the
                // capture has no reverse-geocoded place name (older
                // rows, location toggle off, denied permission, or a
                // failed geocode). Coordinates without a place name
                // aren't surfaced here — they'd read as raw numbers,
                // not useful for the average user. Dedupe at render
                // time so existing rows where the geocoder fell back
                // to the city for both fields don't show identical
                // Area + City rows.
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

    /// One label/value row inside [detailsCard]. Label is muted,
    /// left-aligned; value is ink, right-aligned. `valueColor` lets
    /// callers override (e.g. accent color for the Location link).
    @ViewBuilder
    private func detailRow(
        label: String,
        value: String,
        valueColor: Color = QuickInkColors.ink
    ) -> some View {
        HStack(alignment: .firstTextBaseline) {
            Text(label)
                .font(QuickInkText.caption)
                .foregroundStyle(QuickInkColors.inkSoft)
            Spacer()
            Text(value)
                .font(QuickInkText.caption)
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
                .font(QuickInkText.caption)
                .foregroundStyle(QuickInkColors.inkSoft)
            Spacer()
            HStack(spacing: QuickInkSpacing.s1) {
                if let category = capture.category, !category.isEmpty {
                    Button {
                        showRetagSheet = true
                    } label: {
                        Text(category)
                            .font(QuickInkText.caption)
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

                Button { showRetagSheet = true } label: {
                    actionRowContent(icon: "folder", label: "Move to folder")
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

    // MARK: - File metadata helpers

    /// Resolve the file-type label for the Details row. Captures with
    /// a real PDF on disk show "PDF document"; image-only captures
    /// (preview JPEG, no PDF) fall back to "Image".
    private func fileTypeLabel(for capture: CaptureSummary) -> String {
        if pdfURL(from: capture) != nil { return "PDF document" }
        if loadedPreviewImage(for: capture) != nil { return "Image" }
        return "Document"
    }

    /// Format a byte count as "1.2 MB" / "340 KB" using the system
    /// formatter so the locale-aware separator is correct.
    private func formatBytes(_ bytes: Int64) -> String {
        let formatter = ByteCountFormatter()
        formatter.countStyle = .file
        return formatter.string(fromByteCount: bytes)
    }

    /// True for scans tagged with the Business Card category. Drives
    /// the conditional "Add to contact" action row. Case-insensitive
    /// so "business card" / "Business Card" / "BUSINESS CARD" all
    /// trip the gate.
    private func isBusinessCard(_ capture: CaptureSummary) -> Bool {
        (capture.category ?? "").lowercased() == "business card"
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

    /// Render the capture into one JPEG per page and present the
    /// system share sheet against those files. Guarded by
    /// [isPreparingImageShare] so a double-tap doesn't queue a second
    /// render in parallel.
    private func prepareImageShare() async {
        guard !isPreparingImageShare else { return }
        isPreparingImageShare = true
        defer { isPreparingImageShare = false }
        let urls = await renderImageURLs()
        guard !urls.isEmpty else { return }
        imageShareItems = IdentifiedURLs(urls: urls)
    }

    /// Rasterise the capture to one JPEG per page in the temp dir
    /// and return the resulting file URLs. Falls back to the preview
    /// JPEG for image-only (PDF-less) captures. Returns an empty
    /// array when neither path is available — the caller bails before
    /// presenting the share sheet.
    private func renderImageURLs() async -> [URL] {
        let id = captureId
        if let pdfURL = pdfURL(from: capture) {
            return await Task.detached(priority: .userInitiated) {
                guard let doc = PDFDocument(url: pdfURL) else { return [] }
                let images = doc.renderPageImages(scale: 2.0)
                return ScanDetailScreen.writeJpegsToTemp(images, base: id)
            }.value
        }
        if let cap = capture, let img = loadedPreviewImage(for: cap) {
            return await Task.detached(priority: .userInitiated) { [img] in
                ScanDetailScreen.writeJpegsToTemp([img], base: id)
            }.value
        }
        return []
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

    private func loadCapture() async {
        let dbQueue = QuickInkDatabase.shared.dbQueue
        do {
            let result = try await dbQueue.read { db -> CaptureSummary? in
                try CaptureSummary.fetchOne(db, sql: """
                    SELECT id, title, preview_uri, pdf_uri, category, page_count,
                           created_at, source, latitude, longitude, locality, sub_locality
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

    /// When the capture has lat/lon but no place names — typical
    /// outcome when `CLGeocoder` was rate-limited or offline at scan
    /// time — retry the reverse geocode here and persist the
    /// result. Runs in the background; the UI reloads via
    /// [loadCapture] when the new values land. No retry tracking
    /// state: CLGeocoder's own rate-limit caps the retry frequency,
    /// and an opened-twice-in-a-row screen is fine to ask twice.
    private func retryReverseGeocodeIfNeeded() async {
        guard let cap = capture,
              let lat = cap.latitude,
              let lon = cap.longitude else { return }
        let hasLocality    = !(cap.locality?.trimmingCharacters(in: .whitespaces).isEmpty ?? true)
        let hasSubLocality = !(cap.subLocality?.trimmingCharacters(in: .whitespaces).isEmpty ?? true)
        if hasLocality && hasSubLocality { return }

        let clLocation = CLLocation(latitude: lat, longitude: lon)
        guard let placemark = try? await CLGeocoder().reverseGeocodeLocation(clLocation).first else {
            return
        }
        // Same dedupe as the write path in LocationService — drop
        // the sub-locality when it duplicates the locality so the
        // backfilled row doesn't recreate the "Area = City" UX
        // problem.
        let names = LocationService.dedupePlaceNames(
            locality:    placemark.locality,
            subLocality: placemark.subLocality
        )
        // Bail when the retry yields nothing useful — saves a
        // pointless write + a no-op sync push.
        guard names.locality != nil || names.subLocality != nil else { return }

        do {
            try await CaptureRepository().updateLocality(
                captureId:   captureId,
                locality:    names.locality    ?? cap.locality,
                subLocality: names.subLocality ?? cap.subLocality
            )
            await loadCapture()
        } catch {
            print("ScanDetailScreen.retryReverseGeocode failed: \(error)")
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

