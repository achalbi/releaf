/*
 * PhotoCaptureSurface.kt
 *
 * Third capture surface — a single-shot still-photo camera that
 * plugs into the existing scan pipeline. Sibling to
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
 *      the coordinator to flip `mode = Photo`. Pill stays two-
 *      wide on the top bar (Document / Business Card); the pill
 *      keeps highlighting the user's last pill choice while this
 *      surface is mounted, so flipping back is one tap.
 *
 * Top-down structure mirrors [BusinessCardCaptureSurface] minus
 * the analyzer / stability gate / overlay:
 *
 *   1. Camera-permission gate — if CAMERA hasn't been granted,
 *      show a rationale + Grant button. The pill toggle on the
 *      parent stays available so the user can switch back to
 *      Document mode without granting.
 *
 *   2. PreviewView mounted via AndroidView. CameraX is bound
 *      with two use cases (Preview, ImageCapture) — no
 *      ImageAnalysis, because photo mode has no per-frame work
 *      to do. Session releases automatically on Composable
 *      dispose via the host's LifecycleOwner.
 *
 *   3. Shutter row — same 78dp coral disc + white ring as the
 *      Document / Business Card surfaces, swapped to a
 *      PhotoCamera icon. Tap fires `ImageCapture.takePicture`
 *      writing to a temp file in the app's cache directory and
 *      flips the surface to the captured state.
 *
 *   4. Captured-state preview — frozen still + Retake / Use Photo
 *      buttons. Use Photo runs [buildImportArtifacts] on the
 *      captured file (single-element list — same helper the
 *      gallery import path uses) and calls
 *      `controller.onScanComplete(..., source = "photo",
 *      paperSize = PaperSize.Custom)`, then `onDismiss()` to
 *      collapse the capture sheet. QuickInkRoot is already
 *      observing the controller and mounts the voice-note → review
 *      pipeline on the next render — the post-capture sequencing
 *      is source-agnostic.
 *
 * Why no flash / flip / focus controls in v1: the spec calls
 * them out as nice-to-have but the basic shutter + retake +
 * commit path is the load-bearing piece. Adding them later is
 * an additive change to the preview-state chrome row and the
 * [ImageCapture] builder — no scaffolding change.
 *
 * Mirror of iOS `PhotoCaptureSurface.swift`.
 */

package app.quickink.mobile.features.scan

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.HapticFeedbackConstants
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkRadius
import app.quickink.mobile.ui.theme.QuickInkSpacing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Discoverability state for the Photo-capture long-press shortcut
 * on the bottom-nav ⚡ FAB. Owns the single persisted `dismissed`
 * flag that gates the "Hold ⚡ for a quick photo" chip above the
 * FAB.
 *
 * Spec §3.1 originally gated the chip on a first-scan flag too
 * ("show only after the user has scanned at least once"). We
 * dropped that gate so existing users upgrading into this build
 * see the chip immediately, without needing one more scan to flip
 * a freshly-introduced SharedPreferences bool. The chip is small
 * and one long-press dismisses it forever, so the noise cost is
 * low and the discoverability win is universal.
 *
 * Mirror of iOS `PhotoFabHint` (a `@StateObject ObservableObject`
 * on iOS; here we expose two top-level functions and let the host
 * Compose tree own a `mutableStateOf` that mirrors the on-disk
 * value).
 */
object PhotoFabHint {
    private const val PREFS_NAME = "quickink.capture.photo_fab_hint"
    private const val KEY_DISMISSED = "dismissed"

    /** True after the user's first FAB long-press has fired. */
    fun isDismissed(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DISMISSED, false)

    /**
     * Flip the dismissed flag to true. Idempotent — calling
     * again after the flag is already set is a cheap no-op
     * `apply()`. The caller is expected to mirror the value
     * back into Compose state on the same tick so the chip
     * fades out immediately.
     */
    fun markDismissed(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DISMISSED, true)
            .apply()
    }
}

