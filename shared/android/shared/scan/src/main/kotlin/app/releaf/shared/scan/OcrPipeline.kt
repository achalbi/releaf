/*
 * OcrPipeline.kt
 *
 * Multi-page parallel OCR over the `OcrEngine` contract. Sits on top
 * of an injected engine and runs recognition on N page Uris with
 * bounded concurrency, emitting per-page results as a `Flow<PageOcr>`
 * so callers can drive a "page X of Y" progress UI.
 *
 * Mirror of `OcrPipeline.swift` in `ReleafCoreScan`. Per
 * QUICKINK_PROPOSAL.md §6, multi-page parallel OCR is an explicit
 * Phase 3 piece sitting atop the engine, separate from the engine
 * itself so a future cloud-OCR engine drops in unchanged.
 *
 * Design:
 *
 *   - Stream output (`Flow<PageOcr>`) instead of a one-shot return,
 *     so callers can update progress UI as pages complete. The flow
 *     emits exactly `uris.size` elements before completing; elements
 *     arrive in *completion* order, not input order — caller indexes
 *     by `PageOcr.pageIndex`.
 *
 *   - Bounded concurrency via `flatMapMerge(concurrency = …)`.
 *     Default 3 concurrent recognitions, tunable via the constructor
 *     parameter. ML Kit's text recognition is CPU-bound; unbounded
 *     concurrency thrashes the engine on multi-page docs.
 *
 *   - Per-page failure ≠ batch failure. One page's `OcrException`
 *     lands as `PageOcr.Failure(pageIndex, error)`; the rest of the
 *     batch keeps running. Callers wanting "abort on first failure"
 *     can throw / break out of their `collect { }` block — the
 *     `Flow` cancels the producing scope, which propagates to the
 *     `flatMapMerge` children, so remaining pages don't start.
 *
 *   - Cancellation: when the collector cancels (CancellationException
 *     up the call chain), `flatMapMerge`'s structured-concurrency
 *     teardown cancels the per-page coroutines. Pages that have
 *     already submitted to ML Kit's `Task` API finish that page on
 *     the background (ML Kit's API has no cancellation hook). Pages
 *     that haven't started don't. So a cancel mid-batch wastes at
 *     most `concurrency` pages worth of CPU.
 */

package app.releaf.shared.scan

import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow

class OcrPipeline(
    private val engine: OcrEngine,
    private val concurrency: Int = 3,
) {
    init {
        require(concurrency >= 1) { "concurrency must be >= 1" }
    }

    /**
     * Flow of per-page results. Emits exactly `uris.size` elements
     * before completing. Each element is a [PageOcr] carrying the
     * input page index alongside either a successful [OcrResult] or
     * the [OcrException] that page hit. Pass `uris` in the input
     * order you want indexed by; the flow emits in completion order
     * across the batch.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun recognizePages(uris: List<Uri>): Flow<PageOcr> =
        uris.withIndex().asFlow()
            .flatMapMerge(concurrency = concurrency) { (index, uri) ->
                flow { emit(processOne(index, uri)) }
            }

    // MARK: - Internals

    private suspend fun processOne(index: Int, uri: Uri): PageOcr =
        try {
            PageOcr.Success(pageIndex = index, result = engine.recognize(uri))
        } catch (e: CancellationException) {
            // Propagate cancellation; let Flow's structured-
            // concurrency teardown handle the rest. Don't translate
            // into a Failure — the consumer is already going away.
            throw e
        } catch (e: OcrException) {
            PageOcr.Failure(pageIndex = index, error = e)
        } catch (e: Exception) {
            // Engine impls SHOULD throw `OcrException`, but if any
            // third-party engine ships in the future and throws
            // something else, fold it onto the closest case so the
            // pipeline's contract stays predictable.
            PageOcr.Failure(
                pageIndex = index,
                error     = OcrException.RecognitionFailed(e.message.orEmpty(), e),
            )
        }
}

/**
 * One page's OCR outcome. `pageIndex` matches the caller's input
 * list — caller maps back to the original page Uri via that index.
 */
sealed class PageOcr {
    abstract val pageIndex: Int

    data class Success(
        override val pageIndex: Int,
        val result: OcrResult,
    ) : PageOcr()

    data class Failure(
        override val pageIndex: Int,
        val error: OcrException,
    ) : PageOcr()
}
