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
    public let pageCount: Int
    public let createdAt: String
    /// `"scan"` (default) — went through VisionKit. `"import"` —
    /// came from the system photo picker. `"photo"` / `"video"` —
    /// came from QuickInk's camera media surfaces. Defaulting to `"scan"` keeps any
    /// search-result construction site (and rows synced from older
    /// clients) reading back as scans.
    public let source: String
    /// Page-size class — `"card"` (business cards, +4 pts/page),
    /// `"a4"` (default, +2 pts/page), or `"small"` (reserved for
    /// future smaller-than-A4 PDF imports, +1 pt/page). Free-form
    /// TEXT in the schema (v11), defaulting to `"a4"` for rows that
    /// predate the column or were synced from older clients.
    public let paperSize: String
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
    /// Free-form document-level notes (v13). Surfaced as the "Notes"
    /// card on `ScanDetailScreen`; appended to by the voice-note
    /// transcript editor and editable directly from the detail
    /// screen's notes editor. Nullable for back-compat; SELECTs that
    /// don't request the column read back as nil.
    public let notes: String?
    /// Workspace v1 — folder this capture lives in. Nullable
    /// at the column level so v8_workspace can backfill in a
    /// second pass; after the first-launch migration runs every
    /// capture is in Unsorted (or a user-created folder).
    public let folderId: String?
    /// Workspace v1 — ISO timestamp of the last time the user
    /// opened this capture. Powers the Continue card.
    public let lastOpenedAt: String?
    /// Workspace v1 — 1-indexed page the user last viewed.
    /// Paired with `lastOpenedAt`.
    public let lastOpenedPage: Int?
    /// Workspace v1 — install id of the producing device; reserved
    /// for future cross-device "continue on iPhone" UX.
    public let lastOpenedDevice: String?
    /// Raw video the hold-to-record Photo-mode path produced, kept
    /// as a re-watchable artifact on the detail screen (v16). Nil
    /// for every other source — document scans, business cards,
    /// gallery imports, and the still-tap photo path all land
    /// without a video. The detail screen reads this back to
    /// render an inline `AVPlayer`.
    public let videoUri: String?
    /// Drive file id of the per-row video binary upload (v17). Set
    /// after `QuickInkBinarySync.uploadAndCascade` mirrors the
    /// .mov / .mp4 to Drive. The detail screen pairs this with
    /// [videoUri] to discriminate between three states: real
    /// player (both set + local file exists), placeholder
    /// "downloading" card (drive id set, local file not yet
    /// downloaded), or no card at all (neither set).
    public let videoDriveFileId: String?
    /// User-facing favorite flag for Moments photos/videos. Defaults
    /// false for older SELECTs/payloads that don't include the column.
    public let isFavorite: Bool

    public init(
        id: String,
        title: String? = nil,
        previewUri: String?,
        pdfUri: String,
        pageCount: Int,
        createdAt: String,
        source: String = "scan",
        paperSize: String = "a4",
        latitude: Double? = nil,
        longitude: Double? = nil,
        locality: String? = nil,
        subLocality: String? = nil,
        address: String? = nil,
        notes: String? = nil,
        folderId: String? = nil,
        lastOpenedAt: String? = nil,
        lastOpenedPage: Int? = nil,
        lastOpenedDevice: String? = nil,
        videoUri: String? = nil,
        videoDriveFileId: String? = nil,
        isFavorite: Bool = false
    ) {
        self.id          = id
        self.title       = title
        self.previewUri  = previewUri
        self.pdfUri      = pdfUri
        self.pageCount   = pageCount
        self.createdAt   = createdAt
        self.source      = source
        self.paperSize   = paperSize
        self.latitude    = latitude
        self.longitude   = longitude
        self.locality    = locality
        self.subLocality = subLocality
        self.address     = address
        self.notes       = notes
        self.folderId         = folderId
        self.lastOpenedAt     = lastOpenedAt
        self.lastOpenedPage   = lastOpenedPage
        self.lastOpenedDevice = lastOpenedDevice
        self.videoUri         = videoUri
        self.videoDriveFileId = videoDriveFileId
        self.isFavorite       = isFavorite
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.id          = try c.decode(String.self, forKey: .id)
        self.title       = try c.decodeIfPresent(String.self, forKey: .title)
        self.previewUri  = try c.decodeIfPresent(String.self, forKey: .previewUri)
        self.pdfUri      = try c.decode(String.self, forKey: .pdfUri)
        self.pageCount   = try c.decode(Int.self, forKey: .pageCount)
        self.createdAt   = try c.decode(String.self, forKey: .createdAt)
        // Tolerate rows where SELECT didn't pull `source` (e.g. an
        // older callsite that hasn't been updated) by defaulting
        // to "scan". The schema column is NOT NULL DEFAULT 'scan'
        // so a real DB row always has a value.
        self.source      = (try c.decodeIfPresent(String.self, forKey: .source)) ?? "scan"
        // Same tolerance for `paper_size` (v11 column). Defaults to
        // "a4" so any SELECT that omits the column still constructs.
        self.paperSize   = (try c.decodeIfPresent(String.self, forKey: .paperSize)) ?? "a4"
        // Geolocation columns landed in v6 and remain nullable — a
        // SELECT that doesn't request them, or a row from before the
        // migration ran, reads back as `nil`. `address` joined them
        // in v7 with the same nullability story.
        self.latitude    = try c.decodeIfPresent(Double.self, forKey: .latitude)
        self.longitude   = try c.decodeIfPresent(Double.self, forKey: .longitude)
        self.locality    = try c.decodeIfPresent(String.self, forKey: .locality)
        self.subLocality = try c.decodeIfPresent(String.self, forKey: .subLocality)
        self.address     = try c.decodeIfPresent(String.self, forKey: .address)
        // `notes` landed in v13. Tolerate SELECTs that don't pull
        // the column — they read back as nil.
        self.notes       = try c.decodeIfPresent(String.self, forKey: .notes)
        // Workspace v1 columns landed in v8; older SELECTs that
        // don't request them — or rows synced from a pre-v8 client
        // — read back as nil here.
        self.folderId         = try c.decodeIfPresent(String.self, forKey: .folderId)
        self.lastOpenedAt     = try c.decodeIfPresent(String.self, forKey: .lastOpenedAt)
        self.lastOpenedPage   = try c.decodeIfPresent(Int.self,    forKey: .lastOpenedPage)
        self.lastOpenedDevice = try c.decodeIfPresent(String.self, forKey: .lastOpenedDevice)
        // `video_uri` landed in v16. Tolerate SELECTs that don't
        // request it — they read back as nil. Older rows that
        // predate the migration also read back as nil.
        self.videoUri         = try c.decodeIfPresent(String.self, forKey: .videoUri)
        // `video_drive_file_id` landed in v17. Same back-compat
        // tolerance.
        self.videoDriveFileId = try c.decodeIfPresent(String.self, forKey: .videoDriveFileId)
        // `is_favorite` lands after the Moments visual refresh.
        // Older SELECTs and payloads omit it; default to false.
        self.isFavorite       = (try c.decodeIfPresent(Bool.self, forKey: .isFavorite)) ?? false
    }

    public enum CodingKeys: String, CodingKey {
        case id
        case title
        case previewUri  = "preview_uri"
        case pdfUri      = "pdf_uri"
        case pageCount   = "page_count"
        case createdAt   = "created_at"
        case source
        case paperSize   = "paper_size"
        case latitude
        case longitude
        case locality
        case subLocality = "sub_locality"
        case address
        case notes
        case folderId         = "folder_id"
        case lastOpenedAt     = "last_opened_at"
        case lastOpenedPage   = "last_opened_page"
        case lastOpenedDevice = "last_opened_device"
        case videoUri         = "video_uri"
        case videoDriveFileId = "video_drive_file_id"
        case isFavorite       = "is_favorite"
    }
}

