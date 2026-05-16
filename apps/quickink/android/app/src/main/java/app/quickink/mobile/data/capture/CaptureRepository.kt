/*
 * CaptureRepository.kt
 *
 * Wraps Room DAOs for `captures` + `ocr_results`. Higher-level
 * operations the scan flow needs:
 *   - insert a fresh capture after the scanner returns
 *   - persist a list of OcrResults keyed to a capture
 *   - observe captures + their OCR rows for a user
 *
 * Mirror of `CaptureRepository.swift` in QuickInk's iOS target.
 *
 * `OcrResult` (from `:shared:scan`) is encoded into the
 * `blocks_json` TEXT column via `kotlinx.serialization` over the
 * `@Serializable` annotations on the value types — no separate
 * persistence model.
 */

package app.quickink.mobile.data.capture

import androidx.room.RoomRawQuery
import app.quickink.mobile.data.capturetag.CaptureTagDao
import app.quickink.mobile.data.ocr.OcrResultDao
import app.quickink.mobile.data.ocr.OcrResultEntity
import app.quickink.mobile.data.tag.TagDao
import app.quickink.mobile.data.tag.TagRepository
import app.quickink.mobile.features.scan.PaperSize
import app.releaf.mobile.data.common.IsoClock
import app.releaf.mobile.data.common.Uuidv7
import app.releaf.shared.scan.OcrResult
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class CaptureRepository(
    private val captureDao: CaptureDao,
    private val ocrResultDao: OcrResultDao,
    /**
     * Workspace v1: post-A.3c the legacy `captures.category` column
     * is gone. The scan flow + business-card post-processor surface
     * a user-picked label by attaching a `capture_tags` row through
     * [attachOrEnsurePrimaryTag] — these two DAOs back that. Both
     * default to null so legacy construction sites (tests, previews)
     * keep compiling; the attach-by-name helper is a no-op until the
     * caller wires them in.
     */
    private val tagDao: TagDao? = null,
    private val captureTagDao: CaptureTagDao? = null,
) {

    /**
     * Insert a fresh capture row. `id` is supplied by the caller
     * (UUIDv7 from `:shared:data`'s `Uuidv7`) so the scan flow
     * can reference the capture before persistence completes — i.e.
     * for OCR rows whose `captureId` foreign-keys must already be
     * valid.
     */
    suspend fun insertCapture(
        id: String,
        userId: String,
        title: String?,
        pdfUri: String,
        previewUri: String?,
        pageCount: Int,
        /**
         * Capture origin. `"scan"` (default) — went through the ML
         * Kit scanner. `"import"` — picked from the system photo
         * picker. Drives the "Import" pill in the Library cards.
         */
        source: String = "scan",
        /**
         * Page-size class. Drives the sustainability hero's per-page
         * weight — [PaperSize.Card] scores +4 pts/page, [PaperSize.A4]
         * +2, [PaperSize.Small] +1. Defaulted to [PaperSize.A4] so
         * legacy callers (and rows synced from older clients without
         * the field on the wire) read back as standard pages.
         */
        paperSize: PaperSize = PaperSize.A4,
        /**
         * Optional geolocation captured at scan / import time. Pass
         * `null` when the user has the "Attach location to scans"
         * setting off, when location permission is denied, or when
         * the fetch / reverse-geocode failed. Either all four fields
         * (lat / lon / locality / sub-locality) are written or the
         * caller passes a null struct; we never persist half of a
         * coordinate pair.
         */
        location: CapturedLocation? = null,
    ) {
        val now = IsoClock.nowIso()
        captureDao.insert(
            CaptureEntity(
                id           = id,
                userId       = userId,
                title        = title,
                pdfUri       = pdfUri,
                previewUri   = previewUri,
                pageCount    = pageCount,
                source       = source,
                paperSize    = paperSize.raw,
                latitude     = location?.latitude,
                longitude    = location?.longitude,
                locality     = location?.locality,
                subLocality  = location?.subLocality,
                address      = location?.address,
                conflictStub = null,
                driveFileId  = null,
                createdAt    = now,
                updatedAt    = now,
                dirty        = true,
                deletedAt    = null,
            ),
        )
    }

    /**
     * Attach a tag by name as the capture's primary label, the
     * post-A.3c replacement for the legacy `setCategory`. Find-or-
     * creates the tag in the user's namespace (so a fresh
     * scan-review pick lazily materializes the row) then attaches
     * via the join — idempotent if the same tag is already
     * attached. Pass `null` / blank to clear ALL active tag
     * attachments on the capture (keeps "set to no category" parity
     * with the legacy single-field behavior).
     *
     * No-op when [tagDao] or [captureTagDao] weren't wired (test /
     * preview construction). Best-effort: the caller's
     * try/catch covers SQL failure modes the same way the legacy
     * `setCategory` did.
     */
    suspend fun attachOrEnsurePrimaryTag(
        captureId: String,
        userId: String,
        name: String?,
        source: String = "manual",
    ) {
        val ctDao = captureTagDao ?: return
        val tDao  = tagDao ?: return
        val now   = IsoClock.nowIso()
        val trimmed = name?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            ctDao.softDeleteByCaptureId(captureId, now)
            return
        }
        val tag = TagRepository(tDao).findOrCreate(userId, trimmed)
        ctDao.attachTag(
            joinId    = Uuidv7.generate(),
            captureId = captureId,
            tagId     = tag.id,
            source    = source,
            timestamp = now,
        )
    }

    /**
     * Update an existing capture's user-editable `title`. Pass
     * `null` to clear the title (the Library card then falls back
     * to OCR snippet → primary tag → "Untitled scan"). Used both
     * by the scan-flow auto-populator and the scan-detail editor
     * modal.
     */
    suspend fun setTitle(captureId: String, title: String?) {
        captureDao.setTitle(captureId, title, IsoClock.nowIso())
    }

    /**
     * Append `text` to `captures.notes` as a new paragraph (blank
     * line separator). Used by the voice-note transcript editor —
     * after recording + transcribing, the edited text lands both on
     * the voice note's `transcription` field AND is appended here so
     * the document carries the running notes across clips. Empty /
     * whitespace input is a no-op; the existing value is preserved.
     */
    suspend fun appendNote(captureId: String, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val existing = captureDao.getNotes(captureId)?.trim().orEmpty()
        val next = if (existing.isEmpty()) trimmed else "$existing\n\n$trimmed"
        captureDao.setNotes(captureId, next, IsoClock.nowIso())
    }

    /**
     * Overwrite `captures.notes` outright (distinct from
     * [appendNote] which appends as a new paragraph). Used by the
     * scan-detail Notes editor when the user edits the whole notes
     * blob directly. Empty / whitespace input clears the column to
     * null so the card's empty-state branch reads correctly.
     */
    suspend fun setNotes(captureId: String, notes: String?) {
        val trimmed = notes?.trim()
        val next = if (trimmed.isNullOrEmpty()) null else trimmed
        captureDao.setNotes(captureId, next, IsoClock.nowIso())
    }

    /**
     * Move a single capture into a folder. Bumps `updated_at` +
     * `dirty` so the change rides the next sync push. Used by the
     * scan-review folder picker (`ScanFlowController.setFolder`)
     * and the scan-detail "Move to folder" affordance.
     */
    suspend fun setFolder(captureId: String, folderId: String) {
        captureDao.setFolder(captureId, folderId, IsoClock.nowIso())
    }

    /**
     * Update the `paper_size` bucket on a capture. Called from
     * `ScanReviewScreen`'s paper-size chip when the user
     * disambiguates the auto-detection (typically A4 vs A5).
     * Same dirty + updated_at pattern as [setTitle] / [setFolder].
     */
    suspend fun setPaperSize(captureId: String, paperSize: PaperSize) {
        captureDao.setPaperSize(captureId, paperSize.raw, IsoClock.nowIso())
    }

    /**
     * Stamp a fresh [CapturedLocation] onto an existing capture row
     * — both the locality / sub-locality / address fields and the
     * lat / lon pair, in two DAO writes. Used by
     * `ScanFlowController.onScanComplete` to fill in the geo
     * columns AFTER the row has been inserted; we insert eagerly
     * (so the voice-note pane can mount on a real row) and then
     * patch in the location once the GPS + reverse-geocode lands.
     *
     * No-op when the resolved location is null (toggle off,
     * permission denied, or fetch failed) — caller doesn't need to
     * pre-check.
     */
    suspend fun setLocation(captureId: String, location: CapturedLocation?) {
        if (location == null) return
        val now = IsoClock.nowIso()
        captureDao.setLocation(
            id          = captureId,
            locality    = location.locality,
            subLocality = location.subLocality,
            address     = location.address,
            timestamp   = now,
        )
        captureDao.setCoordinates(
            id        = captureId,
            latitude  = location.latitude,
            longitude = location.longitude,
            timestamp = now,
        )
    }

    /**
     * Persist a successful page recognition into `ocr_results`.
     * `OcrResult.blocks` is encoded to JSON via the
     * `@Serializable` annotations on the value types.
     */
    suspend fun insertOcrResult(
        captureId: String,
        pageIndex: Int,
        result: OcrResult,
    ) {
        val now = IsoClock.nowIso()
        val blocksJson = json.encodeToString(result.blocks)
        ocrResultDao.insert(
            OcrResultEntity(
                id            = Uuidv7.generate(),
                captureId     = captureId,
                pageIndex     = pageIndex,
                language      = result.language,
                confidence    = result.confidence,
                text          = result.text,
                blocksJson    = blocksJson,
                engine        = result.engine,
                engineVersion = result.engineVersion,
                createdAt     = now,
                updatedAt     = now,
                dirty         = true,
                deletedAt     = null,
            ),
        )
    }

    /**
     * Search captures across tag name + OCR text. Mirror of iOS's
     * `CaptureRepository.search`. Returns each capture once with
     * the strongest OCR snippet (when the hit came via FTS5).
     *
     * The two passes:
     *   1. Captures with a tag whose `name` contains [query]
     *      (substring, case-insensitive). Replaces the legacy
     *      `category`-substring pass after A.3c dropped the
     *      column. No OCR snippet for these.
     *   2. OCR-text matches via `fts_ocr_text` MATCH joined back
     *      to captures.
     *
     * Dedupes by `capture.id` with tag hits ordered first.
     *
     * [requiredTagIds] post-filters the union so only captures
     * tagged with EVERY id survive. Used by the search-bar
     * `#tag` autocomplete: a user typing `#invoice paid` keeps
     * Invoice as a hard filter and runs "paid" through the OCR
     * pass.
     */
    suspend fun search(
        userId: String,
        query: String,
        requiredTagIds: List<String> = emptyList(),
    ): List<SearchHit> {
        val trimmed = query.trim()
        if (trimmed.isEmpty() && requiredTagIds.isEmpty()) return emptyList()

        // Tag-only search (no residual text) — every capture that
        // carries the full set of required tags, newest-first.
        if (trimmed.isEmpty() && requiredTagIds.isNotEmpty()) {
            return capturesWithAllTags(userId, requiredTagIds)
                .map { SearchHit(capture = it, ocrSnippet = null) }
        }
        val likePattern = "%$trimmed%"
        val ftsQuery = buildFtsQuery(trimmed)

        val seen = mutableSetOf<String>()
        val hits = mutableListOf<SearchHit>()

        // Pass 1 — tag-name substring. Runs only when both
        // workspace DAOs were wired (legacy test/preview construction
        // sites pass null). Replaces the pre-A.3c `searchByCategory`
        // pass against `captures.category`.
        val ctDao = captureTagDao
        if (ctDao != null) {
            try {
                for (row in captureDao.searchByTagName(userId, likePattern)) {
                    if (seen.add(row.id)) {
                        hits += SearchHit(capture = row, ocrSnippet = null)
                    }
                }
            } catch (_: Exception) {
                // Tag-search pass failed — fall through to OCR.
            }
        }

        // FTS5 raw query — see CaptureDao.searchByOcr for why we
        // can't use a regular @Query here.
        val rawSql = """
            SELECT c.id            AS id,
                   c.user_id       AS user_id,
                   c.preview_uri   AS preview_uri,
                   c.pdf_uri       AS pdf_uri,
                   c.page_count    AS page_count,
                   c.created_at    AS created_at,
                   snippet(fts_ocr_text, 1, '‹', '›', '…', 24) AS ocr_snippet
            FROM   fts_ocr_text f
            JOIN   ocr_results r ON r.id = f.ocr_result_id
            JOIN   captures    c ON c.id = r.capture_id
            WHERE  f.text MATCH ?
              AND  r.deleted_at IS NULL
              AND  c.deleted_at IS NULL
              AND  c.user_id = ?
            ORDER BY rank
        """.trimIndent()
        val rawQuery = RoomRawQuery(sql = rawSql) { stmt ->
            stmt.bindText(1, ftsQuery)
            stmt.bindText(2, userId)
        }

        // FTS pass can throw on a malformed MATCH expression; if it
        // does, fall through to the LIKE fallback below so the user
        // still sees results. We swallow the throw rather than
        // propagating because the UI's catch zeros the hits and the
        // user has no way to recover.
        try {
            for (ocr in captureDao.searchByOcr(rawQuery)) {
                if (!seen.add(ocr.id)) continue
                val snippet = ocr.ocrSnippet
                    ?.replace("‹", "")
                    ?.replace("›", "")
                val entity = CaptureEntity(
                    id           = ocr.id,
                    userId       = ocr.userId,
                    title        = null,
                    pdfUri       = ocr.pdfUri,
                    previewUri   = ocr.previewUri,
                    pageCount    = ocr.pageCount,
                    conflictStub = null,
                    driveFileId  = null,
                    createdAt    = ocr.createdAt,
                    updatedAt    = ocr.createdAt,
                    dirty        = false,
                    deletedAt    = null,
                )
                hits += SearchHit(capture = entity, ocrSnippet = snippet)
            }
        } catch (_: Exception) {
            // FTS5 syntax error or schema not yet created on this
            // device — fall through to LIKE-based fallback.
        }

        // Pass 3 — substring fallback over `ocr_results.text`. Only
        // runs when the FTS pass produced no OCR hits (malformed
        // MATCH expr, FTS index empty because triggers haven't fired,
        // etc.). O(rows × text length).
        if (hits.none { it.ocrSnippet != null }) {
            val likeSql = """
                SELECT c.id            AS id,
                       c.user_id       AS user_id,
                       c.preview_uri   AS preview_uri,
                       c.pdf_uri       AS pdf_uri,
                       c.page_count    AS page_count,
                       c.created_at    AS created_at,
                       substr(r.text, max(1, instr(lower(r.text), lower(?)) - 30), 120) AS ocr_snippet
                FROM   ocr_results r
                JOIN   captures    c ON c.id = r.capture_id
                WHERE  r.deleted_at IS NULL
                  AND  c.deleted_at IS NULL
                  AND  c.user_id = ?
                  AND  lower(r.text) LIKE lower(?)
                ORDER BY c.created_at DESC
            """.trimIndent()
            val likeQuery = RoomRawQuery(sql = likeSql) { stmt ->
                stmt.bindText(1, trimmed)
                stmt.bindText(2, userId)
                stmt.bindText(3, likePattern)
            }
            try {
                for (ocr in captureDao.searchByOcr(likeQuery)) {
                    if (!seen.add(ocr.id)) continue
                    val entity = CaptureEntity(
                        id           = ocr.id,
                        userId       = ocr.userId,
                        title        = null,
                        pdfUri       = ocr.pdfUri,
                        previewUri   = ocr.previewUri,
                        pageCount    = ocr.pageCount,
                        conflictStub = null,
                        driveFileId  = null,
                        createdAt    = ocr.createdAt,
                        updatedAt    = ocr.createdAt,
                        dirty        = false,
                        deletedAt    = null,
                    )
                    hits += SearchHit(capture = entity, ocrSnippet = ocr.ocrSnippet)
                }
            } catch (_: Exception) {
                // Last-resort path failed too — return whatever we
                // have (probably tag hits only).
            }
        }

        // Tag-id post-filter — keep only captures that carry every
        // required tag. Runs in Kotlin against the join row maps
        // because `hits` is already a small list.
        if (requiredTagIds.isNotEmpty()) {
            val survivors = captureIdsWithAllTags(userId, requiredTagIds)
            return hits.filter { it.capture.id in survivors }
        }

        return hits
    }

    /**
     * Captures (full rows, newest-first) that carry every tag id
     * in [requiredTagIds]. Used by the `#tag`-only search path
     * (no residual text). Returns an empty list when the input is
     * empty or no capture matches.
     */
    private suspend fun capturesWithAllTags(
        userId: String,
        requiredTagIds: List<String>,
    ): List<CaptureEntity> {
        val ids = captureIdsWithAllTags(userId, requiredTagIds)
        if (ids.isEmpty()) return emptyList()
        // Re-fetch full rows by id; we don't have a "fetch by ids"
        // DAO query, but the active-list filter is tight enough.
        val active = captureDao.activeRows(userId)
        val byId = active.associateBy { it.id }
        return ids.mapNotNull { byId[it] }.sortedByDescending { it.createdAt }
    }

    private suspend fun captureIdsWithAllTags(
        userId: String,
        requiredTagIds: List<String>,
    ): Set<String> {
        if (requiredTagIds.isEmpty()) return emptySet()
        val ctDao = captureTagDao ?: return emptySet()
        val intersect = mutableSetOf<String>()
        var first = true
        for (tagId in requiredTagIds) {
            val ids = ctDao.captureIdsForTag(tagId).toSet()
            if (first) {
                intersect.addAll(ids)
                first = false
            } else {
                intersect.retainAll(ids)
            }
            if (intersect.isEmpty()) return emptySet()
        }
        return intersect
    }

    /**
     * Build a tokenised FTS5 MATCH expression from a free-form user
     * query. Each whitespace-delimited word becomes a prefix term
     * (`word*`) so partial-word matches work mid-typing.
     *
     * Strips EVERY non-alphanumeric / non-whitespace char (replaces
     * with a space) before tokenising. The earlier "strip the five
     * known FTS5 operators" approach left other reserved chars
     * behind (`-`, `+`, `^`, smart quotes, OR / NEAR keywords),
     * which produced malformed MATCH expressions for queries like
     * "it's" or "math-physics" — the SQL throws, caller catches and
     * zeroes the hits, and the user thinks search is broken.
     * Aggressive sanitisation trades a tiny bit of expressivity for
     * reliability; users typing punctuation almost always want the
     * alphanumeric tokens around it.
     */
    private fun buildFtsQuery(raw: String): String {
        val cleaned = raw.lowercase().map { c ->
            if (c.isLetterOrDigit() || c.isWhitespace()) c else ' '
        }.joinToString("")
        val tokens = cleaned.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return "\"\""
        return tokens.joinToString(" ") { "$it*" }
    }

    private companion object {
        // `ignoreUnknownKeys = true` future-proofs us against
        // OcrBlock gaining new fields in `:shared:scan` — older
        // rows decoded later just drop the unknown.
        private val json = Json { ignoreUnknownKeys = true }
    }
}

/**
 * Result row for a Library / Search capture-based query. The
 * `ocrSnippet` is `null` for non-OCR hits (e.g. category-only
 * matches) and contains the FTS5-extracted excerpt otherwise.
 */
data class SearchHit(
    val capture: CaptureEntity,
    val ocrSnippet: String?,
)
