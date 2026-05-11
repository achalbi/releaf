/*
 * PDFKitView.swift
 *
 * SwiftUI wrapper around UIKit's `PDFView`. Used by `ScanDetailScreen`
 * to render the multi-page PDF that ML-Kit / VisionKit produced for a
 * capture. PDFView gives us pinch-to-zoom, scroll, and two-finger
 * panning natively — no extra gesture wiring needed.
 *
 * Display mode is single-page-continuous + vertical so the document
 * scrolls top-to-bottom in a single column, matching the way scans
 * read on phone-sized screens.
 */

import SwiftUI
import PDFKit

struct PDFKitView: UIViewRepresentable {

    let url: URL
    let backgroundColor: Color
    /// When false, the underlying `PDFView` is rendered as a static
    /// thumbnail — vertical drags pass through to a parent
    /// `ScrollView` instead of being captured by `PDFView`'s internal
    /// scroll/zoom gestures. The inline preview on `ScanDetailScreen`
    /// passes `false` so the page scrolls normally; the fullscreen
    /// viewer keeps the default (true) for full pinch/scroll.
    var interactionsEnabled: Bool = true

    func makeUIView(context: Context) -> PDFView {
        let view = PDFView()
        view.document            = PDFDocument(url: url)
        view.displayMode         = .singlePageContinuous
        view.displayDirection    = .vertical
        view.autoScales          = true
        view.minScaleFactor      = view.scaleFactorForSizeToFit
        view.maxScaleFactor      = 4.0
        view.pageBreakMargins    = .init(top: 8, left: 0, bottom: 8, right: 0)
        view.backgroundColor     = .clear
        view.isUserInteractionEnabled = interactionsEnabled
        return view
    }

    func updateUIView(_ uiView: PDFView, context: Context) {
        // Re-load only when the URL actually changed so the user's
        // current zoom + scroll position survive incidental
        // recomposes (e.g. parent view's @State updates).
        if uiView.document?.documentURL != url {
            uiView.document       = PDFDocument(url: url)
            uiView.minScaleFactor = uiView.scaleFactorForSizeToFit
        }
        uiView.isUserInteractionEnabled = interactionsEnabled
    }
}
