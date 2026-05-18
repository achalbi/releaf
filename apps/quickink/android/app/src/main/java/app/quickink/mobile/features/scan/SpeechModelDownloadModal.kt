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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
    val downloading = state as? ModelDownloadState.Downloading ?: return

    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current

    Dialog(
        onDismissRequest = { /* non-cancellable — see header */ },
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
                text  = "One-time download (~500 MB). Voice notes recorded right now will transcribe " +
                        "as soon as the download finishes.",
                style = type.body,
                color = colors.inkSoft,
            )
            Spacer(Modifier.size(QuickInkSpacing.s4))

            // Determinate when we know the total, indeterminate while
            // we're still waiting on Content-Length / 0-byte phase.
            if (downloading.totalBytes > 0) {
                val fraction = (downloading.bytesDownloaded.toDouble() / downloading.totalBytes.toDouble())
                    .toFloat()
                    .coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                    color    = colors.accent,
                )
                Spacer(Modifier.size(QuickInkSpacing.s2))
                Text(
                    text  = "${downloading.bytesDownloaded.toMb()} MB / ${downloading.totalBytes.toMb()} MB",
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

private fun Long.toMb(): Long = (this + 524_288L) / 1_048_576L  // round to nearest MB
