/*
 * DrivePath.kt
 *
 * Deterministic Drive paths per `docs/DRIVE_SCHEMA.md` §"Root layout".
 * Every entity has one and only one canonical path derived from its kind
 * + id (+ bucket key for date-sharded kinds). Sync callers never invent
 * paths — this file is the single source of truth.
 *
 * Scope deviation from the spec (intentional):
 *
 *   The spec has notebooks/chapters/pages in a nested tree
 *   (`notebooks/{nb}/{ch}/{page}.json`). This implementation keeps them
 *   flat (`notebooks/{id}.json`, `chapters/{id}.json`,
 *   `pages/{id}.json`) because the manifest is the source of truth for
 *   "what exists and where it lives" — paths are just storage detail.
 *   Flat paths are a pure function of the row's own id, so upload doesn't
 *   need a parent-lookup step. Notepad entries and daily logs stay
 *   date-bucketed as the spec requires (`yyyy/mm/` / `yyyy/yyyy-mm-dd`)
 *   because those kinds benefit from directory-listing fan-out.
 *
 *   A future migration can renest by updating this file and re-uploading
 *   every entity (then updating `manifest.json` last). The
 *   `schema_version.major` bump is what gates clients across the change.
 */

package app.releaf.mobile.data.sync

object DrivePath {

    /** User-visible top-level folder in the user's Drive. */
    const val ROOT_FOLDER = "Releaf"

    /** Manifest filename, under the root folder. */
    const val MANIFEST = "manifest.json"

    // ---- entity "kind" strings, stable on the wire ----
    const val KIND_NOTEBOOK       = "notebook"
    const val KIND_CHAPTER        = "chapter"
    const val KIND_PAGE           = "page"
    const val KIND_NOTEPAD_ENTRY  = "notepad_entry"
    const val KIND_DAILY_LOG      = "daily_log"
    const val KIND_CAPTURE        = "capture"
    const val KIND_OCR_RESULT     = "ocr_result"
    const val KIND_TASK           = "task"
    const val KIND_TAG            = "tag"
    const val KIND_PROJECT        = "project"
    const val KIND_REFERENCE_LINK = "reference_link"
    const val KIND_PAGE_TEMPLATE  = "page_template"
    /** QuickInk-only — user-configurable category for [`captures.category`]. */
    const val KIND_CATEGORY       = "category"

    // ---- folder names (no trailing slash; join with `/`) ----
    const val FOLDER_NOTEBOOKS       = "notebooks"
    const val FOLDER_CHAPTERS        = "chapters"
    const val FOLDER_PAGES           = "pages"
    const val FOLDER_NOTEPAD_ENTRIES = "notepad_entries"
    const val FOLDER_DAILY_LOGS      = "daily_logs"
    const val FOLDER_CAPTURES        = "captures"
    /** QuickInk's OCR-result tree — `ocr/{captureId}/page-{N}.json`. */
    const val FOLDER_OCR             = "ocr"
    /** QuickInk's category list — `categories/{id}.json`. */
    const val FOLDER_CATEGORIES      = "categories"
    const val FOLDER_TASKS           = "tasks"
    const val FOLDER_TOMBSTONES      = "tombstones"

    // ---- path builders ----

    fun notebook(id: String): String = "$FOLDER_NOTEBOOKS/$id.json"

    fun chapter(id: String): String = "$FOLDER_CHAPTERS/$id.json"

    fun page(id: String): String = "$FOLDER_PAGES/$id.json"

    /** `entry_date` is `YYYY-MM-DD`; bucket by year + month. */
    fun notepadEntry(entryDate: String, entryId: String): String {
        require(isYyyyMmDd(entryDate)) { "entryDate must be YYYY-MM-DD, got $entryDate" }
        val yyyy = entryDate.substring(0, 4)
        val mm   = entryDate.substring(5, 7)
        return "$FOLDER_NOTEPAD_ENTRIES/$yyyy/$mm/$entryId.json"
    }

    /** `log_date` is `YYYY-MM-DD`; bucket by year, filename == date. */
    fun dailyLog(logDate: String): String {
        require(isYyyyMmDd(logDate)) { "logDate must be YYYY-MM-DD, got $logDate" }
        val yyyy = logDate.substring(0, 4)
        return "$FOLDER_DAILY_LOGS/$yyyy/$logDate.json"
    }

    fun task(id: String): String = "$FOLDER_TASKS/$id.json"

    /** QuickInk's per-capture file — `captures/{id}.json`. */
    fun capture(id: String): String = "$FOLDER_CAPTURES/$id.json"

    /**
     * Date-bucketed QuickInk capture path —
     * `{yyyy}/{mm}/{dd}/{id}.json`, relative to the data source's
     * drive root. Used by the QuickInk sync data source so scans land
     * under year/month/day folders inside `Thoughtbasics/QuickInk/...`.
     */
    fun quickInkCapture(createdAt: String, id: String): String =
        "${quickInkDateBucket(createdAt)}/$id.json"

    /**
     * QuickInk's per-page OCR-result file —
     * `ocr/{captureId}/page-{pageIndex}.json`. Page index is 0-based,
     * matching `ocr_results.page_index`.
     */
    fun ocrResult(captureId: String, pageIndex: Int): String =
        "$FOLDER_OCR/$captureId/page-$pageIndex.json"

    /**
     * Date-bucketed QuickInk OCR-result path —
     * `{yyyy}/{mm}/{dd}/{captureId}/page-{N}.json`. Co-locates the
     * OCR rows with their parent capture's day folder.
     */
    fun quickInkOcrResult(createdAt: String, captureId: String, pageIndex: Int): String =
        "${quickInkDateBucket(createdAt)}/$captureId/page-$pageIndex.json"

    /** QuickInk's per-category file — `categories/{id}.json`. */
    fun category(id: String): String = "$FOLDER_CATEGORIES/$id.json"

    /**
     * `YYYY/MM/DD` triplet derived from an ISO-8601 timestamp's date
     * prefix. Falls back to `0000/00/00` for malformed input so the
     * sync layer never crashes on a single bad row.
     */
    private fun quickInkDateBucket(iso: String): String {
        if (iso.length < 10) return "0000/00/00"
        return "${iso.substring(0, 4)}/${iso.substring(5, 7)}/${iso.substring(8, 10)}"
    }

    fun tombstone(id: String): String = "$FOLDER_TOMBSTONES/$id.json"

    // ---- helpers ----

    private fun isYyyyMmDd(s: String): Boolean =
        s.length == 10 && s[4] == '-' && s[7] == '-' &&
        s.substring(0, 4).all(Char::isDigit) &&
        s.substring(5, 7).all(Char::isDigit) &&
        s.substring(8, 10).all(Char::isDigit)
}
