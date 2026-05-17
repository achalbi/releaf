/*
 * StoryVoiceClipRecorder.swift
 *
 * Stories Phase 2 — tap-and-hold voice-clip recorder embedded inside
 * the "+ Add" sheet. Mirrors the existing capture-attached
 * `VoicePageRecorderEngine` but with the handoff doc's spec for
 * story clips: AAC-LC 64 kbps mono, 16 kHz, max 10 s. Releases on
 * gesture-up (or auto-stops at 10 s) and hands the resulting
 * `(audioUri, durationMs)` back to the caller, which routes it into
 * `StoryEditorViewModel.insertVoiceClipItem(...)`.
 *
 * The view exposes the live waveform (38 bars) + countdown timer so
 * the user sees both elapsed time + audio level while holding. On
 * release, the captured .m4a stays on disk and the `story_voice_clip`
 * row lands dirty for the next sync push.
 *
 * Mirror of Android `StoryVoiceClipRecorder.kt`.
 */

import AVFoundation
import Combine
import Foundation
import ReleafCoreData
import SwiftUI

/// Hard cap per the handoff doc — 10 s. The recorder auto-stops at
/// this boundary; the view shows a coral countdown in the last 2 s.
private let kStoryVoiceClipMaxMs: Int = 10_000

/// AAC-LC 64 kbps mono — matches the handoff doc.
private let kStoryVoiceClipBitrate: Int = 64_000

@MainActor
final class StoryVoiceClipRecorderEngine: NSObject, ObservableObject, AVAudioRecorderDelegate {

    @Published private(set) var isRecording: Bool = false
    @Published private(set) var elapsedMs: Int = 0
    /// Last N normalized peak readings, 0…1. Rendered as the waveform.
    @Published private(set) var amplitudes: [Float] = Array(repeating: 0.04, count: 38)

    private var recorder: AVAudioRecorder?
    private var counterTimer: Timer?
    private var meterTimer: Timer?
    private var outputUrl: URL?

    func start() -> Bool {
        guard !isRecording else { return false }
        do {
            try AVAudioSession.sharedInstance().setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker, .allowBluetooth])
            try AVAudioSession.sharedInstance().setActive(true)
        } catch {
            return false
        }
        let dir: URL
        do {
            dir = try AttachmentStorage.directory()
        } catch {
            return false
        }
        let id = Uuidv7.generate()
        let url = dir.appendingPathComponent("\(id).m4a")
        outputUrl = url

        let settings: [String: Any] = [
            AVFormatIDKey:             Int(kAudioFormatMPEG4AAC),
            AVSampleRateKey:           16_000,
            AVNumberOfChannelsKey:     1,
            AVEncoderBitRateKey:       kStoryVoiceClipBitrate,
            AVEncoderAudioQualityKey:  AVAudioQuality.medium.rawValue,
        ]
        do {
            let rec = try AVAudioRecorder(url: url, settings: settings)
            rec.isMeteringEnabled = true
            rec.delegate = self
            guard rec.record() else { return false }
            recorder = rec
            isRecording = true
            elapsedMs = 0
            amplitudes = Array(repeating: 0.04, count: 38)
            startCounterTimer()
            startMeterTimer()
            return true
        } catch {
            return false
        }
    }

    /// Stops the recorder and returns `(audioUri, durationMs)` if the
    /// clip is long enough to be useful (≥ 300 ms). Nil otherwise —
    /// the .m4a is deleted in that case.
    func stop() -> (audioUri: String, durationMs: Int)? {
        guard let rec = recorder, isRecording else { return nil }
        let duration = elapsedMs
        rec.stop()
        teardown()
        guard let url = outputUrl else { return nil }
        if duration < 300 {
            try? FileManager.default.removeItem(at: url)
            return nil
        }
        return (audioUri: url.absoluteString, durationMs: duration)
    }

    func cancel() {
        recorder?.stop()
        teardown()
        if let url = outputUrl {
            try? FileManager.default.removeItem(at: url)
        }
    }

    private func teardown() {
        counterTimer?.invalidate(); counterTimer = nil
        meterTimer?.invalidate(); meterTimer = nil
        try? AVAudioSession.sharedInstance().setActive(false)
        isRecording = false
    }

    private func startCounterTimer() {
        counterTimer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self = self else { return }
                self.elapsedMs += 100
                if self.elapsedMs >= kStoryVoiceClipMaxMs {
                    _ = self.stop()
                }
            }
        }
    }

    private func startMeterTimer() {
        meterTimer = Timer.scheduledTimer(withTimeInterval: 0.06, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self = self, let rec = self.recorder else { return }
                rec.updateMeters()
                let db = rec.averagePower(forChannel: 0)   // -160 ... 0 dBFS
                // Map -50 dBFS → 0.04, 0 dBFS → 1.0
                let clamped = max(-50.0, min(0.0, Double(db)))
                let normalized = Float((clamped + 50.0) / 50.0)
                let level = max(0.04, normalized)
                self.amplitudes = self.amplitudes.dropFirst() + [level]
            }
        }
    }
}

