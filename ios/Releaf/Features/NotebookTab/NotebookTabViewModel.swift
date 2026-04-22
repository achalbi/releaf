/*
 * NotebookTabViewModel.swift
 *
 * Streams the user's live notebooks from `NotebookRepository` and
 * exposes create / delete / restore operations for the tab UI. No
 * "search" yet — the tab is currently just a list + add affordance.
 * When we wire a search field, extend with a `query` @Published and
 * debounce through `PageRepository.searchAll(...)`.
 */

import Foundation
import Combine
import ReleafData

@MainActor
public final class NotebookTabViewModel: ObservableObject {

    @Published public private(set) var notebooks: [NotebookEntity] = []
    @Published public private(set) var isLoading: Bool = true

    private let repository: NotebookRepository
    private var streamTask: Task<Void, Never>?

    public init(repository: NotebookRepository = NotebookRepository()) {
        self.repository = repository
    }

    /// Start observing. Called by the view's `.task` lifecycle — the
    /// task is bound to the view's body so it tears down on disappear.
    public func start() {
        streamTask?.cancel()
        streamTask = Task { [weak self, repository] in
            guard let self else { return }
            do {
                for try await list in repository.observeActive() {
                    self.notebooks = list
                    self.isLoading = false
                }
            } catch is CancellationError {
                // ok — view left the hierarchy
            } catch {
                self.isLoading = false
            }
        }
    }

    public func stop() {
        streamTask?.cancel()
        streamTask = nil
    }

    public func addNotebook(title: String) {
        let trimmed = title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        Task { [repository] in
            _ = try? await repository.createNotebook(title: trimmed)
        }
    }

    public func delete(notebookId: String) {
        Task { [repository] in
            try? await repository.softDeleteNotebook(id: notebookId)
        }
    }

    public func restore(notebookId: String) {
        Task { [repository] in
            try? await repository.undoSoftDeleteNotebook(id: notebookId)
        }
    }

    deinit {
        streamTask?.cancel()
    }
}
