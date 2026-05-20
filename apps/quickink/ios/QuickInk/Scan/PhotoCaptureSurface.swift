/*
 * PhotoCaptureSurface.swift
 *
 * Single-shot still-camera surface. Sibling to
 * `DocumentCaptureSurface` (VisionKit document scanner),
 * `BusinessCardCaptureSurface` (AVCaptureSession + quad
 * detector), and `VideoCaptureSurface` (AVCaptureMovieFileOutput,
 * tap-to-toggle recording). Reached from the Sundial radial
 * menu's "Photo" ray on the bottom-nav ⚡ FAB, which mounts
 * `QuickCaptureScreen` with `initialMode: .photo`. The Sundial
 * path uses a no-op persist on the coordinator so this transient
 * surface doesn't overwrite the user's pill-selected last mode
 * (`quickink.capture.last_mode`).
 *
 * Shutter gesture (photo-only):
 *
 *   - Tap → still capture (`AVCapturePhotoOutput.capturePhoto`).
 *
 * Hold-to-record used to live on this surface too, but moved to a
 * dedicated `VideoCaptureSurface` once the Sundial menu gave
 * users a separate "Video" entry point. One verb per surface,
 * one unambiguous tap each.
 *
 * A small flip-camera button sits at the trailing edge of the
 * shutter row, next to the shutter itself (matches the iOS
 * Camera / Instagram convention). Only shown in the idle
 * `.preview` state — hidden while a still capture is in flight
 * since tearing down the input mid-take would race the photo
 * delegate callback.
 *
 * A collapse-by-default chip strip sits to the left of the
 * shutter and lets the user pick one of four color filters
 * (None / B&W / Sepia / Cool). Collapsed → only the active chip
 * is visible. Tap → the column expands upward into the preview
 * area with all chips; each chip carries a live mini-preview of
 * the camera with that filter pre-applied (multiple
 * `AVCaptureVideoPreviewLayer`s share the single
 * `captureSession` — GPU-rendered, cost is negligible). Tap a
 * chip → it's selected and the strip collapses again.
 *
 * The selection drives the main live preview (via cheap SwiftUI
 * `.saturation` / `.contrast` / `.colorMultiply` modifiers — no
 * per-frame CIFilter pass) AND the captured still (via
 * `CIFilter` applied to the `UIImage`). See `PhotoFilter` for
 * the per-case modifier / CIFilter mapping.
 *
 * After capture, the surface lands on the standard Retake / Use
 * Photo screen. Use Photo writes the JPEG via
 * `ImportArtifacts.build(from: [image])` and fires
 * `controller.onScanComplete(source: "photo", paperSize: .custom)`.
 * Spec §6 calls out `paperSize=.custom` for photo mode — an
 * arbitrary phone-camera frame's aspect ratio is meaningless
 * against the A4 / Letter / card ratio bands.
 *
 * Mirror of Android `PhotoCaptureSurface.kt`.
 */

import SwiftUI
import AVFoundation
import UIKit
import ReleafCoreData
import ReleafCoreScan

/// One of four color filters the user can apply from the vertical
/// chip strip on the bottom-left of the live preview. Shared by
/// `PhotoCaptureSurface` (still capture) and
/// `VideoCaptureSurface` (post-process re-encode of the recorded
/// `.mov`). Selection lives in each surface's session object and
/// is mirrored to:
///
///   - **Live preview** — via SwiftUI's `.saturation` /
///     `.contrast` / `.colorMultiply` modifiers on the session
///     view. These are cheap GPU operations applied at the render
///     layer, so there's no per-frame CPU cost (we don't run
///     `CIFilter` on every preview frame).
///   - **Captured artifact** — via `CIFilter` applied to the
///     saved `UIImage` (still) or per-frame via
///     `AVMutableVideoComposition.applyingCIFiltersWithHandler`
///     (video). Precise color science here; the live-preview
///     modifiers are approximations of the same outcome.
public enum PhotoFilter: String, CaseIterable {
    case none = "None"
    case bw = "B&W"
    case sepia = "Sepia"
    case cool = "Cool"

