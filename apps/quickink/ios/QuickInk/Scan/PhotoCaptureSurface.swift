/*
 * PhotoCaptureSurface.swift
 *
 * Third capture surface — a single-shot still-photo camera that
 * plugs into the existing scan pipeline. Sibling to
 * `DocumentCaptureSurface` (VisionKit) and
 * `BusinessCardCaptureSurface` (AVCaptureSession + quad detector).
 * Reached two ways:
 *
 *   1. Long-press on the bottom-nav ⚡ FAB
 *      (QuickInkBottomNavBar.onLongPressScan), which mounts
 *      QuickCaptureScreen with `initialMode: .photo`. The long-
 *      press path uses a no-op persist on the coordinator so
 *      this transient surface doesn't overwrite the user's
 *      pill-selected last mode (`quickink.capture.last_mode`).
 *
 *   2. A Photo icon in the shutter row of `DocumentCaptureSurface`
 *      / `BusinessCardCaptureSurface`, which calls back through
 *      the coordinator to flip `mode = .photo`. Pill stays two-
 *      wide on the top bar; QuickCaptureScreen swaps in a static
 *      "Photo" chip while this surface is mounted.
 *
 * Top-down structure mirrors `BusinessCardCaptureSurface` minus
 * the detector / stability gate / overlay:
 *
 *   1. Camera-permission gate — AVAuthorizationStatus pre-flight
 *      with rationale + Grant / Open Settings CTA. The mode pill
 *      stays on the parent so the user can switch back to
 *      Document mode without granting.
 *
 *   2. UIViewRepresentable-wrapped AVCaptureVideoPreviewLayer
 *      bound to a session preset of `.photo` with a single
 *      `AVCapturePhotoOutput`. No video-data delegate — photo
 *      mode has no per-frame work to do, so we skip the
 *      sample-buffer plumbing the card surface needs.
 *
 *   3. Shutter row — same 78pt coral disc + white ring as the
 *      Document / Business Card surfaces, SF Symbol swapped to
 *      `camera.fill`. Tap fires `AVCapturePhotoOutput.capturePhoto`
 *      and flips the surface to the `.captured` state.
 *
 *   4. Captured-state preview — frozen still + Retake / Use Photo
 *      buttons. Use Photo runs `ImportArtifacts.build(from:
 *      [image])` (same helper the PhotosPicker import path uses)
 *      and calls `controller.onScanComplete(source: "photo",
 *      paperSize: .custom)`, then `onDismiss()` to collapse the
 *      capture cover. QuickInkRoot is already observing the
 *      controller and mounts `ScanCaptureSurface` (voice note →
 *      review) on the next render — the post-capture sequencing
 *      is source-agnostic, so the photo path lands on the same
 *      VoiceNoteCapturePane → ScanReviewScreen Document and
 *      Business Card surfaces already drive.
 *
 * Why no flash / flip / focus controls in v1: the spec calls
 * them out as nice-to-have but the basic shutter + retake +
 * commit path is the load-bearing piece. Adding them later is
 * an additive change to the `.preview` state's chrome row and
 * the `AVCapturePhotoSettings` builder — no scaffolding change.
 *
 * Mirror of Android `PhotoCaptureSurface.kt`.
 */

import SwiftUI
import AVFoundation
import UIKit
import ReleafCoreScan

/// First-launch discoverability state for the Photo-capture
/// long-press shortcut on the bottom-nav ⚡ FAB. Two persisted
/// bools combine to drive the small "Hold ⚡ for a quick photo"
/// tooltip that appears above the FAB:
///
///   - `hasCompletedFirstScanKey` — flipped to true the first
///     time any scan (Document / Business Card / Photo) lands
///     through `ScanFlowController.onScanComplete`. Gates the
///     tooltip so brand-new users on Home / Workspace don't see
///     a hint pointing at an action they haven't earned yet.
///
///   - `dismissedKey` — flipped to true the first time the user
///     long-presses the FAB. Hides the tooltip permanently from
///     that point forward; the user has discovered the gesture.
///
/// The bar reads both via `@AppStorage` so a write here triggers
/// a re-render via KVO without manual refresh wiring. Spec §3.1.
public enum PhotoFabHint {
    public static let hasCompletedFirstScanKey = "quickink.capture.has_completed_first_scan"
    public static let dismissedKey             = "quickink.capture.photo_fab_hint_dismissed"

    public static func markFirstScanCompleted() {
        // `set(true:)` is idempotent and thread-safe — cheap to
        // call from every `onPassComplete` rather than gating on
        // a read first.
        UserDefaults.standard.set(true, forKey: hasCompletedFirstScanKey)
    }

    public static func markDismissed() {
        UserDefaults.standard.set(true, forKey: dismissedKey)
    }
}

