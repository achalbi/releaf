/*
 * PageTurnPdfView.swift
 *
 * Multi-page PDF viewer with one-page-at-a-time layout + a 3D
 * page-turn animation on swipe. Mirror of Android's
 * `PageTurnPdfView`. Used by `ScanDetailScreen` whenever the
 * capture has more than one page; single-page captures keep the
 * scrollable `PDFKitView` so users get pinch-to-zoom for free.
 *
 * Rendering: PDFKit's `PDFPage.thumbnail(of:for:)` rasterises each
 * page to a `UIImage` at 2× density once on first appear. Typical
 * scans are 1–10 pages; the bitmap cache fits comfortably in memory.
 * For multi-hundred-page docs we'd want lazy rendering of just the
 * adjacent pair — out of scope for the MVP.
 *
 * Animation: each page is offset horizontally by `(idx - current) ×
 * pageWidth + dragOffset` and rotated around its leading or
 * trailing edge based on the sign of that offset. As the user
 * drags, the active page rotates "off" the screen while the next
 * page rotates "on" — simple book-flip cue without splitting the
 * page geometry. Spring-snap on release with a velocity-aware
 * threshold so quick flicks always advance / retreat.
 */

import SwiftUI
import PDFKit

struct PageTurnPdfView: View {

    let pageImages: [UIImage]
    /// Two-way bound active page index (0-based). Lets the parent
    /// drive the pager from a sibling UI like the thumbnails strip
    /// AND get notified when the user swipes. Callers that don't
    /// need to control the page from outside can pass a binding to a
    /// local `@State Int` — the view writes back through it on
    /// swipe so internal state stays consistent.
    @Binding var currentPage: Int
    /// When false, the swipe + pinch + double-tap gestures are
    /// skipped entirely — vertical drags pass through to a parent
    /// `ScrollView` instead of being captured for the page-turn
    /// animation. Used by the inline preview on `ScanDetailScreen`
    /// so the user can scroll the page while their finger is on the
    /// preview; the fullscreen viewer keeps the default (true).
    var interactionsEnabled: Bool = true

    @State private var dragOffset: CGFloat = 0
    /// Pinch-to-zoom scale for the ACTIVE page. Reset to 1 whenever
    /// the user flips to a new page so each page starts unzoomed.
    /// Range clamped to 1...4 (no zoom-out below the page bounds).
    @State private var zoomScale: CGFloat = 1.0
    /// Pan offset for the ACTIVE page when zoomed in. Pan only
    /// engages while `zoomScale > 1.01`; otherwise drags are
    /// interpreted as a page-turn swipe. Bounds-clamped so the
    /// zoomed page can't drift past the page frame's edge.
    @State private var panOffset: CGSize = .zero
    /// Pan value at the last drag-end. New drags accumulate from
    /// here, so a user who pans, lifts, then pans again gets the
    /// expected continuous behaviour instead of the page snapping
    /// back to baseline at each touch-down.
    @State private var panBaseline: CGSize = .zero

    /// True when the active page is zoomed in — used to gate the
    /// swipe gesture (pinch / pan win when zoomed) and to suppress
    /// the 3D page-turn rotation (a rotating zoomed image looks odd).
    private var isZoomed: Bool { zoomScale > 1.01 }

    var body: some View {
        GeometryReader { geo in
            let pageWidth  = geo.size.width
            let pageHeight = geo.size.height

            ZStack {
                ForEach(drawOrder, id: \.self) { idx in
                    pageView(
                        index:      idx,
                        pageWidth:  pageWidth,
                        pageHeight: pageHeight
                    )
                }
            }
            .frame(width: pageWidth, height: pageHeight)
            .contentShape(Rectangle())
            // SimultaneousGesture so pinch + drag can both fire on
            // the same touch sequence. The drag handler internally
            // routes to either pan-when-zoomed or swipe-to-turn
            // based on `isZoomed`, so the two intents never collide.
            // When `interactionsEnabled == false` we skip the gesture
            // entirely so vertical drags pass through to a parent
            // ScrollView (the inline preview uses this path).
            .pageTurnGesture(
                enabled: interactionsEnabled,
                gesture: combinedGesture(pageWidth: pageWidth, pageHeight: pageHeight)
            )
            .overlay(alignment: .bottom) {
                if pageImages.count > 1 {
                    pageIndicator
                        .padding(.bottom, QuickInkSpacing.s3)
                }
            }
            .onChange(of: currentPage) { _ in
                // Reset zoom + pan when the active page changes so
                // each page starts at fit-to-frame. Without this, a
                // user who zoomed in on page 3 would see page 4
                // pre-zoomed.
                withAnimation(.easeOut(duration: 0.2)) {
                    zoomScale = 1.0
                    panOffset = .zero
                    panBaseline = .zero
                }
            }
        }
    }