    public var displayName: String { rawValue }

    // MARK: Live-preview modifier values

    /// `.saturation(_:)` multiplier. 1.0 leaves the image
    /// untouched, 0 makes it grayscale. Tuned visually to match
    /// the eventual `CIFilter` output reasonably closely so the
    /// captured still doesn't look surprising next to the preview.
    public var liveSaturation: Double {
        switch self {
        case .none, .cool: return 1.0
        case .bw:          return 0.0
        case .sepia:       return 0.3
        }
    }

    /// `.contrast(_:)` multiplier. Currently every filter sticks
    /// at identity contrast (`.colorMultiply` carries the look).
    /// Kept as a property so future filters can dial it in
    /// without restructuring callers.
    public var liveContrast: Double { 1.0 }

    /// `.colorMultiply(_:)` tint. `.white` is the identity (no
    /// tint). Cool / Sepia ship their characteristic color cast
    /// here; B&W goes through saturation and doesn't need a tint.
    public var liveTint: Color {
        switch self {
        case .none, .bw: return .white
        case .sepia:     return Color(red: 1.00, green: 0.85, blue: 0.60)
        case .cool:      return Color(red: 0.85, green: 0.95, blue: 1.10)
        }
    }

    // MARK: Capture-time filter

    /// CIImage → CIImage transform shared by still and video
    /// pipelines. The still path wraps this in a `CIContext.
    /// createCGImage`; the video path hands it to
    /// `AVMutableVideoComposition`'s
    /// `applyingCIFiltersWithHandler` so each decoded frame gets
    /// the same look. Returns `input` on `.none` so callers don't
    /// need to special-case.
    public func applyToCIImage(_ input: CIImage) -> CIImage {
        switch self {
        case .none:
            return input
        case .bw:
            // `CIPhotoEffectMono` — Apple's curated B&W.
            // Slightly warmer than a flat desaturation, matches
            // what the iOS Camera app would produce.
            let f = CIFilter(name: "CIPhotoEffectMono")!
            f.setValue(input, forKey: kCIInputImageKey)
            return f.outputImage ?? input
        case .sepia:
            let f = CIFilter(name: "CISepiaTone")!
            f.setValue(input, forKey: kCIInputImageKey)
            f.setValue(0.80, forKey: kCIInputIntensityKey)
            return f.outputImage ?? input
        case .cool:
            // Shift target neutral to a lower Kelvin → image
            // takes on a blue cast (the white point now sits
            // where ~4500K would, so cooler tones survive).
            let f = CIFilter(name: "CITemperatureAndTint")!
            f.setValue(input, forKey: kCIInputImageKey)
            f.setValue(CIVector(x: 6500, y: 0), forKey: "inputNeutral")
            f.setValue(CIVector(x: 4500, y: 0), forKey: "inputTargetNeutral")
            return f.outputImage ?? input
        }
    }

    /// Apply the filter to a `UIImage` at commit time. Returns the
    /// input verbatim when filter is `.none`. Falls back to the
    /// input on any CIFilter / CGImage failure so a transient
    /// pipeline hiccup never strands the user with a black still.
    public func apply(to image: UIImage) -> UIImage {
        guard self != .none else { return image }
        guard let ciImage = CIImage(image: image) else { return image }
        let output = applyToCIImage(ciImage)
        let context = CIContext()
        guard let cgImage = context.createCGImage(output, from: output.extent) else {
            return image
        }
        return UIImage(cgImage: cgImage, scale: image.scale, orientation: image.imageOrientation)
    }
}

/// Max long-edge dimension we keep for a captured still before
/// passing it on to `ImportArtifacts.build`. iPhone Pro sensors
/// land 48MP frames (8064x6048) which at the importer's
/// `jpegData(compressionQuality: 0.92)` encodes ~15-25MB JPEGs
/// — bigger than most users want sitting in their note
/// attachments. 2048px on the long edge keeps OCR + on-screen
/// detail intact while bringing the encoded file down to
/// ~400KB-1.5MB. The capture session itself still runs the
/// hardware at native resolution; we scale only the artifact we
/// persist.
private let capturedPhotoMaxDimension: CGFloat = 2048

