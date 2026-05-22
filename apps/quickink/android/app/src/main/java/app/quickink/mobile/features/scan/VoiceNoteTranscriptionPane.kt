/*
 * VoiceNoteTranscriptionPane.kt
 *
 * Second phase of the scan capture flow. Mounted by
 * [ScanCaptureSurface] after [VoiceNoteCapturePane] reports a
 * just-recorded voice note. Shows the live transcript in an
 * editable text field while the background transcribe pass lands,
 * and exposes a single "Continue to review" CTA that saves any
 * edits before advancing to [ScanReviewScreen].
 *
 * Lifecycle:
 *   - Observes `voice_notes` for the in-flight capture and picks
 *     out the row by id. While `transcription` is null we render a
 *     "Transcribing your note…" placeholder; once it lands we seed
 *     the editor exactly once so subsequent user edits stick.
 *   - On Continue: if the user changed the text we call
 *     [VoiceNoteRepository.setTranscription] to persist the edit,
 *     then invoke `onContinue`.
 *   - Close in the header deletes the just-recorded voice note,
 *     then returns to the add-context recorder phase so the user
 *     can re-record before review.
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.quickink.mobile.QuickInkApp
import app.quickink.mobile.data.voicenote.VoiceNoteRepository
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkRadius
import app.quickink.mobile.ui.theme.QuickInkSpacing
import app.releaf.mobile.ui.theme.AppSpacing
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@Composable
fun VoiceNoteTranscriptionPane(
    captureId: String,
    voiceNoteId: String,
    showCancelButton: Boolean = true,
    placeContinueBelowEditor: Boolean = false,
    continueLabel: String = "Continue to review",
    onContinue: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors  = LocalQuickInkColors.current
    val type    = LocalQuickInkTypography.current
    val context = LocalContext.current
    val app     = context.applicationContext as QuickInkApp
    val repo    = remember(app) { VoiceNoteRepository(app.database.voiceNoteDao()) }
    val scope   = rememberCoroutineScope()

    // Observe just this row by filtering the per-capture flow.
    // Adding a dedicated `observeById` would be cleaner but isn't
    // worth a new DAO method for one call site.
    val rowFlow = remember(captureId, voiceNoteId) {
        repo.observeForCapture(captureId).map { rows ->
            rows.firstOrNull { it.id == voiceNoteId }
        }
    }
    val row by rowFlow.collectAsState(initial = null)

    var editedValue by remember(voiceNoteId) { mutableStateOf(TextFieldValue("")) }
    var seeded by remember(voiceNoteId) { mutableStateOf(false) }
    var userEdited by remember(voiceNoteId) { mutableStateOf(false) }
    var canceling by remember(voiceNoteId) { mutableStateOf(false) }

    // Seed the editor once when the transcript lands so the user's
    // subsequent edits aren't clobbered by later observation ticks
    // (e.g. if a sync push echoes the same value back).
    LaunchedEffect(row?.transcription) {
        val text = row?.transcription
        if (!seeded && !text.isNullOrEmpty() && !userEdited) {
            editedValue = TextFieldValue(
                text      = text,
                selection = TextRange(text.length),
            )
            seeded = true
        }
    }

    val transcribing = row?.transcription.isNullOrBlank() && !seeded

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
                    top    = QuickInkSpacing.s6,
                    bottom = QuickInkSpacing.s2,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showCancelButton) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.8f))
                        .clickable(enabled = !canceling) {
                            canceling = true
                            scope.launch {
                                runCatching { repo.softDelete(voiceNoteId) }
                                onCancel()
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector       = Icons.Filled.Close,
                        contentDescription = "Close",
                        tint              = colors.inkSoft,
                        modifier          = Modifier.size(14.dp),
                    )
                }
            } else {
                Spacer(modifier = Modifier.size(50.dp))
            }
            Spacer(modifier = Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text  = "VOICE NOTE",
                    style = type.label.copy(
                        fontSize     = 10.sp,
                        fontWeight   = FontWeight.Bold,
                        letterSpacing = 1.2.sp,
                    ),
                    color = colors.muted,
                )
                Text(
                    text  = "Review transcript",
                    style = type.body.copy(
                        fontSize   = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = colors.ink,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            // Match the capture pane's header layout (50dp gutter).
            Spacer(modifier = Modifier.size(50.dp))
        }

        // Body
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = QuickInkSpacing.s4)
                .padding(top = QuickInkSpacing.s3),
        ) {
            Text(
                text  = if (transcribing) "Transcribing your note…" else "Edit the transcript before sending it to review.",
                style = type.meta,
                color = colors.muted,
            )

            Spacer(modifier = Modifier.size(QuickInkSpacing.s3))

            val editorShape = RoundedCornerShape(QuickInkRadius.md)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(editorShape)
                    .background(Color.White.copy(alpha = 0.85f), editorShape)
                    .border(1.dp, colors.border, editorShape)
                    .padding(horizontal = QuickInkSpacing.s3, vertical = QuickInkSpacing.s3),
            ) {
                BasicTextField(
                    value         = editedValue,
                    onValueChange = {
                        editedValue = it
                        userEdited = true
                    },
                    textStyle     = type.body.copy(color = colors.ink, fontSize = 14.sp),
                    cursorBrush   = SolidColor(colors.accent),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                    ),
                    minLines = 8,
                    maxLines = 14,
                    modifier      = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp, max = 320.dp),
                    decorationBox = { inner ->
                        if (editedValue.text.isEmpty()) {
                            Text(
                                text  = if (transcribing) "Listening for your words…"
                                        else "No transcript was generated. You can type notes here.",
                                style = type.body.copy(fontSize = 14.sp),
                                color = colors.muted,
                            )
                        }
                        inner()
                    },
                )
            }
            if (placeContinueBelowEditor) {
                Spacer(modifier = Modifier.size(QuickInkSpacing.s3))
                ContinueReviewButton(
                    label   = continueLabel,
                    onClick = {
                        persistTranscriptAndContinue(
                            editedText  = editedValue.text,
                            original    = row?.transcription.orEmpty(),
                            voiceNoteId = voiceNoteId,
                            source      = row?.transcriptionSource ?: "manual",
                            repo        = repo,
                            scope       = scope,
                            onContinue  = onContinue,
                        )
                    },
                )
            }
        }

        if (!placeContinueBelowEditor) {
            Spacer(modifier = Modifier.weight(1f))

            // Bottom bar — single "Continue to review" CTA.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start  = AppSpacing.s5,
                        end    = AppSpacing.s5,
                        bottom = AppSpacing.s5,
                    ),
            ) {
                ContinueReviewButton(
                    label   = continueLabel,
                    onClick = {
                        persistTranscriptAndContinue(
                            editedText  = editedValue.text,
                            original    = row?.transcription.orEmpty(),
                            voiceNoteId = voiceNoteId,
                            source      = row?.transcriptionSource ?: "manual",
                            repo        = repo,
                            scope       = scope,
                            onContinue  = onContinue,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun ContinueReviewButton(
    label: String,
    onClick: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(999.dp))
            .background(colors.accent)
            .clickable(onClick = onClick)
            .padding(vertical = AppSpacing.s3),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text  = label,
            style = type.body.copy(fontWeight = FontWeight.SemiBold),
            color = colors.textOnAccent,
        )
    }
}

private fun persistTranscriptAndContinue(
    editedText: String,
    original: String,
    voiceNoteId: String,
    source: String,
    repo: VoiceNoteRepository,
    scope: kotlinx.coroutines.CoroutineScope,
    onContinue: () -> Unit,
) {
    if (editedText != original) {
        scope.launch {
            runCatching {
                repo.setTranscription(
                    id     = voiceNoteId,
                    text   = editedText.ifBlank { null },
                    // Preserve whichever backend produced the
                    // original draft; fall back to "manual" for a
                    // pure user-typed transcript.
                    source = source,
                )
            }
            onContinue()
        }
    } else {
        onContinue()
    }
}
