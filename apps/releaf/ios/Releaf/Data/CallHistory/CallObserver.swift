/*
 * CallObserver.swift
 *
 * Wraps `CXCallObserver` so the Contacts screen can capture
 * duration for calls it just initiated. CXCallObserver reports
 * every cellular call on the device (including the ones we
 * initiated via a `tel:` URL) via the delegate callback — no
 * usage-description or user permission required.
 *
 * Attribution is best-effort: the CXCall UUIDs are anonymous, so
 * we bind the first CXCall that changes state after `attach(id:)`
 * to that history id and track it to `hasEnded`. The "user taps
 * dial → call ends → returns to app" path is reliable; overlapping
 * calls in-flight will confuse attribution but that's acceptable.
 */

import Foundation

// CallKit is iOS-only; the entire observer is gated behind `#if os(iOS)`.
// macOS gets a no-op stub with the same public API so anything that
// references `CallObserver` (e.g. for SwiftUI previews on Mac) still
// compiles. The stub's `attach(callId:)` returns `false` to signal
// "tracking unavailable" — callers that already handle the false
// return path (Android does, for symmetry) degrade gracefully.
#if os(iOS)
import CallKit

public final class CallObserver: NSObject, CXCallObserverDelegate, @unchecked Sendable {

    /// Process-lifetime singleton. Holds in-flight pending/active
    /// attribution state across multiple calls — recreating the
    /// observer per dial would lose that state.
    public static let shared = CallObserver(repository: CallHistoryRepository())

    private let observer = CXCallObserver()
    private let repository: CallHistoryRepository

    /// History id awaiting its first CXCall "outgoing" event.
    private var pendingId: String?
    /// CXCall UUID → history id once attribution is locked in.
    private var active: [UUID: String] = [:]
    /// Watchdog that clears `pendingId` when no call event arrives.
    private var pendingTimeoutTask: Task<Void, Never>?

    public init(repository: CallHistoryRepository) {
        self.repository = repository
        super.init()
        observer.setDelegate(self, queue: nil)
    }

    /// Park `callId` as the target of the next CXCall state change.
    /// Returns true — on iOS there's no pre-flight permission that
    /// can fail, but we keep the Bool for symmetry with Android.
    @discardableResult
    public func attach(callId: String) -> Bool {
        pendingId = callId
        pendingTimeoutTask?.cancel()
        let targetId = callId
        pendingTimeoutTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 3 * 60 * 1_000_000_000)
            guard let self else { return }
            await MainActor.run {
                if self.pendingId == targetId {
                    self.pendingId = nil
                    Task { try? await self.repository.recordEnded(id: targetId) }
                }
            }
        }
        return true
    }

    // MARK: - CXCallObserverDelegate

    public func callObserver(_ observer: CXCallObserver, callChanged call: CXCall) {
        // Prefer the in-flight pending-id for the first event on a
        // previously-unseen call. `isOutgoing` filters out incoming
        // calls; the dial flow only attaches when we're placing a
        // call ourselves.
        if active[call.uuid] == nil && call.isOutgoing, let id = pendingId {
            pendingId = nil
            pendingTimeoutTask?.cancel()
            pendingTimeoutTask = nil
            active[call.uuid] = id
        }

        guard let id = active[call.uuid] else { return }

        // `hasConnected` transitions once when the other side picks
        // up. Record connect here so a subsequent `hasEnded` can
        // compute duration.
        if call.hasConnected {
            Task { try? await self.repository.recordConnected(id: id) }
        }

        if call.hasEnded {
            active.removeValue(forKey: call.uuid)
            Task { try? await self.repository.recordEnded(id: id) }
        }
    }
}

#else

// macOS no-op stub. Same public surface area as the iOS implementation
// so SwiftPM resolves on Mac for previews; live call attribution
// obviously isn't available without CallKit.
public final class CallObserver: @unchecked Sendable {
    public static let shared = CallObserver(repository: CallHistoryRepository())
    public init(repository: CallHistoryRepository) { _ = repository }

    @discardableResult
    public func attach(callId: String) -> Bool { false }
}

#endif
