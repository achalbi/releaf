/*
 * VoiceNoteSection.kt
 *
 * Voice notes panel for the document detail screen. Renders the list
 * of voice notes attached to the current capture, a CTA that opens
 * `VoicePageRecorder` in a modal bottom sheet, and per-card playback
 * with a play button, deterministic waveform cursor, current/total
 * timestamps, and a delete affordance. Transcription is offered per
 * card; the recognized text shows inline under the waveform.
 *
 * Mirror of iOS `VoiceNoteSection.swift` — same shape, swapped for
 * Compose primitives + the QuickInk CompositionLocal theme.
 */

package app.quickink.mobile.features.scan

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import app.quickink.mobile.QuickInkApp
import app.quickink.mobile.data.capture.CaptureRepository
import app.quickink.mobile.data.voicenote.SpeechTranscriber
import app.quickink.mobile.data.voicenote.TranscribeResult
import app.quickink.mobile.data.voicenote.WhisperModel
import app.quickink.mobile.data.voicenote.VoiceNoteEntity
import app.quickink.mobile.data.voicenote.VoiceNoteRepository
import app.quickink.mobile.data.voicenote.WaveformSamples
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkRadius
import app.quickink.mobile.ui.theme.QuickInkSpacing
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.core.content.FileProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.max
import kotlin.math.min

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceNoteSection(
    captureId: String,
    userId: String,
    /** Fires after a Copy-to-notes tap or the transcript editor's
     *  save appends to `captures.notes`. The parent re-reads its
     *  capture state so the Notes card refreshes — without this the
     *  column updates but the in-screen Notes card keeps the stale
     *  value until a screen revisit. */
    onNotesChanged: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val app = context.applicationContext as QuickInkApp
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    val scope = rememberCoroutineScope()

    val voiceNoteDao = remember(app) { app.database.voiceNoteDao() }
    val captureDao = remember(app) { app.database.captureDao() }
    val repository = remember(voiceNoteDao) { VoiceNoteRepository(voiceNoteDao) }
    val captureRepository = remember(captureDao, app) {
        CaptureRepository(
            captureDao    = captureDao,
            ocrResultDao  = app.database.ocrResultDao(),
            tagDao        = app.database.tagDao(),
            captureTagDao = app.database.captureTagDao(),
        )
    }

    val notes by remember(captureId, voiceNoteDao) {
        voiceNoteDao.observeForCapture(captureId)
    }.collectAsState(initial = emptyList())

    var showRecorder by remember { mutableStateOf(false) }
    // Per-card transcript editor — when non-null, the section
    // renders an editor sheet pre-filled with this note's transcript.
    // Tapping Edit on any card sets it; Save / Cancel clears it.
    var editingNote by remember { mutableStateOf<VoiceNoteEntity?>(null) }
    // Three-stage sheet lifecycle. `Recording` opens with the
    // VoicePageRecorder. After the user saves the clip, the flow
    // advances to `Transcribing` (spinner) and then either
    // `Editing(text, noteId)` on success or back to dismiss on
    // failure (matching the "skip the editor, just save the clip"
    // preference for failure paths).
    var stage by remember(showRecorder) { mutableStateOf<RecorderStage>(RecorderStage.Recording) }
    val transcribingIds = remember { mutableStateMapOf<String, Boolean>() }
    val unavailable = remember { mutableStateMapOf<String, String>() }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(QuickInkRadius.md))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.md)),
    ) {
        // Heading on a soft grey strip — matches Details / Notes.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier          = Modifier
                .fillMaxWidth()
                .background(colors.borderSoft)
                .padding(horizontal = QuickInkSpacing.s3, vertical = QuickInkSpacing.s2),
        ) {
            Icon(
                imageVector = Icons.Filled.Mic,
                contentDescription = null,
                tint = colors.inkSoft,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.size(QuickInkSpacing.s2))
            Text(
                text = "Voice notes",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.ink,
            )
            if (notes.isNotEmpty()) {
                Spacer(Modifier.size(QuickInkSpacing.s1))
                Text(
                    text = "· ${notes.size}",
                    style = type.caption,
                    color = colors.muted,
                )
            }
        }

        Column(
            modifier            = Modifier.padding(QuickInkSpacing.s3),
            verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
        ) {
        // Snapshot of which Whisper variants are on disk right now —
        // drives the per-card "Re-transcribe with…" picker. Refreshes
        // on every recomposition (cheap: 3 file-exists per variant).
        val availableModels = remember(notes.size) {
            SpeechTranscriber.availableModels(context)
        }
        notes.forEach { note ->
            VoiceNoteCard(
                note = note,
                isTranscribing = transcribingIds[note.id] == true,
                unavailableReason = unavailable[note.id],
                availableModels = availableModels,
                onTranscribe = { modelOverride ->
                    scope.launch {
                        runTranscribe(
                            context       = context,
                            repository    = repository,
                            note          = note,
                            transcribing  = transcribingIds,
                            unavailable   = unavailable,
                            modelOverride = modelOverride,
                        )
                    }
                },
                onCopyToNotes = {
                    scope.launch {
                        val text = note.transcription?.trim().orEmpty()
                        if (text.isNotEmpty()) {
                            runCatching { captureRepository.appendNote(captureId, text) }
                                .onSuccess { onNotesChanged() }
                        }
                    }
                },
                onEditTranscript = { editingNote = note },
                onShareAudio = { shareVoiceNote(context, note) },
                onDelete = {
                    scope.launch {
                        val deleted = runCatching { repository.softDelete(note.id) }.isSuccess
                        if (deleted) app.refreshPendingPushState()
                    }
                },
            )
        }

        if (notes.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = QuickInkSpacing.s3),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
            ) {
                Text(
                    text = "No voice notes yet",
                    style = type.caption,
                    color = colors.inkSoft,
                )
                Text(
                    text = "Tap Record to add audio context for this scan.",
                    style = type.caption,
                    color = colors.muted,
                )
            }
        }

        // Record CTA
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(QuickInkRadius.pill))
                .background(colors.accent)
                .clickable { showRecorder = true }
                .padding(horizontal = QuickInkSpacing.s4, vertical = QuickInkSpacing.s2),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Mic,
                    contentDescription = null,
                    tint = colors.textOnAccent,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.size(QuickInkSpacing.s2))
                Text(
                    text = if (notes.isEmpty()) "Record a voice note" else "Record another",
                    style = type.caption,
                    color = colors.textOnAccent,
                )
            }
        }
        }
    }

    if (showRecorder) {
        ModalBottomSheet(
            onDismissRequest = { showRecorder = false },
            sheetState = sheetState,
            containerColor = colors.bg,
        ) {
            when (val s = stage) {
                is RecorderStage.Recording -> {
                    VoicePageRecorder(
                        onSave = { clip ->
                            scope.launch {
                                val noteId = runCatching {
                                    repository.insert(
                                        captureId  = captureId,
                                        userId     = userId,
                                        audioUri   = clip.uri,
                                        durationMs = clip.durationMs,
                                    ).id
                                }.getOrNull()
                                if (noteId == null) {
                                    showRecorder = false
                                    return@launch
                                }
                                stage = RecorderStage.Transcribing
                                val result = SpeechTranscriber.transcribe(
                                    context = context,
                                    fileUri = clip.uri,
                                    userId  = userId,
                                )
                                when (result) {
                                    is TranscribeResult.Success -> {
                                        // Pre-save what the recognizer
                                        // heard so the card has something
                                        // to show if the user dismisses
                                        // the editor without saving.
                                        runCatching {
                                            repository.setTranscription(
                                                id     = noteId,
                                                text   = result.text,
                                                source = result.source,
                                            )
                                        }
                                        stage = RecorderStage.Editing(
                                            initialText = result.text,
                                            noteId      = noteId,
                                        )
                                    }
                                    is TranscribeResult.Failure -> {
                                        // Skip the editor; the clip
                                        // stays saved without a transcript.
                                        showRecorder = false
                                    }
                                }
                            }
                        },
                        onCancel = { showRecorder = false },
                    )
                }
                is RecorderStage.Transcribing -> TranscribingStage()
                is RecorderStage.Editing -> TranscriptEditor(
                    initialText = s.initialText,
                    onCancel    = { showRecorder = false },
                    onSave      = { edited ->
                        scope.launch {
                            val trimmed = edited.trim()
                            if (trimmed.isNotEmpty()) {
                                runCatching {
                                    repository.setTranscription(
                                        id     = s.noteId,
                                        text   = trimmed,
                                        source = SpeechTranscriber.BACKEND_SHERPA,
                                    )
                                }
                                runCatching {
                                    captureRepository.appendNote(captureId, trimmed)
                                }.onSuccess { onNotesChanged() }
                            }
                            showRecorder = false
                        }
                    },
                )
            }
        }
    }

    // In-card "Edit" pill — overwrites `voice_notes.transcription`
    // without touching the parent capture's notes (distinct from
    // the post-recording editor which also appends to notes).
    val editTarget = editingNote
    if (editTarget != null) {
        val editSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { editingNote = null },
            sheetState       = editSheetState,
            containerColor   = colors.bg,
        ) {
            TranscriptEditor(
                initialText = editTarget.transcription.orEmpty(),
                onCancel    = { editingNote = null },
                onSave      = { edited ->
                    scope.launch {
                        val trimmed = edited.trim()
                        runCatching {
                            repository.setTranscription(
                                id     = editTarget.id,
                                text   = if (trimmed.isEmpty()) null else trimmed,
                                source = if (trimmed.isEmpty()) null
                                         else SpeechTranscriber.BACKEND_SHERPA,
                            )
                        }
                    }
                    editingNote = null
                },
            )
        }
    }
}

