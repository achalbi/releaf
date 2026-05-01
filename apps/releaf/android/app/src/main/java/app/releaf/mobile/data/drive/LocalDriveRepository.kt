/*
 * LocalDriveRepository.kt
 *
 * Real, Room-backed `DriveRepository` implementation. Replaces
 * `FakeDriveRepository` as the production data source — every
 * method talks to the SQLite tables defined by `ReleafDatabase`'s
 * migrations and round-trips through the persistence ↔ domain
 * mappers in `LocalDriveRepositoryMappers.kt`.
 *
 * Why this lives in the data layer (not feature):
 * - The underlying DAOs (`PageDao`, `ChapterDao`, `NotebookDao`,
 *   `ShelfDao`) already speak Room and own their own observation
 *   flows. This class is the *Drive-shaped* facade over them —
 *   same API surface as the interface, different storage.
 * - ViewModels keep depending on `DriveRepository` (the
 *   interface); nothing in the feature layer changes when the
 *   implementation swaps under them. Only `ReleafApp` flips
 *   `driveRepository` from `FakeDriveRepository()` to a
 *   `LocalDriveRepository(...)`.
 *
 * Caveats:
 * - Counts on the `Notebook` domain shape (`chapterCount`,
 *   `pageCount`) are populated lazily on `loadNotebook(id)` and
 *   `listNotebooks()`. Single-row reads (e.g. after a rename)
 *   default both to 0; the calling screen's `viewModel.load()`
 *   re-fetches and gets fresh counts. Acceptable for now.
 * - The Drive sync layer (Drive REST push) is not wired in this
 *   class. Writes set `dirty = true` on each row so the existing
 *   sync worker (`SyncWorker` / `OkHttpDriveClient`) can pick
 *   them up and push when sync is configured. Local persistence
 *   stands on its own without sync.
 *
 * Mirrors `LocalDriveRepository.swift` on iOS — the two
 * implementations are intentionally close to each other so the
 * cross-platform behaviour stays in lockstep.
 */

package app.releaf.mobile.data.drive

import androidx.room.withTransaction
import app.releaf.mobile.data.common.IsoClock
import app.releaf.mobile.data.db.ReleafDatabase
import app.releaf.mobile.data.domain.Chapter
import app.releaf.mobile.data.domain.Notebook
import app.releaf.mobile.data.domain.Page
import app.releaf.mobile.data.domain.PageTemplate
import app.releaf.mobile.data.notebook.ChapterEntity
import app.releaf.mobile.data.notebook.PageEntity
import app.releaf.mobile.data.notebook.StoredPageNote
import app.releaf.mobile.data.notebook.parsePageNotes
import app.releaf.mobile.data.notebook.parseTodos
import app.releaf.mobile.data.notebook.toJsonString
import java.time.Instant
import java.util.UUID
import app.releaf.mobile.data.notebook.TodoItem as StoredTodo

