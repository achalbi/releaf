/*
 * PerspectiveDao.kt
 *
 * CRUD queries for [PerspectiveEntity]. Follows the same shape as
 * TaskDao — Flow for reactive observation, suspend fns for one-shot
 * mutations. Soft-deletes are filtered here so callers never have to
 * remember `deleted_at IS NULL`.
 */

package app.releaf.mobile.data.perspective

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PerspectiveDao {

    /**
     * Active (not-deleted) perspectives for a user, ordered by
     * `sort_order` ascending (assigned at insert time in the
     * repository) then `created_at` as a tie-breaker so two
     * perspectives added in a burst still render in a deterministic
     * order.
     */
    @Query(
        """
        SELECT * FROM perspectives
        WHERE user_id = :userId AND deleted_at IS NULL
        ORDER BY sort_order ASC, created_at ASC
        """
    )
    fun observeActive(userId: String): Flow<List<PerspectiveEntity>>

    /** One-shot count for first-run seeding. */
    @Query(
        """
        SELECT COUNT(*) FROM perspectives
        WHERE user_id = :userId AND deleted_at IS NULL
        """
    )
    suspend fun countActive(userId: String): Int

    /**
     * Lookup by name — used to deduplicate when the user types an
     * `@tag` that already exists, or when the task title path
     * auto-ensures a perspective for a tag it doesn't know yet.
     */
    @Query(
        """
        SELECT * FROM perspectives
        WHERE user_id = :userId
          AND name = :name
          AND deleted_at IS NULL
        LIMIT 1
        """
    )
    suspend fun findByName(userId: String, name: String): PerspectiveEntity?

    /** Insert-or-replace. Callers set `updated_at`. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(perspective: PerspectiveEntity)

    /** Soft delete. Flips deleted_at + bumps updated_at. */
    @Query(
        """
        UPDATE perspectives
        SET deleted_at = :nowIso,
            updated_at = :nowIso
        WHERE id = :id
        """
    )
    suspend fun softDelete(id: String, nowIso: String)
}
