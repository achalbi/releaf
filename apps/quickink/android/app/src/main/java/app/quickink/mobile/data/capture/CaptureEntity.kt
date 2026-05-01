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

    @ColumnInfo(name = "conflict_stub")
    val conflictStub: String?,

    @ColumnInfo(name = "drive_file_id")
    val driveFileId: String?,

    @ColumnInfo(name = "created_at")
    val createdAt: String,

    @ColumnInfo(name = "updated_at")
    val updatedAt: String,

    @ColumnInfo(name = "dirty", defaultValue = "1")
    val dirty: Boolean,

    @ColumnInfo(name = "deleted_at")
    val deletedAt: String?,
)
