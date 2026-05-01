/*
 * TextRecognizer.kt
 *
 * Thin wrapper around ML Kit Text Recognition v2 (Latin script, Play
 * Services variant — same delivery channel as the document scanner,
 * so there's no extra model to bundle into the APK).
 *
 * Runs fully on-device. Callers hand us a `file://` or `content://`
 * URI pointing at a still image (the JPEG preview the document
 * scanner gives us alongside its PDF is the obvious input) and we
 * return the recognized text as a single string, with ML Kit's
 * per-block newlines preserved so paragraphs stay visually distinct
 * when the user later pastes / views the result.
 *
 * Failure modes — decoder errors, unsupported MIME types, empty pages
 * — all fold to `null`. Callers treat "couldn't OCR" the same as
 * "nothing to OCR", both result in the scan being saved without a
 * recognized-text payload. We never throw back to the UI path.
 */

package app.releaf.mobile.data.common

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

object TextRecognizer {

    /**
     * Run OCR on the image behind `uri` and return the recognized text.
     * `null` means nothing usable came back — either ML Kit errored, the
     * image couldn't be decoded, or there genuinely was no text on the
     * page. Callers should treat all three cases the same.
     *
     * Suspends while ML Kit's Task completes; cancellation from the
     * caller's scope propagates straight to the underlying client so
     * nav-away doesn't leak a running inference.
     */
    suspend fun recognize(context: Context, uri: Uri): String? {
        val input = runCatching { InputImage.fromFilePath(context, uri) }
            .getOrNull() ?: return null
        val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            suspendCancellableCoroutine { cont ->
                client.process(input)
                    .addOnSuccessListener { result ->
                        val text = result.text.takeIf { it.isNotBlank() }
                        cont.resume(text)
                    }
                    .addOnFailureListener { cont.resume(null) }
                    .addOnCanceledListener { cont.resume(null) }
            }
        } finally {
            // Recognizer is cheap to recreate; closing here keeps us from
            // holding native resources once the page's scan flow is done.
            runCatching { client.close() }
        }
    }
}