struct PhotoCaptureSurface: View {

    let controller: ScanFlowController
    /// Owning user — threaded through every surface for symmetry,
    /// even though the photo-only path no longer needs it (the
    /// post-record voice-note write moved with the rest of the
    /// video machinery to `VideoCaptureSurface`).
    let userId: String
    let onDismiss: () -> Void

    @State private var permissionStatus: AVAuthorizationStatus = AVCaptureDevice.authorizationStatus(for: .video)

    var body: some View {
        ZStack {
            switch permissionStatus {
            case .authorized:
                ActivePhotoSurface(controller: controller, userId: userId, onDismiss: onDismiss)
            case .notDetermined:
                PhotoPermissionRationale(
                    title:     "Allow camera to take photos",
                    message:   "Photo mode uses your camera to capture a still image. You can still scan documents and import from your library without it.",
                    onRequest: requestPermission,
                )
            case .denied, .restricted:
                PhotoPermissionRationale(
                    title:     "Camera access blocked",
                    message:   "Enable camera access for QuickInk in Settings → Privacy → Camera, then come back.",
                    onRequest: openSettings,
                )
            @unknown default:
                PhotoPermissionRationale(
                    title:     "Allow camera to take photos",
                    message:   "Photo mode uses your camera to capture a still image.",
                    onRequest: requestPermission,
                )
            }
        }
        .task {
            // Auto-prompt on first mount when permission status is
            // still .notDetermined. Subsequent mounts skip the
            // request because the OS persists the decision.
            if permissionStatus == .notDetermined {
                let granted = await AVCaptureDevice.requestAccess(for: .video)
                permissionStatus = granted ? .authorized : .denied
            }
        }
    }

    private func requestPermission() {
        Task {
            let granted = await AVCaptureDevice.requestAccess(for: .video)
            await MainActor.run {
                permissionStatus = granted ? .authorized : .denied
            }
        }
    }

    private func openSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(url)
    }
}

// MARK: - Active surface

private struct ActivePhotoSurface: View {

    let controller: ScanFlowController
    let userId: String
    let onDismiss: () -> Void

    @StateObject private var session: PhotoCaptureSession = PhotoCaptureSession()

    /// Inline error surfaced when `ImportArtifacts.build` returns
    /// nil after a successful capture (JPEG/PDF write failure).
    /// Shown as a toast above the Use Photo CTA so the user can
    /// retry without losing the buffer.
    @State private var commitError: String? = nil
    @State private var isCommitting: Bool = false

    /// Filter strip expand/collapse state. Default `false` →
    /// only the active filter chip is visible (anchored left of
    /// the shutter button). Tap that chip → expands upward into
    /// the full chip column. Tap any chip while expanded →
    /// selects + collapses. Resets to false on every fresh mount
    /// and any time the user picks a chip — the strip is never
    /// "stuck open."
    @State private var isFilterStripExpanded: Bool = false

    var body: some View {
        VStack(spacing: 0) {
            ZStack {
                switch session.state {
                case .preview, .capturing:
                    PhotoSessionView(session: session)
                        .ignoresSafeArea(edges: .horizontal)
                        // Live-preview color filter. Identity
                        // values are no-ops, so when `activeFilter
                        // == .none` SwiftUI optimises the modifier
                        // chain into a pass-through.
                        .saturation(session.activeFilter.liveSaturation)
                        .contrast(session.activeFilter.liveContrast)
                        .colorMultiply(session.activeFilter.liveTint)
                    if session.state == .capturing {
                        Color.black.opacity(0.25).ignoresSafeArea(edges: .horizontal)
                        ProgressView()
                            .progressViewStyle(.circular)
                            .tint(.white)
                    }
                case .captured(let image):
                    capturedPreview(image: image)
                }
            }
            .frame(maxHeight: .infinity)

            switch session.state {
            case .preview, .capturing:
                shutterRow
            case .captured:
                commitRow
            }
        }
        .background(Color.black)
        .onAppear {
            session.start()
        }
        .onDisappear {
            session.stop()
            // Drop the captured buffer on disappear (e.g. app
            // backgrounded during `.captured`). The spec marks
            // the buffer volatile by design — preserving it
            // across a background round-trip is more state than
            // the feature warrants.
            session.discardBuffer()
        }
    }