@MainActor
public final class CaptureListViewModel: ObservableObject {

    @Published public private(set) var captures: [CaptureSummary] = []
    /// Lifetime page counts grouped by `paper_size` across all of
    /// the user's non-deleted captures. Backs the sustainability
    /// hero on the home screen, which weights each bucket
    /// independently (card +4, A4 +2, smaller +1). Keys mirror the
    /// `PaperSize` raw values; missing keys are absent (treat as 0).
    /// Mirror of Android's `captureDao.observePagesBySize(userId)`.
    @Published public private(set) var pagesBySize: [String: Int] = [:]

    /// Convenience — total of `pagesBySize`. Preserves the previous
    /// `totalPageCount` API for the hero headline + empty-state
    /// branch, which only need the lifetime sheet count.
    public var totalPageCount: Int {
        pagesBySize.values.reduce(0, +)
    }

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
                SELECT id, title, preview_uri, pdf_uri, page_count, created_at,
                       source, paper_size, video_uri, video_drive_file_id,
                       is_favorite
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

        // Lifetime page totals grouped by `paper_size` — drives the
        // home sustainability hero, which weights each size bucket
        // independently (card +4, A4 +2, smaller +1). `GROUP BY`
        // collapses buckets that aren't represented yet, so a
        // freshly-installed library reads back as an empty dict;
        // missing keys are treated as 0 by the hero.
        totalCancellable = ValueObservation.tracking { [userId] db in
            try Row.fetchAll(db, sql: """
                SELECT paper_size, COALESCE(SUM(page_count), 0) AS pages
                FROM captures
                WHERE user_id = ? AND deleted_at IS NULL
                GROUP BY paper_size
                """, arguments: [userId])
        }
        .publisher(in: dbQueue)
        .receive(on: DispatchQueue.main)
        .sink(
            receiveCompletion: { _ in },
            receiveValue: { [weak self] rows in
                var buckets: [String: Int] = [:]
                for row in rows {
                    let key: String = row["paper_size"]
                    let pages: Int = row["pages"]
                    buckets[key] = pages
                }
                self?.pagesBySize = buckets
            }
        )
    }
}
