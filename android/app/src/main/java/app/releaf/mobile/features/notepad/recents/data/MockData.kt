package app.releaf.mobile.features.notepad.recents.data

import app.releaf.mobile.features.notepad.recents.model.CaptureType
import app.releaf.mobile.features.notepad.recents.model.RecentsDay
import app.releaf.mobile.features.notepad.recents.model.RecentsDayStats
import app.releaf.mobile.features.notepad.recents.model.RecentsPage
import app.releaf.mobile.features.notepad.recents.model.PageSource
import app.releaf.mobile.features.notepad.recents.model.Tag
import app.releaf.mobile.features.notepad.recents.model.RecentsTotals
import app.releaf.mobile.features.notepad.recents.model.RecentsWeekDay
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Sample data for the Recents screen. The "today" date is fixed at
 * 2026-04-26 so the layout stays predictable while iterating on visuals.
 *
 * Every page is attachment-backed (PHOTO or VOICE) or a notes-only
 * page with `type = null` — the JOURNAL and MOOD page flavours have
 * been retired along with the corresponding [CaptureType] cases.
 */
object MockData {

    val TODAY: LocalDate = LocalDate.of(2026, 4, 26)

    private fun at(date: LocalDate, h: Int, m: Int): LocalDateTime =
        LocalDateTime.of(date, LocalTime.of(h, m))

    // --- Today: jatamansi ---------------------------------------------------

    private val todayDayId = TODAY.toString()

    private val todayPages: List<RecentsPage> = listOf(
        RecentsPage(
            id = "p-${todayDayId}-1",
            dayId = todayDayId,
            type = CaptureType.PHOTO,
            source = PageSource.CAMERA,
            createdAt = at(TODAY, 7, 32),
            title = "morning light on the sill",
            description = "Caught the first sun cutting through the jatamansi leaves.",
            tags = listOf(Tag.HOME, Tag.PERSONAL),
            mediaUri = "mock://today/photo-sill",
        ),
        RecentsPage(
            id = "p-${todayDayId}-2",
            dayId = todayDayId,
            type = CaptureType.PHOTO,
            source = PageSource.SCAN,
            createdAt = at(TODAY, 13, 4),
            title = "tea recipe — handwritten",
            description = "Scanned grandma's jatamansi-and-cardamom tea card.",
            tags = listOf(Tag.RECIPES, Tag.HOME),
            mediaUri = "mock://today/scan-recipe",
        ),
        RecentsPage(
            id = "p-${todayDayId}-3",
            dayId = todayDayId,
            type = CaptureType.VOICE,
            source = PageSource.NATIVE,
            createdAt = at(TODAY, 20, 15),
            title = "nightcap thought",
            description = "Forty-second voice memo about tomorrow's brief.",
            tags = listOf(Tag.WORK),
            durationSec = 42,
        ),
    )

    val today: RecentsDay = RecentsDay(
        id = todayDayId,
        date = TODAY,
        theme = "jatamansi",
        pages = todayPages,
    )

    // --- Earlier in April --------------------------------------------------

    private fun simplePage(
        dayId: String,
        idx: Int,
        type: CaptureType?,
        source: PageSource,
        time: LocalTime,
        title: String,
        description: String,
        tags: List<Tag>,
        mediaUri: String? = null,
    ) = RecentsPage(
        id = "p-$dayId-$idx",
        dayId = dayId,
        type = type,
        source = source,
        createdAt = LocalDateTime.of(LocalDate.parse(dayId), time),
        title = title,
        description = description,
        tags = tags,
        mediaUri = mediaUri,
    )

    private fun apr25(): RecentsDay {
        val date = LocalDate.of(2026, 4, 25)
        val id = date.toString()
        return RecentsDay(
            id = id,
            date = date,
            theme = "daily capture",
            pages = listOf(
                simplePage(id, 1, CaptureType.PHOTO, PageSource.CAMERA, LocalTime.of(8, 5),
                    "balcony basil", "Re-potted into the bigger terracotta.", listOf(Tag.HOME),
                    "mock://apr25/basil"),
                simplePage(id, 2, CaptureType.PHOTO, PageSource.LIBRARY, LocalTime.of(12, 12),
                    "import: market", "Imported a phone-camera shot from the farmer's market.",
                    listOf(Tag.RECIPES), "mock://apr25/market"),
                simplePage(id, 3, CaptureType.VOICE, PageSource.NATIVE, LocalTime.of(15, 45),
                    "voice — pacing", "Two-minute walk-and-talk.", listOf(Tag.PERSONAL)),
            ),
        )
    }

    private fun apr24(): RecentsDay {
        val date = LocalDate.of(2026, 4, 24)
        val id = date.toString()
        return RecentsDay(
            id = id,
            date = date,
            theme = "hello",
            pages = listOf(
                simplePage(id, 1, CaptureType.PHOTO, PageSource.CAMERA, LocalTime.of(18, 10),
                    "low sun", "Long shadows on the courtyard.", listOf(Tag.HOME),
                    "mock://apr24/courtyard"),
            ),
        )
    }

    private fun apr23(): RecentsDay {
        val date = LocalDate.of(2026, 4, 23)
        val id = date.toString()
        return RecentsDay(
            id = id,
            date = date,
            theme = "xoriant games day",
            pages = listOf(
                simplePage(id, 1, CaptureType.PHOTO, PageSource.CAMERA, LocalTime.of(11, 20),
                    "team photo", "Whole crew on the lawn.", listOf(Tag.WORK),
                    "mock://apr23/team"),
            ),
        )
    }

    private fun apr22(): RecentsDay {
        val date = LocalDate.of(2026, 4, 22)
        val id = date.toString()
        return RecentsDay(
            id = id,
            date = date,
            theme = "twak",
            pages = listOf(
                simplePage(id, 1, CaptureType.VOICE, PageSource.NATIVE, LocalTime.of(19, 5),
                    "voice — twak", "Trying to define the feeling out loud.",
                    listOf(Tag.PERSONAL)),
            ),
        )
    }

    private fun emptyDay(date: LocalDate): RecentsDay = RecentsDay(
        id = date.toString(),
        date = date,
        theme = "",
        pages = emptyList(),
    )

    val earlier: List<RecentsDay> = listOf(
        apr25(),
        apr24(),
        apr23(),
        apr22(),
        emptyDay(LocalDate.of(2026, 4, 21)),
        emptyDay(LocalDate.of(2026, 4, 20)),
    )

    // --- Week pulse: 7 days ending today, counts [1,2,1,4,5,3,5] ----------

    val weekPulse: List<RecentsWeekDay> = run {
        val counts = listOf(1, 2, 1, 4, 5, 3, 5)
        // 7 dates ending at TODAY -> indices 0..6 are oldest..today.
        (0 until 7).map { i ->
            val d = TODAY.minusDays((6 - i).toLong())
            RecentsWeekDay(
                date = d,
                pageCount = counts[i],
                isToday = d == TODAY,
            )
        }
    }

    val totals: RecentsTotals = RecentsTotals(
        dayStreak = 12,
        bloomedThisMonth = 22,
        daysInMonth = 30,
        topTheme = Tag.PERSONAL,
    )

    val dayStats: RecentsDayStats = RecentsDayStats(
        today = today,
        weekPulse = weekPulse,
        earlier = earlier,
        totals = totals,
    )
}
