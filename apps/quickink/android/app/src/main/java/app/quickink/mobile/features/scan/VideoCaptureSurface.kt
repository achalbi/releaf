/*
 * VideoCaptureSurface.kt
 *
 * Dedicated video-recording surface — sibling to
 * [PhotoCaptureSurface] (still photo only) and the rest of the
 * capture surfaces ([DocumentCaptureSurface],
 * [BusinessCardCaptureSurface]). Reached from the Sundial radial
 * menu's "Video" ray on the bottom-nav ⚡ FAB, which mounts
 * [QuickCaptureScreen] with `initialMode = CaptureMode.Video`.
 *
 * Shutter gesture (per modern OS-camera convention):
 *
 *   - Tap (idle)        → start recording. Haptic + the shutter
 *                         button swaps from coral video icon to
 *                         red stop-square.
 *   - Tap (recording)   → stop recording. Same button position;
 *                         the state-swap reads as one toggle.
 *
 * A hard 2:00 cap is enforced by
 * [PendingRecording.withDurationLimitMillis] so the user can walk
 * away without blowing up a transcription pass downstream. The
 * same cap is the reason the elapsed-time chip in the live
 * overlay clamps at 2:00 — keeps the on-screen counter honest
 * with what the file will actually contain.
 *
 * Tap-to-toggle replaces the old "press-and-hold to record" idiom
 * that used to live on [PhotoCaptureSurface]. The Sundial menu
 * gives users a dedicated "Video" button, so the dual gesture
 * stopped paying for itself — separate surfaces with a single
 * unambiguous tap each is the simpler model.
 *
 * A small flip-camera button sits at the trailing edge of the
 * shutter row, next to the shutter itself. It is only shown in
 * the idle Preview state — hidden while a recording or post-
 * record processing pass is in flight, since tearing down the
 * [VideoCapture] use case mid-take would corrupt the in-flight
 * `.mp4` and stop the recording.
 *
 * A collapse-by-default chip strip sits to the left of the
 * shutter and lets the user pick one of four color filters
 * (None / B&W / Sepia / Cool) — same [PhotoFilter] enum the
 * still surface uses. Collapsed → only the active chip is
 * visible. Tap → the column expands upward into the preview
 * area with all chips; each chip carries a live mini-preview of
 * the camera with that filter pre-applied. Tap a chip → it's
 * selected and the strip collapses again.
 *
 * The filter drives the main live preview (via a Compose
 * [Canvas] overlay with an HSL blend mode against the COMPATIBLE-
 * mode [PreviewView]'s TextureView pixels) AND is baked into the
 * recorded video via a Media3 [Transformer] post-process with an
 * [RgbMatrix] effect — audio passes through untouched. The chip
 * strip is hidden while recording so the user can't change
 * filter mid-take.
 *
 * After capture, the surface lands on the standard Retake / OK
 * captured-preview UI: the first frame of the recorded video
 * serves as the still preview, with a "With audio" badge in the
 * top-leading corner when the audio track survived. OK writes
 * the first-frame JPEG via [buildImportArtifacts], fires
 * `controller.onScanComplete(source="video", paperSize=Custom)`
 * (videos share the photo source-tag — the first frame is still
 * just an arbitrary phone-camera frame whose aspect ratio tells
 * us nothing about A4 / Letter / card bands), and inserts the
 * extracted audio as a voice note against the freshly-created
 * captureId. The voice-note pane downstream auto-advances when
 * it sees the pre-attached row.
 *
 * Mirror of iOS `VideoCaptureSurface.swift`.
 */

package app.quickink.mobile.features.scan

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix as ComposeColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.RgbMatrix
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import app.quickink.mobile.QuickInkApp
import app.quickink.mobile.data.capture.CaptureRepository
import app.quickink.mobile.data.voicenote.SpeechTranscriber
import app.quickink.mobile.data.voicenote.TranscribeResult
import app.quickink.mobile.data.voicenote.VoiceNoteRepository
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkRadius
import app.quickink.mobile.ui.theme.QuickInkSpacing
import app.releaf.mobile.data.common.AttachmentStorage
import app.releaf.mobile.data.common.Uuidv7
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import java.io.File
import java.nio.ByteBuffer

/** Hard cap on a single recording. */
private const val VIDEO_MAX_RECORDING_MS: Long = 120_000L
private const val VIDEO_QUALITY_PREFS_NAME = "quickink.settings"
private const val VIDEO_QUALITY_PREF_KEY = "video_capture_quality"

@Composable
internal fun VideoCaptureSurface(
    controller: ScanFlowController,
    userId: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        hasCameraPermission = results[Manifest.permission.CAMERA] == true
        // Mic result is informational — recording still produces a
        // video-only .mp4 when denied; voice-note transcription
        // simply doesn't fire downstream.
    }

    LaunchedEffect(Unit) {
        // Request CAMERA + RECORD_AUDIO together so the recording
        // path captures audio for the voice-note transcription
        // pipeline. CAMERA gates the surface entirely;
        // RECORD_AUDIO is opportunistic — denying it still lets
        // the user record silent video.
        val needsCamera = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA,
        ) != PackageManager.PERMISSION_GRANTED
        val needsMic = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO,
        ) != PackageManager.PERMISSION_GRANTED
        val toRequest = buildList {
            if (needsCamera) add(Manifest.permission.CAMERA)
            if (needsMic) add(Manifest.permission.RECORD_AUDIO)
        }
        if (toRequest.isNotEmpty()) {
            permissionLauncher.launch(toRequest.toTypedArray())
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        if (hasCameraPermission) {
            ActiveVideoSurface(controller = controller, userId = userId, onDismiss = onDismiss)
        } else {
            VideoPermissionRationale(
                onRequest = {
                    permissionLauncher.launch(
                        arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
                    )
                },
            )
        }
    }
}

