/*
 * StabilityGate.kt
 *
 * Three-frame ring buffer that gates auto-capture in
 * BusinessCard mode. A frame "votes" by being pushed via
 * [vote]; when three consecutive votes land within
 * [perCornerDriftThresholdPx] of each other (per-corner
 * Euclidean drift), [vote] returns true exactly once and the
 * gate enters a debounce window of [debounceMs] during which
 * further votes are dropped.
 *
 * Pure logic, no Android imports — unit-testable from JUnit.
 * The detector populates votes from a successful [DetectionResult.Valid];
 * partial / none detections call [reset] so a wobbly intermediate
 * frame doesn't keep an old quad "stable" against itself.
 */

package app.quickink.mobile.features.scan.cardcapture

class StabilityGate(
    private val perCornerDriftThresholdPx: Float = 5f,
    private val debounceMs: Long = 1500L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val buffer = arrayOfNulls<DetectedQuad>(3)
    private var bufferSize = 0
    private var debouncedUntil = 0L
    /**
     * When did the current streak of valid frames start? Used by
     * the analytics caller to log `time_to_lock_ms`. -1 means
     * "no streak in progress".
     */
    var streakStartMs: Long = -1L
        private set

    /**
     * Push a fresh detected quad. Returns `true` when the gate
     * has just fired (three stable frames AND outside the
     * debounce window). Returns `false` otherwise.
     */
    fun vote(quad: DetectedQuad): Boolean {
        val now = clock()
        if (now < debouncedUntil) return false
        // Append to the ring (sliding window).
        if (bufferSize < 3) {
            buffer[bufferSize++] = quad
            if (bufferSize == 1) streakStartMs = now
        } else {
            buffer[0] = buffer[1]
            buffer[1] = buffer[2]
            buffer[2] = quad
        }
        if (bufferSize < 3) return false
        // Compare pairwise — every adjacent pair must satisfy the
        // drift threshold. We use 0↔1 and 1↔2 (chain), not 0↔2
        // directly, because monotonic drift is acceptable as long
        // as it's slow enough to be "user-holding-steady".
        val a = buffer[0]!!; val b = buffer[1]!!; val c = buffer[2]!!
        if (!cornersWithin(a, b, perCornerDriftThresholdPx)) return false
        if (!cornersWithin(b, c, perCornerDriftThresholdPx)) return false
        // Fire. Debounce subsequent votes for [debounceMs].
        debouncedUntil = now + debounceMs
        bufferSize = 0
        return true
    }

    /**
     * Drop the buffered streak — typically called when the
     * detector returns [DetectionResult.None] or
     * [DetectionResult.Partial]. Keeps debounce intact so a
     * just-fired capture doesn't immediately rearm on the next
     * frame.
     */
    fun reset() {
        bufferSize = 0
        streakStartMs = -1L
    }

    /**
     * Elapsed ms since the current streak started, or 0 when no
     * streak is in progress. Driven by the same clock as [vote].
     */
    fun streakElapsedMs(): Long {
        if (streakStartMs < 0) return 0L
        return (clock() - streakStartMs).coerceAtLeast(0L)
    }

    /**
     * Whether the last [vote] fired; used by the surface to
     * blank the overlay during the debounce window so the
     * "Hold steady" hint doesn't flash back on between capture
     * and shutter animation. Cheap probe; no clock read at
     * call site beyond the comparison.
     */
    fun isInDebounce(): Boolean = clock() < debouncedUntil

    private fun cornersWithin(a: DetectedQuad, b: DetectedQuad, threshold: Float): Boolean {
        val t2 = threshold * threshold
        return distSq(a.tl, b.tl) <= t2 &&
               distSq(a.tr, b.tr) <= t2 &&
               distSq(a.br, b.br) <= t2 &&
               distSq(a.bl, b.bl) <= t2
    }

    private fun distSq(p: Point2f, q: Point2f): Float {
        val dx = p.x - q.x
        val dy = p.y - q.y
        return dx * dx + dy * dy
    }
}