@Composable
internal fun PhotoCaptureSurface(
    controller: ScanFlowController,
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
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted -> hasCameraPermission = granted }

    // Auto-prompt on first mount when permission isn't already
    // granted. If the user denied previously, the launcher returns
    // immediately without showing system UI — the rationale block
    // below covers that case.
    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        if (hasCameraPermission) {
            ActivePhotoSurface(controller = controller, onDismiss = onDismiss)
        } else {
            PhotoPermissionRationale(
                onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) },
            )
        }
    }
}

/** Holder so capture callbacks can pin the latest ImageCapture instance. */
private class ImageCaptureHolder {
    @Volatile var value: ImageCapture? = null
}

@Composable
private fun ActivePhotoSurface(
    controller: ScanFlowController,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = LocalView.current

    val captureExecutor = remember { Executors.newSingleThreadExecutor() }
    val processingScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    val imageCaptureHolder = remember { ImageCaptureHolder() }
    val uiScope = rememberCoroutineScope()

    // Capture state — null preview means "live", non-null means
    // "captured, awaiting Retake / Use Photo". Holding the file
    // alongside the bitmap so retake can delete it from disk and
    // commit can pass the URI to buildImportArtifacts without
    // re-encoding.
    var capturedFile by remember { mutableStateOf<File?>(null) }
    var capturedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isCapturing by remember { mutableStateOf(false) }
    var commitError by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            captureExecutor.shutdown()
            processingScope.cancel()
            // Drop the captured buffer on dispose (e.g. app
            // backgrounded during the captured state). Spec §11
            // marks the buffer volatile by design — preserving
            // it across a background round-trip is more state
            // than the feature warrants.
            capturedBitmap?.recycle()
            capturedFile?.delete()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {

        // Live preview OR captured-state preview, sharing the
        // weighted top region above the bottom action row.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            val pinnedBitmap = capturedBitmap
            if (pinnedBitmap == null) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        val previewView = PreviewView(ctx).apply {
                            scaleType          = PreviewView.ScaleType.FILL_CENTER
                            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                        }
                        bindCameraX(
                            context            = ctx,
                            previewView        = previewView,
                            lifecycleOwner     = lifecycleOwner,
                            imageCaptureHolder = imageCaptureHolder,
                        )
                        previewView
                    },
                )
                if (isCapturing) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.25f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = Color.White)
                    }
                }
            } else {
                Image(
                    bitmap             = pinnedBitmap.asImageBitmap(),
                    contentDescription = "Captured photo",
                    contentScale       = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black),
                )
            }
        }

        // Bottom action row swaps based on state. Live preview
        // shows the shutter; captured shows Retake / Use Photo.
        if (capturedBitmap == null) {
            ShutterRow(
                isCapturing = isCapturing,
                onTap = onTap@{
                    if (isCapturing) return@onTap
                    isCapturing = true
                    view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    CaptureAnalytics.manualFired(CaptureMode.Photo)
                    triggerPhotoCapture(
                        context            = context,
                        imageCapture       = imageCaptureHolder.value,
                        captureExecutor    = captureExecutor,
                        processingScope    = processingScope,
                        onResult = { file, bitmap ->
                            capturedFile   = file
                            capturedBitmap = bitmap
                            isCapturing    = false
                        },
                        onError = {
                            // Fallback: return to live preview rather
                            // than stranding the surface in
                            // "capturing". A system shutter that
                            // drops a frame is rare and recoverable
                            // by retap.
                            android.util.Log.w("PhotoCapture", "takePicture failed", it)
                            isCapturing = false
                        },
                    )
                },
            )
        } else {
            CommitRow(
                commitError = commitError,
                onRetake = {
                    commitError = null
                    capturedBitmap?.recycle()
                    capturedBitmap = null
                    capturedFile?.delete()
                    capturedFile = null
                },
                onUse = onUse@{
                    val file = capturedFile ?: return@onUse
                    val uri = Uri.fromFile(file)
                    uiScope.launch {
                        val result = withContext(Dispatchers.IO) {
                            buildImportArtifacts(context, listOf(uri))
                        }
                        if (result == null) {
                            commitError = "Couldn't save photo — try again"
                            return@launch
                        }
                        controller.onScanComplete(
                            result    = result,
                            source    = "photo",
                            paperSize = PaperSize.Custom,
                        )
                        onDismiss()
                    }
                },
            )
        }
    }
}

