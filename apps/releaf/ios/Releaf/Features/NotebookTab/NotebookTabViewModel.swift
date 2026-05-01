/*
 * NotebookTabViewModel.swift
 *
 * Drives the classic notebook tab from the local GRDB-backed store.
 * The screen needs two live feeds:
 *   - active notebooks joined with chapter/page counts (`observeShelves`)
 *   - page-content search hits with notebook/chapter context
 *
 * Search is intentionally split: notebook cards filter by title, while a
 * second result list shows full-text page matches. That keeps the tab
 * faithful to the data the local schema currently exposes on iOS.
 */

import Foundation
import Combine
import SwiftUI
import ReleafData
import ReleafDesignSystem

@MainActor
public final class NotebookTabViewModel: ObservableObject {

    public struct Metrics: Equatable {
        public let notebooks: Int
        public let chapters: Int
        public let pages: Int
    }

    /// `NotebookSortMode` lives in DesignSystem (persisted via
    /// UiPreferences) so the picker survives a cold start. Re-export
    /// here as a typealias so existing call sites that read
    /// `NotebookTabViewModel.SortMode` keep compiling.
    public typealias SortMode = NotebookSortMode

    @Published public private(set) var shelves: [ShelfRecord] = []
    @Published public private(set) var shelfDirectory: [Shelf] = []
    @Published public private(set) var matchingPages: [PageSearchHit] = []
    @Published public private(set) var isLoading: Bool = true
    /// Persisted across launches via UiPreferences. Reading the
    /// initial value here means a freshly-mounted view shows the
    /// user's last sort without flashing through the default.
    @Published public var sortMode: SortMode = UiPreferences.shared.state.notebookSortMode {
        didSet {
            if oldValue != sortMode { UiPreferences.shared.setNotebookSortMode(sortMode) }
        }
    }
    @Published public var query: String = "" {
        didSet {
            if oldValue != query { reloadSearch() }
        }
    }

    private let debounceNanos: UInt64 = 150_000_000

    private let notebookRepository: NotebookRepository
    private let pageRepository: PageRepository
    private let shelfRepository: ShelfRepository

    private var shelvesTask: Task<Void, Never>?
    private var shelfDirectoryTask: Task<Void, Never>?
    private var searchTask: Task<Void, Never>?

    public init(
        notebookRepository: NotebookRepository = NotebookRepository(),
        pageRepository: PageRepository = PageRepository(),
        shelfRepository: ShelfRepository = ShelfRepository()
    ) {
        self.notebookRepository = notebookRepository
        self.pageRepository = pageRepository
        self.shelfRepository = shelfRepository
    }

    public func start() {
        if shelvesTask == nil { observeShelves() }
        if shelfDirectoryTask == nil { observeShelfDirectory() }
        reloadSearch()
    }

    public func stop() {
        shelvesTask?.cancel()
        shelvesTask = nil
        shelfDirectoryTask?.cancel()
        shelfDirectoryTask = nil
        searchTask?.cancel()
        searchTask = nil
    }

    public var metrics: Metrics {
        Metrics(
            notebooks: shelves.count,
            chapters: shelves.reduce(0) { $0 + $1.chapterCount },
            pages: shelves.reduce(0) { $0 + $1.pageCount }
        )
    }

    public var filteredShelves: [ShelfRecord] {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        let base: [ShelfRecord]
        if trimmed.isEmpty {
            base = shelves
        } else {
            base = shelves.filter {
                $0.notebook.title.localizedCaseInsensitiveContains(trimmed)
            }
        }
        switch sortMode {
        case .recent:
            return base.sorted { $0.notebook.updatedAt > $1.notebook.updatedAt }
        case .name:
            return base.sorted {
                $0.notebook.title.localizedCaseInsensitiveCompare($1.notebook.title) == .orderedAscending
            }
        case .pages:
            return base.sorted { $0.pageCount > $1.pageCount }
        }
    }

    public func clearQuery() {
        query = ""
    }

    public func addNotebook(
        title: String,
        colorHex: String? = nil,
        colorToken: String? = nil,
        shelfId: String? = nil
    ) {
        let trimmed = title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        let resolvedShelf = shelfId.flatMap { $0.isEmpty ? nil : $0 }
            ?? ShelfEntity.defaultGeneralId
        // Caller may pass either a leaf-theme token (preferred) or a
        // raw hex string (legacy). Token wins when both are set.
        let resolvedHex = colorToken.flatMap(Self.themeHex(forToken:)) ?? colorHex
        Task { [notebookRepository] in
            _ = try? await notebookRepository.createNotebook(
                title:    trimmed,
                colorHex: resolvedHex,
                shelfId:  resolvedShelf
            )
        }
    }

