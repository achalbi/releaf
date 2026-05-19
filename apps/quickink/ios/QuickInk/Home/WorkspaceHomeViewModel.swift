/*
 * WorkspaceHomeViewModel.swift
 *
 * ObservableObject that backs `WorkspaceHomeScreen`. Wraps the
 * GRDB ValueObservation publishers for folders, tags, tag counts,
 * smart collections, the most-recently-opened capture, and the
 * per-folder capture counts. Folder writes (create / rename /
 * recolor / soft-delete) call through to `FolderRepository`.
 *
 * Mirror of the Android `WorkspaceHomeScreen` produceState
 * subscriptions.
 */

import Foundation
import Combine
import GRDB

@MainActor
public final class WorkspaceHomeViewModel: ObservableObject {

    @Published public private(set) var folders: [FolderEntity] = []
    @Published public private(set) var tags: [TagEntity] = []
    @Published public private(set) var tagCounts: [TagCount] = []
    @Published public private(set) var smartCollections: [SmartCollectionEntity] = []
    @Published public private(set) var locations: [LocationEntity] = []
    @Published public private(set) var locationCounts: [String: Int] = [:]
    @Published public private(set) var people: [PersonEntity] = []
    @Published public private(set) var personCounts: [String: Int] = [:]
    /// Most-recently-opened captures (newest first), capped at
    /// [recentlyOpenedLimit]. Row 0 renders as the Continue hero;
    /// rows 1..N feed the compact strip below it.
    @Published public private(set) var recentlyOpened: [CaptureSummary] = []

    /// Hero + carousel cap. Matches Android's `RECENTLY_OPENED_LIMIT`.
    public static let recentlyOpenedLimit: Int = 6
    @Published public private(set) var folderCaptureCounts: [String: Int] = [:]
    /// Per-folder count of captures created in the last 7 days.
    /// Drives the Workspace home folder list's "N new" badge.
    @Published public private(set) var folderNewCounts: [String: Int] = [:]

    private let dbQueue: DatabaseQueue
    private let userId: String
    private let folderRepository: FolderRepository
    private let locationRepository: LocationRepository
    private let personRepository: PersonRepository
    private var folderCancellable: AnyCancellable?
    private var tagCancellable: AnyCancellable?
    private var tagCountCancellable: AnyCancellable?
    private var smartCancellable: AnyCancellable?
    private var continueCancellable: AnyCancellable?
    private var folderCountCancellable: AnyCancellable?
    private var folderNewCountCancellable: AnyCancellable?
    private var locationCancellable: AnyCancellable?
    private var locationCountCancellable: AnyCancellable?
    private var personCancellable: AnyCancellable?
    private var personCountCancellable: AnyCancellable?

    public init(userId: String, database: QuickInkDatabase = .shared) {
        self.userId = userId
        self.dbQueue = database.dbQueue
        self.folderRepository = FolderRepository(database: database)
        self.locationRepository = LocationRepository(database: database)
        self.personRepository = PersonRepository(database: database)
    }