/** Holder so callbacks can pin the latest use-case instances. */
private class VideoCameraXBindings {
    @Volatile var videoCapture: VideoCapture<Recorder>? = null
    @Volatile var recorder: Recorder? = null
    /**
     * The provider itself — stashed so the surface's dispose
     * handler can call `unbindAll()` directly.
     * [bindToLifecycle] only auto-releases when the supplied
     * [androidx.lifecycle.LifecycleOwner] transitions to STOPPED;
     * navigating back inside the same Activity doesn't move the
     * Activity's lifecycle, so without an explicit unbind the
     * camera stays hot in the background.
     */
    @Volatile var cameraProvider: ProcessCameraProvider? = null
}

/** Captured artifact returned to the host after recording. */
private data class CapturedVideoBuffer(
    val image: Bitmap,
    val file: File,
    val videoFile: File,
    val audioFile: File?,
    val durationMs: Long,
)

/** Discriminated UI states for the active video surface. */
private sealed interface VideoUiState {
    data object Preview : VideoUiState
    data class Recording(val elapsedMs: Long) : VideoUiState
    data object Processing : VideoUiState
    data class Captured(val buffer: CapturedVideoBuffer) : VideoUiState
}

private enum class VideoCaptureQuality(
    val id: String,
    val label: String,
    val cameraXOrder: List<Quality>,
) {
    Auto("auto", "Auto", listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD, Quality.LOWEST)),
    Uhd("uhd", "4K", listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD, Quality.LOWEST)),
    Fhd("fhd", "1080", listOf(Quality.FHD, Quality.HD, Quality.SD, Quality.LOWEST)),
    Hd("hd", "720", listOf(Quality.HD, Quality.SD, Quality.LOWEST)),
    Sd("sd", "SD", listOf(Quality.SD, Quality.LOWEST));

    companion object {
        fun fromId(id: String?): VideoCaptureQuality =
            entries.firstOrNull { it.id == id } ?: Auto
    }
}

