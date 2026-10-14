/*
 * MlKitBarcodeReader.kt
 *
 * Decodes QR codes on a page bitmap with ML Kit's bundled barcode
 * model (on-device; no network, no Play Services model download)
 * and returns the raw payload strings for `MarkTemplateDetector`.
 * Runs on the bitmap `PageImageLoader` already decoded for the
 * checkbox pass, so a page is only decoded once.
 *
 * Mirror of iOS `VisionBarcodeReader.swift`.
 */

package app.quickink.mobile.features.scan.templates

import android.graphics.Bitmap
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object MlKitBarcodeReader {

    /** QR only: the assumed mark format, and fewer false reads. */
    private val scanner: BarcodeScanner by lazy {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build(),
        )
    }

    /**
     * Raw payloads of every QR code on [bitmap]. Never throws: a
     * scanner failure reads as "no marks", and template routing
     * falls back to the header-words detector.
     */
    suspend fun readPayloads(bitmap: Bitmap): List<String> = suspendCancellableCoroutine { cont ->
        try {
            scanner.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { codes -> cont.resume(codes.mapNotNull { it.rawValue }) }
                .addOnFailureListener { cont.resume(emptyList()) }
        } catch (_: Exception) {
            cont.resume(emptyList())
        }
    }
}