    public func start() {
        guard folderCancellable == nil else { return }

        folderCancellable = ValueObservation.tracking { [userId] db in
            try FolderEntity
                .filter(Column("user_id") == userId)
                .filter(Column("deleted_at") == nil)
                .order(Column("position").asc, Column("name").asc)
                .fetchAll(db)
        }
        .publisher(in: dbQueue)
        .receive(on: DispatchQueue.main)
        .sink(receiveCompletion: { _ in }, receiveValue: { [weak self] in
            self?.folders = $0
        })

        tagCancellable = ValueObservation.tracking { [userId] db in
            try TagEntity
                .filter(Column("user_id") == userId)
                .filter(Column("deleted_at") == nil)
                .order(Column("position").asc, Column("name").asc)
                .fetchAll(db)
        }
        .publisher(in: dbQueue)
        .receive(on: DispatchQueue.main)
        .sink(receiveCompletion: { _ in }, receiveValue: { [weak self] in
            self?.tags = $0
        })

        tagCountCancellable = ValueObservation.tracking { [userId] db in
            try TagCount.fetchAll(db, sql: """
                SELECT capture_tags.tag_id AS tag_id, COUNT(*) AS doc_count
                FROM capture_tags
                JOIN captures ON captures.id = capture_tags.capture_id
                WHERE capture_tags.deleted_at IS NULL
                  AND captures.deleted_at IS NULL
                  AND captures.user_id = ?
                GROUP BY capture_tags.tag_id
                """, arguments: [userId])
        }
        .publisher(in: dbQueue)
        .receive(on: DispatchQueue.main)
        .sink(receiveCompletion: { _ in }, receiveValue: { [weak self] in
            self?.tagCounts = $0
        })

        smartCancellable = ValueObservation.tracking { [userId] db in
            try SmartCollectionEntity
                .filter(Column("user_id") == userId)
                .filter(Column("deleted_at") == nil)
                .order(Column("position").asc, Column("name").asc)
                .fetchAll(db)
        }
        .publisher(in: dbQueue)
        .receive(on: DispatchQueue.main)
        .sink(receiveCompletion: { _ in }, receiveValue: { [weak self] in
            self?.smartCollections = $0
        })

        let limit = Self.recentlyOpenedLimit
        continueCancellable = ValueObservation.tracking { [userId] db in
            try CaptureSummary.fetchAll(db, sql: """
                SELECT id, title, preview_uri, pdf_uri, page_count, created_at, source,
                       latitude, longitude, locality, sub_locality, address,
                       folder_id, last_opened_at, last_opened_page, last_opened_device
                FROM captures
                WHERE user_id = ?
                  AND last_opened_at IS NOT NULL
                  AND deleted_at IS NULL
                ORDER BY last_opened_at DESC
                LIMIT ?
                """, arguments: [userId, limit])
        }
        .publisher(in: dbQueue)
        .receive(on: DispatchQueue.main)
        .sink(receiveCompletion: { _ in }, receiveValue: { [weak self] in
            self?.recentlyOpened = $0
        })

        // "N new" — captures created in the last 7 days, grouped
        // by folder. ISO-8601 string compare on created_at is
        // chronologically correct.
        let sinceIso: String = {
            let formatter = ISO8601DateFormatter()
            formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
            let cutoff = Date().addingTimeInterval(-7 * 24 * 60 * 60)
            return formatter.string(from: cutoff)
        }()
        folderNewCountCancellable = ValueObservation.tracking { [userId] db -> [String: Int] in
            struct Pair: Codable, FetchableRecord {
                let folderId: String
                let count: Int
                enum CodingKeys: String, CodingKey {
                    case folderId = "folder_id"
                    case count
                }
            }
            let rows = try Pair.fetchAll(db, sql: """
                SELECT folder_id, COUNT(*) AS count
                FROM captures
                WHERE user_id = ?
                  AND folder_id IS NOT NULL
                  AND deleted_at IS NULL
                  AND created_at >= ?
                GROUP BY folder_id
                """, arguments: [userId, sinceIso])
            var out: [String: Int] = [:]
            for r in rows { out[r.folderId] = r.count }
            return out
        }
        .publisher(in: dbQueue)
        .receive(on: DispatchQueue.main)
        .sink(receiveCompletion: { _ in }, receiveValue: { [weak self] in
            self?.folderNewCounts = $0
        })

        folderCountCancellable = ValueObservation.tracking { [userId] db -> [String: Int] in
            struct Pair: Codable, FetchableRecord {
                let folderId: String
                let count: Int
                enum CodingKeys: String, CodingKey {
                    case folderId = "folder_id"
                    case count
                }
            }
            let rows = try Pair.fetchAll(db, sql: """
                SELECT folder_id, COUNT(*) AS count
                FROM captures
                WHERE user_id = ?
                  AND folder_id IS NOT NULL
                  AND deleted_at IS NULL
                GROUP BY folder_id
                """, arguments: [userId])
            var out: [String: Int] = [:]
            for r in rows { out[r.folderId] = r.count }
            return out
        }
        .publisher(in: dbQueue)
        .receive(on: DispatchQueue.main)
        .sink(receiveCompletion: { _ in }, receiveValue: { [weak self] in
            self?.folderCaptureCounts = $0
        })

        // ─── Places (user-defined locations) ───────────────────
        locationCancellable = locationRepository.observe(userId: userId)
            .receive(on: DispatchQueue.main)
            .sink(receiveCompletion: { _ in }, receiveValue: { [weak self] in
                self?.locations = $0
            })

        locationCountCancellable = locationRepository.observeLocationCounts(userId: userId)
            .receive(on: DispatchQueue.main)
            .sink(receiveCompletion: { _ in }, receiveValue: { [weak self] counts in
                var map: [String: Int] = [:]
                for c in counts { map[c.locationId] = c.docCount }
                self?.locationCounts = map
            })

        // ─── People (user-defined people) ──────────────────────
        personCancellable = personRepository.observe(userId: userId)
            .receive(on: DispatchQueue.main)
            .sink(receiveCompletion: { _ in }, receiveValue: { [weak self] in
                self?.people = $0
            })

        personCountCancellable = personRepository.observePersonCounts(userId: userId)
            .receive(on: DispatchQueue.main)
            .sink(receiveCompletion: { _ in }, receiveValue: { [weak self] counts in
                var map: [String: Int] = [:]
                for c in counts { map[c.personId] = c.docCount }
                self?.personCounts = map
            })
    }