    // MARK: - Filter strip

    /// Collapse-aware filter strip. Default `isFilterStripExpanded
    /// == false` renders only the active filter chip; tapping
    /// expands the column upward with the other chips above it.
    /// The active chip is always the last child in the VStack so
    /// its on-screen position is fixed (it stays put at the
    /// bottom-leading anchor while siblings appear/disappear
    /// above). Tap any chip while expanded → select it and
    /// collapse.
    @ViewBuilder
    private var filterStrip: some View {
        VStack(alignment: .leading, spacing: 6) {
            if isFilterStripExpanded {
                ForEach(PhotoFilter.allCases.filter { $0 != session.activeFilter }, id: \.self) { filter in
                    filterChip(filter)
                        .transition(.opacity.combined(with: .move(edge: .bottom)))
                }
            }
            filterChip(session.activeFilter)
        }
        .animation(.easeOut(duration: 0.18), value: isFilterStripExpanded)
    }

    /// Single chip in the strip — live mini-preview of the camera
    /// with this filter applied, plus the filter's display name.
    /// The preview is a sibling `AVCaptureVideoPreviewLayer`
    /// attached to the same `captureSession`; AVFoundation
    /// supports any number of preview layers on a single session
    /// and each is GPU-rendered, so the cost of multiple live
    /// thumbnails (only while expanded) is negligible.
    @ViewBuilder
    private func filterChip(_ filter: PhotoFilter) -> some View {
        let isActive = filter == session.activeFilter
        Button(action: {
            if isFilterStripExpanded {
                session.activeFilter = filter
                isFilterStripExpanded = false
            } else {
                isFilterStripExpanded = true
            }
        }) {
            HStack(spacing: 6) {
                PhotoSessionView(session: session)
                    .saturation(filter.liveSaturation)
                    .contrast(filter.liveContrast)
                    .colorMultiply(filter.liveTint)
                    .frame(width: 32, height: 32)
                    .clipShape(RoundedRectangle(cornerRadius: 6))
                    .overlay(
                        RoundedRectangle(cornerRadius: 6)
                            .stroke(
                                isActive ? Color.white : Color.white.opacity(0.35),
                                lineWidth: isActive ? 2 : 1,
                            ),
                    )
                Text(filter.displayName)
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(.white)
            }
            .padding(.horizontal, 6)
            .padding(.vertical, 4)
            .background(
                RoundedRectangle(cornerRadius: 8).fill(
                    isActive ? QuickInkColors.accent.opacity(0.85) : Color.black.opacity(0.55),
                ),
            )
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(filter.displayName) filter")
        .accessibilityAddTraits(isActive ? [.isSelected] : [])
    }

    // MARK: - Camera flip button

    @ViewBuilder
    private var cameraFlipButton: some View {
        Button(action: { session.flipCamera() }) {
            Image(systemName: "arrow.triangle.2.circlepath.camera")
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: 40, height: 40)
                .background(Circle().fill(Color.black.opacity(0.55)))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Switch camera")
    }

    // MARK: - Captured-state preview

    @ViewBuilder
    private func capturedPreview(image: UIImage) -> some View {
        Image(uiImage: image)
            .resizable()
            .scaledToFit()
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Color.black)
    }

    // MARK: - Shutter row (live)

