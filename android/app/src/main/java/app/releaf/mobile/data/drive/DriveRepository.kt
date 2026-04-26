/*
 * DriveRepository.kt
 * App-facing storage interface. Maps domain objects ↔ Drive JSON.
 *
 * Every app-facing API goes through this. ViewModels never see DriveClient.
 */

package app.releaf.mobile.data.drive

import app.releaf.mobile.data.domain.Chapter
import app.releaf.mobile.data.domain.Contact
import app.releaf.mobile.data.domain.LocationPin
import app.releaf.mobile.data.domain.Note
import app.releaf.mobile.data.domain.Notebook
import app.releaf.mobile.data.domain.NotebookStatus
import app.releaf.mobile.data.domain.Page
import app.releaf.mobile.data.domain.PageCounts
import app.releaf.mobile.data.domain.PageSummary
import app.releaf.mobile.data.domain.PageTemplate
import app.releaf.mobile.data.domain.Photo
import app.releaf.mobile.data.domain.ScannedDocument
import app.releaf.mobile.data.domain.TodoItem
import app.releaf.mobile.data.domain.VoiceNote
import java.time.Instant
import kotlinx.coroutines.delay

/** Notebook + its chapters, returned as one shot from `loadNotebook`. */
data class NotebookDetail(
    val notebook: Notebook,
    val chapters: List<Chapter>,
)

interface DriveRepository {
    suspend fun listNotebooks(): List<Notebook>
    suspend fun loadNotebook(id: String): NotebookDetail
    suspend fun loadChapters(notebookId: String): List<Chapter>
    suspend fun loadPage(id: String): Page

    /** Re-parent a page under a different notebook + chapter. When
     *  [toChapterId] is null the implementation chooses the
     *  destination's first chapter; the picker uses the explicit
     *  arg when the user drills in. */
    suspend fun movePage(pageId: String, toNotebookId: String, toChapterId: String? = null)

    /** All templates available to the user — app-seeded plus
     *  user-saved. Returned in display order. */
    suspend fun listPageTemplates(): List<PageTemplate>

    /** Apply a template's pre-filled content onto an existing page.
     *  Pre-fields prepend / concat onto the page's current content;
     *  applying never deletes captures. Returns the updated page. */
    suspend fun applyTemplate(toPageId: String, templateId: String): Page

    /** Soft-delete the page by stamping `archivedAt = now`. Returns
     *  the updated page so the ViewModel can refresh without a
     *  re-fetch. Idempotent. */
    suspend fun archivePage(id: String): Page

    /** Inverse of [archivePage] — clears `archivedAt`. Idempotent. */
    suspend fun restorePage(id: String): Page

    /** Make a copy of [id] in the same chapter, with a new id and a
     *  suffixed title ("X (copy)"). Notes / todos / captures carry
     *  over by value; the duplicate has its own ids on every nested
     *  row so edits don't leak across copies. */
    suspend fun duplicatePage(id: String): Page

    /** Archived pages across every notebook. Used by the Notebook
     *  list's "Archived" overflow item to surface a flat picker.
     *  Each row carries notebook + chapter context for the
     *  breadcrumb line. */
    suspend fun listArchivedPages(): List<ArchivedPage>

    /** Rename a notebook. Returns the updated notebook so callers
     *  can refresh state without a re-fetch. */
    suspend fun renameNotebook(id: String, title: String): Notebook

    /** Create a new empty chapter under [notebookId]. Position is
     *  end-of-list. Returns the new chapter so the caller can
     *  navigate or scroll to it. */
    suspend fun createChapter(notebookId: String, title: String): Chapter

    /** Soft-delete the notebook. Idempotent. */
    suspend fun archiveNotebook(id: String): Notebook

    /** Inverse of [archiveNotebook] — clears archivedAt. Idempotent. */
    suspend fun restoreNotebook(id: String): Notebook

    /** Soft-delete the chapter. Idempotent. */
    suspend fun archiveChapter(id: String): Chapter

    /** Inverse of [archiveChapter] — clears archivedAt. Idempotent. */
    suspend fun restoreChapter(id: String): Chapter

    /** Update a chapter's title. Returns the updated chapter.
     *  Mirrors `renameChapter` on iOS so the title-only edit path
     *  has a dedicated entry point on both platforms. */
    suspend fun renameChapter(id: String, title: String): Chapter

