/*
 * FolderEntity.kt
 *
 * Room @Entity for the `folders` table — the "intent" axis of the
 * Workspace two-axis IA. One row per user-defined folder. Captures
 * reference it via `captures.folder_id`. Folders are color-coded,
 * carry live-state badges in the UI ("3 new", "needs review"), and
 * are the (future) unit of sharing/collaboration.
 *
 * Schema mirrors
 * `shared/design-system/migrations/quickink/v4_workspace.sql`.
 *
 * Mirror of `FolderEntity.swift` in QuickInk's iOS target (lands in
 * the iOS Phase A pass).
 */

package app.quickink.mobile.data.folder

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "folders",
    indices = [
        // One folder name per user, excluding tombstones — same
        // pattern as `tags` (formerly `categories`). The partial
        // UNIQUE lives below as a SQL index; Room can't express
        // partial uniqueness via @Index, so the @Index here just
        // mirrors the regular indexes for query-plan parity.
        Index(value = ["user_id", "name"], unique = true),
        Index(value = ["user_id", "position"], name = "idx_folders_user_position"),
        Index(value = ["dirty"], name = "idx_folders_dirty"),
        Index(value = ["deleted_at"], name = "idx_folders_tombstone"),
    ],
)
data class FolderEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "user_id")
    val userId: String,

    @ColumnInfo(name = "name")
    val name: String,

    /**
     * Hex color (`#E66943`). NOT NULL — every folder carries a
     * visible identity in the Workspace home folder list. Seeded
     * "Unsorted" uses a neutral stone color; user-created folders
     * pick from the design's palette (coral, gold, green, blue,
     * purple, pink, teal). Stored as text so the palette can grow
     * without a schema migration.
     */
    @ColumnInfo(name = "color")
    val color: String,

    /**
     * Caller-managed sort order — lower values render first. Mirror
     * of `tags.position`. Seeded "Unsorted" gets position = 0.
     */
    @ColumnInfo(name = "position", defaultValue = "0")
    val position: Int,

    /**
     * Reserved for the design's "covers" folder-visual mode
     * (Milanote-style cover image). Out of scope for v1 ship; the
     * column exists so adding the visual later doesn't churn the
     * schema again.
     */
    @ColumnInfo(name = "cover_uri")
    val coverUri: String? = null,

    /**
     * Exactly one row per user has `isDefault = true`. That's the
     * seeded "Unsorted" folder. Used to guard the UI from deleting
     * it and to backfill orphan captures when a user-folder is
     * deleted (move-to-Unsorted, not cascade — per the brief).
     */
    @ColumnInfo(name = "is_default", defaultValue = "0")
    val isDefault: Boolean = false,

    /**
     * Reserved column for the post-v1 share flow. Defaults to
     * false. Costs nothing to add now; saves a migration when
     * sharing ships.
     */
    @ColumnInfo(name = "is_shared", defaultValue = "0")
    val isShared: Boolean = false,

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
)
