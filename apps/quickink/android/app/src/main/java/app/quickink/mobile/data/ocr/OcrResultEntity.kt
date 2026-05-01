/*
 * OcrResultEntity.kt
 *
 * Room @Entity for the `ocr_results` table. One row per scanned
 * page; per QUICKINK_PROPOSAL.md §6.2. Schema mirrors
 * `shared/design-system/migrations/quickink/v1_initial.sql`.
 *
 * `blocksJson` is the encoded `OcrResult.blocks` list from
 * :shared:scan, stored as a TEXT JSON blob. We keep the encoding
 * concern outside Room (no @TypeConverter) so the storage layer
 * can choose its JSON serializer (kotlinx.serialization vs Moshi
 * vs hand-rolled) without coupling the entity.
 *
 * Lives in the QuickInk app target (not :shared:scan) because
 * ocr_results is a QuickInk-specific table — Releaf doesn't
 * persist OCR output today.
 */

package app.quickink.mobile.data.ocr

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.quickink.mobile.data.capture.CaptureEntity

@Entity(
    tableName = "ocr_results",
    foreignKeys = [
        ForeignKey(
            entity        = CaptureEntity::class,
            parentColumns = ["id"],
            childColumns  = ["capture_id"],
            onDelete      = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["capture_id", "page_index"], unique = true,
              name  = "idx_ocr_results_capture_page"),
        Index(value = ["capture_id"], name = "idx_ocr_results_capture"),
        Index(value = ["dirty"],      name = "idx_ocr_results_dirty"),
        Index(value = ["deleted_at"], name = "idx_ocr_results_tombstone"),
    ],
)
data class OcrResultEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "capture_id")
    val captureId: String,

    /** 0-based page index within the parent capture. */
    @ColumnInfo(name = "page_index")
    val pageIndex: Int,

    /**
     * BCP-47 dominant language across page blocks. Null when no
     * engine in the call path classified language (ML Kit's
     * Play-Services variant currently doesn't surface confidence
     * but does surface language; Apple Vision is the inverse).
     */
    @ColumnInfo(name = "language")
    val language: String?,

    /**
     * 0.0–1.0 mean confidence across blocks. Null when the engine
     * doesn't expose per-block confidence (ML Kit Play-Services)
     * or when the page had no detected text.
     */
    @ColumnInfo(name = "confidence")
    val confidence: Double?,

    /** Flat recognized text. Mirrored into the `fts_ocr_text` FTS5 index. */
    @ColumnInfo(name = "text")
    val text: String,

    /**
     * JSON-encoded `OcrResult.blocks` (line + paragraph tier
     * `OcrBlock` records, with normalized 0..1 top-left bboxes).
     * Storage-format choice is downstream of this entity.
     */
    @ColumnInfo(name = "blocks_json")
    val blocksJson: String,

    /** Stable engine identifier — `"apple-vision"` or `"mlkit-latin-v2"`. */
    @ColumnInfo(name = "engine")
    val engine: String,

    @ColumnInfo(name = "engine_version")
    val engineVersion: String?,

    /**
     * Drive `fileId` stamped by the sync worker after a successful
     * upload. Null on rows that haven't been pushed yet. Used by the
     * sync orchestrator to re-target updates rather than rediscover
     * by path on every pass.
     */
    @ColumnInfo(name = "drive_file_id")
    val driveFileId: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: String,

    @ColumnInfo(name = "updated_at")
    val updatedAt: String,

    @ColumnInfo(name = "dirty", defaultValue = "1")
    val dirty: Boolean,

    @ColumnInfo(name = "deleted_at")
    val deletedAt: String?,
)