@Composable
private fun ActiveVideoSurface(
    controller: ScanFlowController,
    userId: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = LocalView.current
    val uiScope = rememberCoroutineScope()

    val processingScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    // Long-lived for the fire-and-forget transcription that
    // outlives the surface's onDispose.
    val backgroundScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    val bindings = remember { VideoCameraXBindings() }
    val recorderExecutor = remember { ContextCompat.getMainExecutor(context) }
    val app = context.applicationContext as QuickInkApp
    val repo = remember(app) { VoiceNoteRepository(app.database.voiceNoteDao()) }
    val captureRepo = remember(app) {
        CaptureRepository(
            captureDao    = app.database.captureDao(),
            ocrResultDao  = app.database.ocrResultDao(),
            tagDao        = app.database.tagDao(),
            captureTagDao = app.database.captureTagDao(),
        )
    }
    val qualityPrefs = remember(context) {
        context.applicationContext.getSharedPreferences(VIDEO_QUALITY_PREFS_NAME, Context.MODE_PRIVATE)
    }

    var uiState by remember { mutableStateOf<VideoUiState>(VideoUiState.Preview) }
    var commitError by remember { mutableStateOf<String?>(null) }
    var isCommitting by remember { mutableStateOf(false) }
    var activeRecording by remember { mutableStateOf<Recording?>(null) }
    var pendingVideoFile by remember { mutableStateOf<File?>(null) }
    var recordingStartedAtElapsed by remember { mutableLongStateOf(0L) }

    // Active camera. Hoisted out of `bindCameraXForVideo` so the
    // LaunchedEffect below can re-bind the video use case against
    // a different selector when the user taps the flip button.
    var cameraSelector by remember { mutableStateOf(CameraSelector.DEFAULT_BACK_CAMERA) }
    // PreviewView is created by AndroidView's factory; we hold a
    // ref so the LaunchedEffect can pass it into
    // `bindCameraXForVideo` outside the factory closure. Nulled in
    // `onRelease` so a remount (e.g. uiState going Preview →
    // Captured → Preview) gets a fresh bind against the new
    // PreviewView instance.
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }
    // Selected color filter. Resets to None on every fresh mount
    // — we don't persist across sessions since a filter that
    // silently survived to the next launch could surprise the
    // user.
    var activeFilter by remember { mutableStateOf(PhotoFilter.None) }
    var isFilterStripExpanded by remember { mutableStateOf(false) }
    var activeQuality by remember {
        mutableStateOf(VideoCaptureQuality.fromId(qualityPrefs.getString(VIDEO_QUALITY_PREF_KEY, null)))
    }
    var isQualityStripExpanded by remember { mutableStateOf(false) }
    // Latest 96x96 RGBA bitmap from the live PreviewView's
    // TextureView, polled while the strip is expanded so each
    // chip can show a real-time mini-preview with its filter
    // applied. `null` when collapsed / before the first poll.
    var thumbnailBitmap by remember { mutableStateOf<Bitmap?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            // Stop any in-flight recording before tearing down so
            // the executor isn't disposed mid-write.
            activeRecording?.stop()
            // Release the hardware camera. Explicit unbind frees
            // the surface immediately even when the host activity
            // stays in RESUMED.
            bindings.cameraProvider?.unbindAll()
            processingScope.cancel()
            // backgroundScope intentionally NOT cancelled — the
            // transcription job needs to outlive the surface.
            (uiState as? VideoUiState.Captured)?.let { st ->
                st.buffer.audioFile?.delete()
                st.buffer.videoFile.delete()
                st.buffer.file.delete()
            }
            pendingVideoFile?.delete()
        }
    }

    // Recording ticker — 250ms cadence so the on-screen elapsed
    // chip ticks smoothly. Re-derives the elapsed value from
    // SystemClock.elapsedRealtime so backgrounding pauses cleanly.
    LaunchedEffect(uiState) {
        if (uiState !is VideoUiState.Recording) return@LaunchedEffect
        while (isActive && uiState is VideoUiState.Recording) {
            val elapsed = SystemClock.elapsedRealtime() - recordingStartedAtElapsed
            uiState = VideoUiState.Recording(elapsedMs = elapsed.coerceAtMost(VIDEO_MAX_RECORDING_MS))
            delay(250)
        }
    }

    LaunchedEffect(previewViewRef, cameraSelector, activeQuality) {
        val pv = previewViewRef ?: return@LaunchedEffect
        bindCameraXForVideo(
            context        = context,
            previewView    = pv,
            lifecycleOwner = lifecycleOwner,
            bindings       = bindings,
            cameraSelector = cameraSelector,
            quality        = activeQuality,
        )
    }

    LaunchedEffect(isFilterStripExpanded, previewViewRef) {
        if (!isFilterStripExpanded) {
            thumbnailBitmap = null
            return@LaunchedEffect
        }
        val pv = previewViewRef ?: return@LaunchedEffect
        while (isActive && isFilterStripExpanded) {
            val tv = findTextureView(pv)
            if (tv != null) {
                val frame = runCatching { tv.getBitmap(96, 96) }.getOrNull()
                if (frame != null) {
                    thumbnailBitmap = frame
                }
            }
            delay(200)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            val state = uiState
            when (state) {
                is VideoUiState.Captured -> {
                    Image(
                        bitmap             = state.buffer.image.asImageBitmap(),
                        contentDescription = "Captured video first frame",
                        modifier           = Modifier.fillMaxSize().background(Color.Black),
                    )
                    if (state.buffer.audioFile != null) {
                        AudioBadge(modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(QuickInkSpacing.s4))
                    }
                }
                else -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
                    ) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                val pv = PreviewView(ctx).apply {
                                    scaleType = PreviewView.ScaleType.FILL_CENTER
                                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                                }
                                previewViewRef = pv
                                pv
                            },
                            onRelease = { previewViewRef = null },
                        )
                        val showFilter = activeFilter != PhotoFilter.None
                        if (showFilter) {
                            val color = activeFilter.overlayColor
                            val blend = activeFilter.overlayBlendMode
                            if (color != null) {
                                Canvas(modifier = Modifier.fillMaxSize()) {
                                    drawRect(color = color, blendMode = blend)
                                }
                            }
                        }
                    }
                    if (state is VideoUiState.Recording) {
                        RecordingTimeChip(
                            elapsedMs = state.elapsedMs,
                            modifier  = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = QuickInkSpacing.s5),
                        )
                    }
                    if (state is VideoUiState.Preview) {
                        VideoQualityStrip(
                            isExpanded    = isQualityStripExpanded,
                            activeQuality = activeQuality,
                            onToggleExpand = {
                                isQualityStripExpanded = !isQualityStripExpanded
                            },
                            onSelect = { picked ->
                                activeQuality = picked
                                isQualityStripExpanded = false
                                qualityPrefs.edit()
                                    .putString(VIDEO_QUALITY_PREF_KEY, picked.id)
                                    .apply()
                            },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = QuickInkSpacing.s5, end = QuickInkSpacing.s4),
                        )
                    }
                    if (state is VideoUiState.Processing) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.25f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(color = Color.White)
                        }
                    }
                }
            }
        }

        val captured = uiState as? VideoUiState.Captured
        if (captured == null) {
            VideoShutterRow(
                uiState = uiState,
                onShutterTap = onShutterTap@{
                    when (uiState) {
                        VideoUiState.Preview -> {
                            val videoCapture = bindings.videoCapture ?: return@onShutterTap
                            val outputDir = File(context.cacheDir, "video_capture").apply { mkdirs() }
                            val outputFile = File(outputDir, "buffer-${System.currentTimeMillis()}.mp4")
                            outputFile.delete()
                            pendingVideoFile = outputFile
                            val outputOptions = FileOutputOptions.Builder(outputFile)
                                .setDurationLimitMillis(VIDEO_MAX_RECORDING_MS)
                                .build()
                            val hasMic = ContextCompat.checkSelfPermission(
                                context, Manifest.permission.RECORD_AUDIO,
                            ) == PackageManager.PERMISSION_GRANTED
                            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                            CaptureAnalytics.manualFired(CaptureMode.Video)
                            val recording = videoCapture.output
                                .prepareRecording(context, outputOptions)
                                .apply { if (hasMic) withAudioEnabled() }
                                .start(recorderExecutor) { event ->
                                    when (event) {
                                        is VideoRecordEvent.Start -> {
                                            recordingStartedAtElapsed = SystemClock.elapsedRealtime()
                                            uiState = VideoUiState.Recording(elapsedMs = 0)
                                            android.util.Log.i("VideoCapture", "recording started")
                                        }
                                        is VideoRecordEvent.Finalize -> {
                                            val file = pendingVideoFile
                                            pendingVideoFile = null
                                            activeRecording = null
                                            val isDurationLimit = event.error ==
                                                VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED
                                            if (event.hasError() && !isDurationLimit) {
                                                android.util.Log.w(
                                                    "VideoCapture",
                                                    "recording failed: ${event.error}",
                                                )
                                                file?.delete()
                                                uiState = VideoUiState.Preview
                                                return@start
                                            }
                                            if (file == null || !file.exists() || file.length() == 0L) {
                                                android.util.Log.w(
                                                    "VideoCapture",
                                                    "Finalize produced no usable file " +
                                                        "(exists=${file?.exists()}, size=${file?.length()})",
                                                )
                                                uiState = VideoUiState.Preview
                                                return@start
                                            }
                                            android.util.Log.i(
                                                "VideoCapture",
                                                "recording finalized — ${file.length()}B at ${file.absolutePath}",
                                            )
                                            uiState = VideoUiState.Processing
                                            val filterAtFinalize = activeFilter
                                            processingScope.launch {
                                                val processed = withContext(Dispatchers.IO) {
                                                    processVideoCaptureBuffer(context, file)
                                                }
                                                if (processed == null) {
                                                    file.delete()
                                                    withContext(Dispatchers.Main) {
                                                        uiState = VideoUiState.Preview
                                                    }
                                                    return@launch
                                                }
                                                val finalBuffer = if (filterAtFinalize != PhotoFilter.None) {
                                                    val filteredVideoFile = File(
                                                        processed.videoFile.parentFile,
                                                        "filtered-${processed.videoFile.name}",
                                                    )
                                                    filteredVideoFile.delete()
                                                    val ok = applyFilterToCapturedVideo(
                                                        context    = context,
                                                        sourceFile = processed.videoFile,
                                                        filter     = filterAtFinalize,
                                                        outputFile = filteredVideoFile,
                                                    )
                                                    val finalVideo = if (ok) {
                                                        processed.videoFile.delete()
                                                        filteredVideoFile
                                                    } else {
                                                        filteredVideoFile.delete()
                                                        processed.videoFile
                                                    }
                                                    val filteredImage = withContext(Dispatchers.Default) {
                                                        applyFilterToBitmap(processed.image, filterAtFinalize)
                                                    }
                                                    withContext(Dispatchers.IO) {
                                                        processed.file.outputStream().use { out ->
                                                            filteredImage.compress(
                                                                Bitmap.CompressFormat.JPEG,
                                                                92,
                                                                out,
                                                            )
                                                        }
                                                    }
                                                    CapturedVideoBuffer(
                                                        image      = filteredImage,
                                                        file       = processed.file,
                                                        videoFile  = finalVideo,
                                                        audioFile  = processed.audioFile,
                                                        durationMs = processed.durationMs,
                                                    )
                                                } else {
                                                    processed
                                                }
                                                withContext(Dispatchers.Main) {
                                                    uiState = VideoUiState.Captured(finalBuffer)
                                                }
                                            }
                                        }
                                        else -> Unit
                                    }
                                }
                            activeRecording = recording
                        }
                        is VideoUiState.Recording -> {
                            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                            val recording = activeRecording ?: return@onShutterTap
                            recording.stop()
                            activeRecording = null
                        }
                        else -> Unit
                    }
                },
                onFlipCamera = {
                    cameraSelector =
                        if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA)
                            CameraSelector.DEFAULT_FRONT_CAMERA
                        else
                            CameraSelector.DEFAULT_BACK_CAMERA
                },
            )
        } else {
            VideoCommitRow(
                commitError = commitError,
                isCommitting = isCommitting,
                onRetake = {
                    commitError = null
                    captured.buffer.audioFile?.delete()
                    captured.buffer.videoFile.delete()
                    captured.buffer.file.delete()
                    uiState = VideoUiState.Preview
                },
                onUse = onUse@{
                    isCommitting = true
                    uiScope.launch {
                        val outcome = withContext(Dispatchers.IO) {
                            commitVideoCapture(
                                context        = context,
                                userId         = userId,
                                controller     = controller,
                                repo           = repo,
                                captureRepo    = captureRepo,
                                buffer         = captured.buffer,
                                backgroundScope = backgroundScope,
                            )
                        }
                        if (outcome) {
                            onDismiss()
                        } else {
                            commitError = "Couldn't save video — try again"
                            isCommitting = false
                        }
                    }
                },
            )
        }
    }
        if (uiState is VideoUiState.Preview) {
            FilterStrip(
                isExpanded      = isFilterStripExpanded,
                activeFilter    = activeFilter,
                thumbnailBitmap = thumbnailBitmap,
                onToggleExpand  = { isFilterStripExpanded = !isFilterStripExpanded },
                onSelect        = { picked ->
                    activeFilter = picked
                    isFilterStripExpanded = false
                },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = QuickInkSpacing.s4, bottom = 86.dp),
            )
        }
    }
}

