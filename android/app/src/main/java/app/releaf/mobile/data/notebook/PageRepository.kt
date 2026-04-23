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
        return page
    }

    suspend fun savePage(entity: PageEntity) {
        pageDao.upsert(
            entity.copy(
                title     = entity.title?.trim()?.ifEmpty { null },
                updatedAt = IsoClock.nowIso(),
                dirty     = true,
            )
        )
    }

    suspend fun softDeletePage(id: String) {
        pageDao.softDelete(id = id, nowIso = IsoClock.nowIso())
    }

    suspend fun undoSoftDeletePage(id: String) {
        pageDao.restore(id = id, nowIso = IsoClock.nowIso())
    }
}
