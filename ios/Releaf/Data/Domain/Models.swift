/*
 * Models.swift
 * Domain model — the shape of a Releaf notebook in memory.
 *
 * Persistence (Drive) formats are deliberately not Codable on these types.
 * The `DriveRepository` maps between these and the JSON payloads described
 * in docs/DRIVE_SCHEMA.md.
 */

import Foundation

// Note: `CaptureMode` now lives in ReleafDesignSystem since it's a UI concept
// (the 7 tappable rows in the capture sheet / tab bar) and no domain type
// references it. See DesignSystem/CaptureMode.swift.

/// Lifecycle state shown on the variant-1 shelves cards.
public enum NotebookStatus: String, Equatable, Sendable {
    case active
    case paused
    case archived
    case shared
}

/// Top-level container in the Shelf → Book → Chapter → Page hierarchy.
/// Every book belongs to exactly one shelf; the default shelf is
/// `"shelf-general"` and is seeded by migration on upgrade.
public struct Shelf: Identifiable, Equatable, Sendable {
    public let id: String
    public var name: String
    public var colorHex: String?
    public var position: Int
    public var updatedAt: Date

    public init(
        id: String,
        name: String,
        colorHex: String? = nil,
        position: Int = 0,
        updatedAt: Date = Date()
    ) {
        self.id = id
        self.name = name
        self.colorHex = colorHex
        self.position = position
        self.updatedAt = updatedAt
    }
}

/// Groups multiple `Notebook` rows into "volumes of the same book".
/// When a book has a single volume this type isn't materialized in
/// the UI — the notebook renders its title on its own.
public struct BookSeries: Identifiable, Equatable, Sendable {
    public let id: String
    public let shelfId: String
    public var name: String
    public var updatedAt: Date

    public init(id: String, shelfId: String, name: String, updatedAt: Date = Date()) {
        self.id = id
        self.shelfId = shelfId
        self.name = name
        self.updatedAt = updatedAt
    }
}

public struct Notebook: Identifiable, Equatable, Sendable {
    public let id: String
    public var title: String
    public var description: String?
    public var colorToken: String?
    public var position: Int
    public var archivedAt: Date?
    public var updatedAt: Date
    public var chapterCount: Int
    public var pageCount: Int

    /// Shelf grouping (e.g. "GARDEN"). Used by variant-1 shelves UI.
    public var shelfName: String?
    /// Volume number (e.g. 2). Used by variant-1 shelves UI.
    public var volumeNumber: Int?
    /// Lifecycle status. When nil, derived from `archivedAt`.
    public var status: NotebookStatus?
    /// Opaque key selecting the variant-1 hero-card icon. See
    /// `ShelfIcon` on iOS / Android for the supported values.
    public var iconKey: String?

    /// Shelf this book belongs to (FK to `Shelf.id`). Required in
    /// the schema; defaulted to the General shelf here so call
    /// sites that don't know the shelf yet still compile.
    public var shelfId: String
    /// Series this book is a volume of. Nil = single-volume book.
    public var seriesId: String?
    /// 1 for the only (or first) volume; 2+ for subsequent volumes.
    /// Distinct from `volumeNumber` (the display hint) which may be
    /// derived from `position` in older code paths.
    public var seriesVolumeNumber: Int
    /// Per-volume label (e.g. "2026"). Nil → UI composes
    /// "<series> vol <n>".
    public var volumeLabel: String?

    public init(
        id: String,
        title: String,
        description: String? = nil,
        colorToken: String? = nil,
        position: Int = 0,
        archivedAt: Date? = nil,
        updatedAt: Date = Date(),
        chapterCount: Int = 0,
        pageCount: Int = 0,
        shelfName: String? = nil,
        volumeNumber: Int? = nil,
        status: NotebookStatus? = nil,
        iconKey: String? = nil,
        shelfId: String = "shelf-general",
        seriesId: String? = nil,
        seriesVolumeNumber: Int = 1,
        volumeLabel: String? = nil
    ) {
        self.id = id
        self.title = title
        self.description = description
        self.colorToken = colorToken
        self.position = position
        self.archivedAt = archivedAt
        self.updatedAt = updatedAt
        self.chapterCount = chapterCount
        self.pageCount = pageCount
        self.shelfName = shelfName
        self.volumeNumber = volumeNumber
        self.status = status
        self.iconKey = iconKey
        self.shelfId = shelfId
        self.seriesId = seriesId
        self.seriesVolumeNumber = seriesVolumeNumber
        self.volumeLabel = volumeLabel
    }

    public var isArchived: Bool { archivedAt != nil }

    /// Resolved status: explicit `status` if set, else derived from `archivedAt`.
    public var resolvedStatus: NotebookStatus {
        if let status { return status }
        return isArchived ? .archived : .active
    }
}

public struct Chapter: Identifiable, Equatable, Sendable {
    public let id: String
    public let notebookId: String
    public var title: String
    public var position: Int
    public var updatedAt: Date
    public var pages: [PageSummary]

    public init(
        id: String,
        notebookId: String,
        title: String,
        position: Int = 0,
        updatedAt: Date = Date(),
        pages: [PageSummary] = []
    ) {
        self.id = id
        self.notebookId = notebookId
        self.title = title
        self.position = position
        self.updatedAt = updatedAt
        self.pages = pages
    }
}

public struct PageSummary: Identifiable, Equatable, Sendable {
    public let id: String
    public var title: String
    public var capturedOn: String?
    public var updatedAt: Date
    public var counts: PageCounts
    /// Free-form tags shown as pills on variant-1 page views.
    public var tags: [String]

    public init(
        id: String,
        title: String,
        capturedOn: String? = nil,
        updatedAt: Date = Date(),
        counts: PageCounts = .zero,
        tags: [String] = []
    ) {
        self.id = id
        self.title = title
        self.capturedOn = capturedOn
        self.updatedAt = updatedAt
        self.counts = counts
        self.tags = tags
    }
}

