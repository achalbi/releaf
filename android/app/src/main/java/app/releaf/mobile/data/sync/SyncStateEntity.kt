/*
 * SyncStateEntity.kt
 *
 * Room entity mirroring the `sync_state` key-value table in
 * `design-system/migrations/v1_initial.sql` §Sync. This table is local-only
 * (never synced to Drive) and caches things the sync worker and the UI
 * badge want to read cheaply:
 *
 *   - `last_full_sync_at`       ISO-8601 UTC
 *   - `last_incremental_sync_at` ISO-8601 UTC
 *   - `manifest_checksum`       hex SHA-256 of the Drive manifest we last
 *                               wrote — lets a future pull path detect
 *                               remote edits without full diff.
 *   - `pending_count`           integer (quick badge for UI)
 *   - `drive_quota_exhausted_at` ISO-8601 UTC | absent when healthy
 *
 * Rows are written via upsert; callers never touch `updated_at` directly.
 */

package app.releaf.mobile.data.sync

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "key")
    val key: String,

    @ColumnInfo(name = "value")
    val value: String? = null,

    @ColumnInfo(name = "updated_at")
    val updatedAt: String,
)

/** Canonical keys — keep the full set here so callers don't stringly-type. */
object SyncStateKeys {
    const val LAST_FULL_SYNC_AT        = "last_full_sync_at"
    const val LAST_INCREMENTAL_SYNC_AT = "last_incremental_sync_at"
    const val MANIFEST_CHECKSUM        = "manifest_checksum"
    const val PENDING_COUNT            = "pending_count"
    const val DRIVE_QUOTA_EXHAUSTED_AT = "drive_quota_exhausted_at"
}
