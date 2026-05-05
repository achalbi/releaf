/*
 * QuickCaptureScreen.swift
 *
 * Pre-capture surface — the dark, editorial scan UI from the
 * mockup brief. Per the brief:
 *
 *   - Dark camera UI
 *   - Animated detection corners on a tilted page preview
 *   - Mode selector (Single / Multi-page / Auto)
 *   - 78px shutter with Zap icon and page count badge
 *
 * Architecturally this sits between Home (the Zap FAB tap) and
 * the system document scanner (`VNDocumentCameraViewController`
 * via `DocumentScannerView` in ReleafCoreScan). It's a mode-picker
 * + visual hand-off, not a custom AVFoundation camera — building
 * a full custom camera here would replace VisionKit's already-
 * mature edge detection and not yield a meaningfully different
 * scan, so the tradeoff favors VisionKit for capture and a polish
 * surface here for brand identity.
 *
 * On Zap shutter tap, this screen dismisses and the system scanner
 * presents — its result flows back through the existing
 * `ScanFlowController.onScanComplete` pipeline.
 *
 * Mirror of Android `QuickCaptureScreen.kt`.
 */

import SwiftUI
import PhotosUI
import ReleafCoreScan

struct QuickCaptureScreen: View {
    let controller: ScanFlowController
    let onDismiss: () -> Void

    @State private var mode: CaptureMode = .single
    @State private var pageCount: Int = 0
    @State private var sweepOffset: CGFloat = -50
    @State private var showSystemScanner = false

    /// Selected `PhotosPickerItem`s from the gallery picker — order
    /// matches the user's selection order in the picker, so a
    /// multi-page PDF preserves the order they tapped photos in.
    /// Loading happens in `.onChange` — each item's `Data` is
    /// decoded to `UIImage`, the array is handed to
    /// `ImportArtifacts.build` which writes the JPEGs + a single
    /// multi-page PDF and returns the URL triple. Reset to `[]`
    /// after the load so picking the same set twice still triggers
    /// the handler.
    @State private var pickedItems: [PhotosPickerItem] = []

    enum CaptureMode: String, CaseIterable {
        case single, multiPage, auto

        var label: String {
            switch self {
            case .single:    return "Single"
            case .multiPage: return "Multi-page"
            case .auto:      return "Auto"
            }
        }
    }

    var body: some View {
        ZStack {
            // Dark canvas — stone-950 overlay covering the full frame.
            Color(hex: 0x0F0E0D).ignoresSafeArea()

            VStack(spacing: 0) {
                topBar
                Spacer()
                pagePreview
                Spacer()
                shutterRow
                modeSelector
                    .padding(.bottom, QuickInkSpacing.s7)
            }
        }
        .preferredColorScheme(.dark)
        // Mode change → reset the page counter so the Multi-page
        // badge doesn't display stale numbers from a previous
        // mode's session. Mirrors Android's LaunchedEffect(mode).
        // Single-parameter form — iOS 16 compatible. The two-arg
        // `onChange(of:_:_:)` overload is iOS 17+ and would break
        // the package's iOS 16 deployment target.
        .onChange(of: mode) { _ in pageCount = 0 }
        // Photo-pick handler — replaces the system scanner's old
        // gallery tab (which ML Kit owned on Android, VisionKit
        // never had on iOS). Loads each selected item as Data in
        // selection order, decodes to UIImage, hands the list to
        // `ImportArtifacts.build` for JPEGs + a single multi-page
        // PDF, then routes through the same `controller.onScanComplete`
        // pipeline as a real scan, with `source: "import"` so the
        // resulting capture row gets the "Import" pill in Library
        // views.
        //
        // Decoding is sequential rather than parallel: a 30-photo
        // import then loads 30 transfers in order, but loadTransferable
        // is I/O-bound on Photos.framework, not CPU-bound, so the
        // serial cost is dominated by Photos' own fetch latency. A
        // TaskGroup would parallelize the I/O but would have to
        // re-sort by index afterwards to preserve picker order; the
        // serial form is simpler and the difference at typical
        // selection sizes (≤10 photos) is imperceptible.
        .onChange(of: pickedItems) { newItems in
            guard !newItems.isEmpty else { return }
            Task {
                var images: [UIImage] = []
                images.reserveCapacity(newItems.count)
                for item in newItems {
                    guard let data  = try? await item.loadTransferable(type: Data.self),
                          let image = UIImage(data: data) else {
                        // Skip un-decodable items rather than abort
                        // the whole import — mirrors how the scanner
                        // drops a single failed page rather than
                        // discarding the whole session.
                        continue
                    }
                    images.append(image)
                }
                guard let result = ImportArtifacts.build(from: images) else {
                    await MainActor.run { pickedItems = [] }
                    return
                }
                await MainActor.run {
                    pageCount = result.pageURLs.count
                    controller.onScanComplete(
                        pdfURL:     result.pdfURL,
                        previewURL: result.previewURL,
                        pageURLs:   result.pageURLs,
                        source:     "import"
                    )
                    pickedItems = []
                    onDismiss()
                }
            }
        }
        .fullScreenCover(isPresented: $showSystemScanner) {
            DocumentScannerView(
                onComplete: { pdfURL, previewURL, pageURLs in
                    showSystemScanner = false
                    // Surface the actual page count returned by the
                    // scanner. The user sees this on the badge
                    // after the scanner closes — a real number, not
                    // a per-shutter-tap fake.
                    pageCount = pageURLs.count
                    controller.onScanComplete(
                        pdfURL:     pdfURL,
                        previewURL: previewURL,
                        pageURLs:   pageURLs
                    )
                    onDismiss()
                },
                onCancel: {
                    showSystemScanner = false
                },
                // Single mode → cap at one page so any extra
                // captures inside VisionKit are dropped before the
                // PDF / JPEGs are written. Multi-page / Auto leave
                // it nil so the user can keep adding pages inside
                // the sheet. Read at sheet-presentation time, so
                // the mode the user had selected when they tapped
                // shutter is the one that wins.
                pageLimit: mode == .single ? 1 : nil
            )
            .ignoresSafeArea()
        }
    }

