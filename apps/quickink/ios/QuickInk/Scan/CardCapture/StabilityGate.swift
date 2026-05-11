/*
 * StabilityGate.swift
 *
 * Three-frame ring buffer that gates auto-capture in Business
 * Card mode. A frame "votes" by being passed to [vote(_:)];
 * when three consecutive votes land within
 * `perCornerDriftThresholdPx` of each other (per-corner
 * Euclidean drift), `vote` returns true exactly once and the
 * gate enters a debounce window of `debounceMs` during which
 * further votes are dropped.
 *
 * Pure logic, no Apple framework imports beyond Foundation —
 * unit-testable from XCTest. The detector populates votes from
 * a successful `.valid` detection; partial / none detections
 * call `reset()` so a wobbly intermediate frame doesn't keep
 * an old quad "stable" against itself.
 *
 * Mirror of Android `StabilityGate.kt`.
 */

import Foundation

public final class StabilityGate {

    private let perCornerDriftThresholdPx: Float
    private let debounceMs: Int64
    private let clock: () -> Int64

    private var buffer: [DetectedQuad?] = [nil, nil, nil]
    private var bufferSize: Int = 0
    private var debouncedUntil: Int64 = 0

    /// When the current streak of valid frames started, in ms
    /// since 1970. `-1` when no streak is in progress. Used by
    /// the analytics caller to log `time_to_lock_ms`.
    public private(set) var streakStartMs: Int64 = -1

    public init(
        perCornerDriftThresholdPx: Float = 5.0,
        debounceMs: Int64 = 1500,
        clock: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) },
    ) {
        self.perCornerDriftThresholdPx = perCornerDriftThresholdPx
        self.debounceMs = debounceMs
        self.clock = clock
    }

    public func vote(_ quad: DetectedQuad) -> Bool {
        let now = clock()
        if now < debouncedUntil { return false }
        if bufferSize < 3 {
            buffer[bufferSize] = quad
            bufferSize += 1
            if bufferSize == 1 { streakStartMs = now }
        } else {
            buffer[0] = buffer[1]
            buffer[1] = buffer[2]
            buffer[2] = quad
        }
        if bufferSize < 3 { return false }
        guard let a = buffer[0], let b = buffer[1], let c = buffer[2] else {
            return false
        }
        if !cornersWithin(a, b, threshold: perCornerDriftThresholdPx) { return false }
        if !cornersWithin(b, c, threshold: perCornerDriftThresholdPx) { return false }
        debouncedUntil = now + debounceMs
        bufferSize = 0
        return true
    }

    public func reset() {
        bufferSize = 0
        streakStartMs = -1
    }

    public func streakElapsedMs() -> Int64 {
        if streakStartMs < 0 { return 0 }
        return max(0, clock() - streakStartMs)
    }

    public func isInDebounce() -> Bool { clock() < debouncedUntil }

    private func cornersWithin(_ a: DetectedQuad, _ b: DetectedQuad, threshold: Float) -> Bool {
        let t2 = threshold * threshold
        return distSq(a.tl, b.tl) <= t2 &&
               distSq(a.tr, b.tr) <= t2 &&
               distSq(a.br, b.br) <= t2 &&
               distSq(a.bl, b.bl) <= t2
    }

    private func distSq(_ p: Point2f, _ q: Point2f) -> Float {
        let dx = p.x - q.x; let dy = p.y - q.y
        return dx * dx + dy * dy
    }
}
