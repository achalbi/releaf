/*
 * CaptureTagEntity.kt
 *
 * Room @Entity for the `capture_tags` many-to-many join — links
 * captures to tags. Each row syncs independently (own id + dirty
 * + tombstone trio) so a tag added on phone A reaches phone B
 * without re-syncing the entire capture. See brief §2.
 *
 * Schema mirrors
 * `shared/design-system/migrations/quickink/v4_workspace.sql`.
 */

package app.quickink.mobile.data.capturetag

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.quickink.mobile.data.capture.CaptureEntity
import app.quickink.mobile.data.tag.TagEntity

@Entity(
    tableName = "capture_tags",
    foreignKeys = [
        ForeignKey(
            entity = CaptureEntity::class,
            parentColumns = ["id"],
            childColumns = ["capture_id"],
            // No cascade — capture_tags rows soft-delete
            // independently. A capture deletion tombstones the
            // capture; the join rows are cleaned up by the sync
            // worker separately so cross-device propagation works
            // even after the parent is gone locally.
            onDelete = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tag_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(value = ["capture_id"], name = "idx_capture_tags_capture"),
        Index(value = ["tag_id"], name = "idx_capture_tags_tag"),
        Index(value = ["dirty"], name = "idx_capture_tags_dirty"),
        Index(value = ["deleted_at"], name = "idx_capture_tags_tombstone"),
        // Logical unique-active constraint lives as a partial
        // index in SQL (see v4_workspace.sql); Room can't express
        // partial uniqueness so the plain index here just makes
        // joins fast. The app-side insert path is responsible for
        // dedup against tombstoned rows.
        Index(value = ["capture_id", "tag_id"], name = "idx_capture_tags_pair"),
    ],
)
data class CaptureTagEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "capture_id")
    val captureId: String,

    @ColumnInfo(name = "tag_id")
    val tagId: String,

    /**
     * Provenance of this tag assignment:
     *
     * - `"manual"` — user-typed in the tag picker bottom sheet.
     * - `"ai-suggested"` — written by the auto-tagging heuristic
     *   (Phase E). Kept as a flag so analytics can measure
     *   suggestion acceptance rate. Visually identical to manual
     *   tags after the user accepts (per brief §10 #9).
     * - `"migration"` — the v4 backfill row carrying the legacy
     *   `captures.category` value into the join. Lets us tell
     *   organic tagging activity apart from one-time migration
     *   noise in analytics dashboards.
     *
     * Stored as free-form TEXT so future sources land without a
     * migration.
     */
    @ColumnInfo(name = "source", defaultValue = "manual")
    val source: String = "manual",

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
