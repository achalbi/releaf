/*
 * ChapterDao.kt
 *
 * Queries for the `chapters` table. Most query paths are parent-scoped —
 * chapters are only meaningful inside their notebook.
 */

package app.releaf.mobile.data.notebook

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChapterDao {

    /** Active chapters in a notebook, in manual ordering. */
    @Query(
        """
        SELECT * FROM chapters
        WHERE notebook_id = :notebookId AND deleted_at IS NULL
        ORDER BY position ASC, created_at ASC
        """
    )
    fun observeForNotebook(notebookId: String): Flow<List<ChapterEntity>>

    @Query(
        """
        SELECT * FROM chapters
        WHERE id = :id AND deleted_at IS NULL
        LIMIT 1
        """
    )
    fun observeById(id: String): Flow<ChapterEntity?>

    @Query("SELECT * FROM chapters WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): ChapterEntity?

    /**
     * First active chapter under a notebook, in the same ordering the
     * notebook detail renders. Used by Quick Capture to land on a real
     * chapter without the user having to choose.
     */
    @Query(
        """
        SELECT id FROM chapters
        WHERE notebook_id = :notebookId AND deleted_at IS NULL
        ORDER BY position ASC, created_at ASC
        LIMIT 1
        """
    )
    suspend fun firstIdForNotebook(notebookId: String): String?

    /**
     * Count active chapters under a notebook. Used by the repo's "can delete
     * this notebook?" guard and the list screen's meta line.
     */
    @Query(
        """
        SELECT COUNT(*) FROM chapters
        WHERE notebook_id = :notebookId AND deleted_at IS NULL
        """
    )
    suspend fun countForNotebook(notebookId: String): Int

    /**
     * Live-count-per-notebook feed for the Notebooks tab. Emits one row per
     * notebook that has at least one active chapter; callers treat "missing"
     * as zero. Cheaper than observing chapters for every notebook separately.
     */
    @Query(
        """
        SELECT notebook_id AS notebookId, COUNT(*) AS count
        FROM chapters
        WHERE deleted_at IS NULL
        GROUP BY notebook_id
        """
    )
    fun observeChapterCounts(): Flow<List<NotebookCountRow>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: ChapterEntity)

    @Query(
        """
        UPDATE chapters
        SET deleted_at = :nowIso, updated_at = :nowIso, dirty = 1
        WHERE id = :id
        """
    )
    suspend fun softDelete(id: String, nowIso: String)

    @Query(
        """
        UPDATE chapters
        SET deleted_at = NULL, updated_at = :nowIso, dirty = 1
        WHERE id = :id
        """
    )
    suspend fun restore(id: String, nowIso: String)

    /**
     * Cascade soft-delete when a parent notebook is deleted. Touches only
     * live rows so a later notebook-undo can distinguish "was already
     * tombstoned before" from "cascaded by the notebook delete".
     *
     * Returns the number of chapters that were cascaded — the caller needs
     * this to recurse into pages via ChapterDao.liveIdsForNotebook() +
     * PageDao.softDeleteCascadeForChapters().
     */
    @Query(
        """
        UPDATE chapters
        SET deleted_at = :nowIso, updated_at = :nowIso, dirty = 1
        WHERE notebook_id = :notebookId AND deleted_at IS NULL
        """
    )
    suspend fun softDeleteCascadeForNotebook(notebookId: String, nowIso: String): Int

    /** IDs of currently-live chapters under a notebook. */
    @Query(
        """
        SELECT id FROM chapters
        WHERE notebook_id = :notebookId AND deleted_at IS NULL
        """
    )
    suspend fun liveIdsForNotebook(notebookId: String): List<String>

    /* ---------- sync worker ---------- */

    @Query("SELECT * FROM chapters WHERE dirty = 1")
    suspend fun dirtyRows(): List<ChapterEntity>

    /**
     * Global count of live chapters — feeds the sync manifest's
     * `entity_counts`. Different from [countForNotebook], which is parent-
     * scoped for the notebook detail screen.
     */
    @Query("SELECT COUNT(*) FROM chapters WHERE deleted_at IS NULL")
    suspend fun countActive(): Int

    /** Race-safe — see NotepadDao.markSynced for the design note. */
    @Query(
        """
        UPDATE chapters
        SET dirty = 0, drive_file_id = :driveFileId
        WHERE id = :id
          AND dirty = 1
          AND updated_at = :updatedAtSnapshot
        """
    )
    suspend fun markSynced(id: String, driveFileId: String, updatedAtSnapshot: String): Int

    @Query(
        """
        UPDATE chapters
        SET dirty = 0
        WHERE id = :id AND deleted_at IS NOT NULL
        """
    )
    suspend fun markTombstoneSynced(id: String): Int
}
