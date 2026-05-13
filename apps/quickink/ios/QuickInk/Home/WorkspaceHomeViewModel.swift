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
    @Published public private(set) var continueCandidate: CaptureSummary? = nil
    @Published public private(set) var folderCaptureCounts: [String: Int] = [:]

    private let dbQueue: DatabaseQueue
    private let userId: String
    private let folderRepository: FolderRepository
    private var folderCancellable: AnyCancellable?
    private var tagCancellable: AnyCancellable?
    private var tagCountCancellable: AnyCancellable?
    private var smartCancellable: AnyCancellable?
    private var continueCancellable: AnyCancellable?
    private var folderCountCancellable: AnyCancellable?

    public init(userId: String, database: QuickInkDatabase = .shared) {
        self.userId = userId
        self.dbQueue = database.dbQueue
        self.folderRepository = FolderRepository(database: database)
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

        continueCancellable = ValueObservation.tracking { [userId] db in
            try CaptureSummary.fetchOne(db, sql: """
                SELECT id, title, preview_uri, pdf_uri, category, page_count, created_at, source,
                       latitude, longitude, locality, sub_locality, address,
                       folder_id, last_opened_at, last_opened_page, last_opened_device
                FROM captures
                WHERE user_id = ?
                  AND last_opened_at IS NOT NULL
                  AND deleted_at IS NULL
                ORDER BY last_opened_at DESC
                LIMIT 1
                """, arguments: [userId])
        }
        .publisher(in: dbQueue)
        .receive(on: DispatchQueue.main)
        .sink(receiveCompletion: { _ in }, receiveValue: { [weak self] in
            self?.continueCandidate = $0
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
}
