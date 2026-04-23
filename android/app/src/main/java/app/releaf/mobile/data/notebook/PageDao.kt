/*
 * PageDao.kt
 *
 * Queries for the `pages` table, plus FTS5 search over `fts_page_notes`. The
 * FTS virtual table and its triggers are installed in
 * `ReleafDatabase.SchemaCallback` from the SQL in v1_initial.sql §FTS5; the
 * search query here joins pages back to the indexed-id column.
 */

package app.releaf.mobile.data.notebook

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.SkipQueryVerification
import kotlinx.coroutines.flow.Flow

@Dao
interface PageDao {

    /** Active pages in a chapter, in manual ordering. */
    @Query(
        """
        SELECT * FROM pages
        WHERE chapter_id = :chapterId AND deleted_at IS NULL
        ORDER BY position ASC, created_at ASC
        """
    )
    fun observeForChapter(chapterId: String): Flow<List<PageEntity>>

    /**
     * All active pages within a notebook (joined through chapters), newest
     * edits first. Used by the notebook-detail flat view and by the tab-level
     * search when no query is typed.
     */
    @Query(
        """
        SELECT p.* FROM pages p
        JOIN chapters c ON c.id = p.chapter_id
        WHERE c.notebook_id = :notebookId
          AND p.deleted_at IS NULL
          AND c.deleted_at IS NULL
        ORDER BY p.updated_at DESC
        """
    )
    fun observeForNotebook(notebookId: String): Flow<List<PageEntity>>

    @Query(
        """
        SELECT * FROM pages
        WHERE id = :id AND deleted_at IS NULL
        LIMIT 1
        """
    )
    fun observeById(id: String): Flow<PageEntity?>

    @Query("SELECT * FROM pages WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): PageEntity?

    /**
     * Full-text search across *every* live page the user can see. `query` must
     * already be in FTS5 MATCH syntax — the repo sanitizes it (reuses the
     * same builder as notepad; see `NotebookSearchUtils.buildFtsQuery`).
     *
     * @SkipQueryVerification is required because `fts_page_notes` is a
     * virtual table installed via SchemaCallback and isn't known to KSP's
     * @Entity-derived schema sandbox.
     */
    @SkipQueryVerification
    @Query(
        """
        SELECT p.* FROM pages p
        JOIN fts_page_notes fts ON fts.page_id = p.id
        WHERE p.deleted_at IS NULL
          AND fts_page_notes MATCH :query
        ORDER BY rank
        """
    )
    fun searchAllActive(query: String): Flow<List<PageEntity>>

    /** Same as [searchAllActive] but scoped to one notebook. */
    @SkipQueryVerification
    @Query(
        """
        SELECT p.* FROM pages p
        JOIN chapters c ON c.id = p.chapter_id
        JOIN fts_page_notes fts ON fts.page_id = p.id
        WHERE c.notebook_id = :notebookId
          AND p.deleted_at IS NULL
          AND c.deleted_at IS NULL
          AND fts_page_notes MATCH :query
        ORDER BY rank
        """
    )
    fun searchInNotebook(notebookId: String, query: String): Flow<List<PageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: PageEntity)

    @Query(
        """
        UPDATE pages
        SET deleted_at = :nowIso, updated_at = :nowIso, dirty = 1
        WHERE id = :id
        """
    )
    suspend fun softDelete(id: String, nowIso: String)

    @Query(
        """
        UPDATE pages
        SET deleted_at = NULL, updated_at = :nowIso, dirty = 1
        WHERE id = :id
        """
    )
    suspend fun restore(id: String, nowIso: String)

    /**
     * Cascade soft-delete: mark every live page under a chapter as deleted.
     * Called when a chapter is soft-deleted (and transitively when a notebook
     * is soft-deleted, via the chapter pass).
     */
    @Query(
        """
        UPDATE pages
        SET deleted_at = :nowIso, updated_at = :nowIso, dirty = 1
        WHERE chapter_id = :chapterId AND deleted_at IS NULL
        """
    )
    suspend fun softDeleteCascadeForChapter(chapterId: String, nowIso: String): Int

    /* ---------- sync worker ---------- */

    @Query("SELECT * FROM pages WHERE dirty = 1")
    suspend fun dirtyRows(): List<PageEntity>

    /** All non-deleted pages, one-shot. Feeds the v2 sync manifest. */
    @Query("SELECT * FROM pages WHERE deleted_at IS NULL")
    suspend fun activeRows(): List<PageEntity>

    /** Lookup-by-ids for the pull path. */
    @Query("SELECT * FROM pages WHERE id IN (:ids)")
    suspend fun findByIds(ids: List<String>): List<PageEntity>

    /** Count of live pages — feeds the sync manifest's `entity_counts`. */
    @Query("SELECT COUNT(*) FROM pages WHERE deleted_at IS NULL")
    suspend fun countActive(): Int

    /**
     * Live-page-count-per-notebook for the Notebooks tab rows. Same shape as
     * ChapterDao.observeChapterCounts — joins through chapters so we count
     * pages whose *chapter* is also live (cascaded tombstones are excluded).
     */
    @Query(
        """
        SELECT c.notebook_id AS notebookId, COUNT(p.id) AS count
        FROM pages p
        JOIN chapters c ON c.id = p.chapter_id
        WHERE p.deleted_at IS NULL AND c.deleted_at IS NULL
        GROUP BY c.notebook_id
        """
    )
    fun observePageCountsByNotebook(): Flow<List<NotebookCountRow>>

    /**
     * Live-page-count-per-chapter for the notebook detail screen's Chapter
     * rows. Excludes deleted pages; chapters with zero pages won't appear in
     * the emission.
     */
    @Query(
        """
        SELECT chapter_id AS chapterId, COUNT(*) AS count
        FROM pages
        WHERE deleted_at IS NULL
        GROUP BY chapter_id
        """
    )
    fun observePageCountsByChapter(): Flow<List<ChapterCountRow>>

    /** Race-safe — see NotepadDao.markSynced for the design note. */
    @Query(
        """
        UPDATE pages
        SET dirty = 0, drive_file_id = :driveFileId
        WHERE id = :id
          AND dirty = 1
          AND updated_at = :updatedAtSnapshot
        """
    )
    suspend fun markSynced(id: String, driveFileId: String, updatedAtSnapshot: String): Int

    @Query(
        """
        UPDATE pages
        SET dirty = 0
        WHERE id = :id AND deleted_at IS NOT NULL
        """
    )
    suspend fun markTombstoneSynced(id: String): Int
}
