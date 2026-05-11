/*
 * CaptureListViewModel.swift
 *
 * Observable for the home-screen "Recent" rail. Streams captures
 * (newest first) so the rail surfaces the actual scanned document
 * thumbnail right after a scan completes — replacing the earlier
 * mock lined-paper rendering tied to notepad entries.
 *
 * Lightweight — only the fields needed to render a thumb + open
 * the detail viewer (id, preview URI, category, created_at). The
 * full row + OCR text load lazily inside `ScanDetailScreen`.
 */

import Foundation
import Combine
import GRDB

public struct CaptureSummary: Codable, FetchableRecord, Equatable, Sendable, Identifiable {
    public let id: String
    /// Defaulting to `nil` keeps existing manual init sites compiling
    /// (e.g., search-result construction in `CaptureRepository`); the
    /// SELECTs that drive the Library + detail view explicitly read
    /// the column so the field is populated where it matters.
    public let title: String?
    public let previewUri: String?
    public let pdfUri: String
    public let category: String?
    public let pageCount: Int
    public let createdAt: String
    /// `"scan"` (default) — went through VisionKit. `"import"` —
    /// came from the system photo picker. Drives the "Import" pill
    /// on the Library cards. Defaulting to `"scan"` keeps any
    /// search-result construction site (and rows synced from older
    /// clients) reading back as scans.
    public let source: String
    /// Geographic latitude (decimal degrees) captured at scan time
    /// when the user has "Location for scans" enabled. `nil` for
    /// captures taken with location off, denied, or older rows.
    public let latitude: Double?
    /// Geographic longitude. Pairs with [latitude] — either both are
    /// set or both are nil; never half-resolved.
    public let longitude: Double?
    /// Reverse-geocoded city. `CLPlacemark.locality` — usually the
    /// formal city name in the device's locale. Surfaced by the
    /// Details card on `ScanDetailScreen` as the "City" row.
    public let locality: String?
    /// Reverse-geocoded neighbourhood / area. `CLPlacemark.sub-
    /// Locality`. Surfaced by the Details card as the "Area" row.
    public let subLocality: String?
    /// Formatted full street address from `CLPlacemark` (via
    /// `CNPostalAddressFormatter`) — e.g. "1234 Main St, Mission
    /// District, San Francisco, CA 94110, USA". Surfaced as the
    /// "Address" row on the Details card. Nil when the geocode
    /// failed or the location toggle was off.
    public let address: String?

    public init(
        id: String,
        title: String? = nil,
        previewUri: String?,
        pdfUri: String,
        category: String?,
        pageCount: Int,
        createdAt: String,
        source: String = "scan",
        latitude: Double? = nil,
        longitude: Double? = nil,
        locality: String? = nil,
        subLocality: String? = nil,
        address: String? = nil
    ) {
        self.id          = id
        self.title       = title
        self.previewUri  = previewUri
        self.pdfUri      = pdfUri
        self.category    = category
        self.pageCount   = pageCount
        self.createdAt   = createdAt
        self.source      = source
        self.latitude    = latitude
        self.longitude   = longitude
        self.locality    = locality
        self.subLocality = subLocality
        self.address     = address
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.id          = try c.decode(String.self, forKey: .id)
        self.title       = try c.decodeIfPresent(String.self, forKey: .title)
        self.previewUri  = try c.decodeIfPresent(String.self, forKey: .previewUri)
        self.pdfUri      = try c.decode(String.self, forKey: .pdfUri)
        self.category    = try c.decodeIfPresent(String.self, forKey: .category)
        self.pageCount   = try c.decode(Int.self, forKey: .pageCount)
        self.createdAt   = try c.decode(String.self, forKey: .createdAt)
        // Tolerate rows where SELECT didn't pull `source` (e.g. an
        // older callsite that hasn't been updated) by defaulting
        // to "scan". The schema column is NOT NULL DEFAULT 'scan'
        // so a real DB row always has a value.
        self.source      = (try c.decodeIfPresent(String.self, forKey: .source)) ?? "scan"
        // Geolocation columns landed in v6 and remain nullable — a
        // SELECT that doesn't request them, or a row from before the
        // migration ran, reads back as `nil`. `address` joined them
        // in v7 with the same nullability story.
        self.latitude    = try c.decodeIfPresent(Double.self, forKey: .latitude)
        self.longitude   = try c.decodeIfPresent(Double.self, forKey: .longitude)
        self.locality    = try c.decodeIfPresent(String.self, forKey: .locality)
        self.subLocality = try c.decodeIfPresent(String.self, forKey: .subLocality)
        self.address     = try c.decodeIfPresent(String.self, forKey: .address)
    }

    public enum CodingKeys: String, CodingKey {
        case id
        case title
        case previewUri  = "preview_uri"
        case pdfUri      = "pdf_uri"
        case category
        case pageCount   = "page_count"
        case createdAt   = "created_at"
        case source
        case latitude
        case longitude
        case locality
        case subLocality = "sub_locality"
        case address
    }
}

@MainActor
public final class CaptureListViewModel: ObservableObject {

    @Published public private(set) var captures: [CaptureSummary] = []
    /// Lifetime sum of `page_count` across all of the user's
    /// non-deleted captures. Backs the sustainability hero on the
    /// home screen — kept here (alongside `captures`) because both
    /// reads watch the same `captures` table, so a single screen
    /// holds both observations rather than spawning a sibling VM.
    /// Mirror of Android's `captureDao.observeTotalPageCount(userId)`.
    @Published public private(set) var totalPageCount: Int = 0

    private let dbQueue: DatabaseQueue
    private let userId: String
    private var cancellable: AnyCancellable?
    private var totalCancellable: AnyCancellable?

    public init(database: QuickInkDatabase = .shared, userId: String) {
        self.dbQueue = database.dbQueue
        self.userId  = userId
    }

    public func start() {
        cancellable = ValueObservation.tracking { [userId] db in
            try CaptureSummary.fetchAll(db, sql: """
                SELECT id, title, preview_uri, pdf_uri, category, page_count, created_at, source
                FROM captures
                WHERE user_id = ? AND deleted_at IS NULL
                ORDER BY created_at DESC
                LIMIT 30
                """, arguments: [userId])
        }
        .publisher(in: dbQueue)
        .receive(on: DispatchQueue.main)
        .sink(
            receiveCompletion: { _ in },
            receiveValue: { [weak self] in self?.captures = $0 }
        )

        // Lifetime page total — drives the home sustainability hero.
        // `COALESCE(SUM(...), 0)` keeps the empty-library case as `0`
        // rather than NULL, so the hero's empty-state branch reads a
        // real Int without a separate Optional unwrap.
        totalCancellable = ValueObservation.tracking { [userId] db in
            try Int.fetchOne(db, sql: """
                SELECT COALESCE(SUM(page_count), 0)
                FROM captures
                WHERE user_id = ? AND deleted_at IS NULL
                """, arguments: [userId]) ?? 0
        }
        .publisher(in: dbQueue)
        .receive(on: DispatchQueue.main)
        .sink(
            receiveCompletion: { _ in },
            receiveValue: { [weak self] in self?.totalPageCount = $0 }
        )
    }
}
