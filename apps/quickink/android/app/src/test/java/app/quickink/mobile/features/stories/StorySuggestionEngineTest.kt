/*
 * StorySuggestionEngineTest.kt
 *
 * Coverage for `StorySuggestionEngine.compute(captures, dismissed)` —
 * the pure-function path the DAO-backed overload delegates to. Tests
 * the §3–§7 rules of `shared/algorithms/story-suggestions.md`.
 * Mirror of `StorySuggestionEngineTests.swift`.
 */

package app.quickink.mobile.features.stories

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.OffsetDateTime

class StorySuggestionEngineTest {

    // ── Fixtures ─────────────────────────────────────────────────

    private fun point(
        id: String,
        date: String,
        source: String = "scan",
        locality: String? = null,
    ) = StorySuggestionEngine.CapturePoint(
        id        = id,
        timestamp = OffsetDateTime.parse(date),
        source    = source,
        locality  = locality,
    )

    private fun iso(ymdHms: String): String = "$ymdHms.000Z"

    // ── Below threshold ──────────────────────────────────────────

    @Test fun `empty captures yields no suggestion`() {
        assertNull(StorySuggestionEngine.compute(emptyList(), emptySet()))
    }

    @Test fun `fewer than four captures yields no suggestion`() {
        val captures = (0 until 3).map { idx ->
            val hh = "%02d".format(9 + idx)
            point(id = "c$idx", date = iso("2026-05-04T$hh:00:00"))
        }
        assertNull(StorySuggestionEngine.compute(captures, emptySet()))
    }

    // ── Single cluster qualifies ─────────────────────────────────

    @Test fun `four tight captures yield one suggestion`() {
        val captures = (0 until 4).map { idx ->
            val hh = "%02d".format(10 + idx)
            point(id = "c$idx", date = iso("2026-05-04T$hh:30:00"))
        }
        val s = StorySuggestionEngine.compute(captures, emptySet())
        assertNotNull(s)
        assertEquals(4, s!!.candidateRefs.size)
        assertEquals(listOf("c0", "c1", "c2", "c3"), s.candidateRefs)
        assertTrue(s.reason, s.reason.contains("May 4"))
    }

    // ── Cut rule ─────────────────────────────────────────────────

    @Test fun `gap over 18 hours and prior cluster has three or more items cuts`() {
        val day1 = (10..13).map { hr ->
            val hh = "%02d".format(hr)
            point(id = "a$hr", date = iso("2026-05-04T$hh:00:00"))
        }
        val day2 = (10..13).map { hr ->
            val hh = "%02d".format(hr)
            point(id = "b$hr", date = iso("2026-05-05T$hh:00:00"))
        }
        val s = StorySuggestionEngine.compute(day1 + day2, emptySet())
        assertNotNull(s)
        // Recency tiebreak — both clusters have same size + same
        // distinct-source diversity, prefer the more recent one.
        assertEquals(day2.map { it.id }, s!!.candidateRefs)
    }

    @Test fun `cut does not apply when prior cluster has fewer than three items`() {
        // 2 items, then a 30h gap, then 4 more. "previous cluster
        // has ≥ 3 items" guard means we DON'T cut after the 2nd
        // item, so all 6 land in one cluster.
        val head = listOf(
            point(id = "a1", date = iso("2026-05-04T09:00:00")),
            point(id = "a2", date = iso("2026-05-04T10:00:00")),
        )
        val tail = (10..13).map { hr ->
            val hh = "%02d".format(hr)
            point(id = "b$hr", date = iso("2026-05-05T$hh:00:00"))
        }
        val s = StorySuggestionEngine.compute(head + tail, emptySet())
        assertNotNull(s)
        assertEquals(6, s!!.candidateRefs.size)
    }

    // ── Scoring ──────────────────────────────────────────────────

    @Test fun `mixed sources score higher than single source of same size`() {
        val clusterA = (0 until 4).map { hr ->
            val hh = "%02d".format(hr + 1)
            point(id = "a$hr", date = iso("2026-05-04T$hh:00:00"), source = "scan")
        }
        val clusterB = (0 until 4).map { hr ->
            val hh = "%02d".format(hr + 1)
            point(
                id     = "b$hr",
                date   = iso("2026-05-06T$hh:00:00"),
                source = if (hr % 2 == 0) "scan" else "import",
            )
        }
        val s = StorySuggestionEngine.compute(clusterA + clusterB, emptySet())
        assertEquals("b0", s?.candidateRefs?.firstOrNull())
    }

    // ── Reason format ────────────────────────────────────────────

    @Test fun `reason string includes scan and photo counts`() {
        val captures = listOf(
            point(id = "s1", date = iso("2026-05-04T09:00:00"), source = "scan"),
            point(id = "s2", date = iso("2026-05-04T10:00:00"), source = "scan"),
            point(id = "s3", date = iso("2026-05-04T11:00:00"), source = "scan"),
            point(id = "p1", date = iso("2026-05-04T12:00:00"), source = "import"),
        )
        val s = StorySuggestionEngine.compute(captures, emptySet())
        assertNotNull(s)
        val reason = s!!.reason
        assertTrue(reason, reason.contains("3 scans"))
        assertTrue(reason, reason.contains("1 photo"))
        assertTrue(reason, reason.contains("May 4"))
    }

    @Test fun `reason omits zero side when only one source present`() {
        val captures = (0 until 5).map { hr ->
            val hh = "%02d".format(hr + 1)
            point(id = "s$hr", date = iso("2026-05-04T$hh:00:00"), source = "scan")
        }
        val s = StorySuggestionEngine.compute(captures, emptySet())
        assertNotNull(s)
        val reason = s!!.reason
        assertTrue(reason, reason.contains("5 scans"))
        assertFalse(reason, reason.contains("photo"))
    }

    // ── Determinism + dismissal ──────────────────────────────────

    @Test fun `stable id across runs`() {
        val captures = (0 until 4).map { hr ->
            val hh = "%02d".format(hr + 9)
            point(id = "c$hr", date = iso("2026-05-04T$hh:00:00"))
        }
        val first = StorySuggestionEngine.compute(captures, emptySet())
        val again = StorySuggestionEngine.compute(captures, emptySet())
        assertNotNull(first)
        assertEquals(first!!.id, again!!.id)
    }

    @Test fun `dismissed suggestion returns next best`() {
        val day1 = (10..13).map { hr ->
            val hh = "%02d".format(hr)
            point(id = "a$hr", date = iso("2026-05-04T$hh:00:00"))
        }
        val day2 = (10..13).map { hr ->
            val hh = "%02d".format(hr)
            point(id = "b$hr", date = iso("2026-05-08T$hh:00:00"))
        }
        val first = StorySuggestionEngine.compute(day1 + day2, emptySet())
        assertNotNull(first)
        val second = StorySuggestionEngine.compute(day1 + day2, setOf(first!!.id))
        assertNotNull(second)
        assertNotEquals(first.id, second!!.id)
    }

    @Test fun `all suggestions dismissed returns null`() {
        val captures = (0 until 4).map { hr ->
            val hh = "%02d".format(hr + 9)
            point(id = "c$hr", date = iso("2026-05-04T$hh:00:00"))
        }
        val first = StorySuggestionEngine.compute(captures, emptySet())
        assertNotNull(first)
        assertNull(StorySuggestionEngine.compute(captures, setOf(first!!.id)))
    }
}