private sealed interface RecorderStage {
    data object Recording    : RecorderStage
    data object Transcribing : RecorderStage
    data class Editing(val initialText: String, val noteId: String) : RecorderStage
}

@Composable
private fun TranscribingStage() {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = QuickInkSpacing.s8, horizontal = QuickInkSpacing.s5),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3),
    ) {
        CircularProgressIndicator(
            color = colors.accent,
            strokeWidth = 3.dp,
            modifier = Modifier.size(40.dp),
        )
        Text(
            text = "Transcribing voice note…",
            style = type.body,
            color = colors.ink,
        )
        Text(
            text = "This runs on-device. You can edit the text on the next screen.",
            style = type.caption,
            color = colors.muted,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun TranscriptEditor(
    initialText: String,
    onCancel: () -> Unit,
    onSave: (String) -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    var text by remember(initialText) { mutableStateOf(initialText) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = QuickInkSpacing.s4, vertical = QuickInkSpacing.s3),
        verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCancel) {
                Text(text = "Cancel", style = type.body, color = colors.inkSoft)
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = "Edit transcript",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.ink,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { onSave(text) }) {
                Text(
                    text = "Save",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.accent,
                )
            }
        }

        Text(
            text = "This will save with the voice note and add to the document's notes.",
            style = type.caption,
            color = colors.muted,
        )

        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor    = colors.borderSoft.copy(alpha = 0.4f),
                unfocusedContainerColor  = colors.borderSoft.copy(alpha = 0.4f),
                focusedTextColor         = colors.ink,
                unfocusedTextColor       = colors.ink,
                focusedIndicatorColor    = colors.border,
                unfocusedIndicatorColor  = colors.border,
                cursorColor              = colors.accent,
            ),
        )
    }
}

