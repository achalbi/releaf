/*
 * CapturedLocation.kt
 *
 * Plain-value carrier for the geolocation a scan / import flow
 * attaches to a fresh `CaptureEntity`. Lives in the data package so
 * both the repository (which writes it to the DB row) and the
 * features-layer LocationService (which produces it) can reference
 * the same shape without crossing the feature-to-data dependency
 * boundary the wrong way.
 *
 * Mirror of iOS's `CapturedLocation` (Swift `struct` in
 * `LocationService.swift`).
 */

package app.quickink.mobile.data.capture

/**
 * One captured-time geolocation reading + reverse-geocoded place
 * name. All four fields are nullable from the persistence layer's
 * perspective (the schema columns are nullable too), but at the
 * call-site the producer keeps lat / lon together — a null struct
 * means "no location was attached to this capture."
 *
 * - [latitude] / [longitude] are decimal degrees, paired (both
 *   present or the whole struct is null).
 * - [locality] is the reverse-geocoded city.
 * - [subLocality] is the reverse-geocoded neighbourhood / area.
 *
 * Both place-name fields can stay null even when the coordinates
 * succeed — the system geocoder is best-effort and offline /
 * unknown coordinates surface as nil placemarks.
 */
data class CapturedLocation(
    val latitude: Double,
    val longitude: Double,
    val locality: String?,
    val subLocality: String?,
)
