/*
 * LocationProbe.swift
 *
 * One-shot wrapper around CLLocationManager + CLGeocoder. Exposes a
 * single `requestOnce` method that handles permission first-ask,
 * requestLocation, and reverse-geocoding, delivering results on the
 * main actor.
 *
 * Info.plist (app target side — this package has no target of its own):
 *   NSLocationWhenInUseUsageDescription — required string for the
 *   "Allow while in use" prompt that CoreLocation triggers on first
 *   request. Without it the system denies immediately and the callback
 *   fires with `nil`.
 */

import Foundation
import CoreLocation

@MainActor
final class LocationProbe: NSObject, ObservableObject, CLLocationManagerDelegate {

    /// Result payload for the editor UI — lat/lng + best-effort address.
    struct Fix: Sendable {
        let lat: Double
        let lng: Double
        let address: String?
    }

    private let manager = CLLocationManager()
    private let geocoder = CLGeocoder()
    private var pending: ((Fix?) -> Void)?

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
    }

    /// Ask for a single fresh fix. If permission is not yet determined we
    /// request when-in-use; the authorization callback chains into the
    /// fix request. Any denial / failure resolves with `nil`.
    func requestOnce(completion: @escaping (Fix?) -> Void) {
        // If a previous request is still in flight, replace it — the UI
        // guarantees only one active request at a time, but being
        // defensive avoids a callback leak.
        pending?(nil)
        pending = completion

        switch manager.authorizationStatus {
        case .notDetermined:
            manager.requestWhenInUseAuthorization()
        case .authorizedWhenInUse, .authorizedAlways:
            manager.requestLocation()
        default:
            finish(with: nil)
        }
    }

    // MARK: - CLLocationManagerDelegate

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        Task { @MainActor in
            switch manager.authorizationStatus {
            case .authorizedWhenInUse, .authorizedAlways:
                // Permission just granted — chain into the fix request.
                if pending != nil { manager.requestLocation() }
            case .denied, .restricted:
                finish(with: nil)
            default:
                break
            }
        }
    }

    nonisolated func locationManager(
        _ manager: CLLocationManager,
        didUpdateLocations locations: [CLLocation]
    ) {
        let location = locations.last
        Task { @MainActor in
            guard let location else {
                finish(with: nil)
                return
            }
            // Best-effort reverse geocode. A miss still yields a Fix —
            // the UI falls back to raw coordinates.
            let addr: String? = await withCheckedContinuation { cont in
                geocoder.reverseGeocodeLocation(location) { placemarks, _ in
                    cont.resume(returning: Self.formatAddress(placemarks?.first))
                }
            }
            finish(with: Fix(
                lat: location.coordinate.latitude,
                lng: location.coordinate.longitude,
                address: addr
            ))
        }
    }

    nonisolated func locationManager(
        _ manager: CLLocationManager,
        didFailWithError error: Error
    ) {
        Task { @MainActor in finish(with: nil) }
    }

    private func finish(with fix: Fix?) {
        let callback = pending
        pending = nil
        callback?(fix)
    }

    // MARK: - Formatting

    /// Short one-line address. Falls back to nil when the placemark has
    /// nothing useful — the row then shows raw coordinates.
    ///
    /// `nonisolated` so the CLGeocoder reverse-geocode completion
    /// callback (which runs on a CoreLocation-owned queue) can call
    /// it directly without a MainActor hop. The function is pure —
    /// no state to protect.
    private nonisolated static func formatAddress(_ placemark: CLPlacemark?) -> String? {
        guard let p = placemark else { return nil }
        let parts: [String?] = [
            [p.subThoroughfare, p.thoroughfare].compactMap { $0 }.joined(separator: " ").nilIfEmpty,
            p.locality,
            p.administrativeArea,
            p.isoCountryCode,
        ]
        let line = parts.compactMap { $0 }.joined(separator: ", ")
        return line.isEmpty ? nil : line
    }
}

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}
