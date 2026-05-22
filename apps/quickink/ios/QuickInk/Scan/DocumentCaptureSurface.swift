/*
 * DocumentCaptureSurface.swift
 *
 * The Document branch of the capture screen — preserves the
 * existing VisionKit `VNDocumentCameraViewController` flow
 * verbatim. Owned by `QuickCaptureScreen`, which mounts this
 * view when `CaptureMode.document` is active and tears it
 * down on a flip to `.businessCard` (pill), `.photo`
 * (shutter-row camera icon tap), or `.video` (shutter-row
 * camera icon long-press).
 *
 * Behavior is the same as the previous all-in-one
 * QuickCaptureScreen body: page-mode pill (Single /
 * Multi-page) + tilted lined-paper page mock + shutter
 * that presents the system scanner + Import button that opens
 * PhotosPicker. The shutter row's left slot now hosts a
 * camera icon: tap fires `onSelectPhoto`, long-press fires
 * `onSelectVideo`. The scanner result flows through
 * `ScanFlowController.onScanComplete` exactly as before — no
 * detector swap, no overlay, no in-app camera.
 *
 * Renamed from the original nested `enum CaptureMode`
 * (Single / Multi-page) to [ScanPageMode] so the new
 * top-level `CaptureMode` can take the canonical name. The
 * pill labels and `pageLimit` plumbing are unchanged.
 *
 * Mirror of Android `DocumentCaptureSurface.kt`.
 */

import SwiftUI
import PhotosUI
import ReleafCoreScan

enum ScanPageMode: String, CaseIterable {
    case single, multiPage

    var label: String {
        switch self {
        case .single:    return "Single"
        case .multiPage: return "Multi-page"
        }
    }
}

struct DocumentCaptureSurface: View {
    let controller: ScanFlowController
    let onDismiss: () -> Void
    /// Callback fired when the user taps the Photo icon in the
    /// shutter row's left slot. Threaded up through the
    /// `QuickCaptureScreen` parent so the coordinator (which
    /// owns mode state) can flip to `.photo` and SwiftUI swaps
    /// in `PhotoCaptureSurface`. Defaults to a no-op so callers
    /// that don't host a coordinator (previews) keep compiling.
    var onSelectPhoto: () -> Void = {}
    /// Long-press counterpart to `onSelectPhoto`; starts the
    /// dedicated Video surface from the same camera icon.
    var onSelectVideo: () -> Void = {}

    @State private var pageMode: ScanPageMode = .single
    @State private var sweepOffset: CGFloat = -50
    @State private var showSystemScanner = false
    @State private var pickedItems: [PhotosPickerItem] = []

    var body: some View {
        VStack(spacing: 0) {
            Spacer()
            pagePreview
            Spacer()
            shutterRow
            pageModeSelector
                .padding(.bottom, QuickInkSpacing.s7)
        }
        .onChange(of: pickedItems) { newItems in
            guard !newItems.isEmpty else { return }
            Task { await loadPickedItems(newItems) }
        }
        .fullScreenCover(isPresented: $showSystemScanner) {
            DocumentScannerView(
                onComplete: { pdfURL, previewURL, pageURLs in
                    showSystemScanner = false
                    CaptureAnalytics.manualFired(mode: .document)
                    controller.onScanComplete(
                        pdfURL:     pdfURL,
                        previewURL: previewURL,
                        pageURLs:   pageURLs,
                    )
                    onDismiss()
                },
                onCancel: { showSystemScanner = false },
                pageLimit: pageMode == .single ? 1 : nil,
                compressedPdfEnabled: SettingsState.compressedPdfSavesDefault,
            )
            .ignoresSafeArea()
        }
    }

    // MARK: - Page preview

    @ViewBuilder
    private var pagePreview: some View {
        ZStack {
            ZStack(alignment: .topLeading) {
                QuickInkLinedPaper(
                    tone: QuickInkColors.surface,
                    lineSpacing: 18,
                    lineOpacity: 0.10,
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
                        startPoint: .leading, endPoint: .trailing,
                    )
                )
                .frame(width: 240, height: 2)
                .offset(y: sweepOffset)
                .shadow(color: QuickInkColors.accent.opacity(0.6), radius: 8, x: 0, y: 0)

            DocumentDetectionCorner(rotation: 0)        .offset(x: -120, y: -160)
            DocumentDetectionCorner(rotation: 90)       .offset(x:  120, y: -160)
            DocumentDetectionCorner(rotation: 270)      .offset(x: -120, y:  160)
            DocumentDetectionCorner(rotation: 180)      .offset(x:  120, y:  160)
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
            // Left slot — Photo icon (was a spacer pre-Photo
            // capture). Same 48pt disc styling as the right-
            // side import button so the row reads as a
            // symmetric two-button frame around the shutter.
            photoModeButton.frame(width: 64, height: 64)
            Spacer()
            documentShutterButton
            Spacer()
            importButton.frame(width: 64, height: 64)
        }
        .padding(.horizontal, QuickInkSpacing.s5)
        .padding(.bottom, QuickInkSpacing.s4)
    }

    @ViewBuilder
    private var photoModeButton: some View {
        Image(systemName: "camera")
            .font(.system(size: 22, weight: .medium))
            .foregroundStyle(Color.white.opacity(0.85))
            .frame(width: 48, height: 48)
            .background(Color.white.opacity(0.10))
            .clipShape(Circle())
            .contentShape(Circle())
            .gesture(
                LongPressGesture(minimumDuration: 0.45)
                    .exclusively(before: TapGesture())
                    .onEnded { value in
                        switch value {
                        case .first(true):
                            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                            onSelectVideo()
                        case .second:
                            UIImpactFeedbackGenerator(style: .light).impactOccurred()
                            onSelectPhoto()
                        default:
                            break
                        }
                    }
            )
        .accessibilityLabel("Take a photo")
        .accessibilityHint("Long press to record a video")
    }

    @ViewBuilder
    private var documentShutterButton: some View {
        Button(action: { showSystemScanner = true }) {
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
        PhotosPicker(
            selection:    $pickedItems,
            matching:     .images,
            photoLibrary: .shared(),
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

    // MARK: - Page-mode selector

    @ViewBuilder
    private var pageModeSelector: some View {
        HStack(spacing: 4) {
            ForEach(ScanPageMode.allCases, id: \.self) { m in
                let active = (m == pageMode)
                Button(action: { pageMode = m }) {
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

    // MARK: - Photo import

    private func loadPickedItems(_ items: [PhotosPickerItem]) async {
        var images: [UIImage] = []
        images.reserveCapacity(items.count)
        for item in items {
            guard let data  = try? await item.loadTransferable(type: Data.self),
                  let image = UIImage(data: data) else {
                continue
            }
            images.append(image)
        }
        guard let result = ImportArtifacts.build(from: images) else {
            await MainActor.run { pickedItems = [] }
            return
        }
        await MainActor.run {
            controller.onScanComplete(
                pdfURL:     result.pdfURL,
                previewURL: result.previewURL,
                pageURLs:   result.pageURLs,
                source:     "import",
            )
            pickedItems = []
            onDismiss()
        }
    }
}

private struct DocumentDetectionCorner: View {
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
