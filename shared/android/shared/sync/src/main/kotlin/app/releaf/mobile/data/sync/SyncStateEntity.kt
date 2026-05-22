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
    /**
     * Outcome of the most recent sync pass — one of [SyncErrorCodes].
     * Cleared on a successful run, so the UI can surface a "needs
     * re-auth" banner after persistent auth failures without
     * showing it forever once the user fixes the underlying issue.
     */
    const val LAST_SYNC_ERROR_CODE     = "last_sync_error_code"

    /**
     * Last time QuickInk itself enqueued an automatic Drive backup.
     * Manual "Sync now" requests do not touch this value; it exists
     * only to cap dirty-record auto sync to once per day.
     */
    const val LAST_AUTO_SYNC_REQUEST_AT = "last_auto_sync_request_at"

    /**
     * Number of locally-dirty rows that haven't been pushed to
     * Drive yet. Refreshed by a foreground 60-second poll in
     * `QuickInkApp` and zeroed by the worker on a successful push.
     * Drives the "N pending" pill on the Home screen, and triggers
     * the auto-safety-net push when > 0.
     *
     * Distinct from [PENDING_COUNT] which is set on sync FAILURE
     * to indicate work that didn't go through. Future refactor: fold
     * both signals into one — for now they're separate so existing
     * code paths don't fight over a single key.
     */
    const val LOCAL_DIRTY_COUNT        = "local_dirty_count"

    /**
     * Outcome of the most recent Restore-from-Drive run. Pipe-
     * separated key=value snapshot the Settings screen reads to
     * surface a transient banner ("Restored 73 items, 11 orphan
     * rows skipped") after the worker completes. Cleared by the
     * dismiss button or by a fresh restore tap.
     *
     * Format: `downloaded=N|applyFailed=N|orphanSkipped=N|cleanedOrphans=N|completedAt=<ISO>|status=<ok|failed|version_blocked>`.
     * Pipe-separated keeps the parsing trivial in Compose without
     * pulling kotlinx.serialization into the UI module.
     */
    const val LAST_RESTORE_OUTCOME      = "last_restore_outcome"
}

/** Values the sync worker writes to [SyncStateKeys.LAST_SYNC_ERROR_CODE]. */
object SyncErrorCodes {
    /** Drive responded 401/403 — token rejected or scope not granted. */
    const val AUTH_REJECTED = "AUTH_REJECTED"
    /** Network / 5xx / I-O — retryable, no user action needed. */
    const val TRANSIENT     = "TRANSIENT"
    /** Anything else — log says the rest. */
    const val UNKNOWN       = "UNKNOWN"
}