    public func rankedTags(limit: Int) -> [(TagEntity, Int)] {
        let countById = Dictionary(uniqueKeysWithValues: tagCounts.map { ($0.tagId, $0.docCount) })
        return tags
            .map { ($0, countById[$0.id] ?? 0) }
            .sorted { $0.1 > $1.1 }
            .prefix(limit)
            .map { $0 }
    }

    // MARK: - Folder writes (Phase B.1)

    @discardableResult
    public func createFolder(name: String, color: String) async throws -> FolderEntity? {
        try await folderRepository.create(
            userId:   userId,
            name:     name,
            color:    color,
            position: folders.count,
        )
    }

    public func renameFolder(id: String, newName: String) async throws {
        try await folderRepository.rename(id: id, newName: newName)
    }

    public func setFolderColor(id: String, color: String) async throws {
        try await folderRepository.setColor(id: id, color: color)
    }

    public func softDeleteFolder(folderId: String) async throws {
        try await folderRepository.softDelete(userId: userId, folderId: folderId)
    }

    // MARK: - Place writes

    @discardableResult
    public func createLocation(
        name: String,
        address: String? = nil,
        latitude: Double? = nil,
        longitude: Double? = nil
    ) async throws -> Bool {
        try await locationRepository.insert(
            userId:    userId,
            name:      name,
            position:  locations.count,
            latitude:  latitude,
            longitude: longitude,
            address:   address
        )
    }

    public func renameLocation(id: String, newName: String) async throws {
        try await locationRepository.rename(id: id, newName: newName)
    }

    public func setLocationCoordinates(
        id: String,
        latitude: Double?,
        longitude: Double?,
        address: String?
    ) async throws {
        try await locationRepository.setCoordinates(
            id:        id,
            latitude:  latitude,
            longitude: longitude,
            address:   address
        )
    }

    public func softDeleteLocation(id: String) async throws {
        try await locationRepository.softDelete(id: id)
    }

    // MARK: - Person writes

    @discardableResult
    public func createPerson(
        name: String,
        phone: String? = nil,
        email: String? = nil,
        contactLookupKey: String? = nil,
        contactPhotoUri: String? = nil
    ) async throws -> Bool {
        try await personRepository.insert(
            userId:           userId,
            name:             name,
            position:         people.count,
            contactLookupKey: contactLookupKey,
            contactPhone:     phone,
            contactEmail:     email,
            contactPhotoUri:  contactPhotoUri
        )
    }

    public func renamePerson(id: String, newName: String) async throws {
        try await personRepository.rename(id: id, newName: newName)
    }

    public func setPersonContact(
        id: String,
        lookupKey: String?,
        phone: String?,
        email: String?,
        photoUri: String?
    ) async throws {
        try await personRepository.setContactLink(
            id:        id,
            lookupKey: lookupKey,
            phone:     phone,
            email:     email,
            photoUri:  photoUri
        )
    }

    public func softDeletePerson(id: String) async throws {
        try await personRepository.softDelete(id: id)
    }
}
