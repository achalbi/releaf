/*
 * ScanCaptureSurface.swift
 *
 * Wrapping surface mounted by QuickInkRoot when the ScanFlowController
 * leaves `.idle`. Three phases:
 *
 *   1. [VoiceNoteCapturePane] — pre-review voice-note capture. The
 *      user can record a short dictation, skip, or cancel the whole
 *      scan back to the home screen.
 *   2. [VoiceNoteTranscriptionPane] — shown when a recorded or
 *      pre-attached voice note exists, including video-capture
 *      audio, so the transcript can be edited before review.
 *   3. [ScanReviewScreen]     — the existing folder / paper-size /
 *      tags review. Suggestions on this screen pull both the first-
 *      page OCR text AND any voice-note transcript persisted in
 *      phase 1, so dictation contributes to the AI tag picks.
 *
 * Phase advances on Skip, on the recorder's onSave callback, or on
 * the explicit "Continue to review" CTA. captureId comes off the
 * controller's state once recognition starts.
 */

import SwiftUI

struct ScanCaptureSurface: View {

    @ObservedObject var controller: ScanFlowController
    let userId: String

    @State private var voiceNoteCompleted: Bool = false
    @State private var pendingTranscriptId: String? = nil
    @State private var returningFromTranscript: Bool = false

    private var captureId: String? {
        switch controller.state {
        case .recognizing(let captureId, _, _): return captureId
        case .complete(let captureId, _, _):    return captureId
        default:                                return nil
        }
    }

    private var isVideoCapture: Bool {
        controller.currentSource == "video"
    }

    var body: some View {
        Group {
            if voiceNoteCompleted {
                ScanReviewScreen(
                    controller: controller,
                    userId:     userId,
                    onBack:     {
                        voiceNoteCompleted = false
                        pendingTranscriptId = nil
                    }
                )
            } else if let pendingTranscriptId = pendingTranscriptId, let captureId = captureId {
                VoiceNoteTranscriptionPane(
                    captureId:                captureId,
                    voiceNoteId:              pendingTranscriptId,
                    showCancelButton:         !isVideoCapture,
                    placeContinueBelowEditor: isVideoCapture,
                    continueLabel:            isVideoCapture ? "Continue for review" : "Continue to review",
                    onContinue: {
                        voiceNoteCompleted = true
                        self.pendingTranscriptId = nil
                        returningFromTranscript = false
                    },
                    onCancel: {
                        self.pendingTranscriptId = nil
                        voiceNoteCompleted = false
                        returningFromTranscript = true
                    }
                )
            } else if let captureId = captureId {
                VoiceNoteCapturePane(
                    captureId:  captureId,
                    userId:     userId,
                    onContinue: { noteId in
                        returningFromTranscript = false
                        if let noteId {
                            pendingTranscriptId = noteId
                        } else {
                            voiceNoteCompleted = true
                        }
                    },
                    autoSkipExistingNote: !returningFromTranscript
                )
            }
        }
        .onChange(of: captureId) { newValue in
            // Reset the phase on a fresh scan so subsequent captures
            // also pause on the voice-note pane.
            if newValue == nil {
                voiceNoteCompleted = false
                pendingTranscriptId = nil
                returningFromTranscript = false
            }
        }
    }
}

struct ScanCaptureHandoffSurface: View {
    var eyebrow: String = "VOICE NOTE"
    var title: String = "Add some context"

    var body: some View {
        VStack(spacing: 0) {
            Spacer()

            VStack(spacing: 2) {
                Text(eyebrow)
                    .font(.system(size: 10, weight: .bold))
                    .tracking(1.2)
                    .foregroundStyle(QuickInkColors.muted)
                Text(title)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(QuickInkColors.ink)
            }
            .padding(.top, QuickInkSpacing.s8)

            ProgressView()
                .progressViewStyle(.circular)
                .tint(QuickInkColors.accent)
                .padding(.top, QuickInkSpacing.s5)

            Spacer()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(QuickInkColors.bg.ignoresSafeArea())
    }
}
