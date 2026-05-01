/*
 * VoicePageRecorder.swift
 *
 * Recording control for the page-detail voice tab. Idle state shows
 * a halo disc with editorial copy; recording state expands into a
 * live waveform driven by AVAudioRecorder metering, a progress ring
 * that fills clockwise toward the 2:00 auto-save cap, an mm:ss
 * counter, and a slide-up-to-cancel gesture.
 *
 * Threading: the engine class is `@MainActor`. AVAudioRecorder is
 * thread-safe for the calls we make and the metering poll runs on a
 * Timer scheduled on the main run loop.
 *
 * Color: every coral surface reads from `@Environment(\.accentPalette)`
 * so the user's selected theme (coral / green / yellow / dry) drives
 * the disc, progress ring, waveform fill, halos, and "today" pulse
 * cues. Danger / cancel state uses a dedicated red distinct from any
 * theme palette so "you're about to discard this" reads consistently.
 *
 * Required Info.plist:
 *   - NSMicrophoneUsageDescription
 *   - NSSpeechRecognitionUsageDescription (only if transcription
 *     happens further up the stack — the recorder itself doesn't ask)
 */

import SwiftUI
import AVFoundation
import ReleafDesignSystem
import ReleafData

// MARK: - Public view

public struct VoicePageRecorder: View {
    /// Whether the surrounding section has any saved notes. Drives the
    /// idle copy: full editorial empty-state on day-one, compact prompt
    /// once at least one note exists.
    let isEmpty: Bool
    let onSave: (RecordedClip) -> Void
    let onCancel: () -> Void

    @StateObject private var engine = VoicePageRecorderEngine()
    @Environment(\.accentPalette) private var accent

    @State private var dragOffsetY: CGFloat = 0
    @State private var dragStartY: CGFloat? = nil
    @State private var isCancelHover: Bool = false

    public struct RecordedClip {
        public let uri: String
        public let durationMs: Int
    }

    public init(
        isEmpty: Bool,
        onSave: @escaping (RecordedClip) -> Void,
        onCancel: @escaping () -> Void = {}
    ) {
        self.isEmpty = isEmpty
        self.onSave = onSave
        self.onCancel = onCancel
    }

    public var body: some View {
        Group {
            if engine.isRecording {
                recordingStage
            } else {
                idleStage
            }
        }
        .onDisappear { engine.cancel() }
    }

    // MARK: Idle stage

    private var idleStage: some View {
        // Idle layout is identical on first record and every
        // subsequent record — the drawer is a focused capture
        // surface, not a dashboard of how many notes already exist
        // (the parent surface already shows the count). Keeping it
        // consistent means the muscle memory of "tap Record → see
        // disc + headline → tap disc" works the same way the
        // second, third, tenth time.
        VStack(spacing: AppSpacing.s4) {
            recordDisc
                .onTapGesture { startRecording() }
            Text("Catch the thought before it goes.")
                .font(.system(size: 19, weight: .regular, design: .serif))
                .multilineTextAlignment(.center)
                .foregroundStyle(AppColors.textPrimary)
                .frame(maxWidth: 240)
            Text("Tap to record. Up to two minutes per note.")
                .font(AppText.meta)
                .multilineTextAlignment(.center)
                .foregroundStyle(AppColors.textTertiary)
                .frame(maxWidth: 260)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, AppSpacing.s5)
    }

    private var recordDisc: some View {
        ZStack {
            // Outer dashed halo
            Circle()
                .stroke(
                    accent.primary.opacity(0.45),
                    style: StrokeStyle(lineWidth: 0.7, dash: [3, 3])
                )
                .frame(width: 130, height: 130)
            // Soft pulse
            Circle()
                .fill(accent.soft)
                .frame(width: 102, height: 102)
            // Coral disc
            Circle()
                .fill(accent.primary)
                .frame(width: 74, height: 74)
            // Mic glyph
            Image(systemName: "mic.fill")
                .font(.system(size: 26, weight: .regular))
                .foregroundStyle(AppColors.cream)
        }
        .frame(width: 130, height: 130)
        .contentShape(Rectangle())
    }

    // MARK: Recording stage

    private var recordingStage: some View {
        VStack(spacing: AppSpacing.s4) {
            recordingEyebrow
            liveWaveform
                .frame(height: 80)
            counter
            stopButtonArea
            recordingHint
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, AppSpacing.s4)
    }

