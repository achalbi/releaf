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
import app.quickink.mobile.data.ocr.OcrResultDao
import app.quickink.mobile.data.ocr.OcrResultEntity
import app.releaf.mobile.data.common.IsoClock
import app.releaf.mobile.data.common.Uuidv7
import app.releaf.shared.scan.OcrResult
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class CaptureRepository(
    private val captureDao: CaptureDao,
    private val ocrResultDao: OcrResultDao,
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
        category: String? = null,
        /**
         * Capture origin. `"scan"` (default) — went through the ML
         * Kit scanner. `"import"` — picked from the system photo
         * picker. Drives the "Import" pill in the Library cards.
         */
        source: String = "scan",
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
                category     = category,
                source       = source,
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
     * Update an existing capture's `category` to the user's pick
     * from the scan-review screen. Bumps `updated_at` + `dirty`
     * so the next sync pass uploads the change.
     */
    suspend fun setCategory(captureId: String, category: String?) {
        captureDao.setCategory(captureId, category, IsoClock.nowIso())
    }

    /**
     * Update an existing capture's user-editable `title`. Same dirty-
     * bit pattern as [setCategory]. Pass `null` to clear the title
     * (the Library card then falls back to OCR snippet → category →
     * "Untitled scan"). Used both by the scan-flow auto-populator
     * and the scan-detail editor modal.
     */
    suspend fun setTitle(captureId: String, title: String?) {
        captureDao.setTitle(captureId, title, IsoClock.nowIso())
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
     * Search captures across category name + OCR text. Mirror of
     * iOS's `CaptureRepository.search`. Returns each capture once
     * with the strongest OCR snippet (when the hit came via FTS5).
     * The two passes:
     *   1. Captures whose `category` contains [query] (substring,
     *      case-insensitive). No OCR snippet for these.
     *   2. OCR-text matches via `fts_ocr_text` MATCH joined back to
     *      captures.
     * Dedupes by `capture.id` with category hits ordered first.
     */
    suspend fun search(userId: String, query: String): List<SearchHit> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val likePattern = "%$trimmed%"
        val ftsQuery = buildFtsQuery(trimmed)

        val seen = mutableSetOf<String>()
        val hits = mutableListOf<SearchHit>()

        for (row in captureDao.searchByCategory(userId, likePattern)) {
            if (seen.add(row.id)) {
                hits += SearchHit(capture = row, ocrSnippet = null)
            }
        }

        // FTS5 raw query — see CaptureDao.searchByOcr for why we
        // can't use a regular @Query here.
        val rawSql = """
            SELECT c.id            AS id,
                   c.user_id       AS user_id,
                   c.preview_uri   AS preview_uri,
                   c.pdf_uri       AS pdf_uri,
                   c.category      AS category,
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
                    category     = ocr.category,
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
                       c.category      AS category,
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
                        category     = ocr.category,
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
                // have (probably category hits only).
            }
        }

        return hits
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
