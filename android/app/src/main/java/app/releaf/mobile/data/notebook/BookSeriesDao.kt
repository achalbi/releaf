/*
 * BookSeriesDao.kt
 *
 * Room DAO for the `book_series` table. Soft-deleted rows are
 * filtered out at the query level so callers never leak them.
 */

package app.releaf.mobile.data.notebook

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BookSeriesDao {

    @Query(
        """
        SELECT * FROM book_series
        WHERE shelf_id = :shelfId AND deleted_at IS NULL
        ORDER BY name ASC
        """
    )
    fun observeForShelf(shelfId: String): Flow<List<BookSeriesEntity>>

    @Query("SELECT * FROM book_series WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): BookSeriesEntity?

    @Query("SELECT * FROM book_series WHERE deleted_at IS NULL")
    fun observeAllActive(): Flow<List<BookSeriesEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: BookSeriesEntity)

    @Query(
        """
        UPDATE book_series
        SET deleted_at = :nowIso, updated_at = :nowIso, dirty = 1
        WHERE id = :id
        """
    )
    suspend fun softDelete(id: String, nowIso: String)
}
