/*
 * AuditDao.kt
 *
 * Room access for the append-only audit_events table. Insert path is
 * fire-and-forget (any errors should never block the underlying
 * mutation). Read path emits a Flow keyed on user_id + a row cap.
 */

package app.releaf.mobile.data.activity

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AuditDao {

    /**
     * Live feed of recent events for a user, newest first, capped.
     * Re-emits on every committed transaction touching audit_events.
     */
    @Query(
        """
        SELECT * FROM audit_events
        WHERE user_id = :userId
        ORDER BY timestamp DESC
        LIMIT :maxItems
        """
    )
    fun observe(userId: String, maxItems: Int): Flow<List<AuditEvent>>

    /** One-shot count — used by the backfill check to avoid double-seeding. */
    @Query("SELECT COUNT(*) FROM audit_events WHERE user_id = :userId")
    suspend fun countForUser(userId: String): Int

    /** Insert-or-replace by id (uuidv7 collisions are effectively zero). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: AuditEvent)

    /** Bulk insert — used by the first-launch backfill so 100s of
     *  synthetic events land in a single transaction. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<AuditEvent>)

    /**
     * Pruning — delete rows older than the cutoff. Returns rows
     * affected so the caller can log the volume. Driven by a
     * scheduled worker; manual run from settings is also wired.
     */
    @Query("DELETE FROM audit_events WHERE timestamp < :cutoffIso")
    suspend fun pruneOlderThan(cutoffIso: String): Int

    /**
     * Wipe all audit events for a user. Backs the "Clear all
     * activity" action in Settings ▸ Activity. Hard delete — the
     * audit log doesn't audit itself, so there's nothing to
     * tombstone.
     */
    @Query("DELETE FROM audit_events WHERE user_id = :userId")
    suspend fun deleteAllForUser(userId: String): Int

    /* ---------- Drive sync foundation (phase 4 wiring point) ---------- */

    /**
     * Rows needing upload to Drive. Append-only — there's no
     * "tombstone" branch like the other entity tables, so this is
     * just `dirty = 1` with no deleted_at filter.
     */
    @Query("SELECT * FROM audit_events WHERE dirty = 1")
    suspend fun dirtyRows(): List<AuditEvent>

    /**
     * Race-safe clear-dirty after a successful Drive upload. No
     * updated_at guard because audit rows are immutable once written
     * — there's no concurrent edit to race against.
     */
    @Query("UPDATE audit_events SET dirty = 0 WHERE id = :id AND dirty = 1")
    suspend fun markSynced(id: String): Int
}