    /// Create a fresh shelf — wired to the "+ New shelf…" row in
    /// the notebook create sheet *and* the overflow's New shelf
    /// item. The optional `colorToken` lets the caller pick one of
    /// the four leaf themes; we convert it to the hex string the
    /// shelf repository expects so persistence stays a thin layer.
    public func createShelf(
        name: String,
        colorToken: String? = nil,
        onCreated: @escaping (String) -> Void = { _ in }
    ) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let resolved = trimmed.isEmpty ? "Untitled shelf" : trimmed
        let hex = colorToken.flatMap(Self.themeHex(forToken:))
        Task { [shelfRepository] in
            if let shelf = try? await shelfRepository.createShelf(
                name: resolved,
                colorHex: hex
            ) {
                await MainActor.run { onCreated(shelf.id) }
            }
        }
    }

    /// Map a leaf-theme token ("coral" / "green" / "yellow" / "dry")
    /// to the hex string the shelf entity stores. Source of truth
    /// is `ShelfTheme.palette(...)` so any color-token change in
    /// the design system flows through.
    private static func themeHex(forToken token: String) -> String? {
        let palette = ShelfTheme.palette(for: token)
        // Drop alpha; shelves store opaque RGB strings.
        return Self.hexString(from: palette.background)
    }

    private static func hexString(from color: Color) -> String {
        // Lossy round-trip through UIColor; close enough for the
        // four flat theme primaries since none use alpha.
        #if canImport(UIKit)
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        UIColor(color).getRed(&r, green: &g, blue: &b, alpha: &a)
        return String(
            format: "#%02X%02X%02X",
            Int(r * 255), Int(g * 255), Int(b * 255)
        )
        #else
        return "#000000"
        #endif
    }

    public func delete(notebookId: String) {
        Task { [notebookRepository] in
            try? await notebookRepository.softDeleteNotebook(id: notebookId)
        }
    }

    private func observeShelves() {
        shelvesTask?.cancel()
        shelvesTask = Task { [weak self] in
            guard let self else { return }
            do {
                for try await list in self.notebookRepository.observeShelves() {
                    if Task.isCancelled { break }
                    self.shelves = list
                    self.isLoading = false
                }
            } catch is CancellationError {
                // View left the hierarchy.
            } catch {
                self.shelves = []
                self.isLoading = false
            }
        }
    }

    private func observeShelfDirectory() {
        shelfDirectoryTask?.cancel()
        shelfDirectoryTask = Task { [weak self] in
            guard let self else { return }
            do {
                for try await list in self.shelfRepository.observeActive() {
                    if Task.isCancelled { break }
                    self.shelfDirectory = list.map {
                        Shelf(
                            id:        $0.id,
                            name:      $0.name,
                            colorHex:  $0.colorHex,
                            position:  Int($0.position),
                            updatedAt: Self.parseISO($0.updatedAt) ?? Date(timeIntervalSince1970: 0)
                        )
                    }
                }
            } catch is CancellationError {
                // View left the hierarchy.
            } catch {
                self.shelfDirectory = []
            }
        }
    }

    private static func parseISO(_ s: String) -> Date? {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let d = f.date(from: s) { return d }
        f.formatOptions = [.withInternetDateTime]
        return f.date(from: s)
    }

    private func reloadSearch() {
        searchTask?.cancel()
        let q = query

        searchTask = Task { [weak self] in
            guard let self else { return }

            let trimmed = q.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmed.isEmpty else {
                self.matchingPages = []
                return
            }

            try? await Task.sleep(nanoseconds: self.debounceNanos)
            if Task.isCancelled { return }

            do {
                for try await hits in self.pageRepository.searchAllWithContext(rawQuery: trimmed) {
                    if Task.isCancelled { break }
                    self.matchingPages = hits
                }
            } catch is CancellationError {
                // ok
            } catch {
                self.matchingPages = []
            }
        }
    }

    deinit {
        shelvesTask?.cancel()
        shelfDirectoryTask?.cancel()
        searchTask?.cancel()
    }
}