private suspend fun runTranscribe(
    context: Context,
    repository: VoiceNoteRepository,
    note: VoiceNoteEntity,
    transcribing: MutableMap<String, Boolean>,
    unavailable: MutableMap<String, String>,
    modelOverride: WhisperModel? = null,
) {
    if (transcribing[note.id] == true) return
    transcribing[note.id] = true
    try {
        val result = SpeechTranscriber.transcribe(
            context       = context,
            fileUri       = note.audioUri,
            userId        = note.userId,
            modelOverride = modelOverride,
        )
        when (result) {
            is TranscribeResult.Success -> {
                runCatching {
                    repository.setTranscription(
                        id     = note.id,
                        text   = result.text,
                        source = result.source,
                    )
                }
                unavailable.remove(note.id)
            }
            is TranscribeResult.Failure -> {
                unavailable[note.id] = result.reason
            }
        }
    } finally {
        transcribing[note.id] = false
    }
}

@Composable
private fun VoiceNoteCard(
    note: VoiceNoteEntity,
    isTranscribing: Boolean,
    unavailableReason: String?,
    /** Whisper variants that are already on disk — drives the per-card
     *  "Re-transcribe with…" picker. Empty / single-entry lists fall
     *  back to a plain pill that uses the global Settings pick. */
    availableModels: List<WhisperModel>,
    onTranscribe: (WhisperModel?) -> Unit,
    /** Append the current transcript to the parent capture's notes.
     *  Only rendered when [note.transcription] is non-empty. */
    onCopyToNotes: () -> Unit,
    /** Open the transcript editor for this card. The section handles
     *  the sheet + persistence so only one editor is alive at a time. */
    onEditTranscript: () -> Unit,
    onShareAudio: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current

    var player by remember(note.id) { mutableStateOf<MediaPlayer?>(null) }
    var isPlaying by remember(note.id) { mutableStateOf(false) }
    var positionMs by remember(note.id) { mutableLongStateOf(0L) }
    var amplitudes by remember(note.audioUri) { mutableStateOf<FloatArray?>(null) }
    var showDeleteConfirm by remember(note.id) { mutableStateOf(false) }

    LaunchedEffect(note.audioUri) {
        amplitudes = WaveformSamples.extract(note.audioUri, barCount = 40)
    }

    DisposableEffect(note.id) {
        onDispose {
            runCatching { player?.stop() }
            runCatching { player?.release() }
            player = null
        }
    }

    LaunchedEffect(isPlaying, player) {
        val mp = player ?: return@LaunchedEffect
        while (isActive && isPlaying) {
            positionMs = runCatching { mp.currentPosition.toLong() }.getOrDefault(0L)
            delay(100)
        }
    }

    val totalMs = note.durationMs.coerceAtLeast(1L)

    val togglePlay: () -> Unit = toggle@{
        val existing = player
        if (existing == null) {
            val mp = MediaPlayer()
            try {
                mp.setDataSource(context, Uri.parse(note.audioUri))
                mp.setOnCompletionListener {
                    isPlaying = false
                    positionMs = 0L
                    runCatching { mp.seekTo(0) }
                }
                mp.prepare()
                mp.start()
            } catch (_: Exception) {
                runCatching { mp.release() }
                Toast.makeText(context, "Couldn't play this voice note.", Toast.LENGTH_SHORT).show()
                return@toggle
            }
            player = mp
            isPlaying = true
        } else if (isPlaying) {
            runCatching { existing.pause() }
            isPlaying = false
        } else {
            runCatching { existing.start() }
            isPlaying = true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(QuickInkRadius.md))
            .background(colors.borderSoft.copy(alpha = 0.5f))
            .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.md))
            .padding(QuickInkSpacing.s3),
        verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(colors.accent)
                    .clickable(onClick = togglePlay),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = colors.textOnAccent,
                    modifier = Modifier.size(22.dp),
                )
            }

            Spacer(Modifier.size(QuickInkSpacing.s3))

            Column(
                modifier = Modifier
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s1),
            ) {
                VoiceWaveform(
                    seed          = note.id,
                    progress      = (positionMs.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f),
                    playedColor   = colors.accent,
                    unplayedColor = colors.muted,
                    amplitudes    = amplitudes,
                    modifier      = Modifier
                        .fillMaxWidth()
                        .height(24.dp),
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = formatDurationMs(positionMs),
                        style = type.caption,
                        color = colors.inkSoft,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = formatDurationMs(note.durationMs),
                        style = type.caption,
                        color = colors.inkSoft,
                    )
                }
            }

            Spacer(Modifier.size(QuickInkSpacing.s2))

            Column(
                verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s1),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                VoiceNoteIconButton(
                    icon = Icons.Outlined.Share,
                    contentDescription = "Share voice note",
                    onClick = onShareAudio,
                )
                VoiceNoteIconButton(
                    icon = Icons.Filled.Delete,
                    contentDescription = "Delete",
                    onClick = { showDeleteConfirm = true },
                )
            }
        }

        TranscriptStrip(
            transcript        = note.transcription,
            isPending         = isTranscribing,
            unavailableReason = unavailableReason,
            availableModels   = availableModels,
            onTranscribe      = onTranscribe,
            onCopyToNotes     = onCopyToNotes,
            onEditTranscript  = onEditTranscript,
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = {
                Text(
                    text = "Delete this voice note?",
                    style = type.body.copy(fontWeight = FontWeight.SemiBold, fontSize = 17.sp),
                    color = colors.ink,
                )
            },
            text = {
                Text(
                    text = "The audio and transcript will be removed from this scan. This can't be undone.",
                    style = type.meta,
                    color = colors.inkSoft,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onDelete()
                }) {
                    Text("Delete", color = colors.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel", color = colors.ink)
                }
            },
            containerColor = colors.surface,
        )
    }
}

