/*
 * FullscreenPdfViewer.swift
 *
 * Edge-to-edge flipbook viewer launched from `ScanDetailScreen`'s
 * inline preview. Mirror of Android's `FullscreenPdfDialog.kt` —
 * presented via `.fullScreenCover` so the content fills the entire
 * screen with a black backdrop that pushes focus onto the page.
 *
 * Re-uses the existing flipbook UX:
 *   - Multi-page captures get the full `PageTurnPdfView` 3D book-flip
 *     swipe + pinch-to-zoom + double-tap-to-zoom, just stretched to
 *     the viewport bounds.
 *   - Single-page captures render the page as a pinch-zoomable image
 *     directly (PageTurnPdfView's gesture machinery would no-op on a
 *     single page anyway).
 *
 * Bitmaps are re-rendered here instead of being passed in from
 * `ScanDetailScreen`: PDFKit's `thumbnail(of:for:)` is fast on the
 * 1–10-page scans the app produces, the cover is short-lived, and
 * keeping the entry surface (URL in) consistent with
 * `pageTurnViewer(for:)` makes the call site read symmetric.
 *
 * Differences from the inline view: black background, white-on-black
 * close affordance in the top-trailing corner, no rounded chrome
 * around the page (the page IS the screen).
 */

import SwiftUI
import PDFKit

struct FullscreenPdfViewer: View {

    let pdfURL: URL
    /// Initial page to show when the viewer opens. Wired from the
    /// inline `PageTurnPdfView`'s current page so the user lands on
    /// whatever page they were last looking at — left at 0 for now
    /// since the inline view doesn't surface its currentPage as a
    /// binding. Plumb through if/when that becomes useful.
    var initialPage: Int = 0
    let onDismiss: () -> Void

    @State private var pageImages: [UIImage] = []
    @State private var loadFailed = false
    /// Pinch-zoom scale for the single-page path. PageTurnPdfView
    /// owns its own scale state for the multi-page path.
    @State private var singlePageZoom: CGFloat = 1.0

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            content

            // Top-trailing close affordance. Lives outside `content`
            // so it stays reachable even while the user is mid-pinch
            // on a zoomed page (PageTurnPdfView eats touches inside
            // its own bounds, so the button needs to be a sibling
            // overlay, not a child of the viewer).
            VStack {
                HStack {
                    Spacer()
                    Button(action: onDismiss) {
                        Image(systemName: "xmark")
                            .font(.system(size: 18, weight: .semibold))
                            .foregroundStyle(.white)
                            .frame(width: 40, height: 40)
                            .background(Color.black.opacity(0.55))
                            .clipShape(Circle())
                    }
                    .accessibilityLabel("Close fullscreen")
                }
                Spacer()
            }
            .padding(QuickInkSpacing.s3)
        }
        .task(id: pdfURL.path) {
            // Off-main rasterisation — same rationale as
            // `ScanDetailScreen.pageTurnViewer`. PDFKit's thumbnail()
            // is synchronous so we hop a detached Task to keep the
            // close button responsive while the (typically brief)
            // render runs.
            let url = pdfURL
            let images: [UIImage] = await Task.detached(priority: .userInitiated) {
                guard let doc = PDFDocument(url: url) else { return [] }
                return doc.renderPageImages(scale: 2.0)
            }.value
            if images.isEmpty {
                loadFailed = true
            } else {
                pageImages = images
            }
        }
    }

    @ViewBuilder
    private var content: some View {
        if loadFailed {
            Text("Couldn't open this scan.")
                .font(QuickInkText.body)
                .foregroundStyle(.white.opacity(0.85))
                .padding(QuickInkSpacing.s4)
        } else if pageImages.isEmpty {
            ProgressView()
                .tint(.white)
        } else if pageImages.count > 1 {
            // Multi-page → PageTurnPdfView. Aspect-ratio'd to the
            // first page so portrait scans don't get letterboxed
            // unnecessarily; SwiftUI fits within the viewport so the
            // page never exceeds screen bounds in either axis.
            PageTurnPdfView(pageImages: pageImages)
                .aspectRatio(pageAspectRatio, contentMode: .fit)
                .padding(.horizontal, QuickInkSpacing.s2)
        } else if let only = pageImages.first {
            // Single-page → just the image, with the same pinch +
            // double-tap zoom the inline JPEG fallback uses. No need
            // to spin up the whole pager for one page.
            Image(uiImage: only)
                .resizable()
                .scaledToFit()
                .scaleEffect(singlePageZoom)
                .gesture(
                    MagnificationGesture()
                        .onChanged { value in
                            singlePageZoom = max(1.0, min(value.magnitude, 5.0))
                        }
                        .onEnded { _ in
                            withAnimation(.easeOut(duration: 0.2)) {
                                if singlePageZoom < 1.05 { singlePageZoom = 1.0 }
                            }
                        }
                )
                .onTapGesture(count: 2) {
                    withAnimation(.easeInOut(duration: 0.25)) {
                        singlePageZoom = singlePageZoom > 1.0 ? 1.0 : 2.5
                    }
                }
                .padding(.horizontal, QuickInkSpacing.s2)
        }
    }

    /// Aspect ratio of the first rasterised page — drives the
    /// `PageTurnPdfView` frame so swipes have predictable bounds.
    /// Falls back to A4 portrait when the page reports degenerate
    /// dimensions.
    private var pageAspectRatio: CGFloat {
        guard let first = pageImages.first, first.size.height > 0 else { return 0.707 }
        return first.size.width / first.size.height
    }
}
