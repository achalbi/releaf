/*
 * BusinessCardCaptureSurface.swift
 *
 * In-app AVCaptureSession preview + custom detector for the
 * iOS Business Card capture mode. Mounted by
 * QuickCaptureScreen when the user toggles to
 * `CaptureMode.businessCard`; the surface tears down when
 * the user toggles back or dismisses the screen.
 *
 * Top-down structure:
 *
 *   1. Camera-permission gate — if AVAuthorizationStatus !=
 *      .authorized, show a rationale + Grant button. The
 *      mode pill is still on the parent so the user can
 *      switch back to Document mode without granting.
 *
 *   2. UIViewRepresentable-wrapped AVCaptureVideoPreviewLayer.
 *      AVCaptureSession is bound with a video-data output
 *      (1280×720, kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange)
 *      and a photo output (high-resolution JPEG). The
 *      session releases automatically on view disappear.
 *
 *   3. The video-data delegate runs `BusinessCardDetector`
 *      on each frame; valid quads flow into `StabilityGate`.
 *      Partial/none results refresh the overlay tint and
 *      reset the gate. When the gate fires, the surface
 *      kicks off `AVCapturePhotoOutput.capturePhoto` and
 *      routes the still through `BusinessCardPostProcessor`.
 *
 *   4. `CardGuideOverlay` sits on top of the preview, driven
 *      by `OverlayState` derived from the latest detection.
 *      It publishes the canvas-space guide rect back via
 *      `onMetricsKnown` so the surface can map it to
 *      pixel-buffer coordinates for the detector + IoU
 *      gate.
 *
 *   5. Manual shutter — same coral disc as the Document
 *      surface. Tap fires `capturePhoto` immediately,
 *      regardless of detection state; the post-processor
 *      falls back to the guide rect as the quad when no
 *      valid detection is in flight.
 *
 * Threading: the AVCapture delegates fire on a dedicated
 * `DispatchQueue` (sessionQueue / sampleBufferQueue); state
 * updates marshal to MainActor via `Task { @MainActor in }`.
 * The post-processor runs on the global concurrent queue
 * since the warp + JPEG encode are CPU/GPU-bound enough to
 * drop a frame on the main actor.
 *
 * Mirror of Android `BusinessCardCaptureSurface.kt`.
 */

import SwiftUI
import AVFoundation
import UIKit
import ReleafCoreScan

/// Idle hint kicks in after this much wall time with no
/// detection. Matches the Android constant.
private let idleHintThresholdMs: TimeInterval = 8.0

struct BusinessCardCaptureSurface: View {

    let controller: ScanFlowController
    let onDismiss: () -> Void

    @State private var permissionStatus: AVAuthorizationStatus = AVCaptureDevice.authorizationStatus(for: .video)