    @ViewBuilder
    private var shutterRow: some View {
        VStack(spacing: QuickInkSpacing.s2) {
            ZStack {
                PhotoShutterButton()
                    .onTapGesture { triggerCapture() }
                    .disabled(session.state == .capturing)
                    .opacity(session.state == .capturing ? 0.5 : 1.0)
                if session.state == .preview {
                    cameraFlipButton
                        .frame(maxWidth: .infinity, alignment: .trailing)
                }
            }
            .frame(maxWidth: .infinity)
            .overlay(alignment: .bottomLeading) {
                if session.state == .preview {
                    filterStrip
                }
            }
            Text("Tap for photo")
                .font(.system(size: 11, weight: .medium))
                .foregroundStyle(Color.white.opacity(0.55))
        }
        .padding(.horizontal, QuickInkSpacing.s5)
        .padding(.vertical, QuickInkSpacing.s4)
        .padding(.bottom, QuickInkSpacing.s7)
    }

    private func triggerCapture() {
        guard session.state == .preview else { return }
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
        CaptureAnalytics.manualFired(mode: .photo)
        session.triggerCapture()
    }

    // MARK: - Commit row (captured)

    @ViewBuilder
    private var commitRow: some View {
        VStack(spacing: QuickInkSpacing.s3) {
            if let commitError = commitError {
                Text(commitError)
                    .font(QuickInkText.label)
                    .foregroundStyle(.white)
                    .padding(.horizontal, QuickInkSpacing.s4)
                    .padding(.vertical, QuickInkSpacing.s2)
                    .background(
                        Capsule().fill(Color.black.opacity(0.65))
                    )
            }
            HStack {
                Button(action: retake) {
                    HStack(spacing: 6) {
                        Image(systemName: "xmark")
                            .font(.system(size: 14, weight: .semibold))
                        Text("Retake")
                            .font(QuickInkText.label)
                    }
                    .foregroundStyle(.white)
                    .padding(.horizontal, QuickInkSpacing.s4)
                    .padding(.vertical, QuickInkSpacing.s3)
                    .background(Color.white.opacity(0.10))
                    .clipShape(Capsule())
                }
                .buttonStyle(.plain)
                .disabled(isCommitting)
                .accessibilityLabel("Retake")

                Spacer()

                Button(action: commit) {
                    HStack(spacing: 6) {
                        if isCommitting {
                            ProgressView()
                                .progressViewStyle(.circular)
                                .tint(.white)
                        } else {
                            Image(systemName: "checkmark")
                                .font(.system(size: 14, weight: .semibold))
                        }
                        Text("OK")
                            .font(QuickInkText.label)
                    }
                    .foregroundStyle(.white)
                    .padding(.horizontal, QuickInkSpacing.s5)
                    .padding(.vertical, QuickInkSpacing.s3)
                    .background(QuickInkColors.accent)
                    .clipShape(Capsule())
                    .shadow(color: QuickInkColors.accent.opacity(0.5), radius: 12, x: 0, y: 4)
                }
                .buttonStyle(.plain)
                .disabled(isCommitting)
                .accessibilityLabel("Use this capture")
            }
        }
        .padding(.horizontal, QuickInkSpacing.s5)
        .padding(.vertical, QuickInkSpacing.s4)
        .padding(.bottom, QuickInkSpacing.s7)
    }

    private func retake() {
        commitError = nil
        session.discardBuffer()
    }

    /// Use Photo handler. Builds the photo artifacts (PDF + JPEG)
    /// from the captured frame and runs the controller's scan-
    /// complete pass. No video / voice-note plumbing — that lives
    /// on `VideoCaptureSurface`.
    private func commit() {
        guard case .captured(let image) = session.state else { return }
        guard let result = ImportArtifacts.build(from: [image]) else {
            commitError = "Couldn't save photo — try again"
            return
        }
        isCommitting = true
        controller.onScanComplete(
            pdfURL:     result.pdfURL,
            previewURL: result.previewURL,
            pageURLs:   result.pageURLs,
            source:     "photo",
            paperSize:  .custom,
        )
        onDismiss()
    }
}

// MARK: - Shutter button

