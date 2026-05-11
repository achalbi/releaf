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
import Contacts

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
    /// Formatted full street address built from the placemark via
    /// `CNPostalAddressFormatter` — single-line, locale-aware,
    /// e.g. "1234 Main St, Mission District, San Francisco CA
    /// 94110, USA". Nil when the geocoder didn't return a
    /// postalAddress (offline, rate-limited, or remote area).
    public let address: String?
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
        // GPS-grade accuracy. The 100m setting we shipped first was
        // catching reverse-geocodes for the wrong city near boundary
        // areas + producing imprecise full addresses (which were the
        // explicit reasons for adding `address` in v7). `kCL-
        // LocationAccuracyBest` asks iOS for the best fix it can
        // produce; with "Precise Location" granted in the system
        // dialog the result is street-accurate. With Approximate the
        // user gets ~few-km accuracy regardless of what we ask for,
        // which is the system's choice.
        manager.desiredAccuracy = kCLLocationAccuracyBest
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
        if let pm = placemark {
            // Verbose dump of every CLPlacemark field. Lets us see
            // which one matches what the user expects to see for
            // "City" without guessing — different regions surface
            // the meaningful name on different fields (`locality`
            // for US; `subAdministrativeArea` for many Indian
            // metropolitan areas; etc.).
            print("[Location] placemark fields: name=\(pm.name ?? "nil") thoroughfare=\(pm.thoroughfare ?? "nil") subThoroughfare=\(pm.subThoroughfare ?? "nil") subLocality=\(pm.subLocality ?? "nil") locality=\(pm.locality ?? "nil") subAdministrativeArea=\(pm.subAdministrativeArea ?? "nil") administrativeArea=\(pm.administrativeArea ?? "nil") postalCode=\(pm.postalCode ?? "nil") country=\(pm.country ?? "nil") isoCountryCode=\(pm.isoCountryCode ?? "nil") areasOfInterest=\(pm.areasOfInterest?.joined(separator: ", ") ?? "nil")")
        }
        print("[Location] captureCurrent: placemark raw locality=\(placemark?.locality ?? "nil") subLocality=\(placemark?.subLocality ?? "nil")")
        let derived: (locality: String?, subLocality: String?) =
            placemark.map(Self.derivePlaceNames(from:)) ?? (nil, nil)
        print("[Location] captureCurrent: derive → locality=\(derived.locality ?? "nil") subLocality=\(derived.subLocality ?? "nil")")
        let names = Self.dedupePlaceNames(
            locality:    derived.locality,
            subLocality: derived.subLocality
        )
        print("[Location] captureCurrent: dedupe → locality=\(names.locality ?? "nil") subLocality=\(names.subLocality ?? "nil")")
        let address = placemark.flatMap(Self.formatAddress(from:))
        print("[Location] captureCurrent: address=\(address ?? "nil")")
        return CapturedLocation(
            latitude:    lat,
            longitude:   lon,
            locality:    names.locality,
            subLocality: names.subLocality,
            address:     address
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

// MARK: - Address formatting

extension LocationService {
    /// Build a single-line formatted street address from a
    /// `CLPlacemark`. Uses `CNPostalAddressFormatter` so the field
    /// order respects the placemark's locale (US "Street, City,
    /// State ZIP" vs European "Street, ZIP City"), then collapses
    /// the formatter's newlines into ", " so the string fits on a
    /// single Details-card row that wraps to two lines if needed.
    /// Returns `nil` when the placemark carries no `postalAddress`.
    public static func formatAddress(from placemark: CLPlacemark) -> String? {
        guard let postal = placemark.postalAddress else { return nil }
        let formatter = CNPostalAddressFormatter()
        formatter.style = .mailingAddress
        let multiline = formatter.string(from: postal)
        let single = multiline
            .replacingOccurrences(of: "\n", with: ", ")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return single.isEmpty ? nil : single
    }
}

// MARK: - Place-name derivation (region-aware)

extension LocationService {
    /// Choose which `CLPlacemark` fields back the "City" + "Area"
    /// rows on the Details card. Most regions are happy with the
    /// geocoder's own labelling (`locality` = city, `subLocality` =
    /// area), but India returns the immediate village as `locality`
    /// (e.g. "Kadabagere") and the broader metropolitan name in
    /// `subAdministrativeArea` (e.g. "Bangalore Division"). Users
    /// there think of the metropolitan name as their city. For
    /// `IN` placemarks we hoist a suffix-stripped subAdministrative-
    /// Area into the "City" slot and demote the original locality
    /// to "Area". Other countries pass through untouched.
    public static func derivePlaceNames(
        from placemark: CLPlacemark
    ) -> (locality: String?, subLocality: String?) {
        let locality    = placemark.locality?.trimmingCharacters(in: .whitespaces).nilIfEmpty
        let subLocality = placemark.subLocality?.trimmingCharacters(in: .whitespaces).nilIfEmpty
        let subAdmin    = placemark.subAdministrativeArea?
            .trimmingCharacters(in: .whitespaces).nilIfEmpty
        let countryCode = placemark.isoCountryCode?.uppercased()

        if countryCode == "IN", let subAdmin = subAdmin {
            let cleaned = stripIndianAdminSuffixes(subAdmin)
            if !cleaned.isEmpty, cleaned != locality {
                // Hoist subAdmin → City, demote original locality →
                // Area. Original subLocality (a more granular
                // hyper-local) is dropped; the 2-row UI can't
                // surface three levels of place name without
                // bloating the Details card, and "Area" carrying
                // the immediate town/suburb reads better than a
                // street-level cul-de-sac name.
                return (locality: cleaned, subLocality: locality)
            }
        }
        return (locality: locality, subLocality: subLocality)
    }

    /// Strip the trailing admin-division suffix Indian geocoders
    /// often append to the metropolitan name ("Bangalore Division",
    /// "Bengaluru Urban", "Mumbai Suburban District"). Case-
    /// insensitive; one pass is enough — these labels carry at
    /// most one suffix.
    private static func stripIndianAdminSuffixes(_ s: String) -> String {
        let suffixes = [
            " Division",
            " District",
            " Urban",
            " Rural",
            " Suburban",
            " Metropolitan",
        ]
        let trimmed = s.trimmingCharacters(in: .whitespaces)
        let lower = trimmed.lowercased()
        for suffix in suffixes {
            if lower.hasSuffix(suffix.lowercased()) {
                return String(trimmed.dropLast(suffix.count))
                    .trimmingCharacters(in: .whitespaces)
            }
        }
        return trimmed
    }
}

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
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
