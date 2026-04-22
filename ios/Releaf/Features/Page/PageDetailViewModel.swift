/*
 * PageDetailViewModel.swift
 * Loads a single Page (full payload with all seven capture modes).
 */

import Foundation
import ReleafData

@MainActor
public final class PageDetailViewModel: ObservableObject {
    public enum State: Equatable {
        case loading
        case loaded(Page)
        case failed(String)
    }

    @Published public private(set) var state: State = .loading

    private let pageId: String
    private let repository: DriveRepository

    public init(pageId: String, repository: DriveRepository = FakeDriveRepository()) {
        self.pageId = pageId
        self.repository = repository
    }

    public func load() async {
        state = .loading
        do {
            let page = try await repository.loadPage(id: pageId)
            state = .loaded(page)
        } catch {
            state = .failed((error as? LocalizedError)?.errorDescription ?? "Couldn't load page")
        }
    }
}
