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

import android.graphics.BitmapFactory
import android.location.Location
import app.quickink.mobile.data.capture.CaptureRepository
import app.quickink.mobile.data.capture.CapturedLocation
import app.quickink.mobile.data.capturelocation.CaptureLocationDao
import app.quickink.mobile.data.folder.FolderDao
import app.quickink.mobile.data.location.LocationDao
import app.quickink.mobile.data.tag.TagDao
import app.quickink.mobile.features.settings.SettingsPreferences
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
     * Application context — used by [onScanComplete] to read the
     * "Attach location to scans" preference and call
     * `LocationService.captureCurrent`. Optional with a null default
     * so existing test / preview construction sites keep compiling;
     * location capture silently no-ops when not supplied.
     */
    private val appContext: android.content.Context? = null,
    /**
     * DAO for the user's category list. Read once at the end of
     * each OCR pass so the controller can auto-pick a category from
     * the first word of the recognized text. Default `null` keeps
     * existing call sites (tests / previews) compiling — auto-match
     * silently no-ops when the DAO isn't supplied.
     */
    private val tagDao: TagDao? = null,
    /**
     * DAO used to look up the user's seeded "Unsorted" folder so each
     * fresh capture defaults to a definite folder selection on the
     * review screen rather than NULL. Optional with a null default so
     * existing test / preview construction sites keep compiling — the
     * default-folder seed silently no-ops when the DAO isn't supplied.
     */
    private val folderDao: FolderDao? = null,
    /**
     * Places + capture_locations join DAOs. Used to attach any saved
     * Place whose stored coordinates are near the captured GPS fix.
     * Optional so tests / previews can keep constructing the
     * controller without the workspace database.
     */
    private val locationDao: LocationDao? = null,
    private val captureLocationDao: CaptureLocationDao? = null,
    /**
     * Slice 4.2c — fired when a scan pass finishes and at least
     * one row has been written. Wired by `QuickInkRoot.MainShell`
     * to enqueue an analytics outbox row (the analytics-flush
     * worker drains opportunistically right after) and historically
     * to kick a Drive-sync push. Defaults to a no-op for tests /
     * previews / construction sites that don't want either coupling.
     *
     * Fires ONCE per pass — at `.Complete` (or right after a
     * single-row partial fail-path) — rather than per OCR row, so
     * a 30-page scan triggers one analytics enqueue, not 30. The
     * outbox dedupes by capture id anyway, but firing once at the
     * end avoids redundant work + log noise.
     *
     * The [PassSummary] argument carries every field
     * `AnalyticsRepository.enqueueCapture` needs — keeping it as a
     * single struct (rather than 7 positional args) means call
     * sites read top-down without arg-order accidents.
     */
    private val onPassComplete: (PassSummary) -> Unit = {},
) {

    /**
     * One-shot hook fired AFTER [onPassComplete]. Used by the Story
     * editor to pick up the just-captured row and insert it as a
     * story_item. Cleared to null after firing so a subsequent
     * unrelated capture from elsewhere doesn't accidentally land in
     * the wrong story.
     */
    var nextCompletionHandler: ((PassSummary) -> Unit)? = null

    /**
     * What [onPassComplete] receives. Mirrors the field set
     * `AnalyticsRepository.enqueueCapture` consumes — same field
     * names so the wiring at the call site reads as a 1:1 forward.
     */
    data class PassSummary(
        val captureId:  String,
        val source:     String,
        val pageCount:  Int,
        /**
         * Primary tag name attached to the capture by the end of
         * the pass (user pick or auto-match). Pre-A.3c this was
         * `captures.category`; post-drop it's just whichever tag
         * the controller attached via [CaptureRepository
         * .attachOrEnsurePrimaryTag]. Surfaced here so the
         * analytics enqueue picks the same label the user sees.
         */
        val primaryTagName: String?,
        val hasOcr:     Boolean,
        val ocrChars:   Int,
        /** ISO-8601 timestamp the user finished the scan pass. */
        val capturedAt: String,
    )

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
     * User-selected primary tag for the in-flight capture. Bound to
     * the chip picker in `ScanReviewScreen`. Persisted via
     * [setCategory] → `CaptureRepository.attachOrEnsurePrimaryTag`
     * (which writes into `capture_tags`); held here too so the
     * chip's selected state survives state-machine transitions.
     * Field name kept as `selectedCategory` for back-compat with
     * `ScanReviewScreen`'s observers, even though "category" is
     * now just a single-label view onto the tag join.
     */
    private val _selectedCategory = MutableStateFlow<String?>(null)
    val selectedCategory: StateFlow<String?> = _selectedCategory.asStateFlow()

    /**
     * User-selected folder for the in-flight capture. Bound to the
     * folder picker on `ScanReviewScreen`. Persisted via
     * [setFolder] → `CaptureRepository.setFolder` (which writes
     * `captures.folder_id`). Defaults to `null`; the first-launch
     * backfill assigns every unassigned capture to the seeded
     * "Unsorted" folder, so a capture without an explicit pick still
     * ends up filed correctly.
     */
    private val _selectedFolderId = MutableStateFlow<String?>(null)
    val selectedFolderId: StateFlow<String?> = _selectedFolderId.asStateFlow()

    /**
     * First-page preview JPEG of the in-flight capture (a
     * `content://` or `file://` URI string). Surfaced to
     * `ScanReviewScreen` so it can render the saved image below
     * the category picker. `null` outside an active scan pass.
     */
    private val _previewImageUri = MutableStateFlow<String?>(null)
    val previewImageUri: StateFlow<String?> = _previewImageUri.asStateFlow()

    /**
     * Currently-selected [PaperSize] for the in-flight capture.
     * Seeded by the auto-classifier in [onScanComplete] and updated
     * whenever the user taps a chip on `ScanReviewScreen` (via
     * [setPaperSize]). Defaults to [PaperSize.A4] between passes so
     * the review-screen preview doesn't flicker before scan-1.
     */
    private val _selectedPaperSize = MutableStateFlow(PaperSize.A4)
    val selectedPaperSize: StateFlow<PaperSize> = _selectedPaperSize.asStateFlow()

    /**
     * Source for the active review pass (`scan`, `import`, `photo`,
     * `video`, etc.). The review screen uses this to hide paper-size controls
     * for arbitrary camera media while keeping document/import flows
     * unchanged.
     */
    private val _currentSource = MutableStateFlow("scan")
    val currentSource: StateFlow<String> = _currentSource.asStateFlow()

    private var activeJob: Job? = null

    /**
     * Called by the Home screen after `rememberDocumentScannerLauncher`'s
     * `onResult` fires.
     *
     * @param source `"scan"` when the result came from the document
     *   scanner (the default), `"import"` when it came from the
     *   system photo picker, `"photo"` / `"video"` for QuickInk
     *   camera media. Persisted on the capture row for type labels.
     * @param paperSize Page-size class for the sustainability hero's
     *   per-page weight. Defaults to [PaperSize.A4]; the business-
     *   card camera surface passes [PaperSize.Card] so each card
     *   scan scores the bulk-print bonus.
     */
    fun onScanComplete(
        result: DocumentScanResult,
        category: String? = null,
        source: String = "scan",
        paperSize: PaperSize = PaperSize.A4,
    ) {
        // Cancel any previous in-flight pass before starting a new
        // one. The user could conceivably tap Scan twice in quick
        // succession; we don't dedupe at the launcher layer so
        // guard here.
        activeJob?.cancel()

        val captureId  = Uuidv7.generate()
        val totalPages = result.pageUris.size

        // Auto-classify the page-size class from the first page's
        // rectified aspect ratio when the caller hasn't pinned a
        // specific bucket (default [PaperSize.A4]). The card-mode
        // capture path explicitly passes [PaperSize.Card] so this
        // branch is skipped there — no risk of an in-frame ID card
        // being misread as a tiny A-series sheet. For A-series
        // ratios (where ratio alone can't tell A4 from A5), fall
        // back to the user's last-picked size so a power user who
        // routinely scans A5 doesn't have to flip the chip every
        // time. The user can still override per-scan on
        // ScanReviewScreen.
        //
        // Uses `BitmapFactory.Options.inJustDecodeBounds = true` so
        // the bitmap pixels are never actually loaded — we only need
        // the header's width/height. Fast and allocation-free.
        val resolvedPaperSize: PaperSize = run {
            if (paperSize != PaperSize.A4) return@run paperSize
            val ctx = appContext ?: return@run paperSize
            val firstUri = result.pageUris.firstOrNull() ?: return@run paperSize
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            try {
                ctx.contentResolver.openInputStream(firstUri).use { stream ->
                    BitmapFactory.decodeStream(stream, null, opts)
                }
            } catch (_: Exception) {
                return@run paperSize
            }
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return@run paperSize
            val detected = classifyPaperSize(opts.outWidth, opts.outHeight)
            if (detected == PaperSize.A4) {
                val lastPick = PaperSize.fromRaw(SettingsPreferences.readLastPaperSize(ctx))
                if (lastPick == PaperSize.A5) PaperSize.A5 else PaperSize.A4
            } else {
                detected
            }
        }
        // Stamp once at the start of the pass so retries / OCR-pipeline
        // jitter don't shift the analytics timestamp away from "when
        // the user actually finished scanning". The capturedAt field
        // ends up on `analytics_outbox` and on the backend's
        // `capture_events.captured_at` column.
        val capturedAt = IsoClock.nowIso()
        // Reset the picker selection so a fresh capture starts with
        // no category. The previous capture's choice was already
        // persisted to its own row.
        _selectedCategory.value  = category
        _previewImageUri.value   = result.previewUri?.toString()
        _selectedPaperSize.value = resolvedPaperSize
        _currentSource.value     = source
        // State.Recognizing is published AFTER the capture row is
        // in Room (below) so the voice-note pane mounts on a real
        // FK target. Previously this fired first and the row write
        // was gated behind a 5–10s location lookup, which let a
        // fast voice-note save race ahead of the parent row and
        // silently drop the clip onto a FOREIGN KEY constraint.

        activeJob = scope.launch {
            // 1. Persist the parent capture FIRST, with a null
            //    location placeholder. The voice-note pane (and OCR
            //    rows) FK against this id, so blocking the pane on
            //    a slow GPS fix used to drop clips when the user
            //    tapped stop before the row had landed. The
            //    location columns are filled in by the parallel
            //    update launched below once the fetch finishes.
            try {
                repository.insertCapture(
                    id         = captureId,
                    userId     = userId,
                    title      = null,
                    pdfUri     = result.pdfUri?.toString().orEmpty(),
                    previewUri = result.previewUri?.toString(),
                    pageCount  = totalPages,
                    source     = source,
                    paperSize  = resolvedPaperSize,
                    location   = null,
                )
                // Pre-attach the seeded `category` (post-A.3c: a
                // tag attach into `capture_tags`) when the caller
                // already knows the label — typically the
                // BusinessCardPostProcessor path that captures
                // ahead of OCR. Best-effort: a SQL failure here
                // leaves the capture row valid but un-tagged; the
                // OCR auto-pick + the user's manual retag both
                // recover.
                if (!category.isNullOrBlank()) {
                    try {
                        repository.attachOrEnsurePrimaryTag(
                            captureId = captureId,
                            userId    = userId,
                            name      = category,
                        )
                    } catch (_: Exception) { /* best-effort */ }
                }
                // Default-folder assignment — file the capture into
                // the seeded "Unsorted" folder so the review screen
                // lands on a definite selection and the row never
                // lives orphaned outside any folder. The user can
                // re-file via the folder buttons. Best-effort: a
                // failure here leaves `folder_id` NULL, which the
                // rest of the app already renders as Unsorted.
                val defaultFolder = try {
                    folderDao?.findDefault(userId)
                } catch (_: Exception) { null }
                if (defaultFolder != null) {
                    try {
                        repository.setFolder(
                            captureId = captureId,
                            folderId  = defaultFolder.id,
                        )
                        _selectedFolderId.value = defaultFolder.id
                    } catch (_: Exception) { /* best-effort */ }
                }
            } catch (e: Exception) {
                _state.value = State.Failed("Couldn't save scan: ${e.message.orEmpty()}")
                return@launch
            }

            // 2. Capture row exists — publish State so the voice-
            //    note pane mounts on a row that's already there.
            _state.value = State.Recognizing(
                captureId      = captureId,
                totalPages     = totalPages,
                completedPages = 0,
            )

            // 3. Location lookup runs in parallel with the OCR pass
            //    below. Same gating as before (`locationForScans-
            //    Enabled` + permission), same LocationService 5+5s
            //    timeout. The row is updated in two writes —
            //    locality fields and lat/lon — via [CaptureRepository
            //    .setLocation] (no-op when fetch returns null).
            launch {
                val location = captureLocationIfEnabled() ?: return@launch
                val saved = try {
                    repository.setLocation(captureId, location)
                    true
                } catch (_: Exception) { false }
                if (saved) {
                    attachNearbyPlace(captureId, location)
                }
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
                                        repository.attachOrEnsurePrimaryTag(
                                            captureId = captureId,
                                            userId    = userId,
                                            name      = match,
                                        )
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
                        repository.attachOrEnsurePrimaryTag(
                            captureId = captureId,
                            userId    = userId,
                            name      = match,
                        )
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

            // Hand the just-completed pass to the host
            // (QuickInkRoot.MainShell) so it can enqueue an
            // analytics-outbox row + opportunistically kick the
            // analytics flush worker. The host computes
            // `hasOcr` / `ocrChars` from the same numbers we
            // collected during the OCR loop. Drive-sync used to
            // also fan out from here; that's now user-initiated
            // only via the Settings → "Sync now" button (see the
            // QuickInkApp `auth: SignedIn` comment for rationale).
            val totalChars = pageTexts.values.sumOf { it.length }
            val summary = PassSummary(
                captureId      = captureId,
                source         = source,
                pageCount      = totalPages,
                primaryTagName = _selectedCategory.value,
                hasOcr         = successCount > 0,
                ocrChars       = totalChars,
                capturedAt     = capturedAt,
            )
            onPassComplete(summary)
            nextCompletionHandler?.let { hook ->
                nextCompletionHandler = null
                hook(summary)
            }
        }
    }

    /** Reset back to `Idle` — typically called on review-screen dismiss. */
    fun dismiss() {
        activeJob?.cancel()
        activeJob = null
        _state.value = State.Idle
        _selectedCategory.value = null
        _selectedFolderId.value = null
        _previewImageUri.value  = null
        _currentSource.value    = "scan"
    }

    /**
     * Picked-folder persistence hook for the review screen's
     * folder buttons. Updates [selectedFolderId] so the UI
     * redraws, then fires-and-forgets a `captures.folder_id`
     * write against the in-flight capture. No-ops when there's
     * no active capture. Pass `null` to clear back to the seeded
     * Unsorted folder (the backfill catches the next launch); the
     * UI doesn't expose that path today but the API is shaped
     * the same as [setCategory] for symmetry.
     */
    fun setFolder(folderId: String?) {
        _selectedFolderId.value = folderId
        val captureId = currentCaptureId() ?: return
        val pickedId  = folderId ?: return
        scope.launch {
            try {
                repository.setFolder(captureId = captureId, folderId = pickedId)
            } catch (_: Exception) {
                // Best-effort: a transient SQL failure shouldn't
                // crash the review flow. The user can retap the
                // folder button to re-issue the write.
            }
        }
    }

    /**
     * Picked-paper-size persistence hook for the review screen's
     * paper-size chip. Updates the in-flight capture's `paper_size`
     * column AND stamps the choice as the user's `lastPaperSize`
     * preference so the next scan can default to the same bucket
     * (relevant for A4 vs A5 — the chip is the only way to
     * disambiguate within the A-series ratio bucket). Fires-and-
     * forgets; no-ops when there's no active capture.
     */
    fun setPaperSize(paperSize: PaperSize) {
        _selectedPaperSize.value = paperSize
        appContext?.let { SettingsPreferences.writeLastPaperSize(it, paperSize.raw) }
        val captureId = currentCaptureId() ?: return
        scope.launch {
            try {
                repository.setPaperSize(captureId = captureId, paperSize = paperSize)
            } catch (_: Exception) {
                // Best-effort, same rationale as [setFolder].
            }
        }
    }

    /**
     * Picked-tag persistence hook for the review screen's chip
     * row. Updates [selectedCategory] so the UI redraws, then
     * fires-and-forgets a tag attach against the in-flight
     * capture's row through `capture_tags`. No-ops when there's
     * no active capture (Idle / Failed). Pass `null`/blank to
     * clear the current attached tags.
     */
    fun setCategory(name: String?) {
        _selectedCategory.value = name
        val captureId = currentCaptureId() ?: return
        scope.launch {
            try {
                repository.attachOrEnsurePrimaryTag(
                    captureId = captureId,
                    userId    = userId,
                    name      = name,
                )
            } catch (_: Exception) {
                // Best-effort: a transient SQL failure shouldn't
                // crash the review flow. The user can retap the
                // chip to re-issue the attach.
            }
        }
    }

    private fun currentCaptureId(): String? = when (val current = _state.value) {
        is State.Recognizing -> current.captureId
        is State.Complete    -> current.captureId
        is State.Idle, is State.Failed -> null
    }

    // ─── Geolocation ──────────────────────────────────────────────

    /**
     * Resolve the device's current location for the capture row, or
     * return `null` when the feature is off, permission isn't
     * granted, the context wasn't wired through, or the system
     * can't produce a fix. Driven by `SettingsPreferences.
     * locationForScansEnabled` — when the user has turned the
     * toggle off in Settings we short-circuit without touching the
     * system service. The capture flow treats `null` as "save
     * without coordinates"; the Details card simply omits the Area
     * / City rows in that case.
     */
    private suspend fun captureLocationIfEnabled(): CapturedLocation? {
        val ctx = appContext
        if (ctx == null) {
            android.util.Log.i("QuickInkLocation", "gate: appContext null, skipping capture")
            return null
        }
        val prefs = app.quickink.mobile.features.settings.SettingsPreferences(ctx)
        android.util.Log.i("QuickInkLocation", "gate: toggleEnabled=${prefs.locationForScansEnabled}")
        if (!prefs.locationForScansEnabled) {
            android.util.Log.i("QuickInkLocation", "gate: toggle off, skipping capture")
            return null
        }
        val granted = LocationService.hasPermission(ctx)
        android.util.Log.i("QuickInkLocation", "gate: hasPermission=$granted")
        if (!granted) {
            android.util.Log.i("QuickInkLocation", "gate: permission not granted, skipping capture")
            return null
        }
        val result = LocationService.captureCurrent(ctx)
        android.util.Log.i("QuickInkLocation", "gate: result locality=${result?.locality} subLocality=${result?.subLocality} lat=${result?.latitude} lon=${result?.longitude}")
        return result
    }

    /**
     * Auto-tag the capture with saved Places whose coordinates are
     * close to the captured GPS fix. This lives in the controller,
     * not just the review screen, so photo/video captures that move
     * straight into the voice-note flow still get their place join.
     */
    private suspend fun attachNearbyPlace(
        captureId: String,
        captured: CapturedLocation,
    ) {
        val placesDao = locationDao ?: return
        val joinsDao = captureLocationDao ?: return
        val places = try {
            placesDao.listActive(userId)
        } catch (_: Exception) {
            return
        }
        if (places.isEmpty()) return
        val attachedIds = try {
            joinsDao.listLocationIdsForCapture(captureId).toSet()
        } catch (_: Exception) {
            emptySet()
        }
        val results = FloatArray(1)
        for (place in places) {
            val lat = place.latitude ?: continue
            val lon = place.longitude ?: continue
            if (place.id in attachedIds) continue
            Location.distanceBetween(
                captured.latitude,
                captured.longitude,
                lat,
                lon,
                results,
            )
            if (results[0] <= AUTO_ATTACH_PLACE_RADIUS_METERS) {
                try {
                    joinsDao.attachLocation(
                        joinId = Uuidv7.generate(),
                        captureId = captureId,
                        locationId = place.id,
                        source = "ai-suggested",
                        timestamp = IsoClock.nowIso(),
                    )
                } catch (_: Exception) { /* best-effort */ }
            }
        }
    }

    // ─── Append-to-today's-entry ──────────────────────────────────

    /**
     * Append a freshly-recognized capture's OCR text to today's
     * `notepad_entries` row (creating it if missing). One entry
     * per (userId, entryDate); multiple captures of the same day
     * concatenate into the same row's `notes` column. The row's
     * `category` is overwritten with the latest capture's pick —
     * derived data, cheap to refresh, matches the design note in
     * `TagRepository`'s header.
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
     * No-ops when [tagDao] wasn't injected (tests / previews).
     */
    private suspend fun matchCategoryName(tokens: List<String>): String? {
        val dao = tagDao ?: return null
        if (tokens.isEmpty()) return null
        return try {
            val cats = dao.listActive(userId)
            // Try the widest window first (two-word phrase), then
            // fall back to just the first word. A single-token
            // input only runs the fallback iteration.
            for (window in tokens.size downTo 1) {
                val needle = depluralizePhrase(tokens.take(window).joinToString(" "))
                if (needle.isEmpty()) continue
                // Pass 1: canonical (depluralized) name match.
                val canonical = cats.firstOrNull { depluralizePhrase(it.name) == needle }
                if (canonical != null) return canonical.name
                // Pass 2: alias match — categories with known
                // synonyms / OCR-error variants that don't reduce
                // to the canonical via depluralization alone (e.g.
                // "card" / "businesscard" / "8usiness" all map to
                // "Business Card").
                val aliasMatch = cats.firstOrNull { cat ->
                    aliasesFor(cat.name).any { alias ->
                        depluralizePhrase(alias) == needle
                    }
                }
                if (aliasMatch != null) return aliasMatch.name
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Synonyms / OCR-error variants for a category name that the
     * canonical depluralization pass wouldn't catch. Matched
     * case-insensitively against the active category list; returning
     * a non-empty list adds an extra pass to [matchCategoryName] that
     * compares the OCR needle against each alias (after
     * depluralization). Empty for unknown categories, which fall
     * through to the canonical-only match. Mirror of iOS
     * `ScanFlowController.aliasesFor(_:)`.
     */
    private fun aliasesFor(canonicalName: String): List<String> =
        when (canonicalName.lowercase()) {
            "business card" -> listOf(
                // "card" alone, the no-space mash-up, and the common
                // OCR misread where ML Kit decodes "B" as "8".
                "card",
                "businesscard",
                "8usiness",
                "8usinesscard",
                "8usiness card",
            )
            else -> emptyList()
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

    private companion object {
        private const val AUTO_ATTACH_PLACE_RADIUS_METERS = 150f
    }
}
