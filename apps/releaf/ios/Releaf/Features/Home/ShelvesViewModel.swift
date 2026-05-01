/*
 * ShelvesViewModel.swift
 *
 * Backs the variant-1 "Your shelves" screen with real Room/GRDB
 * data. Observes two streams: the shelves-with-notebook-counts
 * join (`NotebookRepository.observeShelves()`) and the live shelf
 * list (`ShelfRepository.observeActive()`). Exposes shelf-aware
 * create actions so the book-creation sheet can route through
 * `createBookInNewSeries` or `createNotebook(shelfId:)`.
 */

import Foundation
import ReleafData

@MainActor
public final class ShelvesViewModel: ObservableObject {

    public struct LoadedState: Equatable {
        public let notebooks: [Notebook]
        public let shelves: [Shelf]
        public let captureCounts: CaptureCountsByMode
    }

    public enum State: Equatable {
        case loading
        case loaded(LoadedState)
    }

    @Published public private(set) var state: State = .loading

    private let notebookRepository: NotebookRepository
    private let shelfRepository: ShelfRepository

    private var tasks: [Task<Void, Never>] = []
    private var latestRecords: [ShelfRecord] = []
    private var latestShelves: [ShelfEntity] = []

    public init(
        notebookRepository: NotebookRepository = NotebookRepository(),
        shelfRepository: ShelfRepository = ShelfRepository()
    ) {
        self.notebookRepository = notebookRepository
        self.shelfRepository = shelfRepository
    }

    public func start() {
        stop()
        tasks.append(Task { [weak self, notebookRepository] in
            guard let self else { return }
            do {
                for try await records in notebookRepository.observeShelves() {
                    self.latestRecords = records
                    self.recompute()
                }
            } catch {}
        })
        tasks.append(Task { [weak self, shelfRepository] in
            guard let self else { return }
            do {
                for try await shelves in shelfRepository.observeActive() {
                    self.latestShelves = shelves
                    self.recompute()
                }
            } catch {}
        })
    }

    public func stop() {
        tasks.forEach { $0.cancel() }
        tasks.removeAll()
    }

    deinit { tasks.forEach { $0.cancel() } }

    // MARK: - Actions

    /// Create a standalone book on the given shelf.
    public func createNotebook(
        title: String = "",
        shelfId: String? = nil,
        onCreated: @escaping (String) -> Void = { _ in }
    ) {
        let trimmed = title.trimmingCharacters(in: .whitespacesAndNewlines)
        let resolvedTitle = trimmed.isEmpty ? "Untitled notebook" : trimmed
        let resolvedShelf = shelfId.flatMap { $0.isEmpty ? nil : $0 } ?? ShelfEntity.defaultGeneralId
        Task { [notebookRepository] in
            if let created = try? await notebookRepository.createNotebook(
                title:   resolvedTitle,
                shelfId: resolvedShelf
            ) {
                await MainActor.run { onCreated(created.id) }
            }
        }
    }

    /// Create a book and its series in one call — use when the
    /// user opts into "this book will have volumes" up front.
    public func createBookInNewSeries(
        shelfId: String,
        seriesName: String,
        volumeName: String? = nil,
        onCreated: @escaping (String) -> Void = { _ in }
    ) {
        let resolvedShelf = shelfId.isEmpty ? ShelfEntity.defaultGeneralId : shelfId
        let trimmed = seriesName.trimmingCharacters(in: .whitespacesAndNewlines)
        let resolvedName = trimmed.isEmpty ? "Untitled book" : trimmed
        Task { [notebookRepository] in
            if let created = try? await notebookRepository.createBookInNewSeries(
                shelfId:    resolvedShelf,
                seriesName: resolvedName,
                volumeName: volumeName
            ) {
                await MainActor.run { onCreated(created.id) }
            }
        }
    }

    /// Add a new volume under an existing series.
    public func addVolumeToSeries(
        seriesId: String,
        volumeName: String? = nil,
        onCreated: @escaping (String) -> Void = { _ in }
    ) {
        Task { [notebookRepository] in
            if let created = try? await notebookRepository.addVolumeToSeries(
                seriesId:   seriesId,
                volumeName: volumeName
            ) {
                await MainActor.run { onCreated(created.id) }
            }
        }
    }