@Composable
private fun VideoShutterRow(
    uiState: VideoUiState,
    onShutterTap: () -> Unit,
    onFlipCamera: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = QuickInkSpacing.s5, vertical = QuickInkSpacing.s4),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier         = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            VideoShutterButton(
                isRecording = uiState is VideoUiState.Recording,
                enabled     = uiState !is VideoUiState.Processing,
                onClick     = onShutterTap,
            )
            if (uiState is VideoUiState.Preview) {
                VideoCameraFlipButton(
                    onClick  = onFlipCamera,
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
        }
        Spacer(Modifier.size(QuickInkSpacing.s2))
        val hint = if (uiState is VideoUiState.Recording) "Tap to stop" else "Tap to record"
        Text(
            text  = hint,
            style = LocalQuickInkTypography.current.caption.copy(fontSize = 11.sp),
            color = Color.White.copy(alpha = 0.55f),
        )
    }
    Spacer(Modifier.size(QuickInkSpacing.s7))
}

@Composable
private fun VideoCommitRow(
    commitError: String?,
    isCommitting: Boolean,
    onRetake: () -> Unit,
    onUse: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = QuickInkSpacing.s5, vertical = QuickInkSpacing.s4),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (commitError != null) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(QuickInkRadius.pill))
                    .background(Color.Black.copy(alpha = 0.65f))
                    .padding(horizontal = QuickInkSpacing.s4, vertical = QuickInkSpacing.s2),
            ) {
                Text(text = commitError, style = type.label, color = Color.White)
            }
            Spacer(Modifier.size(QuickInkSpacing.s3))
        }
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(QuickInkRadius.pill))
                    .background(Color.White.copy(alpha = 0.10f))
                    .clickable(enabled = !isCommitting, onClick = onRetake)
                    .padding(horizontal = QuickInkSpacing.s4, vertical = QuickInkSpacing.s3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector       = Icons.Filled.Close,
                    contentDescription = null,
                    tint              = Color.White,
                    modifier          = Modifier.size(16.dp),
                )
                Spacer(Modifier.size(6.dp))
                Text(text = "Retake", style = type.label, color = Color.White)
            }
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(QuickInkRadius.pill))
                    .shadow(12.dp, RoundedCornerShape(QuickInkRadius.pill))
                    .background(colors.accent)
                    .clickable(enabled = !isCommitting, onClick = onUse)
                    .padding(horizontal = QuickInkSpacing.s5, vertical = QuickInkSpacing.s3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isCommitting) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp),
                    )
                } else {
                    Icon(
                        imageVector       = Icons.Filled.Check,
                        contentDescription = null,
                        tint              = Color.White,
                        modifier          = Modifier.size(16.dp),
                    )
                }
                Spacer(Modifier.size(6.dp))
                Text(text = "OK", style = type.label, color = Color.White)
            }
        }
    }
    Spacer(Modifier.size(QuickInkSpacing.s7))
}

