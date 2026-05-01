/*
 * VoicePageRecorder.kt
 *
 * Recording control for the page-detail Voice tab. Idle state shows
 * a halo disc with editorial copy; recording state expands into a
 * live waveform driven by MediaRecorder amplitude readings, a
 * progress arc that fills clockwise toward the 2:00 auto-save cap,
 * an mm:ss counter, and a slide-up-to-cancel gesture.
 *
 * Color: every coral surface reads from `LocalAccent` (via
 * `AppAccent.*`) so the active theme drives the disc, progress arc,
 * waveform fill, halos, and "today" pulse cues. Danger / cancel uses
 * a dedicated red distinct from any palette so "you're discarding
 * this" reads consistently across themes.
 *
 * Required permissions:
 *   - android.permission.RECORD_AUDIO
 */

package app.releaf.mobile.features.page

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import android.net.Uri
import app.releaf.mobile.data.common.AttachmentStorage
import app.releaf.mobile.data.common.Uuidv7
import app.releaf.mobile.ui.theme.AppAccent
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography
import androidx.compose.foundation.Canvas
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

private const val NUM_WAVE_BARS = 38
private const val MAX_DURATION_MS = 120_000L
private const val MIN_RECORDING_MS = 500L
private const val DRAG_TAP_THRESHOLD_DP = 6f
private const val CANCEL_THRESHOLD_DP = 70f

data class RecordedClip(val uri: String, val durationMs: Long)

@Composable
fun VoicePageRecorder(
    isEmpty: Boolean,
    onSave: (RecordedClip) -> Unit,
    onCancel: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val engine = remember { VoicePageRecorderEngine(context, scope) }

    DisposableEffect(engine) {
        onDispose { engine.cancel() }
    }

    val permLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) engine.start()
    }

    fun requestStart() {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) engine.start()
        else permLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    if (engine.isRecording) {
        RecordingStage(
            engine = engine,
            onStop = {
                val result = engine.stop()
                if (result != null) {
                    onSave(result)
                } else {
                    onCancel()
                }
            },
            onCancel = {
                engine.cancel()
                onCancel()
            },
            modifier = modifier,
        )
    } else {
        IdleStage(
            isEmpty = isEmpty,
            onTap = { requestStart() },
            modifier = modifier,
        )
    }
}

// MARK: - Idle stage

@Composable
private fun IdleStage(
    isEmpty: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Idle layout is identical on first record and every subsequent
    // record — the drawer is a focused capture surface, not a
    // dashboard of how many notes already exist (the parent
    // VoiceSection already shows the count). Keeping it consistent
    // means the muscle memory of "tap Record → see disc + headline
    // → tap disc" works the same way the second, third, tenth time.
    @Suppress("UNUSED_PARAMETER") val unused = isEmpty
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = AppSpacing.s5),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s4),
    ) {
        RecordDisc(onTap = onTap)
        Text(
            text = "Catch the thought before it goes.",
            fontSize = 19.sp,
            fontFamily = FontFamily.Serif,
            color = AppColors.TextPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 240.dp),
        )
        Text(
            text = "Tap to record. Up to two minutes per note.",
            style = AppTypography.Meta,
            color = AppColors.TextTertiary,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 260.dp),
        )
    }
}

