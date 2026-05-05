/*
 * CaptureEntity.kt
 *
 * Room @Entity for the `captures` table. One row per scan session
 * (a multi-page document the user captured via
 * DocumentScannerLauncher). Schema mirrors
 * `shared/design-system/migrations/quickink/v1_initial.sql`.
 *
 * Lives in the QuickInk app target (not :shared:scan) because
 * captures is a QuickInk-specific table — Releaf doesn't have an
 * equivalent today.
 */

package app.quickink.mobile.data.capture

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "captures",
    indices = [
        Index(value = ["user_id", "created_at"], name = "idx_captures_user_created"),
        Index(value = ["dirty"], name = "idx_captures_dirty"),
        Index(value = ["deleted_at"], name = "idx_captures_tombstone"),
    ],
)
data class CaptureEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "user_id")
    val userId: String,

    @ColumnInfo(name = "title")
    val title: String?,

    /** file:// or content:// pointing at the scanner-produced PDF. */
    @ColumnInfo(name = "pdf_uri")
    val pdfUri: String,

    /** file:// pointing at a first-page preview JPEG (thumbnail). */
    @ColumnInfo(name = "preview_uri")
    val previewUri: String?,

    @ColumnInfo(name = "page_count", defaultValue = "0")
    val pageCount: Int,

    /**
     * Pre-tagged category name picked on the scan review screen.
     * Stored as a TEXT value (not an FK to `categories.id`) so a
     * later soft-delete of the category row keeps the historical
     * tag readable. See v2_capture_categories.sql for the rationale.
     */
    @ColumnInfo(name = "category")
    val category: String?,

    /**
     * How the capture was created. `"scan"` (default) — went through
     * the ML Kit / VisionKit document scanner. `"import"` — was
     * brought in from the system photo picker (single image, no
     * edge detection, single-page PDF synthesised app-side). Drives
     * the "Import" pill in the Library cards. Stored as a free-form
     * TEXT so a later third source ("share-extension", etc.) can
     * land without another migration. Defaulted at the column level
     * so legacy rows synced down from Drive (without this field)
     * read back as scans.
     */
    @ColumnInfo(name = "source", defaultValue = "scan")
    val source: String = "scan",

    @ColumnInfo(name = "conflict_stub")
    val conflictStub: String?,

    @ColumnInfo(name = "drive_file_id")
    val driveFileId: String?,

    /**
     * Drive file id of the per-row PDF binary upload. NULL = the
     * PDF hasn't been pushed to Drive yet (or the local file went
     * missing before upload). Populated by `SyncRepository` via
     * `markPdfSynced` after `DriveClient.uploadBinary` returns.
     * Defaulted so legacy construction sites keep compiling — only
     * the upload pipeline writes a non-null value.
     */
    @ColumnInfo(name = "pdf_drive_file_id")
    val pdfDriveFileId: String? = null,

    /**
     * Drive file id of the per-row preview-JPEG upload. Same
     * lifecycle as [pdfDriveFileId]; null when the binary hasn't
     * been mirrored to Drive.
     */
    @ColumnInfo(name = "preview_drive_file_id")
    val previewDriveFileId: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: String,

    @ColumnInfo(name = "updated_at")
    val updatedAt: String,

    @ColumnInfo(name = "dirty", defaultValue = "1")
    val dirty: Boolean,

    @ColumnInfo(name = "deleted_at")
    val deletedAt: String?,
)

/**
 * Display title for [CaptureEntity] across the app's lists and
 * thumbnails. Priority: user-set [title] (trimmed, non-empty) →
 * [category] → [fallback]. Centralised so the Library, Home recents,
 * Search hits, and Category drill-down all surface the same string.
 */
fun CaptureEntity.displayTitle(fallback: String = "Scan"): String {
    val titled = title?.trim().orEmpty()
    if (titled.isNotEmpty()) return titled
    val cat = category?.trim().orEmpty()
    if (cat.isNotEmpty()) return cat
    return fallback
}
