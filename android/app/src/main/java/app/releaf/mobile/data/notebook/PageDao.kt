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
import androidx.room.RawQuery
import androidx.room.RoomRawQuery
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

    /**
     * Every live page across every chapter + notebook — used by the
     * contacts directory to scan the `contacts` JSON column without
     * having to load a notebook at a time.
     */
    @Query(
        """
        SELECT * FROM pages
        WHERE deleted_at IS NULL
        ORDER BY updated_at DESC
        """
    )
    fun observeAllActive(): Flow<List<PageEntity>>

    /**
     * Real-table invalidation source for global page search. FTS queries
     * below are intentionally one-shot suspend calls because Room cannot
     * observe `fts_page_notes` (the virtual table is installed by the DB
     * callback, not declared as an @Entity).
     */
    @Query(
        """
        SELECT p.id FROM pages p
        JOIN chapters c ON c.id = p.chapter_id
        JOIN notebooks n ON n.id = c.notebook_id
        WHERE p.deleted_at IS NULL
          AND c.deleted_at IS NULL
          AND n.deleted_at IS NULL
        ORDER BY p.updated_at DESC
        """
    )
    fun observeSearchScope(): Flow<List<String>>

    @Query("SELECT * FROM pages WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): PageEntity?

    /**
     * One-shot list of live pages in a chapter, ordered the same
     * way `observeForChapter` does. Used by the request/response
     * `LocalDriveRepository` reads — that layer doesn't subscribe
     * to flows, just snapshots.
     */
    @Query(
        """
        SELECT * FROM pages
        WHERE chapter_id = :chapterId AND deleted_at IS NULL
        ORDER BY position ASC, created_at ASC
        """
    )
    suspend fun findByChapterActive(chapterId: String): List<PageEntity>

    /**
     * All pages where `archived_at IS NOT NULL` and the row hasn't
     * been soft-deleted. Used by `LocalDriveRepository.listArchivedPages`
     * to populate the cross-notebook archive picker. Ordered
     * newest-archived first so the picker surfaces fresh entries
     * at the top.
     */
    @Query(
        """
        SELECT * FROM pages
        WHERE archived_at IS NOT NULL AND deleted_at IS NULL
        ORDER BY archived_at DESC
        """
    )
    suspend fun findArchived(): List<PageEntity>

    /**
     * Full-text search across *every* live page the user can see. The query
     * is built by the repository as a [RoomRawQuery] (see
     * `PageRepository.searchAll`); the bound MATCH expression must already
     * be in FTS5 MATCH syntax (see `FtsQuery.build`).
     *
     * Uses `@RawQuery` (not `@Query`) because `fts_page_notes` is a virtual
     * table installed by `ReleafDatabase.SchemaCallback` and isn't declared
     * as an @Entity. Room 2.7's invalidation tracker validates every observed
     * table at query time — including for `suspend` queries, since the
     * generated DAO_Impl routes them through `FlowUtil.createFlow` for
     * thread management — and would throw `IllegalArgumentException: There
     * is no table with name fts_page_notes` if the table name appeared in a
     * `@Query` SQL. `@RawQuery(observedEntities = [...])` skips SQL parsing
     * entirely and only watches the entities listed.
     */
    @RawQuery(observedEntities = [PageEntity::class])
    suspend fun searchAllActive(query: RoomRawQuery): List<PageEntity>

    /**
     * Same global FTS search, but with notebook/chapter labels attached so
     * the notebook tab can tell the user where a matching page lives. See
     * the comment on [searchAllActive] for the `@RawQuery` rationale; observed
     * entities are widened to include `chapters` and `notebooks` because the
     * SELECT joins them.
     */
    @RawQuery(observedEntities = [PageEntity::class, ChapterEntity::class, NotebookEntity::class])
    suspend fun searchAllActiveWithContext(query: RoomRawQuery): List<PageSearchHit>

    /** Same as [searchAllActive] but scoped to one notebook. */
    @RawQuery(observedEntities = [PageEntity::class, ChapterEntity::class])
    suspend fun searchInNotebook(query: RoomRawQuery): List<PageEntity>

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

    /**
     * Live pages (joined to their chapter + notebook) that carry at least
     * one todo JSON entry. Cheap server-side filter — the `todos != '[]'`
     * clause drops pages with no todos; the caller still has to parse the
     * JSON string to separate open vs done. Ordered newest-edit first so
     * the open-todos modal on the library header can show the user the
     * most recently touched items without an additional sort pass.
     */
    @Query(
        """
        SELECT
            p.id AS id,
            p.title AS title,
            p.todos AS todos,
            p.updated_at AS updatedAt,
            n.id AS notebookId,
            n.title AS notebookTitle,
            c.id AS chapterId,
            c.title AS chapterTitle
        FROM pages p
        JOIN chapters c ON c.id = p.chapter_id
        JOIN notebooks n ON n.id = c.notebook_id
        WHERE p.deleted_at IS NULL
          AND c.deleted_at IS NULL
          AND n.deleted_at IS NULL
          AND p.todos IS NOT NULL
          AND p.todos != '[]'
        ORDER BY p.updated_at DESC
        """
    )
    fun observePagesWithTodos(): Flow<List<PageTodosRow>>

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
