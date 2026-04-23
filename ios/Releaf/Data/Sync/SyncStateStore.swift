/*
 * SyncStateStore.swift
 *
 * Per-device sync bookkeeping. UserDefaults-backed for the first cut
 * — matches the shape of Android's `sync_state` SQLite table but
 * without the Room / GRDB machinery. The values are never synced to
 * Drive; each device reconstructs them from its own fetches.
 *
 * Stored keys:
 *   - last_full_sync_at         ISO-8601 UTC of the last successful pass.
 *   - manifest_checksum         SHA-256 of the manifest we last uploaded.
 *                               Lets a future Settings surface detect
 *                               remote-edit-since-we-wrote.
 *   - pending_count             Rows that failed the last pass.
 *   - drive_quota_exhausted_at  ISO-8601 UTC | nil.
 *   - version_blocked           "true" if the remote manifest is on a
 *                               newer major schema than this build.
 *
 * If we later decide sync state needs to be observable by views (e.g.
 * the Settings screen reading last-sync live), swap the backing store
 * for a GRDB table. The protocol stays the same.
 */

import Foundation
import Combine

public final class SyncStateStore: ObservableObject {

    public static let shared = SyncStateStore()

    public struct State: Equatable {
        public var lastFullSyncAt: String?
        public var manifestChecksum: String?
        public var pendingCount: Int
        public var driveQuotaExhaustedAt: String?
        public var versionBlocked: Bool

        public init(
            lastFullSyncAt: String? = nil,
            manifestChecksum: String? = nil,
            pendingCount: Int = 0,
            driveQuotaExhaustedAt: String? = nil,
            versionBlocked: Bool = false
        ) {
            self.lastFullSyncAt = lastFullSyncAt
            self.manifestChecksum = manifestChecksum
            self.pendingCount = pendingCount
            self.driveQuotaExhaustedAt = driveQuotaExhaustedAt
            self.versionBlocked = versionBlocked
        }
    }

    @Published public private(set) var state: State = State()

    private let defaults: UserDefaults

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        self.state = load()
    }

    public func recordSuccess(
        lastFullSyncAt: String,
        manifestChecksum: String,
        pendingCount: Int
    ) {
        defaults.set(lastFullSyncAt, forKey: Keys.lastFullSyncAt)
        defaults.set(manifestChecksum, forKey: Keys.manifestChecksum)
        defaults.set(pendingCount, forKey: Keys.pendingCount)
        defaults.set(false, forKey: Keys.versionBlocked)
        state = load()
    }

    public func recordVersionBlocked() {
        defaults.set(true, forKey: Keys.versionBlocked)
        state = load()
    }

    public func recordQuotaExhausted(at: String) {
        defaults.set(at, forKey: Keys.driveQuotaExhaustedAt)
        state = load()
    }

    public func clear() {
        for k in Keys.all { defaults.removeObject(forKey: k) }
        state = load()
    }

    // MARK: - Persistence

    private func load() -> State {
        State(
            lastFullSyncAt: defaults.string(forKey: Keys.lastFullSyncAt),
            manifestChecksum: defaults.string(forKey: Keys.manifestChecksum),
            pendingCount: defaults.integer(forKey: Keys.pendingCount),
            driveQuotaExhaustedAt: defaults.string(forKey: Keys.driveQuotaExhaustedAt),
            versionBlocked: defaults.bool(forKey: Keys.versionBlocked)
        )
    }

    private enum Keys {
        static let lastFullSyncAt        = "releaf.sync.last_full_sync_at"
        static let manifestChecksum      = "releaf.sync.manifest_checksum"
        static let pendingCount          = "releaf.sync.pending_count"
        static let driveQuotaExhaustedAt = "releaf.sync.drive_quota_exhausted_at"
        static let versionBlocked        = "releaf.sync.version_blocked"

        static let all: [String] = [
            lastFullSyncAt, manifestChecksum, pendingCount,
            driveQuotaExhaustedAt, versionBlocked,
        ]
    }
}