    private var recordingEyebrow: some View {
        HStack(spacing: AppSpacing.s2) {
            Circle()
                .fill(isCancelHover ? VoiceColors.danger : accent.deep)
                .frame(width: 8, height: 8)
                .opacity(0.85)
                .scaleEffect(isCancelHover ? 0.9 : 1.0)
                .animation(
                    .easeInOut(duration: 0.95).repeatForever(autoreverses: true),
                    value: engine.isRecording
                )
            Text(isCancelHover ? "RELEASE TO CANCEL" : "RECORDING")
                .font(AppText.eyebrow)
                .tracking(AppLetterSpacing.eyebrow)
                .foregroundStyle(isCancelHover ? VoiceColors.danger : accent.deep)
        }
        .animation(.easeInOut(duration: 0.18), value: isCancelHover)
    }

    private var liveWaveform: some View {
        // The engine pushes amplitudes (0...1) into a rolling buffer.
        // Each bar is a fixed-width Capsule; `Spacer(minLength: 0)`
        // between bars makes the bars span end-to-end across the
        // available width — first bar at the left edge, last bar at
        // the right edge, equal flex space between. Without this,
        // the fixed 3pt × 38 + 2.5pt spacing content (~210pt) leaves
        // a big gap on the right of any phone wider than that.
        HStack(spacing: 0) {
            ForEach(Array(engine.amplitudes.enumerated()), id: \.offset) { index, level in
                Capsule()
                    .fill(isCancelHover ? VoiceColors.danger : accent.primary)
                    .frame(
                        width: 3,
                        height: max(4, CGFloat(level) * 76)
                    )
                if index < engine.amplitudes.count - 1 {
                    Spacer(minLength: 0)
                }
            }
        }
        .animation(.easeOut(duration: 0.11), value: engine.amplitudes)
        .padding(.horizontal, AppSpacing.s3)
        .frame(maxWidth: .infinity)
    }

    private var counter: some View {
        Text(engine.formattedElapsed)
            .font(.system(size: 38, weight: .regular, design: .serif))
            .monospacedDigit()
            .foregroundStyle(isCancelHover ? VoiceColors.danger : AppColors.textPrimary)
            .animation(.easeInOut(duration: 0.18), value: isCancelHover)
    }

    private var stopButtonArea: some View {
        // The previous outer .frame(width: 130, height: 130) clipped
        // the cancel hint out of the visible region (60pt hint +
        // 100pt button > 130pt frame), so "Swipe up to cancel"
        // never appeared. Letting the VStack size to its content
        // makes the hint reserve its 60pt slot regardless of state
        // (opacity controls visibility) and stops the layout from
        // shifting on the first drag pixel.
        VStack(spacing: 8) {
            cancelHint
                .frame(height: 60)
                .opacity(isDragging ? 1 : 0)
                .animation(.easeOut(duration: 0.18), value: isDragging)
            stopButton
                .offset(y: -dragOffsetY)
                .animation(
                    isDragging ? .none : .spring(response: 0.32, dampingFraction: 0.7),
                    value: dragOffsetY
                )
                .gesture(stopGesture)
        }
        .fixedSize(horizontal: false, vertical: true)
    }

    private var cancelHint: some View {
        VStack(spacing: 5) {
            ZStack {
                Circle()
                    .stroke(
                        isCancelHover ? VoiceColors.danger : AppColors.textTertiary,
                        style: StrokeStyle(lineWidth: 1, dash: isCancelHover ? [] : [3, 3])
                    )
                    .frame(width: 36, height: 36)
                if isCancelHover {
                    Circle()
                        .fill(VoiceColors.danger)
                        .frame(width: 36, height: 36)
                }
                Image(systemName: "xmark")
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(
                        isCancelHover
                            ? AppColors.cream
                            : AppColors.textTertiary
                    )
            }
            .animation(.easeInOut(duration: 0.18), value: isCancelHover)

            Text(isCancelHover ? "Release to cancel" : "Swipe up to cancel")
                .font(.system(size: 10.5, weight: .medium))
                .tracking(0.4)
                .foregroundStyle(
                    isCancelHover ? VoiceColors.danger : AppColors.textTertiary
                )
                .animation(.easeInOut(duration: 0.18), value: isCancelHover)
        }
    }