/// 78pt coral disc with a camera icon. Plain visual — the
/// parent's `.onTapGesture` handles the tap so this view just
/// paints the chrome.
private struct PhotoShutterButton: View {
    var body: some View {
        ZStack {
            Circle()
                .stroke(Color.white.opacity(0.6), lineWidth: 3)
                .frame(width: 78, height: 78)
            Circle()
                .fill(QuickInkColors.accent)
                .frame(width: 64, height: 64)
            Image(systemName: "camera.fill")
                .font(.system(size: 26, weight: .semibold))
                .foregroundStyle(.white)
        }
        .shadow(
            color: QuickInkColors.accent.opacity(0.5),
            radius: 16, x: 0, y: 6,
        )
        .accessibilityLabel("Take photo")
    }
}

// MARK: - Permission rationale

/// Sibling of `BusinessCardCaptureSurface.PermissionRationale`.
/// Each surface keeps its own private copy so the per-surface
/// copy strings live next to the surface using them — small
/// duplication, big readability win versus a single shared
/// rationale parameterised on five strings.
private struct PhotoPermissionRationale: View {
    let title: String
    /// Named `message` rather than `body` because `body` is
    /// already SwiftUI's required View accessor — naming the
    /// stored property the same shadows it and crashes the
    /// compiler with "invalid redeclaration of 'body'".
    let message: String
    let onRequest: () -> Void

    var body: some View {
        VStack(spacing: QuickInkSpacing.s4) {
            Image(systemName: "camera")
                .font(.system(size: 48, weight: .light))
                .foregroundStyle(Color.white.opacity(0.85))
            Text(title)
                .font(QuickInkText.heading)
                .foregroundStyle(.white)
                .multilineTextAlignment(.center)
            Text(message)
                .font(QuickInkText.body)
                .foregroundStyle(Color.white.opacity(0.70))
                .multilineTextAlignment(.center)
                .padding(.horizontal, QuickInkSpacing.s5)
            Button(action: onRequest) {
                Text("Grant access")
                    .font(QuickInkText.label)
                    .foregroundStyle(.white)
                    .padding(.horizontal, QuickInkSpacing.s5)
                    .padding(.vertical, QuickInkSpacing.s3)
                    .background(
                        Capsule().fill(QuickInkColors.accent)
                    )
            }
            .buttonStyle(.plain)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(QuickInkSpacing.s5)
    }
}

// MARK: - UIViewRepresentable preview

private struct PhotoSessionView: UIViewRepresentable {
    let session: PhotoCaptureSession

    func makeUIView(context: Context) -> PhotoPreviewUIView {
        let view = PhotoPreviewUIView()
        view.previewLayer.videoGravity = .resizeAspectFill
        view.attach(session: session.captureSession)
        return view
    }

    func updateUIView(_ uiView: PhotoPreviewUIView, context: Context) {
        // No-op — the session itself owns lifecycle changes.
    }
}

/// Bare `UIView` that hosts an `AVCaptureVideoPreviewLayer`. Set
/// as the layer-class so the preview layer auto-sizes with the
/// view's bounds — saves manual frame plumbing.
private final class PhotoPreviewUIView: UIView {
    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }

    var previewLayer: AVCaptureVideoPreviewLayer {
        layer as! AVCaptureVideoPreviewLayer
    }

    func attach(session: AVCaptureSession) {
        previewLayer.session = session
    }
}

// MARK: - Session coordinator

