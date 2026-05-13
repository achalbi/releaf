/*
 * CategoryEntity.kt
 *
 * Room @Entity for the `categories` table — user-configurable tags
 * shown in the scan-review screen's picker and managed in
 * Settings → Categories. Schema mirrors
 * `shared/design-system/migrations/quickink/v2_capture_categories.sql`.
 *
 * Mirror of `CategoryEntity.swift` in QuickInk's iOS target.
 */

package app.quickink.mobile.data.category

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "categories",
    indices = [
        Index(value = ["user_id", "name"], unique = true),
        Index(value = ["user_id", "position"], name = "idx_categories_user_position"),
        Index(value = ["dirty"], name = "idx_categories_dirty"),
        Index(value = ["deleted_at"], name = "idx_categories_tombstone"),
    ],
)
data class CategoryEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "user_id")
    val userId: String,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "position", defaultValue = "0")
    val position: Int,

    /**
     * Workspace v1: optional hex color for the tag chip (e.g. "#E66943").
     * Stored as text so the palette can grow without a schema migration.
     * NULL → UI falls back to the accent tint. Added in v4_workspace.sql.
     *
     * Note: this entity is being semantically renamed from
     * "category" to "tag" in Phase A.2 — table will rename to
     * `tags`, package to `data.tag`, class to `TagEntity`.
     */
    @ColumnInfo(name = "color")
    val color: String? = null,

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