    private var stopButton: some View {
        ZStack {
            // Dashed track
            Circle()
                .stroke(
                    accent.primary.opacity(0.35),
                    style: StrokeStyle(lineWidth: 0.8, dash: [3, 3])
                )
                .frame(width: 100, height: 100)

            // Progress fill
            Circle()
                .trim(from: 0, to: engine.progress)
                .stroke(
                    isCancelHover ? VoiceColors.danger : accent.primary,
                    style: StrokeStyle(lineWidth: 2.5, lineCap: .round)
                )
                .rotationEffect(.degrees(-90))
                .frame(width: 100, height: 100)
                .animation(.linear(duration: 0.18), value: engine.progress)

            // Pulse halo
            Circle()
                .fill(isCancelHover ? VoiceColors.dangerSoft : accent.soft)
                .frame(width: 84, height: 84)
                .scaleEffect(engine.pulseScale)
                .animation(
                    .easeInOut(duration: 1.4).repeatForever(autoreverses: true),
                    value: engine.pulseTick
                )

            // Action button
            Circle()
                .fill(isCancelHover ? VoiceColors.danger : accent.primary)
                .frame(width: 56, height: 56)
                .animation(.easeInOut(duration: 0.18), value: isCancelHover)

            // Stop or X glyph
            if isCancelHover {
                Image(systemName: "xmark")
                    .font(.system(size: 22, weight: .medium))
                    .foregroundStyle(AppColors.cream)
                    .transition(.scale.combined(with: .opacity))
            } else {
                RoundedRectangle(cornerRadius: 4)
                    .fill(AppColors.cream)
                    .frame(width: 20, height: 20)
                    .transition(.scale.combined(with: .opacity))
            }
        }
        .frame(width: 100, height: 100)
        .contentShape(Circle())
    }

    private var recordingHint: some View {
        Text("Tap to stop · swipe up to cancel · auto-saves at 2:00")
            .font(.system(size: 11.5))
            .multilineTextAlignment(.center)
            .foregroundStyle(AppColors.textTertiary)
            .opacity(isDragging ? 0 : 1)
            .animation(.easeOut(duration: 0.2), value: isDragging)
            .frame(maxWidth: 280)
    }

    // MARK: Gesture

    private var isDragging: Bool { dragOffsetY > 4 || isCancelHover }

    private var stopGesture: some Gesture {
        DragGesture(minimumDistance: 0, coordinateSpace: .local)
            .onChanged { value in
                if dragStartY == nil {
                    dragStartY = value.location.y
                }
                let dy = -value.translation.height
                if dy > VoiceLayout.dragTapThreshold {
                    dragOffsetY = min(dy, VoiceLayout.cancelThreshold * 1.4)
                    isCancelHover = dy >= VoiceLayout.cancelThreshold
                } else {
                    dragOffsetY = 0
                    isCancelHover = false
                }
            }
            .onEnded { value in
                let dy = -value.translation.height
                let totalMovement = abs(value.translation.height)

                if isCancelHover {
                    cancelRecording()
                } else if totalMovement < VoiceLayout.dragTapThreshold {
                    finishRecording()
                } else {
                    // Partial drag below threshold — snap back, keep recording
                    dragOffsetY = 0
                }

                isCancelHover = false
                dragStartY = nil
            }
    }

    // MARK: Actions

    private func startRecording() {
        Task {
            let granted = await VoicePageRecorderEngine.requestPermission()
            guard granted else { return }
            engine.start()
        }
    }

    private func finishRecording() {
        guard let result = engine.stop() else {
            // Clip too short or never started — treat like cancel
            onCancel()
            return
        }
        onSave(RecordedClip(uri: result.uri, durationMs: result.durationMs))
    }

    private func cancelRecording() {
        engine.cancel()
        dragOffsetY = 0
        onCancel()
    }
}

// MARK: - Engine

@MainActor
final class VoicePageRecorderEngine: ObservableObject {
    struct Result { let uri: String; let durationMs: Int }

    /// Drop anything under this — almost certainly a double-tap misfire.
    private static let minRecordingMs: Int = 500
    /// Auto-stop ceiling. Matches the prototype + the existing
    /// editor-section recorder.
    static let maxRecordingS: Int = 120

    @Published private(set) var isRecording: Bool = false
    @Published private(set) var elapsedMs: Int = 0
    @Published private(set) var amplitudes: [Float] = Array(repeating: 0.05, count: 38)
    /// Drives the soft pulse halo around the stop button. Toggled on a
    /// 1.4s timer so the SwiftUI animation modifier has a value to
    /// observe — the actual scale value comes from `pulseScale`.
    @Published private(set) var pulseTick: Bool = false

    private var recorder: AVAudioRecorder?
    private var outputURL: URL?
    private var startedAt: Date?
    private var counterTimer: Timer?
    private var meterTimer: Timer?
    private var pulseTimer: Timer?

    var progress: CGFloat {
        guard isRecording else { return 0 }
        return CGFloat(min(Double(elapsedMs) / 1000.0 / Double(Self.maxRecordingS), 1.0))
    }

    var pulseScale: CGFloat { pulseTick ? 1.06 : 1.0 }

    var formattedElapsed: String {
        let total = elapsedMs / 1000
        let m = total / 60
        let s = total % 60
        return String(format: "%d:%02d", m, s)
    }

    static func requestPermission() async -> Bool {
        await withCheckedContinuation { continuation in
            AVAudioSession.sharedInstance().requestRecordPermission { granted in
                continuation.resume(returning: granted)
            }
        }
    }

