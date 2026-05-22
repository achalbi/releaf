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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkSpacing

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
    val currentSource by controller.currentSource.collectAsState()
    val isVideoCapture = currentSource == "video"

    var voiceNoteCompleted by remember { mutableStateOf(false) }
    // Set by [VoiceNoteCapturePane] when the recorder commits a
    // clip. Non-null routes through [VoiceNoteTranscriptionPane]
    // before review; null (Skip / idle-Continue) jumps straight to
    // review.
    var pendingTranscriptId by remember { mutableStateOf<String?>(null) }
    var returningFromTranscript by remember { mutableStateOf(false) }

    // Reset both phases whenever the controller drops back to no-
    // captureId so subsequent scans also pause on the voice-note
    // pane rather than skipping past it.
    LaunchedEffect(captureId) {
        if (captureId == null) {
            voiceNoteCompleted      = false
            pendingTranscriptId     = null
            returningFromTranscript = false
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
                captureId                = captureId,
                voiceNoteId              = pendingTranscriptId!!,
                showCancelButton         = !isVideoCapture,
                placeContinueBelowEditor = isVideoCapture,
                continueLabel            = if (isVideoCapture) "Continue for review" else "Continue to review",
                onContinue               = {
                    voiceNoteCompleted   = true
                    pendingTranscriptId  = null
                    returningFromTranscript = false
                },
                onCancel                 = {
                    pendingTranscriptId     = null
                    voiceNoteCompleted      = false
                    returningFromTranscript = true
                },
            )
        }
        captureId != null -> {
            VoiceNoteCapturePane(
                captureId  = captureId,
                userId     = userId,
                onContinue = { noteId ->
                    returningFromTranscript = false
                    if (noteId == null) voiceNoteCompleted = true
                    else                pendingTranscriptId = noteId
                },
                autoSkipExistingNote = !returningFromTranscript,
            )
        }
    }
}

@Composable
fun ScanCaptureHandoffSurface(
    eyebrow: String = "VOICE NOTE",
    title: String = "Add some context",
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg)
            .padding(top = statusBarTop),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text  = eyebrow,
            modifier = Modifier.padding(top = QuickInkSpacing.s8),
            style = type.label.copy(
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
            ),
            color = colors.muted,
        )
        Text(
            text  = title,
            style = type.body.copy(
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            color = colors.ink,
        )
        Spacer(modifier = Modifier.size(QuickInkSpacing.s5))
        CircularProgressIndicator(
            color = colors.accent,
            strokeWidth = 3.dp,
            modifier = Modifier.size(28.dp),
        )
    }
}
