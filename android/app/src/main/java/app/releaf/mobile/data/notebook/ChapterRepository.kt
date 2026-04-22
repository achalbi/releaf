/*
 * ChapterRepository.kt
 *
 * Per-notebook chapter CRUD. Cascade-on-delete goes through this repo (not
 * the DAO) so pages get tombstoned too.
 */

package app.releaf.mobile.data.notebook

import app.releaf.mobile.data.common.IsoClock
import app.releaf.mobile.data.common.Uuidv7
import kotlinx.coroutines.flow.Flow

class ChapterRepository(
    private val chapterDao: ChapterDao,
    private val pageDao: PageDao,
) {
    fun observeForNotebook(notebookId: String): Flow<List<ChapterEntity>> =
        chapterDao.observeForNotebook(notebookId)

    fun observeById(id: String): Flow<ChapterEntity?> = chapterDao.observeById(id)

    suspend fun findById(id: String): ChapterEntity? = chapterDao.findById(id)

    suspend fun createChapter(notebookId: String, title: String): ChapterEntity {
        val now = IsoClock.nowIso()
        val entity = ChapterEntity(
            id         = Uuidv7.generate(),
            notebookId = notebookId,
            title      = title.trim(),
            createdAt  = now,
            updatedAt  = now,
            dirty      = true,
        )
        chapterDao.upsert(entity)
        return entity
    }

    suspend fun saveChapter(entity: ChapterEntity) {
        chapterDao.upsert(
            entity.copy(
                title     = entity.title.trim(),
                updatedAt = IsoClock.nowIso(),
                dirty     = true,
            )
        )
    }

    /** Soft-delete a chapter and cascade to its pages. */
    suspend fun softDeleteChapter(id: String) {
        val now = IsoClock.nowIso()
        pageDao.softDeleteCascadeForChapter(chapterId = id, nowIso = now)
        chapterDao.softDelete(id = id, nowIso = now)
    }

    suspend fun undoSoftDeleteChapter(id: String) {
        chapterDao.restore(id = id, nowIso = IsoClock.nowIso())
    }
}
