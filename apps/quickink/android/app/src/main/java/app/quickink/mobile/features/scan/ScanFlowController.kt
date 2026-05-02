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
import app.releaf.mobile.data.common.IsoClock
import app.releaf.mobile.data.common.Uuidv7
import app.releaf.mobile.data.notepad.NotepadDao
import app.releaf.mobile.data.notepad.NotepadEntry
import app.releaf.shared.scan.DocumentScanResult
import app.releaf.shared.scan.OcrPipeline
import app.releaf.shared.scan.PageOcr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

class ScanFlowController(
    private val userId: String,
    private val repository: CaptureRepository,
    private val pipeline: OcrPipeline,
    private val notepadDao: NotepadDao,
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

    /**
     * User-selected category for the in-flight capture. Bound to
     * the chip picker in `ScanReviewScreen`. Persisted to
     * `captures.category` via [setCategory] whenever the user
     * taps a chip; held here too so the chip's selected state
     * survives state-machine transitions.
     */
    private val _selectedCategory = MutableStateFlow<String?>(null)
    val selectedCategory: StateFlow<String?> = _selectedCategory.asStateFlow()

    /**
     * First-page preview JPEG of the in-flight capture (a
     * `content://` or `file://` URI string). Surfaced to
     * `ScanReviewScreen` so it can render the saved image below
     * the category picker. `null` outside an active scan pass.
     */
    private val _previewImageUri = MutableStateFlow<String?>(null)
    val previewImageUri: StateFlow<String?> = _previewImageUri.asStateFlow()

    private var activeJob: Job? = null

    /**
     * Called by the Home screen after `rememberDocumentScannerLauncher`'s
     * `onResult` fires.
     */
    fun onScanComplete(result: DocumentScanResult, category: String? = null) {
        // Cancel any previous in-flight pass before starting a new
        // one. The user could conceivably tap Scan twice in quick
        // succession; we don't dedupe at the launcher layer so
        // guard here.
        activeJob?.cancel()

        val captureId  = Uuidv7.generate()
        val totalPages = result.pageUris.size
        // Reset the picker selection so a fresh capture starts with
        // no category. The previous capture's choice was already
        // persisted to its own row.
        _selectedCategory.value = category
        _previewImageUri.value  = result.previewUri?.toString()
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
                    category   = category,
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
            // Page-ordered OCR text accumulator — drives the
            // append-to-today-entry pass after the OCR loop.
            // Indexed by `pageIndex` so out-of-order completion
            // stays correct in the final paste.
            val pageTexts = mutableMapOf<Int, String>()
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
                            pageTexts[page.pageIndex] = page.result.text
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

            // 3. Append the recognized text into today's
            //    `notepad_entries` row so the home recents rail
            //    surfaces it immediately. One entry per (user, day);
            //    multiple captures append to the same row. The
            //    row's category mirrors the latest capture's pick.
            if (pageTexts.isNotEmpty()) {
                appendOcrToTodayEntry(
                    pageTexts = pageTexts,
                    category  = _selectedCategory.value,
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
        _selectedCategory.value = null
        _previewImageUri.value  = null
    }

    /**
     * Picked-category persistence hook for the review screen's
     * chip row. Updates [selectedCategory] so the UI redraws, then
     * fires-and-forgets the SQL update against the in-flight
     * capture's row. No-ops when there's no active capture
     * (Idle / Failed).
     */
    fun setCategory(name: String?) {
        _selectedCategory.value = name
        val captureId = currentCaptureId() ?: return
        scope.launch {
            try {
                repository.setCategory(captureId, name)
            } catch (_: Exception) {
                // Best-effort: a transient SQL failure shouldn't
                // crash the review flow. The user can retap the
                // chip to re-issue the UPDATE.
            }
        }
    }

    private fun currentCaptureId(): String? = when (val current = _state.value) {
        is State.Recognizing -> current.captureId
        is State.Complete    -> current.captureId
        is State.Idle, is State.Failed -> null
    }

    // ─── Append-to-today's-entry ──────────────────────────────────

    /**
     * Append a freshly-recognized capture's OCR text to today's
     * `notepad_entries` row (creating it if missing). One entry
     * per (userId, entryDate); multiple captures of the same day
     * concatenate into the same row's `notes` column. The row's
     * `category` is overwritten with the latest capture's pick —
     * derived data, cheap to refresh, matches the design note in
     * `CategoryRepository`'s header.
     */
    private suspend fun appendOcrToTodayEntry(
        pageTexts: Map<Int, String>,
        category: String?,
    ) {
        val snippet = formatSnippet(pageTexts)
        if (snippet.isEmpty()) return
        val today = LocalDate.now().toString() // YYYY-MM-DD, local TZ
        val now   = IsoClock.nowIso()

        try {
            val existing = notepadDao.findLatestForDate(userId, today)
            if (existing != null) {
                val combined = if (existing.notes.isEmpty()) {
                    snippet
                } else {
                    existing.notes + "\n\n" + snippet
                }
                notepadDao.upsert(
                    existing.copy(
                        notes     = combined,
                        category  = category,
                        updatedAt = now,
                        dirty     = true,
                    ),
                )
            } else {
                notepadDao.upsert(
                    NotepadEntry(
                        id         = Uuidv7.generate(),
                        userId     = userId,
                        entryDate  = today,
                        category   = category,
                        notes      = snippet,
                        createdAt  = now,
                        updatedAt  = now,
                        dirty      = true,
                    ),
                )
            }
        } catch (e: Exception) {
            println("Append OCR to today entry failed: $e")
        }
    }

    private fun formatSnippet(pageTexts: Map<Int, String>): String =
        pageTexts.keys.sorted().mapNotNull { idx ->
            val text = pageTexts[idx]?.trim().orEmpty()
            if (text.isEmpty()) null else "## Page ${idx + 1}\n\n$text"
        }.joinToString(separator = "\n\n")
}
