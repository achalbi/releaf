/*
 * PhotoCaptureSurface.kt
 *
 * Third capture surface — a single-shot still camera that ALSO
 * doubles as a hold-to-record video recorder. Sibling to
 * [DocumentCaptureSurface] (ML Kit system scanner) and
 * [BusinessCardCaptureSurface] (CameraX + quad detector).
 * Reached two ways:
 *
 *   1. Long-press on the bottom-nav ⚡ FAB
 *      (QuickInkBottomNavBar.onLongPressScan), which mounts
 *      QuickCaptureScreen with `initialMode = CaptureMode.Photo`.
 *      The long-press path uses a no-op persist on the
 *      coordinator so this transient surface doesn't overwrite
 *      the user's pill-selected last mode
 *      (`quickink.capture.last_mode`).
 *
 *   2. A Photo icon in the shutter row of [DocumentCaptureSurface]
 *      / [BusinessCardCaptureSurface], which calls back through
 *      the coordinator to flip `mode = Photo`.
 *
 * Shutter gesture (per Instagram / WhatsApp camera convention):
 *
 *   - Quick tap        → still photo capture
 *                        ([ImageCapture.takePicture]).
 *   - Press-and-hold   → video recording starts after a 300ms
 *                        threshold (so a normal tap doesn't
 *                        accidentally start a 1-frame video).
 *                        Release stops the recording; a hard 2:00
 *                        cap is enforced by
 *                        [PendingRecording.withDurationLimitMillis]
 *                        so the user can hold forever without
 *                        blowing up a transcription pass downstream.
 *
 * A small flip-camera button (top-right of the preview) toggles
 * between the back and front cameras. It is only shown in the
 * idle `Preview` state — hidden while a still capture, video
 * recording, or post-record processing pass is in flight, since
 * tearing down the [VideoCapture] use case mid-take would
 * corrupt the in-flight `.mp4` and stop the recording.
 *
 * After capture (still OR video), the surface lands on the same
 * captured-preview UI: a frozen still (or the video's first
 * frame) + Retake / Use Photo. Use Photo writes the JPEG via
 * [buildImportArtifacts], fires
 * `controller.onScanComplete(source="photo", paperSize=Custom)`,
 * and — when the capture was a video — inserts the extracted
 * audio track as a voice note against the freshly-created
 * captureId. The voice-note capture pane downstream sees the
 * pre-attached note and auto-advances to the review screen.
 *
 * The raw .mp4 video file is NOT persisted to AttachmentStorage —
 * only the first-frame JPEG (as the page) and the .m4a (as the
 * voice note) survive. Spec §6 calls out `paperSize=Custom` for
 * photo mode; video clips inherit the same since the first frame
 * is still an arbitrary phone-camera frame whose aspect ratio
 * tells us nothing about A4 / Letter / card bands.
 *
 * Mirror of iOS `PhotoCaptureSurface.swift`.
 */

package app.quickink.mobile.features.scan

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Discoverability state for the Photo-capture long-press shortcut
 * on the bottom-nav ⚡ FAB. Single persisted `dismissed` flag
 * that gates the "Hold ⚡ for a quick photo" chip above the FAB.
 * Spec §3.1.
 */
object PhotoFabHint {
    private const val PREFS_NAME = "quickink.capture.photo_fab_hint"
    private const val KEY_DISMISSED = "dismissed"

    fun isDismissed(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DISMISSED, false)