struct PhotoCaptureSurface: View {

    let controller: ScanFlowController
    let onDismiss: () -> Void

    @State private var permissionStatus: AVAuthorizationStatus = AVCaptureDevice.authorizationStatus(for: .video)

    var body: some View {
        ZStack {
            switch permissionStatus {
            case .authorized:
                ActivePhotoSurface(controller: controller, onDismiss: onDismiss)
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
            // Auto-prompt on first mount when permission status
            // is still .notDetermined. Subsequent mounts skip
            // the request because the OS persists the decision.
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
    let onDismiss: () -> Void

    @StateObject private var session: PhotoCaptureSession = PhotoCaptureSession()

    /// Inline error surfaced when `ImportArtifacts.build` returns
    /// nil after a successful capture (JPEG/PDF write failure).
    /// Shown as a toast above the Use Photo CTA so the user can
    /// retry without losing the buffer.
    @State private var commitError: String? = nil

    var body: some View {
        VStack(spacing: 0) {
            ZStack {
                switch session.state {
                case .preview, .capturing:
                    PhotoSessionView(session: session)
                        .ignoresSafeArea(edges: .horizontal)
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

            // Bottom action row swaps based on state. Live preview
            // shows the shutter; captured shows Retake / Use Photo.
            switch session.state {
            case .preview, .capturing:
                shutterRow
            case .captured(let image):
                commitRow(image: image)
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
            HStack {
                Spacer()
                PhotoShutterButton(onTap: triggerShutter)
                    .disabled(session.state == .capturing)
                    .opacity(session.state == .capturing ? 0.5 : 1.0)
                Spacer()
            }
        }
        .padding(.horizontal, QuickInkSpacing.s5)
        .padding(.vertical, QuickInkSpacing.s4)
        .padding(.bottom, QuickInkSpacing.s7)
    }

    // MARK: - Commit row (captured)

    @ViewBuilder
    private func commitRow(image: UIImage) -> some View {
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
                .accessibilityLabel("Retake photo")

                Spacer()

                Button(action: { commit(image: image) }) {
                    HStack(spacing: 6) {
                        Image(systemName: "checkmark")
                            .font(.system(size: 14, weight: .semibold))
                        Text("Use Photo")
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
                .accessibilityLabel("Use this photo")
            }
        }
        .padding(.horizontal, QuickInkSpacing.s5)
        .padding(.vertical, QuickInkSpacing.s4)
        .padding(.bottom, QuickInkSpacing.s7)
    }

    private func triggerShutter() {
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
        CaptureAnalytics.manualFired(mode: .photo)
        session.triggerCapture()
    }

    private func retake() {
        commitError = nil
        session.discardBuffer()
    }

    private func commit(image: UIImage) {
        guard let result = ImportArtifacts.build(from: [image]) else {
            commitError = "Couldn't save photo — try again"
            return
        }
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

private struct PhotoShutterButton: View {
    let onTap: () -> Void
    var body: some View {
        Button(action: onTap) {
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
            .shadow(color: QuickInkColors.accent.opacity(0.5), radius: 16, x: 0, y: 6)
        }
        .buttonStyle(.plain)
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

/// Bare `UIView` that hosts an `AVCaptureVideoPreviewLayer`.
/// Set as the layer-class so the preview layer auto-sizes
/// with the view's bounds — saves manual frame plumbing.
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

/// Minimal AVCaptureSession wrapper for the photo surface. Unlike
/// `CardCaptureSession` we don't bind a video-data output — photo
/// mode has no per-frame detection work, so the session has only
/// a back-camera input + a single `AVCapturePhotoOutput`. State
/// transitions:
///
///   .preview    ─ live AVCaptureSession running, shutter armed
///        │ triggerCapture()
///        ▼
///   .capturing  ─ photo output in flight; preview frozen
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
        /// reference identity, so compare by case only. We don't
        /// need fine-grained diffing on the captured image since
        /// it doesn't change once it lands.
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
        captureSession.sessionPreset = .photo

        if let camera = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
           let input  = try? AVCaptureDeviceInput(device: camera),
           captureSession.canAddInput(input)
        {
            captureSession.addInput(input)
        }

        photoOutput.maxPhotoQualityPrioritization = .quality
        if captureSession.canAddOutput(photoOutput) {
            captureSession.addOutput(photoOutput)
        }
        // Lock to portrait — host activity is portrait-only,
        // matching the card surface. Pixel-buffer orientation
        // stays predictable for downstream code.
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
            // Fallback path — return to preview rather than
            // stranding the surface in `.capturing`. We don't
            // surface a toast for the capture itself (a system
            // shutter that drops a frame is rare and recoverable
            // by retap); only commit-time errors warrant UI.
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
            self.state = .captured(image)
        }
    }
}
