/*
 * CaptureCountsByMode.swift
 * Aggregate capture counts across the whole library, grouped by
 * capture mode. Feeds the home-screen "trees saved" strip.
 *
 * Today only `notes` is populated (from live-page count); the other
 * four modes will come online when the design-system `captures`
 * table ships (see design-system/migrations/v1_initial.sql). The
 * struct intentionally exposes zero for absent modes so the UI can
 * consume the same shape before and after that migration.
 */

import Foundation

public struct CaptureCountsByMode: Equatable, Sendable {
    public let notes: Int
    public let photos: Int
    public let scans: Int
    public let voice: Int
    public let contacts: Int

    public init(
        notes: Int = 0,
        photos: Int = 0,
        scans: Int = 0,
        voice: Int = 0,
        contacts: Int = 0
    ) {
        self.notes = notes
        self.photos = photos
        self.scans = scans
        self.voice = voice
        self.contacts = contacts
    }

    public static let empty = CaptureCountsByMode()

    /// Flat sum across all five capture modes.
    public var total: Int { notes + photos + scans + voice + contacts }
}