@Composable
private fun RecordingTimeChip(elapsedMs: Long, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .wrapContentWidth()
            .clip(RoundedCornerShape(QuickInkRadius.pill))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = QuickInkSpacing.s3, vertical = QuickInkSpacing.s2),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(Color.Red),
        )
        Text(
            text  = formatVideoElapsed(elapsedMs),
            color = Color.White,
            style = LocalQuickInkTypography.current.caption.copy(fontSize = 14.sp),
        )
    }
}

private fun formatVideoElapsed(elapsedMs: Long): String {
    val totalSec = (elapsedMs / 1000).toInt()
    val mm = totalSec / 60
    val ss = totalSec % 60
    return "%01d:%02d".format(mm, ss)
}

@Composable
private fun AudioBadge(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(QuickInkRadius.pill))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = QuickInkSpacing.s3, vertical = QuickInkSpacing.s2),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector        = Icons.Filled.GraphicEq,
            contentDescription = null,
            tint               = Color.White,
            modifier           = Modifier.size(11.dp),
        )
        Text(
            text  = "With audio",
            style = LocalQuickInkTypography.current.caption,
            color = Color.White,
        )
    }
}

/**
 * 78dp coral disc that toggles to a red recording-stop variant
 * while a video recording is in flight. Single-tap dispatches to
 * [onClick]; the caller distinguishes start-vs-stop from
 * [isRecording] state.
 */
@Composable
private fun VideoShutterButton(
    isRecording: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    Box(
        modifier = Modifier
            .size(78.dp)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(78.dp)
                .border(3.dp, Color.White.copy(alpha = 0.6f), CircleShape),
        )
        Box(
            modifier = Modifier
                .size(64.dp)
                .shadow(16.dp, CircleShape)
                .clip(CircleShape)
                .background(if (isRecording) Color.Red else colors.accent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector        = if (isRecording) Icons.Filled.Stop else Icons.Filled.Videocam,
                contentDescription = if (isRecording) "Stop recording" else "Start recording",
                tint               = Color.White,
                modifier           = Modifier.size(26.dp),
            )
        }
    }
}

