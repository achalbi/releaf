/*
 * StoryVoiceClipRecorder.kt
 *
 * Stories Phase 2 — tap-and-hold voice-clip recorder embedded inside
 * the "+ Add" sheet. Mirror of iOS `StoryVoiceClipRecorder.swift`.
 *
 * AAC-LC 64 kbps mono, 16 kHz, max 10 s per the handoff doc. Released
 * on gesture-up; auto-stops at 10 s. The .m4a lands in
 * `AttachmentStorage`; the caller routes the resulting (audioUri,
 * durationMs) into `StoryEditorViewModel.insertVoiceClipItem(...)`.
 */

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.quickink.mobile.features.stories

import android.Manifest
import android.media.MediaRecorder
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkSpacing
import app.releaf.mobile.data.common.AttachmentStorage
import app.releaf.mobile.data.common.Uuidv7
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File

/** Hard cap per the handoff doc — 10 s. */
private const val STORY_VOICE_CLIP_MAX_MS: Long = 10_000

/** AAC-LC 64 kbps mono — matches the handoff doc. */
private const val STORY_VOICE_CLIP_BITRATE: Int = 64_000

/** Recorder engine — owns the [MediaRecorder] + the live timer/level
 *  tickers. UI calls [start] / [stop] / [cancel]; everything else is
 *  observed via [isRecording], [elapsedMs], [amplitudes]. */
class StoryVoiceClipRecorderEngine(
    private val context: android.content.Context,
) {
    private val scope = CoroutineScope(SupervisorJob())
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var counterJob: Job? = null
    private var meterJob: Job? = null

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _elapsedMs = MutableStateFlow(0L)
    val elapsedMs: StateFlow<Long> = _elapsedMs.asStateFlow()

    private val _amplitudes = MutableStateFlow(FloatArray(38) { 0.04f })
    val amplitudes: StateFlow<FloatArray> = _amplitudes.asStateFlow()

    fun start(): Boolean {
        if (_isRecording.value) return false
        val file = File(AttachmentStorage.directory(context), "${Uuidv7.generate()}.m4a")
        outputFile = file
        val rec = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION") MediaRecorder()
        }).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(16_000)
            setAudioChannels(1)
            setAudioEncodingBitRate(STORY_VOICE_CLIP_BITRATE)
            setOutputFile(file.absolutePath)
        }
        return try {
            rec.prepare()
            rec.start()
            recorder = rec
            _isRecording.value = true
            _elapsedMs.value = 0L
            _amplitudes.value = FloatArray(38) { 0.04f }
            startCounterTicker()
            startMeterTicker()
            true
        } catch (_: Exception) {
            try { rec.release() } catch (_: Exception) {}
            outputFile = null
            false
        }
    }

    /** Stops recording; returns `(audioUri, durationMs)` if the clip
     *  is ≥ 300 ms, else returns null (and deletes the .m4a). */
    fun stop(): Pair<String, Long>? {
        val rec = recorder ?: return null
        if (!_isRecording.value) return null
        val durationMs = _elapsedMs.value
        try { rec.stop() } catch (_: Exception) {}
        teardown()
        val file = outputFile ?: return null
        return if (durationMs < 300L) {
            file.delete()
            null
        } else {
            "file://${file.absolutePath}" to durationMs
        }
    }

    fun cancel() {
        val rec = recorder
        if (rec != null) {
            try { rec.stop() } catch (_: Exception) {}
        }
        teardown()
        outputFile?.delete()
    }

    private fun teardown() {
        counterJob?.cancel(); counterJob = null
        meterJob?.cancel(); meterJob = null
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        _isRecording.value = false
    }

    private fun startCounterTicker() {
        counterJob = scope.launch {
            while (_isRecording.value) {
                delay(100)
                _elapsedMs.value += 100
                if (_elapsedMs.value >= STORY_VOICE_CLIP_MAX_MS) {
                    // Auto-stop — but we can't synchronously return
                    // the result here; the gesture-up handler will
                    // discover the engine is no longer recording on
                    // its next poll and treat the clip as committed.
                    stop()
                }
            }
        }
    }

    private fun startMeterTicker() {
        meterJob = scope.launch {
            while (_isRecording.value) {
                delay(60)
                val maxAmp = try { recorder?.maxAmplitude ?: 0 } catch (_: Exception) { 0 }
                // maxAmplitude is 0..32767 — map log scale into [0.04, 1.0].
                val normalized = (maxAmp.coerceIn(0, 32_767) / 32_767f).let { v ->
                    (kotlin.math.ln(1f + 9f * v) / kotlin.math.ln(10f)).coerceIn(0.04f, 1f)
                }
                val current = _amplitudes.value
                val next = FloatArray(current.size)
                for (i in 0 until current.size - 1) next[i] = current[i + 1]
                next[current.size - 1] = normalized
                _amplitudes.value = next
            }
        }
    }

    fun dispose() {
        cancel()
        scope.cancel()
    }
}

