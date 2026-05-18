/*
 * PhotoCaptureSurface.swift
 *
 * Third capture surface — a single-shot still camera that ALSO
 * doubles as a hold-to-record video recorder. Sibling to
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
 *      the coordinator to flip `mode = .photo`.
 *
 * Shutter gesture (per Instagram / WhatsApp camera convention):
 *
 *   - Quick tap        → still photo capture
 *                        (`AVCapturePhotoOutput.capturePhoto`).
 *   - Press-and-hold   → video recording starts after a 0.3s
 *                        threshold (so a normal tap doesn't
 *                        accidentally start a 1-frame video).
 *                        Release stops the recording; a hard 2:00
 *                        cap is enforced by
 *                        `AVCaptureMovieFileOutput.maxRecordedDuration`
 *                        so the user can hold forever without
 *                        blowing up a transcription pass downstream.
 *
 * A small flip-camera button sits at the trailing edge of the
 * shutter row, next to the shutter itself (matches the iOS
 * Camera / Instagram convention). It is only shown in the idle
 * `.preview` state — hidden while a still capture, video
 * recording, or post-record processing pass is in flight, since
 * tearing down the video input mid-take would corrupt the
 * in-flight `.mov` and race the photo/movie delegate callbacks.
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
 * The selection drives the main live preview (via cheap
 * SwiftUI `.saturation` / `.contrast` / `.colorMultiply`
 * modifiers — no per-frame CIFilter pass) AND every saved
 * artifact: the captured still (via `CIFilter` applied to the
 * `UIImage`) and the recorded video (via
 * `AVMutableVideoComposition.applyingCIFiltersWithHandler` +
 * `AVAssetExportSession`, run as a post-record pass that
 * replaces the raw `.mov` in `AttachmentStorage` with a
 * filtered re-encode). The chip strip is hidden while
 * recording so the user can't change the filter mid-take —
 * the post-process snapshot is taken once when the recording
 * ends. See `PhotoFilter` for the per-case modifier / CIFilter
 * mapping.
 *
 * After capture (still OR video), the surface lands on the same
 * captured-preview UI: a frozen first frame (or the still) +
 * Retake / Use Photo. Use Photo writes the JPEG via
 * `ImportArtifacts.build(from: [image])`, fires
 * `controller.onScanComplete(source: "photo", paperSize: .custom)`,
 * and — when the capture was a video — inserts the extracted
 * audio track as a voice note against the freshly-created
 * captureId. The voice-note capture pane downstream sees the
 * pre-attached note and auto-advances to the review screen so
 * the user doesn't get prompted to record a voice note for a
 * clip we already extracted from their own audio.
 *
 * The raw .mov video file is NOT persisted to AttachmentStorage —
 * only the first-frame JPEG (as the page) and the .m4a (as the
 * voice note) survive. Spec §6 calls out `paperSize=.custom` for
 * photo mode; video clips inherit the same since the first frame
 * is still an arbitrary phone-camera frame whose aspect ratio
 * tells us nothing about A4 / Letter / card bands.
 *
 * Mirror of Android `PhotoCaptureSurface.kt`.
 */

import SwiftUI
import AVFoundation
import UIKit
import ReleafCoreData
import ReleafCoreScan

/// One of six color filters the user can apply from the vertical
/// chip strip on the bottom-left of the live preview. Selection
/// lives in `PhotoCaptureSession.activeFilter` and is mirrored to:
///
///   - **Live preview** — via SwiftUI's `.saturation` / `.contrast`
///     / `.colorMultiply` modifiers on `PhotoSessionView`. These
///     are cheap GPU operations applied at the render layer, so
///     there's no per-frame CPU cost (we don't run `CIFilter` on
///     every preview frame).
///   - **Captured still** — via `CIFilter` applied to the saved
///     `UIImage` before `ImportArtifacts.build` writes the JPEG.
///     Precise color science here; the live-preview modifiers
///     are approximations of the same outcome.
///
/// Video recording captures **raw** (no filter applied to the
/// `.mov` output). The strip is hidden while recording and the
/// live preview drops back to identity, so the user's mental
/// model is "what I see is what gets saved." A filtered-video
/// pipeline (CIFilter via `AVMutableVideoComposition` +
/// `AVAssetExportSession`) is a separate, heavier follow-up.
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

