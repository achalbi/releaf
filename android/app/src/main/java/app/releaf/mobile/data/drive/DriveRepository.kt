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
}

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

    companion object {
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
            // pg-6 — empty morning page
            Page(
                id = "pg-6", notebookId = "nb-2", chapterId = "ch-3",
                title = "Sunday", capturedOn = "Apr 19, 2026",
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