@Composable
private fun VoiceNoteIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val colors = LocalQuickInkColors.current

    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(QuickInkRadius.sm))
            .background(colors.borderSoft)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = colors.inkSoft,
            modifier = Modifier.size(16.dp),
        )
    }
}

private fun shareVoiceNote(
    context: Context,
    note: VoiceNoteEntity,
) {
    val shareUri = shareableVoiceNoteUri(context, note.audioUri)
    if (shareUri == null) {
        Toast.makeText(
            context,
            "Voice note isn't available to share.",
            Toast.LENGTH_SHORT,
        ).show()
        return
    }

    val transcript = note.transcription?.trim().orEmpty()
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "audio/mp4"
        putExtra(Intent.EXTRA_STREAM, shareUri)
        if (transcript.isNotEmpty()) {
            putExtra(Intent.EXTRA_TEXT, transcript)
        }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = android.content.ClipData.newRawUri(null, shareUri)
    }

    try {
        context.startActivity(Intent.createChooser(intent, "Share voice note"))
    } catch (_: Exception) {
        Toast.makeText(
            context,
            "Couldn't open the share sheet for this voice note.",
            Toast.LENGTH_SHORT,
        ).show()
    }
}

private fun shareableVoiceNoteUri(context: Context, raw: String): Uri? {
    if (raw.isBlank()) return null
    val parsed = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
    return when (parsed.scheme) {
        "content" -> parsed
        "file" -> {
            val path = parsed.path ?: return null
            shareableVoiceNoteFileUri(context, File(path))
        }
        null -> shareableVoiceNoteFileUri(context, File(raw))
        else -> null
    }
}

