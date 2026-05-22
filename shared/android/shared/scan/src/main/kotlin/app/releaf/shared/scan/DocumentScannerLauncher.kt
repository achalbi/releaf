/*
 * DocumentScannerLauncher.kt
 *
 * Compose-native wrapper around ML Kit's `GmsDocumentScanning` client.
 * Mirrors iOS `ReleafCoreScan/DocumentScannerView.swift` — the
 * platform-native scanner UI is invoked through a stable handle the
 * caller drives, with the result-extraction + AttachmentStorage copy
 * already done by the time `onResult` fires.
 *
 * Scope: this file is the launcher contract only. Releaf-specific UX
 * around scans (filter chips, the Edit-scan dialog, the in-house PDF
 * viewer, OCR fan-out, Toast wording) stays in the app target's
 * `ScansSection`. QuickInk plugs straight into `rememberDocumentScannerLauncher`
 * and writes its own thin UI shell.
 *
 * Phase-3 follow-ups deliberately NOT in this PR:
 *   - OcrEngine protocol + MlKitTextRecognizer impl
 *   - Multi-page parallel OCR pipeline
 *   - Searchable PDF export
 */

package app.releaf.shared.scan

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import app.releaf.mobile.data.common.AttachmentStorage
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Result of a successful document scan.
 *
 * `pdfUri` and `previewUri` are file:// URIs already copied into
 * `AttachmentStorage.directory()`. They survive process death and
 * cache rotation, so they're safe to persist on a row.
 *
 * `pageUris` are content:// URIs pointing into ML Kit's own cache.
 * They stay readable for the process lifetime — long enough to feed
 * into a Text Recognition v2 pass, but NOT safe to persist. Caller
 * should kick off OCR off-tree before they expire.
 *
 * Either or both of `pdfUri` / `previewUri` may be non-null. They're
 * jointly null only when both copies failed (rare; surfaced through
 * `DocumentScanError.SaveFailed` so the caller can show the right
 * message instead of receiving an empty success).
 */
data class DocumentScanResult(
    val pdfUri: Uri?,
    val previewUri: Uri?,
    val pageUris: List<Uri>,
)

/**
 * Failure modes the caller might want to surface differently. The
 * launcher distinguishes them so a "no scanner module on this device"
 * Toast can read differently from a "couldn't save scan, try again"
 * Toast.
 *
 *   - [IntentUnavailable] — `getStartScanIntent` failed. Usually means
 *     the ML Kit document-scanner Play module isn't available on the
 *     device (Play Services missing or out of date).
 *   - [NoActivity]        — `LocalContext` wasn't an Activity. Should
 *     never fire in practice; included for safety so the launcher
 *     doesn't silently swallow it.
 *   - [SaveFailed]        — scan succeeded but `AttachmentStorage`
 *     couldn't copy either the PDF or the preview JPEG into filesDir.
 */
sealed interface DocumentScanError {
    data object IntentUnavailable : DocumentScanError
    data object NoActivity : DocumentScanError
    data object SaveFailed : DocumentScanError
}

/**
 * Stable handle returned by [rememberDocumentScannerLauncher]. The
 * `isLaunching` flag is true while we're waiting for ML Kit to
 * resolve `getStartScanIntent` — on first use the scanner module gets
 * downloaded (~30 MB) and the call can sit for 10–30 s; the caller
 * can show a loading row to keep the user from assuming the app hung.
 */
class DocumentScannerLauncher internal constructor(
    private val onLaunchImpl: () -> Unit,
    isLaunchingState: State<Boolean>,
) {
    val isLaunching: Boolean by isLaunchingState
    fun launch() = onLaunchImpl()
}

/**
 * Composable factory. Wires up:
 *   - `GmsDocumentScannerOptions` (gallery import on, JPEG + PDF
 *     output, full scanner mode — same defaults Releaf has been
 *     shipping)
 *   - `GmsDocumentScanning.getClient`
 *   - The `StartIntentSenderForResult` activity-result launcher
 *   - The result extraction (PDF + first-page JPEG → AttachmentStorage)
 *
 * `onResult`, `onError`, `pageLimit`, and `galleryImportAllowed`
 * are wrapped through `rememberUpdatedState` so the launcher
 * captures the values-as-of-the-current-recomposition without
 * re-creating itself when the caller flips modes — the common-
 * case caller passes lambdas that close over view-model state,
 * which would otherwise cause the launcher to thrash.
 *
 * @param pageLimit when non-null, caps the scanner at that many
 *   pages — `1` makes the scanner a single-shot capture with no
 *   in-UI Add-page affordance, matching QuickInk's "Single" mode.
 *   `null` (default) leaves ML Kit at its unlimited-pages default,
 *   which is what Releaf ships and what QuickInk uses for
 *   Multi-page / Auto. Built into options at launch time, not at
 *   composition time, so the latest value wins even when the
 *   caller flips mode without re-mounting.
 * @param galleryImportAllowed when `true` (default) ML Kit shows
 *   its in-scanner gallery picker so the user can import a photo
 *   instead of capturing one. QuickInk passes `false` so the only
 *   gallery path is its own dedicated Import button — that way the
 *   `source` we stamp on the resulting capture row is unambiguous
 *   ("scan" vs "import"), since ML Kit doesn't expose the source
 *   per-page in its result. Releaf keeps the default to preserve
 *   the unified flow it ships.
 * @param compressedPdfEnabled when `true`, tries an optimized
 *   JPEG-backed PDF under the writer's 250 KB/page budget and keeps
 *   it only if it is smaller than ML Kit's raw PDF. When `false`,
 *   copies ML Kit's raw PDF where available.
 */