    fun markDismissed(context: Context) {
        prefs(context).edit().putBoolean(KEY_DISMISSED, true).apply()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

/** Hard cap on a hold-to-record video clip. */
private const val MAX_VIDEO_RECORDING_MS: Long = 120_000L

/** Threshold before a press is promoted from "tap" to "video hold". */
private const val VIDEO_HOLD_THRESHOLD_MS: Long = 300L

@Composable
internal fun PhotoCaptureSurface(
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
        // The mic result is informational — we don't gate on it.
        // Hold-to-record will produce a video-only .mp4 when the
        // user denies microphone access; the voice-note transcription
        // simply doesn't fire downstream.
    }

    LaunchedEffect(Unit) {
        // Request CAMERA + RECORD_AUDIO together so the hold-to-
        // record path captures audio for the voice-note
        // transcription pipeline. CAMERA gates the surface
        // entirely; RECORD_AUDIO is opportunistic — denying it
        // still lets the user take stills + silent video.
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
            ActivePhotoSurface(controller = controller, userId = userId, onDismiss = onDismiss)
        } else {
            PhotoPermissionRationale(
                onRequest = {
                    permissionLauncher.launch(
                        arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
                    )
                },
            )
        }
    }
}

/** Holder so capture callbacks can pin the latest use-case instances. */
private class CameraXBindings {
    @Volatile var imageCapture: ImageCapture? = null
    @Volatile var videoCapture: VideoCapture<Recorder>? = null
    @Volatile var recorder: Recorder? = null
}

/** Captured artifact returned to the host after the user lets go. */
private sealed interface CapturedBuffer {
    val image: Bitmap
    val file: File

    data class Still(override val image: Bitmap, override val file: File) : CapturedBuffer
    /**
     * Hold-to-record result. [videoFile] is the raw .mp4 that gets
     * promoted into AttachmentStorage at commit time so the detail
     * screen can re-play it; [audioFile] is the extracted audio
     * track for the voice-note transcription pipeline.
     */
    data class Video(
        override val image: Bitmap,
        override val file: File,
        val videoFile: File,
        val audioFile: File?,
        val durationMs: Long,
    ) : CapturedBuffer
}

/** Discriminated UI states for the active photo surface. */
private sealed interface PhotoUiState {
    data object Preview : PhotoUiState
    data class Recording(val elapsedMs: Long) : PhotoUiState
    data object Capturing : PhotoUiState
    data object Processing : PhotoUiState
    data class Captured(val buffer: CapturedBuffer) : PhotoUiState
}