struct StoryVoiceClipRecorderView: View {

    var onSave: (_ audioUri: String, _ durationMs: Int) -> Void
    var onCancel: () -> Void

    @StateObject private var engine = StoryVoiceClipRecorderEngine()

    var body: some View {
        VStack(spacing: QuickInkSpacing.s4) {
            handle
            Text("Record a voice note")
                .font(QuickInkText.editorial)
                .foregroundStyle(QuickInkColors.ink)
            Text("Tap and hold the button below. Max 10 seconds.")
                .font(QuickInkText.bodyItalic)
                .foregroundStyle(QuickInkColors.inkSoft)

            waveform

            timerLabel

            holdButton

            HStack {
                Button("Cancel") {
                    engine.cancel()
                    onCancel()
                }
                .font(.system(size: 13))
                .foregroundStyle(QuickInkColors.inkSoft)
                .buttonStyle(.plain)
                Spacer()
            }
            .padding(.horizontal, QuickInkSpacing.s4)
            .padding(.bottom, QuickInkSpacing.s3)
        }
        .padding(.top, QuickInkSpacing.s2)
        .background(QuickInkColors.surface)
        .onDisappear { engine.cancel() }
    }

    private var handle: some View {
        Capsule()
            .fill(QuickInkColors.border)
            .frame(width: 38, height: 4)
            .padding(.bottom, QuickInkSpacing.s2)
    }

    private var waveform: some View {
        HStack(spacing: 3) {
            ForEach(0..<engine.amplitudes.count, id: \.self) { idx in
                Capsule()
                    .fill(engine.isRecording ? QuickInkColors.accent : QuickInkColors.border)
                    .frame(width: 3, height: max(4, CGFloat(engine.amplitudes[idx]) * 40))
            }
        }
        .frame(height: 44)
        .animation(.linear(duration: 0.05), value: engine.amplitudes)
    }

    private var timerLabel: some View {
        let secs = Double(engine.elapsedMs) / 1000.0
        let warning = engine.elapsedMs >= kStoryVoiceClipMaxMs - 2_000
        return Text(String(format: "%.1fs", secs))
            .font(.system(size: 24, weight: .semibold, design: .rounded))
            .foregroundStyle(warning ? QuickInkColors.accent : QuickInkColors.ink)
            .monospacedDigit()
    }

    private var holdButton: some View {
        ZStack {
            Circle()
                .fill(engine.isRecording ? QuickInkColors.accent : QuickInkColors.accentSoft)
                .frame(width: 84, height: 84)
                .shadow(color: QuickInkColors.accent.opacity(engine.isRecording ? 0.4 : 0.15),
                        radius: engine.isRecording ? 18 : 8, y: 4)
            Image(systemName: "mic.fill")
                .font(.system(size: 28, weight: .semibold))
                .foregroundStyle(engine.isRecording ? QuickInkColors.textOnAccent : QuickInkColors.accent)
        }
        .scaleEffect(engine.isRecording ? 1.08 : 1.0)
        .animation(.spring(response: 0.25, dampingFraction: 0.7), value: engine.isRecording)
        .gesture(
            DragGesture(minimumDistance: 0)
                .onChanged { _ in
                    if !engine.isRecording {
                        _ = engine.start()
                    }
                }
                .onEnded { _ in
                    if let result = engine.stop() {
                        onSave(result.audioUri, result.durationMs)
                    }
                }
        )
        .accessibilityLabel("Hold to record a voice note")
    }
}