    var body: some View {
        ZStack {
            switch permissionStatus {
            case .authorized:
                ActiveBusinessCardSurface(controller: controller, onDismiss: onDismiss)
            case .notDetermined:
                PermissionRationale(
                    title:   "Allow camera to scan cards",
                    message: "Business Card mode uses your camera to detect and capture cards in-frame. Document mode keeps working without it.",
                    onRequest: requestPermission,
                )
            case .denied, .restricted:
                PermissionRationale(
                    title:   "Camera access blocked",
                    message: "Enable camera access for QuickInk in Settings → Privacy → Camera, then come back.",
                    onRequest: openSettings,
                )
            @unknown default:
                PermissionRationale(
                    title:   "Allow camera to scan cards",
                    message: "Business Card mode uses your camera to detect and capture cards in-frame.",
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

private struct ActiveBusinessCardSurface: View {

    let controller: ScanFlowController
    let onDismiss: () -> Void

    @StateObject private var session: CardCaptureSession = CardCaptureSession()
    @State private var overlayState: OverlayState = .neutral
    @State private var lastDetectionAt: Date = Date()
    @State private var guideMetrics: GuideMetrics? = nil
    @State private var ticker: Timer? = nil

    var body: some View {
        VStack(spacing: 0) {
            ZStack {
                CardCaptureSessionView(session: session)
                    .ignoresSafeArea(edges: .horizontal)

                CardGuideOverlay(
                    state: overlayState,
                    onMetricsKnown: { metrics in
                        guideMetrics = metrics
                        session.updateOverlayMetrics(metrics)
                    },
                )
            }
            .frame(maxHeight: .infinity)

            // Shutter row — manual fallback. Always available;
            // the post-processor falls back to the guide rect
            // as the "card quad" when no valid lock is in
            // flight.
            HStack {
                Spacer()
                BusinessCardShutterButton(onTap: triggerManualShutter)
                Spacer()
            }
            .padding(.horizontal, QuickInkSpacing.s5)
            .padding(.vertical, QuickInkSpacing.s4)
            .padding(.bottom, QuickInkSpacing.s7)
        }
        .background(Color.black)
        .onAppear {
            session.controller = controller
            session.onOverlayState = { state in
                Task { @MainActor in
                    overlayState = state
                    if state == .valid || state == .partial {
                        lastDetectionAt = Date()
                    }
                }
            }
            session.onCaptureComplete = {
                Task { @MainActor in onDismiss() }
            }
            session.start()
            ticker = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { _ in
                Task { @MainActor in
                    if overlayState == .neutral &&
                       Date().timeIntervalSince(lastDetectionAt) > idleHintThresholdMs
                    {
                        overlayState = .idle
                    }
                }
            }
        }
        .onDisappear {
            ticker?.invalidate()
            ticker = nil
            session.stop()
        }
    }

    private func triggerManualShutter() {
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
        CaptureAnalytics.manualFired(mode: .businessCard)
        session.triggerManualCapture()
    }
}

// MARK: - Shutter button

private struct BusinessCardShutterButton: View {
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
                Image(systemName: "bolt.fill")
                    .font(.system(size: 26, weight: .semibold))
                    .foregroundStyle(.white)
            }
            .shadow(color: QuickInkColors.accent.opacity(0.5), radius: 16, x: 0, y: 6)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Capture business card")
    }
}

// MARK: - Permission rationale

private struct PermissionRationale: View {
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

private struct CardCaptureSessionView: UIViewRepresentable {
    let session: CardCaptureSession

    func makeUIView(context: Context) -> CardPreviewUIView {
        let view = CardPreviewUIView()
        view.previewLayer.videoGravity = .resizeAspectFill
        view.attach(session: session.captureSession)
        return view
    }

    func updateUIView(_ uiView: CardPreviewUIView, context: Context) {
        // No-op — the session itself owns lifecycle changes.
    }
}

/// Bare `UIView` that hosts an `AVCaptureVideoPreviewLayer`.
/// Set as the layer-class so the preview layer auto-sizes
/// with the view's bounds — saves manual frame plumbing.
private final class CardPreviewUIView: UIView {
    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }

    var previewLayer: AVCaptureVideoPreviewLayer {
        layer as! AVCaptureVideoPreviewLayer
    }

    func attach(session: AVCaptureSession) {
        previewLayer.session = session
    }
}

// MARK: - Session coordinator

@MainActor
private final class CardCaptureSession: NSObject, ObservableObject,
    AVCaptureVideoDataOutputSampleBufferDelegate,
    AVCapturePhotoCaptureDelegate
{
    let captureSession = AVCaptureSession()
    private let sessionQueue = DispatchQueue(label: "app.quickink.cardcapture.session")
    private let sampleQueue  = DispatchQueue(label: "app.quickink.cardcapture.sample")
    private let processingQueue = DispatchQueue(label: "app.quickink.cardcapture.processing", qos: .userInitiated)

    private let videoOutput = AVCaptureVideoDataOutput()
    private let photoOutput = AVCapturePhotoOutput()

    private var detector: BusinessCardDetector? = nil
    private var analyzerSize: CGSize = .zero
    private let stabilityGate = StabilityGate()

    /// Latest valid quad in pixel-buffer-space, used at
    /// capture time to scale into still-image coordinates.
    private var latestValidQuad: DetectedQuad? = nil
    /// Latest guide rect in pixel-buffer space, mirrored
    /// from the overlay's onMetricsKnown.
    private var latestGuideInBuffer: GuideRect? = nil
    private var latestGuideMetrics: GuideMetrics? = nil

    var controller: ScanFlowController? = nil
    var onOverlayState: ((OverlayState) -> Void)? = nil
    var onCaptureComplete: (() -> Void)? = nil

    /// Whether the current capture is auto or manual, captured
    /// at the moment of `capturePhoto` so the delegate knows
    /// which analytics path + post-process path to take.
    private var pendingCaptureIsAuto: Bool = false
    private var pendingAutoLockMs: Int = 0

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

    func updateOverlayMetrics(_ metrics: GuideMetrics) {
        // Translate the canvas-space guide rect into pixel
        // buffer coordinates using the same fractional layout
        // the detector's analyzer guide uses. Both spaces use
        // `videoGravity = .resizeAspectFill`, so the fractions
        // map 1:1.
        latestGuideMetrics = metrics
        guard analyzerSize != .zero else { return }
        latestGuideInBuffer = computeAnalyzerGuide(
            width:  Int(analyzerSize.width),
            height: Int(analyzerSize.height),
        )
    }

    /// User tapped the manual shutter. The video-data delegate
    /// may not have a valid quad in flight; the post-processor
    /// falls back to the guide rect as the quad in that case.
    func triggerManualCapture() {
        pendingCaptureIsAuto = false
        let settings = AVCapturePhotoSettings()
        settings.flashMode = .off
        sessionQueue.async { [weak self] in
            guard let self = self else { return }
            self.photoOutput.capturePhoto(with: settings, delegate: self)
        }
    }

    // MARK: AVCaptureSession setup

    private var configured = false
    private func configureSessionIfNeeded() {
        guard !configured else { return }
        configured = true

        captureSession.beginConfiguration()
        captureSession.sessionPreset = .photo

        if let camera = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
           let input = try? AVCaptureDeviceInput(device: camera),
           captureSession.canAddInput(input)
        {
            captureSession.addInput(input)
        }

        videoOutput.videoSettings = [
            kCVPixelBufferPixelFormatTypeKey as String:
                NSNumber(value: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange),
        ]
        videoOutput.alwaysDiscardsLateVideoFrames = true
        videoOutput.setSampleBufferDelegate(self, queue: sampleQueue)
        if captureSession.canAddOutput(videoOutput) {
            captureSession.addOutput(videoOutput)
        }
        // Lock to portrait — the host activity is portrait-only,
        // so the user holds the card horizontally across the
        // phone. Pixel-buffer coordinates and analyzer guide
        // both reason in portrait.
        if let conn = videoOutput.connection(with: .video),
           conn.isVideoOrientationSupported
        {
            conn.videoOrientation = .portrait
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

    // MARK: Frame delegate

    nonisolated func captureOutput(
        _ output: AVCaptureOutput,
        didOutput sampleBuffer: CMSampleBuffer,
        from connection: AVCaptureConnection,
    ) {
        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        let w = CVPixelBufferGetWidth(pixelBuffer)
        let h = CVPixelBufferGetHeight(pixelBuffer)

        Task { @MainActor in
            await self.processFrame(pixelBuffer: pixelBuffer, width: w, height: h)
        }
    }

    private func processFrame(pixelBuffer: CVPixelBuffer, width: Int, height: Int) async {
        let sz = CGSize(width: width, height: height)
        if detector == nil || analyzerSize != sz {
            detector = BusinessCardDetector(analyzerWidth: width, analyzerHeight: height)
            analyzerSize = sz
            latestGuideInBuffer = computeAnalyzerGuide(width: width, height: height)
        }
        let guide = latestGuideInBuffer ?? computeAnalyzerGuide(width: width, height: height)
        let result = detector?.detect(pixelBuffer: pixelBuffer, guide: guide) ?? .none

        switch result {
        case .none:
            stabilityGate.reset()
            latestValidQuad = nil
            onOverlayState?(.neutral)
        case .partial:
            stabilityGate.reset()
            latestValidQuad = nil
            onOverlayState?(.partial)
        case .valid(let quad, _):
            latestValidQuad = quad
            onOverlayState?(.valid)
            let fired = stabilityGate.vote(quad)
            if fired {
                let elapsed = Int(stabilityGate.streakElapsedMs())
                pendingCaptureIsAuto = true
                pendingAutoLockMs = elapsed
                UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                CaptureAnalytics.autoFired(mode: .businessCard, timeToLockMs: elapsed)
                let settings = AVCapturePhotoSettings()
                settings.flashMode = .off
                sessionQueue.async { [weak self] in
                    guard let self = self else { return }
                    self.photoOutput.capturePhoto(with: settings, delegate: self)
                }
            }
        }
    }

    // MARK: Photo delegate

    nonisolated func photoOutput(
        _ output: AVCapturePhotoOutput,
        didFinishProcessingPhoto photo: AVCapturePhoto,
        error: Error?,
    ) {
        if let error = error {
            print("BusinessCard capturePhoto failed: \(error)")
            return
        }
        guard let cg = photo.cgImageRepresentation() else { return }

        Task { @MainActor in
            await self.handlePhoto(cgImage: cg)
        }
    }

    private func handlePhoto(cgImage: CGImage) async {
        guard let controller = controller else {
            onCaptureComplete?()
            return
        }
        // Scale the latest analyzer-space quad / guide into the
        // photo's pixel grid. The photo is portrait-oriented
        // (we set videoOrientation = .portrait above), so its
        // dimensions are width = sensor portrait width, height =
        // sensor portrait height; both analyzer and photo
        // share the same aspect ratio, so a single scale
        // factor maps fractions cleanly.
        let photoSize = CGSize(width: cgImage.width, height: cgImage.height)
        let sx = Float(photoSize.width)  / Float(analyzerSize.width  == 0 ? 1 : analyzerSize.width)
        let sy = Float(photoSize.height) / Float(analyzerSize.height == 0 ? 1 : analyzerSize.height)
        let quadInPhoto = latestValidQuad?.scaled(sx: sx, sy: sy)
        let guideInPhoto = computeAnalyzerGuide(
            width:  Int(photoSize.width),
            height: Int(photoSize.height),
        )
        await Task.detached(priority: .userInitiated) {
            _ = await BusinessCardPostProcessor.process(
                source:       cgImage,
                quadInImage:  quadInPhoto,
                guideInImage: guideInPhoto,
                controller:   controller,
            )
        }.value
        onCaptureComplete?()
    }
}

/// Compute the analyzer-space guide rect from analyzer
/// dimensions using the same fractional layout
/// (`GuideMetrics.guideWidthFraction`, ISO 7810 ID-1 aspect,
/// centered horizontally at 50% / vertically at 45%). Used
/// by both the live preview and the at-capture rescale so
/// they agree on the same target region.
internal func computeAnalyzerGuide(width: Int, height: Int) -> GuideRect {
    let aw = Float(width)
    let ah = Float(height)
    let targetW = aw * Float(GuideMetrics.guideWidthFraction)
    let targetH = targetW / Float(GuideMetrics.cardAspectRatio)
    let cx = aw * 0.5
    let cy = ah * 0.45
    let left = max(0, cx - targetW * 0.5)
    let top  = max(0, cy - targetH * 0.5)
    return GuideRect(
        left: left,
        top: top,
        right: min(aw, left + targetW),
        bottom: min(ah, top + targetH),
    )
}
