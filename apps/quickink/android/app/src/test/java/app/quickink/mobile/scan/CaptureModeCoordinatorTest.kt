/*
 * CaptureModeCoordinatorTest.kt
 *
 * Unit coverage for [CaptureModeCoordinator] — the source of
 * truth for the Document/Business-Card pill toggle. Verifies:
 *
 *   - construction with a starting mode
 *   - select() persists + fires analytics on change
 *   - select() to the same mode no-ops (no second persist call,
 *     no duplicate analytics)
 *   - analytics fires _mode_switched BEFORE _mode_selected so
 *     downstream aggregators see causal ordering
 */

package app.quickink.mobile.scan

import app.quickink.mobile.features.scan.CaptureMode
import app.quickink.mobile.features.scan.CaptureModeCoordinator
import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureModeCoordinatorTest {

    private class RecordingAnalytics : CaptureModeCoordinator.Analytics {
        val events = mutableListOf<String>()
        override fun modeSelected(mode: CaptureMode) {
            events.add("selected:${mode.analyticsKey}")
        }
        override fun modeSwitched(from: CaptureMode, to: CaptureMode) {
            events.add("switched:${from.analyticsKey}->${to.analyticsKey}")
        }
    }

    @Test fun `initial mode is reflected immediately`() {
        val coordinator = CaptureModeCoordinator(
            initial   = CaptureMode.BusinessCard,
            persist   = {},
            analytics = RecordingAnalytics(),
        )
        assertEquals(CaptureMode.BusinessCard, coordinator.mode)
    }

    @Test fun `select to new mode persists, fires both analytics events in order`() {
        val persisted = mutableListOf<CaptureMode>()
        val analytics = RecordingAnalytics()
        val coordinator = CaptureModeCoordinator(
            initial   = CaptureMode.Document,
            persist   = { persisted.add(it) },
            analytics = analytics,
        )
        coordinator.select(CaptureMode.BusinessCard)

        assertEquals(CaptureMode.BusinessCard, coordinator.mode)
        assertEquals(listOf(CaptureMode.BusinessCard), persisted)
        // _switched should precede _selected so streaming
        // aggregators see the causal pair in order.
        assertEquals(
            listOf(
                "switched:document->business_card",
                "selected:business_card",
            ),
            analytics.events,
        )
    }

    @Test fun `select to same mode is a no-op`() {
        val persisted = mutableListOf<CaptureMode>()
        val analytics = RecordingAnalytics()
        val coordinator = CaptureModeCoordinator(
            initial   = CaptureMode.Document,
            persist   = { persisted.add(it) },
            analytics = analytics,
        )
        coordinator.select(CaptureMode.Document)

        assertEquals(CaptureMode.Document, coordinator.mode)
        // No persist write, no analytics fan-out.
        assertEquals(emptyList<CaptureMode>(), persisted)
        assertEquals(emptyList<String>(), analytics.events)
    }

    @Test fun `round-trip select Document, BusinessCard, Document keeps state coherent`() {
        val persisted = mutableListOf<CaptureMode>()
        val analytics = RecordingAnalytics()
        val coordinator = CaptureModeCoordinator(
            initial   = CaptureMode.Document,
            persist   = { persisted.add(it) },
            analytics = analytics,
        )
        coordinator.select(CaptureMode.BusinessCard)
        coordinator.select(CaptureMode.Document)
        assertEquals(CaptureMode.Document, coordinator.mode)
        assertEquals(
            listOf(CaptureMode.BusinessCard, CaptureMode.Document),
            persisted,
        )
        assertEquals(
            listOf(
                "switched:document->business_card",
                "selected:business_card",
                "switched:business_card->document",
                "selected:document",
            ),
            analytics.events,
        )
    }
}
