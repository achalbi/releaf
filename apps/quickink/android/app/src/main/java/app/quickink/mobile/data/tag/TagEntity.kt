/*
 * TagEntity.kt
 *
 * Room @Entity for the `tags` table — the "content" axis of the
 * Workspace two-axis IA. Many tags per capture (via the
 * `capture_tags` join), free-form, cross-cutting, AI-augmentable.
 *
 * History: this entity was originally `CategoryEntity` in the
 * `categories` table — flat, single-tag-per-capture, managed in
 * Settings → Categories. The Workspace v1 redesign (see
 * apps/quickink/design/WORKSPACE_SPEC.md and the plan doc) promotes
 * categories to a many-to-many tag axis and adds a separate
 * `folders` table for the "intent" axis. Same data, sharper IA.
 *
 * Schema mirrors
 * `shared/design-system/migrations/quickink/v4_workspace.sql`
 * (renames `categories` → `tags`, adds `color`).
 *
 * Mirror of `TagEntity.swift` in QuickInk's iOS target (lands in
 * the iOS Phase A pass; iOS still says CategoryEntity until then).
 */

package app.quickink.mobile.data.tag

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "tags",
    indices = [
        Index(value = ["user_id", "name"], unique = true),
        Index(value = ["user_id", "position"], name = "idx_tags_user_position"),
        Index(value = ["dirty"], name = "idx_tags_dirty"),
        Index(value = ["deleted_at"], name = "idx_tags_tombstone"),
    ],
)
data class TagEntity(
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
     * Optional hex color for the tag chip (e.g. "#E66943"). NULL →
     * UI falls back to the accent tint. Stored as text so the
     * palette can grow without a schema migration.
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
