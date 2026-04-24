/*
 * ShelfEntity.kt
 *
 * Room entity for the `shelves` table — top of the Shelf → Book →
 * Chapter → Page hierarchy. A shelf is a user-editable container
 * (name, color, position); books live inside exactly one shelf.
 *
 * Introduced in DB v12. Existing notebooks are backfilled onto the
 * default `shelf-general` shelf by Migration11To12.
 */

package app.releaf.mobile.data.shelf

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "shelves",
    indices = [
        Index("position"),
        Index("deleted_at"),
    ],
)
data class ShelfEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "name")
    val name: String,

    /** Hex color (e.g. `#7AA874`) or null for theme default. */
    @ColumnInfo(name = "color_hex")
    val colorHex: String? = null,

    /** 1024-step spacing leaves room for reordering without renumbering. */
    @ColumnInfo(name = "position", defaultValue = "1024")
    val position: Long = 1024L,

    @ColumnInfo(name = "created_at")
    val createdAt: String,

    @ColumnInfo(name = "updated_at")
    val updatedAt: String,

    @ColumnInfo(name = "dirty", defaultValue = "1")
    val dirty: Boolean = true,

    @ColumnInfo(name = "deleted_at")
    val deletedAt: String? = null,
) {
    companion object {
        /** Stable id of the default "General" shelf seeded by migration. */
        const val DEFAULT_GENERAL_ID = "shelf-general"
    }
}