@Composable
private fun ActivePhotoSurface(
    controller: ScanFlowController,
    userId: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = LocalView.current
    val uiScope = rememberCoroutineScope()

    val captureExecutor = remember { Executors.newSingleThreadExecutor() }
    val processingScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    // Long-lived for the fire-and-forget transcription that
    // outlives the surface's onDispose.
    val backgroundScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    val bindings = remember { CameraXBindings() }
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

    var uiState by remember { mutableStateOf<PhotoUiState>(PhotoUiState.Preview) }
    var commitError by remember { mutableStateOf<String?>(null) }
    var isCommitting by remember { mutableStateOf(false) }
    var activeRecording by remember { mutableStateOf<Recording?>(null) }
    var pendingVideoFile by remember { mutableStateOf<File?>(null) }
    var recordingStartedAtElapsed by remember { mutableLongStateOf(0L) }

    // Active camera. Hoisted out of `bindCameraX` so the
    // LaunchedEffect below can re-bind the CameraX use cases
    // against a different selector when the user taps the flip
    // button. Default is back camera — photo capture is primarily
    // for documents / objects in front of the user, not selfies.
    var cameraSelector by remember { mutableStateOf(CameraSelector.DEFAULT_BACK_CAMERA) }
    // PreviewView is created by AndroidView's factory; we hold a
    // ref so the LaunchedEffect can pass it into `bindCameraX`
    // outside the factory closure. Nulled in `onRelease` so a
    // remount (e.g. uiState going Preview → Captured → Preview)
    // gets a fresh bind against the new PreviewView instance.
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            // Stop any in-flight recording before tearing down so
            // the executor isn't disposed mid-write.
            activeRecording?.stop()
            captureExecutor.shutdown()
            processingScope.cancel()
            // backgroundScope intentionally NOT cancelled — the
            // transcription job needs to outlive the surface.
            (uiState as? PhotoUiState.Captured)?.let { st ->
                (st.buffer as? CapturedBuffer.Video)?.let { v ->
                    v.audioFile?.delete()
                    v.videoFile.delete()
                }
                st.buffer.file.delete()
            }
            pendingVideoFile?.delete()
        }
    }

    // Recording ticker — 250ms cadence so the on-screen elapsed
    // chip ticks smoothly. Re-derives the elapsed value from
    // SystemClock.elapsedRealtime so backgrounding pauses cleanly
    // (we don't tick while suspended).
    LaunchedEffect(uiState) {
        if (uiState !is PhotoUiState.Recording) return@LaunchedEffect
        while (isActive && uiState is PhotoUiState.Recording) {
            val elapsed = SystemClock.elapsedRealtime() - recordingStartedAtElapsed
            uiState = PhotoUiState.Recording(elapsedMs = elapsed.coerceAtMost(MAX_VIDEO_RECORDING_MS))
            delay(250)
        }
    }

    // (Re)bind CameraX whenever the PreviewView mounts (factory →
    // previewViewRef set) or the user flips cameras. Each call
    // unbindAll()s and rebinds against the current selector, so
    // the same effect drives both the initial bind and the flip
    // path — no separate "swap input" code path to keep in sync.
    LaunchedEffect(previewViewRef, cameraSelector) {
        val pv = previewViewRef ?: return@LaunchedEffect
        bindCameraX(
            context        = context,
            previewView    = pv,
            lifecycleOwner = lifecycleOwner,
            bindings       = bindings,
            cameraSelector = cameraSelector,
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            val state = uiState
            when (state) {
                is PhotoUiState.Captured -> {
                    Image(
                        bitmap             = state.buffer.image.asImageBitmap(),
                        contentDescription = "Captured photo",
                        modifier           = Modifier.fillMaxSize().background(Color.Black),
                    )
                    if (state.buffer is CapturedBuffer.Video) {
                        AudioBadge(modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(QuickInkSpacing.s4))
                    }
                }
                else -> {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            val pv = PreviewView(ctx).apply {
                                scaleType          = PreviewView.ScaleType.FILL_CENTER
                                implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                            }
                            // Bind happens via the LaunchedEffect
                            // up in ActivePhotoSurface — it runs
                            // as soon as we publish the ref here
                            // and re-runs on cameraSelector flips.
                            previewViewRef = pv
                            pv
                        },
                        onRelease = { previewViewRef = null },
                    )
                    if (state is PhotoUiState.Preview) {
                        CameraFlipButton(
                            onClick = {
                                cameraSelector =
                                    if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA)
                                        CameraSelector.DEFAULT_FRONT_CAMERA
                                    else
                                        CameraSelector.DEFAULT_BACK_CAMERA
                            },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = QuickInkSpacing.s5, end = QuickInkSpacing.s4),
                        )
                    }
                    if (state is PhotoUiState.Recording) {
                        RecordingTimeChip(
                            elapsedMs = state.elapsedMs,
                            modifier  = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = QuickInkSpacing.s5),
                        )
                    }
                    if (state is PhotoUiState.Capturing || state is PhotoUiState.Processing) {
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

        val captured = uiState as? PhotoUiState.Captured
        if (captured == null) {
            ShutterRow(
                uiState = uiState,
                onTap = onTap@{
                    if (uiState != PhotoUiState.Preview) return@onTap
                    uiState = PhotoUiState.Capturing
                    view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    CaptureAnalytics.manualFired(CaptureMode.Photo)
                    triggerPhotoCapture(
                        context         = context,
                        imageCapture    = bindings.imageCapture,
                        captureExecutor = captureExecutor,
                        processingScope = processingScope,
                        onResult = { file, bitmap ->
                            uiState = PhotoUiState.Captured(CapturedBuffer.Still(bitmap, file))
                        },
                        onError = {
                            android.util.Log.w("PhotoCapture", "takePicture failed", it)
                            uiState = PhotoUiState.Preview
                        },
                    )
                },
                onStartRecording = onStartRecording@{
                    if (uiState != PhotoUiState.Preview) return@onStartRecording
                    val videoCapture = bindings.videoCapture ?: return@onStartRecording
                    val outputDir = File(context.cacheDir, "photo_capture_video").apply { mkdirs() }
                    val outputFile = File(outputDir, "buffer-${System.currentTimeMillis()}.mp4")
                    outputFile.delete()
                    pendingVideoFile = outputFile
                    val outputOptions = FileOutputOptions.Builder(outputFile)
                        .setDurationLimitMillis(MAX_VIDEO_RECORDING_MS)
                        .build()
                    val hasMic = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.RECORD_AUDIO,
                    ) == PackageManager.PERMISSION_GRANTED
                    val recording = videoCapture.output
                        .prepareRecording(context, outputOptions)
                        .apply { if (hasMic) withAudioEnabled() }
                        .start(recorderExecutor) { event ->
                            when (event) {
                                is VideoRecordEvent.Start -> {
                                    recordingStartedAtElapsed = SystemClock.elapsedRealtime()
                                    view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                    uiState = PhotoUiState.Recording(elapsedMs = 0)
                                    android.util.Log.i("PhotoCapture", "video recording started")
                                }
                                is VideoRecordEvent.Finalize -> {
                                    val file = pendingVideoFile
                                    pendingVideoFile = null
                                    activeRecording = null
                                    // Recording reached its 2:00 cap OR was
                                    // stopped by the user. `hasError()`
                                    // distinguishes a hard failure from
                                    // the "max-duration reached" pseudo-
                                    // error which CameraX returns as code
                                    // [VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED].
                                    val isDurationLimit = event.error ==
                                        VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED
                                    if (event.hasError() && !isDurationLimit) {
                                        android.util.Log.w(
                                            "PhotoCapture",
                                            "video recording failed: ${event.error}",
                                        )
                                        file?.delete()
                                        uiState = PhotoUiState.Preview
                                        return@start
                                    }
                                    if (file == null || !file.exists() || file.length() == 0L) {
                                        android.util.Log.w(
                                            "PhotoCapture",
                                            "video Finalize produced no usable file " +
                                                "(exists=${file?.exists()}, size=${file?.length()})",
                                        )
                                        uiState = PhotoUiState.Preview
                                        return@start
                                    }
                                    android.util.Log.i(
                                        "PhotoCapture",
                                        "video recording finalized — ${file.length()}B at ${file.absolutePath}",
                                    )
                                    uiState = PhotoUiState.Processing
                                    processingScope.launch {
                                        // Note: pass the file through verbatim
                                        // so `processVideoBuffer` can extract
                                        // first frame + audio AND hand back
                                        // the .mp4 path itself for promotion
                                        // to AttachmentStorage on commit.
                                        val processed = withContext(Dispatchers.IO) {
                                            processVideoBuffer(context, file)
                                        }
                                        // file is preserved when processed
                                        // is non-null — it becomes the
                                        // `videoFile` member that commit()
                                        // copies into AttachmentStorage and
                                        // deletes afterwards. On failure
                                        // (processed == null) clean up here.
                                        if (processed == null) {
                                            file.delete()
                                        }
                                        withContext(Dispatchers.Main) {
                                            uiState = if (processed != null) {
                                                PhotoUiState.Captured(processed)
                                            } else {
                                                PhotoUiState.Preview
                                            }
                                        }
                                    }
                                }
                                else -> Unit
                            }
                        }
                    activeRecording = recording
                },
                onStopRecording = onStopRecording@{
                    val recording = activeRecording ?: return@onStopRecording
                    recording.stop()
                    activeRecording = null
                },
            )
        } else {
            CommitRow(
                commitError = commitError,
                isCommitting = isCommitting,
                onRetake = {
                    commitError = null
                    (captured.buffer as? CapturedBuffer.Video)?.let { v ->
                        v.audioFile?.delete()
                        v.videoFile.delete()
                    }
                    captured.buffer.file.delete()
                    uiState = PhotoUiState.Preview
                },
                onUse = onUse@{
                    isCommitting = true
                    uiScope.launch {
                        val outcome = withContext(Dispatchers.IO) {
                            commitCapture(
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
                            commitError = "Couldn't save photo — try again"
                            isCommitting = false
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun ShutterRow(
    uiState: PhotoUiState,
    onTap: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = QuickInkSpacing.s5, vertical = QuickInkSpacing.s4),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment     = Alignment.CenterVertically,
        ) {
            PhotoShutterButton(
                isRecording      = uiState is PhotoUiState.Recording,
                enabled          = uiState !is PhotoUiState.Capturing && uiState !is PhotoUiState.Processing,
                onTap            = onTap,
                onStartRecording = onStartRecording,
                onStopRecording  = onStopRecording,
            )
        }
        Spacer(Modifier.size(QuickInkSpacing.s2))
        // Hint copy: two variants — "tap or hold" for the
        // preview state, "tap to stop" once the recording is
        // running so the user knows they can let go of the
        // shutter (the gesture has flipped from "hold the whole
        // take" to "tap again to end").
        val hint = if (uiState is PhotoUiState.Recording) {
            "Tap to stop"
        } else {
            "Tap for photo · hold for video"
        }
        Text(
            text  = hint,
            style = LocalQuickInkTypography.current.caption.copy(fontSize = 11.sp),
            color = Color.White.copy(alpha = 0.55f),
        )
    }
    Spacer(Modifier.size(QuickInkSpacing.s7))
}

@Composable
private fun CommitRow(
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
            text  = formatElapsed(elapsedMs),
            color = Color.White,
            style = LocalQuickInkTypography.current.caption.copy(fontSize = 14.sp),
        )
    }
}

private fun formatElapsed(elapsedMs: Long): String {
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

@Composable
private fun PhotoShutterButton(
    isRecording: Boolean,
    enabled: Boolean,
    onTap: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    Box(
        modifier = Modifier
            .size(78.dp)
            .pointerInput(enabled, isRecording) {
                if (!enabled) return@pointerInput
                // Two-mode gesture detector:
                //
                //   - Recording   → any touch (regardless of
                //                   duration) stops the take on
                //                   release. The shutter swaps
                //                   to a red stop icon while
                //                   recording so the affordance
                //                   reads as "tap to stop."
                //   - Preview     → race a 300ms hold threshold
                //                   against a release:
                //                     ‣ released early → onTap
                //                       (still photo).
                //                     ‣ threshold elapsed →
                //                       onStartRecording. Release
                //                       does NOT stop — the user
                //                       must explicitly tap the
                //                       shutter again to end the
                //                       take, which lets them
                //                       record hands-free without
                //                       holding the finger down
                //                       for the entire clip.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    if (isRecording) {
                        waitForUpOrCancellation()
                        onStopRecording()
                        return@awaitEachGesture
                    }
                    val released = withTimeoutOrNull(VIDEO_HOLD_THRESHOLD_MS) {
                        waitForUpOrCancellation()
                    }
                    if (released != null) {
                        onTap()
                    } else {
                        onStartRecording()
                        // Consume the eventual up event so the
                        // gesture loop resets cleanly; we
                        // deliberately do NOT call
                        // onStopRecording here.
                        waitForUpOrCancellation()
                    }
                }
            },
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
                imageVector        = if (isRecording) Icons.Filled.Stop else Icons.Filled.PhotoCamera,
                contentDescription = if (isRecording) "Stop recording" else "Take photo or hold to record",
                tint               = Color.White,
                modifier           = Modifier.size(26.dp),
            )
        }
    }
}