public struct PageCounts: Equatable, Sendable {
    public var photos: Int
    public var voiceNotes: Int
    public var todoItems: Int
    public var scannedDocuments: Int
    public var contacts: Int
    public var locations: Int

    public init(
        photos: Int = 0, voiceNotes: Int = 0, todoItems: Int = 0,
        scannedDocuments: Int = 0, contacts: Int = 0, locations: Int = 0
    ) {
        self.photos = photos
        self.voiceNotes = voiceNotes
        self.todoItems = todoItems
        self.scannedDocuments = scannedDocuments
        self.contacts = contacts
        self.locations = locations
    }

    public static let zero = PageCounts()

    public var total: Int {
        photos + voiceNotes + todoItems + scannedDocuments + contacts + locations
    }
}

// MARK: - Full page payload

public struct Page: Identifiable, Equatable, Sendable {
    public let id: String
    public let notebookId: String
    public let chapterId: String
    public var title: String
    public var capturedOn: String?
    public var updatedAt: Date
    public var notes: [Note]
    public var photos: [Photo]
    public var voiceNotes: [VoiceNote]
    public var todoItems: [TodoItem]
    public var scannedDocuments: [ScannedDocument]
    public var contacts: [Contact]
    public var locations: [LocationPin]
    /// Free-form tags shown as pills on variant-1 page views.
    public var tags: [String]

    public init(
        id: String,
        notebookId: String,
        chapterId: String,
        title: String,
        capturedOn: String? = nil,
        updatedAt: Date = Date(),
        notes: [Note] = [],
        photos: [Photo] = [],
        voiceNotes: [VoiceNote] = [],
        todoItems: [TodoItem] = [],
        scannedDocuments: [ScannedDocument] = [],
        contacts: [Contact] = [],
        locations: [LocationPin] = [],
        tags: [String] = []
    ) {
        self.id = id
        self.notebookId = notebookId
        self.chapterId = chapterId
        self.title = title
        self.capturedOn = capturedOn
        self.updatedAt = updatedAt
        self.notes = notes
        self.photos = photos
        self.voiceNotes = voiceNotes
        self.todoItems = todoItems
        self.scannedDocuments = scannedDocuments
        self.contacts = contacts
        self.locations = locations
        self.tags = tags
    }

    public var counts: PageCounts {
        PageCounts(
            photos: photos.count,
            voiceNotes: voiceNotes.count,
            todoItems: todoItems.count,
            scannedDocuments: scannedDocuments.count,
            contacts: contacts.count,
            locations: locations.count
        )
    }
}

public struct Note: Identifiable, Equatable, Sendable {
    public let id: String
    public var body: String
    public var createdAt: Date

    public init(id: String, body: String, createdAt: Date = Date()) {
        self.id = id
        self.body = body
        self.createdAt = createdAt
    }
}

public struct Photo: Identifiable, Equatable, Sendable {
    public let id: String
    public var driveFileId: String?
    public var caption: String?
    public var capturedAt: Date
    public var width: Int?
    public var height: Int?

    public init(
        id: String,
        driveFileId: String? = nil,
        caption: String? = nil,
        capturedAt: Date = Date(),
        width: Int? = nil,
        height: Int? = nil
    ) {
        self.id = id
        self.driveFileId = driveFileId
        self.caption = caption
        self.capturedAt = capturedAt
        self.width = width
        self.height = height
    }
}

public struct VoiceNote: Identifiable, Equatable, Sendable {
    public let id: String
    public var driveFileId: String?
    public var durationMs: Int
    public var recordedAt: Date
    public var transcription: String?

    public init(
        id: String,
        driveFileId: String? = nil,
        durationMs: Int,
        recordedAt: Date = Date(),
        transcription: String? = nil
    ) {
        self.id = id
        self.driveFileId = driveFileId
        self.durationMs = durationMs
        self.recordedAt = recordedAt
        self.transcription = transcription
    }
}

public struct TodoItem: Identifiable, Equatable, Sendable {
    public let id: String
    public var body: String
    public var done: Bool
    public var position: Int

    public init(id: String, body: String, done: Bool = false, position: Int = 0) {
        self.id = id
        self.body = body
        self.done = done
        self.position = position
    }
}

public struct ScannedDocument: Identifiable, Equatable, Sendable {
    public let id: String
    public var driveFileId: String?
    public var title: String
    public var pageCount: Int
    public var scannedAt: Date

    public init(
        id: String,
        driveFileId: String? = nil,
        title: String,
        pageCount: Int = 1,
        scannedAt: Date = Date()
    ) {
        self.id = id
        self.driveFileId = driveFileId
        self.title = title
        self.pageCount = pageCount
        self.scannedAt = scannedAt
    }
}

public struct Contact: Identifiable, Equatable, Sendable {
    public let id: String
    public var name: String
    public var phone: String?
    public var email: String?
    public var notes: String?

    public init(id: String, name: String, phone: String? = nil, email: String? = nil, notes: String? = nil) {
        self.id = id
        self.name = name
        self.phone = phone
        self.email = email
        self.notes = notes
    }
}

public struct LocationPin: Identifiable, Equatable, Sendable {
    public let id: String
    public var name: String
    public var latitude: Double
    public var longitude: Double
    public var capturedAt: Date
    public var notes: String?

    public init(
        id: String,
        name: String,
        latitude: Double,
        longitude: Double,
        capturedAt: Date = Date(),
        notes: String? = nil
    ) {
        self.id = id
        self.name = name
        self.latitude = latitude
        self.longitude = longitude
        self.capturedAt = capturedAt
        self.notes = notes
    }
}
