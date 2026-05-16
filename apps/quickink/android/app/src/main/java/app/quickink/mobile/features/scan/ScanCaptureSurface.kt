/*
 * ScanCaptureSurface.kt
 *
 * Wrapping surface mounted by QuickInkRoot when the ScanFlowController
 * leaves Idle. Three phases:
 *
 *   1. [VoiceNoteCapturePane]         — pre-review voice-note capture.
 *      Skip / idle-Continue bypass straight to review; the recorder's
 *      stop control commits the take and hands the row id to phase 2.
 *   2. [VoiceNoteTranscriptionPane]   — only mounted when phase 1
 *      committed a clip. Live transcript view with an editable field
 *      and a single "Continue to review" CTA.
 *   3. [ScanReviewScreen]             — folder / paper-size / tags
 *      review. Suggestions pull both first-page OCR text AND any
 *      voice-note transcript persisted upstream so dictation
 *      contributes to the AI tag picks.
 *
 * captureId comes off the controller's state once recognition starts.
 */

package app.quickink.mobile.features.scan

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

@Composable
fun ScanCaptureSurface(
    controller: ScanFlowController,
    userId: String,
) {
    val state by controller.state.collectAsState()
    val captureId: String? = when (val s = state) {
        is ScanFlowController.State.Recognizing -> s.captureId
        is ScanFlowController.State.Complete    -> s.captureId
        else                                    -> null
    }

    var voiceNoteCompleted by remember { mutableStateOf(false) }
    // Set by [VoiceNoteCapturePane] when the recorder commits a
    // clip. Non-null routes through [VoiceNoteTranscriptionPane]
    // before review; null (Skip / idle-Continue) jumps straight to
    // review.
    var pendingTranscriptId by remember { mutableStateOf<String?>(null) }

    // Reset both phases whenever the controller drops back to no-
    // captureId so subsequent scans also pause on the voice-note
    // pane rather than skipping past it.
    LaunchedEffect(captureId) {
        if (captureId == null) {
            voiceNoteCompleted   = false
            pendingTranscriptId  = null
        }
    }

    when {
        voiceNoteCompleted -> {
            ScanReviewScreen(
                controller = controller,
                userId     = userId,
                onBack     = {
                    voiceNoteCompleted  = false
                    pendingTranscriptId = null
                },
            )
        }
        pendingTranscriptId != null && captureId != null -> {
            VoiceNoteTranscriptionPane(
                captureId   = captureId,
                voiceNoteId = pendingTranscriptId!!,
                onContinue  = {
                    voiceNoteCompleted   = true
                    pendingTranscriptId  = null
                },
                onCancel    = { controller.dismiss() },
            )
        }
        captureId != null -> {
            VoiceNoteCapturePane(
                captureId  = captureId,
                userId     = userId,
                onContinue = { noteId ->
                    if (noteId == null) voiceNoteCompleted = true
                    else                pendingTranscriptId = noteId
                },
                onCancel   = { controller.dismiss() },
            )
        }
    }
}
