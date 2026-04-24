/*
 * NotebookEntity.kt
 *
 * Room entity mirroring the `notebooks` table in
 * `design-system/migrations/v1_initial.sql`. Notebook is the top of the
 * notebook → chapter → page hierarchy.
 *
 * Schema-source-of-truth gap: same situation as NotepadEntry — column shapes
 * match v1_initial.sql, but Room generates its own DDL and does not replicate
 * the CHECK constraints from the .sql file. Tracked as a follow-up alongside
 * that entity.
 */

package app.releaf.mobile.data.notebook

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "notebooks",
    indices = [
        Index("updated_at"),
        Index("deleted_at"),
        Index("archived_at"),
        Index("shelf_id"),
        Index("series_id"),
    ],
)
data class NotebookEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "title")
    val title: String,

    /** Free-form summary shown on list + detail cards. Nullable / empty. */
    @ColumnInfo(name = "description")
    val description: String? = null,

    /** Hex color (e.g. `#E77850`) or null for theme default. */
    @ColumnInfo(name = "color_hex")
    val colorHex: String? = null,

    /** Manual ordering hint. 1024-step spacing leaves room for re-ordering. */
    @ColumnInfo(name = "position", defaultValue = "1024")
    val position: Long = 1024L,

    /**
     * Shelf this book belongs to. Required — every book lives on
     * some shelf. Existing rows are backfilled to `shelf-general`
     * by Migration11To12.
     */
    @ColumnInfo(name = "shelf_id", defaultValue = "'shelf-general'")
    val shelfId: String = "shelf-general",

    /**
     * Series this book is a volume of. NULL = standalone single-
     * volume book (UI renders the title without a "Vol N" badge).
     * When non-null, points at a `book_series` row; sibling volumes
     * share this id.
     */
    @ColumnInfo(name = "series_id")
    val seriesId: String? = null,

    /** 1 for the first (or only) volume; 2+ for subsequent volumes. */
    @ColumnInfo(name = "volume_number", defaultValue = "1")
    val volumeNumber: Int = 1,

    /**
     * Optional per-volume label (e.g. "2026"). When null the UI
     * composes `"<book> vol <n>"` from the parent series name.
     */
    @ColumnInfo(name = "volume_name")
    val volumeName: String? = null,

    @ColumnInfo(name = "drive_file_id")
    val driveFileId: String? = null,

    /** ISO-8601 UTC with ms. */
    @ColumnInfo(name = "created_at")
    val createdAt: String,

    @ColumnInfo(name = "updated_at")
    val updatedAt: String,

    /** 1 = needs upload to Drive; cleared by sync worker. */
    @ColumnInfo(name = "dirty", defaultValue = "1")
    val dirty: Boolean = true,

    /**
     * ISO-8601 UTC when archived; null = active. Archived notebooks move to
     * the Archive tab and stop showing in Current notebooks. Orthogonal to
     * `deleted_at` — soft-deleted rows are hidden from both tabs.
     */
    @ColumnInfo(name = "archived_at")
    val archivedAt: String? = null,

    /** ISO-8601 UTC when soft-deleted; null = active. */
    @ColumnInfo(name = "deleted_at")
    val deletedAt: String? = null,
)