@Composable
private fun VideoCameraFlipButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector        = Icons.Filled.Cameraswitch,
            contentDescription = "Switch camera",
            tint               = Color.White,
            modifier           = Modifier.size(20.dp),
        )
    }
}

/**
 * Collapse-aware filter strip — anchored to the bottom-left of
 * the screen by the caller. When [isExpanded] is false (default
 * on every fresh mount), only the active chip renders. Tapping
 * it flips [isExpanded] true via [onToggleExpand] and the column
 * reveals additional chips above. Tapping any chip during
 * expanded state selects via [onSelect].
 */
@Composable
private fun FilterStrip(
    isExpanded: Boolean,
    activeFilter: PhotoFilter,
    thumbnailBitmap: Bitmap?,
    onToggleExpand: () -> Unit,
    onSelect: (PhotoFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier              = modifier,
        verticalArrangement   = Arrangement.spacedBy(6.dp),
        horizontalAlignment   = Alignment.Start,
    ) {
        if (isExpanded) {
            PhotoFilter.entries.filter { it != activeFilter }.forEach { filter ->
                FilterChip(
                    filter    = filter,
                    isActive  = false,
                    thumbnail = thumbnailBitmap,
                    onClick   = { onSelect(filter) },
                )
            }
        }
        FilterChip(
            filter    = activeFilter,
            isActive  = true,
            thumbnail = thumbnailBitmap,
            onClick   = onToggleExpand,
        )
    }
}

@Composable
private fun FilterChip(
    filter: PhotoFilter,
    isActive: Boolean,
    thumbnail: Bitmap?,
    onClick: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(QuickInkRadius.sm))
            .background(
                if (isActive) colors.accent.copy(alpha = 0.85f)
                else Color.Black.copy(alpha = 0.55f),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black)
                .border(
                    width = if (isActive) 2.dp else 1.dp,
                    color = if (isActive) Color.White else Color.White.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(6.dp),
                ),
        ) {
            if (thumbnail != null) {
                val colorFilter = filter.captureMatrix?.let {
                    ColorFilter.colorMatrix(ComposeColorMatrix(it))
                }
                Image(
                    bitmap             = thumbnail.asImageBitmap(),
                    contentDescription = null,
                    contentScale       = ContentScale.Crop,
                    colorFilter        = colorFilter,
                    modifier           = Modifier.fillMaxSize(),
                )
            }
        }
        Text(
            text  = filter.displayName,
            style = type.caption.copy(fontSize = 11.sp),
            color = Color.White,
        )
    }
}

/**
 * Compact quality selector for the in-app recorder. Anchored in
 * the live preview by the caller; collapsed state shows the active
 * tier, expanded state reveals the other tiers below it.
 */
@Composable
private fun VideoQualityStrip(
    isExpanded: Boolean,
    activeQuality: VideoCaptureQuality,
    onToggleExpand: () -> Unit,
    onSelect: (VideoCaptureQuality) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier              = modifier,
        verticalArrangement   = Arrangement.spacedBy(6.dp),
        horizontalAlignment   = Alignment.End,
    ) {
        VideoQualityChip(
            quality  = activeQuality,
            isActive = true,
            onClick  = onToggleExpand,
        )
        if (isExpanded) {
            VideoCaptureQuality.entries.filter { it != activeQuality }.forEach { quality ->
                VideoQualityChip(
                    quality  = quality,
                    isActive = false,
                    onClick  = { onSelect(quality) },
                )
            }
        }
    }
}

@Composable
private fun VideoQualityChip(
    quality: VideoCaptureQuality,
    isActive: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(QuickInkRadius.pill))
            .background(
                if (isActive) colors.accent.copy(alpha = 0.88f)
                else Color.Black.copy(alpha = 0.58f),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text  = quality.label,
            style = type.caption.copy(fontSize = 11.sp),
            color = Color.White,
        )
    }
}

@Composable
private fun VideoPermissionRationale(onRequest: () -> Unit) {
    val type = LocalQuickInkTypography.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(QuickInkSpacing.s5),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector       = Icons.Filled.Videocam,
            contentDescription = null,
            tint              = Color.White.copy(alpha = 0.85f),
            modifier          = Modifier.size(48.dp),
        )
        Spacer(Modifier.size(QuickInkSpacing.s4))
        Text(text = "Allow camera to record video", style = type.heading, color = Color.White)
        Spacer(Modifier.size(QuickInkSpacing.s2))
        Text(
            text  = "Video mode uses your camera and microphone to capture a short clip. You can still scan documents and import from your library without it.",
            style = type.body,
            color = Color.White.copy(alpha = 0.70f),
        )
        Spacer(Modifier.size(QuickInkSpacing.s5))
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(LocalQuickInkColors.current.accent)
                .clickable(onClick = onRequest)
                .padding(horizontal = QuickInkSpacing.s5, vertical = QuickInkSpacing.s3),
        ) {
            Text(text = "Grant access", style = type.label, color = Color.White)
        }
    }
}