    // MARK: - Top bar

    @ViewBuilder
    private var topBar: some View {
        HStack {
            Button(action: onDismiss) {
                Image(systemName: "xmark")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(Color.white.opacity(0.85))
                    .frame(width: 36, height: 36)
                    .background(Color.white.opacity(0.10))
                    .clipShape(Circle())
            }
            .accessibilityLabel("Close scan")

            Spacer()

            Text("Capture")
                .font(QuickInkText.label)
                .foregroundStyle(Color.white.opacity(0.85))

            Spacer()

            // Flash toggle placeholder.
            Button(action: { /* flash mode follow-up */ }) {
                Image(systemName: "bolt.slash")
                    .font(.system(size: 16, weight: .medium))
                    .foregroundStyle(Color.white.opacity(0.85))
                    .frame(width: 36, height: 36)
                    .background(Color.white.opacity(0.10))
                    .clipShape(Circle())
            }
            .accessibilityLabel("Flash")
        }
        .padding(.horizontal, QuickInkSpacing.s5)
        // Generous top padding so the close button clears the notch
        // with breathing room. Doubles as a vertical-centering nudge:
        // the bottom region (shutter + mode-selector + s7 bottom pad)
        // is heavier than the top, so without this the page mock
        // appeared visibly above center.
        .padding(.top, QuickInkSpacing.s7)
    }

    // MARK: - Page preview

    @ViewBuilder
    private var pagePreview: some View {
        ZStack {
            // Tilted lined-paper page mock — Caveat handwriting for
            // texture; the real camera frame replaces this when we
            // build a custom AVFoundation surface (out of scope for
            // QuickInk MVP).
            ZStack(alignment: .topLeading) {
                QuickInkLinedPaper(
                    tone: QuickInkColors.surface,
                    lineSpacing: 18,
                    lineOpacity: 0.10
                )
                HStack(spacing: 0) {
                    Rectangle()
                        .fill(QuickInkColors.accent.opacity(0.6))
                        .frame(width: 1.5)
                        .padding(.leading, 24)
                    Spacer()
                }
                .padding(.vertical, 14)

                Text("Brainstorm — Q3\nGoals, opportunities,\nand notes…")
                    .font(QuickInkFont.handwritten(20))
                    .foregroundStyle(QuickInkColors.ink.opacity(0.78))
                    .padding(.leading, 36)
                    .padding(.top, QuickInkSpacing.s5)
            }
            .frame(width: 240, height: 320)
            .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous))
            .rotation3DEffect(.degrees(8), axis: (x: 1, y: 0, z: 0))
            .rotation3DEffect(.degrees(-4), axis: (x: 0, y: 1, z: 0))
            .shadow(color: .black.opacity(0.5), radius: 24, x: 0, y: 12)

            // Animated coral scan line.
            Rectangle()
                .fill(
                    LinearGradient(
                        colors: [
                            QuickInkColors.accent.opacity(0),
                            QuickInkColors.accent.opacity(0.7),
                            QuickInkColors.accent,
                            QuickInkColors.accent.opacity(0.7),
                            QuickInkColors.accent.opacity(0),
                        ],
                        startPoint: .leading, endPoint: .trailing
                    )
                )
                .frame(width: 240, height: 2)
                .offset(y: sweepOffset)
                .shadow(color: QuickInkColors.accent.opacity(0.6), radius: 8, x: 0, y: 0)