private fun shareableVoiceNoteFileUri(context: Context, file: File): Uri? {
    if (!file.exists()) return null
    val authority = "${context.packageName}.fileprovider"
    return runCatching { FileProvider.getUriForFile(context, authority, file) }
        .getOrNull()
}

@Composable
private fun TranscriptStrip(
    transcript: String?,
    isPending: Boolean,
    unavailableReason: String?,
    availableModels: List<WhisperModel>,
    onTranscribe: (WhisperModel?) -> Unit,
    onCopyToNotes: () -> Unit,
    onEditTranscript: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    val hasTranscript = !transcript.isNullOrBlank()
    val hasReason = unavailableReason != null
    // Always surface the picker — every menu entry is a real choice
    // now that un-downloaded variants are listed with their fetch
    // size and an extra-tap confirm. The "Use default" entry stays
    // a one-tap path to the global Settings pick.
    val canPickModel = true
    // When a transcribe pass is in flight after a re-tap, keep the
    // existing transcript visible (so the user can compare) and
    // show the spinner inside the Try-again pill instead of blanking
    // the whole strip down to "Transcribing…". Only fall through to
    // the "Transcribing…" row when there's no prior transcript to
    // hold up.
    val keepTranscriptVisible = hasTranscript && isPending

    // Flips the Copy-to-notes pill to "Copied" briefly after a tap
    // so the action acknowledges without leaving a permanent badge.
    var didCopyToNotes by remember(transcript) { mutableStateOf(false) }
    LaunchedEffect(didCopyToNotes) {
        if (didCopyToNotes) {
            delay(1600)
            didCopyToNotes = false
        }
    }

    val eyebrow = when {
        hasTranscript -> "TRANSCRIPT"
        isPending     -> "TRANSCRIBING"
        hasReason     -> "TRANSCRIPT UNAVAILABLE"
        else          -> "TRANSCRIPT"
    }

    Column(verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s1)) {
        Text(text = eyebrow, style = type.eyebrow, color = colors.muted)

        when {
            keepTranscriptVisible -> {
                Text(
                    text = transcript!!,
                    style = type.caption,
                    color = colors.ink,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(QuickInkRadius.pill))
                            .background(colors.accentSoft)
                            .padding(horizontal = QuickInkSpacing.s2, vertical = 4.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier   = Modifier.size(12.dp),
                                color      = colors.accent,
                                strokeWidth = 1.5.dp,
                            )
                            Spacer(Modifier.size(QuickInkSpacing.s1))
                            Text(
                                text  = "Transcribing…",
                                style = type.caption,
                                color = colors.accent,
                            )
                        }
                    }
                }
            }
            hasTranscript -> {
                Text(
                    text = transcript!!,
                    style = type.caption,
                    color = colors.ink,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(QuickInkRadius.pill))
                            .background(colors.accentSoft)
                            .clickable(enabled = !didCopyToNotes) {
                                onCopyToNotes()
                                didCopyToNotes = true
                            }
                            .padding(horizontal = QuickInkSpacing.s2, vertical = 4.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (didCopyToNotes) Icons.Filled.Check else Icons.Outlined.ContentCopy,
                                contentDescription = null,
                                tint = colors.accent,
                                modifier = Modifier.size(12.dp),
                            )
                            Spacer(Modifier.size(QuickInkSpacing.s1))
                            Text(
                                text = if (didCopyToNotes) "Copied" else "Copy to notes",
                                style = type.caption,
                                color = colors.accent,
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(QuickInkRadius.pill))
                            .background(colors.accentSoft)
                            .clickable(onClick = onEditTranscript)
                            .padding(horizontal = QuickInkSpacing.s2, vertical = 4.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.Edit,
                                contentDescription = null,
                                tint = colors.accent,
                                modifier = Modifier.size(12.dp),
                            )
                            Spacer(Modifier.size(QuickInkSpacing.s1))
                            Text(
                                text = "Edit",
                                style = type.caption,
                                color = colors.accent,
                            )
                        }
                    }
                    TranscribePill(
                        label            = "Try again",
                        availableModels  = availableModels,
                        canPickModel     = canPickModel,
                        onTranscribe     = onTranscribe,
                    )
                }
            }
            isPending -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    color = colors.accent,
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.size(QuickInkSpacing.s2))
                Text(text = "Transcribing…", style = type.caption, color = colors.inkSoft)
            }
            hasReason -> {
                Text(text = unavailableReason!!, style = type.caption, color = colors.inkSoft)
                TranscribePill(
                    label            = "Retry",
                    availableModels  = availableModels,
                    canPickModel     = canPickModel,
                    onTranscribe     = onTranscribe,
                )
            }
            else -> {
                TranscribePill(
                    label            = "Transcribe",
                    leadingIcon      = Icons.Outlined.Subtitles,
                    availableModels  = availableModels,
                    canPickModel     = canPickModel,
                    onTranscribe     = onTranscribe,
                )
            }
        }
    }
}