/**
 * Top-right overlay that toggles between the back and front
 * cameras. Only rendered while the surface is idle on
 * [PhotoUiState.Preview] — hidden mid-recording since flipping
 * tears down the in-flight [VideoCapture] use case and would
 * stop the `.mp4` mid-write. The 40dp black disc matches the
 * other on-camera affordances (recording chip, audio badge)
 * for visual rhythm.
 */
@Composable
private fun CameraFlipButton(
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

@Composable
private fun PhotoPermissionRationale(onRequest: () -> Unit) {
    val type = LocalQuickInkTypography.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(QuickInkSpacing.s5),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector       = Icons.Filled.PhotoCamera,
            contentDescription = null,
            tint              = Color.White.copy(alpha = 0.85f),
            modifier          = Modifier.size(48.dp),
        )
        Spacer(Modifier.size(QuickInkSpacing.s4))
        Text(text = "Allow camera to take photos", style = type.heading, color = Color.White)
        Spacer(Modifier.size(QuickInkSpacing.s2))
        Text(
            text  = "Photo mode uses your camera to capture a still image or a quick video. You can still scan documents and import from your library without it.",
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
 * Bind CameraX with three use cases — Preview + ImageCapture
 * (still tap path) + VideoCapture (hold-to-record path). The
 * recorder is created here and pinned in [bindings] so the
 * hold-start handler can call `prepareRecording` against the
 * same instance. Lifecycle release happens automatically via
 * [lifecycleOwner].
 *
 * Re-callable: pass a different [cameraSelector] (e.g. FRONT
 * instead of BACK) and the function `unbindAll`s and rebinds
 * against the new camera. That's how the flip button works —
 * the [LaunchedEffect] up in `ActivePhotoSurface` keys on the
 * selector and calls this again whenever the user flips.
 */
private fun bindCameraX(
    context: Context,
    previewView: PreviewView,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    bindings: CameraXBindings,
    cameraSelector: CameraSelector,
) {
    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
    cameraProviderFuture.addListener({
        val cameraProvider = cameraProviderFuture.get()

        val preview = Preview.Builder()
            .setTargetRotation(Surface.ROTATION_0)
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        val imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setTargetRotation(Surface.ROTATION_0)
            .build()
        bindings.imageCapture = imageCapture

        // Quality cascade — prefer HD, fall back to lower if the
        // device doesn't advertise the higher tier. Capping at HD
        // keeps the post-process audio-extract cheap and the
        // resulting .mp4 well under a typical 60MB SQLite blob
        // limit even at 2 minutes.
        val qualitySelector = QualitySelector.fromOrderedList(
            listOf(Quality.HD, Quality.SD, Quality.LOWEST),
        )
        val recorder = Recorder.Builder()
            .setQualitySelector(qualitySelector)
            .build()
        val videoCapture = VideoCapture.withOutput(recorder)
        bindings.recorder = recorder
        bindings.videoCapture = videoCapture

        cameraProvider.unbindAll()
        // Bind preview + imageCapture together; videoCapture is
        // bound separately because CameraX has a 2-use-case cap
        // per lifecycle on some legacy devices and the
        // image+video combo can exceed the stream-config table.
        // The official workaround is two parallel binds against
        // the same lifecycleOwner — preview + image, then
        // preview + video — letting the framework pick a
        // compatible session for each.
        runCatching {
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageCapture,
                videoCapture,
            )
        }.recoverCatching {
            // Fall back to image + preview only on devices that
            // can't satisfy the three-use-case bind. Video
            // capture won't work in that case, but the still
            // path still does — the hold gesture will start a
            // recording that never produces a Finalize event,
            // and the surface stays in `Recording` until the
            // user releases. Acceptable degraded behaviour.
            // `recoverCatching` (not `onFailure`) so a throw in
            // this lambda itself gets caught by the outer chain
            // rather than crashing the executor — relevant when
            // the user flips to a camera the device doesn't have
            // (front-camera-less tablets, etc.) and BOTH binds
            // fail.
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageCapture,
            )
        }.onFailure {
            android.util.Log.w(
                "PhotoCapture",
                "bindToLifecycle failed for selector=$cameraSelector",
                it,
            )
        }
    }, ContextCompat.getMainExecutor(context))
}