            // Detection corners.
            DetectionCorner(rotation: 0)        .offset(x: -120, y: -160)
            DetectionCorner(rotation: 90)       .offset(x:  120, y: -160)
            DetectionCorner(rotation: 270)      .offset(x: -120, y:  160)
            DetectionCorner(rotation: 180)      .offset(x:  120, y:  160)
        }
        .onAppear {
            withAnimation(.easeInOut(duration: 1.4).repeatForever(autoreverses: true)) {
                sweepOffset = 130
            }
        }
    }

    // MARK: - Shutter row

    @ViewBuilder
    private var shutterRow: some View {
        HStack(alignment: .center) {
            // Page count badge — visible in multi-mode, showing
            // the page count returned by the most recent scanner
            // session (zero before the first capture).
            if mode == .multiPage {
                pageBadge
                    .frame(width: 64)
            } else {
                Spacer().frame(width: 64)
            }

            Spacer()

            shutterButton

            Spacer()

            // Right slot — Import button. Replaces VisionKit's
            // (nonexistent) gallery affordance with a system
            // PhotosPicker so the capture can be tagged
            // `source: "import"` and the Library cards can render
            // an "Import" pill. Mirror of Android.
            importButton
                .frame(width: 64, height: 64)
        }
        .padding(.horizontal, QuickInkSpacing.s5)
        .padding(.bottom, QuickInkSpacing.s4)
    }

    @ViewBuilder
    private var shutterButton: some View {
        Button(action: triggerScan) {
            ZStack {
                Circle()
                    .stroke(Color.white.opacity(0.6), lineWidth: 3)
                    .frame(width: 78, height: 78)
                Circle()
                    .fill(QuickInkColors.accent)
                    .frame(width: 64, height: 64)
                Image(systemName: "bolt.fill")
                    .font(.system(size: 26, weight: .semibold))
                    .foregroundStyle(.white)
            }
            .shadow(color: QuickInkColors.accent.opacity(0.5), radius: 16, x: 0, y: 6)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Capture page")
    }

    @ViewBuilder
    private var importButton: some View {
        // PhotosPicker drives a system gallery sheet directly off
        // the binding — no extra `.photosPicker(isPresented:)` is
        // needed when the picker is its own button. Visually quieter
        // than the shutter (white-on-translucent disc, ~48pt) so it
        // reads as a secondary affordance. Same surface treatment
        // as the close button in the top bar.
        //
        // Omitting `maxSelectionCount` keeps the picker uncapped —
        // we let the system picker enforce its own ceiling rather
        // than impose product policy. The init defaults the param
        // to nil, which Photos.framework treats as unlimited.
        PhotosPicker(
            selection:    $pickedItems,
            matching:     .images,
            photoLibrary: .shared()
        ) {
            Image(systemName: "photo.on.rectangle")
                .font(.system(size: 22, weight: .medium))
                .foregroundStyle(Color.white.opacity(0.85))
                .frame(width: 48, height: 48)
                .background(Color.white.opacity(0.10))
                .clipShape(Circle())
        }
        .accessibilityLabel("Import photos")
    }

    @ViewBuilder
    private var pageBadge: some View {
        VStack(spacing: 2) {
            Text("\(pageCount)")
                .font(QuickInkText.heading)
                .foregroundStyle(.white)
            Text(pageCount == 1 ? "page" : "pages")
                .font(QuickInkText.caption)
                .foregroundStyle(Color.white.opacity(0.7))
        }
    }

    // MARK: - Mode selector

    @ViewBuilder
    private var modeSelector: some View {
        HStack(spacing: 4) {
            ForEach(CaptureMode.allCases, id: \.self) { m in
                let active = (m == mode)
                Button(action: { mode = m }) {
                    Text(m.label)
                        .font(QuickInkText.label)
                        .foregroundStyle(active ? .white : Color.white.opacity(0.55))
                        .padding(.horizontal, QuickInkSpacing.s4)
                        .padding(.vertical, QuickInkSpacing.s2)
                        .background(
                            RoundedRectangle(cornerRadius: QuickInkRadius.pill, style: .continuous)
                                .fill(active ? QuickInkColors.accent : .clear)
                        )
                }
                .buttonStyle(.plain)
            }
        }
        .padding(4)
        .background(Color.white.opacity(0.10))
        .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.pill, style: .continuous))
    }

    // MARK: - Actions

    private func triggerScan() {
        // Always launch the system scanner. The scanner runs its
        // own UI from there: edge detection, capture, optional
        // Add-page (suppressed in Single via `pageLimit`), Done.
        // The earlier "increment pageCount, only launch on first
        // tap" code stranded the user after canceling VisionKit
        // — pageCount=2 → the gate failed → shutter became a
        // no-op. Mirrors Android.
        showSystemScanner = true
    }
}

// MARK: - Detection corner

struct DetectionCorner: View {
    let rotation: Double
    var body: some View {
        Path { path in
            path.move(to: CGPoint(x: 0, y: 18))
            path.addLine(to: CGPoint(x: 0, y: 0))
            path.addLine(to: CGPoint(x: 18, y: 0))
        }
        .stroke(QuickInkColors.accent, style: StrokeStyle(lineWidth: 3, lineCap: .round, lineJoin: .round))
        .frame(width: 18, height: 18)
        .rotationEffect(.degrees(rotation))
    }
}
