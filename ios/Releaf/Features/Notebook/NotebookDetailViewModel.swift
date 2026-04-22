/*
 * NotebookDetailViewModel.swift
 * Loads a single notebook's chapters + page summaries.
 */

import Foundation
import ReleafData

@MainActor
public final class NotebookDetailViewModel: ObservableObject {
    public enum State: Equatable {
        case loading
        case loaded(NotebookDetail)
        case failed(String)
    }

    @Published public private(set) var state: State = .loading

    private let notebookId: String
    private let repository: DriveRepository

    public init(notebookId: String, repository: DriveRepository = FakeDriveRepository()) {
        self.notebookId = notebookId
        self.repository = repository
    }

    public func load() async {
        state = .loading
        do {
            let detail = try await repository.loadNotebook(id: notebookId)
            state = .loaded(detail)
        } catch {
            state = .failed((error as? LocalizedError)?.errorDescription ?? "Couldn't load notebook")
        }
    }
}
