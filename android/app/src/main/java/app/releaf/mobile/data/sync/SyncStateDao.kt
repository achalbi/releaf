/*
 * SyncStateDao.kt
 *
 * Read + write the local `sync_state` key-value store. Keys are the
 * constants in [SyncStateKeys].
 */

package app.releaf.mobile.data.sync

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncStateDao {

    /** Observe a single key — returns null when the row doesn't exist yet. */
    @Query("SELECT * FROM sync_state WHERE key = :key LIMIT 1")
    fun observe(key: String): Flow<SyncStateEntity?>

    /** One-shot read — used from worker code paths. */
    @Query("SELECT * FROM sync_state WHERE key = :key LIMIT 1")
    suspend fun get(key: String): SyncStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: SyncStateEntity)
}
