/*
 * MlKitTextRecognizer.kt
 *
 * ML Kit-backed `OcrEngine` impl. Wraps `TextRecognition` (Latin v2)
 * onto the engine-agnostic surface defined in `OcrEngine.kt`.
 *
 * Recognition posture:
 *   - Latin-script Default options. The other regional models (CJK,
 *     Devanagari, etc.) ship as separate Play modules and would each
 *     need their own dep + on-demand-download flow; out of scope for
 *     v1, follow-up if the user telemetry ever justifies it.
 *   - The `TextRecognizer` client is created lazily and held for the
 *     lifetime of this object, per ML Kit's recommendation. Callers
 *     usually scope a single recognizer instance per app process
 *     (held by `Application.onCreate`).
 *
 * Coordinate-system bridge:
 *   ML Kit returns `android.graphics.Rect` in PIXEL coordinates with
 *   origin top-left. The `OcrEngine` contract specifies normalized
 *   0..1 in image space, also origin top-left. We divide rects by the
 *   `InputImage`'s width/height to land in the contract's frame.
 *
 * Granularity:
 *   ML Kit emits both `TextBlock` (paragraph-grained) and `Line`
 *   tiers. We surface BOTH — one `OcrBlock(kind: Paragraph)` per
 *   `TextBlock` and one `OcrBlock(kind: Line)` per `Line`. This is
 *   richer than the iOS Vision impl, which only sees line-level
 *   observations; downstream consumers (e.g. the searchable-PDF
 *   prototype's invisible text layer) can pick the granularity
 *   that fits.
 *
 * Concurrency:
 *   `TextRecognizer.process(InputImage)` returns a `Task<Text>`. We
 *   bridge it to a coroutine via `suspendCancellableCoroutine` so
 *   callers can `await` it directly without pulling in
 *   `kotlinx-coroutines-play-services`.
 *
 * Failure mapping:
 *   - `IOException` from `InputImage.fromFilePath` → `ImageUnreadable`
 *     (file missing or can't be read off the URI).
 *   - Any other exception from `process` → `RecognitionFailed`
 *     (engine threw, returned an inconsistent payload, etc.).
 *   - `RecognizerInitFailed` is unused on Android — `TextRecognition.getClient`
 *     in v19 doesn't fail at client-init time; module-availability
 *     issues surface at `process` and land on `RecognitionFailed`.
 */

package app.releaf.shared.scan

import android.content.Context
import android.graphics.Rect
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class MlKitTextRecognizer(private val context: Context) : OcrEngine {

    /**
     * Held for the lifetime of this object. ML Kit's docs note that
     * recreating clients is wasteful — process-scoped recognizer
     * instances are cheap to keep around.
     */
    private val recognizer: TextRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    override suspend fun recognize(imageUri: Uri): OcrResult {
        val image = try {
            InputImage.fromFilePath(context, imageUri)
        } catch (e: IOException) {
            throw OcrException.ImageUnreadable(imageUri, e)
        }

        val text = try {
            recognizer.processSuspending(image)
        } catch (e: Exception) {
            throw OcrException.RecognitionFailed(e.message.orEmpty(), e)
        }

        return flatten(
            text         = text,
            imageWidth   = image.width.toDouble(),
            imageHeight  = image.height.toDouble(),
        )
    }

    // MARK: - Internals

    /**
     * Coroutine-friendly wrapper around `TextRecognizer.process`.
     * `suspendCancellableCoroutine` honors caller cancellation —
     * Tasks API doesn't expose a cancel hook, so the underlying ML Kit
     * job runs to completion regardless, but the coroutine bookkeeping
     * unwinds correctly on the caller side.
     */
    private suspend fun TextRecognizer.processSuspending(image: InputImage): Text =
        suspendCancellableCoroutine { cont ->
            process(image)
                .addOnSuccessListener { result -> cont.resume(result) }
                .addOnFailureListener { e      -> cont.resumeWithException(e) }
        }

    /**
     * Walk ML Kit's TextBlock → Line hierarchy into the contract's
     * `OcrBlock` shape. Empty pages return a well-formed empty
     * `OcrResult` (the contract says throwing is reserved for engine
     * failures, not "no text found").
     */
    private fun flatten(
        text: Text,
        imageWidth: Double,
        imageHeight: Double,
    ): OcrResult {
        val blocks = mutableListOf<OcrBlock>()
        val languageVotes = mutableMapOf<String, Int>()

        // ML Kit's Play-Services variant 19.0.1 exposes `confidence`
        // on neither `Text.TextBlock` nor `Text.Line` nor
        // `Text.Element` (as of the release pinned in
        // libs.versions.toml). We pass null through to every
        // OcrBlock here and to OcrResult.confidence — the
        // contract's `Double?` field captures this honestly. When ML
        // Kit ships a confidence API on a future revision, swap the
        // null literal for the real value.
        for (block in text.textBlocks) {
            // Paragraph tier — one OcrBlock per TextBlock. Skip
            // blocks with no bounding rect (defensive; should be
            // unreachable for blocks ML Kit actually returned).
            block.boundingBox?.let { rect ->
                blocks.add(
                    OcrBlock(
                        text       = block.text,
                        bbox       = normalizedBbox(rect, imageWidth, imageHeight),
                        confidence = null,
                        language   = block.recognizedLanguage.takeIf { it.isNotEmpty() },
                        kind       = OcrBlock.Kind.Paragraph,
                    ),
                )
                block.recognizedLanguage.takeIf { it.isNotEmpty() }?.let { lang ->
                    languageVotes[lang] = (languageVotes[lang] ?: 0) + 1
                }
            }

            // Line tier — one OcrBlock per Line. Lets downstream
            // renderers pick the granularity that fits the surface.
            for (line in block.lines) {
                line.boundingBox?.let { rect ->
                    blocks.add(
                        OcrBlock(
                            text       = line.text,
                            bbox       = normalizedBbox(rect, imageWidth, imageHeight),
                            confidence = null,
                            language   = line.recognizedLanguage.takeIf { it.isNotEmpty() },
                            kind       = OcrBlock.Kind.Line,
                        ),
                    )
                }
            }
        }

        // Most-voted language across the page's TextBlocks. ML Kit
        // surfaces per-block language detection; pages with mixed
        // scripts pick the dominant one. Null when no block had a
        // detected language (ML Kit's `recognizedLanguage` is
        // documented as returning the empty string when undetected,
        // which we filter out above).
        val dominantLanguage = languageVotes
            .maxByOrNull { it.value }
            ?.key

        return OcrResult(
            text          = text.text,
            blocks        = blocks,
            language      = dominantLanguage,
            // Null because ML Kit doesn't surface per-block
            // confidence on this variant (see for-loop comment).
            confidence    = null,
            engine        = "mlkit-latin-v2",
            // ML Kit's client SDK doesn't expose a stable revision /
            // version handle the way Apple Vision does. Leaving null
            // is honest; the `engine` string identifies the family.
            engineVersion = null,
        )
    }

    /**
     * ML Kit's `Rect` is in pixels with origin top-left (Android's
     * default). The contract is normalized 0..1, also origin
     * top-left. Just divide.
     */
    private fun normalizedBbox(rect: Rect, imageWidth: Double, imageHeight: Double): OcrBbox =
        OcrBbox(
            x      = rect.left.toDouble()                  / imageWidth,
            y      = rect.top.toDouble()                   / imageHeight,
            width  = (rect.right - rect.left).toDouble()   / imageWidth,
            height = (rect.bottom - rect.top).toDouble()   / imageHeight,
        )
}
