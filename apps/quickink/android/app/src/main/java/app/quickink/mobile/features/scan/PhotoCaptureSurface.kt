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
 * A small flip-camera button sits at the trailing edge of the
 * shutter row, next to the shutter itself (matches the iOS
 * Camera / Instagram convention). It is only shown in the idle
 * `Preview` state — hidden while a still capture, video
 * recording, or post-record processing pass is in flight, since
 * tearing down the [VideoCapture] use case mid-take would
 * corrupt the in-flight `.mp4` and stop the recording.
 *
 * A collapse-by-default chip strip sits to the left of the
 * shutter and lets the user pick one of four color filters
 * (None / B&W / Sepia / Cool). Collapsed → only the active chip
 * is visible. Tap → the column expands upward into the preview
 * area with all chips; each chip carries a live mini-preview
 * rendered from [TextureView.getBitmap] polled at 5fps and
 * re-tinted per chip via Compose [ColorFilter.colorMatrix].
 * Tap a chip → it's selected and the strip collapses again.
 *
 * The selection drives the main live preview (via a Compose
 * [Canvas] overlay with an HSL [BlendMode] against the
 * COMPATIBLE-mode [PreviewView]'s TextureView pixels — no per-
 * frame Bitmap copy) AND every saved artifact: the captured
 * still (via [android.graphics.ColorMatrix] applied to the
 * Bitmap before the JPEG re-encode) and the recorded video
 * (via a Media3 [Transformer] post-process with an [RgbMatrix]
 * effect that bakes the same matrix into every frame of the
 * `.mp4`, audio passed through untouched). The chip strip is
 * hidden while recording so the user can't change filter mid-
 * take — the post-process snapshot is taken once at Finalize.
 * See [PhotoFilter] for the per-case overlay / matrix mapping.
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
import android.graphics.Canvas as AndroidCanvas
import android.graphics.ColorMatrix as AndroidColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
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
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix as ComposeColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * One of six color filters the user can apply from the vertical
 * chip strip on the bottom-left of the live preview. Selection
 * lives in `ActivePhotoSurface`'s `activeFilter` state and is
 * mirrored to:
 *
 *   - **Live preview** — via a Compose [Canvas] overlay that
 *     paints a single colored rect over the preview using one
 *     of the HSL blend modes ([BlendMode.Saturation] /
 *     [BlendMode.Color]). The [PreviewView] is forced into
 *     COMPATIBLE mode (TextureView under the hood) so the
 *     overlay can composite against it in the same render tree;
 *     PERFORMANCE mode's SurfaceView lives on its own surface
 *     and Compose blend modes wouldn't reach it.
 *
 *   - **Captured still** — via [android.graphics.ColorMatrix]
 *     applied to the saved [Bitmap] before disk write. The
 *     matrix gives a precise, deterministic outcome; the live
 *     preview is a cheap approximation tuned to land in roughly
 *     the same color space.
 *
 * Video recording captures **raw** (no filter applied to the
 * `.mp4` output). The chip strip + live preview filter are both
 * suppressed while recording so the user can see what's actually
 * being saved. A filtered-video pipeline would need
 * `androidx.media3.transformer` + a `RgbFilter` effect chain;
 * that's a separate, heavier follow-up.
 */