    /// Create a fresh shelf — appears in the picker via the
    /// observer stream immediately.
    public func createShelf(
        name: String,
        onCreated: @escaping (String) -> Void = { _ in }
    ) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let resolved = trimmed.isEmpty ? "Untitled shelf" : trimmed
        Task { [shelfRepository] in
            if let shelf = try? await shelfRepository.createShelf(name: resolved) {
                await MainActor.run { onCreated(shelf.id) }
            }
        }
    }

    // MARK: - Recompute

    private func recompute() {
        let shelfNameById = Dictionary(uniqueKeysWithValues: latestShelves.map { ($0.id, $0.name) })
        let notebooks = latestRecords.enumerated().map { index, record in
            Self.mapRecord(
                record,
                index: index,
                resolvedShelfName: shelfNameById[record.notebook.shelfId]
            )
        }
        let shelves = latestShelves.map { Shelf(
            id:        $0.id,
            name:      $0.name,
            colorHex:  $0.colorHex,
            position:  Int($0.position),
            updatedAt: Self.parseISO($0.updatedAt) ?? Date(timeIntervalSince1970: 0)
        ) }
        // Derive aggregate capture counts from the per-notebook
        // summaries already in `latestRecords`. Pages stand in for
        // "notes"; photos/scans/voice/contacts will populate once
        // the captures table migration lands.
        let captureCounts = CaptureCountsByMode(
            notes: latestRecords.reduce(0) { $0 + $1.pageCount }
        )
        state = .loaded(LoadedState(
            notebooks:     notebooks,
            shelves:       shelves,
            captureCounts: captureCounts
        ))
    }

    // MARK: - Mapping

    private static func mapRecord(
        _ record: ShelfRecord,
        index: Int,
        resolvedShelfName: String?
    ) -> Notebook {
        let entity = record.notebook
        let token  = colorTokenFor(hex: entity.colorHex, fallbackIndex: index)
        // Prefer the real shelf name; fall back to first-word-of-title
        // for rows whose shelf got renamed or deleted between two
        // observed snapshots (rare but possible inside the join).
        let shelfDisplayName: String = {
            if let name = resolvedShelfName?.trimmingCharacters(in: .whitespacesAndNewlines),
               !name.isEmpty {
                return name.uppercased()
            }
            return shelfNameDisplay(for: entity.title)
        }()
        return Notebook(
            id:                 entity.id,
            title:              entity.title,
            description:        nil,
            colorToken:         token,
            position:           index,
            archivedAt:         nil,
            updatedAt:          parseISO(entity.updatedAt) ?? Date(timeIntervalSince1970: 0),
            chapterCount:       record.chapterCount,
            pageCount:          record.pageCount,
            shelfName:          shelfDisplayName,
            volumeNumber:       entity.volumeNumber,
            status:             .active,
            iconKey:            iconKey(for: token),
            shelfId:            entity.shelfId,
            seriesId:           entity.seriesId,
            seriesVolumeNumber: entity.volumeNumber,
            volumeLabel:        entity.volumeName
        )
    }

    private static func parseISO(_ s: String) -> Date? {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let d = f.date(from: s) { return d }
        f.formatOptions = [.withInternetDateTime]
        return f.date(from: s)
    }
}

// MARK: - Derivation helpers

private let iosShelfRotation = ["green", "info", "dry", "coral", "yellow"]

private func colorTokenFor(hex: String?, fallbackIndex: Int) -> String {
    switch hex?.uppercased().replacingOccurrences(of: "#", with: "") {
    case "7AA874": return "green"
    case "E07856": return "coral"
    case "F4C430": return "yellow"
    case "B8956A": return "dry"
    case "8E86DB", "6E66BB": return "info"
    default:
        return iosShelfRotation[fallbackIndex % iosShelfRotation.count]
    }
}

private func iconKey(for colorToken: String) -> String {
    switch colorToken {
    case "green":  return "plant"
    case "info":   return "chart"
    case "dry":    return "sun"
    case "coral":  return "book"
    case "yellow": return "sun"
    default:       return "plant"
    }
}

private func shelfNameDisplay(for title: String) -> String {
    let trimmed = title.trimmingCharacters(in: .whitespacesAndNewlines)
    if trimmed.isEmpty { return "NOTEBOOK" }
    let head = trimmed
        .split(separator: " ", maxSplits: 1, omittingEmptySubsequences: true)
        .first.map(String.init) ?? trimmed
    let clean = head.split(separator: "—").first.map(String.init) ?? head
    let upper = clean.uppercased()
    return upper.isEmpty ? "NOTEBOOK" : upper
}

private func volumeNumberDisplay(from position: Int64, fallbackIndex: Int) -> Int {
    if position <= 0 { return fallbackIndex + 1 }
    let derived = max(Int(position / 1024), 1)
    return derived > 99 ? fallbackIndex + 1 : derived
}
