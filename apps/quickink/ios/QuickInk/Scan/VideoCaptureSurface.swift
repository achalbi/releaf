/*
 * VideoCaptureSurface.swift
 *
 * Dedicated video-recording surface — sibling to
 * `PhotoCaptureSurface` (still photo only) and the rest of the
 * capture surfaces (`DocumentCaptureSurface`,
 * `BusinessCardCaptureSurface`). Reached from the Sundial radial
 * menu's "Video" ray on the bottom-nav ⚡ FAB, which mounts
 * `QuickCaptureScreen` with `initialMode: .video`.
 *
 * Shutter gesture (per modern OS-camera convention):
 *
 *   - Tap (idle)        → start recording. Haptic + the shutter
 *                         button swaps from coral camera-icon to
 *                         red stop-square.
 *   - Tap (recording)   → stop recording. Same button position;
 *                         the state-swap reads as one toggle.
 *
 * A hard 2:00 cap is enforced by
 * `AVCaptureMovieFileOutput.maxRecordedDuration` so the user can
 * walk away without blowing up a transcription pass downstream.
 * The same cap is the reason the elapsed-time chip in the live
 * overlay clamps at 2:00 — keeps the on-screen counter honest
 * with what the file will actually contain.
 *
 * Tap-to-toggle replaces the old "press-and-hold to record" idiom
 * that used to live on `PhotoCaptureSurface`. The Sundial menu
 * gives users a dedicated "Video" button, so the dual gesture
 * stopped paying for itself — separate surfaces with a single
 * unambiguous tap each is the simpler model.
 *
 * A small flip-camera button sits at the trailing edge of the
 * shutter row, next to the shutter itself (matches the iOS
 * Camera / Instagram convention). It is only shown in the idle
 * `.preview` state — hidden while a recording or post-record
 * processing pass is in flight, since tearing down the video
 * input mid-take would corrupt the in-flight `.mov` and race the
 * movie delegate callbacks.
 *
 * A collapse-by-default chip strip sits to the left of the
 * shutter and lets the user pick one of four color filters
 * (None / B&W / Sepia / Cool) — same `PhotoFilter` enum the
 * still surface uses. Collapsed → only the active chip is
 * visible. Tap → the column expands upward into the preview
 * area with all chips; each chip carries a live mini-preview of
 * the camera with that filter pre-applied. Tap a chip → it's
 * selected and the strip collapses again.
 *
 * The filter is applied to the live preview via cheap SwiftUI
 * `.saturation` / `.contrast` / `.colorMultiply` modifiers (no
 * per-frame CIFilter cost) AND baked into the saved `.mov` via
 * `AVMutableVideoComposition.applyingCIFiltersWithHandler` +
 * `AVAssetExportSession`. The chip strip is hidden while
 * recording so the user can't change filter mid-take — the post-
 * process snapshot is taken once when the recording ends.
 *
 * After capture, the surface lands on the standard Retake /
 * Use captured-preview UI: the first frame of the recorded video
 * serves as the still preview, with a "With audio" badge in the
 * top-leading corner when the audio track survived. Use writes
 * the first-frame JPEG via `ImportArtifacts.build`, fires
 * `controller.onScanComplete(source: "photo", paperSize: .custom)`
 * (videos share the photo source-tag — the first frame is still
 * just an arbitrary phone-camera frame whose aspect ratio tells
 * us nothing about A4 / Letter / card bands), and inserts the
 * extracted audio as a voice note against the freshly-created
 * captureId. The voice-note pane downstream auto-advances when
 * it sees the pre-attached row.
 *
 * Mirror of Android `VideoCaptureSurface.kt`.
 */

import SwiftUI
import AVFoundation
import UIKit
import ReleafCoreData
import ReleafCoreScan

/// Hard cap on a single recording. The same value the elapsed
/// chip clamps to, and the value `AVCaptureMovieFileOutput.maxRecordedDuration`
/// is set to so the recording auto-stops if the user walks away.
private let videoMaxRecordingSeconds: Int = 120

public struct VideoCaptureSurface: View {

    let controller: ScanFlowController
    /// Owning user — required so the post-recording voice-note
    /// insert can populate `voice_notes.user_id`. Threaded down
    /// from `QuickInkRoot`'s `MainShell` → `QuickCaptureScreen` →
    /// here. Kept as a stored property for symmetry with every
    /// other capture surface.
    let userId: String
    let onDismiss: () -> Void

    @State private var permissionStatus: AVAuthorizationStatus = AVCaptureDevice.authorizationStatus(for: .video)

