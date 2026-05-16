/*
 * ScanCaptureSurface.swift
 *
 * Wrapping surface mounted by QuickInkRoot when the ScanFlowController
 * leaves `.idle`. Two phases:
 *
 *   1. [VoiceNoteCapturePane] — pre-review voice-note capture. The
 *      user can record a short dictation, skip, or cancel the whole
 *      scan back to the home screen.
 *   2. [ScanReviewScreen]     — the existing folder / paper-size /
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

    private var captureId: String? {
        switch controller.state {
        case .recognizing(let captureId, _, _): return captureId
        case .complete(let captureId, _, _):    return captureId
        default:                                return nil
        }
    }

    var body: some View {
        Group {
            if !voiceNoteCompleted, let captureId = captureId {
                VoiceNoteCapturePane(
                    captureId:  captureId,
                    userId:     userId,
                    onContinue: { voiceNoteCompleted = true },
                    onCancel:   { controller.dismiss() }
                )
            } else {
                ScanReviewScreen(
                    controller: controller,
                    userId:     userId,
                    onBack:     { voiceNoteCompleted = false }
                )
            }
        }
        .onChange(of: captureId) { newValue in
            // Reset the phase on a fresh scan so subsequent captures
            // also pause on the voice-note pane.
            if newValue == nil {
                voiceNoteCompleted = false
            }
        }
    }
}
