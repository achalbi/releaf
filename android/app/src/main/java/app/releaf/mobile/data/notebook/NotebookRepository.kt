/*
 * NotebookRepository.kt
 *
 * Orchestrates notebook + chapter + page writes as cohesive units. Consumers
 * (ViewModels) only need this one repo — the child DAOs are exposed for
 * read-side Flow observation but we funnel cascade semantics through here so
 * soft-deletes of a notebook also tombstone the chapters and pages beneath
 * it (and undo restores the whole triple).
 */

package app.releaf.mobile.data.notebook

import app.releaf.mobile.data.common.IsoClock
import app.releaf.mobile.data.common.Uuidv7
import kotlinx.coroutines.flow.Flow

class NotebookRepository(
    private val notebookDao: NotebookDao,
    private val chapterDao: ChapterDao,
    private val pageDao: PageDao,
) {
    /* ---------- reads ---------- */

    fun observeActive(): Flow<List<NotebookEntity>> = notebookDao.observeActive()
    fun observeById(id: String): Flow<NotebookEntity?> = notebookDao.observeById(id)
    suspend fun findById(id: String): NotebookEntity? = notebookDao.findById(id)

    /* ---------- create / update ---------- */

    /**
     * Create a notebook. The editor VM is fine passing `title` and an
     * optional `colorHex`; everything else (id, timestamps, dirty) is filled
     * in here so the UI layer isn't duplicating boilerplate.
     */
    suspend fun createNotebook(title: String, colorHex: String? = null): NotebookEntity {
        val now = IsoClock.nowIso()
        val entity = NotebookEntity(
            id        = Uuidv7.generate(),
            title     = title.trim(),
            colorHex  = colorHex,
            createdAt = now,
            updatedAt = now,
            dirty     = true,
        )
        notebookDao.upsert(entity)
        return entity
    }

    suspend fun saveNotebook(entity: NotebookEntity) {
        notebookDao.upsert(
            entity.copy(
                title     = entity.title.trim(),
                updatedAt = IsoClock.nowIso(),
                dirty     = true,
            )
        )
    }

    /* ---------- soft delete + cascade ---------- */

    /**
     * Tombstone a notebook and every live chapter + page beneath it. Each
     * row gets `deleted_at = now, dirty = 1` so the sync worker knows to
     * propagate the cascade to Drive — we don't rely on remote-side cascade.
     *
     * No transaction today: Room's suspend DAOs run on the IO dispatcher and
     * each suspend call is its own short transaction. For v1 MVP the
     * consistency window is acceptable (a UI that observes mid-cascade just
     * flicks rows out in sequence). When we care, wrap in `db.withTransaction`.
     */
    suspend fun softDeleteNotebook(id: String) {
        val now = IsoClock.nowIso()
        // Collect live chapter ids *before* the cascade so we know which ones
        // we own (for potential undo use). Pages cascade per-chapter below.
        val liveChapters = chapterDao.liveIdsForNotebook(id)
        liveChapters.forEach { chapterId ->
            pageDao.softDeleteCascadeForChapter(chapterId = chapterId, nowIso = now)
        }
        chapterDao.softDeleteCascadeForNotebook(notebookId = id, nowIso = now)
        notebookDao.softDelete(id = id, nowIso = now)
    }

    /**
     * Restore a notebook tombstone. Intentionally restores *only* the
     * notebook row — chapters and pages that were cascaded into tombstones
     * by [softDeleteNotebook] stay deleted. A "full restore" mode would need
     * to snapshot the cascade list before delete and replay it; that's a
     * phase-3 concern (same data we'd want for Drive conflict resolution).
     */
    suspend fun undoSoftDeleteNotebook(id: String) {
        notebookDao.restore(id = id, nowIso = IsoClock.nowIso())
    }
}
