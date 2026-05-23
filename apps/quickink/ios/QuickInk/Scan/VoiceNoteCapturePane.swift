/*
 * VoiceNoteCapturePane.swift
 *
 * Pre-review surface that lets the user dictate a quick voice note
 * right after a fresh scan and before the full review screen. The
 * recorded clip is persisted into `voice_notes` against the in-
 * flight capture so the transcript can feed the AI-suggested tags
 * strip on the review screen (alongside the first-page OCR text).
 *
 * Layout: a slim header with Skip, the existing
 * `VoicePageRecorder` body, and a bottom bar that toggles between
 * two states:
 *
 *   - Idle (not recording): single "Continue to review" CTA. The
 *     user can also skip from the header.
 *   - Recording: two side-by-side buttons — "Cancel" (drops the
 *     in-progress clip and returns to idle on the same pane) and
 *     "Save and continue" (stops the recorder, commits the clip,
 *     kicks off background transcription, advances to review).
 */

import SwiftUI
import ReleafCoreDesignSystem

struct VoiceNoteCapturePane: View {

    let captureId: String
    let userId: String
    let onContinue: (String?) -> Void
    let autoSkipExistingNote: Bool

    @StateObject private var model: VoiceNoteCapturePaneModel
    @StateObject private var engine = VoicePageRecorderEngine()
    /// Auto-skip guard. On first mount we query the repository
    /// for any existing voice note attached to the captureId; if
    /// one is already there (e.g. video capture pre-attached the
    /// extracted audio), pass that note id up to transcript review
    /// so the user isn't prompted to record over audio we already
    /// have. `checkComplete` flips to true once the query lands;
    /// `body` shows a brief progress view in the meantime so we
    /// don't flash the recorder for a frame.
    @State private var checkComplete: Bool = false

    init(
        captureId: String,
        userId: String,
        onContinue: @escaping (String?) -> Void,
        autoSkipExistingNote: Bool = true
    ) {
        self.captureId = captureId
        self.userId    = userId
        self.onContinue = onContinue
        self.autoSkipExistingNote = autoSkipExistingNote
        _model = StateObject(
            wrappedValue: VoiceNoteCapturePaneModel(
                captureId: captureId,
                userId:    userId
            )
        )
    }

    var body: some View {
        if !checkComplete {
            preCheckLoader
                .task {
                    await runExistenceCheck()
                }
        } else {
            mainPane
        }
    }

    @ViewBuilder
    private var preCheckLoader: some View {
        ZStack {
            QuickInkColors.bg.ignoresSafeArea()
            ProgressView()
                .progressViewStyle(.circular)
                .tint(QuickInkColors.accent)
        }
    }

    /// Hit the voice-note repository once on mount. If a row
    /// already exists for this captureId, skip the recorder and
    /// pass that row id up so the parent can show transcript
    /// review before metadata review.
    /// Failures (DB read error) fall through to showing the pane
    /// so the user isn't stranded.
    private func runExistenceCheck() async {
        let existing = autoSkipExistingNote
            ? (try? await VoiceNoteRepository().firstForCapture(captureId))
            : nil
        if let existing {
            await MainActor.run { onContinue(existing.id) }
        } else {
            await MainActor.run { checkComplete = true }
        }
    }

