/*
 * DaylightLocationStore.swift
 *
 * Tiny @ObservableObject the Home `DaylightHero` card reads its
 * lat/long from. Sits in front of `LocationService.captureCurrent()`
 * so the hero doesn't have to know about CoreLocation, permission
 * states, or the scan-flow capture path.
 *
 * Strategy:
 *   - Persist the last successful fix in `UserDefaults` so every
 *     launch after the first paints the hero instantly (sunrise/
 *     sunset drift by <1 minute over ~100km — the persisted coords
 *     stay good for weeks even if the user travels). Solar math is
 *     latitude-dominant; longitude only matters for the time-zone
 *     shift, which is independent of this cache.
 *
 *   - On `refreshIfNeeded`, kick off one async `captureCurrent`
 *     and overwrite the cache. Skipped when permission isn't
 *     granted (this isn't a prompt path; the hero shouldn't trigger
 *     a system dialog).
 *
 *   - In-flight de-dup: a single `fetchTask` slot prevents two
 *     overlapping calls when `refreshIfNeeded` is invoked from
 *     both `QuickInkRoot.task` and a scene-active hook.
 */

import Foundation

@MainActor
public final class DaylightLocationStore: ObservableObject {

    @Published public private(set) var latitude:  Double?
    @Published public private(set) var longitude: Double?

    private var fetchTask: Task<Void, Never>?

    private enum Keys {
        static let lat = "quickink.daylight.lat"
        static let lng = "quickink.daylight.lng"
    }

    public init() {
        // Seed from the persisted cache — re-launches paint the bar
        // immediately rather than flashing the loading shell.
        let defaults = UserDefaults.standard
        if defaults.object(forKey: Keys.lat) != nil,
           defaults.object(forKey: Keys.lng) != nil {
            latitude  = defaults.double(forKey: Keys.lat)
            longitude = defaults.double(forKey: Keys.lng)
        }
    }

    /// Kick off a one-shot location fetch if we don't already have
    /// coordinates AND permission is granted. No-op when there's
    /// already an in-flight task or the user hasn't granted location.
    /// Safe to call repeatedly (e.g. on every `scenePhase == .active`).
    public func refreshIfNeeded() {
        guard fetchTask == nil else { return }
        let status = LocationService.shared.authorizationStatus
        guard status == .authorizedWhenInUse || status == .authorizedAlways else {
            return
        }
        // Skip the refetch when we already have a cache AND a fetch
        // happened recently — keeps the bar from waking GPS on every
        // foreground.
        if latitude != nil, longitude != nil,
           !Self.cacheIsStale() { return }

        fetchTask = Task { [weak self] in
            defer { self?.fetchTask = nil }
            guard let captured = await LocationService.shared.captureCurrent() else {
                return
            }
            self?.applyAndPersist(latitude: captured.latitude, longitude: captured.longitude)
        }
    }

    private func applyAndPersist(latitude: Double, longitude: Double) {
        self.latitude  = latitude
        self.longitude = longitude
        let defaults = UserDefaults.standard
        defaults.set(latitude,  forKey: Keys.lat)
        defaults.set(longitude, forKey: Keys.lng)
        defaults.set(Date().timeIntervalSince1970, forKey: "quickink.daylight.fetched_at")
    }

    /// Cache considered stale after 24h. The sun's path is the same
    /// week to week at a fixed point on Earth; a daily refresh keeps
    /// us honest if the user travelled overnight.
    private static func cacheIsStale() -> Bool {
        let key = "quickink.daylight.fetched_at"
        let ts  = UserDefaults.standard.double(forKey: key)
        guard ts > 0 else { return true }
        let age = Date().timeIntervalSince1970 - ts
        return age > 24 * 60 * 60
    }
}
