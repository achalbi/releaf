/*
 * SpeechModelDownloadModal.kt
 *
 * Modal dialog that surfaces sherpa-onnx Whisper first-fetch
 * progress and failure to the user. Observes
 * [SpeechModelDownloadProgress.observe] which derives its state
 * from WorkManager's WorkInfo stream — meaning the modal
 * reappears at the right progress beat after the app is
 * dismissed and reopened mid-download.
 *
 * Two visual states:
 *   - Downloading: non-cancellable; bytes / total readout plus a
 *     determinate progress bar once Content-Length is known.
 *   - Failed:     dismissable; "Got it" cancels the failed work
 *                 (so the modal hides) and reminds the user that
 *                 their partial bytes are saved and the next tap
 *                 of Transcribe / Try again on the voice-note
 *                 card will resume from there.
 *
 * Hosted in `MainShell`'s root `Box` so it's visible across every
 * tab the user might be on while bytes pull down.
 */

package app.quickink.mobile.features.scan

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.quickink.mobile.data.voicenote.ModelDownloadState
import app.quickink.mobile.data.voicenote.SpeechModelDownloadProgress
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkRadius
import app.quickink.mobile.ui.theme.QuickInkSpacing

@Composable
fun SpeechModelDownloadModal() {
    val context = LocalContext.current
    val stateFlow = remember(context) { SpeechModelDownloadProgress.observe(context) }
    val state by stateFlow.collectAsState(initial = ModelDownloadState.Idle)
    when (val s = state) {
        is ModelDownloadState.Downloading -> DownloadingDialog(s)
        is ModelDownloadState.Extracting  -> ExtractingDialog()
        is ModelDownloadState.Failed      -> FailedDialog(s)
        is ModelDownloadState.Idle        -> Unit  // nothing to render
    }
}

@Composable
private fun ExtractingDialog() {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current

    Dialog(
        onDismissRequest = { /* non-cancellable */ },
        properties = DialogProperties(
            dismissOnBackPress    = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(QuickInkRadius.lg))
                .background(colors.surface)
                .padding(QuickInkSpacing.s5),
        ) {
            Text(
                text  = "Preparing transcription model",
                style = type.heading,
                color = colors.ink,
            )
            Spacer(Modifier.size(QuickInkSpacing.s2))
            Text(
                text  = "Decompressing the archive into your device. This can take a " +
                        "minute on a 500 MB+ model — the CPU does most of the work " +
                        "here, no more network traffic.",
                style = type.body,
                color = colors.inkSoft,
            )
            Spacer(Modifier.size(QuickInkSpacing.s4))
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color    = colors.accent,
            )
        }
    }
}

@Composable
private fun DownloadingDialog(state: ModelDownloadState.Downloading) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current

    Dialog(
        onDismissRequest = { /* non-cancellable — download keeps running */ },
        properties = DialogProperties(
            dismissOnBackPress    = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(QuickInkRadius.lg))
                .background(colors.surface)
                .padding(QuickInkSpacing.s5),
        ) {
            Text(
                text  = "Downloading transcription model",
                style = type.heading,
                color = colors.ink,
            )
            Spacer(Modifier.size(QuickInkSpacing.s2))
            Text(
                text  = "The download runs in the background — you can switch apps " +
                        "or close QuickInk and it'll keep going. Resumes automatically " +
                        "if your connection drops.",
                style = type.body,
                color = colors.inkSoft,
            )
            Spacer(Modifier.size(QuickInkSpacing.s4))

            // Determinate when we know the total, indeterminate while
            // we're still waiting on Content-Length / 0-byte phase.
            if (state.totalBytes > 0) {
                val fraction = (state.bytesDownloaded.toDouble() / state.totalBytes.toDouble())
                    .toFloat()
                    .coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                    color    = colors.accent,
                )
                Spacer(Modifier.size(QuickInkSpacing.s2))
                Text(
                    text  = "${state.bytesDownloaded.toMb()} MB / ${state.totalBytes.toMb()} MB",
                    style = type.meta,
                    color = colors.inkSoft,
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color    = colors.accent,
                )
                Spacer(Modifier.size(QuickInkSpacing.s2))
                Text(
                    text  = "Starting download…",
                    style = type.meta,
                    color = colors.inkSoft,
                )
            }
        }
    }
}

@Composable
private fun FailedDialog(state: ModelDownloadState.Failed) {
    val context = LocalContext.current
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current

    Dialog(
        onDismissRequest = { SpeechModelDownloadProgress.dismissFailures(context) },
        properties = DialogProperties(
            dismissOnBackPress    = true,
            dismissOnClickOutside = true,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(QuickInkRadius.lg))
                .background(colors.surface)
                .padding(QuickInkSpacing.s5),
        ) {
            Text(
                text  = "Download paused",
                style = type.heading,
                color = colors.ink,
            )
            Spacer(Modifier.size(QuickInkSpacing.s2))
            Text(
                text  = state.message,
                style = type.body,
                color = colors.inkSoft,
            )
            Spacer(Modifier.size(QuickInkSpacing.s2))
            Text(
                text  = "Your progress is saved. Tap Transcribe (or Try again) on the " +
                        "voice note to resume from where this attempt stopped.",
                style = type.meta,
                color = colors.inkSoft,
            )
            Spacer(Modifier.size(QuickInkSpacing.s4))
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                TextButton(onClick = {
                    SpeechModelDownloadProgress.dismissFailures(context)
                }) {
                    Text(text = "Got it", color = colors.accent)
                }
            }
        }
    }
}

private fun Long.toMb(): Long = (this + 524_288L) / 1_048_576L  // round to nearest MB
