/*
 * SpeechModelDownloadModal.kt
 *
 * Modal dialog that surfaces the ~500 MB Whisper-small first-fetch
 * progress to the user. Observes [SpeechModelDownloadProgress.state]
 * and shows itself whenever a download is in flight; auto-dismisses
 * when state flips back to Idle (extraction finished) or Failed
 * (the recorder card surfaces the error separately).
 *
 * Non-cancellable — the download is fire-and-forget from
 * `SpeechTranscriber.transcribe`, and back-pressing the modal
 * wouldn't actually stop the HTTP fetch. We omit the dismiss
 * affordance and let the user navigate away from the host screen
 * if they need to; the modal re-appears the moment they land on a
 * shell that hosts it.
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    val state by SpeechModelDownloadProgress.state.collectAsState()
    when (val s = state) {
        is ModelDownloadState.Downloading -> DownloadingDialog(s)
        is ModelDownloadState.Failed      -> FailedDialog(s)
        is ModelDownloadState.Idle        -> Unit  // nothing to render
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
                text  = "Resumes automatically if your connection drops or the app is " +
                        "backgrounded. Voice notes recorded right now will transcribe " +
                        "as soon as the download finishes.",
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
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current

    // Dismissing flips the state holder back to Idle so the dialog
    // closes. Bytes already pulled stay on disk under
    // `<modelDir>.partial`, so the next tap of Transcribe / Try
    // again from the voice-note card picks up where this attempt
    // stopped — no fresh full re-download.
    Dialog(
        onDismissRequest = { SpeechModelDownloadProgress.update(ModelDownloadState.Idle) },
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
                    SpeechModelDownloadProgress.update(ModelDownloadState.Idle)
                }) {
                    Text(text = "Got it", color = colors.accent)
                }
            }
        }
    }
}

private fun Long.toMb(): Long = (this + 524_288L) / 1_048_576L  // round to nearest MB