    @discardableResult
    func start() -> Bool {
        guard !isRecording else { return false }
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(
                .playAndRecord,
                mode: .default,
                options: [.defaultToSpeaker, .allowBluetoothHFP]
            )
            try session.setActive(true)

            let dir = try AttachmentStorage.directory()
            let url = dir.appendingPathComponent("\(Uuidv7.generate()).m4a")
            let settings: [String: Any] = [
                AVFormatIDKey:             Int(kAudioFormatMPEG4AAC),
                AVSampleRateKey:           44_100,
                AVNumberOfChannelsKey:     1,
                AVEncoderAudioQualityKey:  AVAudioQuality.medium.rawValue,
                AVEncoderBitRateKey:       96_000,
            ]
            let rec = try AVAudioRecorder(url: url, settings: settings)
            rec.isMeteringEnabled = true
            guard rec.prepareToRecord(), rec.record() else {
                try? FileManager.default.removeItem(at: url)
                return false
            }

            self.recorder    = rec
            self.outputURL   = url
            self.startedAt   = Date()
            self.isRecording = true
            self.elapsedMs   = 0
            self.amplitudes  = Array(repeating: 0.05, count: 38)
            startCounterTimer()
            startMeterTimer()
            startPulseTimer()
            return true
        } catch {
            return false
        }
    }

    @discardableResult
    func stop() -> Result? {
        guard isRecording, let rec = recorder, let url = outputURL else {
            tearDown()
            return nil
        }
        let durationMs = Int(Date().timeIntervalSince(startedAt ?? Date()) * 1000)
        rec.stop()
        tearDown()

        let exists = FileManager.default.fileExists(atPath: url.path)
        let size   = (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? Int) ?? 0
        guard exists, size > 0, durationMs >= Self.minRecordingMs else {
            try? FileManager.default.removeItem(at: url)
            return nil
        }
        return Result(uri: url.absoluteString, durationMs: durationMs)
    }

    func cancel() {
        guard isRecording else { return }
        recorder?.stop()
        if let url = outputURL {
            try? FileManager.default.removeItem(at: url)
        }
        tearDown()
    }

    private func startCounterTimer() {
        counterTimer?.invalidate()
        counterTimer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self, let started = self.startedAt else { return }
                self.elapsedMs = Int(Date().timeIntervalSince(started) * 1000)
                if self.elapsedMs / 1000 >= Self.maxRecordingS {
                    _ = self.stop()
                }
            }
        }
    }

    private func startMeterTimer() {
        meterTimer?.invalidate()
        meterTimer = Timer.scheduledTimer(withTimeInterval: 0.06, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self, let rec = self.recorder, self.isRecording else { return }
                rec.updateMeters()
                let db = rec.averagePower(forChannel: 0)
                // Map -50 dB...0 dB to 0.04...1.0
                let normalized = max(0.04, min(1.0, Float(pow(10.0, db / 20.0)) * 2.5))
                var next = self.amplitudes
                next.removeFirst()
                next.append(normalized)
                self.amplitudes = next
            }
        }
    }

    private func startPulseTimer() {
        pulseTimer?.invalidate()
        pulseTimer = Timer.scheduledTimer(withTimeInterval: 1.4, repeats: true) { [weak self] _ in
            Task { @MainActor in
                self?.pulseTick.toggle()
            }
        }
    }

    private func tearDown() {
        counterTimer?.invalidate()
        meterTimer?.invalidate()
        pulseTimer?.invalidate()
        counterTimer = nil
        meterTimer = nil
        pulseTimer = nil
        recorder = nil
        outputURL = nil
        startedAt = nil
        isRecording = false
        elapsedMs = 0
        try? AVAudioSession.sharedInstance().setActive(false, options: [.notifyOthersOnDeactivation])
    }
}

// MARK: - Layout / colors

private enum VoiceLayout {
    /// Below this drag distance, treat a release as a tap (stop & save).
    static let dragTapThreshold: CGFloat = 6
    /// At/above this drag distance, the cancel-zone activates and a
    /// release commits the cancel.
    static let cancelThreshold: CGFloat = 70
}

private enum VoiceColors {
    /// Dedicated danger / cancel red. Distinct from any accent palette
    /// so "you're discarding this" reads consistently across themes.
    static let danger = Color(red: 0xA3 / 255, green: 0x2D / 255, blue: 0x2D / 255)
    static let dangerSoft = Color(red: 0xF4 / 255, green: 0xC9 / 255, blue: 0xC0 / 255)
}

private extension AppColors {
    /// Cream surface used for the icon glyphs that sit on top of the
    /// coral disc. Falls back to the design system canvas if the host
    /// app's palette ever ships a darker cream.
    static var cream: Color {
        Color(red: 0xFB / 255, green: 0xF8 / 255, blue: 0xEC / 255)
    }
}