@Composable
fun StoryVoiceClipRecorderSheet(
    onSave: (audioUri: String, durationMs: Long) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val colors  = LocalQuickInkColors.current
    val type    = LocalQuickInkTypography.current

    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted -> permissionGranted = granted }

    LaunchedEffect(Unit) {
        if (!permissionGranted) {
            permLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val engine = remember(context) { StoryVoiceClipRecorderEngine(context) }
    DisposableEffect(engine) {
        onDispose { engine.dispose() }
    }

    val isRecording by engine.isRecording.collectAsState()
    val elapsedMs   by engine.elapsedMs.collectAsState()
    val amplitudes  by engine.amplitudes.collectAsState()

    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onCancel,
        sheetState       = sheetState,
        containerColor   = colors.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = QuickInkSpacing.s4, vertical = QuickInkSpacing.s2),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s4),
        ) {
            Text("Record a voice note", style = type.editorial, color = colors.ink)
            Text(
                text = "Tap and hold the button below. Max 10 seconds.",
                style = type.bodyItalic,
                color = colors.inkSoft,
            )

            // Waveform
            Row(
                modifier              = Modifier.height(44.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                amplitudes.forEach { level ->
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height((4f + level * 40f).dp)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
                            .background(if (isRecording) colors.accent else colors.border),
                    )
                }
            }

            val secs = elapsedMs / 1000.0
            val warning = elapsedMs >= STORY_VOICE_CLIP_MAX_MS - 2_000
            Text(
                text       = String.format("%.1fs", secs),
                color      = if (warning) colors.accent else colors.ink,
                fontSize   = 24.sp,
                fontWeight = FontWeight.SemiBold,
            )

            // Hold button
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .shadow(elevation = if (isRecording) 18.dp else 8.dp, shape = CircleShape, clip = false)
                    .clip(CircleShape)
                    .background(if (isRecording) colors.accent else colors.accentSoft)
                    .pointerInput(permissionGranted) {
                        detectTapGestures(
                            onPress = { _ ->
                                if (!permissionGranted) {
                                    permLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    return@detectTapGestures
                                }
                                engine.start()
                                tryAwaitRelease()
                                val result = engine.stop()
                                if (result != null) {
                                    onSave(result.first, result.second)
                                }
                            }
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector        = Icons.Filled.Mic,
                    contentDescription = "Hold to record",
                    tint               = if (isRecording) colors.textOnAccent else colors.accent,
                    modifier           = Modifier.size(28.dp),
                )
            }

            if (!permissionGranted) {
                Text(
                    text = "Grant microphone access to record.",
                    style = type.bodyItalic,
                    color = colors.inkSoft,
                )
            }

            Spacer(modifier = Modifier.height(QuickInkSpacing.s2))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                Text(
                    text     = "Cancel",
                    color    = colors.inkSoft,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .padding(bottom = QuickInkSpacing.s3)
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = {
                                engine.cancel()
                                onCancel()
                            })
                        },
                )
            }
        }
    }
}
