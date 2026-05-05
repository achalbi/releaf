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
import app.quickink.mobile.data.category.CategoryDao
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
     * DAO for the user's category list. Read once at the end of
     * each OCR pass so the controller can auto-pick a category from
     * the first word of the recognized text. Default `null` keeps
     * existing call sites (tests / previews) compiling — auto-match
     * silently no-ops when the DAO isn't supplied.
     */
    private val categoryDao: CategoryDao? = null,
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
     *
     * @param source `"scan"` when the result came from the document
     *   scanner (the default), `"import"` when it came from the
     *   system photo picker. Persisted on the capture row so the
     *   Library cards can render an "Import" pill.
     */
    fun onScanComplete(
        result: DocumentScanResult,
        category: String? = null,
        source: String = "scan",
    ) {
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
                    source     = source,
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

                            // Fast-path auto-pick: as soon as page 0
                            // (the first physical page) lands, try
                            // to match its leading tokens against
                            // the user's categories so the review
                            // screen's chip flips on without
                            // waiting for the rest of a multi-page
                            // pipeline. Two-word phrases ("Meeting
                            // Notes") are tried before the first
                            // word alone — see [matchCategoryName].
                            // Subsequent pages skip this branch —
                            // only page 1 drives the auto-pick.
                            // The post-loop block below still runs
                            // as a fallback when page 0's OCR fails
                            // entirely.
                            if (page.pageIndex == 0 &&
                                _selectedCategory.value == null
                            ) {
                                val tokens = extractLeadingTokens(pageTexts)
                                val match = matchCategoryName(tokens)
                                if (match != null) {
                                    _selectedCategory.value = match
                                    try {
                                        repository.setCategory(captureId, match)
                                    } catch (_: Exception) { /* best-effort */ }
                                }
                            }
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

            // 3. Auto-pick fallback. The fast-path inside the loop
            //    (above) handles the common case — page 0 succeeds
            //    and the chip flips on the moment its OCR lands.
            //    This block catches the edge case where page 0 OCR
            //    failed but a later page succeeded: we still try
            //    the lowest-indexed available page's leading tokens
            //    so the user gets some auto-match instead of none.
            //    Skipped when the user pre-picked a category, the
            //    fast-path already matched, or the DAO wasn't
            //    injected.
            if (_selectedCategory.value == null) {
                val tokens = extractLeadingTokens(pageTexts)
                val match = matchCategoryName(tokens)
                if (match != null) {
                    _selectedCategory.value = match
                    // Persist on the in-flight capture row so a
                    // later restart restores the auto-pick. Best-
                    // effort — silent failure leaves the chip
                    // unselected on disk but in-memory selection
                    // wins for the rest of this pass.
                    try {
                        repository.setCategory(captureId, match)
                    } catch (_: Exception) { /* best-effort */ }
                }
            }

            // 4. Auto-populate the capture's title now that category
            //    + OCR are both settled. Priority:
            //      (a) the picked category, when present;
            //      (b) otherwise, the first two words of the
            //          earliest non-blank OCR page.
            //    The user can edit the title later from the scan
            //    detail screen — that write also goes through
            //    `setTitle`, so the latest value wins. Best-effort:
            //    a SQL failure here leaves the title null and the
            //    Library card falls back to its existing cascade.
            val autoTitle = computeInitialTitle(
                category  = _selectedCategory.value,
                pageTexts = pageTexts,
            )
            if (autoTitle != null) {
                try {
                    repository.setTitle(captureId, autoTitle)
                } catch (_: Exception) { /* best-effort */ }
            }

            // 5. Append the recognized text into today's
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

    /**
     * Compute the initial title to stamp on a freshly-captured row.
     * Priority:
     *   (1) the picked / auto-matched [category], trimmed — explicit
     *       tagging is the strongest signal of intent;
     *   (2) the first two words of the earliest non-blank OCR page —
     *       gives every untagged capture a readable preview header.
     * Returns `null` when neither signal is available; the caller
     * leaves the title column at NULL so the Library card's existing
     * "Untitled scan" fallback handles it.
     */
    private fun computeInitialTitle(
        category: String?,
        pageTexts: Map<Int, String>,
    ): String? {
        val cat = category?.trim().orEmpty()
        if (cat.isNotEmpty()) return cat

        val firstKey = pageTexts.keys.sorted().firstOrNull { idx ->
            !pageTexts[idx].isNullOrBlank()
        } ?: return null
        val text = pageTexts[firstKey]?.trim().orEmpty()
        if (text.isEmpty()) return null

        val words = text
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
        if (words.isEmpty()) return null
        return words.take(2).joinToString(" ")
    }

    // ─── Auto-category from leading OCR tokens ────────────────────

    /**
     * Pull up to [maxTokens] leading word tokens from the lowest-
     * indexed page's OCR text. Strips leading/trailing whitespace,
     * splits on whitespace runs, and trims any non-alphanumeric
     * padding so `"Ideas,"` / `"Ideas."` / `"  ideas "` all reduce
     * to `"Ideas"`. Returns an empty list when there's no usable
     * text — callers should treat that as "no auto-match".
     */
    private fun extractLeadingTokens(
        pageTexts: Map<Int, String>,
        maxTokens: Int = 2,
    ): List<String> {
        val firstKey = pageTexts.keys.minOrNull() ?: return emptyList()
        val raw = pageTexts[firstKey] ?: return emptyList()
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return emptyList()
        // Filter empty-after-strip tokens *before* taking the
        // window, so a stray leading "—"/"•"/"1." doesn't eat into
        // our maxTokens budget. With the wrong order, OCR text like
        // "— Meeting Notes" would yield just ["Meeting"] instead of
        // ["Meeting", "Notes"] and a "Meeting Notes" category would
        // miss the auto-match.
        return trimmed
            .split(Regex("\\s+"))
            .map { tok -> tok.trim { c -> !c.isLetterOrDigit() } }
            .filter { it.isNotEmpty() }
            .take(maxTokens)
    }

    /**
     * Look up a category whose name matches the leading OCR
     * [tokens] case- and number-insensitively, scoped to the
     * current `userId`. The widest window is tried first — for a
     * two-token input like `["Meeting", "Notes"]` we check the
     * full phrase against multi-word categories before falling
     * back to just `"Meeting"`. Each token (on both sides) is
     * stemmed via [depluralize] so "Idea" still matches "Ideas",
     * "Story" still matches "Stories", and so on. Returns the
     * canonical (database-cased) name on a hit, `null` otherwise.
     * No-ops when [categoryDao] wasn't injected (tests / previews).
     */
    private suspend fun matchCategoryName(tokens: List<String>): String? {
        val dao = categoryDao ?: return null
        if (tokens.isEmpty()) return null
        return try {
            val cats = dao.listActive(userId)
            // Try the widest window first (two-word phrase), then
            // fall back to just the first word. A single-token
            // input only runs the fallback iteration.
            for (window in tokens.size downTo 1) {
                val needle = depluralizePhrase(tokens.take(window).joinToString(" "))
                if (needle.isEmpty()) continue
                val match = cats.firstOrNull { depluralizePhrase(it.name) == needle }
                if (match != null) return match.name
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Whitespace-split tokenizer + per-token depluralizer. Lets
     * `"Meeting Notes"` reduce to `"meeting note"` so a category
     * named "Meeting Notes" still matches a scan starting with
     * "Meeting Note" (or vice versa). Empty input → empty stem.
     */
    private fun depluralizePhrase(s: String): String =
        s.split(Regex("\\s+")).joinToString(" ") { depluralize(it) }

    /**
     * Reduce an English word to its (rough) singular stem so we can
     * compare a scanned token like "Ideas" against a category named
     * "Idea" (or vice versa). Rules cover the common regular cases:
     *
     *   - `-ies` → `-y`     (stories → story)
     *   - `-ches`/`-shes`/`-xes`/`-zes`/`-sses` → drop `-es`
     *                       (boxes → box, classes → class)
     *   - `-ss`             → kept as-is (class stays class)
     *   - trailing `-s`     → dropped (ideas → idea, votes → vote)
     *
     * Irregular plurals (mice, geese, children) aren't handled —
     * they fall through to a literal exact-match. Returns the
     * lower-cased stem.
     */
    private fun depluralize(s: String): String {
        val lower = s.lowercase()
        if (lower.length <= 2) return lower
        return when {
            lower.endsWith("ies") -> lower.dropLast(3) + "y"
            lower.endsWith("ches") || lower.endsWith("shes") ||
            lower.endsWith("xes")  || lower.endsWith("zes")  ||
            lower.endsWith("sses") -> lower.dropLast(2)
            lower.endsWith("ss") -> lower
            lower.endsWith("s")  -> lower.dropLast(1)
            else -> lower
        }
    }
}