    /** Replace a page's tags with [tags]. Order is preserved
     *  exactly; duplicates are de-duped case-insensitively at the
     *  call site, not here. */
    suspend fun setPageTags(pageId: String, tags: List<String>): Page
}

/** One entry in the cross-notebook archive picker. */
data class ArchivedPage(
    val id: String,
    val title: String,
    val notebookId: String,
    val notebookTitle: String,
    val chapterId: String,
    val chapterTitle: String,
    val archivedAt: java.time.Instant,
)

/** In-memory fake. Use for previews, tests, and the skeleton signed-in state. */
class FakeDriveRepository(
    private val notebooks: List<Notebook> = SEEDED_NOTEBOOKS,
    private val chaptersByNotebook: Map<String, List<Chapter>> = SEEDED_CHAPTERS,
    private val pagesById: Map<String, Page> = SEEDED_PAGES,
) : DriveRepository {

    override suspend fun listNotebooks(): List<Notebook> {
        delay(150)
        return notebooks
    }

    override suspend fun loadNotebook(id: String): NotebookDetail {
        delay(150)
        val nb = notebooks.firstOrNull { it.id == id } ?: throw DriveError.NotFound
        return NotebookDetail(nb, chaptersByNotebook[id].orEmpty())
    }

    override suspend fun loadChapters(notebookId: String): List<Chapter> {
        delay(100)
        return chaptersByNotebook[notebookId].orEmpty()
    }

    override suspend fun loadPage(id: String): Page {
        delay(150)
        return pagesById[id] ?: throw DriveError.NotFound
    }

    override suspend fun movePage(pageId: String, toNotebookId: String, toChapterId: String?) {
        // Stub: simulates a write round-trip but doesn't mutate the
        // seeded pages map (would require it be a `var`). Real impl
        // will update the page row's `chapter_id` to the resolved
        // chapter (explicit `toChapterId` if non-null; else the
        // destination notebook's first chapter) and rewrite the
        // manifest entry. Tracked under TODO in the ViewModel call site.
        delay(200)
        pagesById[pageId] ?: throw DriveError.NotFound
        notebooks.firstOrNull { it.id == toNotebookId } ?: throw DriveError.NotFound
        if (toChapterId != null) {
            chaptersByNotebook[toNotebookId].orEmpty()
                .firstOrNull { it.id == toChapterId }
                ?: throw DriveError.NotFound
        }
    }

    override suspend fun archivePage(id: String): Page {
        delay(150)
        val page = pagesById[id] ?: throw DriveError.NotFound
        if (page.archivedAt != null) return page
        return page.copy(updatedAt = Instant.now(), archivedAt = Instant.now())
    }

    override suspend fun restorePage(id: String): Page {
        delay(150)
        val page = pagesById[id] ?: throw DriveError.NotFound
        if (page.archivedAt == null) return page
        return page.copy(updatedAt = Instant.now(), archivedAt = null)
    }

    override suspend fun renameNotebook(id: String, title: String): Notebook {
        delay(150)
        val nb = notebooks.firstOrNull { it.id == id } ?: throw DriveError.NotFound
        return nb.copy(title = title, updatedAt = Instant.now())
    }

    override suspend fun createChapter(notebookId: String, title: String): Chapter {
        delay(150)
        notebooks.firstOrNull { it.id == notebookId } ?: throw DriveError.NotFound
        val existing = chaptersByNotebook[notebookId].orEmpty()
        val nextPosition = (existing.maxOfOrNull { it.position } ?: 0) + 1
        val resolved = title.trim().ifEmpty { "Untitled chapter" }
        return Chapter(
            id         = "ch-${java.util.UUID.randomUUID().toString().take(8)}",
            notebookId = notebookId,
            title      = resolved,
            position   = nextPosition,
            updatedAt  = Instant.now(),
        )
    }

    override suspend fun archiveNotebook(id: String): Notebook {
        delay(150)
        val nb = notebooks.firstOrNull { it.id == id } ?: throw DriveError.NotFound
        if (nb.archivedAt != null) return nb
        return nb.copy(
            archivedAt = Instant.now(),
            status     = NotebookStatus.Archived,
            updatedAt  = Instant.now(),
        )
    }

    override suspend fun archiveChapter(id: String): Chapter {
        delay(150)
        for ((_, chapters) in chaptersByNotebook) {
            val chapter = chapters.firstOrNull { it.id == id } ?: continue
            if (chapter.archivedAt != null) return chapter
            return chapter.copy(
                archivedAt = Instant.now(),
                updatedAt  = Instant.now(),
            )
        }
        throw DriveError.NotFound
    }

    override suspend fun restoreNotebook(id: String): Notebook {
        delay(150)
        val nb = notebooks.firstOrNull { it.id == id } ?: throw DriveError.NotFound
        if (nb.archivedAt == null) return nb
        return nb.copy(
            archivedAt = null,
            status     = NotebookStatus.Active,
            updatedAt  = Instant.now(),
        )
    }

    override suspend fun setPageTags(pageId: String, tags: List<String>): Page {
        delay(150)
        val page = pagesById[pageId] ?: throw DriveError.NotFound
        return page.copy(updatedAt = Instant.now(), tags = tags)
    }

    override suspend fun restoreChapter(id: String): Chapter {
        delay(150)
        for ((_, chapters) in chaptersByNotebook) {
            val chapter = chapters.firstOrNull { it.id == id } ?: continue
            if (chapter.archivedAt == null) return chapter
            return chapter.copy(archivedAt = null, updatedAt = Instant.now())
        }
        throw DriveError.NotFound
    }

    override suspend fun renameChapter(id: String, title: String): Chapter {
        delay(150)
        val resolved = title.trim().ifEmpty { "Untitled chapter" }
        for ((_, chapters) in chaptersByNotebook) {
            val chapter = chapters.firstOrNull { it.id == id } ?: continue
            return chapter.copy(title = resolved, updatedAt = Instant.now())
        }
        throw DriveError.NotFound
    }

    override suspend fun listArchivedPages(): List<ArchivedPage> {
        delay(150)
        // Build a chapterId → (notebookId, chapterTitle) lookup so
        // we can fan archived-page rows out with full breadcrumb
        // context in one pass.
        val chapterIndex: Map<String, Pair<String, String>> =
            chaptersByNotebook.flatMap { it.value }
                .associate { ch -> ch.id to (ch.notebookId to ch.title) }
        val notebookTitle: Map<String, String> =
            notebooks.associate { it.id to it.title }
        return pagesById.values
            .mapNotNull { page ->
                val archivedAt = page.archivedAt ?: return@mapNotNull null
                val (nbId, chTitle) = chapterIndex[page.chapterId] ?: (page.notebookId to "")
                ArchivedPage(
                    id            = page.id,
                    title         = page.title,
                    notebookId    = page.notebookId,
                    notebookTitle = notebookTitle[nbId].orEmpty(),
                    chapterId     = page.chapterId,
                    chapterTitle  = chTitle,
                    archivedAt    = archivedAt,
                )
            }
            .sortedByDescending { it.archivedAt }
    }

    override suspend fun duplicatePage(id: String): Page {
        delay(200)
        val page = pagesById[id] ?: throw DriveError.NotFound
        val newId = "dup-${java.util.UUID.randomUUID().toString().take(8)}"
        return page.copy(
            id         = newId,
            title      = "${page.title} (copy)",
            updatedAt  = Instant.now(),
            archivedAt = null,
            notes      = page.notes.map { it.copy(id = "$newId-n-${it.id}") },
            photos     = page.photos.map { it.copy(id = "$newId-ph-${it.id}") },
            voiceNotes = page.voiceNotes.map { it.copy(id = "$newId-v-${it.id}") },
            todoItems  = page.todoItems.map { it.copy(id = "$newId-t-${it.id}") },
            scannedDocuments = page.scannedDocuments.map { it.copy(id = "$newId-s-${it.id}") },
            contacts   = page.contacts.map { it.copy(id = "$newId-c-${it.id}") },
            locations  = page.locations.map { it.copy(id = "$newId-l-${it.id}") },
        )
    }

    override suspend fun listPageTemplates(): List<PageTemplate> {
        delay(100)
        return SEEDED_TEMPLATES
    }

    override suspend fun applyTemplate(toPageId: String, templateId: String): Page {
        // Stub: simulates a write round-trip and returns a Page with
        // the template's pre-fills concatenated onto the existing
        // content. Doesn't persist to the seeded map; real impl will
        // write through.
        delay(200)
        val page = pagesById[toPageId] ?: throw DriveError.NotFound
        val template = SEEDED_TEMPLATES.firstOrNull { it.id == templateId }
            ?: throw DriveError.NotFound
        val newNotes = template.preNotes.mapIndexed { idx, body ->
            Note(id = "tmpl-n-${template.id}-$idx", body = body)
        }
        val basePosition = (page.todoItems.maxOfOrNull { it.position } ?: -1) + 1
        val newTodos = template.preTodos.mapIndexed { idx, body ->
            TodoItem(
                id       = "tmpl-t-${template.id}-$idx",
                body     = body,
                done     = false,
                position = basePosition + idx,
            )
        }
        return page.copy(
            updatedAt  = Instant.now(),
            notes      = page.notes + newNotes,
            todoItems  = page.todoItems + newTodos,
        )
    }

    companion object {
        /** Seeded set of page templates surfaced by [listPageTemplates].
         *  Hand-curated to cover the most common shapes the variant-1
         *  "what arrived?" surface invites. Order is the display order. */
        val SEEDED_TEMPLATES: List<PageTemplate> = listOf(
            PageTemplate(
                id          = "tmpl-walk",
                title       = "Daily walk",
                description = "Three to-dos for a walk and a place to drop a thought.",
                iconKey     = "plant",
                preNotes    = listOf("What surprised me on the walk today —"),
                preTodos    = listOf(
                    "Stretch before heading out",
                    "Photograph one new thing",
                    "Stop somewhere I haven't before",
                ),
            ),
            PageTemplate(
                id          = "tmpl-recipe",
                title       = "Recipe",
                description = "Ingredients on the left, steps on the right.",
                iconKey     = "coffee",
                preNotes    = listOf(
                    "INGREDIENTS\n— \n— \n— ",
                    "METHOD\n1. \n2. \n3. ",
                    "NOTES\nWhat I'd change next time —",
                ),
            ),
            PageTemplate(
                id          = "tmpl-meeting",
                title       = "Meeting notes",
                description = "Attendees, agenda, decisions, follow-ups.",
                iconKey     = "chart",
                preNotes    = listOf(
                    "ATTENDEES\n— ",
                    "AGENDA\n1. \n2. ",
                    "DECISIONS\n— ",
                    "FOLLOW-UPS\n— ",
                ),
                preTodos    = listOf("Send minutes within 24 hours"),
            ),
            PageTemplate(
                id          = "tmpl-field",
                title       = "Field journal",
                description = "Date, weather, observations, sketch.",
                iconKey     = "sun",
                preNotes    = listOf(
                    "WEATHER\n",
                    "OBSERVATIONS\n— ",
                    "SKETCH\n(snap a photo or doodle)",
                ),
            ),
            PageTemplate(
                id          = "tmpl-morning",
                title       = "Morning pages",
                description = "Three blank pages, no rules, no editing.",
                iconKey     = "book",
                preNotes    = listOf("", "", ""),
            ),
        )

        val SEEDED_NOTEBOOKS = listOf(
            Notebook(
                id = "nb-1", title = "Plant log 2026",
                description = "A month off to walk, write, and cook again.",
                colorToken = "green", position = 0,
                updatedAt = Instant.now().minusSeconds(2 * 3600),
                chapterCount = 12, pageCount = 47,
                shelfName = "GARDEN", volumeNumber = 2,
                status = NotebookStatus.Active, iconKey = "plant",
            ),
            Notebook(
                id = "nb-2", title = "Sprint notes",
                description = "Three pages every day, in any form.",
                colorToken = "info", position = 1,
                updatedAt = Instant.now().minusSeconds(30 * 60),
                chapterCount = 6, pageCount = 28,
                shelfName = "WORK", volumeNumber = 7,
                status = NotebookStatus.Active, iconKey = "chart",
            ),
            Notebook(
                id = "nb-3", title = "Morning pages",
                description = "Things that worked at least twice.",
                colorToken = "dry", position = 2,
                updatedAt = Instant.now().minusSeconds(24 * 3600),
                chapterCount = 5, pageCount = 33,
                shelfName = "DAILY", volumeNumber = 12,
                status = NotebookStatus.Paused, iconKey = "sun",
            ),
        )

        val SEEDED_CHAPTERS: Map<String, List<Chapter>> = mapOf(
            "nb-1" to listOf(
                Chapter("ch-1", "nb-1", "Seedling diary — week 3",
                    position = 7,
                    updatedAt = Instant.now().minusSeconds(2 * 3600),
                    pages = listOf(
                        PageSummary("pg-1", "Seedlings found the light",
                            capturedOn = "Fri · Apr 24 · 2026",
                            updatedAt = Instant.now().minusSeconds(2 * 3600),
                            counts = PageCounts(photos = 2, todoItems = 3, locations = 1, voiceNotes = 1),
                            tags = listOf("tomato", "basil", "windowsill")),
                        PageSummary("pg-2", "Coffee shop, 9am",
                            capturedOn = "Apr 13, 2026",
                            counts = PageCounts(voiceNotes = 1, scannedDocuments = 1, contacts = 1)),
                    )),
                Chapter("ch-2", "nb-1", "Soil mix experiments",
                    position = 6,
                    pages = listOf(
                        PageSummary("pg-3", "Farmers market",
                            capturedOn = "Apr 19, 2026",
                            counts = PageCounts(photos = 3, contacts = 2, todoItems = 2)),
                        PageSummary("pg-4", "Evening draft",
                            capturedOn = "Apr 20, 2026",
                            counts = PageCounts(todoItems = 5)),
                    )),
                Chapter("ch-5", "nb-1", "Herbs windowsill setup", position = 5),
                Chapter("ch-6", "nb-1", "Composter first turn",   position = 4),
                Chapter("ch-7", "nb-1", "Seed order & sourcing",  position = 3),
                Chapter("ch-8", "nb-1", "Garden plan — layout",   position = 2),
            ),
            "nb-2" to listOf(
                Chapter("ch-3", "nb-2", "April", pages = listOf(
                    PageSummary("pg-5", "Monday",
                        capturedOn = "Apr 20, 2026",
                        counts = PageCounts(voiceNotes = 1)),
                    PageSummary("pg-6", "Sunday",
                        capturedOn = "Apr 19, 2026",
                        counts = PageCounts()),
                )),
            ),
            "nb-3" to listOf(
                Chapter("ch-4", "nb-3", "Bread", pages = listOf(
                    PageSummary("pg-7", "Olive focaccia",
                        capturedOn = "Apr 10, 2026",
                        counts = PageCounts(photos = 1, todoItems = 6, scannedDocuments = 1)),
                )),
            ),
        )

        val SEEDED_PAGES: Map<String, Page> = listOf(
            // pg-1 — the variant-1 flagship page
            Page(
                id = "pg-1", notebookId = "nb-1", chapterId = "ch-1",
                title = "Seedlings found the light",
                capturedOn = "Fri · Apr 24 · 2026",
                notes = listOf(
                    Note("n1", "Moved the tray two feet closer to the window this morning. " +
                        "The tomato sprouts that were leaning are already righting themselves — " +
                        "by lunch, two had opened their first true leaves."),
                    Note("n2", "The basil is still sulking. Three weeks in and only the " +
                        "heartiest seedling has broken through. I'll give the rest until Sunday " +
                        "before starting fresh."),
                    Note("n-quote", "NOTE TO SELF\nRotate the tray each morning — " +
                        "they're always reaching one way."),
                ),
                photos = listOf(
                    Photo("ph1", caption = "TRAY A",        width = 1620, height = 2160),
                    Photo("ph2", caption = "DETAIL · 11AM", width = 2160, height = 1620),
                ),
                voiceNotes = listOf(
                    VoiceNote("v1", durationMs = 47_000,
                        transcription = "A thing I keep coming back to — the distinction between " +
                            "being busy and being engaged. I want to stop optimizing for busy."),
                ),
                todoItems = listOf(
                    TodoItem("t1", "Pick up framing quote for the heron print", done = false, position = 0),
                    TodoItem("t2", "Email the ranger about the trail closure", done = false, position = 1),
                    TodoItem("t3", "Charge the camera for tomorrow",           done = true,  position = 2),
                ),
                locations = listOf(
                    LocationPin("l1", "Second bridge, Arcata Marsh",
                        latitude = 40.8541, longitude = -124.0876,
                        notes = "Bring the longer lens next time."),
                ),
                tags = listOf("tomato", "basil", "windowsill"),
            ),
            // pg-2 — coffee shop, scans + contacts
            Page(
                id = "pg-2", notebookId = "nb-1", chapterId = "ch-1",
                title = "Coffee shop, 9am", capturedOn = "Apr 13, 2026",
                notes = listOf(
                    Note("n3", "Met Priya at Old Town. She's two years into the same sabbatical " +
                        "conversation I'm having now and had a lot to say about stop-dates."),
                ),
                voiceNotes = listOf(
                    VoiceNote("v2", durationMs = 112_000,
                        transcription = "Priya's argument — if you don't set a return date, it's " +
                            "not a sabbatical, it's a resignation."),
                ),
                scannedDocuments = listOf(
                    ScannedDocument("s1", title = "Priya's reading list", pageCount = 2),
                ),
                contacts = listOf(
                    Contact("c1", name = "Priya Ramanathan",
                        phone = "+1 707 555 0144", email = "priya@priya.works",
                        notes = "Introduced by Sam. Follow up in June."),
                ),
            ),
            // pg-3 — farmers market
            Page(
                id = "pg-3", notebookId = "nb-1", chapterId = "ch-2",
                title = "Farmers market", capturedOn = "Apr 19, 2026",
                notes = listOf(
                    Note("n4", "Asparagus stand had the first local ones of the year. Picked up a " +
                        "business card from the flower farm — their ranunculus are absurd."),
                ),
                photos = listOf(
                    Photo("ph3", caption = "Asparagus, 7am"),
                    Photo("ph4", caption = "Ranunculus bouquet"),
                    Photo("ph5", caption = "Strawberries, not yet"),
                ),
                todoItems = listOf(
                    TodoItem("t4", "Order two pints of strawberries next Saturday", position = 0),
                    TodoItem("t5", "Try the duck confit from the butcher booth",   position = 1),
                ),
                contacts = listOf(
                    Contact("c2", name = "Fog Hollow Farm",
                        phone = "+1 707 555 0912", email = "hello@foghollow.farm"),
                    Contact("c3", name = "Ed, the butcher",
                        notes = "Saturdays only, cash preferred."),
                ),
            ),
            // pg-4 — mostly just todos
            Page(
                id = "pg-4", notebookId = "nb-1", chapterId = "ch-2",
                title = "Evening draft", capturedOn = "Apr 20, 2026",
                todoItems = listOf(
                    TodoItem("t6", "Outline chapter two", done = true,  position = 0),
                    TodoItem("t7", "Re-read last page of chapter one",  position = 1),
                    TodoItem("t8", "Find that Annie Dillard quote",     position = 2),
                    TodoItem("t9", "Cut the second paragraph",          position = 3),
                    TodoItem("t10", "Print draft for tomorrow's walk",  position = 4),
                ),
            ),
            // pg-5 — simple voice-note morning page
            Page(
                id = "pg-5", notebookId = "nb-2", chapterId = "ch-3",
                title = "Monday", capturedOn = "Apr 20, 2026",
                voiceNotes = listOf(
                    VoiceNote("v3", durationMs = 198_000,
                        transcription = "Three pages, but spoken. One — the sabbatical is three " +
                            "weeks in and I haven't opened a single work tab. Two — I keep waiting " +
                            "for a breakthrough and I should stop. Three — the point was rest."),
                ),
            ),
            // pg-6 — empty morning page; pre-seeded as archived so
            // the cross-notebook archive picker has at least one row
            // to render against the FakeDriveRepository state.
            Page(
                id = "pg-6", notebookId = "nb-2", chapterId = "ch-3",
                title = "Sunday", capturedOn = "Apr 19, 2026",
                archivedAt = Instant.now().minusSeconds(3 * 24 * 3600),
            ),
            // pg-7 — a recipe
            Page(
                id = "pg-7", notebookId = "nb-3", chapterId = "ch-4",
                title = "Olive focaccia", capturedOn = "Apr 10, 2026",
                notes = listOf(
                    Note("n5", "500g flour, 400g water, 10g salt, 5g dry yeast, 50g olive oil. " +
                        "Mix, rest 20 min, three stretch-and-folds, cold ferment 18-24 hours. " +
                        "Dimple with oil and salt. 230°C convection, 22 min."),
                ),
                photos = listOf(Photo("ph6", caption = "Final loaf, take three")),
                todoItems = listOf(
                    TodoItem("t11", "Buy Castelvetrano olives",         done = true,  position = 0),
                    TodoItem("t12", "Weigh flour to 500g exactly",      done = true,  position = 1),
                    TodoItem("t13", "Do three stretch-and-folds, 30 min apart", position = 2),
                    TodoItem("t14", "Cold ferment overnight",           position = 3),
                    TodoItem("t15", "Dimple with oil, flaky salt",      position = 4),
                    TodoItem("t16", "Bake 230°C, 22 min",               position = 5),
                ),
                scannedDocuments = listOf(
                    ScannedDocument("s2", title = "Original scribbled recipe", pageCount = 1),
                ),
            ),
        ).associateBy { it.id }
    }
}
