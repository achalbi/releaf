/*
 * BookSeriesEntity.kt
 *
 * Groups notebooks that are volumes of the same book. A series
 * belongs to exactly one shelf; a book row (volume) points back at
 * its series via `series_id`. Single-volume books carry
 * `series_id = NULL` so the UI can render them without a "Vol 1"
 * suffix.
 *
 * Introduced in DB v12.
 */

package app.releaf.mobile.data.notebook

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "book_series",
    indices = [
        Index("shelf_id"),
        Index("deleted_at"),
    ],
)
data class BookSeriesEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "shelf_id")
    val shelfId: String,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "created_at")
    val createdAt: String,

    @ColumnInfo(name = "updated_at")
    val updatedAt: String,

    @ColumnInfo(name = "dirty", defaultValue = "1")
    val dirty: Boolean = true,

    @ColumnInfo(name = "deleted_at")
    val deletedAt: String? = null,
)