@Composable
private fun ShutterRow(
    isCapturing: Boolean,
    onTap: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = QuickInkSpacing.s5, vertical = QuickInkSpacing.s4),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        PhotoShutterButton(enabled = !isCapturing, onClick = onTap)
    }
    Spacer(Modifier.size(QuickInkSpacing.s7))
}

@Composable
private fun CommitRow(
    commitError: String?,
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
                Text(
                    text  = commitError,
                    style = type.label,
                    color = Color.White,
                )
            }
            Spacer(Modifier.size(QuickInkSpacing.s3))
        }
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically,
        ) {
            // Retake — secondary chip, white-on-translucent.
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(QuickInkRadius.pill))
                    .background(Color.White.copy(alpha = 0.10f))
                    .clickable(onClick = onRetake)
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
            // Use Photo — primary coral capsule.
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(QuickInkRadius.pill))
                    .shadow(12.dp, RoundedCornerShape(QuickInkRadius.pill))
                    .background(colors.accent)
                    .clickable(onClick = onUse)
                    .padding(horizontal = QuickInkSpacing.s5, vertical = QuickInkSpacing.s3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector       = Icons.Filled.Check,
                    contentDescription = null,
                    tint              = Color.White,
                    modifier          = Modifier.size(16.dp),
                )
                Spacer(Modifier.size(6.dp))
                Text(text = "Use Photo", style = type.label, color = Color.White)
            }
        }
    }
    Spacer(Modifier.size(QuickInkSpacing.s7))
}

@Composable
private fun PhotoShutterButton(enabled: Boolean, onClick: () -> Unit) {
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
                .background(colors.accent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector        = Icons.Filled.PhotoCamera,
                contentDescription = "Take photo",
                tint               = Color.White,
                modifier           = Modifier.size(26.dp),
            )
        }
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
        Text(
            text  = "Allow camera to take photos",
            style = type.heading,
            color = Color.White,
        )
        Spacer(Modifier.size(QuickInkSpacing.s2))
        Text(
            text  = "Photo mode uses your camera to capture a still image. You can still scan documents and import from your library without it.",
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
            Text(
                text  = "Grant access",
                style = type.label,
                color = Color.White,
            )
        }
    }
}

/**
 * Bind CameraX with two use cases — [Preview] + [ImageCapture].
 * Unlike [BusinessCardCaptureSurface], we skip [androidx.camera.core.ImageAnalysis]
 * because photo mode has no per-frame detection work. Lifecycle
 * release happens automatically via the [lifecycleOwner] tie.
 */
private fun bindCameraX(
    context: Context,
    previewView: PreviewView,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    imageCaptureHolder: ImageCaptureHolder,
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
        imageCaptureHolder.value = imageCapture

        cameraProvider.unbindAll()
        cameraProvider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            imageCapture,
        )
    }, ContextCompat.getMainExecutor(context))
}

/**
 * Take a single still using [ImageCapture.OutputFileOptions] →
 * the file lives in the app's cache directory under
 * `photo_capture/buffer-<ts>.jpg`. Decoded back to a [Bitmap]
 * for the captured-state preview, then handed to [onResult] on
 * the main thread.
 *
 * The file is intentionally NOT in AttachmentStorage — it's a
 * transient buffer that either gets promoted to AttachmentStorage
 * by [buildImportArtifacts] on Use Photo, or deleted on Retake /
 * dispose. Keeping it in `cache/` means the OS can also evict it
 * under memory pressure without breaking the app.
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