@Composable
fun rememberDocumentScannerLauncher(
    onResult: (DocumentScanResult) -> Unit,
    onError: (DocumentScanError) -> Unit = {},
    pageLimit: Int? = null,
    galleryImportAllowed: Boolean = true,
    compressedPdfEnabled: Boolean = true,
): DocumentScannerLauncher {
    val context = LocalContext.current
    val activity = context as? Activity

    // Hold the latest lambdas / params so the launcher doesn't have
    // to be re-created every recomposition. The Composable factory
    // itself recomposes; the inner `onLaunch` / result handler keep
    // reading through these state proxies.
    val currentOnResult     = rememberUpdatedState(onResult)
    val currentOnError      = rememberUpdatedState(onError)
    val currentPageLimit    = rememberUpdatedState(pageLimit)
    val currentGalleryAllow = rememberUpdatedState(galleryImportAllowed)
    val currentCompressedPdf = rememberUpdatedState(compressedPdfEnabled)

    val isLaunching = remember { mutableStateOf(false) }
    val extractionScope = rememberCoroutineScope()

    val scanLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult

        val scanResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
        val cachedPdf  = scanResult?.pdf?.uri
        val pageUris   = scanResult?.pages?.map { it.imageUri } ?: emptyList()
        val cachedJpeg = pageUris.firstOrNull()

        // ML Kit hands back content:// URIs pointing into its own cache
        // directory. Those stop resolving once the cache rotates, so we
        // store app-owned attachment artifacts (PDF + preview JPEG) in
        // filesDir and return file:// URIs on the DocumentScanResult.
        // Cleanup on remove is then straightforward — the caller just
        // deletes the files we own. If compression is enabled, keep the
        // smaller of the optimized image-only PDF and ML Kit's raw PDF
        // instead of assuming the re-encoded copy always wins.
        //
        // Page content:// URIs are NOT copied — they're handed straight
        // through, expected to feed into Text Recognition v2 off the
        // Compose tree. They stay readable for the process lifetime,
        // which is well beyond a 1–3 s/page inference run.
        extractionScope.launch {
            val (localPdf, localJpeg) = withContext(Dispatchers.IO) {
                val pdf = if (currentCompressedPdf.value) {
                    val compressed = CompressedImagePdfWriter.writeToAttachment(context, pageUris)
                    val raw = cachedPdf?.let { AttachmentStorage.copyIntoStorage(context, it, "pdf") }
                    chooseSmallerPdfAttachment(compressed, raw)
                } else {
                    cachedPdf?.let { AttachmentStorage.copyIntoStorage(context, it, "pdf") }
                        ?: CompressedImagePdfWriter.writeToAttachment(context, pageUris)
                }
                val jpeg = cachedJpeg?.let { AttachmentStorage.copyIntoStorage(context, it, "jpg") }
                pdf to jpeg
            }

            if (localPdf == null && localJpeg == null) {
                currentOnError.value(DocumentScanError.SaveFailed)
            } else {
                currentOnResult.value(
                    DocumentScanResult(
                        pdfUri     = localPdf,
                        previewUri = localJpeg,
                        pageUris   = pageUris,
                    )
                )
            }
        }
    }

    val onLaunch: () -> Unit = launch@{
        val act = activity ?: run {
            currentOnError.value(DocumentScanError.NoActivity)
            return@launch
        }
        if (isLaunching.value) return@launch
        isLaunching.value = true

        // Build options + client per-launch so a freshly-flipped
        // `pageLimit` takes effect on the next shutter tap. Both
        // calls are cheap (the builder/client are config holders;
        // the real work happens in `getStartScanIntent`), and per-
        // launch construction sidesteps the staleness bug from the
        // earlier reverted experiment that cached options at first
        // composition and never updated them.
        val builder = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(currentGalleryAllow.value)
            .setResultFormats(
                GmsDocumentScannerOptions.RESULT_FORMAT_JPEG,
                GmsDocumentScannerOptions.RESULT_FORMAT_PDF,
            )
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
        currentPageLimit.value?.let { builder.setPageLimit(it) }
        val scannerClient = GmsDocumentScanning.getClient(builder.build())

        scannerClient.getStartScanIntent(act)
            .addOnSuccessListener { sender ->
                // Clear loading before launching the scanner Activity
                // so when the user dismisses it the loading row isn't
                // stuck on.
                isLaunching.value = false
                scanLauncher.launch(IntentSenderRequest.Builder(sender).build())
            }
            .addOnFailureListener {
                isLaunching.value = false
                currentOnError.value(DocumentScanError.IntentUnavailable)
            }
    }

    return remember { DocumentScannerLauncher(onLaunch, isLaunching) }
}

private fun chooseSmallerPdfAttachment(compressed: Uri?, raw: Uri?): Uri? {
    if (compressed == null) return raw
    if (raw == null) return compressed

    val compressedSize = localFileSize(compressed)
    val rawSize = localFileSize(raw)
    val keepCompressed = compressedSize == null ||
        rawSize == null ||
        compressedSize <= rawSize

    val discarded = if (keepCompressed) raw else compressed
    AttachmentStorage.deleteIfLocal(discarded.toString())

    return if (keepCompressed) compressed else raw
}

private fun localFileSize(uri: Uri): Long? {
    if (uri.scheme != "file") return null
    val path = uri.path ?: return null
    return File(path).takeIf { it.exists() }?.length()
}