internal enum class PhotoFilter(
    val displayName: String,
    /** Color painted by the live-preview Canvas. `null` = no overlay. */
    val overlayColor: Color?,
    /** Blend mode the overlay uses against the preview underneath. */
    val overlayBlendMode: BlendMode,
    /**
     * 4x5 color matrix applied to the captured bitmap. `null`
     * leaves the bitmap untouched (the None case + the read site
     * skips the paint pass entirely).
     */
    val captureMatrix: FloatArray?,
) {
    None(
        displayName      = "None",
        overlayColor     = null,
        overlayBlendMode = BlendMode.SrcOver,
        captureMatrix    = null,
    ),
    BW(
        displayName      = "B&W",
        overlayColor     = Color.Gray,
        overlayBlendMode = BlendMode.Saturation,
        // Standard luminosity weights (Rec. 601). Produces a
        // gentle, naturally-toned grayscale rather than a flat
        // average-of-channels desaturation.
        captureMatrix    = floatArrayOf(
            0.299f, 0.587f, 0.114f, 0f, 0f,
            0.299f, 0.587f, 0.114f, 0f, 0f,
            0.299f, 0.587f, 0.114f, 0f, 0f,
            0f,     0f,     0f,     1f, 0f,
        ),
    ),
    Sepia(
        displayName      = "Sepia",
        overlayColor     = Color(0xFFD2A56F),
        overlayBlendMode = BlendMode.Color,
        // Classic Microsoft sepia matrix.
        captureMatrix    = floatArrayOf(
            0.393f, 0.769f, 0.189f, 0f, 0f,
            0.349f, 0.686f, 0.168f, 0f, 0f,
            0.272f, 0.534f, 0.131f, 0f, 0f,
            0f,     0f,     0f,     1f, 0f,
        ),
    ),
    Cool(
        displayName      = "Cool",
        overlayColor     = Color(0xFF80A0FF),
        overlayBlendMode = BlendMode.Color,
        // Dim red, lift blue — matches the iOS
        // `CITemperatureAndTint` cool target.
        captureMatrix    = floatArrayOf(
            0.85f, 0f,    0f,    0f, 0f,
            0f,    0.95f, 0f,    0f, 0f,
            0f,    0f,    1.10f, 0f, 0f,
            0f,    0f,    0f,    1f, 0f,
        ),
    ),
}

/**
 * Walk the view tree under [root] and return the first
 * [TextureView] found, or `null` if none exists. PreviewView in
 * COMPATIBLE mode hosts a TextureView as a child; we need a
 * reference to it so the filter-strip polling effect can call
 * `getBitmap(w, h)` for the live mini-previews. The TextureView
 * isn't part of PreviewView's public API, so walking the tree is
 * the supported way to reach it.
 */
private fun findTextureView(root: View): TextureView? {
    if (root is TextureView) return root
    if (root is ViewGroup) {
        for (i in 0 until root.childCount) {
            val hit = findTextureView(root.getChildAt(i))
            if (hit != null) return hit
        }
    }
    return null
}

/**
 * Apply [filter]'s `captureMatrix` to [source] and return a new
 * [Bitmap]. Returns [source] verbatim when filter is None
 * (caller doesn't need to special-case). Always returns an
 * ARGB_8888 bitmap regardless of the source config so the
 * downstream JPEG encoder has a known format.
 */
private fun applyFilterToBitmap(source: Bitmap, filter: PhotoFilter): Bitmap {
    val matrix = filter.captureMatrix ?: return source
    val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
    val paint = Paint().apply {
        colorFilter = ColorMatrixColorFilter(AndroidColorMatrix(matrix))
    }
    AndroidCanvas(out).drawBitmap(source, 0f, 0f, paint)
    return out
}

/**
 * Convert our 4x5 row-major Android ColorMatrix into the 4x4
 * column-major float[16] format GL shaders (and therefore
 * Media3's [RgbMatrix]) expect. We drop the 5th column entirely
 * because all of [PhotoFilter]'s `captureMatrix` values use
 * zero offsets — purely linear transforms, no constant
 * addition. The transpose folds row-major rows into column-
 * major columns: row 0 (R coefficients) becomes the first
 * element of each column, row 1 (G) the second, etc.
 *
 * The shader then computes `result = M * vec4(r, g, b, a)`
 * where M is read column-major, so each output channel is
 * dot(M.column, vec4(r,g,b,a)) — equivalent to the row-major
 * dot we get from ColorMatrixColorFilter on the still path.
 */
private fun FloatArray.toRgbMatrix4x4ColumnMajor(): FloatArray = floatArrayOf(
    // column 0: r0 g0 b0 a0
    this[0], this[5], this[10], this[15],
    // column 1: r1 g1 b1 a1
    this[1], this[6], this[11], this[16],
    // column 2: r2 g2 b2 a2
    this[2], this[7], this[12], this[17],
    // column 3: r3 g3 b3 a3
    this[3], this[8], this[13], this[18],
)

