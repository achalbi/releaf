/*
 * StoryDayMarkersTest.kt
 *
 * Coverage for `StoryDayMarkers.derive` — the helper that walks a
 * story's items and emits a marker each time the effective date OR
 * time-of-day bucket changes from the previous item. The derivation
 * is shared verbatim between iOS + Android; this file mirrors
 * `StoryDayMarkersTests.swift`.
 */

package app.quickink.mobile.features.stories

import app.quickink.mobile.data.storyitem.StoryItemEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.TimeZone

class StoryDayMarkersTest {

    // Pin the process's timezone to UTC so the engine's local-hour
    // computation produces deterministic output regardless of the
    // dev machine's locale. Production runs on the user's actual
    // timezone — this is purely a test-determinism affordance.
    @Before fun pinTimezoneToUtc() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    // ── Fixtures ─────────────────────────────────────────────────

    private fun item(
        id: String,
        occurredAt: String? = null,
        createdAt: String = isoUtc("2026-05-01T09:00:00"),
    ) = StoryItemEntity(
        id          = id,
        storyId     = "story-1",
        position    = 1,
        kind        = StoryItemEntity.Kind.TEXT_BLOCK.raw,
        refId       = null,
        text        = null,
        caption     = null,
        occurredAt  = occurredAt,
        layout      = StoryItemEntity.Layout.FULL.raw,
        createdAt   = createdAt,
        updatedAt   = createdAt,
        dirty       = true,
        deletedAt   = null,
    )

    /** Build an ISO-8601 UTC timestamp like `2026-05-04T18:00:00.000Z`. */
    private fun isoUtc(ymdHms: String): String = "$ymdHms.000Z"

    // ── Basic shape ──────────────────────────────────────────────

    @Test fun `empty items yields no markers`() {
        assertEquals(emptyList<StoryDayMarker>(), StoryDayMarkers.derive(emptyList()))
    }

    @Test fun `single item yields one opening marker`() {
        val items = listOf(item(id = "a", createdAt = isoUtc("2026-05-04T18:00:00")))
        val markers = StoryDayMarkers.derive(items)
        assertEquals(1, markers.size)
        assertEquals("a", markers[0].precedingItemId)
        assertEquals("— MAY 4 · EVENING —", markers[0].label)
    }

    // ── Same vs different bucket ─────────────────────────────────

    @Test fun `same day and bucket collapses to one marker`() {
        val items = listOf(
            item(id = "a", createdAt = isoUtc("2026-05-04T18:00:00")), // EVENING
            item(id = "b", createdAt = isoUtc("2026-05-04T19:30:00")), // EVENING
        )
        val markers = StoryDayMarkers.derive(items)
        assertEquals(1, markers.size)
        assertEquals("a", markers[0].precedingItemId)
    }

    @Test fun `same day different bucket emits two markers`() {
        val items = listOf(
            item(id = "a", createdAt = isoUtc("2026-05-04T18:00:00")), // EVENING
            item(id = "b", createdAt = isoUtc("2026-05-04T22:00:00")), // NIGHT
        )
        val markers = StoryDayMarkers.derive(items)
        assertEquals(2, markers.size)
        assertEquals("— MAY 4 · EVENING —", markers[0].label)
        assertEquals("— MAY 4 · NIGHT —",   markers[1].label)
        assertEquals("a", markers[0].precedingItemId)
        assertEquals("b", markers[1].precedingItemId)
    }

    @Test fun `different days same bucket emits two markers`() {
        val items = listOf(
            item(id = "a", createdAt = isoUtc("2026-05-04T18:00:00")), // EVENING May 4
            item(id = "b", createdAt = isoUtc("2026-05-05T18:00:00")), // EVENING May 5
        )
        val markers = StoryDayMarkers.derive(items)
        assertEquals(2, markers.size)
        assertEquals("— MAY 4 · EVENING —", markers[0].label)
        assertEquals("— MAY 5 · EVENING —", markers[1].label)
    }

    // ── Bucket boundaries ────────────────────────────────────────

    @Test fun `hour boundaries match spec`() {
        // Spec §3: MORNING 05–10, AFTERNOON 11–16, EVENING 17–20, NIGHT 21–04.
        val cases = listOf(
            5  to "MORNING",
            10 to "MORNING",
            11 to "AFTERNOON",
            16 to "AFTERNOON",
            17 to "EVENING",
            20 to "EVENING",
            21 to "NIGHT",
            0  to "NIGHT",
            4  to "NIGHT",
        )
        for ((hour, expectedBucket) in cases) {
            val hh = "%02d".format(hour)
            val items = listOf(item(id = "a-$hour", createdAt = isoUtc("2026-05-04T$hh:00:00")))
            val markers = StoryDayMarkers.derive(items)
            assertEquals("hour=$hour", 1, markers.size)
            assertTrue(
                "hour=$hour label=${markers[0].label} expected bucket=$expectedBucket",
                markers[0].label.contains(expectedBucket),
            )
        }
    }

    // ── occurredAt overrides createdAt ───────────────────────────

    @Test fun `occurredAt takes precedence over createdAt`() {
        val items = listOf(
            item(id = "a", occurredAt = isoUtc("2026-05-04T09:00:00"), createdAt = isoUtc("2026-05-04T23:00:00")),
            item(id = "b", occurredAt = isoUtc("2026-05-04T18:00:00"), createdAt = isoUtc("2026-05-04T23:30:00")),
        )
        val markers = StoryDayMarkers.derive(items)
        assertEquals(
            listOf("— MAY 4 · MORNING —", "— MAY 4 · EVENING —"),
            markers.map { it.label },
        )
    }

    // ── Bad input ────────────────────────────────────────────────

    @Test fun `unparseable timestamp is silently skipped`() {
        val items = listOf(
            item(id = "a", createdAt = isoUtc("2026-05-04T09:00:00")),
            item(id = "garbage", createdAt = "not-an-iso-string"),
            item(id = "c", createdAt = isoUtc("2026-05-04T18:00:00")),
        )
        val markers = StoryDayMarkers.derive(items)
        assertEquals(listOf("a", "c"), markers.map { it.precedingItemId })
        assertEquals(
            listOf("— MAY 4 · MORNING —", "— MAY 4 · EVENING —"),
            markers.map { it.label },
        )
    }

    // ── Months across the year ───────────────────────────────────

    @Test fun `all months render as three-letter uppercase`() {
        val cases = listOf(
            "2026-01-15T09:00:00" to "JAN",
            "2026-06-15T09:00:00" to "JUN",
            "2026-12-15T09:00:00" to "DEC",
        )
        for ((iso, expected) in cases) {
            val items = listOf(item(id = "a", createdAt = isoUtc(iso)))
            val markers = StoryDayMarkers.derive(items)
            assertEquals(1, markers.size)
            assertTrue(markers[0].label, markers[0].label.startsWith("— $expected"))
        }
    }
}