@Composable
private fun RecordDisc(onTap: () -> Unit) {
    val accentPrimary = AppAccent.primary
    val accentSoft = AppAccent.soft
    val density = LocalDensity.current

    Box(
        modifier = Modifier
            .size(130.dp)
            .clickable(onClick = onTap),
        contentAlignment = Alignment.Center,
    ) {
        // Outer dashed halo
        Canvas(modifier = Modifier.size(130.dp)) {
            val stroke = with(density) { 0.7.dp.toPx() }
            drawCircle(
                color = accentPrimary.copy(alpha = 0.45f),
                radius = (size.minDimension - stroke) / 2f,
                center = Offset(size.width / 2f, size.height / 2f),
                style = Stroke(
                    width = stroke,
                    pathEffect = PathEffect.dashPathEffect(
                        floatArrayOf(with(density) { 3.dp.toPx() }, with(density) { 3.dp.toPx() })
                    ),
                ),
            )
        }
        // Soft pulse
        Box(
            modifier = Modifier
                .size(102.dp)
                .clip(CircleShape)
                .background(accentSoft),
        )
        // Coral disc
        Box(
            modifier = Modifier
                .size(74.dp)
                .clip(CircleShape)
                .background(accentPrimary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Mic,
                contentDescription = "Record",
                tint = CREAM,
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

// MARK: - Recording stage

@Composable
private fun RecordingStage(
    engine: VoicePageRecorderEngine,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragOffsetDp by remember { mutableStateOf(0f) }
    var isCancelHover by remember { mutableStateOf(false) }
    val isDragging by remember { derivedStateOf { dragOffsetDp > DRAG_TAP_THRESHOLD_DP || isCancelHover } }

    val accent = AppAccent.primary
    val accentDeep = AppAccent.deep
    val accentSoft = AppAccent.soft

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = AppSpacing.s4),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s4),
    ) {
        // Eyebrow with pulse
        RecordingEyebrow(isCancelling = isCancelHover, accentDeep = accentDeep)

        // Live waveform
        LiveWaveform(
            amplitudes = engine.amplitudes,
            color = if (isCancelHover) DANGER else accent,
        )

        // Counter
        Text(
            text = engine.formattedElapsed,
            fontSize = 38.sp,
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Normal,
            color = if (isCancelHover) DANGER else AppColors.TextPrimary,
        )

        // Stop button area with cancel zone
        StopButtonArea(
            engine = engine,
            dragOffsetDp = dragOffsetDp,
            isCancelHover = isCancelHover,
            isDragging = isDragging,
            onDragChange = { offsetDp, threshold ->
                dragOffsetDp = offsetDp
                isCancelHover = threshold
            },
            onTapStop = onStop,
            onSlideCancel = onCancel,
            accent = accent,
            accentSoft = accentSoft,
        )

        // Hint
        Text(
            text = "Tap to stop · swipe up to cancel · auto-saves at 2:00",
            fontSize = 11.5.sp,
            color = AppColors.TextTertiary,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .widthIn(max = 280.dp)
                .let { if (isDragging) it.alpha(0f) else it },
        )
    }
}

@Composable
private fun RecordingEyebrow(isCancelling: Boolean, accentDeep: Color) {
    val infinite = rememberInfiniteTransition(label = "rec-pulse")
    val pulse by infinite.animateFloat(
        initialValue = 1f, targetValue = 0.5f,
        animationSpec = infiniteRepeatable(animation = tween(950)),
        label = "rec-pulse-alpha"
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (isCancelling) DANGER else accentDeep)
                .alpha(pulse),
        )
        Text(
            text = if (isCancelling) "RELEASE TO CANCEL" else "RECORDING",
            style = AppTypography.Eyebrow,
            color = if (isCancelling) DANGER else accentDeep,
        )
    }
}

@Composable
private fun LiveWaveform(amplitudes: FloatArray, color: Color) {
    // Engine pushes smoothed amplitudes every 60ms — render directly
    // rather than per-bar animateFloatAsState (which would share a
    // state slot across forEach iterations and visually collapse all
    // bars onto the most-recent amplitude).
    //
    // Arrangement.SpaceBetween makes the bars span end-to-end across
    // the available width: first bar pinned to the left edge, last
    // bar pinned to the right edge, equal spacing between. With a
    // fixed bar count (38) the spacing scales naturally with the
    // sheet width instead of leaving slack on the right.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp)
            .padding(horizontal = AppSpacing.s3),
    ) {
        amplitudes.forEach { level ->
            val barHeight = max(4f, level * 76f)
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(barHeight.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color),
            )
        }
    }
}

