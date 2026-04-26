/*
 * PageRepository.kt
 *
 * Single-page CRUD + FTS-backed search. Mirrors NotepadRepository in shape
 * (create / save / softDelete / undoSoftDelete / search) so the UI layer
 * uses the same mental model across both surfaces.
 */

package app.releaf.mobile.data.notebook

import app.releaf.mobile.data.common.FtsQuery
import app.releaf.mobile.data.common.IsoClock
import app.releaf.mobile.data.common.Uuidv7
import app.releaf.mobile.data.notepad.NotepadEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class PageRepository(
    private val pageDao: PageDao,
    /** See note on NotepadRepository — nullable for tests. */
    private val auditLogger: app.releaf.mobile.data.activity.AuditLogger? = null,
) {
    fun observeForChapter(chapterId: String): Flow<List<PageEntity>> =
        pageDao.observeForChapter(chapterId)

    fun observeForNotebook(notebookId: String): Flow<List<PageEntity>> =
        pageDao.observeForNotebook(notebookId)

    fun observeById(id: String): Flow<PageEntity?> = pageDao.observeById(id)

    /** page-count-per-notebook feed for the Notebooks tab rows. */
    fun observePageCountsByNotebook(): Flow<List<NotebookCountRow>> =
        pageDao.observePageCountsByNotebook()

    /** page-count-per-chapter feed for the notebook detail Chapter rows. */
    fun observePageCountsByChapter(): Flow<List<ChapterCountRow>> =
        pageDao.observePageCountsByChapter()

    /** Pages that carry at least one todo (open or done), newest-edit
     *  first. The caller is responsible for parsing the per-row
     *  `todos` JSON and filtering to open items. Drives the Open-todos
     *  modal on the library header. */
    fun observePagesWithTodos(): Flow<List<PageTodosRow>> =
        pageDao.observePagesWithTodos()

    suspend fun findById(id: String): PageEntity? = pageDao.findById(id)

    /**
     * Global FTS search across all live pages. Empty or all-noise queries
     * short-circuit to an empty flow so we don't pass a bad MATCH expression
     * into SQLite.
     */
    fun searchAll(rawQuery: String): Flow<List<PageEntity>> {
        val match = FtsQuery.build(rawQuery) ?: return flowOf(emptyList())
        return pageDao.searchAllActive(match)
    }

    /** Global FTS search with notebook/chapter labels for UI context. */
    fun searchAllWithContext(rawQuery: String): Flow<List<PageSearchHit>> {
        val match = FtsQuery.build(rawQuery) ?: return flowOf(emptyList())
        return pageDao.searchAllActiveWithContext(match)
    }

    /** Same as [searchAll] but scoped to a single notebook. */
    fun searchInNotebook(notebookId: String, rawQuery: String): Flow<List<PageEntity>> {
        val match = FtsQuery.build(rawQuery) ?: return flowOf(emptyList())
        return pageDao.searchInNotebook(notebookId, match)
    }

    suspend fun createPage(
        chapterId: String,
        title: String?,
        notes: String,
    ): PageEntity {
        val now = IsoClock.nowIso()
        val entity = PageEntity(
            id        = Uuidv7.generate(),
            chapterId = chapterId,
            title     = title?.trim()?.ifEmpty { null },
            notes     = notes,
            createdAt = now,
            updatedAt = now,
            dirty     = true,
        )
        pageDao.upsert(entity)
        auditLogger?.log(
            action     = app.releaf.mobile.data.activity.AuditAction.Created,
            entityType = app.releaf.mobile.data.activity.AuditEntity.Page,
            entityId   = entity.id,
            title      = entity.title ?: "Untitled page",
        )
        return entity
    }

    /**
     * Create a new page inside [chapterId] whose body is copied straight
     * from a notepad entry. All the JSON side channels (sub-pages,
     * attachments, todos, contacts, locations, strokes) are carried over
     * verbatim — both surfaces share the same JSON shapes, so no
     * conversion is needed. The caller is expected to soft-delete the
     * source notepad entry after this returns.
     */
    suspend fun createFromNotepadEntry(
        chapterId: String,
        source: NotepadEntry,
    ): PageEntity {
        val now = IsoClock.nowIso()
        val page = PageEntity(
            id            = Uuidv7.generate(),
            chapterId     = chapterId,
            projectId     = source.projectId,
            title         = source.title?.trim()?.ifEmpty { null },
            notes         = source.notes,
            contacts      = source.contacts,
            locations     = source.locations,
            todos         = source.todos,
            attachments   = source.attachments,
            sketchStrokes = source.sketchStrokes,
            subPages      = source.subPages,
            createdAt     = now,
            updatedAt     = now,
            dirty         = true,
        )
        pageDao.upsert(page)
        auditLogger?.log(
            action     = app.releaf.mobile.data.activity.AuditAction.Created,
            entityType = app.releaf.mobile.data.activity.AuditEntity.Page,
            entityId   = page.id,
            title      = page.title ?: "Untitled page",
        )
        return page
    }

    suspend fun savePage(entity: PageEntity) {
        val updated = entity.copy(
            title     = entity.title?.trim()?.ifEmpty { null },
            updatedAt = IsoClock.nowIso(),
            dirty     = true,
        )
        pageDao.upsert(updated)
        auditLogger?.log(
            action     = app.releaf.mobile.data.activity.AuditAction.Updated,
            entityType = app.releaf.mobile.data.activity.AuditEntity.Page,
            entityId   = updated.id,
            title      = updated.title ?: "Untitled page",
        )
    }

    suspend fun softDeletePage(id: String) {
        val snapshot = pageDao.findById(id)
        pageDao.softDelete(id = id, nowIso = IsoClock.nowIso())
        auditLogger?.log(
            action     = app.releaf.mobile.data.activity.AuditAction.Deleted,
            entityType = app.releaf.mobile.data.activity.AuditEntity.Page,
            entityId   = id,
            title      = snapshot?.title ?: "Untitled page",
        )
    }

    suspend fun undoSoftDeletePage(id: String) {
        pageDao.restore(id = id, nowIso = IsoClock.nowIso())
        val snapshot = pageDao.findById(id)
        auditLogger?.log(
            action     = app.releaf.mobile.data.activity.AuditAction.Restored,
            entityType = app.releaf.mobile.data.activity.AuditEntity.Page,
            entityId   = id,
            title      = snapshot?.title ?: "Untitled page",
        )
    }
}