/**
 * Take a single still using [ImageCapture.OutputFileOptions] →
 * the file lives in the app's cache directory under
 * `photo_capture/buffer-<ts>.jpg`. Decoded back to a [Bitmap]
 * for the captured-preview, then handed to [onResult] on the
 * main thread.
 */
private fun triggerPhotoCapture(
    context: Context,
    imageCapture: ImageCapture?,
    captureExecutor: ExecutorService,
    processingScope: CoroutineScope,
    onResult: (File, Bitmap) -> Unit,
    onError: (Throwable) -> Unit,
) {
    val ic = imageCapture ?: run {
        onError(IllegalStateException("camera not bound"))
        return
    }
    val cacheDir = File(context.cacheDir, "photo_capture").apply { mkdirs() }
    val outFile = File(cacheDir, "buffer-${System.currentTimeMillis()}.jpg")
    val outputOptions = ImageCapture.OutputFileOptions.Builder(outFile).build()
    ic.takePicture(
        outputOptions,
        captureExecutor,
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                processingScope.launch {
                    val bitmap = withContext(Dispatchers.IO) {
                        BitmapFactory.decodeFile(outFile.absolutePath)
                    }
                    if (bitmap == null) {
                        outFile.delete()
                        withContext(Dispatchers.Main) {
                            onError(IllegalStateException("decode failed"))
                        }
                        return@launch
                    }
                    withContext(Dispatchers.Main) { onResult(outFile, bitmap) }
                }
            }

            override fun onError(exception: ImageCaptureException) {
                outFile.delete()
                processingScope.launch {
                    withContext(Dispatchers.Main) { onError(exception) }
                }
            }
        },
    )
}