/**
 * Bind CameraX with two use cases — Preview + VideoCapture. No
 * ImageCapture; this surface is video-only. Lifecycle release
 * happens automatically via [lifecycleOwner].
 *
 * Re-callable: pass a different [cameraSelector] (e.g. FRONT
 * instead of BACK) and the function `unbindAll`s and rebinds.
 * That's how the flip button works.
 */
private fun bindCameraXForVideo(
    context: Context,
    previewView: PreviewView,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    bindings: VideoCameraXBindings,
    cameraSelector: CameraSelector,
    quality: VideoCaptureQuality,
) {
    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
    cameraProviderFuture.addListener({
        val cameraProvider = cameraProviderFuture.get()
        bindings.cameraProvider = cameraProvider

        val preview = Preview.Builder()
            .setTargetRotation(Surface.ROTATION_0)
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        // Quality cascade — prefer the highest common capture tiers
        // now that the Video FAB routes here specifically to avoid
        // OEM intent defaults. CameraX falls through when a device
        // does not advertise UHD/FHD for the selected lens.
        val qualitySelector = QualitySelector.fromOrderedList(quality.cameraXOrder)
        val recorder = Recorder.Builder()
            .setQualitySelector(qualitySelector)
            .build()
        val videoCapture = VideoCapture.withOutput(recorder)
        bindings.recorder = recorder
        bindings.videoCapture = videoCapture

        cameraProvider.unbindAll()
        runCatching {
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                videoCapture,
            )
        }.onFailure {
            android.util.Log.w(
                "VideoCapture",
                "bindToLifecycle failed for selector=$cameraSelector",
                it,
            )
        }
    }, ContextCompat.getMainExecutor(context))
}

/**
 * Extract the first video frame as a JPEG + the audio track as
 * a separate .m4a, returning a [CapturedVideoBuffer] with both +
 * the duration. Runs on the IO dispatcher; the raw .mp4 is
 * preserved (caller manages its lifecycle through commit /
 * dispose).
 */
private fun processVideoCaptureBuffer(context: Context, videoFile: File): CapturedVideoBuffer? {
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(videoFile.absolutePath)
        val durationMs = retriever.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_DURATION,
        )?.toLongOrNull() ?: 0L
        val firstFrame = retriever.getFrameAtTime(
            0L,
            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
        ) ?: return null
        val cacheDir = File(context.cacheDir, "video_capture").apply { mkdirs() }
        val frameFile = File(cacheDir, "buffer-${System.currentTimeMillis()}.jpg")
        frameFile.outputStream().use { out ->
            firstFrame.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }
        val audioFile = extractVideoAudioTrack(context, videoFile)
        return CapturedVideoBuffer(
            image      = firstFrame,
            file       = frameFile,
            videoFile  = videoFile,
            audioFile  = audioFile,
            durationMs = durationMs,
        )
    } catch (t: Throwable) {
        android.util.Log.w("VideoCapture", "processVideoCaptureBuffer failed", t)
        return null
    } finally {
        runCatching { retriever.release() }
    }
}

/**
 * Strip the .mp4's audio track into a standalone .m4a using
 * [MediaExtractor] + [MediaMuxer]. Returns null if the source
 * has no audio track or the muxer fails — caller treats null as
 * "no voice note attached" and the captured preview hides the
 * "With audio" badge.
 */
private fun extractVideoAudioTrack(context: Context, videoFile: File): File? {
    val extractor = MediaExtractor()
    return try {
        extractor.setDataSource(videoFile.absolutePath)
        var audioTrackIndex = -1
        var audioFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                audioTrackIndex = i
                audioFormat = format
                break
            }
        }
        if (audioTrackIndex < 0 || audioFormat == null) return null
        extractor.selectTrack(audioTrackIndex)
        val outDir = File(context.cacheDir, "video_capture_audio").apply { mkdirs() }
        val outFile = File(outDir, "buffer-${System.currentTimeMillis()}.m4a")
        val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val muxerTrackIndex = muxer.addTrack(audioFormat)
        muxer.start()
        val buffer = ByteBuffer.allocate(1 shl 18) // 256 KB chunk
        val bufferInfo = android.media.MediaCodec.BufferInfo()
        while (true) {
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            bufferInfo.offset = 0
            bufferInfo.size = size
            bufferInfo.presentationTimeUs = extractor.sampleTime
            bufferInfo.flags = extractor.sampleFlags
            muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
            extractor.advance()
        }
        muxer.stop()
        muxer.release()
        outFile
    } catch (t: Throwable) {
        android.util.Log.w("VideoCapture", "extractVideoAudioTrack failed", t)
        null
    } finally {
        runCatching { extractor.release() }
    }
}

/**
 * Convert a 4x5 row-major Android ColorMatrix into the 4x4
 * column-major float[16] format GL shaders (and therefore
 * Media3's [RgbMatrix]) expect. Drops the 5th column entirely
 * because [PhotoFilter]'s capture matrices use zero offsets —
 * purely linear transforms.
 */
private fun FloatArray.toRgbMatrix4x4ColumnMajorForVideo(): FloatArray = floatArrayOf(
    this[0], this[5], this[10], this[15],
    this[1], this[6], this[11], this[16],
    this[2], this[7], this[12], this[17],
    this[3], this[8], this[13], this[18],
)

