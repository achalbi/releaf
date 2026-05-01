/*
 * CallHistoryViewModel.swift
 *
 * Observes the local call-history log for the signed-in user and
 * drives `CallHistoryView`. Matches the Android `CallHistoryViewModel`
 * shape so the screen reads the same on both platforms.
 */

import Foundation
import ReleafData

@MainActor
public final class CallHistoryViewModel: ObservableObject {

    public struct State: Equatable {
        public var isLoading: Bool = true
        public var entries: [CallHistoryRecord] = []

        public var isEmpty: Bool { !isLoading && entries.isEmpty }
    }

    @Published public private(set) var state: State = State()

    private let userId: String
    private let repository: CallHistoryRepository
    private var observerTask: Task<Void, Never>?

    public init(
        userId: String,
        repository: CallHistoryRepository = CallHistoryRepository()
    ) {
        self.userId = userId
        self.repository = repository
    }

    public func start() {
        stop()
        observerTask = Task { [weak self, repository, userId] in
            guard let self else { return }
            do {
                for try await entries in repository.observeAll(userId: userId) {
                    self.state.isLoading = false
                    self.state.entries = entries
                }
            } catch {}
        }
    }

    public func stop() {
        observerTask?.cancel()
        observerTask = nil
    }

    deinit { observerTask?.cancel() }

    public func clearAll() {
        let userId = self.userId
        let repository = self.repository
        Task { try? await repository.deleteAll(userId: userId) }
    }

    public func delete(_ id: String) {
        let repository = self.repository
        Task { try? await repository.delete(id: id) }
    }
}
