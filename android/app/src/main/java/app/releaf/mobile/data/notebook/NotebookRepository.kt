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
    private val bookSeriesDao: BookSeriesDao,
    /** See note on NotepadRepository — nullable for tests. */
    private val auditLogger: app.releaf.mobile.data.activity.AuditLogger? = null,
) {
    /* ---------- reads ---------- */

    fun observeActive(): Flow<List<NotebookEntity>> = notebookDao.observeActive()
    fun observeArchived(): Flow<List<NotebookEntity>> = notebookDao.observeArchived()
    fun observeForShelf(shelfId: String): Flow<List<NotebookEntity>> =
        notebookDao.observeForShelf(shelfId)
    fun observeForSeries(seriesId: String): Flow<List<NotebookEntity>> =
        notebookDao.observeForSeries(seriesId)
    fun observeById(id: String): Flow<NotebookEntity?> = notebookDao.observeById(id)
    suspend fun findById(id: String): NotebookEntity? = notebookDao.findById(id)

    /* ---------- create / update ---------- */

    /**
     * Create a standalone book (no series). If the caller wants the
     * book to live in a series, use [createBookInNewSeries] or
     * [addVolumeToSeries] instead.
     *
     * `shelfId` defaults to the General shelf so existing call-sites
     * (Quick Capture fallback, legacy VM paths) continue to work
     * without plumbing shelf awareness through every layer.
     */
    suspend fun createNotebook(
        title: String,
        colorHex: String? = null,
        description: String? = null,
        shelfId: String = "shelf-general",
    ): NotebookEntity {
        val now = IsoClock.nowIso()
        val entity = NotebookEntity(
            id           = Uuidv7.generate(),
            title        = title.trim(),
            description  = description?.trim()?.ifEmpty { null },
            colorHex     = colorHex,
            shelfId      = shelfId,
            seriesId     = null,
            volumeNumber = 1,
            volumeName   = null,
            createdAt    = now,
            updatedAt    = now,
            dirty        = true,
        )
        notebookDao.upsert(entity)
        auditLogger?.log(
            action     = app.releaf.mobile.data.activity.AuditAction.Created,
            entityType = app.releaf.mobile.data.activity.AuditEntity.Notebook,
            entityId   = entity.id,
            title      = entity.title.ifBlank { "Untitled notebook" },
        )
        return entity
    }

    /**
     * Promote an existing standalone book into a series so a second
     * volume can be added. Returns the new series id (creating the
     * `book_series` row in the process). If the book is already
     * part of a series, returns the existing `seriesId`.
     */
    suspend fun ensureSeriesFor(notebookId: String, seriesName: String? = null): String {
        val nb = notebookDao.findById(notebookId) ?: error("Notebook $notebookId not found")
        nb.seriesId?.let { return it }

        val now = IsoClock.nowIso()
        val series = BookSeriesEntity(
            id        = Uuidv7.generate(),
            shelfId   = nb.shelfId,
            name      = seriesName?.trim()?.ifEmpty { null } ?: nb.title,
            createdAt = now,
            updatedAt = now,
            dirty     = true,
        )
        bookSeriesDao.upsert(series)
        notebookDao.upsert(
            nb.copy(
                seriesId     = series.id,
                volumeNumber = 1,
                updatedAt    = now,
                dirty        = true,
            )
        )
        return series.id
    }

    /**
     * Add a new volume under an existing series. `volumeName` may be
     * blank — the UI derives "<series> vol <n>" from the series name
     * and number when `volumeName` is null.
     */
    suspend fun addVolumeToSeries(
        seriesId: String,
        volumeName: String? = null,
        colorHex: String? = null,
    ): NotebookEntity {
        val series = bookSeriesDao.findById(seriesId)
            ?: error("Series $seriesId not found")
        val next = (notebookDao.maxVolumeFor(seriesId) ?: 0) + 1
        val cleanedVolumeName = volumeName?.trim()?.ifEmpty { null }
        val displayTitle = cleanedVolumeName ?: "${series.name} vol $next"

        val now = IsoClock.nowIso()
        val entity = NotebookEntity(
            id           = Uuidv7.generate(),
            title        = displayTitle,
            description  = null,
            colorHex     = colorHex,
            shelfId      = series.shelfId,
            seriesId     = series.id,
            volumeNumber = next,
            volumeName   = cleanedVolumeName,
            createdAt    = now,
            updatedAt    = now,
            dirty        = true,
        )
        notebookDao.upsert(entity)
        auditLogger?.log(
            action     = app.releaf.mobile.data.activity.AuditAction.Created,
            entityType = app.releaf.mobile.data.activity.AuditEntity.Notebook,
            entityId   = entity.id,
            title      = entity.title.ifBlank { "Untitled notebook" },
        )
        return entity
    }

    /**
     * Create a fresh book AND its enclosing series in one call — the
     * common flow when a user says "New book" knowing they'll want
     * multiple volumes. The first volume defaults to number 1 and
     * inherits the book's title when `volumeName` is null.
     */
    suspend fun createBookInNewSeries(
        shelfId: String,
        seriesName: String,
        volumeName: String? = null,
        colorHex: String? = null,
    ): NotebookEntity {
        val now = IsoClock.nowIso()
        val series = BookSeriesEntity(
            id        = Uuidv7.generate(),
            shelfId   = shelfId,
            name      = seriesName.trim().ifEmpty { "Untitled book" },
            createdAt = now,
            updatedAt = now,
            dirty     = true,
        )
        bookSeriesDao.upsert(series)
        val cleanedVolumeName = volumeName?.trim()?.ifEmpty { null }
        val entity = NotebookEntity(
            id           = Uuidv7.generate(),
            title        = cleanedVolumeName ?: series.name,
            description  = null,
            colorHex     = colorHex,
            shelfId      = shelfId,
            seriesId     = series.id,
            volumeNumber = 1,
            volumeName   = cleanedVolumeName,
            createdAt    = now,
            updatedAt    = now,
            dirty        = true,
        )
        notebookDao.upsert(entity)
        auditLogger?.log(
            action     = app.releaf.mobile.data.activity.AuditAction.Created,
            entityType = app.releaf.mobile.data.activity.AuditEntity.Notebook,
            entityId   = entity.id,
            title      = entity.title.ifBlank { "Untitled notebook" },
        )
        return entity
    }

    /**
     * Resolve a chapter id suitable for a brand-new page created from Quick
     * Capture (middle leaf button). Picks the first active notebook and its
     * first chapter, matching the ordering the Notebooks tab / detail screen
     * already use. Auto-creates a "Quick Notes" notebook and "Notes" chapter
     * on fresh installs so the capture flow never has to dead-end.
     */
    suspend fun resolveQuickCaptureChapter(): String {
        val notebook = notebookDao.firstActive() ?: run {
            val now = IsoClock.nowIso()
            val entity = NotebookEntity(
                id        = Uuidv7.generate(),
                title     = "Quick Notes",
                createdAt = now,
                updatedAt = now,
                dirty     = true,
            )
            notebookDao.upsert(entity)
            entity
        }
        chapterDao.firstIdForNotebook(notebook.id)?.let { return it }
        val now = IsoClock.nowIso()
        val chapter = ChapterEntity(
            id         = Uuidv7.generate(),
            notebookId = notebook.id,
            title      = "Notes",
            createdAt  = now,
            updatedAt  = now,
            dirty      = true,
        )
        chapterDao.upsert(chapter)
        return chapter.id
    }

    suspend fun saveNotebook(entity: NotebookEntity) {
        val updated = entity.copy(
            title       = entity.title.trim(),
            description = entity.description?.trim()?.ifEmpty { null },
            updatedAt   = IsoClock.nowIso(),
            dirty       = true,
        )
        notebookDao.upsert(updated)
        auditLogger?.log(
            action     = app.releaf.mobile.data.activity.AuditAction.Updated,
            entityType = app.releaf.mobile.data.activity.AuditEntity.Notebook,
            entityId   = updated.id,
            title      = updated.title.ifBlank { "Untitled notebook" },
        )
    }

    /* ---------- archive ---------- */

    suspend fun archiveNotebook(id: String) {
        notebookDao.archive(id = id, nowIso = IsoClock.nowIso())
        val snapshot = notebookDao.findById(id)
        auditLogger?.log(
            action     = app.releaf.mobile.data.activity.AuditAction.Updated,
            entityType = app.releaf.mobile.data.activity.AuditEntity.Notebook,
            entityId   = id,
            title      = snapshot?.title?.ifBlank { "Untitled notebook" } ?: "Untitled notebook",
        )
    }

    suspend fun unarchiveNotebook(id: String) {
        notebookDao.unarchive(id = id, nowIso = IsoClock.nowIso())
        val snapshot = notebookDao.findById(id)
        auditLogger?.log(
            action     = app.releaf.mobile.data.activity.AuditAction.Restored,
            entityType = app.releaf.mobile.data.activity.AuditEntity.Notebook,
            entityId   = id,
            title      = snapshot?.title?.ifBlank { "Untitled notebook" } ?: "Untitled notebook",
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
        val snapshot = notebookDao.findById(id)
        val now = IsoClock.nowIso()
        // Collect live chapter ids *before* the cascade so we know which ones
        // we own (for potential undo use). Pages cascade per-chapter below.
        val liveChapters = chapterDao.liveIdsForNotebook(id)
        liveChapters.forEach { chapterId ->
            pageDao.softDeleteCascadeForChapter(chapterId = chapterId, nowIso = now)
        }
        chapterDao.softDeleteCascadeForNotebook(notebookId = id, nowIso = now)
        notebookDao.softDelete(id = id, nowIso = now)
        // One audit row for the notebook itself — cascaded pages /
        // chapters intentionally don't emit per-row events; the
        // notebook delete is the user-visible action.
        auditLogger?.log(
            action     = app.releaf.mobile.data.activity.AuditAction.Deleted,
            entityType = app.releaf.mobile.data.activity.AuditEntity.Notebook,
            entityId   = id,
            title      = snapshot?.title?.ifBlank { "Untitled notebook" } ?: "Untitled notebook",
        )
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
        val snapshot = notebookDao.findById(id)
        auditLogger?.log(
            action     = app.releaf.mobile.data.activity.AuditAction.Restored,
            entityType = app.releaf.mobile.data.activity.AuditEntity.Notebook,
            entityId   = id,
            title      = snapshot?.title?.ifBlank { "Untitled notebook" } ?: "Untitled notebook",
        )
    }
}