class LocalDriveRepository(
    private val database: ReleafDatabase,
) : DriveRepository {

    private val pageDao = database.pageDao()
    private val chapterDao = database.chapterDao()
    private val notebookDao = database.notebookDao()
    private val shelfDao = database.shelfDao()

    // -------------------------- Reads --------------------------

    override suspend fun listNotebooks(): List<Notebook> {
        // Active notebooks (not deleted, not archived). Counts are
        // joined in via the chapter + page DAOs in one batch each,
        // so the resulting list is render-ready without further
        // round-trips.
        val notebookEntities = notebookDao.activeRows()
        val activeNotebooks = notebookEntities.filter { it.archivedAt == null }
        val chapterCounts = chapterCountsByNotebook(activeNotebooks.map { it.id })
        val pageCounts = pageCountsByNotebook(activeNotebooks.map { it.id })
        val shelfNames = shelfNamesById(activeNotebooks.map { it.shelfId })
        return activeNotebooks.map { nb ->
            nb.toDomainNotebook(
                chapterCount = chapterCounts[nb.id] ?: 0,
                pageCount    = pageCounts[nb.id] ?: 0,
                shelfName    = shelfNames[nb.shelfId],
            )
        }
    }

    override suspend fun loadNotebook(id: String): NotebookDetail {
        val notebookEntity = notebookDao.findById(id)
            ?.takeIf { it.deletedAt == null }
            ?: throw DriveError.NotFound

        val chapterCount = chapterCountsByNotebook(listOf(id))[id] ?: 0
        val pageCount    = pageCountsByNotebook(listOf(id))[id] ?: 0
        val shelfName    = shelfNamesById(listOf(notebookEntity.shelfId))[notebookEntity.shelfId]
        val notebook = notebookEntity.toDomainNotebook(
            chapterCount = chapterCount,
            pageCount    = pageCount,
            shelfName    = shelfName,
        )

        // Active chapters in position order. Archived chapters live
        // in the chapter archive surface — the live notebook detail
        // filters them out here.
        val chapterEntities = chapterDao.activeForNotebookOnce(id)
            .filter { it.archivedAt == null }
            .sortedBy { it.position }

        val chapters = chapterEntities.map { chEntity ->
            val pageRows = pageDao.findByChapterActive(chEntity.id)
                .filter { it.archivedAt == null }
                .sortedWith(
                    compareBy<PageEntity>({ it.position }).thenByDescending { it.updatedAt },
                )
            chEntity.toDomainChapter(pages = pageRows.map { it.toPageSummary() })
        }
        return NotebookDetail(notebook = notebook, chapters = chapters)
    }

    override suspend fun loadChapters(notebookId: String): List<Chapter> {
        val chapterEntities = chapterDao.activeForNotebookOnce(notebookId)
            .filter { it.archivedAt == null }
            .sortedBy { it.position }
        return chapterEntities.map { chEntity ->
            val pageRows = pageDao.findByChapterActive(chEntity.id)
                .filter { it.archivedAt == null }
            chEntity.toDomainChapter(pages = pageRows.map { it.toPageSummary() })
        }
    }

    override suspend fun loadPage(id: String): Page {
        val pageEntity = pageDao.findById(id)
            ?.takeIf { it.deletedAt == null }
            ?: throw DriveError.NotFound
        // Pages don't store notebookId directly — chapter is the
        // FK chain so we hop one level up to resolve the parent.
        val chapterEntity = chapterDao.findById(pageEntity.chapterId)
            ?: throw DriveError.NotFound
        return pageEntity.toDomainPage(notebookId = chapterEntity.notebookId)
    }

    override suspend fun listArchivedPages(): List<ArchivedPage> {
        // Cross-table walk: archived pages → their chapter +
        // notebook for breadcrumb context. Filter out true
        // tombstones so we only surface user-archived rows.
        val archivedPageRows = pageDao.findArchived()
        if (archivedPageRows.isEmpty()) return emptyList()

        val chapterIds = archivedPageRows.map { it.chapterId }.toSet()
        val chapterMap: Map<String, ChapterEntity> = chapterIds
            .mapNotNull { chapterDao.findById(it) }
            .filter { it.deletedAt == null }
            .associateBy { it.id }
        val notebookIds = chapterMap.values.map { it.notebookId }.toSet()
        val notebookMap = notebookIds
            .mapNotNull { notebookDao.findById(it) }
            .filter { it.deletedAt == null }
            .associateBy { it.id }

        return archivedPageRows.mapNotNull { pageRow ->
            val chapter = chapterMap[pageRow.chapterId] ?: return@mapNotNull null
            val notebook = notebookMap[chapter.notebookId] ?: return@mapNotNull null
            val archivedAt = parseInstant(pageRow.archivedAt) ?: return@mapNotNull null
            ArchivedPage(
                id            = pageRow.id,
                title         = pageRow.title.orEmpty(),
                notebookId    = notebook.id,
                notebookTitle = notebook.title,
                chapterId     = chapter.id,
                chapterTitle  = chapter.title,
                archivedAt    = archivedAt,
            )
        }.sortedByDescending { it.archivedAt }
    }

    override suspend fun listPageTemplates(): List<PageTemplate> {
        // Templates aren't persisted — they're a curated catalog
        // shipped with the app. Living in code means a template
        // tweak doesn't need a migration. When they move to the
        // DB the static can come with them.
        return FakeDriveRepository.SEEDED_TEMPLATES
    }

    // -------------------------- Page mutations --------------------------

    override suspend fun archivePage(id: String): Page = database.withTransaction {
        touchPageArchive(id = id, archivedAt = Instant.now())
    }

    override suspend fun restorePage(id: String): Page = database.withTransaction {
        touchPageArchive(id = id, archivedAt = null)
    }

    override suspend fun setPageTags(pageId: String, tags: List<String>): Page = database.withTransaction {
        val entity = pageDao.findById(pageId)?.takeIf { it.deletedAt == null }
            ?: throw DriveError.NotFound
        val updated = entity.copy(
            tags      = encodeTagsJson(tags),
            updatedAt = IsoClock.nowIso(),
            dirty     = true,
        )
        pageDao.upsert(updated)
        val parentNotebookId = notebookId(forChapter = entity.chapterId)
        updated.toDomainPage(notebookId = parentNotebookId)
    }

    override suspend fun movePage(pageId: String, toNotebookId: String, toChapterId: String?) {
        database.withTransaction {
            val entity = pageDao.findById(pageId)?.takeIf { it.deletedAt == null }
                ?: throw DriveError.NotFound
            // Validate dest notebook is reachable (non-deleted).
            val destNotebook = notebookDao.findById(toNotebookId)
                ?.takeIf { it.deletedAt == null }
                ?: throw DriveError.NotFound

            // Resolve dest chapter — explicit if given, otherwise
            // the destination notebook's first active chapter.
            // Throws if neither is reachable.
            val resolvedChapterId: String = if (toChapterId != null) {
                val ch = chapterDao.findById(toChapterId)
                    ?.takeIf { it.deletedAt == null && it.notebookId == destNotebook.id }
                    ?: throw DriveError.NotFound
                ch.id
            } else {
                chapterDao.activeForNotebookOnce(destNotebook.id)
                    .filter { it.archivedAt == null }
                    .minByOrNull { it.position }
                    ?.id
                    ?: throw DriveError.NotFound
            }

            pageDao.upsert(
                entity.copy(
                    chapterId = resolvedChapterId,
                    updatedAt = IsoClock.nowIso(),
                    dirty     = true,
                ),
            )
        }
    }

    override suspend fun duplicatePage(id: String): Page = database.withTransaction {
        val source = pageDao.findById(id)?.takeIf { it.deletedAt == null }
            ?: throw DriveError.NotFound
        val newId = "pg-${UUID.randomUUID().toString().take(8)}"
        val now = IsoClock.nowIso()
        val copy = source.copy(
            id          = newId,
            title       = (source.title ?: "Untitled page") + " (copy)",
            position    = source.position + 1,
            createdAt   = now,
            updatedAt   = now,
            dirty       = true,
            deletedAt   = null,
            archivedAt  = null,
            driveFileId = null,
        )
        pageDao.upsert(copy)
        val parentNotebookId = notebookId(forChapter = source.chapterId)
        copy.toDomainPage(notebookId = parentNotebookId)
    }

    override suspend fun applyTemplate(toPageId: String, templateId: String): Page = database.withTransaction {
        val entity = pageDao.findById(toPageId)?.takeIf { it.deletedAt == null }
            ?: throw DriveError.NotFound
        val template = FakeDriveRepository.SEEDED_TEMPLATES.firstOrNull { it.id == templateId }
            ?: throw DriveError.NotFound

        // Append template pre-notes as their own typed `StoredPageNote`
        // entries so each one keeps its identity. The legacy
        // `notes` markdown column is rebuilt from the joined bodies
        // — kept in sync only because the FTS triggers index
        // against it.
        val nowIso = IsoClock.nowIso()
        val existingNotes: List<StoredPageNote> = entity.pageNotesJson.parsePageNotes()
        val appendedNotes = template.preNotes
            .filter { it.isNotBlank() }
            .mapIndexed { idx, body ->
                StoredPageNote(
                    id = "tmpl-n-${template.id}-$toPageId-$idx",
                    body = body,
                    createdAt = nowIso,
                )
            }
        val mergedNotesArray = existingNotes + appendedNotes
        val mergedPageNotesJson = mergedNotesArray.toJsonString()
        val mergedFtsNotes = mergedNotesArray.joinToString("\n\n") { it.body }

        // Append todos onto the existing JSON list. Generate ids
        // that include the page id so re-applying on different
        // pages doesn't collide.
        val existingTodos: List<StoredTodo> = entity.todos.parseTodos()
        val appendedTodos = template.preTodos.mapIndexed { idx, body ->
            StoredTodo(
                id   = "tmpl-t-${template.id}-$toPageId-$idx",
                text = body,
                done = false,
            )
        }
        val mergedTodosJson: String = (existingTodos + appendedTodos).toJsonString()

        val updated = entity.copy(
            notes         = mergedFtsNotes,
            pageNotesJson = mergedPageNotesJson,
            todos         = mergedTodosJson,
            updatedAt     = nowIso,
            dirty         = true,
        )
        pageDao.upsert(updated)
        val parentNotebookId = notebookId(forChapter = entity.chapterId)
        updated.toDomainPage(notebookId = parentNotebookId)
    }

    /**
     * Create a brand-new empty page in the given notebook + chapter.
     * Companion to the protocol's other create methods — used by
     * the Quick Capture flow on the home shell where the user
     * picks a capture mode and lands inside an empty page ready to
     * be filled in.
     */
    suspend fun createPage(notebookId: String, chapterId: String, title: String): Page = database.withTransaction {
        // Guard against stale ids — caller normally pulls these
        // out of `defaultCaptureDestination()` so they should be
        // live, but a stale path shouldn't insert an orphan page.
        val notebook = notebookDao.findById(notebookId)?.takeIf { it.deletedAt == null }
            ?: throw DriveError.NotFound
        val chapter = chapterDao.findById(chapterId)
            ?.takeIf { it.deletedAt == null && it.notebookId == notebook.id }
            ?: throw DriveError.NotFound

        val id = "pg-${UUID.randomUUID().toString().take(8)}"
        val now = IsoClock.nowIso()
        val resolvedTitle = title.trim().ifEmpty { "New page" }
        val entity = PageEntity(
            id          = id,
            chapterId   = chapter.id,
            title       = resolvedTitle,
            notes       = "",
            contacts    = "[]",
            locations   = "[]",
            todos       = "[]",
            attachments = "[]",
            sketchStrokes = "[]",
            subPages    = "[]",
            position    = 1024L,
            createdAt   = now,
            updatedAt   = now,
            dirty       = true,
            tags        = "[]",
            archivedAt  = null,
        )
        pageDao.upsert(entity)
        entity.toDomainPage(notebookId = notebook.id)
    }

    /**
     * Resolve a default destination for a fresh page when the user
     * hasn't picked one explicitly (Quick Capture flow). Returns
     * the most-recently-updated active notebook + its first
     * active chapter. Throws when nothing is reachable so the
     * caller can surface an empty state.
     */
    suspend fun defaultCaptureDestination(): Pair<String, String> {
        val notebook = notebookDao.activeRows()
            .filter { it.deletedAt == null && it.archivedAt == null }
            .maxByOrNull { it.updatedAt }
            ?: throw DriveError.NotFound
        val chapter = chapterDao.activeForNotebookOnce(notebook.id)
            .filter { it.archivedAt == null }
            .minByOrNull { it.position }
            ?: throw DriveError.NotFound
        return notebook.id to chapter.id
    }

    // -------------------------- Notebook mutations --------------------------

    override suspend fun renameNotebook(id: String, title: String): Notebook = database.withTransaction {
        val entity = notebookDao.findById(id)?.takeIf { it.deletedAt == null }
            ?: throw DriveError.NotFound
        val updated = entity.copy(
            title     = title,
            updatedAt = IsoClock.nowIso(),
            dirty     = true,
        )
        notebookDao.upsert(updated)
        updated.toDomainNotebook(
            chapterCount = 0,
            pageCount    = 0,
            shelfName    = shelfNamesById(listOf(updated.shelfId))[updated.shelfId],
        )
    }

    override suspend fun archiveNotebook(id: String): Notebook = database.withTransaction {
        touchNotebookArchive(id = id, archivedAt = Instant.now())
    }

    override suspend fun restoreNotebook(id: String): Notebook = database.withTransaction {
        touchNotebookArchive(id = id, archivedAt = null)
    }

    // -------------------------- Chapter mutations --------------------------

    override suspend fun createChapter(notebookId: String, title: String): Chapter = database.withTransaction {
        notebookDao.findById(notebookId)?.takeIf { it.deletedAt == null }
            ?: throw DriveError.NotFound
        // Position = max + 1024 so new chapters land at the end of
        // the existing list (1024-step convention used elsewhere).
        val nextPosition: Long = (chapterDao.activeForNotebookOnce(notebookId)
            .maxOfOrNull { it.position } ?: 0L) + 1024L
        val resolved = title.trim().ifEmpty { "Untitled chapter" }
        val now = IsoClock.nowIso()
        val entity = ChapterEntity(
            id         = "ch-${UUID.randomUUID().toString().take(8)}",
            notebookId = notebookId,
            title      = resolved,
            position   = nextPosition,
            createdAt  = now,
            updatedAt  = now,
            dirty      = true,
            archivedAt = null,
        )
        chapterDao.upsert(entity)
        entity.toDomainChapter()
    }

    override suspend fun archiveChapter(id: String): Chapter = database.withTransaction {
        touchChapterArchive(id = id, archivedAt = Instant.now())
    }

    override suspend fun restoreChapter(id: String): Chapter = database.withTransaction {
        touchChapterArchive(id = id, archivedAt = null)
    }

    override suspend fun renameChapter(id: String, title: String): Chapter = database.withTransaction {
        val entity = chapterDao.findById(id)?.takeIf { it.deletedAt == null }
            ?: throw DriveError.NotFound
        val resolved = title.trim().ifEmpty { "Untitled chapter" }
        val updated = entity.copy(
            title     = resolved,
            updatedAt = IsoClock.nowIso(),
            dirty     = true,
        )
        chapterDao.upsert(updated)
        updated.toDomainChapter()
    }

    // -------------------------- Internal helpers --------------------------

    /** Toggle a page's `archivedAt`. nil → restore. Idempotent. */
    private suspend fun touchPageArchive(id: String, archivedAt: Instant?): Page {
        val entity = pageDao.findById(id)?.takeIf { it.deletedAt == null }
            ?: throw DriveError.NotFound
        val target = archivedAt?.let(::formatInstant)
        val updated = if (entity.archivedAt == target) {
            entity
        } else {
            val refreshed = entity.copy(
                archivedAt = target,
                updatedAt  = IsoClock.nowIso(),
                dirty      = true,
            )
            pageDao.upsert(refreshed)
            refreshed
        }
        val parentNotebookId = notebookId(forChapter = updated.chapterId)
        return updated.toDomainPage(notebookId = parentNotebookId)
    }

    private suspend fun touchNotebookArchive(id: String, archivedAt: Instant?): Notebook {
        val entity = notebookDao.findById(id)?.takeIf { it.deletedAt == null }
            ?: throw DriveError.NotFound
        val target = archivedAt?.let(::formatInstant)
        val updated = if (entity.archivedAt == target) {
            entity
        } else {
            val refreshed = entity.copy(
                archivedAt = target,
                updatedAt  = IsoClock.nowIso(),
                dirty      = true,
            )
            notebookDao.upsert(refreshed)
            refreshed
        }
        return updated.toDomainNotebook(
            chapterCount = 0,
            pageCount    = 0,
            shelfName    = shelfNamesById(listOf(updated.shelfId))[updated.shelfId],
        )
    }

    private suspend fun touchChapterArchive(id: String, archivedAt: Instant?): Chapter {
        val entity = chapterDao.findById(id)?.takeIf { it.deletedAt == null }
            ?: throw DriveError.NotFound
        val target = archivedAt?.let(::formatInstant)
        val updated = if (entity.archivedAt == target) {
            entity
        } else {
            val refreshed = entity.copy(
                archivedAt = target,
                updatedAt  = IsoClock.nowIso(),
                dirty      = true,
            )
            chapterDao.upsert(refreshed)
            refreshed
        }
        return updated.toDomainChapter()
    }

    /** Resolve the parent notebook id for a chapter id. */
    private suspend fun notebookId(forChapter: String): String {
        val chapter = chapterDao.findById(forChapter) ?: throw DriveError.NotFound
        return chapter.notebookId
    }

    /** Active chapter counts per notebook id. Empty input → empty map. */
    private suspend fun chapterCountsByNotebook(notebookIds: List<String>): Map<String, Int> {
        if (notebookIds.isEmpty()) return emptyMap()
        // Walk active rows once — Room doesn't ship a "group by"
        // suspending DAO query here, so we do the aggregation
        // client-side. Cheap for the cardinality we expect (tens
        // of notebooks, hundreds of chapters at most).
        val targets = notebookIds.toSet()
        val out = mutableMapOf<String, Int>()
        for (nbId in targets) {
            val rows = chapterDao.activeForNotebookOnce(nbId).filter { it.deletedAt == null }
            out[nbId] = rows.size
        }
        return out
    }

    /** Active page counts per notebook id. Walks chapters then
     *  pages-per-chapter; same client-side aggregation as the
     *  chapter counts. */
    private suspend fun pageCountsByNotebook(notebookIds: List<String>): Map<String, Int> {
        if (notebookIds.isEmpty()) return emptyMap()
        val out = mutableMapOf<String, Int>()
        for (nbId in notebookIds) {
            val chapters = chapterDao.activeForNotebookOnce(nbId).filter { it.deletedAt == null }
            var pageCount = 0
            for (ch in chapters) {
                pageCount += pageDao.findByChapterActive(ch.id)
                    .count { it.deletedAt == null }
            }
            out[nbId] = pageCount
        }
        return out
    }

    /** Resolve a batch of shelf ids → display names. Missing /
     *  deleted shelves resolve as null so an orphaned FK doesn't
     *  crash the load. */
    private suspend fun shelfNamesById(shelfIds: List<String>): Map<String, String> {
        if (shelfIds.isEmpty()) return emptyMap()
        return shelfIds.toSet().mapNotNull { id ->
            shelfDao.findById(id)?.takeIf { it.deletedAt == null }?.let { id to it.name }
        }.toMap()
    }
}