@Composable
private fun StopButtonArea(
    engine: VoicePageRecorderEngine,
    dragOffsetDp: Float,
    isCancelHover: Boolean,
    isDragging: Boolean,
    onDragChange: (offsetDp: Float, threshold: Boolean) -> Unit,
    onTapStop: () -> Unit,
    onSlideCancel: () -> Unit,
    accent: Color,
    accentSoft: Color,
) {
    val density = LocalDensity.current
    val animatedOffsetDp by animateFloatAsState(
        targetValue = dragOffsetDp,
        animationSpec = if (isDragging) tween(0) else tween(280),
        label = "stop-offset"
    )

    // Sized to wrap-content — the previous fixed 130×130 frame
    // pushed the cancel zone above the stop button OUT of the visible
    // region (60dp + 100dp content vs 130dp frame), so the
    // "Swipe up to cancel" copy never appeared. Now the cancel zone
    // reserves its own 60dp slot regardless of state (alpha controls
    // visibility) and the column sizes to fit both.
    Column(
        modifier = Modifier.wrapContentSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Cancel zone — always reserves vertical space; alpha hides
        // it when not dragging so the layout doesn't jump on the
        // first drag pixel.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier
                .height(60.dp)
                .alpha(if (isDragging) 1f else 0f),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .let {
                        if (isCancelHover) it.background(DANGER)
                        else it.border(1.dp, AppColors.TextTertiary, CircleShape)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = null,
                    tint = if (isCancelHover) CREAM else AppColors.TextTertiary,
                    modifier = Modifier.size(14.dp),
                )
            }
            Text(
                text = if (isCancelHover) "Release to cancel" else "Swipe up to cancel",
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Medium,
                color = if (isCancelHover) DANGER else AppColors.TextTertiary,
            )
        }

        // Stop button — draggable
        Box(
            modifier = Modifier
                .size(100.dp)
                .offset(y = (-animatedOffsetDp).dp)
                .pointerInput(Unit) {
                    var dragStart = 0f
                    var totalDy = 0f
                    detectVerticalDragGestures(
                        onDragStart = {
                            dragStart = 0f
                            totalDy = 0f
                        },
                        onVerticalDrag = { _, dragAmount ->
                            // dragAmount is positive when moving DOWN.
                            // We want positive when moving UP, so negate.
                            totalDy += -dragAmount
                            val dyDp = with(density) { totalDy.toDp().value }
                            if (dyDp > DRAG_TAP_THRESHOLD_DP) {
                                val clamped = min(dyDp, CANCEL_THRESHOLD_DP * 1.4f)
                                val cancelling = dyDp >= CANCEL_THRESHOLD_DP
                                onDragChange(clamped, cancelling)
                            } else {
                                onDragChange(0f, false)
                            }
                        },
                        onDragEnd = {
                            val dyDp = with(density) { totalDy.toDp().value }
                            when {
                                dyDp >= CANCEL_THRESHOLD_DP -> onSlideCancel()
                                abs(dyDp) < DRAG_TAP_THRESHOLD_DP -> onTapStop()
                                else -> onDragChange(0f, false)
                            }
                        },
                        onDragCancel = {
                            onDragChange(0f, false)
                        },
                    )
                }
                .clickable(onClick = onTapStop),
            contentAlignment = Alignment.Center,
        ) {
            // Track + progress + halo + button stack
            StopButtonStack(
                progress = engine.progress,
                isCancelHover = isCancelHover,
                accent = accent,
                accentSoft = accentSoft,
            )
        }
    }
}

@Composable
private fun StopButtonStack(
    progress: Float,
    isCancelHover: Boolean,
    accent: Color,
    accentSoft: Color,
) {
    val density = LocalDensity.current
    val displayedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(180),
        label = "progress-fill"
    )

    val infinite = rememberInfiniteTransition(label = "stop-pulse")
    val pulseScale by infinite.animateFloat(
        initialValue = 1f, targetValue = 1.06f,
        animationSpec = infiniteRepeatable(animation = tween(1400)),
        label = "stop-pulse-scale"
    )

    Box(
        modifier = Modifier.size(100.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Dashed track + filled progress arc
        Canvas(modifier = Modifier.size(100.dp)) {
            val trackStroke = with(density) { 0.8.dp.toPx() }
            val fillStroke = with(density) { 2.5.dp.toPx() }
            val radius = (size.minDimension - fillStroke) / 2f
            val topLeft = Offset(
                (size.width - radius * 2) / 2f,
                (size.height - radius * 2) / 2f,
            )
            val arcSize = Size(radius * 2, radius * 2)

            // Dashed track
            drawArc(
                color = accent.copy(alpha = 0.35f),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(
                    width = trackStroke,
                    pathEffect = PathEffect.dashPathEffect(
                        floatArrayOf(with(density) { 3.dp.toPx() }, with(density) { 3.dp.toPx() })
                    ),
                ),
            )

            // Solid progress arc
            drawArc(
                color = if (isCancelHover) DANGER else accent,
                startAngle = -90f,
                sweepAngle = 360f * displayedProgress,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = fillStroke, cap = StrokeCap.Round),
            )
        }

        // Pulse halo
        Box(
            modifier = Modifier
                .size((84 * pulseScale).dp)
                .clip(CircleShape)
                .background(if (isCancelHover) DANGER_SOFT else accentSoft),
        )

        // Action button
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(if (isCancelHover) DANGER else accent),
            contentAlignment = Alignment.Center,
        ) {
            if (isCancelHover) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Cancel",
                    tint = CREAM,
                    modifier = Modifier.size(28.dp),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(CREAM),
                )
            }
        }
    }
}

// MARK: - Engine