/**
 * Extract the first video frame as a JPEG + the audio track as
 * a separate .m4a, returning a [CapturedBuffer.Video] with both
 * + the duration. Runs on the IO dispatcher; the raw .mp4 is
 * deleted by the caller after this returns.
 *
 * The first-frame bitmap is written into the same cache dir as
 * the still-capture path so the discard / retake / dispose
 * branches all use the same cleanup logic. The audio file lives
 * in a sibling `photo_capture_audio/` directory until commit
 * promotes it into AttachmentStorage.
 */
private fun processVideoBuffer(context: Context, videoFile: File): CapturedBuffer.Video? {
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
        // Write the first frame to disk so it has the same shape
        // as the still-capture path (File + Bitmap pair).
        val cacheDir = File(context.cacheDir, "photo_capture").apply { mkdirs() }
        val frameFile = File(cacheDir, "buffer-${System.currentTimeMillis()}.jpg")
        frameFile.outputStream().use { out ->
            firstFrame.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }
        // Strip the audio track into a standalone .m4a; null when
        // the recording had no audio (mic permission denied).
        val audioFile = extractAudioTrack(context, videoFile)
        return CapturedBuffer.Video(
            image      = firstFrame,
            file       = frameFile,
            videoFile  = videoFile,
            audioFile  = audioFile,
            durationMs = durationMs,
        )
    } catch (t: Throwable) {
        android.util.Log.w("PhotoCapture", "processVideoBuffer failed", t)
        return null
    } finally {
        runCatching { retriever.release() }
    }
}