/// AVCaptureSession wrapper for the photo surface. One output:
///   - `AVCapturePhotoOutput`       — still capture on tap.
///
/// No movie file output, no audio input — `VideoCaptureSurface`
/// owns the recording path.
///
/// State transitions:
///
///   .preview         ─ live AVCaptureSession running, shutter
///                      armed.
///        │ triggerCapture()
///        ▼
///   .capturing       ─ photo output in flight; preview frozen
///                      behind a dim scrim.
///        │ photoOutput delegate fires
///        ▼
///   .captured(UIImage)
///        │ discardBuffer() (Retake or .onDisappear)
///        ▼
///   .preview
@MainActor
private final class PhotoCaptureSession: NSObject, ObservableObject,
    AVCapturePhotoCaptureDelegate
{
    enum State: Equatable {
        case preview
        case capturing
        case captured(UIImage)

        /// Equatable conformance — UIImage isn't Equatable by
        /// reference identity, so compare by case only.
        static func == (lhs: State, rhs: State) -> Bool {
            switch (lhs, rhs) {
            case (.preview, .preview),
                 (.capturing, .capturing):
                return true
            case (.captured, .captured):
                return true
            default:
                return false
            }
        }
    }

    @Published private(set) var state: State = .preview

    let captureSession = AVCaptureSession()
    private let sessionQueue = DispatchQueue(label: "app.quickink.photocapture.session")

    private let photoOutput = AVCapturePhotoOutput()

    /// Currently-bound camera input (front or back). Pinned so
    /// the camera-flip handler can detach and replace it without
    /// re-enumerating session inputs.
    private var currentCameraInput: AVCaptureDeviceInput?

    /// Active camera position. Defaults to `.back` since document /
    /// object capture is the dominant use case; the on-screen flip
    /// button toggles to `.front` and back.
    @Published private(set) var cameraPosition: AVCaptureDevice.Position = .back

    /// Active color filter for the live preview + still capture.
    /// Defaults to `.none` on every fresh mount — we don't
    /// persist the choice across sessions, since a filter that
    /// silently survived to the next launch could surprise the
    /// user.
    @Published var activeFilter: PhotoFilter = .none

    func start() {
        sessionQueue.async { [weak self] in
            self?.configureSessionIfNeeded()
            if let s = self?.captureSession, !s.isRunning {
                s.startRunning()
            }
        }
    }

    func stop() {
        sessionQueue.async { [weak self] in
            if let s = self?.captureSession, s.isRunning {
                s.stopRunning()
            }
        }
    }

    /// Defensive cleanup — `stop()` is also called from the
    /// SwiftUI view's `onDisappear`, but in some teardown
    /// orderings the async stop on `sessionQueue` can miss the
    /// window. Doing a synchronous `stopRunning()` here ensures
    /// the hardware camera is released the moment the last
    /// reference to `PhotoCaptureSession` is dropped.
    deinit {
        if captureSession.isRunning {
            captureSession.stopRunning()
        }
    }

    // MARK: Camera flip

    /// Toggle between front and back cameras. No-op outside
    /// `.preview` — flipping mid-capture would race the photo
    /// delegate callback. The view hides the flip button outside
    /// `.preview` too, but we guard here defensively.
    func flipCamera() {
        guard state == .preview else { return }
        let newPosition: AVCaptureDevice.Position = (cameraPosition == .back) ? .front : .back
        sessionQueue.async { [weak self] in
            guard let self = self else { return }
            guard let device = AVCaptureDevice.default(
                    .builtInWideAngleCamera,
                    for: .video,
                    position: newPosition,
                  ),
                  let input = try? AVCaptureDeviceInput(device: device)
            else {
                NSLog("[PhotoCapture] flipCamera: no %@ camera on device",
                      newPosition == .front ? "front" : "back")
                return
            }
            self.captureSession.beginConfiguration()
            if let current = self.currentCameraInput {
                self.captureSession.removeInput(current)
            }
            if self.captureSession.canAddInput(input) {
                self.captureSession.addInput(input)
                self.currentCameraInput = input
            } else {
                NSLog("[PhotoCapture] flipCamera: canAddInput=false for %@; reverting",
                      newPosition == .front ? "front" : "back")
                if let old = self.currentCameraInput,
                   self.captureSession.canAddInput(old) {
                    self.captureSession.addInput(old)
                }
                self.captureSession.commitConfiguration()
                return
            }
            // The input swap recreates the output connection from
            // scratch, so portrait orientation has to be re-
            // applied (defaults to landscape-right on a fresh
            // connection).
            if let conn = self.photoOutput.connection(with: .video),
               conn.isVideoOrientationSupported {
                conn.videoOrientation = .portrait
            }
            self.captureSession.commitConfiguration()
            Task { @MainActor in self.cameraPosition = newPosition }
        }
    }

    // MARK: Still capture

    func triggerCapture() {
        guard state == .preview else { return }
        state = .capturing
        let settings = AVCapturePhotoSettings()
        settings.flashMode = .off
        sessionQueue.async { [weak self] in
            guard let self = self else { return }
            self.photoOutput.capturePhoto(with: settings, delegate: self)
        }
    }

    func discardBuffer() {
        state = .preview
    }

    // MARK: AVCaptureSession setup

    private var configured = false
    private func configureSessionIfNeeded() {
        guard !configured else { return }
        configured = true

        captureSession.beginConfiguration()
        // `.photo` preset — optimal for a single-output still
        // pipeline. `VideoCaptureSurface` uses `.high` because its
        // movie + audio outputs need a configuration that allows
        // both; the photo-only path here doesn't.
        captureSession.sessionPreset = .photo

        if let camera = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: cameraPosition),
           let input  = try? AVCaptureDeviceInput(device: camera),
           captureSession.canAddInput(input)
        {
            captureSession.addInput(input)
            self.currentCameraInput = input
        }

        photoOutput.maxPhotoQualityPrioritization = .quality
        if captureSession.canAddOutput(photoOutput) {
            captureSession.addOutput(photoOutput)
        }
        if let conn = photoOutput.connection(with: .video),
           conn.isVideoOrientationSupported
        {
            conn.videoOrientation = .portrait
        }

        captureSession.commitConfiguration()
    }

    // MARK: Photo delegate

    nonisolated func photoOutput(
        _ output: AVCapturePhotoOutput,
        didFinishProcessingPhoto photo: AVCapturePhoto,
        error: Error?,
    ) {
        if let error = error {
            print("Photo capturePhoto failed: \(error)")
            Task { @MainActor in self.state = .preview }
            return
        }
        guard let data = photo.fileDataRepresentation(),
              let image = UIImage(data: data) else {
            Task { @MainActor in self.state = .preview }
            return
        }
        Task { @MainActor in
            // Scale first, filter second. The captured frame
            // comes in at full sensor resolution (up to 48MP on
            // Pro models — a UIImage that big is fine in memory
            // but bloats the saved JPEG enormously). Scaling
            // before the CIFilter pass also speeds the filter
            // up — the same shader runs over a quarter as many
            // pixels.
            let scaled = Self.scaleDown(image, maxDimension: capturedPhotoMaxDimension)
            // Apply the active filter on MainActor so the read of
            // `activeFilter` doesn't cross actor boundaries from
            // the `nonisolated` delegate. CIFilter on a single
            // still is fast (~10-50ms) so the brief MainActor
            // hop is fine.
            let filtered = self.activeFilter.apply(to: scaled)
            self.state = .captured(filtered)
        }
    }

    /// Downscale `image` so its long edge is at most
    /// `maxDimension` points, preserving aspect ratio. No-op if
    /// the image is already smaller. Returns a non-Retina (scale
    /// 1.0), opaque-format raster so the resulting bitmap matches
    /// the pixel dimensions reported by `.size` — keeping a
    /// UIImage at the device's @3x scale would mean
    /// `ImportArtifacts.build` encodes the underlying CGImage at
    /// 3x the resolution we just asked for.
    private static func scaleDown(_ image: UIImage, maxDimension: CGFloat) -> UIImage {
        let maxSide = max(image.size.width, image.size.height)
        guard maxSide > maxDimension else { return image }
        let scale = maxDimension / maxSide
        let newSize = CGSize(
            width:  (image.size.width  * scale).rounded(),
            height: (image.size.height * scale).rounded(),
        )
        let format = UIGraphicsImageRendererFormat()
        format.scale  = 1.0
        format.opaque = true
        let renderer = UIGraphicsImageRenderer(size: newSize, format: format)
        return renderer.image { _ in
            image.draw(in: CGRect(origin: .zero, size: newSize))
        }
    }
}
