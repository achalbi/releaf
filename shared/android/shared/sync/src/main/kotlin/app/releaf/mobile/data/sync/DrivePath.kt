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
    /** QuickInk-only — user-configurable category for [`captures.category`].
     *  Semantically renamed to "tag" in Workspace v1 (Phase A.2); the
     *  Drive wire kind keeps the legacy string to round-trip with
     *  existing payloads on Drive until the prefix migration completes. */
    const val KIND_CATEGORY       = "category"
    /** QuickInk-only — per-user profile-settings row (display name, photo, etc.). */
    const val KIND_PROFILE_SETTINGS = "profile_settings"

    // ─── Workspace v1 (Phase A.3b) ───────────────────────────────────
    /** QuickInk-only — Workspace folder ("intent" axis; one per capture). */
    const val KIND_FOLDER           = "folder"
    /** QuickInk-only — capture↔tag many-to-many join row. */
    const val KIND_CAPTURE_TAG      = "capture_tag"
    /** QuickInk-only — rule-based saved view. */
    const val KIND_SMART_COLLECTION = "smart_collection"

    /** QuickInk-only — user-defined place ("Home", "Work", etc.).
     *  Optional axis attached to captures via the [KIND_CAPTURE_LOCATION]
     *  join. Seeded with "Home" and "Work" on first launch. */
    const val KIND_LOCATION         = "location"
    /** QuickInk-only — capture↔location many-to-many join row. */
    const val KIND_CAPTURE_LOCATION = "capture_location"

    /** QuickInk-only — voice note attached to a capture. Audio
     *  binary lives at the binary side via `QuickInkBinarySync`;
     *  this JSON kind carries the metadata (duration, transcript,
     *  audio_drive_file_id). */
    const val KIND_VOICE_NOTE       = "voice_note"

    // ---- folder names (no trailing slash; join with `/`) ----
    const val FOLDER_NOTEBOOKS       = "notebooks"
    const val FOLDER_CHAPTERS        = "chapters"
    const val FOLDER_PAGES           = "pages"
    const val FOLDER_NOTEPAD_ENTRIES = "notepad_entries"
    const val FOLDER_DAILY_LOGS      = "daily_logs"
    const val FOLDER_CAPTURES        = "captures"
    /** QuickInk's OCR-result tree — `ocr/{captureId}/page-{N}.json`. */
    const val FOLDER_OCR             = "ocr"
    /** QuickInk's category list — `categories/{id}.json`. Legacy prefix
     *  for QuickInk's "tags" (renamed in Workspace v1 Phase A.2). New
     *  writes go under [FOLDER_TAGS]; readers fall back to this prefix
     *  during the rollout soak. */
    const val FOLDER_CATEGORIES      = "categories"
    /** QuickInk's tag list — `tags/{id}.json`. Workspace v1 destination
     *  for what used to live under `categories/`. The data source writes
     *  here exclusively after Phase A.3b; the legacy `categories/`
     *  prefix is read-back-compat for two weeks then cleaned up. */
    const val FOLDER_TAGS            = "tags"
    /** QuickInk's profile-settings folder — `profile_settings/{userId}.json`. */
    const val FOLDER_PROFILE_SETTINGS = "profile_settings"

    // ─── Workspace v1 (Phase A.3b) ───────────────────────────────────
    /** QuickInk folders — `folders/{id}.json`. */
    const val FOLDER_FOLDERS           = "folders"
    /** QuickInk capture↔tag joins — `capture_tags/{id}.json`. */
    const val FOLDER_CAPTURE_TAGS      = "capture_tags"
    /** QuickInk smart collections — `smart_collections/{id}.json`. */
    const val FOLDER_SMART_COLLECTIONS = "smart_collections"
    /** QuickInk locations — `locations/{id}.json`. */
    const val FOLDER_LOCATIONS         = "locations"
    /** QuickInk capture↔location joins — `capture_locations/{id}.json`. */
    const val FOLDER_CAPTURE_LOCATIONS = "capture_locations"
    /** QuickInk voice notes — `{yyyy}/{mm}/{dd}/{captureId}/voice-{id}.json`,
     *  co-located with the parent capture's day folder like ocr_results. */
    const val FOLDER_VOICE_NOTES       = "voice_notes"
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

    /** QuickInk's per-category file — `categories/{id}.json`. Legacy
     *  read path. New writes go through [tag] under `tags/{id}.json`. */
    fun category(id: String): String = "$FOLDER_CATEGORIES/$id.json"

    /** QuickInk's per-tag file — `tags/{id}.json` (Workspace v1).
     *  Renamed from [category] in Phase A.3b. */
    fun tag(id: String): String = "$FOLDER_TAGS/$id.json"

    /** QuickInk folder payload file — `folders/{id}.json`. */
    fun folder(id: String): String = "$FOLDER_FOLDERS/$id.json"

    /** QuickInk capture↔tag join payload — `capture_tags/{id}.json`. */
    fun captureTag(id: String): String = "$FOLDER_CAPTURE_TAGS/$id.json"

    /** QuickInk smart-collection payload — `smart_collections/{id}.json`. */
    fun smartCollection(id: String): String = "$FOLDER_SMART_COLLECTIONS/$id.json"

    /** QuickInk's per-location file — `locations/{id}.json`. */
    fun location(id: String): String = "$FOLDER_LOCATIONS/$id.json"

    /** QuickInk capture↔location join payload — `capture_locations/{id}.json`. */
    fun captureLocation(id: String): String = "$FOLDER_CAPTURE_LOCATIONS/$id.json"

    /**
     * Date-bucketed QuickInk voice-note path —
     * `{yyyy}/{mm}/{dd}/{captureId}/voice-{id}.json`. Co-locates voice
     * notes under their parent capture's day folder, same pattern as
     * [quickInkOcrResult].
     */
    fun quickInkVoiceNote(createdAt: String, captureId: String, id: String): String =
        "${quickInkDateBucket(createdAt)}/$captureId/voice-$id.json"

    /**
     * QuickInk's per-user profile-settings file —
     * `profile_settings/{userId}.json`. Single row per user; the
     * filename is the row's id (which equals the user id).
     */
    fun profileSettings(id: String): String = "$FOLDER_PROFILE_SETTINGS/$id.json"

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