/**
 * Strip the .mp4's audio track into a standalone .m4a using
 * `MediaExtractor` + `MediaMuxer`. Returns null if the source
 * has no audio track or the muxer fails — caller treats null
 * as "no voice note attached" and the captured preview hides
 * the "With audio" badge.
 *
 * Output lives in cache; commit() copies the bytes into
 * AttachmentStorage so the file's lifecycle matches every
 * other voice note (Drive sync, thumbnails, etc.).
 */
private fun extractAudioTrack(context: Context, videoFile: File): File? {
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
        val outDir = File(context.cacheDir, "photo_capture_audio").apply { mkdirs() }
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
        android.util.Log.w("PhotoCapture", "extractAudioTrack failed", t)
        null
    } finally {
        runCatching { extractor.release() }
    }
}

/**
 * Use Photo handler — promotes the captured buffer into the
 * scan pipeline. Builds the JPEG/PDF artifacts via
 * [buildImportArtifacts], fires `controller.onScanComplete`
 * with `source="photo"` / `paperSize=Custom`, and — when the
 * buffer was a video — copies the extracted audio into
 * AttachmentStorage and inserts a voice note row against the
 * freshly-created captureId. Returns true on success; false
 * surfaces an inline retry toast on the commit row.
 */
private suspend fun commitCapture(
    context: Context,
    userId: String,
    controller: ScanFlowController,
    repo: VoiceNoteRepository,
    captureRepo: CaptureRepository,
    buffer: CapturedBuffer,
    backgroundScope: CoroutineScope,
): Boolean {
    val frameUri = Uri.fromFile(buffer.file)
    val result = buildImportArtifacts(context, listOf(frameUri)) ?: return false

    val audioUri = (buffer as? CapturedBuffer.Video)?.audioFile?.let { audio ->
        runCatching {
            val storedFile = File(AttachmentStorage.directory(context), "${Uuidv7.generate()}.m4a")
            audio.copyTo(storedFile, overwrite = true)
            Uri.fromFile(storedFile)
        }.getOrNull()
    }
    // Promote the raw .mp4 into AttachmentStorage so the
    // detail screen can re-play it. The cache copy is deleted
    // below once the persistent copy lands.
    val videoUri = (buffer as? CapturedBuffer.Video)?.videoFile?.let { src ->
        runCatching {
            val storedFile = File(AttachmentStorage.directory(context), "${Uuidv7.generate()}.mp4")
            src.copyTo(storedFile, overwrite = true)
            Uri.fromFile(storedFile)
        }.getOrNull()
    }
    val durationMs = (buffer as? CapturedBuffer.Video)?.durationMs

    withContext(Dispatchers.Main) {
        controller.onScanComplete(
            result    = result,
            source    = "photo",
            paperSize = PaperSize.Custom,
        )
    }

    // The Android controller's `onScanComplete` fires-and-
    // forgets a coroutine that calls `insertCapture` BEFORE
    // publishing `State.Recognizing`. So reading
    // `controller.state.value` immediately after the call
    // returns gives us `State.Idle` — the captureId isn't on
    // the state yet. Wait for the controller's state to
    // transition to `Recognizing`, which guarantees the parent
    // row has landed in Room and our follow-up `setVideoUri` /
    // voice-note INSERT can target it without an FK violation
    // or a 0-rows-affected silent loss. Cap the wait at 8s so
    // a stuck controller doesn't strand the user.
    val captureId = withTimeoutOrNull(8_000L) {
        controller.state
            .filterIsInstance<ScanFlowController.State.Recognizing>()
            .first()
            .captureId
    }
    if (captureId == null) {
        android.util.Log.w(
            "PhotoCapture",
            "commitCapture: timed out waiting for State.Recognizing — video / voice note skipped",
        )
    }
    if (captureId != null) {
        if (audioUri != null && durationMs != null) {
            val row = runCatching {
                repo.insert(
                    captureId  = captureId,
                    userId     = userId,
                    audioUri   = audioUri.toString(),
                    durationMs = durationMs,
                )
            }.getOrNull()
            if (row != null) {
                backgroundScope.launch(Dispatchers.IO) {
                    val transcribe = runCatching {
                        SpeechTranscriber.transcribe(context, audioUri.toString())
                    }.getOrNull()
                    if (transcribe is TranscribeResult.Success) {
                        runCatching {
                            repo.setTranscription(row.id, transcribe.text, transcribe.source)
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
                    "PhotoCapture",
                    "commitCapture: setVideoUri ok captureId=${captureId.take(8)}… uri=$videoUri",
                )
            } else {
                android.util.Log.w(
                    "PhotoCapture",
                    "commitCapture: setVideoUri failed: ${outcome.exceptionOrNull()}",
                )
            }
        }
    }
    // Clean up the per-capture cache files now that the
    // canonical AttachmentStorage copies own the data.
    buffer.file.delete()
    (buffer as? CapturedBuffer.Video)?.let { v ->
        v.audioFile?.delete()
        v.videoFile.delete()
    }
    return true
}