/**
 * Re-encode [sourceFile] with [filter] baked into every video
 * frame via a Media3 [Transformer] + [RgbMatrix] effect, writing
 * to [outputFile]. Returns true on success; false on any error
 * (caller falls back to the raw source on failure so the user
 * still has their clip).
 */
@OptIn(UnstableApi::class)
private suspend fun applyFilterToCapturedVideo(
    context: Context,
    sourceFile: File,
    filter: PhotoFilter,
    outputFile: File,
): Boolean {
    val matrix4x4 = filter.captureMatrix?.toRgbMatrix4x4ColumnMajorForVideo() ?: return false
    return withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val rgbMatrix = object : RgbMatrix {
                override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray =
                    matrix4x4
            }
            val editedItem = EditedMediaItem.Builder(MediaItem.fromUri(sourceFile.toUri()))
                .setEffects(Effects(/* audioProcessors = */ emptyList(), listOf(rgbMatrix)))
                .build()
            val transformer = Transformer.Builder(context)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(
                        composition: Composition,
                        exportResult: ExportResult,
                    ) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException,
                    ) {
                        android.util.Log.w(
                            "VideoCapture",
                            "applyFilterToCapturedVideo Transformer error",
                            exportException,
                        )
                        if (cont.isActive) cont.resume(false)
                    }
                })
                .build()
            cont.invokeOnCancellation {
                runCatching { transformer.cancel() }
            }
            runCatching {
                transformer.start(editedItem, outputFile.absolutePath)
            }.onFailure {
                android.util.Log.w("VideoCapture", "applyFilterToCapturedVideo start() threw", it)
                if (cont.isActive) cont.resume(false)
            }
        }
    }
}

/**
 * Use handler — promotes the captured video buffer into the scan
 * pipeline. Builds the first-frame JPEG/PDF artifacts via
 * [buildImportArtifacts], fires `controller.onScanComplete` with
 * `source="video"` / `paperSize=Custom`, copies the extracted
 * audio + raw .mp4 into AttachmentStorage, and inserts a voice
 * note row against the freshly-created captureId. Returns true
 * on success; false surfaces an inline retry toast.
 */
private suspend fun commitVideoCapture(
    context: Context,
    userId: String,
    controller: ScanFlowController,
    repo: VoiceNoteRepository,
    captureRepo: CaptureRepository,
    buffer: CapturedVideoBuffer,
    backgroundScope: CoroutineScope,
): Boolean {
    val frameUri = Uri.fromFile(buffer.file)
    val result = buildImportArtifacts(context, listOf(frameUri)) ?: return false

    val audioUri = buffer.audioFile?.let { audio ->
        runCatching {
            val storedFile = File(AttachmentStorage.directory(context), "${Uuidv7.generate()}.m4a")
            audio.copyTo(storedFile, overwrite = true)
            Uri.fromFile(storedFile)
        }.getOrNull()
    }
    val videoUri = runCatching {
        val storedFile = File(AttachmentStorage.directory(context), "${Uuidv7.generate()}.mp4")
        buffer.videoFile.copyTo(storedFile, overwrite = true)
        Uri.fromFile(storedFile)
    }.getOrNull()
    val durationMs = buffer.durationMs

    withContext(Dispatchers.Main) {
        controller.onScanComplete(
            result    = result,
            source    = "video",
            paperSize = PaperSize.Custom,
        )
    }

    val captureId = withTimeoutOrNull(8_000L) {
        controller.state
            .filterIsInstance<ScanFlowController.State.Recognizing>()
            .first()
            .captureId
    }
    if (captureId == null) {
        android.util.Log.w(
            "VideoCapture",
            "commitVideoCapture: timed out waiting for State.Recognizing — video / voice note skipped",
        )
    }
    if (captureId != null) {
        if (audioUri != null) {
            val row = runCatching {
                repo.insert(
                    captureId  = captureId,
                    userId     = userId,
                    audioUri   = audioUri.toString(),
                    durationMs = durationMs,
                )
            }.getOrNull()
            if (row != null) {
                val pendingUserId = userId
                backgroundScope.launch(Dispatchers.IO) {
                    val transcribe = runCatching {
                        SpeechTranscriber.transcribe(
                            context = context,
                            fileUri = audioUri.toString(),
                            userId  = pendingUserId,
                        )
                    }.getOrNull()
                    if (transcribe is TranscribeResult.Success) {
                        runCatching {
                            repo.setTranscription(row.id, transcribe.text, transcribe.source)
                            captureRepo.appendNote(captureId, transcribe.text)
                        }
                    }
                }
            }
        }
        if (videoUri != null) {
            val outcome = runCatching {
                captureRepo.setVideoUri(captureId, videoUri.toString())
            }
            if (outcome.isSuccess) {
                android.util.Log.i(
                    "VideoCapture",
                    "commitVideoCapture: setVideoUri ok captureId=${captureId.take(8)}… uri=$videoUri",
                )
            } else {
                android.util.Log.w(
                    "VideoCapture",
                    "commitVideoCapture: setVideoUri failed: ${outcome.exceptionOrNull()}",
                )
            }
        }
    }
    buffer.file.delete()
    buffer.audioFile?.delete()
    buffer.videoFile.delete()
    return true
}