/// Discoverability state for the Photo-capture long-press shortcut
/// on the bottom-nav ⚡ FAB. Owns the single persisted `dismissed`
/// flag that gates the "Hold ⚡ for a quick photo" chip above the
/// FAB.
///
/// Why this is an `ObservableObject` rather than a plain enum
/// of statics on top of `@AppStorage`: in practice `@AppStorage`
/// doesn't always re-render SwiftUI views when the underlying
/// `UserDefaults` key is written through a direct
/// `UserDefaults.standard.set(...)` call (only through the
/// `@AppStorage` setter). The chip stays stuck on its previous
/// value until something else nudges the view tree. Routing the
/// write through `@Published dismissed` here guarantees the
/// publish fires the instant `markDismissed()` is called, so the
/// FAB chip fades out exactly when the user long-presses.
///
/// Spec §3.1 originally gated the chip on a first-scan flag too
/// ("show only after the user has scanned at least once"). We
/// dropped that gate so existing users upgrading into this build
/// see the chip immediately, without needing one more scan to
/// flip a freshly-introduced UserDefaults bool. The chip is small
/// and one long-press dismisses it forever, so the noise cost is
/// low and the discoverability win is universal.
@MainActor
public final class PhotoFabHint: ObservableObject {

    /// Persisted across launches under this UserDefaults key. A
    /// single bool is all the hint state we keep — once the user
    /// long-presses the FAB they've discovered the gesture and
    /// shouldn't see the chip again.
    private static let dismissedKey = "quickink.capture.photo_fab_hint_dismissed"

    @Published public private(set) var dismissed: Bool

    public init() {
        self.dismissed = UserDefaults.standard.bool(forKey: Self.dismissedKey)
    }

    /// Flip the dismissed flag if it wasn't already set. Writes
    /// to UserDefaults AND publishes through `@Published` so
    /// `@StateObject` / `@ObservedObject` consumers see the
    /// change synchronously on the current run loop tick.
    public func markDismissed() {
        guard !dismissed else { return }
        UserDefaults.standard.set(true, forKey: Self.dismissedKey)
        dismissed = true
    }
}

/// Hard cap on a single hold-to-record video clip. Past this the
/// recording auto-stops via
/// `AVCaptureMovieFileOutput.maxRecordedDuration` and lands on
/// the captured-preview state. Two minutes is long enough for a
/// realistic dictation-while-capturing scenario and short enough
/// that the m4a transcription pass downstream stays bounded.
private let maxVideoRecordingSeconds: Int = 120

/// Threshold after touch-down before we commit to recording a
/// video. Releases shorter than this fire the still capture path
/// instead. 0.3s matches the FAB long-press feel and gives the
/// user a clear "tap vs hold" affordance.
private let videoHoldThresholdMs: Int = 300

struct PhotoCaptureSurface: View {

