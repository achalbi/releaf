/*
 * ChapterEntity.kt
 *
 * Room entity mirroring the `chapters` table in
 * `design-system/migrations/v1_initial.sql`. Chapters are a middle tier in
 * the notebook → chapter → page hierarchy: each chapter belongs to exactly
 * one notebook and groups a linear sequence of pages.
 *
 * FK to notebooks is declared in the .sql file; Room's @ForeignKey is
 * intentionally omitted for parity with NotepadEntry (see that file for the
 * schema-source-of-truth follow-up). Cascade semantics are handled at the
 * repository layer today.
 */

package app.releaf.mobile.data.notebook

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "chapters",
    indices = [
        // Composite supports the hot query "chapters of a notebook, in order".
        Index(value = ["notebook_id", "position"]),
        Index("updated_at"),
        Index("deleted_at"),
        Index("archived_at"),
    ],
)
data class ChapterEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "notebook_id")
    val notebookId: String,

    @ColumnInfo(name = "title")
    val title: String,

    /** Free-form summary shown on the chapter row + chapter-detail card. */
    @ColumnInfo(name = "description")
    val description: String? = null,

    @ColumnInfo(name = "position", defaultValue = "1024")
    val position: Long = 1024L,

    @ColumnInfo(name = "drive_file_id")
    val driveFileId: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: String,

    @ColumnInfo(name = "updated_at")
    val updatedAt: String,

    @ColumnInfo(name = "dirty", defaultValue = "1")
    val dirty: Boolean = true,

    @ColumnInfo(name = "deleted_at")
    val deletedAt: String? = null,

    /**
     * Soft-archive timestamp (separate from `deletedAt`). Added in
     * v17 — non-nil means the chapter lives in the archive bin and
     * is filtered out of the active chapter list. Independent of
     * `deletedAt` (true tombstone).
     */
    @ColumnInfo(name = "archived_at")
    val archivedAt: String? = null,
)
