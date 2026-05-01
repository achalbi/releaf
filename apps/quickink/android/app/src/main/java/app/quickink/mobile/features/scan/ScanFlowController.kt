/*
 * ScanFlowController.kt
 *
 * Drives the Home → Scan → OCR review flow:
 *   1. UI calls `onScanComplete(result)` with the
 *      `DocumentScanResult` from `rememberDocumentScannerLauncher`.
 *   2. Controller persists a fresh `captures` row.
 *   3. Controller kicks off `OcrPipeline.recognizePages(pageUris)`,
 *      writing each successful `OcrResult` into `ocr_results` as
 *      it arrives.
 *   4. State transitions: `Idle` → `Recognizing(progress)`
 *      → `Complete(captureId, success/total)`.
 *
 * Mirror of `ScanFlowController.swift`. Plain Kotlin class with
 * its own `CoroutineScope` injected at construction (rather than
 * an Android `ViewModel`) — keeps the scaffold simple, fits the
 * Compose `remember { ScanFlowController(...) }` ownership
 * pattern. Survival across config changes lands with the proper
 * nav-graph wiring in Slice 6.
 */

package app.quickink.mobile.features.scan

import app.quickink.mobile.data.capture.CaptureRepository
import app.releaf.mobile.data.common.Uuidv7
import app.releaf.shared.scan.DocumentScanResult
import app.releaf.shared.scan.OcrPipeline
import app.releaf.shared.scan.PageOcr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ScanFlowController(
    private val userId: String,
    private val repository: CaptureRepository,
    private val pipeline: OcrPipeline,
    private val scope: CoroutineScope,
    /**
     * Slice 4.2c — fired when a scan pass finishes and at least
     * one row has been written. Wired by `QuickInkRoot.MainShell`
     * to call `QuickInkSyncScheduler.requestImmediate(context)` so
     * the user's just-completed scan pushes to Drive in seconds
     * instead of waiting for the 15-minute periodic. Defaults to a
     * no-op for tests / previews / construction sites that don't
     * want sync coupling.
     *
     * Fires ONCE per pass — at `.Complete` (or right after a
     * single-row partial fail-path) — rather than per OCR row, so
     * a 30-page scan triggers one sync kick, not 30. WorkManager's
     * `ExistingWorkPolicy.KEEP` would coalesce repeated fires
     * anyway, but firing once at the end avoids the edge case
     * where row 1's enqueued work runs before row 30 lands and
     * row 30 misses its window.
     */
    private val onPassComplete: () -> Unit = {},
) {
    sealed class State {
        data object Idle : State()

        /** `completedPages` includes both successes and failures. */
        data class Recognizing(
            val captureId: String,
            val totalPages: Int,
            val completedPages: Int,
        ) : State()

        data class Complete(
            val captureId: String,
            val totalPages: Int,
            val successCount: Int,
        ) : State()

        data class Failed(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var activeJob: Job? = null

    /**
     * Called by the Home screen after `rememberDocumentScannerLauncher`'s
     * `onResult` fires.
     */
    fun onScanComplete(result: DocumentScanResult) {
        // Cancel any previous in-flight pass before starting a new
        // one. The user could conceivably tap Scan twice in quick
        // succession; we don't dedupe at the launcher layer so
        // guard here.
        activeJob?.cancel()

        val captureId  = Uuidv7.generate()
        val totalPages = result.pageUris.size
        _state.value = State.Recognizing(
            captureId      = captureId,
            totalPages     = totalPages,
            completedPages = 0,
        )

        activeJob = scope.launch {
            // 1. Persist the parent capture so OCR row foreign
            //    keys have something to reference.
            try {
                repository.insertCapture(
                    id         = captureId,
                    userId     = userId,
                    title      = null,
                    pdfUri     = result.pdfUri?.toString().orEmpty(),
                    previewUri = result.previewUri?.toString(),
                    pageCount  = totalPages,
                )
            } catch (e: Exception) {
                _state.value = State.Failed("Couldn't save scan: ${e.message.orEmpty()}")
                return@launch
            }

            // 2. Stream OCR results, persisting each one as it
            //    lands. Pipeline emits in completion order; we rely
            //    on the (capture_id, page_index) UNIQUE index to
            //    keep ordering stable for later reads.
            var successCount = 0
            var completed    = 0
            pipeline.recognizePages(result.pageUris).collect { page ->
                completed += 1
                when (page) {
                    is PageOcr.Success -> {
                        try {
                            repository.insertOcrResult(
                                captureId = captureId,
                                pageIndex = page.pageIndex,
                                result    = page.result,
                            )
                            successCount += 1
                        } catch (e: Exception) {
                            // Persistence error on a single page — log,
                            // continue. Capture row + other pages still
                            // valid; user gets a partial result rather
                            // than empty capture. No structured logger
                            // yet; println is fine for dev builds.
                            println("CaptureRepository.insertOcrResult failed for page ${page.pageIndex}: $e")
                        }
                    }
                    is PageOcr.Failure -> {
                        println("OCR failed for page ${page.pageIndex}: ${page.error.message}")
                    }
                }
                _state.value = State.Recognizing(
                    captureId      = captureId,
                    totalPages     = totalPages,
                    completedPages = completed,
                )
            }

            _state.value = State.Complete(
                captureId    = captureId,
                totalPages   = totalPages,
                successCount = successCount,
            )

            // Slice 4.2c — kick an immediate sync so the just-
            // captured scan + OCR rows land on Drive without
            // waiting for the 15-min periodic. The capture row
            // itself was always persisted (we'd have early-returned
            // to State.Failed otherwise); some OCR rows may have
            // failed individually, but those that did persist are
            // still worth pushing.
            onPassComplete()
        }
    }

    /** Reset back to `Idle` — typically called on review-screen dismiss. */
    fun dismiss() {
        activeJob?.cancel()
        activeJob = null
        _state.value = State.Idle
    }
}