    let controller: ScanFlowController
    /// Owning user — required so the post-video voice-note insert
    /// can populate `voice_notes.user_id`. Threaded down from
    /// `QuickInkRoot`'s `MainShell` → `QuickCaptureScreen` →
    /// here. Kept as a stored property rather than an environment
    /// value because the host already passes it explicitly to
    /// every other capture surface for symmetry.
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
                    message:   "Photo mode uses your camera to capture a still image or a quick video. You can still scan documents and import from your library without it.",
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
                    message:   "Photo mode uses your camera to capture a still image or a quick video.",
                    onRequest: requestPermission,
                )
            }
        }
        .task {
            // Auto-prompt on first mount when permission status
            // is still .notDetermined. Subsequent mounts skip
            // the request because the OS persists the decision.
            // Microphone permission is requested lazily at the
            // moment the user starts a video recording — keeps
            // the permission ask tightly coupled to the action.
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

    /// Touch-down timestamp for the shutter button. Used by the
    /// DragGesture-based shutter to discriminate quick taps (fire
    /// the still capture) from holds (fire the video recording).
    /// Nil when the user isn't currently touching the shutter.
    @State private var pressStart: Date? = nil

    /// Outstanding timer that promotes a press into a recording
    /// at the `videoHoldThresholdMs` mark. Cancelled if the user
    /// releases early (→ still photo) or once recording starts.
    @State private var pendingRecordingStart: DispatchWorkItem? = nil

    /// True when the current touch sequence is the one that
    /// promoted into a video recording (the user held past
    /// `videoHoldThresholdMs`). Lets `onEnded` distinguish
    /// "released after starting a recording — keep recording,
    /// the next tap will stop" from "released during recording
    /// from a separate tap — stop now." Reset on every touch-
    /// down.
    @State private var startedRecordingThisPress: Bool = false

    /// Filter strip expand/collapse state. Default `false` →
    /// only the active filter chip is visible (anchored left of
    /// the shutter button). Tap that chip → expands upward into
    /// the full 6-chip column. Tap any chip while expanded →
    /// selects + collapses. Resets to false on every fresh
    /// mount and any time the user picks a chip — the strip is
    /// never "stuck open."
    @State private var isFilterStripExpanded: Bool = false

    var body: some View {
        VStack(spacing: 0) {
            ZStack {
                switch session.state {
                case .preview, .recording, .capturing, .processing:
                    PhotoSessionView(session: session)
                        .ignoresSafeArea(edges: .horizontal)
                        // Live-preview color filter. Identity values
                        // (1.0 / 1.0 / .white) are no-ops, so when
                        // `effectiveLiveFilter == .none` SwiftUI
                        // optimises the modifier chain into a pass-
                        // through. Stays on through `.recording`
                        // too — `applyFilterToVideo` bakes the
                        // same filter into the saved `.mov`, so
                        // preview ≈ saved output.
                        .saturation(effectiveLiveFilter.liveSaturation)
                        .contrast(effectiveLiveFilter.liveContrast)
                        .colorMultiply(effectiveLiveFilter.liveTint)
                    if case .recording(let elapsed) = session.state {
                        recordingOverlay(elapsedSeconds: elapsed)
                    }
                    if session.state == .capturing || session.state == .processing {
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

            // Bottom action row swaps based on state. Live preview
            // shows the shutter; captured shows Retake / Use Photo.
            switch session.state {
            case .preview, .capturing, .processing, .recording:
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
            cancelPendingRecordingStart()
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

    // MARK: - Live filter

    /// The filter SwiftUI should apply to the live preview right
    /// now. Always the active filter — the recorded `.mov` is
    /// re-exported through `PhotoCaptureSession.applyFilterToVideo`
    /// after the take ends, so the preview can keep showing the
    /// filter throughout recording without misleading the user
    /// about what's saved. The filter strip is still hidden mid-
    /// recording (changing filter mid-take would mismatch the
    /// post-process snapshot) — only the live filter on the raw
    /// camera feed stays on.
    private var effectiveLiveFilter: PhotoFilter {
        session.activeFilter
    }

    /// Collapse-aware filter strip. Default `isFilterStripExpanded
    /// == false` renders only the active filter chip; tapping
    /// expands the column upward with the other 5 chips above
    /// it. The active chip is always the last child in the
    /// VStack so its on-screen position is fixed (it stays put
    /// at the bottom-leading anchor while siblings appear/
    /// disappear above). Tap any chip while expanded → select
    /// it and collapse.
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
    /// and each is GPU-rendered, so the cost of 6 live thumbnails
    /// (only while expanded) is negligible.
    @ViewBuilder
    private func filterChip(_ filter: PhotoFilter) -> some View {
        let isActive = filter == session.activeFilter
        Button(action: {
            if isFilterStripExpanded {
                // Expanded → tap any chip selects it and collapses.
                // Tapping the active chip while expanded also
                // collapses (natural "I'm done" gesture).
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
                Capsule().fill(
                    isActive ? QuickInkColors.accent.opacity(0.85) : Color.black.opacity(0.55),
                ),
            )
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(filter.displayName) filter")
        .accessibilityAddTraits(isActive ? [.isSelected] : [])
    }

    // MARK: - Camera flip button

    /// Bare 40pt black disc that toggles between the back and
    /// front cameras. Positioned by `shutterRow` at the trailing
    /// edge of the shutter row so it reads as a peer affordance
    /// to the shutter button (matches Instagram / iOS Camera).
    /// Only rendered while `session.state == .preview` —
    /// `flipCamera()` no-ops outside `.preview` defensively too,
    /// but hiding the button keeps the UI honest.
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
            // Small video badge so the user can tell a tap-still
            // from a hold-video at a glance on the Retake/Use Photo
            // screen (the bottom row doesn't disambiguate).
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
            // ZStack (not the old centered HStack) so the flip
            // button can pin to the trailing edge without pushing
            // the shutter off-center. The shutter stays at the
            // ZStack's natural center; the flip button takes a
            // `maxWidth: .infinity` frame aligned trailing, which
            // expands the ZStack to the row's full width without
            // moving the shutter.
            ZStack {
                PhotoShutterButton(
                    isRecording: {
                        if case .recording = session.state { return true }
                        return false
                    }(),
                )
                .gesture(shutterGesture)
                .disabled(session.state == .capturing || session.state == .processing)
                .opacity((session.state == .capturing || session.state == .processing) ? 0.5 : 1.0)
                if session.state == .preview {
                    cameraFlipButton
                        .frame(maxWidth: .infinity, alignment: .trailing)
                }
            }
            .frame(maxWidth: .infinity)
            // Filter strip overlay sits at the bottom-leading of
            // the shutter ZStack — mirror of `cameraFlipButton`'s
            // trailing position. Anchored at the bottom so the
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

    /// Subtle copy under the shutter in the live state. Surfaces
    /// the dual gesture so a first-time user doesn't have to
    /// discover the hold-to-record behaviour through trial and
    /// error. Two variants:
    ///   - Preview   → "Tap for photo · hold for video"
    ///   - Recording → "Tap to stop" (the shutter swaps to a red
    ///                  stop icon already; the copy makes the
    ///                  "no need to keep holding" affordance
    ///                  unmistakable).
    @ViewBuilder
    private var shutterHint: some View {
        if case .recording = session.state {
            Text("Tap to stop")
                .font(.system(size: 11, weight: .medium))
                .foregroundStyle(Color.white.opacity(0.55))
        } else {
            Text("Tap for photo · hold for video")
                .font(.system(size: 11, weight: .medium))
                .foregroundStyle(Color.white.opacity(0.55))
        }
    }

    // MARK: - Shutter gesture (tap vs long-press)

    /// Two-mode press detector:
    ///
    ///   - Recording   → any release stops the take (the user
    ///                   has already moved past the start
    ///                   gesture; their next tap is "stop").
    ///   - Preview     → schedule a 300ms timer. Released early
    ///                   → still photo. Timer elapsed → start
    ///                   video recording. Crucially, release
    ///                   AFTER the timer does NOT stop the
    ///                   recording — the take continues
    ///                   hands-free until the user taps the
    ///                   shutter again (which falls through to
    ///                   the Recording branch above and stops
    ///                   the take). Avoids the awkward
    ///                   "hold-the-whole-time" model that made
    ///                   ≥10s clips painful.
    ///
    /// `DragGesture(minimumDistance: 0)` is the SwiftUI idiom
    /// for "press detector" — we ignore the drag itself, just
    /// listen for touch-down (`onChanged` first fire) and
    /// touch-up (`onEnded`).
    private var shutterGesture: some Gesture {
        DragGesture(minimumDistance: 0, coordinateSpace: .local)
            .onChanged { _ in
                if pressStart != nil { return }
                pressStart = Date()
                startedRecordingThisPress = false
                // If we're already mid-recording, this press is
                // the user's "stop" tap — don't schedule a fresh
                // start timer, just let onEnded handle the stop.
                if case .recording = session.state { return }
                scheduleRecordingStart()
            }
            .onEnded { _ in
                defer { pressStart = nil }
                cancelPendingRecordingStart()
                if case .recording = session.state {
                    if startedRecordingThisPress {
                        // The release that initially kicked off
                        // the recording — keep recording. The
                        // next tap will land in the recording
                        // branch above without
                        // `startedRecordingThisPress` set and
                        // will stop the take.
                        startedRecordingThisPress = false
                        return
                    }
                    session.stopVideoRecording()
                } else if session.state == .preview {
                    // Released before the 0.3s threshold without
                    // the recording having kicked in → still
                    // photo.
                    UIImpactFeedbackGenerator(style: .light).impactOccurred()
                    CaptureAnalytics.manualFired(mode: .photo)
                    session.triggerCapture()
                }
            }
    }

    private func scheduleRecordingStart() {
        cancelPendingRecordingStart()
        let work = DispatchWorkItem {
            // If the user is still pressing AND the session is
            // still in preview (i.e. they haven't already
            // triggered something), promote the press into a
            // recording.
            guard pressStart != nil, session.state == .preview else { return }
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            CaptureAnalytics.manualFired(mode: .photo)
            startedRecordingThisPress = true
            session.startVideoRecording()
        }
        pendingRecordingStart = work
        DispatchQueue.main.asyncAfter(
            deadline: .now() + .milliseconds(videoHoldThresholdMs),
            execute: work,
        )
    }

    private func cancelPendingRecordingStart() {
        pendingRecordingStart?.cancel()
        pendingRecordingStart = nil
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
    /// from the captured frame, runs the controller's scan-
    /// complete pass, and — when the capture was a video — pins
    /// the extracted audio as a voice note against the freshly
    /// created captureId. The voice-note pane downstream auto-
    /// advances when it sees the pre-attached row, so the user
    /// flows straight to the review screen.
    private func commit() {
        guard case .captured(let image, let audioURL, let durationMs, let videoURL) = session.state else { return }
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
        // `onScanComplete` on iOS flips state to `.recognizing`
        // SYNCHRONOUSLY but defers the actual
        // `insertCapture(...)` write behind an
        // `await captureLocationIfEnabled()` inside the
        // controller's activeTask, which can suspend for 0-2s
        // while the GPS fix lands. If we ran `setVideoUri` /
        // the voice-note INSERT against the captureId
        // immediately, the UPDATE would target a non-existent
        // row (0 rows affected → silently lost) and the voice
        // note's FK to captures would fail with
        // `SQLITE_CONSTRAINT`. Poll for the row to exist
        // (capped at 8s) before doing the dependent writes.
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
                            NSLog("[PhotoCapture] setVideoUri ok captureId=%@ uri=%@",
                                  captureId, videoURL.absoluteString)
                        } catch {
                            NSLog("[PhotoCapture] setVideoUri failed: %@", "\(error)")
                        }
                    }
                } else {
                    NSLog("[PhotoCapture] waitForCaptureRow timed out for captureId=%@ — " +
                          "video / voice note skipped", captureId)
                }
            } else {
                NSLog("[PhotoCapture] no captureId on state after onScanComplete — " +
                      "video / voice note skipped")
            }
            await MainActor.run { onDismiss() }
        }
    }

    /// Poll `captures` until the row with `id` exists OR the
    /// timeout elapses. Used by `commit` to wait out the race
    /// between `onScanComplete` flipping state to `.recognizing`
    /// and the controller's deferred `insertCapture` actually
    /// landing the row. 100ms cadence is fast enough that a
    /// typical 50-150ms `insertCapture` window is caught on the
    /// first poll, and bounded enough that an 8s timeout costs
    /// at most ~80 cheap reads.
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
        NSLog("[PhotoCapture] attachVoiceNote captureId=%@ audio=%@ size=%dB durationMs=%d",
              captureId, audioFileURL.path, audioSize, durationMs)
        do {
            let row = try await repo.insert(
                captureId:  captureId,
                userId:     userId,
                audioUri:   audioFileURL.absoluteString,
                durationMs: durationMs,
            )
            NSLog("[PhotoCapture] attachVoiceNote insert ok rowId=%@", row.id)
            let pendingUserId = userId
            Task.detached(priority: .userInitiated) {
                let granted = await VoiceTranscriber.requestPermission()
                NSLog("[PhotoCapture] speech permission granted=%@", granted ? "true" : "false")
                guard granted else { return }
                guard let transcript = await VoiceTranscriber.transcribe(
                    fileURL: audioFileURL,
                    userId:  pendingUserId
                ) else {
                    NSLog("[PhotoCapture] VoiceTranscriber returned nil for %@", audioFileURL.path)
                    return
                }
                NSLog("[PhotoCapture] transcript ok source=%@ chars=%d",
                      transcript.source, transcript.text.count)
                do {
                    try await VoiceNoteRepository().setTranscription(
                        id:            row.id,
                        transcription: transcript.text,
                        source:        transcript.source,
                    )
                } catch {
                    NSLog("[PhotoCapture] setTranscription failed: %@", "\(error)")
                }
            }
        } catch {
            // Soft failure — the capture lands without a voice
            // note. The user can record one manually from the
            // detail screen.
            NSLog("[PhotoCapture] attachVoiceNote insert failed: %@", "\(error)")
        }
    }
}

// MARK: - Shutter button

/// 78pt coral disc, swapped to a red recording-stop variant
/// while a video recording is in flight. The button itself has
/// no gesture wiring — the parent's `DragGesture` handles tap
/// vs hold so the shutter button just paints state.
private struct PhotoShutterButton: View {
    let isRecording: Bool
    var body: some View {
        ZStack {
            Circle()
                .stroke(Color.white.opacity(0.6), lineWidth: 3)
                .frame(width: 78, height: 78)
            Circle()
                .fill(isRecording ? Color.red : QuickInkColors.accent)
                .frame(width: 64, height: 64)
            Image(systemName: isRecording ? "stop.fill" : "camera.fill")
                .font(.system(size: 26, weight: .semibold))
                .foregroundStyle(.white)
        }
        .shadow(
            color: (isRecording ? Color.red : QuickInkColors.accent).opacity(0.5),
            radius: 16, x: 0, y: 6,
        )
        .accessibilityLabel(isRecording ? "Stop recording" : "Take photo or hold to record")
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

/// AVCaptureSession wrapper for the photo surface. Has THREE
/// outputs:
///   - `AVCapturePhotoOutput`       — still capture on tap.
///   - `AVCaptureMovieFileOutput`   — hold-to-record video.
///   - (audio input)                — feeds the movie output's
///                                    audio track for downstream
///                                    transcription.
///
/// State transitions:
///
///   .preview         ─ live AVCaptureSession running, shutter
///                      armed (both still + video paths).
///        │ triggerCapture()
///        ▼
///   .capturing       ─ still photo output in flight; preview
///                      frozen behind a dim scrim.
///        │ photoOutput delegate fires
///        ▼
///   .captured(UIImage, audioURL: nil, durationMs: nil)
///        │ discardBuffer() (Retake or .onDisappear)
///        ▼
///   .preview
///
///   .preview
///        │ startVideoRecording()
///        ▼
///   .recording(elapsedSeconds: Int)
///        │ stopVideoRecording() OR max-2:00 cap fires
///        ▼
///   .processing      ─ extracting first frame + audio from the
///                      saved .mov so the captured-preview UI
///                      has a stable frozen-still and a separate
///                      .m4a path to hand the voice-note pipeline.
///        │ extraction completes
///        ▼
///   .captured(UIImage, audioURL: .some, durationMs: .some)
@MainActor
private final class PhotoCaptureSession: NSObject, ObservableObject,
    AVCapturePhotoCaptureDelegate,
    AVCaptureFileOutputRecordingDelegate
{
    enum State: Equatable {
        case preview
        case recording(elapsedSeconds: Int)
        case capturing
        case processing
        case captured(UIImage, audioURL: URL?, durationMs: Int?, videoURL: URL?)

        /// Equatable conformance — UIImage isn't Equatable by
        /// reference identity, so compare by case only (with
        /// nested-value match for recording so the elapsed-
        /// seconds tick re-publishes a distinct value).
        static func == (lhs: State, rhs: State) -> Bool {
            switch (lhs, rhs) {
            case (.preview, .preview),
                 (.capturing, .capturing),
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
    private let sessionQueue = DispatchQueue(label: "app.quickink.photocapture.session")

    private let photoOutput = AVCapturePhotoOutput()
    private let movieOutput = AVCaptureMovieFileOutput()
    private var audioInput: AVCaptureDeviceInput?

    /// Currently-bound camera input (front or back). Tracked
    /// separately from `audioInput` so the camera-flip handler can
    /// detach and replace just the video input without disturbing
    /// the mic side of the session.
    private var currentCameraInput: AVCaptureDeviceInput?

    /// Active camera position. Defaults to `.back` since document /
    /// object capture is the dominant use case; the on-screen flip
    /// button toggles to `.front` and back. `@Published` so SwiftUI
    /// consumers can react (e.g. swap the flip-icon style) — for
    /// the current spec the view just hides the flip button outside
    /// `.preview`, but exposing the position keeps room for that.
    @Published private(set) var cameraPosition: AVCaptureDevice.Position = .back

    /// Active color filter for the live preview + still capture.
    /// Defaults to `.none` on every fresh mount — we don't
    /// persist the choice across sessions, since a filter that
    /// silently survived to the next launch could surprise the
    /// user. Video recording ignores this value and always
    /// records raw (see `PhotoFilter` docblock).
    @Published var activeFilter: PhotoFilter = .none

    /// File URL the in-flight video recording is writing to.
    /// Lives in the app's cache dir; deleted after we extract
    /// the first frame + audio in `handleVideo(...)`. Set to nil
    /// once recording starts → completes → finishes processing.
    private var pendingVideoURL: URL? = nil

    /// Wall-clock start of the current recording. Used to tick
    /// the on-screen elapsed-seconds counter via a 1Hz timer; we
    /// don't rely on `AVCaptureMovieFileOutput.recordedDuration`
    /// because that read crosses the session queue.
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

    // MARK: Camera flip

    /// Toggle between front and back cameras. No-op outside
    /// `.preview` — flipping mid-recording would tear down the
    /// `AVCaptureMovieFileOutput` connection and corrupt the in-
    /// flight `.mov`; flipping during capturing/processing would
    /// race the photo/movie delegate callbacks. The view hides
    /// the flip button outside `.preview` too, but we guard here
    /// defensively in case a stray gesture sneaks through.
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
                // Couldn't add the new input — try to reinstate the
                // old one so we don't strand the user on a black
                // preview.
                NSLog("[PhotoCapture] flipCamera: canAddInput=false for %@; reverting",
                      newPosition == .front ? "front" : "back")
                if let old = self.currentCameraInput,
                   self.captureSession.canAddInput(old) {
                    self.captureSession.addInput(old)
                }
                self.captureSession.commitConfiguration()
                return
            }
            // The input swap recreates both output connections from
            // scratch, so portrait orientation has to be re-applied
            // (defaults to landscape-right on a fresh connection).
            if let conn = self.photoOutput.connection(with: .video),
               conn.isVideoOrientationSupported {
                conn.videoOrientation = .portrait
            }
            if let conn = self.movieOutput.connection(with: .video),
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

    // MARK: Video recording

    func startVideoRecording() {
        guard state == .preview else { return }
        // Microphone permission is lazy: ask the moment the
        // user actually starts a recording. If denied the
        // movie output still records video; the audio track
        // will be empty and the transcription pass will land
        // with no usable signal.
        Task {
            _ = await AVCaptureDevice.requestAccess(for: .audio)
            await MainActor.run { self.beginVideoRecording() }
        }
    }

    private func beginVideoRecording() {
        guard state == .preview else { return }
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("photo_capture_video", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent("buffer-\(Int(Date().timeIntervalSince1970 * 1000)).mov")
        try? FileManager.default.removeItem(at: url)
        pendingVideoURL = url
        recordingStartedAt = Date()
        state = .recording(elapsedSeconds: 0)
        startRecordingTicker()
        sessionQueue.async { [weak self] in
            guard let self = self else { return }
            // Cap at 2:00 even if the host forgets to stop —
            // a 30-minute hold would otherwise OOM the transcript
            // backend downstream.
            self.movieOutput.maxRecordedDuration = CMTime(
                seconds: Double(maxVideoRecordingSeconds),
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
                self.state = .recording(elapsedSeconds: min(elapsed, maxVideoRecordingSeconds))
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
            // when the user hits Retake. Commit() leaves the
            // files in place (the capture row points at them);
            // this branch only fires when the user explicitly
            // discards the capture, so blowing the files away
            // is safe.
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
        // `.high` instead of `.photo` because adding a
        // `AVCaptureMovieFileOutput` to a `.photo` preset
        // session is a runtime error on some devices — the
        // photo preset doesn't promise an audio-compatible
        // configuration. `.high` lets both photo + movie
        // outputs coexist while still producing a quality
        // still on the photo path.
        captureSession.sessionPreset = .high

        if let camera = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: cameraPosition),
           let input  = try? AVCaptureDeviceInput(device: camera),
           captureSession.canAddInput(input)
        {
            captureSession.addInput(input)
            // Pin the video input so `flipCamera()` can detach it
            // later without having to re-enumerate session inputs.
            self.currentCameraInput = input
        }
        if let mic = AVCaptureDevice.default(for: .audio),
           let input = try? AVCaptureDeviceInput(device: mic),
           captureSession.canAddInput(input)
        {
            captureSession.addInput(input)
            self.audioInput = input
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

    // MARK: Photo delegate (still)

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
            // Apply the active filter on MainActor so the read of
            // `activeFilter` doesn't cross actor boundaries from
            // the `nonisolated` delegate. CIFilter on a single
            // still is fast (~10-50ms) so the brief MainActor
            // hop is fine; we're already in a Task so the UI
            // doesn't jank.
            let filtered = self.activeFilter.apply(to: image)
            self.state = .captured(filtered, audioURL: nil, durationMs: nil, videoURL: nil)
        }
    }

    // MARK: Movie file delegate (video)

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
            print("PhotoCapture video recording failed: \(error)")
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
    /// recorded .mov, promote the .mov itself into
    /// AttachmentStorage so the detail screen can play it back
    /// later, then transition to `.captured` with both URLs
    /// pinned for the commit path. The cache copy of the .mov
    /// gets deleted afterwards — the AttachmentStorage copy is
    /// the canonical artifact from here on.
    private func handleVideo(outputFileURL: URL) async {
        defer {
            pendingVideoURL = nil
            try? FileManager.default.removeItem(at: outputFileURL)
        }
        let cacheSize = (try? FileManager.default.attributesOfItem(atPath: outputFileURL.path)[.size] as? Int) ?? 0
        NSLog("[PhotoCapture] handleVideo cache=%@ size=%dB", outputFileURL.path, cacheSize)
        let asset = AVURLAsset(url: outputFileURL)
        let rawFirstFrame = await Self.extractFirstFrame(from: asset)
        let durationMs = Int((CMTimeGetSeconds(asset.duration) * 1000.0).rounded())

        // Snapshot the filter so the rest of the pipeline runs
        // against a fixed value — the user could flip filters
        // mid-process and we want the recorded artifact to match
        // what they saw when they released the shutter.
        let filter = self.activeFilter

        // Extract audio to a stable AttachmentStorage URL —
        // VoiceNoteRepository expects a file:// path it can
        // serve up to the transcriber and sync workers later.
        // (Filter doesn't touch the audio track; we extract from
        // the raw .mov before any video transcode.)
        let audioURL = await Self.extractAudio(from: asset)
        // Persist the raw .mov into AttachmentStorage. If a
        // filter is active we'll re-export this through
        // `applyFilterToVideo` and replace it in-storage; doing
        // it in two steps (persist-then-filter) keeps the
        // fallback simple — if the filter pass fails for any
        // reason the user still has the raw recording.
        let rawVideoURL = Self.persistVideo(from: outputFileURL)
        let videoURL: URL? = await {
            guard filter != .none, let raw = rawVideoURL else { return rawVideoURL }
            let filtered = await Self.applyFilterToVideo(rawAttachmentURL: raw, filter: filter)
            return filtered
        }()
        // Apply the filter to the first frame too so the
        // captured-preview the user sees on the Retake / OK
        // screen matches what the recorded video plays back.
        let firstFrame = rawFirstFrame.map { filter.apply(to: $0) }
        NSLog("[PhotoCapture] handleVideo persisted firstFrame=%@ audio=%@ video=%@ duration=%dms filter=%@",
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
    /// to a temp dir, then moves it into `AttachmentStorage` so
    /// the saved artifact lives where every other capture binary
    /// does (Drive sync, thumbnails, etc.). The raw video at
    /// `rawAttachmentURL` is deleted on success; on any failure
    /// we fall back to returning the raw URL so the user still
    /// has their clip.
    private static func applyFilterToVideo(rawAttachmentURL: URL, filter: PhotoFilter) async -> URL? {
        guard filter != .none else { return rawAttachmentURL }
        let asset = AVURLAsset(url: rawAttachmentURL)
        let composition = AVMutableVideoComposition(
            asset: asset,
            applyingCIFiltersWithHandler: { request in
                // `clampedToExtent()` keeps the filter happy on
                // shaders that sample outside the source bounds
                // (e.g. CIColorControls) — we crop back to the
                // original extent after so the export keeps the
                // same dimensions as the input.
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
            NSLog("[PhotoCapture] applyFilterToVideo: no exporter, falling back to raw")
            return rawAttachmentURL
        }
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("photo_capture_filtered", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let tempURL = dir.appendingPathComponent("filtered-\(Int(Date().timeIntervalSince1970 * 1000)).mov")
        try? FileManager.default.removeItem(at: tempURL)
        exporter.outputURL = tempURL
        exporter.outputFileType = .mov
        exporter.videoComposition = composition
        await exporter.export()
        guard exporter.status == .completed else {
            NSLog("[PhotoCapture] applyFilterToVideo export failed: %@ — falling back to raw",
                  "\(exporter.error?.localizedDescription ?? "unknown")")
            try? FileManager.default.removeItem(at: tempURL)
            return rawAttachmentURL
        }
        // Move into AttachmentStorage and clean up.
        guard let data = try? Data(contentsOf: tempURL),
              let stored = AttachmentStorage.write(data, ext: "mov")
        else {
            NSLog("[PhotoCapture] applyFilterToVideo: AttachmentStorage write failed — falling back to raw")
            try? FileManager.default.removeItem(at: tempURL)
            return rawAttachmentURL
        }
        try? FileManager.default.removeItem(at: tempURL)
        try? FileManager.default.removeItem(at: rawAttachmentURL)
        NSLog("[PhotoCapture] applyFilterToVideo ok: %@ → %@ (filter=%@)",
              rawAttachmentURL.absoluteString, stored.absoluteString, filter.displayName)
        return stored
    }

    /// Copy the raw cache .mov into AttachmentStorage so it
    /// survives a cache eviction + lines up with the same
    /// lifecycle every other capture binary follows (Drive
    /// sync, thumbnails, etc.). Returns nil on copy failure —
    /// the captured-preview still loads, but the detail screen
    /// will treat the capture as "no video" on the rare error
    /// path.
    private static func persistVideo(from cacheURL: URL) -> URL? {
        guard let data = try? Data(contentsOf: cacheURL) else { return nil }
        // `.mov` is what AVCaptureMovieFileOutput hands us;
        // AttachmentStorage stores it verbatim so AVPlayer plays
        // back without re-encoding.
        return AttachmentStorage.write(data, ext: "mov")
    }

    /// Pull the first frame of the asset as a `UIImage`. Uses
    /// `AVAssetImageGenerator` with `appliesPreferredTrackTransform`
    /// so the captured-preview UI sees a frame oriented the way
    /// the user held the phone (portrait) — without the transform
    /// flag the raw track is landscape with a 90° transform set
    /// in metadata.
    private static func extractFirstFrame(from asset: AVURLAsset) async -> UIImage? {
        let generator = AVAssetImageGenerator(asset: asset)
        generator.appliesPreferredTrackTransform = true
        // Snap to the first sample exactly — `kCMTimeZero` rounds
        // to the nearest keyframe by default which on some codecs
        // means the very first frame.
        let time = CMTime(seconds: 0, preferredTimescale: 600)
        do {
            let cgImage = try generator.copyCGImage(at: time, actualTime: nil)
            return UIImage(cgImage: cgImage)
        } catch {
            print("PhotoCapture first-frame extract failed: \(error)")
            return nil
        }
    }

    /// Strip the audio track into a standalone `.m4a` written into
    /// `AttachmentStorage`. Returns nil when the recording had no
    /// audio track (mic permission denied, hardware unavailable)
    /// — in that case the captured-preview shows up without the
    /// "With audio" badge and the commit path skips the voice-note
    /// attach.
    private static func extractAudio(from asset: AVURLAsset) async -> URL? {
        let audioTracks = asset.tracks(withMediaType: .audio)
        guard !audioTracks.isEmpty else { return nil }
        guard let export = AVAssetExportSession(asset: asset, presetName: AVAssetExportPresetAppleM4A) else {
            return nil
        }
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("photo_capture_audio", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let outURL = dir.appendingPathComponent("buffer-\(Int(Date().timeIntervalSince1970 * 1000)).m4a")
        try? FileManager.default.removeItem(at: outURL)
        export.outputURL = outURL
        export.outputFileType = .m4a
        await export.export()
        if export.status != .completed {
            print("PhotoCapture audio extract failed: \(String(describing: export.error))")
            return nil
        }
        // Move into AttachmentStorage so the file's lifecycle
        // matches every other voice note (synced to Drive,
        // pulled into thumbnails, etc.). Reading via
        // `Data(contentsOf:)` + `AttachmentStorage.write` is the
        // shared helper the import / scan paths already use.
        guard let data = try? Data(contentsOf: outURL),
              let stored = AttachmentStorage.write(data, ext: "m4a") else {
            try? FileManager.default.removeItem(at: outURL)
            return nil
        }
        try? FileManager.default.removeItem(at: outURL)
        return stored
    }
}