    public init(
        controller: ScanFlowController,
        userId: String,
        onDismiss: @escaping () -> Void,
    ) {
        self.controller = controller
        self.userId     = userId
        self.onDismiss  = onDismiss
    }

    public var body: some View {
        ZStack {
            switch permissionStatus {
            case .authorized:
                ActiveVideoSurface(controller: controller, userId: userId, onDismiss: onDismiss)
            case .notDetermined:
                VideoPermissionRationale(
                    title:     "Allow camera to record video",
                    message:   "Video mode uses your camera and microphone to capture a short clip. You can still scan documents and import from your library without it.",
                    onRequest: requestPermission,
                )
            case .denied, .restricted:
                VideoPermissionRationale(
                    title:     "Camera access blocked",
                    message:   "Enable camera access for QuickInk in Settings → Privacy → Camera, then come back.",
                    onRequest: openSettings,
                )
            @unknown default:
                VideoPermissionRationale(
                    title:     "Allow camera to record video",
                    message:   "Video mode uses your camera and microphone to capture a short clip.",
                    onRequest: requestPermission,
                )
            }
        }
        .task {
            // Auto-prompt on first mount when permission status is
            // still .notDetermined. Microphone permission is
            // requested lazily at the moment the user starts a
            // recording — keeps the permission ask tightly coupled
            // to the action.
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

private struct ActiveVideoSurface: View {

    let controller: ScanFlowController
    let userId: String
    let onDismiss: () -> Void

    @StateObject private var session: VideoCaptureSession = VideoCaptureSession()

    /// Inline error surfaced when `ImportArtifacts.build` returns
    /// nil after a successful capture (JPEG/PDF write failure).
    /// Shown as a toast above the Use CTA so the user can retry
    /// without losing the buffer.
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
                case .preview, .recording, .processing:
                    VideoSessionView(session: session)
                        .ignoresSafeArea(edges: .horizontal)
                        // Live-preview color filter. Identity
                        // values are no-ops, so when `activeFilter
                        // == .none` SwiftUI optimises the modifier
                        // chain into a pass-through. Stays on
                        // through `.recording` too — the post-
                        // process re-export bakes the same filter
                        // into the saved `.mov`, so preview ≈
                        // saved output.
                        .saturation(session.activeFilter.liveSaturation)
                        .contrast(session.activeFilter.liveContrast)
                        .colorMultiply(session.activeFilter.liveTint)
                    if case .recording(let elapsed) = session.state {
                        recordingOverlay(elapsedSeconds: elapsed)
                    }
                    if session.state == .processing {
                        Color.black.opacity(0.25).ignoresSafeArea(edges: .horizontal)
                        ProgressView()
                            .progressViewStyle(.circular)
                            .tint(.white)
                    }
                case .captured(let image, _, _, _):
                    capturedPreview(image: image)
                }
            }
            .frame(maxHeight: .infinity)

            switch session.state {
            case .preview, .processing, .recording:
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

    // MARK: - Recording overlay

    @ViewBuilder
    private func recordingOverlay(elapsedSeconds: Int) -> some View {
        VStack {
            HStack(spacing: 6) {
                Circle()
                    .fill(Color.red)
                    .frame(width: 8, height: 8)
                Text(formatRecordingTime(elapsedSeconds))
                    .font(.system(size: 14, weight: .semibold, design: .rounded))
                    .foregroundStyle(.white)
            }
            .padding(.horizontal, QuickInkSpacing.s3)
            .padding(.vertical, QuickInkSpacing.s2)
            .background(Capsule().fill(Color.black.opacity(0.55)))
            .padding(.top, QuickInkSpacing.s5)
            Spacer()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
    }

    private func formatRecordingTime(_ seconds: Int) -> String {
        let mm = seconds / 60
        let ss = seconds % 60
        return String(format: "%01d:%02d", mm, ss)
    }

    // MARK: - Filter strip

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
                VideoSessionView(session: session)
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

    // MARK: - Camera flip

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
        ZStack(alignment: .topLeading) {
            Image(uiImage: image)
                .resizable()
                .scaledToFit()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(Color.black)
            // "With audio" badge — surfaces that the recorded
            // clip carried a usable audio track and that the
            // commit path will attach it as a voice note. Hidden
            // when mic was denied / no audio track survived.
            if case .captured(_, let audioURL, _, _) = session.state, audioURL != nil {
                HStack(spacing: 4) {
                    Image(systemName: "waveform")
                        .font(.system(size: 11, weight: .semibold))
                    Text("With audio")
                        .font(QuickInkText.caption)
                }
                .foregroundStyle(.white)
                .padding(.horizontal, QuickInkSpacing.s3)
                .padding(.vertical, QuickInkSpacing.s2)
                .background(Capsule().fill(Color.black.opacity(0.55)))
                .padding(QuickInkSpacing.s4)
            }
        }
    }

    // MARK: - Shutter row (live)

    @ViewBuilder
    private var shutterRow: some View {
        VStack(spacing: QuickInkSpacing.s2) {
            ZStack {
                VideoShutterButton(
                    isRecording: {
                        if case .recording = session.state { return true }
                        return false
                    }(),
                )
                .onTapGesture { handleShutterTap() }
                .disabled(session.state == .processing)
                .opacity(session.state == .processing ? 0.5 : 1.0)
                if session.state == .preview {
                    cameraFlipButton
                        .frame(maxWidth: .infinity, alignment: .trailing)
                }
            }
            .frame(maxWidth: .infinity)
            // Filter strip overlay sits at the bottom-leading of
            // the shutter ZStack. Anchored at the bottom so the
            // active chip stays put while the column expands
            // upward into the preview area above. Overlay (not a
            // ZStack child) so the expanded column's height
            // doesn't grow the row and push the shutter off
            // center.
            .overlay(alignment: .bottomLeading) {
                if session.state == .preview {
                    filterStrip
                }
            }
            shutterHint
        }
        .padding(.horizontal, QuickInkSpacing.s5)
        .padding(.vertical, QuickInkSpacing.s4)
        .padding(.bottom, QuickInkSpacing.s7)
    }

    /// Subtle copy under the shutter in the live state. Two
    /// variants:
    ///   - Preview   → "Tap to record"
    ///   - Recording → "Tap to stop" (the shutter swaps to a red
    ///                  stop icon already; the copy makes the
    ///                  affordance unmistakable).
    @ViewBuilder
    private var shutterHint: some View {
        if case .recording = session.state {
            Text("Tap to stop")
                .font(.system(size: 11, weight: .medium))
                .foregroundStyle(Color.white.opacity(0.55))
        } else {
            Text("Tap to record")
                .font(.system(size: 11, weight: .medium))
                .foregroundStyle(Color.white.opacity(0.55))
        }
    }

    /// Single-tap toggle. Idle → start recording; recording →
    /// stop. Processing is gated by the button's `disabled` flag
    /// so a stray tap during the post-record export pass can't
    /// re-arm the session.
    private func handleShutterTap() {
        switch session.state {
        case .preview:
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            CaptureAnalytics.manualFired(mode: .video)
            session.startVideoRecording()
        case .recording:
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
            session.stopVideoRecording()
        case .processing, .captured:
            return
        }
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

    /// Use handler. Builds the photo artifacts (PDF + JPEG) from
    /// the captured first frame, runs the controller's scan-
    /// complete pass, and pins the extracted audio as a voice
    /// note against the freshly-created captureId. The voice-
    /// note pane downstream auto-advances when it sees the pre-
    /// attached row, so the user flows straight to the review
    /// screen.
    private func commit() {
        guard case .captured(let image, let audioURL, let durationMs, let videoURL) = session.state else { return }
        guard let result = ImportArtifacts.build(from: [image]) else {
            commitError = "Couldn't save video — try again"
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
        let captureId: String? = {
            if case .recognizing(let id, _, _) = controller.state { return id }
            return nil
        }()
        let pendingAudioURL = audioURL
        let pendingDuration = durationMs
        let pendingVideoURL = videoURL
        let pendingUserId   = userId
        Task {
            if let captureId = captureId {
                let landed = await Self.waitForCaptureRow(id: captureId, timeoutMs: 8_000)
                if landed {
                    if let audioURL = pendingAudioURL,
                       let duration = pendingDuration {
                        await Self.attachVoiceNote(
                            captureId:    captureId,
                            userId:       pendingUserId,
                            audioFileURL: audioURL,
                            durationMs:   duration,
                        )
                    }
                    if let videoURL = pendingVideoURL {
                        do {
                            try await CaptureRepository().setVideoUri(
                                captureId: captureId,
                                videoUri:  videoURL.absoluteString,
                            )
                            NSLog("[VideoCapture] setVideoUri ok captureId=%@ uri=%@",
                                  captureId, videoURL.absoluteString)
                        } catch {
                            NSLog("[VideoCapture] setVideoUri failed: %@", "\(error)")
                        }
                    }
                } else {
                    NSLog("[VideoCapture] waitForCaptureRow timed out for captureId=%@ — " +
                          "video / voice note skipped", captureId)
                }
            } else {
                NSLog("[VideoCapture] no captureId on state after onScanComplete — " +
                      "video / voice note skipped")
            }
            await MainActor.run { onDismiss() }
        }
    }

    /// Poll `captures` until the row with `id` exists OR the
    /// timeout elapses. Used by `commit` to wait out the race
    /// between `onScanComplete` flipping state to `.recognizing`
    /// and the controller's deferred `insertCapture` actually
    /// landing the row. 100ms cadence catches a typical 50-150ms
    /// `insertCapture` window on the first poll; 8s bound costs
    /// at most ~80 cheap reads on the rare slow path.
    private static func waitForCaptureRow(id: String, timeoutMs: Int) async -> Bool {
        let pollMs = 100
        var elapsed = 0
        let repo = CaptureRepository()
        while elapsed < timeoutMs {
            let exists: Bool = (try? await repo.exists(captureId: id)) ?? false
            if exists { return true }
            try? await Task.sleep(nanoseconds: UInt64(pollMs * 1_000_000))
            elapsed += pollMs
        }
        return false
    }

    /// Persist the extracted audio as a voice note attached to
    /// the just-created capture, then kick off the existing
    /// background transcription pass. Mirrors the pre-commit
    /// hand-off in `VoiceNoteCapturePane.commit(uri:durationMs:)`.
    private static func attachVoiceNote(
        captureId: String,
        userId: String,
        audioFileURL: URL,
        durationMs: Int,
    ) async {
        let repo = VoiceNoteRepository()
        let audioSize = (try? FileManager.default.attributesOfItem(atPath: audioFileURL.path)[.size] as? Int) ?? 0
        NSLog("[VideoCapture] attachVoiceNote captureId=%@ audio=%@ size=%dB durationMs=%d",
              captureId, audioFileURL.path, audioSize, durationMs)
        do {
            let row = try await repo.insert(
                captureId:  captureId,
                userId:     userId,
                audioUri:   audioFileURL.absoluteString,
                durationMs: durationMs,
            )
            NSLog("[VideoCapture] attachVoiceNote insert ok rowId=%@", row.id)
            let pendingUserId = userId
            Task.detached(priority: .userInitiated) {
                let granted = await VoiceTranscriber.requestPermission()
                NSLog("[VideoCapture] speech permission granted=%@", granted ? "true" : "false")
                guard granted else { return }
                guard let transcript = await VoiceTranscriber.transcribe(
                    fileURL: audioFileURL,
                    userId:  pendingUserId
                ) else {
                    NSLog("[VideoCapture] VoiceTranscriber returned nil for %@", audioFileURL.path)
                    return
                }
                NSLog("[VideoCapture] transcript ok source=%@ chars=%d",
                      transcript.source, transcript.text.count)
                do {
                    try await VoiceNoteRepository().setTranscription(
                        id:            row.id,
                        transcription: transcript.text,
                        source:        transcript.source,
                    )
                } catch {
                    NSLog("[VideoCapture] setTranscription failed: %@", "\(error)")
                }
            }
        } catch {
            // Soft failure — the capture lands without a voice
            // note. The user can record one manually from the
            // detail screen.
            NSLog("[VideoCapture] attachVoiceNote insert failed: %@", "\(error)")
        }
    }
}

// MARK: - Shutter button

/// 78pt coral disc that toggles to a red recording-stop variant
/// while a video recording is in flight. The button itself has
/// no gesture wiring — the parent's `.onTapGesture` handles the
/// tap-to-start / tap-to-stop toggle so this view just paints
/// state.
private struct VideoShutterButton: View {
    let isRecording: Bool
    var body: some View {
        ZStack {
            Circle()
                .stroke(Color.white.opacity(0.6), lineWidth: 3)
                .frame(width: 78, height: 78)
            Circle()
                .fill(isRecording ? Color.red : QuickInkColors.accent)
                .frame(width: 64, height: 64)
            Image(systemName: isRecording ? "stop.fill" : "video.fill")
                .font(.system(size: 26, weight: .semibold))
                .foregroundStyle(.white)
        }
        .shadow(
            color: (isRecording ? Color.red : QuickInkColors.accent).opacity(0.5),
            radius: 16, x: 0, y: 6,
        )
        .accessibilityLabel(isRecording ? "Stop recording" : "Start recording")
    }
}

// MARK: - Permission rationale

/// Sibling of `PhotoPermissionRationale`. Each surface keeps its
/// own private copy so the per-surface copy strings live next to
/// the surface using them — small duplication, big readability
/// win versus a single shared rationale parameterised on five
/// strings.
private struct VideoPermissionRationale: View {
    let title: String
    /// Named `message` rather than `body` because `body` is
    /// already SwiftUI's required View accessor — naming the
    /// stored property the same shadows it and crashes the
    /// compiler with "invalid redeclaration of 'body'".
    let message: String
    let onRequest: () -> Void

    var body: some View {
        VStack(spacing: QuickInkSpacing.s4) {
            Image(systemName: "video")
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

private struct VideoSessionView: UIViewRepresentable {
    let session: VideoCaptureSession

    func makeUIView(context: Context) -> VideoPreviewUIView {
        let view = VideoPreviewUIView()
        view.previewLayer.videoGravity = .resizeAspectFill
        view.attach(session: session.captureSession)
        return view
    }

    func updateUIView(_ uiView: VideoPreviewUIView, context: Context) {
        // No-op — the session itself owns lifecycle changes.
    }
}

/// Bare `UIView` that hosts an `AVCaptureVideoPreviewLayer`.
/// Set as the layer-class so the preview layer auto-sizes with
/// the view's bounds — saves manual frame plumbing.
private final class VideoPreviewUIView: UIView {
    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }

    var previewLayer: AVCaptureVideoPreviewLayer {
        layer as! AVCaptureVideoPreviewLayer
    }

    func attach(session: AVCaptureSession) {
        previewLayer.session = session
    }
}

// MARK: - Session coordinator

/// AVCaptureSession wrapper for the video surface. Two outputs:
///   - `AVCaptureMovieFileOutput`   — tap-to-start / tap-to-stop
///                                    video recording.
///   - (audio input)                — feeds the movie output's
///                                    audio track for downstream
///                                    transcription.
///
/// No still-photo output — that's `PhotoCaptureSurface`'s job.
///
/// State transitions:
///
///   .preview         ─ live AVCaptureSession running, shutter
///                      armed.
///        │ startVideoRecording()
///        ▼
///   .recording(elapsedSeconds: Int)
///        │ stopVideoRecording() OR max-2:00 cap fires
///        ▼
///   .processing      ─ extracting first frame + audio from the
///                      saved .mov; baking the active filter into
///                      a re-export if one is selected.
///        │ extraction completes
///        ▼
///   .captured(UIImage, audioURL: .some, durationMs: .some)
///        │ discardBuffer() (Retake or .onDisappear)
///        ▼
///   .preview
@MainActor
private final class VideoCaptureSession: NSObject, ObservableObject,
    AVCaptureFileOutputRecordingDelegate
{
    enum State: Equatable {
        case preview
        case recording(elapsedSeconds: Int)
        case processing
        case captured(UIImage, audioURL: URL?, durationMs: Int?, videoURL: URL?)

        static func == (lhs: State, rhs: State) -> Bool {
            switch (lhs, rhs) {
            case (.preview, .preview),
                 (.processing, .processing):
                return true
            case (.recording(let l), .recording(let r)):
                return l == r
            case (.captured, .captured):
                return true
            default:
                return false
            }
        }
    }

    @Published private(set) var state: State = .preview

    let captureSession = AVCaptureSession()
    private let sessionQueue = DispatchQueue(label: "app.quickink.videocapture.session")

    private let movieOutput = AVCaptureMovieFileOutput()
    private var audioInput: AVCaptureDeviceInput?

    /// Currently-bound camera input (front or back). Tracked
    /// separately from `audioInput` so the camera-flip handler can
    /// detach and replace just the video input without disturbing
    /// the mic side of the session.
    private var currentCameraInput: AVCaptureDeviceInput?

    /// Active camera position. Defaults to `.back` since object /
    /// scene capture is the dominant use case; the on-screen flip
    /// button toggles to `.front` and back.
    @Published private(set) var cameraPosition: AVCaptureDevice.Position = .back

    /// Active color filter for the live preview + recorded video.
    /// Defaults to `.none` on every fresh mount — we don't persist
    /// the choice across sessions, since a filter that silently
    /// survived to the next launch could surprise the user.
    @Published var activeFilter: PhotoFilter = .none

    /// File URL the in-flight recording is writing to. Lives in
    /// the app's cache dir; deleted after we extract first frame +
    /// audio in `handleVideo(...)`. Nil outside an in-flight take.
    private var pendingVideoURL: URL? = nil

    /// Wall-clock start of the current recording. Used to tick the
    /// on-screen elapsed-seconds counter via a 1Hz timer; we don't
    /// rely on `AVCaptureMovieFileOutput.recordedDuration` because
    /// that read crosses the session queue.
    private var recordingStartedAt: Date? = nil
    private var recordingTicker: Timer? = nil

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
    /// reference to `VideoCaptureSession` is dropped.
    deinit {
        if captureSession.isRunning {
            captureSession.stopRunning()
        }
    }

    // MARK: Camera flip

    /// Toggle between front and back cameras. No-op outside
    /// `.preview` — flipping mid-recording would tear down the
    /// `AVCaptureMovieFileOutput` connection and corrupt the in-
    /// flight `.mov`. The view hides the flip button outside
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
                NSLog("[VideoCapture] flipCamera: no %@ camera on device",
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
                NSLog("[VideoCapture] flipCamera: canAddInput=false for %@; reverting",
                      newPosition == .front ? "front" : "back")
                if let old = self.currentCameraInput,
                   self.captureSession.canAddInput(old) {
                    self.captureSession.addInput(old)
                }
                self.captureSession.commitConfiguration()
                return
            }
            if let conn = self.movieOutput.connection(with: .video),
               conn.isVideoOrientationSupported {
                conn.videoOrientation = .portrait
            }
            self.captureSession.commitConfiguration()
            Task { @MainActor in self.cameraPosition = newPosition }
        }
    }

    // MARK: Video recording

    func startVideoRecording() {
        guard state == .preview else { return }
        // Microphone permission is lazy: ask the moment the user
        // actually starts a recording. If denied the movie output
        // still records video; the audio track will be empty and
        // the transcription pass downstream will land with no
        // usable signal.
        Task {
            _ = await AVCaptureDevice.requestAccess(for: .audio)
            await MainActor.run { self.beginVideoRecording() }
        }
    }

    private func beginVideoRecording() {
        guard state == .preview else { return }
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("video_capture", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent("buffer-\(Int(Date().timeIntervalSince1970 * 1000)).mov")
        try? FileManager.default.removeItem(at: url)
        pendingVideoURL = url
        recordingStartedAt = Date()
        state = .recording(elapsedSeconds: 0)
        startRecordingTicker()
        sessionQueue.async { [weak self] in
            guard let self = self else { return }
            // Cap at 2:00 even if the host forgets to stop — a 30-
            // minute hold would otherwise OOM the transcript
            // backend downstream.
            self.movieOutput.maxRecordedDuration = CMTime(
                seconds: Double(videoMaxRecordingSeconds),
                preferredTimescale: 600,
            )
            self.movieOutput.startRecording(to: url, recordingDelegate: self)
        }
    }

    func stopVideoRecording() {
        guard case .recording = state else { return }
        stopRecordingTicker()
        // Flip immediately to `.processing` so the shutter UI
        // disables and the user can't double-trigger. The actual
        // stop happens on the session queue; the delegate fires
        // shortly after.
        state = .processing
        sessionQueue.async { [weak self] in
            guard let self = self else { return }
            if self.movieOutput.isRecording {
                self.movieOutput.stopRecording()
            }
        }
    }

    private func startRecordingTicker() {
        recordingTicker?.invalidate()
        recordingTicker = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self = self,
                      let started = self.recordingStartedAt,
                      case .recording = self.state
                else { return }
                let elapsed = max(0, Int(Date().timeIntervalSince(started)))
                self.state = .recording(elapsedSeconds: min(elapsed, videoMaxRecordingSeconds))
            }
        }
    }

    private func stopRecordingTicker() {
        recordingTicker?.invalidate()
        recordingTicker = nil
        recordingStartedAt = nil
    }

    func discardBuffer() {
        if case .captured(_, let audioURL, _, let videoURL) = state {
            // Best-effort cleanup of the AttachmentStorage copies
            // when the user hits Retake. Commit() leaves the files
            // in place (the capture row points at them); this
            // branch only fires when the user explicitly discards.
            if let audioURL = audioURL {
                try? FileManager.default.removeItem(at: audioURL)
            }
            if let videoURL = videoURL {
                try? FileManager.default.removeItem(at: videoURL)
            }
        }
        state = .preview
    }

    // MARK: AVCaptureSession setup

    private var configured = false
    private func configureSessionIfNeeded() {
        guard !configured else { return }
        configured = true

        captureSession.beginConfiguration()
        // `.high` so the movie output coexists cleanly with the
        // audio input — `.photo` preset doesn't promise an audio-
        // compatible configuration on all devices.
        captureSession.sessionPreset = .high

        if let camera = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: cameraPosition),
           let input  = try? AVCaptureDeviceInput(device: camera),
           captureSession.canAddInput(input)
        {
            captureSession.addInput(input)
            self.currentCameraInput = input
        }
        if let mic = AVCaptureDevice.default(for: .audio),
           let input = try? AVCaptureDeviceInput(device: mic),
           captureSession.canAddInput(input)
        {
            captureSession.addInput(input)
            self.audioInput = input
        }

        if captureSession.canAddOutput(movieOutput) {
            captureSession.addOutput(movieOutput)
        }
        if let conn = movieOutput.connection(with: .video),
           conn.isVideoOrientationSupported
        {
            conn.videoOrientation = .portrait
        }

        captureSession.commitConfiguration()
    }

    // MARK: Movie file delegate

    nonisolated func fileOutput(
        _ output: AVCaptureFileOutput,
        didStartRecordingTo fileURL: URL,
        from connections: [AVCaptureConnection],
    ) {
        // No-op — UI flip to `.recording` already happened in
        // `beginVideoRecording`.
    }

    nonisolated func fileOutput(
        _ output: AVCaptureFileOutput,
        didFinishRecordingTo outputFileURL: URL,
        from connections: [AVCaptureConnection],
        error: Error?,
    ) {
        // The max-duration cap surfaces as an
        // `AVErrorMaximumDurationReached` here even though the
        // recording itself completed successfully — treat as a
        // soft error and still process the file.
        let isMaxDurationError: Bool = {
            guard let nsErr = error as? NSError else { return false }
            return nsErr.domain == AVFoundationErrorDomain
                && nsErr.code == AVError.maximumDurationReached.rawValue
        }()
        if let error = error, !isMaxDurationError {
            print("VideoCapture recording failed: \(error)")
            try? FileManager.default.removeItem(at: outputFileURL)
            Task { @MainActor in
                self.pendingVideoURL = nil
                self.state = .preview
            }
            return
        }
        Task { @MainActor in
            await self.handleVideo(outputFileURL: outputFileURL)
        }
    }

    /// Extract the first video frame + audio track from the
    /// recorded .mov, promote the .mov into AttachmentStorage,
    /// then transition to `.captured` with all URLs pinned for
    /// the commit path. The cache copy gets deleted afterwards —
    /// the AttachmentStorage copy is the canonical artifact.
    private func handleVideo(outputFileURL: URL) async {
        defer {
            pendingVideoURL = nil
            try? FileManager.default.removeItem(at: outputFileURL)
        }
        let cacheSize = (try? FileManager.default.attributesOfItem(atPath: outputFileURL.path)[.size] as? Int) ?? 0
        NSLog("[VideoCapture] handleVideo cache=%@ size=%dB", outputFileURL.path, cacheSize)
        let asset = AVURLAsset(url: outputFileURL)
        let rawFirstFrame = await Self.extractFirstFrame(from: asset)
        let durationMs = Int((CMTimeGetSeconds(asset.duration) * 1000.0).rounded())

        // Snapshot the filter so the rest of the pipeline runs
        // against a fixed value — the user could flip filters
        // mid-process and we want the recorded artifact to match
        // what they saw when they released the shutter.
        let filter = self.activeFilter

        let audioURL = await Self.extractAudio(from: asset)
        let rawVideoURL = Self.persistVideo(from: outputFileURL)
        let videoURL: URL? = await {
            guard filter != .none, let raw = rawVideoURL else { return rawVideoURL }
            let filtered = await Self.applyFilterToVideo(rawAttachmentURL: raw, filter: filter)
            return filtered
        }()
        let firstFrame = rawFirstFrame.map { filter.apply(to: $0) }
        NSLog("[VideoCapture] handleVideo persisted firstFrame=%@ audio=%@ video=%@ duration=%dms filter=%@",
              firstFrame == nil ? "nil" : "ok",
              audioURL?.absoluteString ?? "nil",
              videoURL?.absoluteString ?? "nil",
              durationMs,
              filter.displayName)

        guard let image = firstFrame else {
            // No frames at all (very short or malformed recording)
            // → drop everything and return to preview.
            if let audioURL = audioURL { try? FileManager.default.removeItem(at: audioURL) }
            if let videoURL = videoURL { try? FileManager.default.removeItem(at: videoURL) }
            state = .preview
            return
        }
        state = .captured(image, audioURL: audioURL, durationMs: durationMs, videoURL: videoURL)
    }

    /// Re-encode the recorded video with `filter` baked in via
    /// `AVMutableVideoComposition` + `AVAssetExportSession`. The
    /// audio track is copied verbatim. Writes the filtered output
    /// to a temp dir, then moves it into `AttachmentStorage`. The
    /// raw video at `rawAttachmentURL` is deleted on success; on
    /// any failure we fall back to returning the raw URL so the
    /// user still has their clip.
    private static func applyFilterToVideo(rawAttachmentURL: URL, filter: PhotoFilter) async -> URL? {
        guard filter != .none else { return rawAttachmentURL }
        let asset = AVURLAsset(url: rawAttachmentURL)
        let composition = AVMutableVideoComposition(
            asset: asset,
            applyingCIFiltersWithHandler: { request in
                let source = request.sourceImage.clampedToExtent()
                let output = filter.applyToCIImage(source).cropped(to: request.sourceImage.extent)
                request.finish(with: output, context: nil)
            },
        )
        guard let exporter = AVAssetExportSession(
                asset: asset,
                presetName: AVAssetExportPresetHighestQuality,
              )
        else {
            NSLog("[VideoCapture] applyFilterToVideo: no exporter, falling back to raw")
            return rawAttachmentURL
        }
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("video_capture_filtered", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let tempURL = dir.appendingPathComponent("filtered-\(Int(Date().timeIntervalSince1970 * 1000)).mov")
        try? FileManager.default.removeItem(at: tempURL)
        exporter.outputURL = tempURL
        exporter.outputFileType = .mov
        exporter.videoComposition = composition
        await exporter.export()
        guard exporter.status == .completed else {
            NSLog("[VideoCapture] applyFilterToVideo export failed: %@ — falling back to raw",
                  "\(exporter.error?.localizedDescription ?? "unknown")")
            try? FileManager.default.removeItem(at: tempURL)
            return rawAttachmentURL
        }
        guard let data = try? Data(contentsOf: tempURL),
              let stored = AttachmentStorage.write(data, ext: "mov")
        else {
            NSLog("[VideoCapture] applyFilterToVideo: AttachmentStorage write failed — falling back to raw")
            try? FileManager.default.removeItem(at: tempURL)
            return rawAttachmentURL
        }
        try? FileManager.default.removeItem(at: tempURL)
        try? FileManager.default.removeItem(at: rawAttachmentURL)
        NSLog("[VideoCapture] applyFilterToVideo ok: %@ → %@ (filter=%@)",
              rawAttachmentURL.absoluteString, stored.absoluteString, filter.displayName)
        return stored
    }

    /// Copy the raw cache .mov into AttachmentStorage so it
    /// survives a cache eviction + lines up with the same
    /// lifecycle every other capture binary follows. Returns nil
    /// on copy failure.
    private static func persistVideo(from cacheURL: URL) -> URL? {
        guard let data = try? Data(contentsOf: cacheURL) else { return nil }
        return AttachmentStorage.write(data, ext: "mov")
    }

    /// Pull the first frame of the asset as a `UIImage`. Uses
    /// `AVAssetImageGenerator` with `appliesPreferredTrackTransform`
    /// so the captured-preview UI sees a frame oriented the way
    /// the user held the phone.
    private static func extractFirstFrame(from asset: AVURLAsset) async -> UIImage? {
        let generator = AVAssetImageGenerator(asset: asset)
        generator.appliesPreferredTrackTransform = true
        let time = CMTime(seconds: 0, preferredTimescale: 600)
        do {
            let cgImage = try generator.copyCGImage(at: time, actualTime: nil)
            return UIImage(cgImage: cgImage)
        } catch {
            print("VideoCapture first-frame extract failed: \(error)")
            return nil
        }
    }

    /// Strip the audio track into a standalone `.m4a` written
    /// into `AttachmentStorage`. Returns nil when the recording
    /// had no audio track (mic denied, hardware unavailable) —
    /// captured-preview shows up without the "With audio" badge
    /// and the commit path skips the voice-note attach.
    private static func extractAudio(from asset: AVURLAsset) async -> URL? {
        let audioTracks = asset.tracks(withMediaType: .audio)
        guard !audioTracks.isEmpty else { return nil }
        guard let export = AVAssetExportSession(asset: asset, presetName: AVAssetExportPresetAppleM4A) else {
            return nil
        }
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("video_capture_audio", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let outURL = dir.appendingPathComponent("buffer-\(Int(Date().timeIntervalSince1970 * 1000)).m4a")
        try? FileManager.default.removeItem(at: outURL)
        export.outputURL = outURL
        export.outputFileType = .m4a
        await export.export()
        if export.status != .completed {
            print("VideoCapture audio extract failed: \(String(describing: export.error))")
            return nil
        }
        guard let data = try? Data(contentsOf: outURL),
              let stored = AttachmentStorage.write(data, ext: "m4a") else {
            try? FileManager.default.removeItem(at: outURL)
            return nil
        }
        try? FileManager.default.removeItem(at: outURL)
        return stored
    }
}
