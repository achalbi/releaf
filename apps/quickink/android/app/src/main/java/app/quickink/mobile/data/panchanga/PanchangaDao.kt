/*
 * PanchangaDao.kt
 *
 * Read-mostly DAO over the bundled panchanga dataset. Writes happen
 * once on first launch (asset bootstrap in `PanchangaRepository`)
 * and again only on user-initiated refresh; everything else is
 * observation.
 *
 * Port of Releaf Android's `PanchangaDao` — package rename only.
 */

package app.quickink.mobile.data.panchanga

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PanchangaDao {

    /**
     * All rows for the given Gregorian date, ordered by numeric
     * tithi. Some dates carry two rows (one tithi rolling into the
     * next during the day); callers iterate the list to render each
     * variant. Empty list when the date is outside the dataset
     * range — the UI surfaces a "data not available" placeholder
     * instead of inventing one.
     */
    @Query(
        """
        SELECT * FROM panchanga
        WHERE date = :date
        ORDER BY thithi_num ASC
        """
    )
    fun observeForDate(date: String): Flow<List<PanchangaEntity>>

    /**
     * All rows whose date falls within the given month, used to
     * render the festival-dot indicators on the calendar grid.
     * `monthPrefix` is `yyyy-MM-` (with the trailing dash) so the
     * `LIKE` matches every day of that month and only that month.
     */
    @Query(
        """
        SELECT * FROM panchanga
        WHERE date LIKE :monthPrefix || '%'
        ORDER BY date ASC, thithi_num ASC
        """
    )
    fun observeForMonth(monthPrefix: String): Flow<List<PanchangaEntity>>

    /**
     * All rows that carry a non-empty `special_day`. The repo applies
     * the tokenized search over this in-memory list — a single-`LIKE`
     * SQL query couldn't AND-match tokens across multiple columns
     * (e.g. "Krishna" in `paksha` plus "Janmashtami" in `special_day`)
     * without dynamic SQL, and the dataset is small (~330 such rows)
     * so filtering in Kotlin stays well under a frame budget.
     */
    @Query(
        """
        SELECT * FROM panchanga
        WHERE special_day_lc != ''
        ORDER BY date ASC, thithi_num ASC
        """
    )
    fun observeAllSpecialDays(): Flow<List<PanchangaEntity>>

    /** Quick gate for the first-launch bootstrap path. */
    @Query("SELECT COUNT(*) FROM panchanga")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<PanchangaEntity>)

    /** Used by the refresh path before re-inserting from a freshly
     *  parsed CSV — keeps the table from holding rows for dates that
     *  were dropped between dataset versions. */
    @Query("DELETE FROM panchanga")
    suspend fun deleteAll()
}
