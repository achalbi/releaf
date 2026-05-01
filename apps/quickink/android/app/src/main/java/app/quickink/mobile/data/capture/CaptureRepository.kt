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

    private companion object {
        // `ignoreUnknownKeys = true` future-proofs us against
        // OcrBlock gaining new fields in `:shared:scan` — older
        // rows decoded later just drop the unknown.
        private val json = Json { ignoreUnknownKeys = true }
    }
}