class VoicePageRecorderEngine(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    var isRecording by mutableStateOf(false)
        private set
    var elapsedMs by mutableStateOf(0L)
        private set
    var amplitudes by mutableStateOf(FloatArray(NUM_WAVE_BARS) { 0.05f })
        private set

    val progress: Float
        get() = if (!isRecording) 0f
        else min(elapsedMs.toFloat() / MAX_DURATION_MS.toFloat(), 1f)

    val formattedElapsed: String
        get() {
            val total = elapsedMs / 1000
            val m = total / 60
            val s = total % 60
            return "%d:%02d".format(m, s)
        }

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startedAt: Long = 0
    private var counterJob: Job? = null
    private var meterJob: Job? = null

    fun start(): Boolean {
        if (isRecording) return false
        try {
            // Match the rest of the app — voice notes live in
            // AttachmentStorage so the existing transcription /
            // playback / export paths can find them by the standard
            // file:// URI without special-casing.
            val file = File(
                AttachmentStorage.directory(context),
                "${Uuidv7.generate()}.m4a",
            )

            val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            // Audio settings:
            //   - MIC source: raw mic without VOICE_RECOGNITION's
            //     noise-suppression + AGC chain, which flattens the
            //     dynamic range and makes playback feel quiet. MIC
            //     reads at the device's default mic gain, which is
            //     tuned for speech on every Android phone, so voice
            //     notes play back at a natural level.
            //   - 16 kHz: whisper's native sample rate. Transcription
            //     decodes the saved .m4a straight to PCM at this rate
            //     so we avoid a resampling pass that some devices
            //     fail outright on.
            //   - 96 kbps: more headroom than the legacy 64 kbps
            //     setting; soft consonants and breath stay intact
            //     instead of being compressed away.
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioSamplingRate(16_000)
            rec.setAudioEncodingBitRate(96_000)
            rec.setOutputFile(file.absolutePath)
            rec.prepare()
            rec.start()

            recorder = rec
            outputFile = file
            startedAt = System.currentTimeMillis()
            isRecording = true
            elapsedMs = 0
            amplitudes = FloatArray(NUM_WAVE_BARS) { 0.05f }
            startCounterTicker()
            startMeterTicker()
            return true
        } catch (e: Exception) {
            tearDown()
            return false
        }
    }

    fun stop(): RecordedClip? {
        val rec = recorder ?: return null.also { tearDown() }
        val file = outputFile ?: return null.also { tearDown() }
        if (!isRecording) return null.also { tearDown() }

        val duration = System.currentTimeMillis() - startedAt
        try {
            rec.stop()
        } catch (e: Exception) {
            // MediaRecorder.stop throws on too-short recordings
        }
        tearDown()

        if (!file.exists() || file.length() == 0L || duration < MIN_RECORDING_MS) {
            file.delete()
            return null
        }
        // Use Uri.fromFile so the resulting `file:///...` matches what
        // every other voice/photo/scan path in the app produces. Going
        // through java.io.File.toURI() yields a slightly different
        // serialisation that the SpeechTranscriber resolver may not
        // accept on every Android version.
        return RecordedClip(uri = Uri.fromFile(file).toString(), durationMs = duration)
    }

    fun cancel() {
        if (!isRecording) return
        try { recorder?.stop() } catch (_: Exception) {}
        outputFile?.delete()
        tearDown()
    }

    private fun startCounterTicker() {
        counterJob?.cancel()
        counterJob = scope.launch {
            while (isActive && isRecording) {
                elapsedMs = System.currentTimeMillis() - startedAt
                if (elapsedMs >= MAX_DURATION_MS) {
                    stop()
                    break
                }
                delay(100)
            }
        }
    }

    private fun startMeterTicker() {
        meterJob?.cancel()
        meterJob = scope.launch {
            while (isActive && isRecording) {
                val rec = recorder ?: break
                val raw = try { rec.maxAmplitude } catch (_: Exception) { 0 }
                val normalized = max(0.04f, min(1f, raw / 12000f))
                val next = FloatArray(NUM_WAVE_BARS)
                System.arraycopy(amplitudes, 1, next, 0, NUM_WAVE_BARS - 1)
                next[NUM_WAVE_BARS - 1] = normalized
                amplitudes = next
                delay(60)
            }
        }
    }

    private fun tearDown() {
        counterJob?.cancel()
        meterJob?.cancel()
        counterJob = null
        meterJob = null
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        outputFile = null
        startedAt = 0
        isRecording = false
        elapsedMs = 0
    }
}

// MARK: - Helpers

private val DANGER = Color(0xFFA32D2D)
private val DANGER_SOFT = Color(0xFFF4C9C0)
private val CREAM = Color(0xFFFBF8EC)
