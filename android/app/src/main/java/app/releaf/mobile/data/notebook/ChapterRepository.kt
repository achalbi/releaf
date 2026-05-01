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
    /** See note on NotepadRepository — nullable for tests. */
    private val auditLogger: app.releaf.mobile.data.activity.AuditLogger? = null,
) {
    fun observeForNotebook(notebookId: String): Flow<List<ChapterEntity>> =
        chapterDao.observeForNotebook(notebookId)

    fun observeArchivedForNotebook(notebookId: String): Flow<List<ChapterEntity>> =
        chapterDao.observeArchivedForNotebook(notebookId)

    fun observeById(id: String): Flow<ChapterEntity?> = chapterDao.observeById(id)

    suspend fun findById(id: String): ChapterEntity? = chapterDao.findById(id)

    /** chapter-count-per-notebook feed for the Notebooks tab rows. */
    fun observeChapterCounts(): Flow<List<NotebookCountRow>> =
        chapterDao.observeChapterCounts()

    suspend fun createChapter(
        notebookId: String,
        title: String,
        description: String? = null,
    ): ChapterEntity {
        val now = IsoClock.nowIso()
        val entity = ChapterEntity(
            id          = Uuidv7.generate(),
            notebookId  = notebookId,
            title       = title.trim(),
            description = description?.trim()?.ifEmpty { null },
            createdAt   = now,
            updatedAt   = now,
            dirty       = true,
        )
        chapterDao.upsert(entity)
        auditLogger?.log(
            action     = app.releaf.mobile.data.activity.AuditAction.Created,
            entityType = app.releaf.mobile.data.activity.AuditEntity.Chapter,
            entityId   = entity.id,
            title      = entity.title.ifBlank { "Untitled chapter" },
        )
        return entity
    }

    suspend fun saveChapter(entity: ChapterEntity) {
        val updated = entity.copy(
            title       = entity.title.trim(),
            description = entity.description?.trim()?.ifEmpty { null },
            updatedAt   = IsoClock.nowIso(),
            dirty       = true,
        )
        chapterDao.upsert(updated)
        auditLogger?.log(
            action     = app.releaf.mobile.data.activity.AuditAction.Updated,
            entityType = app.releaf.mobile.data.activity.AuditEntity.Chapter,
            entityId   = updated.id,
            title      = updated.title.ifBlank { "Untitled chapter" },
        )
    }

    /** Soft-delete a chapter and cascade to its pages. */
    suspend fun softDeleteChapter(id: String) {
        val snapshot = chapterDao.findById(id)
        val now = IsoClock.nowIso()
        pageDao.softDeleteCascadeForChapter(chapterId = id, nowIso = now)
        chapterDao.softDelete(id = id, nowIso = now)
        auditLogger?.log(
            action     = app.releaf.mobile.data.activity.AuditAction.Deleted,
            entityType = app.releaf.mobile.data.activity.AuditEntity.Chapter,
            entityId   = id,
            title      = snapshot?.title?.takeIf { it.isNotBlank() } ?: "Untitled chapter",
        )
    }

    suspend fun undoSoftDeleteChapter(id: String) {
        chapterDao.restore(id = id, nowIso = IsoClock.nowIso())
        val snapshot = chapterDao.findById(id)
        auditLogger?.log(
            action     = app.releaf.mobile.data.activity.AuditAction.Restored,
            entityType = app.releaf.mobile.data.activity.AuditEntity.Chapter,
            entityId   = id,
            title      = snapshot?.title?.takeIf { it.isNotBlank() } ?: "Untitled chapter",
        )
    }
}
