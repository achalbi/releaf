/*
 * LocationService.swift
 *
 * Thin CoreLocation wrapper used by the scan + import flows to
 * attach a (latitude, longitude, sub-locality, locality) record to
 * each capture. Bridges CLLocationManager's delegate-driven API to
 * async/await with two single-shot continuations — one for
 * authorization, one for a one-off fix.
 *
 * The caller flow looks like:
 *
 *     guard SettingsState.shared.locationForScansEnabled else { return }
 *     let status = await LocationService.shared.requestAuthorization()
 *     guard status == .authorizedWhenInUse || status == .authorizedAlways
 *         else { return }
 *     let captured = await LocationService.shared.captureCurrent()
 *
 * Returning `nil` is the canonical "couldn't get a fix" signal —
 * the capture row writes back four NULL columns and the Details
 * card simply omits the Area / City rows. We never block the scan
 * on the location fetch; the existing capture proceeds with or
 * without coordinates.
 *
 * Mirror of Android's `LocationService` (Kotlin coroutine flavour).
 */

import Foundation
import CoreLocation

/// Result of a one-shot location capture. All four fields are
/// optional from the caller's perspective: `latitude` and `longitude`
/// pair (both set, or skip the capture entirely); `locality` and
/// `subLocality` are best-effort and stay nil when the system
/// geocoder doesn't surface them for the given coordinates (offline,
/// remote area, unknown placemark).
public struct CapturedLocation: Equatable, Sendable {
    public let latitude: Double
    public let longitude: Double
    public let locality: String?
    public let subLocality: String?
}

/// One-call helper for "give me the current location with a
/// reverse-geocoded place name." Wraps a single shared
/// `CLLocationManager` so we don't churn the system service on
/// every scan; safe to call concurrently — overlapping requests
/// cancel the in-flight continuation with `nil` so neither caller
/// hangs.
public final class LocationService: NSObject, @unchecked Sendable, CLLocationManagerDelegate {

    public static let shared = LocationService()

    private let manager: CLLocationManager
    private let stateLock = NSLock()
    private var authContinuation: CheckedContinuation<CLAuthorizationStatus, Never>?
    private var locationContinuation: CheckedContinuation<CLLocation?, Never>?

    public override init() {
        manager = CLLocationManager()
        super.init()
        manager.delegate = self
        // Hundred-metre accuracy is plenty for sub-locality / locality
        // reverse-geocoding and meaningfully cheaper to obtain (no
        // GPS warm-up). City names don't get more precise at finer
        // accuracy, and we don't pin a scan to a specific street.
        manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
    }

    /// Current system permission status, read straight from
    /// `CLLocationManager`. No async cost — callers can short-circuit
    /// before requesting a fix.
    public var authorizationStatus: CLAuthorizationStatus {
        manager.authorizationStatus
    }

    /// Prompt the user for "When in Use" authorization if and only
    /// if status is `.notDetermined`. Already-granted or already-
    /// denied states return immediately with the current value, so
    /// the caller doesn't need a separate `if` ladder.
    public func requestAuthorization() async -> CLAuthorizationStatus {
        let current = manager.authorizationStatus
        print("[Location] requestAuthorization: current status = \(current.debugLabel)")
        if current != .notDetermined { return current }
        return await withCheckedContinuation { cont in
            stateLock.lock()
            // Defensive — if an earlier request never resolved (would
            // only happen on app suspend before the system dialog),
            // resume it with the current status so its caller unwinds
            // cleanly instead of hanging forever.
            if let prev = authContinuation {
                authContinuation = nil
                stateLock.unlock()
                prev.resume(returning: current)
                stateLock.lock()
            }
            authContinuation = cont
            stateLock.unlock()
            manager.requestWhenInUseAuthorization()
        }
    }

    /// Fetch a single location reading and reverse-geocode it.
    /// Returns nil when permission is denied, the system can't get a
    /// fix in a reasonable time, or the geocoder fails — in all three
    /// cases the caller writes the capture without location columns.
    public func captureCurrent() async -> CapturedLocation? {
        let status = manager.authorizationStatus
        print("[Location] captureCurrent: status = \(status.debugLabel)")
        guard status == .authorizedWhenInUse || status == .authorizedAlways else {
            print("[Location] captureCurrent: not authorized, returning nil")
            return nil
        }
        guard let location = await requestSingleLocation() else {
            print("[Location] captureCurrent: requestSingleLocation returned nil")
            return nil
        }
        let lat = location.coordinate.latitude
        let lon = location.coordinate.longitude
        print("[Location] captureCurrent: got fix lat=\(lat) lon=\(lon)")
        // `reverseGeocodeLocation` is rate-limited by Apple's
        // service; intermittent failures are common in the field and
        // don't merit logging. `try?` swallows them — the capture
        // simply records the coordinates without the place name.
        let placemark = try? await CLGeocoder().reverseGeocodeLocation(location).first
        print("[Location] captureCurrent: placemark raw locality=\(placemark?.locality ?? "nil") subLocality=\(placemark?.subLocality ?? "nil")")
        let names = Self.dedupePlaceNames(
            locality:    placemark?.locality,
            subLocality: placemark?.subLocality
        )
        print("[Location] captureCurrent: dedupe → locality=\(names.locality ?? "nil") subLocality=\(names.subLocality ?? "nil")")
        return CapturedLocation(
            latitude:    lat,
            longitude:   lon,
            locality:    names.locality,
            subLocality: names.subLocality
        )
    }

