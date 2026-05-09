/*
 * AnalyticsOutboxDao.kt
 *
 * Room DAO for `analytics_outbox`. All flush-side reads scope by
 * next_attempt_at <= now() so backed-off rows don't keep getting
 * picked, and ORDER BY created_at so the oldest events flush
 * first (FIFO). The 200-row LIMIT matches the backend's
 * MAX_BATCH_SIZE.
 *
 * Idempotency on enqueue: REPLACE strategy. For capture rows the
 * id IS the capture_event UUID, so re-enqueuing the same capture
 * (e.g. after a process death between insert + flush) just
 * overwrites in place — no duplicate rows, no duplicate POSTs.
 */

package app.quickink.mobile.data.analytics

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AnalyticsOutboxDao {
    /** REPLACE so re-enqueue of the same event id is idempotent. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: AnalyticsOutboxEntity)

    /**
     * Rows that are ready to flush (now() >= next_attempt_at), oldest
     * first. Only `limit` rows so we never over-batch the backend.
     */
    @Query(
        """
        SELECT * FROM analytics_outbox
        WHERE next_attempt_at <= :now
        ORDER BY created_at ASC
        LIMIT :limit
        """
    )
    suspend fun nextBatch(now: String, limit: Int): List<AnalyticsOutboxEntity>

    /** Hard count for diagnostic / dashboard purposes. */
    @Query("SELECT COUNT(*) FROM analytics_outbox")
    suspend fun count(): Int

    /** Drop the listed rows after the server confirmed acceptance. */
    @Query("DELETE FROM analytics_outbox WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    /** Bulk-update the listed rows after a transient failure. */
    @Query(
        """
        UPDATE analytics_outbox
           SET attempts        = attempts + 1,
               next_attempt_at = :nextAttemptAt,
               last_error      = :error
         WHERE id IN (:ids)
        """
    )
    suspend fun bumpFailure(ids: List<String>, nextAttemptAt: String, error: String)

    /**
     * GC: drop rows that are older than 30 days regardless of
     * status. Means a row that's been failing forever (because
     * Auth is broken, or backend rejected the payload, etc.) won't
     * grow the table indefinitely.
     */
    @Query("DELETE FROM analytics_outbox WHERE created_at < :cutoff")
    suspend fun dropOlderThan(cutoff: String)
}
