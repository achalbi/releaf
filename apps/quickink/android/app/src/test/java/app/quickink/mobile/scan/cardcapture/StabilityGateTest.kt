/*
 * StabilityGateTest.kt
 *
 * Unit coverage for the three-frame stability gate. Drives the
 * clock manually so debounce + streak elapsed are testable
 * without sleeping. The detector itself isn't exercised — the
 * gate's input is "a quad" and the test feeds quads directly.
 */

package app.quickink.mobile.scan.cardcapture

import app.quickink.mobile.features.scan.cardcapture.DetectedQuad
import app.quickink.mobile.features.scan.cardcapture.Point2f
import app.quickink.mobile.features.scan.cardcapture.StabilityGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StabilityGateTest {

    private fun quad(offset: Float = 0f) = DetectedQuad(
        tl = Point2f(10f + offset, 10f + offset),
        tr = Point2f(110f + offset, 10f + offset),
        br = Point2f(110f + offset, 70f + offset),
        bl = Point2f(10f + offset,  70f + offset),
    )

    @Test fun `gate fires on third consecutive within-threshold vote`() {
        var now = 0L
        val gate = StabilityGate(perCornerDriftThresholdPx = 5f, clock = { now })
        assertFalse(gate.vote(quad(0f)))   ; now += 30
        assertFalse(gate.vote(quad(1f)))   ; now += 30
        assertTrue (gate.vote(quad(2f)))   // 3rd vote → fires
    }

    @Test fun `gate rejects votes that drift past threshold`() {
        var now = 0L
        val gate = StabilityGate(perCornerDriftThresholdPx = 5f, clock = { now })
        gate.vote(quad(0f))  ; now += 30
        gate.vote(quad(2f))  ; now += 30
        // 10px drift > 5px threshold — 3rd vote should NOT fire.
        assertFalse(gate.vote(quad(20f)))
    }

    @Test fun `gate debounces after firing`() {
        var now = 0L
        val gate = StabilityGate(perCornerDriftThresholdPx = 5f, debounceMs = 1000L, clock = { now })
        gate.vote(quad(0f)); now += 30
        gate.vote(quad(1f)); now += 30
        assertTrue(gate.vote(quad(2f))) // fires
        // Subsequent votes inside the debounce window should not fire.
        now += 500
        assertFalse(gate.vote(quad(3f)))
        assertFalse(gate.vote(quad(4f)))
        assertFalse(gate.vote(quad(5f)))
        // After the debounce window we can re-accumulate.
        now += 1000  // total 1500ms since fire — beyond 1000ms debounce
        assertFalse(gate.vote(quad(6f)))
        now += 30
        assertFalse(gate.vote(quad(7f)))
        now += 30
        assertTrue(gate.vote(quad(8f)))
    }

    @Test fun `reset clears the buffered streak`() {
        var now = 0L
        val gate = StabilityGate(perCornerDriftThresholdPx = 5f, clock = { now })
        gate.vote(quad(0f)); now += 30
        gate.vote(quad(1f)); now += 30
        gate.reset()
        // After reset, a third vote alone shouldn't fire — we
        // need three new votes from scratch.
        assertFalse(gate.vote(quad(2f)))
        now += 30
        assertFalse(gate.vote(quad(3f)))
        now += 30
        assertTrue (gate.vote(quad(4f)))
    }

    @Test fun `streak elapsed tracks time since first vote in streak`() {
        var now = 1000L
        val gate = StabilityGate(perCornerDriftThresholdPx = 5f, clock = { now })
        gate.vote(quad(0f))
        now = 1080L
        assertEquals(80L, gate.streakElapsedMs())
    }
}
