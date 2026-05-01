/*
 * HomeViewModel.swift
 * Loads the signed-in user's notebook list via DriveRepository.
 */

import Foundation
import ReleafData

@MainActor
public final class HomeViewModel: ObservableObject {
    public enum State: Equatable {
        case idle
        case loading
        case loaded([Notebook])
        case failed(String)
    }

    @Published public private(set) var state: State = .idle

    private let repository: DriveRepository

    public init(repository: DriveRepository = LocalDriveRepository.shared) {
        self.repository = repository
    }

    public func load() async {
        state = .loading
        do {
            let notebooks = try await repository.listNotebooks()
            state = .loaded(notebooks)
        } catch {
            state = .failed((error as? LocalizedError)?.errorDescription ?? "Couldn't load notebooks")
        }
    }
}