    // MARK: - Per-page render

    @ViewBuilder
    private func pageView(index: Int, pageWidth: CGFloat, pageHeight: CGFloat) -> some View {
        // `baseOffset` is where the page would sit if no drag was
        // happening — `idx == currentPage` → 0; previous pages are
        // off-screen left, next pages off-screen right. `dragOffset`
        // shifts the whole stack by the user's finger position.
        let baseOffset    = CGFloat(index - currentPage) * pageWidth
        let totalOffset   = baseOffset + dragOffset
        let normalized    = totalOffset / max(pageWidth, 1)
        let clamped       = max(-1.0, min(1.0, normalized))
        // Suppress the page-turn rotation while the active page is
        // zoomed — applying a 3D rotation to a scaled image reads
        // as a smear rather than a fold.
        let angle         = isZoomed && index == currentPage
                            ? 0.0
                            : Double(clamped) * 75.0
        let anchor: UnitPoint = clamped > 0 ? .leading : .trailing
        let isOffscreen   = abs(normalized) > 1.0
        let isActive      = (index == currentPage)
        let pageScale     = isActive ? zoomScale : 1.0
        let pagePan       = isActive ? panOffset : .zero

        Image(uiImage: pageImages[index])
            .resizable()
            .scaledToFit()
            .frame(width: pageWidth, height: pageHeight)
            // Apply zoom + pan FIRST so they happen relative to the
            // page's natural bounds, then clip to the page frame
            // (so a panned-zoomed page doesn't bleed onto neighbours),
            // then apply the swipe offset + 3D rotation.
            .scaleEffect(pageScale)
            .offset(pagePan)
            .frame(width: pageWidth, height: pageHeight)
            .clipped()
            .offset(x: totalOffset)
            .rotation3DEffect(
                .degrees(angle),
                axis: (x: 0, y: 1, z: 0),
                anchor: anchor,
                perspective: 0.7
            )
            // Hide pages that are fully off-screen so the swipe gesture
            // doesn't register taps on far-away images.
            .opacity(isOffscreen ? 0 : 1)
            .allowsHitTesting(!isOffscreen)
            .onTapGesture(count: 2) {
                guard isActive else { return }
                withAnimation(.easeInOut(duration: 0.25)) {
                    if zoomScale > 1.0 {
                        zoomScale = 1.0
                        panOffset = .zero
                        panBaseline = .zero
                    } else {
                        zoomScale = 2.0
                    }
                }
            }
    }

    // MARK: - Gestures

    /// Pinch + drag composed so both can run on the same touch.
    /// Pinch always feeds `zoomScale`; drag routes to pan when
    /// zoomed and to swipe-to-turn when at fit-to-frame. Pan offsets
    /// are bounds-clamped via `clampPan(...)` so the zoomed page
    /// can't drift past the page frame's edge in either axis.
    private func combinedGesture(pageWidth: CGFloat, pageHeight: CGFloat) -> some Gesture {
        SimultaneousGesture(
            MagnificationGesture()
                .onChanged { value in
                    let next = max(1.0, min(value.magnitude, 4.0))
                    zoomScale = next
                    // Re-clamp pan whenever the zoom changes —
                    // zooming OUT shrinks the allowed pan envelope,
                    // so a previously-valid pan might now extend
                    // past the page edge. Re-clamping keeps the
                    // page snapped inside its frame. We deliberately
                    // do NOT update `panBaseline` here: a
                    // simultaneous pinch+pan gesture would otherwise
                    // jitter as the drag handler re-applies its
                    // gesture-start `value.translation` to a
                    // shifting baseline. Baseline syncs at
                    // gesture-end only.
                    panOffset = clampPan(
                        panOffset,
                        scale:      next,
                        pageWidth:  pageWidth,
                        pageHeight: pageHeight
                    )
                }
                .onEnded { _ in
                    // Sync the baseline so a follow-on drag starts
                    // exactly where the pinch left off.
                    panBaseline = panOffset
                    // Snap fully back to 1 when the user pinched out
                    // most of the way — avoids a sticky 1.02 zoom
                    // that would keep the swipe gesture disabled.
                    withAnimation(.easeOut(duration: 0.2)) {
                        if zoomScale < 1.1 {
                            zoomScale = 1.0
                            panOffset = .zero
                            panBaseline = .zero
                        }
                    }
                },
            DragGesture(minimumDistance: 8)
                .onChanged { value in
                    if isZoomed {
                        // Accumulate from the baseline so successive
                        // drags don't snap back to (0,0) at each
                        // touch-down. Then clamp to keep the page
                        // inside its frame.
                        let proposed = CGSize(
                            width:  panBaseline.width  + value.translation.width,
                            height: panBaseline.height + value.translation.height
                        )
                        panOffset = clampPan(
                            proposed,
                            scale:      zoomScale,
                            pageWidth:  pageWidth,
                            pageHeight: pageHeight
                        )
                    } else {
                        dragOffset = value.translation.width
                    }
                }
                .onEnded { value in
                    if isZoomed {
                        // Lock in the pan as the new baseline so the
                        // next drag continues from here.
                        panBaseline = panOffset
                        return
                    }
                    let distanceThreshold: CGFloat = pageWidth * 0.2
                    let velocityThreshold: CGFloat = 300
                    let dx        = value.translation.width
                    let predicted = value.predictedEndTranslation.width

                    let goNext = (dx < -distanceThreshold || predicted < -velocityThreshold)
                                && currentPage < pageImages.count - 1
                    let goPrev = (dx >  distanceThreshold || predicted >  velocityThreshold)
                                && currentPage > 0

                    withAnimation(.spring(response: 0.5, dampingFraction: 0.8)) {
                        if goNext {
                            currentPage += 1
                        } else if goPrev {
                            currentPage -= 1
                        }
                        dragOffset = 0
                    }
                }
        )
    }