    /// Bridge `CLLocationManager.requestLocation()` to async. Resolves
    /// with the most recent fix from `didUpdateLocations` (typically
    /// fired once on a one-shot request) or `nil` from
    /// `didFailWithError`.
    private func requestSingleLocation() async -> CLLocation? {
        await withCheckedContinuation { cont in
            stateLock.lock()
            // Overlapping requests — the older one gets cancelled so
            // neither caller leaks its continuation.
            if let prev = locationContinuation {
                locationContinuation = nil
                stateLock.unlock()
                prev.resume(returning: nil)
                stateLock.lock()
            }
            locationContinuation = cont
            stateLock.unlock()
            manager.requestLocation()
        }
    }

    // MARK: - CLLocationManagerDelegate

    public func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        // Fires once with the current status when the delegate is
        // assigned AND again each time the status changes (e.g.
        // user grants permission via the system dialog). Either way
        // we hand the resolution off to any waiting caller; if none
        // is waiting the continuation pointer is nil and we no-op.
        stateLock.lock()
        let cont = authContinuation
        authContinuation = nil
        stateLock.unlock()
        cont?.resume(returning: manager.authorizationStatus)
    }

    public func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        stateLock.lock()
        let cont = locationContinuation
        locationContinuation = nil
        stateLock.unlock()
        cont?.resume(returning: locations.last)
    }

    public func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        stateLock.lock()
        let cont = locationContinuation
        locationContinuation = nil
        stateLock.unlock()
        cont?.resume(returning: nil)
    }
}

// MARK: - Debug helpers

private extension CLAuthorizationStatus {
    /// Human-readable label for `print` lines — the enum's
    /// numeric raw value is opaque in logs ("status = 3" tells
    /// you nothing); these labels match Apple's documentation
    /// names so a grep for "authorizedWhenInUse" finds them.
    var debugLabel: String {
        switch self {
        case .notDetermined:       return "notDetermined"
        case .restricted:          return "restricted"
        case .denied:              return "denied"
        case .authorizedAlways:    return "authorizedAlways"
        case .authorizedWhenInUse: return "authorizedWhenInUse"
        @unknown default:          return "unknown(\(self.rawValue))"
        }
    }
}

// MARK: - Place-name dedupe

extension LocationService {
    /// Some `CLGeocoder` results return the same string for
    /// `locality` and `subLocality` when neighborhood-level data
    /// isn't available — typical for many non-US cities where the
    /// geocoder only resolves to the city, not the neighborhood.
    /// Dropping the redundant sub-locality here keeps the Details
    /// card from rendering an "Area" and a "City" row with
    /// identical values.
    ///
    /// Called by `captureCurrent` (write-time, so new scans land
    /// clean) and by `ScanDetailScreen`'s render path (so existing
    /// rows with duplicate values render correctly without a
    /// DB migration).
    public static func dedupePlaceNames(
        locality: String?,
        subLocality: String?
    ) -> (locality: String?, subLocality: String?) {
        let trimmedLoc = locality?.trimmingCharacters(in: .whitespaces)
        let trimmedSub = subLocality?.trimmingCharacters(in: .whitespaces)
        if let l = trimmedLoc, !l.isEmpty, l == trimmedSub {
            return (locality: locality, subLocality: nil)
        }
        return (locality: locality, subLocality: subLocality)
    }
}

// MARK: - One-shot prompt tracking

extension LocationService {
    /// UserDefaults key — bumped when the location-prompt UX
    /// changes meaningfully so existing users re-see the prompt
    /// the first time after upgrade.
    private static let promptHandledKey = "quickink.location.prompt_handled.v1"

    /// True when we've already asked the user about location
    /// (either via the onboarding step OR the post-onboarding
    /// one-shot trigger in `MainShell`). Suppresses the one-shot
    /// re-ask on every app launch.
    public static var wasPromptHandled: Bool {
        UserDefaults.standard.bool(forKey: promptHandledKey)
    }

    /// Mark the prompt as handled. Called from the onboarding
    /// LocationPermissionScreen (whether the user tapped Allow or
    /// Skip) and from the MainShell's one-shot trigger so the two
    /// paths share a single flag.
    public static func markPromptHandled() {
        UserDefaults.standard.set(true, forKey: promptHandledKey)
    }
}