    @ViewBuilder
    private var mainPane: some View {
        VStack(spacing: 0) {
            header
                .padding(.horizontal, QuickInkSpacing.s4)
                .padding(.top, QuickInkSpacing.s8)

            Spacer(minLength: QuickInkSpacing.s5)

            VoicePageRecorder(
                engine: engine,
                onSave: { clip in
                    // Reached when the recorder's internal stop fires
                    // (auto-stop at 2:00 or in-recorder Stop tap). The
                    // pane's "Save and continue" path goes through
                    // `saveAndContinue` directly, but the in-recorder
                    // stop should land the same place.
                    Task {
                        let noteId = await model.commit(uri: clip.uri, durationMs: clip.durationMs)
                        await MainActor.run { onContinue(noteId) }
                    }
                },
                onCancel: { /* recorder cancelled — stay on the pane */ }
            )
            .padding(.horizontal, QuickInkSpacing.s4)

            Spacer(minLength: QuickInkSpacing.s5)

            bottomBar
                .padding(.horizontal, QuickInkSpacing.s5)
                .padding(.bottom, QuickInkSpacing.s5)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(QuickInkColors.bg.ignoresSafeArea())
    }

    // MARK: Header

    private var header: some View {
        HStack(alignment: .center) {
            Color.clear.frame(width: 50, height: 1)

            Spacer()

            VStack(spacing: 2) {
                Text("VOICE NOTE")
                    .font(.system(size: 10, weight: .bold))
                    .tracking(1.2)
                    .foregroundStyle(QuickInkColors.muted)
                Text("Add some context")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(QuickInkColors.ink)
            }

            Spacer()

            if engine.isRecording {
                // Hide the Skip control while recording — Cancel /
                // Save and continue at the bottom take over.
                Color.clear.frame(width: 50, height: 1)
            } else {
                Button(action: { onContinue(nil) }) {
                    Text("Skip")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(QuickInkColors.accentDeep)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Skip voice note")
            }
        }
    }

    // MARK: Bottom bar

    @ViewBuilder
    private var bottomBar: some View {
        if engine.isRecording {
            HStack(spacing: QuickInkSpacing.s3) {
                Button(action: cancelRecording) {
                    Text("Cancel")
                        .font(AppText.body)
                        .foregroundStyle(QuickInkColors.ink)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, AppSpacing.s3)
                        .background(
                            Capsule().fill(Color.white.opacity(0.85))
                        )
                        .overlay(
                            Capsule().stroke(QuickInkColors.border, lineWidth: 1)
                        )
                }
                .buttonStyle(.plain)

                Button(action: saveAndContinue) {
                    Text("Save and continue")
                        .font(AppText.body)
                        .foregroundStyle(QuickInkColors.textOnAccent)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, AppSpacing.s3)
                        .background(QuickInkColors.accent)
                        .clipShape(Capsule())
                }
                .buttonStyle(.plain)
            }
        } else {
            Button(action: { onContinue(nil) }) {
                Text("Continue to review")
                    .font(AppText.body)
                    .foregroundStyle(QuickInkColors.textOnAccent)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, AppSpacing.s3)
                    .background(QuickInkColors.accent)
                    .clipShape(Capsule())
            }
            .buttonStyle(.plain)
        }
    }

    // MARK: Actions

    /// Stop the recorder, commit the resulting clip, kick off the
    /// background transcription, then advance to review.
    private func saveAndContinue() {
        guard let result = engine.stop() else {
            // Recording was below the engine's min duration — drop
            // it and advance anyway so the user isn't trapped.
            onContinue(nil)
            return
        }
        Task {
            let noteId = await model.commit(uri: result.uri, durationMs: result.durationMs)
            await MainActor.run { onContinue(noteId) }
        }
    }

    /// Drop the in-progress clip and return to the pane's idle
    /// state. User can tap to record again or skip.
    private func cancelRecording() {
        engine.cancel()
    }
}

@MainActor
final class VoiceNoteCapturePaneModel: ObservableObject {

    let captureId: String
    let userId: String
    private let repo: VoiceNoteRepository

    init(captureId: String, userId: String, repo: VoiceNoteRepository = VoiceNoteRepository()) {
        self.captureId = captureId
        self.userId    = userId
        self.repo      = repo
    }

    /// Insert the freshly recorded clip and fire-and-forget a
    /// transcription pass. We don't block the UI on transcription —
    /// the review screen reactively pulls in the transcript once
    /// `setTranscription` lands, and re-runs the AI suggester at
    /// that point.
    func commit(uri: String, durationMs: Int) async -> String? {
        do {
            let row = try await repo.insert(
                captureId:  captureId,
                userId:     userId,
                audioUri:   uri,
                durationMs: durationMs
            )
            let pendingUserId = userId
            let pendingCaptureId = captureId
            Task.detached(priority: .userInitiated) {
                let granted = await VoiceTranscriber.requestPermission()
                guard granted else { return }
                guard let url = Self.fileURL(for: uri) else { return }
                guard let result = await VoiceTranscriber.transcribe(
                    fileURL: url,
                    userId:  pendingUserId
                ) else { return }
                try? await VoiceNoteRepository().setTranscription(
                    id:            row.id,
                    transcription: result.text,
                    source:        result.source
                )
                try? await CaptureRepository().appendNote(
                    captureId: pendingCaptureId,
                    text:      result.text
                )
            }
            return row.id
        } catch {
            print("VoiceNoteCapturePane commit failed: \(error)")
            return nil
        }
    }

    nonisolated private static func fileURL(for raw: String) -> URL? {
        if let parsed = URL(string: raw), parsed.isFileURL { return parsed }
        return URL(fileURLWithPath: raw)
    }
}