    /// Clamp `pan` so the scaled page can't drift past the page-frame
    /// edges. The maximum allowed offset on each axis is half the
    /// scaled-vs-natural overflow: at scale 1× there's no overflow,
    /// so the envelope collapses to (0, 0); at 4× the page is 4×
    /// wider/taller, so the envelope is `(pageWidth × 3) / 2` on
    /// each side.
    private func clampPan(
        _ pan: CGSize,
        scale: CGFloat,
        pageWidth: CGFloat,
        pageHeight: CGFloat
    ) -> CGSize {
        let overflowScale = max(0, scale - 1)
        let maxX = (pageWidth  * overflowScale) / 2
        let maxY = (pageHeight * overflowScale) / 2
        return CGSize(
            width:  max(-maxX, min(maxX, pan.width)),
            height: max(-maxY, min(maxY, pan.height))
        )
    }

    // MARK: - Z-order

    /// Draw far-from-current pages first so the active + adjacent
    /// pages render on top during a swipe — keeps the active page
    /// from being occluded by an outgoing edge during the rotation.
    private var drawOrder: [Int] {
        Array(0..<pageImages.count).sorted {
            abs($0 - currentPage) > abs($1 - currentPage)
        }
    }

    // MARK: - Indicator

    @ViewBuilder
    private var pageIndicator: some View {
        Text("\(currentPage + 1) / \(pageImages.count)")
            .font(QuickInkText.caption)
            .foregroundStyle(QuickInkColors.inkSoft)
            .padding(.horizontal, QuickInkSpacing.s3)
            .padding(.vertical, QuickInkSpacing.s1)
            .background(QuickInkColors.surface.opacity(0.85))
            .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.pill, style: .continuous))
    }
}

// MARK: - Conditional gesture modifier

private extension View {
    /// Apply `gesture` only when `enabled` is true; otherwise leave
    /// the view's gesture stack untouched so the surrounding hit-test
    /// reaches the parent (e.g. a SwiftUI `ScrollView`). Built as a
    /// helper because `.gesture(_:)` itself doesn't accept an
    /// optional Gesture and a ternary on the gesture argument won't
    /// type-check when the branches differ.
    @ViewBuilder
    func pageTurnGesture<G: Gesture>(enabled: Bool, gesture: G) -> some View {
        if enabled {
            self.gesture(gesture)
        } else {
            self
        }
    }
}

// MARK: - PDFDocument → [UIImage]

extension PDFDocument {
    /// Rasterise every page to a `UIImage` at 2× the page's media-box
    /// size — sharp enough on retina displays without blowing memory
    /// on typical scan-sized PDFs. Returns an empty array when the
    /// document has no pages or fails to decode any page.
    func renderPageImages(scale: CGFloat = 2.0) -> [UIImage] {
        (0..<self.pageCount).compactMap { idx in
            guard let page = self.page(at: idx) else { return nil }
            let bounds = page.bounds(for: .mediaBox)
            let size = CGSize(
                width:  bounds.width  * scale,
                height: bounds.height * scale
            )
            return page.thumbnail(of: size, for: .mediaBox)
        }
    }
}
