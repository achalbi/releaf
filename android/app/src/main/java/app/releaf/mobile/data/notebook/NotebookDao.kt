/*
 * NotebookDao.kt
 *
 * Queries for the `notebooks` table. Active rows only — soft-deletes are
 * filtered here (deleted_at IS NULL) so callers never have to remember.
 * Mirrors the shape of NotepadDao for consistency.
 */

package app.releaf.mobile.data.notebook

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface NotebookDao {

    /**
     * Live, non-archived notebooks — the "Current notebooks" tab feed.
     * Two-key ORDER BY keeps drag-to-reorder deterministic while still
     * letting recent activity surface ahead of long-idle notebooks with
     * the same position.
     */
    @Query(
        """
        SELECT * FROM notebooks
        WHERE deleted_at IS NULL AND archived_at IS NULL
        ORDER BY position ASC, updated_at DESC
        """
    )
    fun observeActive(): Flow<List<NotebookEntity>>

    /** Archived (but not deleted) notebooks — the "Archive" tab feed. */
    @Query(
        """
        SELECT * FROM notebooks
        WHERE deleted_at IS NULL AND archived_at IS NOT NULL
        ORDER BY archived_at DESC
        """
    )
    fun observeArchived(): Flow<List<NotebookEntity>>

    /** Observe a single notebook (null when not found or soft-deleted). */
    @Query(
        """
        SELECT * FROM notebooks
        WHERE id = :id AND deleted_at IS NULL
        LIMIT 1
        """
    )
    fun observeById(id: String): Flow<NotebookEntity?>

    /** One-shot lookup used by parent screens on first load. */
    @Query("SELECT * FROM notebooks WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): NotebookEntity?

    /**
     * First active notebook by the "Current" tab's ordering. Used by Quick
     * Capture to pick a default landing notebook when the user taps the
     * middle leaf button — null means the user hasn't created any yet.
     */
    @Query(
        """
        SELECT * FROM notebooks
        WHERE deleted_at IS NULL AND archived_at IS NULL
        ORDER BY position ASC, updated_at DESC
        LIMIT 1
        """
    )
    suspend fun firstActive(): NotebookEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: NotebookEntity)

    @Query(
        """
        UPDATE notebooks
        SET deleted_at = :nowIso, updated_at = :nowIso, dirty = 1
        WHERE id = :id
        """
    )
    suspend fun softDelete(id: String, nowIso: String)

    /** Inverse of [softDelete] — pairs with the list's "Undo" snackbar. */
    @Query(
        """
        UPDATE notebooks
        SET deleted_at = NULL, updated_at = :nowIso, dirty = 1
        WHERE id = :id
        """
    )
    suspend fun restore(id: String, nowIso: String)

    /** Move a notebook into the Archive tab. */
    @Query(
        """
        UPDATE notebooks
        SET archived_at = :nowIso, updated_at = :nowIso, dirty = 1
        WHERE id = :id
        """
    )
    suspend fun archive(id: String, nowIso: String)

    /** Inverse of [archive]. */
    @Query(
        """
        UPDATE notebooks
        SET archived_at = NULL, updated_at = :nowIso, dirty = 1
        WHERE id = :id
        """
    )
    suspend fun unarchive(id: String, nowIso: String)

    /* ---------- sync worker ---------- */

    @Query("SELECT * FROM notebooks WHERE dirty = 1")
    suspend fun dirtyRows(): List<NotebookEntity>

    /** All non-deleted notebooks, one-shot. Feeds the v2 sync manifest. */
    @Query("SELECT * FROM notebooks WHERE deleted_at IS NULL")
    suspend fun activeRows(): List<NotebookEntity>

    /** Lookup-by-ids for the pull path. */
    @Query("SELECT * FROM notebooks WHERE id IN (:ids)")
    suspend fun findByIds(ids: List<String>): List<NotebookEntity>

    /** Count of live notebooks — feeds the sync manifest's `entity_counts`. */
    @Query("SELECT COUNT(*) FROM notebooks WHERE deleted_at IS NULL")
    suspend fun countActive(): Int

    /** Race-safe — see NotepadDao.markSynced for the design note. */
    @Query(
        """
        UPDATE notebooks
        SET dirty = 0, drive_file_id = :driveFileId
        WHERE id = :id
          AND dirty = 1
          AND updated_at = :updatedAtSnapshot
        """
    )
    suspend fun markSynced(id: String, driveFileId: String, updatedAtSnapshot: String): Int

    @Query(
        """
        UPDATE notebooks
        SET dirty = 0
        WHERE id = :id AND deleted_at IS NOT NULL
        """
    )
    suspend fun markTombstoneSynced(id: String): Int
}