/**
 * Re-encode [sourceFile] with [filter] baked into every video
 * frame via a Media3 [Transformer] + [RgbMatrix] effect, writing
 * to [outputFile]. Returns true on success; false on any error
 * (the caller is expected to fall back to the raw source on
 * failure so the user still has their clip).
 *
 * Audio is copied through unchanged — Effects.audioProcessors is
 * empty, so the audio track just goes through Transformer's
 * default passthrough path. That keeps the voice-note transcript
 * pipeline downstream identical regardless of filter.
 *
 * Transformer requires its `start()` call (and the listener
 * dispatch) to happen on a thread with a Looper, so the whole
 * suspending region runs on [Dispatchers.Main]. Encoding itself
 * happens on Transformer's internal worker threads — Main is
 * only used for orchestration / callbacks.
 */
@OptIn(UnstableApi::class)
private suspend fun applyFilterToVideo(
    context: Context,
    sourceFile: File,
    filter: PhotoFilter,
    outputFile: File,
): Boolean {
    val matrix4x4 = filter.captureMatrix?.toRgbMatrix4x4ColumnMajor() ?: return false
    return withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val rgbMatrix = object : RgbMatrix {
                // Media3 1.4 renamed the interface method from
                // `getRgbMatrix` (older betas) to `getMatrix` —
                // override the current name.
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
                            "PhotoCapture",
                            "applyFilterToVideo Transformer error",
                            exportException,
                        )
                        if (cont.isActive) cont.resume(false)
                    }
                })
                .build()
            // `cancel()` is the supported way to abort an in-
            // flight Transformer (release would race the worker
            // threads). Safe to call even if the listener already
            // resumed `cont` — we only check `isActive` before
            // resuming, and `cancel` after is a no-op for the
            // coroutine.
            cont.invokeOnCancellation {
                runCatching { transformer.cancel() }
            }
            runCatching {
                transformer.start(editedItem, outputFile.absolutePath)
            }.onFailure {
                android.util.Log.w("PhotoCapture", "applyFilterToVideo start() threw", it)
                if (cont.isActive) cont.resume(false)
            }
        }
    }
}

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
    // Selected color filter. Resets to None on every fresh mount
    // — we don't persist across sessions since a filter that
    // silently survived to the next launch could surprise the
    // user. See `PhotoFilter` docblock for the live-preview vs
    // capture-time pipeline.
    var activeFilter by remember { mutableStateOf(PhotoFilter.None) }
    // Filter strip expand/collapse state. Default `false` →
    // only the active chip is visible (anchored left of the
    // shutter). Tap → expands upward into the 6-chip column;
    // tap any chip → selects + collapses. Resets each mount.
    var isFilterStripExpanded by remember { mutableStateOf(false) }
    // Latest 96x96 RGBA bitmap from the live PreviewView's
    // TextureView, polled while the strip is expanded so each
    // chip can show a real-time mini-preview with its filter
    // applied (via Compose `ColorFilter.colorMatrix`). `null`
    // when collapsed / before the first poll lands.
    var thumbnailBitmap by remember { mutableStateOf<Bitmap?>(null) }

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

    // Thumbnail polling. Runs only while the filter strip is
    // expanded — when collapsed we drop the cached bitmap so the
    // GC can reclaim it and we don't burn CPU on a stream
    // nobody is looking at. 5fps (200ms) gives the chips a
    // "live" feel without thrashing — TextureView.getBitmap is
    // not free (it does a GPU → CPU readback) so we don't want
    // to do it 30 times a second for six tiny preview boxes.
    //
    // The polled bitmap is a single fresh allocation per tick
    // (TextureView.getBitmap(w,h) returns a new ARGB_8888 bitmap
    // at the requested size). Compose sees a new instance →
    // recomposes the chips → each chip re-draws with its own
    // ColorFilter applied to the same source pixels.
    LaunchedEffect(isFilterStripExpanded, previewViewRef) {
        if (!isFilterStripExpanded) {
            thumbnailBitmap = null
            return@LaunchedEffect
        }
        val pv = previewViewRef ?: return@LaunchedEffect
        while (isActive && isFilterStripExpanded) {
            val tv = findTextureView(pv)
            if (tv != null) {
                // 96x96 is enough resolution for a 32dp chip
                // thumbnail at any reasonable display density;
                // smaller would alias badly when the chip is
                // scaled up on an XXHDPI screen.
                val frame = runCatching { tv.getBitmap(96, 96) }.getOrNull()
                if (frame != null) {
                    thumbnailBitmap = frame
                }
            }
            delay(200)
        }
    }

    // Outer Box hosts the preview/shutter Column AND the
    // filter-strip overlay as siblings, so the expanded strip
    // can extend upward into the preview area without growing
    // the Column's layout (and pushing the shutter row down).
    // Same pattern as iOS's `.overlay(alignment: .bottomLeading)`.
    Box(modifier = Modifier.fillMaxSize()) {
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
                    // Wrap PreviewView + filter overlay in a Box
                    // with Offscreen compositing so the Compose
                    // Canvas's BlendMode operates against the
                    // PreviewView's pixels (it needs an offscreen
                    // buffer as the blend destination — otherwise
                    // HSL blend modes have nothing to blend with).
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
                                    // COMPATIBLE (TextureView)
                                    // instead of PERFORMANCE
                                    // (SurfaceView) so the live-
                                    // preview filter overlay can
                                    // actually composite against
                                    // the preview pixels. A
                                    // SurfaceView's surface lives
                                    // on its own window layer and
                                    // Compose blend modes wouldn't
                                    // reach it.
                                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
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
                        // Live-preview filter overlay. Stays on
                        // through Recording / Capturing / Processing
                        // — the recorded `.mp4` is post-processed
                        // by `applyFilterToVideo` to bake in the
                        // same matrix, so the preview ≈ the saved
                        // video. The chip strip is still hidden
                        // mid-recording (we snapshot `activeFilter`
                        // at Finalize and changing it during a take
                        // would mismatch), only the overlay stays.
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
                    val filterAtCapture = activeFilter
                    triggerPhotoCapture(
                        context         = context,
                        imageCapture    = bindings.imageCapture,
                        captureExecutor = captureExecutor,
                        processingScope = processingScope,
                        onResult = { file, bitmap ->
                            if (filterAtCapture == PhotoFilter.None) {
                                uiState = PhotoUiState.Captured(CapturedBuffer.Still(bitmap, file))
                            } else {
                                // Filter the captured bitmap on a
                                // background dispatcher (ColorMatrix
                                // + JPEG re-encode is ~50-200ms),
                                // flip to Processing in the
                                // meantime so the spinner overlay
                                // shows and the shutter is locked.
                                uiState = PhotoUiState.Processing
                                processingScope.launch {
                                    val filtered = withContext(Dispatchers.Default) {
                                        applyFilterToBitmap(bitmap, filterAtCapture)
                                    }
                                    withContext(Dispatchers.IO) {
                                        // Rewrite the JPEG so the
                                        // file ImportArtifacts
                                        // eventually consumes
                                        // matches the bitmap the
                                        // user sees on the captured
                                        // preview.
                                        file.outputStream().use { out ->
                                            filtered.compress(
                                                Bitmap.CompressFormat.JPEG,
                                                92,
                                                out,
                                            )
                                        }
                                    }
                                    withContext(Dispatchers.Main) {
                                        uiState = PhotoUiState.Captured(
                                            CapturedBuffer.Still(filtered, file),
                                        )
                                    }
                                }
                            }
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
                                    val filterAtFinalize = activeFilter
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
                                            withContext(Dispatchers.Main) {
                                                uiState = PhotoUiState.Preview
                                            }
                                            return@launch
                                        }
                                        // Bake the active filter into the
                                        // recorded video + first frame so
                                        // commit() persists artifacts that
                                        // match what the user was seeing in
                                        // the live preview. Falls back to
                                        // the raw buffer if the Transformer
                                        // pass errors — the user still gets
                                        // their clip, just unfiltered.
                                        val finalBuffer = if (filterAtFinalize != PhotoFilter.None) {
                                            val filteredVideoFile = File(
                                                processed.videoFile.parentFile,
                                                "filtered-${processed.videoFile.name}",
                                            )
                                            filteredVideoFile.delete()
                                            val ok = applyFilterToVideo(
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
                                            // Re-encode the first frame
                                            // JPEG with the same matrix so
                                            // the captured-preview tracks
                                            // the saved video.
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
                                            CapturedBuffer.Video(
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
                                            uiState = PhotoUiState.Captured(finalBuffer)
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
                onFlipCamera = {
                    cameraSelector =
                        if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA)
                            CameraSelector.DEFAULT_FRONT_CAMERA
                        else
                            CameraSelector.DEFAULT_BACK_CAMERA
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
        // Filter strip overlay — anchored bottom-start of the
        // screen with bottom padding tuned to put the active
        // chip's vertical center roughly at the shutter
        // button's center (~105dp from screen bottom, chip is
        // ~38dp tall → bottom anchor at ~86dp). Same suppression
        // rule as the camera-flip button: only visible in idle
        // Preview, hidden during recording / capturing / etc.
        if (uiState is PhotoUiState.Preview) {
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
private fun ShutterRow(
    uiState: PhotoUiState,
    onTap: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onFlipCamera: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = QuickInkSpacing.s5, vertical = QuickInkSpacing.s4),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Box (not a centered Row) so the flip button can pin to
        // the trailing edge via `Alignment.CenterEnd` without
        // displacing the shutter — the shutter sits at the Box's
        // own center alignment and the flip floats over the same
        // row, vertically aligned with the shutter.
        Box(
            modifier         = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            PhotoShutterButton(
                isRecording      = uiState is PhotoUiState.Recording,
                enabled          = uiState !is PhotoUiState.Capturing && uiState !is PhotoUiState.Processing,
                onTap            = onTap,
                onStartRecording = onStartRecording,
                onStopRecording  = onStopRecording,
            )
            if (uiState is PhotoUiState.Preview) {
                CameraFlipButton(
                    onClick  = onFlipCamera,
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
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
 * Bare 40dp black disc that toggles between the back and front
 * cameras. Positioned by [ShutterRow] at the trailing edge of
 * the shutter row so it reads as a peer affordance to the
 * shutter button (matches iOS Camera / Instagram). Only
 * rendered while the surface is idle on [PhotoUiState.Preview]
 * — hidden mid-recording since flipping tears down the in-
 * flight [VideoCapture] use case and would stop the `.mp4`
 * mid-write.
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

/**
 * Collapse-aware filter strip — anchored to the bottom-left of
 * the screen by the caller, mirror of the camera-flip button on
 * the right. When [isExpanded] is false (default on every fresh
 * mount), only the active chip renders. Tapping it flips
 * [isExpanded] true via [onToggleExpand] and the column reveals
 * five additional chips above (each with its filter pre-applied
 * as a live mini-preview of [thumbnailBitmap]). Tapping any
 * chip during expanded state selects via [onSelect] and the
 * caller is responsible for collapsing again.
 *
 * The active chip is always the LAST child in the Column so its
 * on-screen position stays fixed across collapse / expand —
 * siblings appear / disappear above it.
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
            // Five non-active chips above. Order follows enum
            // declaration order; the active is dropped from the
            // list and re-rendered at the bottom below.
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

/**
 * One chip in the filter strip. Visually: a 32dp live mini-
 * preview (the camera's current frame with this chip's
 * `captureMatrix` applied via Compose [ColorFilter.colorMatrix])
 * + a short label. Falls back to a black square when
 * [thumbnail] is null — happens during the brief window
 * between expand-tap and the first poll landing, plus the
 * permanent collapsed state where thumbnails aren't polled at
 * all but the active chip still renders.
 */
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
            // Rounded rectangle (QuickInkRadius.sm = 8dp), not a
            // pill — the chips are wide enough that a full pill
            // looked too soft; 8dp corners read as "rounded
            // rectangle" and match the rest of the surface chrome.
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