/**
 * Coral pill that triggers transcription. The dropdown menu lists
 * all four Whisper variants regardless of whether they're already
 * on disk:
 *   - Downloaded entries tap-through directly to transcribe.
 *   - Un-downloaded entries show "~XXX MB download" inline and
 *     trip an `AlertDialog` confirm before the fetch kicks off, so
 *     the user never accidentally pulls 1.5 GB from a card-level
 *     affordance. The download itself rides the existing process-
 *     scope path (modal + progress bar at the MainShell root).
 *
 * "Use default" stays at the top of the menu so the global Settings
 * pick is always one tap away.
 *
 * The leading icon area also doubles as the in-flight indicator —
 * when [isPending] is true the pill becomes non-clickable and the
 * icon slot shows a small CircularProgressIndicator instead.
 */
@Composable
private fun TranscribePill(
    label: String,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    availableModels: List<WhisperModel>,
    canPickModel: Boolean,
    onTranscribe: (WhisperModel?) -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current
    var menuOpen by remember { mutableStateOf(false) }
    var pendingDownloadConfirm by remember { mutableStateOf<WhisperModel?>(null) }
    val downloadedIds = remember(availableModels) {
        availableModels.map { it.id }.toSet()
    }

    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(QuickInkRadius.pill))
                .background(colors.accentSoft)
                .clickable {
                    if (canPickModel) menuOpen = true
                    else onTranscribe(null)
                }
                .padding(horizontal = QuickInkSpacing.s2, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(12.dp),
                )
                Spacer(Modifier.size(QuickInkSpacing.s1))
            }
            Text(
                text  = label,
                style = type.caption,
                color = colors.accent,
            )
            if (canPickModel) {
                Spacer(Modifier.size(QuickInkSpacing.s1))
                Icon(
                    imageVector = Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(14.dp),
                )
            }
        }

        if (canPickModel) {
            DropdownMenu(
                expanded         = menuOpen,
                onDismissRequest = { menuOpen = false },
            ) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text  = "Use default",
                            style = type.body,
                            color = colors.ink,
                        )
                    },
                    onClick = {
                        menuOpen = false
                        onTranscribe(null)
                    },
                )
                for (model in WhisperModel.values()) {
                    val downloaded = model.id in downloadedIds
                    DropdownMenuItem(
                        text = {
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text  = model.displayName,
                                        style = type.body,
                                        color = colors.ink,
                                    )
                                    Spacer(Modifier.width(QuickInkSpacing.s2))
                                    val sizeTag = if (downloaded) {
                                        "~${formatWhisperSize(model.approxSizeMb)}"
                                    } else {
                                        "~${formatWhisperSize(model.approxSizeMb)} download"
                                    }
                                    Text(
                                        text  = sizeTag,
                                        style = type.meta,
                                        color = if (downloaded) colors.inkSoft else colors.accent,
                                    )
                                }
                                Text(
                                    text  = model.blurb,
                                    style = type.meta,
                                    color = colors.inkSoft,
                                )
                            }
                        },
                        onClick = {
                            menuOpen = false
                            if (downloaded) {
                                onTranscribe(model)
                            } else {
                                pendingDownloadConfirm = model
                            }
                        },
                    )
                }
            }
        }
    }

    val toConfirm = pendingDownloadConfirm
    if (toConfirm != null) {
        WhisperDownloadConfirmDialog(
            model     = toConfirm,
            onConfirm = {
                pendingDownloadConfirm = null
                onTranscribe(toConfirm)
            },
            onDismiss = { pendingDownloadConfirm = null },
        )
    }
}

