/*
 * ShelfDao.kt
 *
 * Room DAO for the `shelves` table. Soft-deleted rows are filtered
 * here so callers never have to remember.
 */

package app.releaf.mobile.data.shelf

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ShelfDao {

    /** Live (non-deleted) shelves ordered for list display. */
    @Query(
        """
        SELECT * FROM shelves
        WHERE deleted_at IS NULL
        ORDER BY position ASC, created_at ASC
        """
    )
    fun observeActive(): Flow<List<ShelfEntity>>

    @Query("SELECT * FROM shelves WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<ShelfEntity?>

    @Query("SELECT * FROM shelves WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): ShelfEntity?

    @Query("SELECT COUNT(*) FROM shelves WHERE deleted_at IS NULL")
    suspend fun countActive(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ShelfEntity)

    @Query(
        """
        UPDATE shelves
        SET deleted_at = :nowIso, updated_at = :nowIso, dirty = 1
        WHERE id = :id
        """
    )
    suspend fun softDelete(id: String, nowIso: String)

    @Query(
        """
        UPDATE shelves
        SET deleted_at = NULL, updated_at = :nowIso, dirty = 1
        WHERE id = :id
        """
    )
    suspend fun restore(id: String, nowIso: String)
}
