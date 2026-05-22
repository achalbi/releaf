/*
 * VoiceNoteCapturePane.kt
 *
 * Pre-review surface that lets the user dictate a quick voice note
 * right after a fresh scan and before the full review screen. The
 * recorded clip is persisted into `voice_notes` against the in-
 * flight capture so the transcript can feed the AI-suggested tags
 * strip on the review screen (alongside the first-page OCR text).
 *
 * Layout: slim header with Skip, the existing
 * [VoicePageRecorder] body, and a bottom bar that toggles between:
 *   - Idle: single "Continue to review" CTA that skips the voice
 *     note entirely. Skip lives in the header for the same effect.
 *   - Recording: a single full-width "Cancel" button that drops the
 *     in-progress clip and returns to idle on the same pane. There
 *     is no inline save button — the recorder's own stop control
 *     ends the take, fires `onSave`, and the parent surface advances
 *     to the transcript-edit page.
 *
 * `onContinue` carries the just-recorded voice-note id (or null for
 * Skip / idle-Continue). The parent [ScanCaptureSurface] uses that
 * to decide whether to mount [VoiceNoteTranscriptionPane] next or
 * jump straight to [ScanReviewScreen].
 */

package app.quickink.mobile.features.scan

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.quickink.mobile.QuickInkApp
import app.quickink.mobile.data.voicenote.SpeechTranscriber
import app.quickink.mobile.data.voicenote.TranscribeResult
import app.quickink.mobile.data.voicenote.VoiceNoteRepository
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkSpacing
import app.releaf.mobile.ui.theme.AppSpacing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@Composable
fun VoiceNoteCapturePane(
    captureId: String,
    userId: String,
    onContinue: (voiceNoteId: String?) -> Unit,
    autoSkipExistingNote: Boolean = true,
) {
    val colors  = LocalQuickInkColors.current
    val type    = LocalQuickInkTypography.current
    val context = LocalContext.current
    val app     = context.applicationContext as QuickInkApp
    val repo    = remember(app) { VoiceNoteRepository(app.database.voiceNoteDao()) }
    val scope   = rememberCoroutineScope()
    // Long-lived scope for the fire-and-forget transcription work
    // so it isn't cancelled when this pane unmounts on advance to
    // the transcript-edit pane. The SupervisorJob isn't auto-
    // cancelled on disposal — jobs launched on it run to completion.
    val backgroundScope = remember {
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    // Auto-skip guard. On first mount we query the repository
    // for any existing voice note attached to this captureId; if
    // one is already there (e.g. video capture pre-attached the
    // extracted audio), advance to the transcript-review pane
    // with that note's id so the user can review/edit it before
    // metadata review. `checkComplete` flips to true once the
    // query lands; while it's false we render nothing (just the
    // warm bg) so the recorder UI doesn't flash for a frame.
    var checkComplete by remember { mutableStateOf(false) }
    LaunchedEffect(captureId, autoSkipExistingNote) {
        val existing = if (autoSkipExistingNote) {
            runCatching { repo.firstForCapture(captureId) }.getOrNull()
        } else {
            null
        }
        if (existing != null) {
            onContinue(existing.id)
        } else {
            checkComplete = true
        }
    }
    if (!checkComplete) {
        Box(
            modifier         = Modifier.fillMaxSize().background(colors.bg),
            contentAlignment = Alignment.Center,
        ) {
            // Empty surface — the query is cheap; flashing a
            // spinner would be noisier than a brief blank frame.
        }
        return
    }

    // Engine is owned here so the bottom bar can observe
    // `isRecording` and drive stop/cancel.
    val engine = remember { VoicePageRecorderEngine(context, scope) }
    val isRecording = engine.isRecording

    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg)
            .padding(top = statusBarTop),
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start  = QuickInkSpacing.s4,
                    end    = QuickInkSpacing.s4,
                    top    = QuickInkSpacing.s8,
                    bottom = QuickInkSpacing.s2,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(modifier = Modifier.size(50.dp))
            Spacer(modifier = Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text  = "VOICE NOTE",
                    style = type.label.copy(
                        fontSize    = 10.sp,
                        fontWeight  = FontWeight.Bold,
                        letterSpacing = 1.2.sp,
                    ),
                    color = colors.muted,
                )
                Text(
                    text  = "Add some context",
                    style = type.body.copy(
                        fontSize   = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = colors.ink,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            // Hide Skip while recording — the bottom-bar Cancel is
            // the only action available mid-take.
            if (!isRecording) {
                Text(
                    text  = "Skip",
                    style = type.label.copy(
                        fontSize   = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = colors.accentDeep,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .clickable { onContinue(null) }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            } else {
                Spacer(modifier = Modifier.size(50.dp))
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        VoicePageRecorder(
            onSave = { clip ->
                scope.launch {
                    val row = runCatching {
                        repo.insert(
                            captureId  = captureId,
                            userId     = userId,
                            audioUri   = clip.uri,
                            durationMs = clip.durationMs,
                        )
                    }.getOrNull()
                    if (row != null) {
                        // Hand the transcription off to the process-
                        // lifecycle scope so it isn't cancelled when
                        // this composable unmounts as the parent
                        // advances to the transcript-edit pane.
                        val pendingUserId = userId
                        backgroundScope.launch(Dispatchers.IO) {
                            val result = runCatching {
                                SpeechTranscriber.transcribe(
                                    context = context,
                                    fileUri = clip.uri,
                                    userId  = pendingUserId,
                                )
                            }.getOrNull()
                            if (result is TranscribeResult.Success) {
                                runCatching {
                                    repo.setTranscription(row.id, result.text, result.source)
                                }
                            }
                        }
                    }
                    onContinue(row?.id)
                }
            },
            onCancel = { /* recorder cancelled — stay on the pane */ },
            modifier = Modifier.padding(horizontal = QuickInkSpacing.s4),
            engine = engine,
        )

        Spacer(modifier = Modifier.weight(1f))

        // Bottom bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start  = AppSpacing.s5,
                    end    = AppSpacing.s5,
                    bottom = AppSpacing.s5,
                ),
        ) {
            if (isRecording) {
                // Single full-width Cancel — drops the in-progress
                // clip and returns to idle. The recorder's own stop
                // pill is the way to commit the take and advance.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.White.copy(alpha = 0.85f))
                        .border(
                            width = 1.dp,
                            color = colors.border,
                            shape = RoundedCornerShape(999.dp),
                        )
                        .clickable { engine.cancel() }
                        .padding(vertical = AppSpacing.s3),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text  = "Cancel",
                        style = type.body.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.ink,
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(999.dp))
                        .background(colors.accent)
                        .clickable { onContinue(null) }
                        .padding(vertical = AppSpacing.s3),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text  = "Continue to review",
                        style = type.body.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.textOnAccent,
                    )
                }
            }
        }
    }
}