@Composable
private fun WhisperDownloadConfirmDialog(
    model: WhisperModel,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Download ${model.displayName} model?") },
        text  = {
            Column(verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2)) {
                Text(
                    text = "One-time download (~${formatWhisperSize(model.approxSizeMb)}). " +
                        "We'll start it on this voice note and transcribe with " +
                        "${model.displayName} as soon as it finishes.",
                )
                Text(text = model.blurb)
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = "Download & transcribe")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "Cancel")
            }
        },
    )
}

private fun formatWhisperSize(approxMb: Int): String =
    if (approxMb < 1_000) "$approxMb MB"
    else "%.1f GB".format(approxMb / 1_000.0)

@Composable
private fun VoiceWaveform(
    seed: String,
    progress: Float,
    playedColor: Color,
    unplayedColor: Color,
    amplitudes: FloatArray?,
    modifier: Modifier = Modifier,
) {
    val barCount = 40
    val heights = remember(seed, amplitudes) {
        amplitudes ?: hashBasedHeights(seed, barCount)
    }

    Canvas(modifier = modifier) {
        val gap = 3f
        val barWidth = max(1f, (size.width - gap * (barCount - 1)) / barCount)
        val centerY = size.height / 2
        val progressX = size.width * progress
        var x = barWidth / 2
        for (i in 0 until barCount) {
            val level = if (i < heights.size) heights[i] else 0.5f
            val h = size.height * level
            val color = if (x <= progressX) playedColor else unplayedColor
            drawLine(
                color = color,
                start = Offset(x, centerY - h / 2),
                end   = Offset(x, centerY + h / 2),
                strokeWidth = barWidth,
                cap = StrokeCap.Round,
            )
            x += barWidth + gap
        }
    }
}

private fun hashBasedHeights(seed: String, count: Int): FloatArray {
    val base = seed.hashCode().toLong() and 0xFFFFFFFFL
    val out = FloatArray(count)
    for (i in 0 until count) {
        val mixed = base * 2_654_435_761L + i * 1_779_033_703L
        val unit = ((mixed and 0xFFFFFFFFL) % 10_000L) / 10_000.0f
        out[i] = 0.2f + unit * 0.8f
    }
    return out
}

internal fun formatDurationMs(ms: Long): String {
    val total = max(ms, 0L) / 1000L
    val m = total / 60
    val s = total % 60
    return "%d:%02d".format(m, s)
}
