/*
 * StoriesShelfViewModel.swift
 *
 * Observable wrapper around `StoryRepository.observeShelf` for the
 * Stories tab — exposes a `[StoryShelfRow]` published array the
 * SwiftUI shelf renders into the §7.1 layout. Mirrors the shape of
 * `TagListViewModel` (Combine subscription threaded through
 * `@MainActor`).
 *
 * Mirror of Android `StoriesShelfViewModel.kt`.
 */

import Foundation
import Combine

@MainActor
public final class StoriesShelfViewModel: ObservableObject {

    @Published public private(set) var rows: [StoryShelfRow] = []
    /// Phase 5 — populated by `StorySuggestionEngine.compute` over the
    /// user's captures whenever the shelf opens or a capture lands.
    /// Nil when no high-confidence cluster qualifies OR when the
    /// best candidate is already in the session dismissal set below.
    @Published public private(set) var suggestion: StorySuggestion? = nil

    private let repository: StoryRepository
    private let database: QuickInkDatabase
    private let userId: String
    private var cancellable: AnyCancellable?
    /// Process-scoped dismissal set, per spec §7. Cleared on app
    /// restart by virtue of being instance state on a @StateObject.
    private var dismissed: Set<String> = []

    public init(
        repository: StoryRepository = StoryRepository(),
        userId: String,
        database: QuickInkDatabase = .shared
    ) {
        self.repository = repository
        self.userId     = userId
        self.database   = database
    }

    /// Open the live observation. Call from `.task` of the hosting
    /// view; safe to call multiple times — the previous subscription
    /// is replaced.
    public func start() {
        cancellable = repository.observeShelf(userId: userId)
            .receive(on: DispatchQueue.main)
            .sink(
                receiveCompletion: { _ in },
                receiveValue: { [weak self] in self?.rows = $0 }
            )
        Task { await refreshSuggestion() }
    }

    public func refreshSuggestion() async {
        let userId = self.userId
        let dismissed = self.dismissed
        let database = self.database
        let result = try? await StorySuggestionEngine.compute(
            userId:    userId,
            database:  database,
            dismissed: dismissed
        )
        await MainActor.run { self.suggestion = result }
    }

    /// "Not interested" — drop the current suggestion and re-run the
    /// engine so a runner-up shows up if any.
    public func dismissSuggestion() {
        guard let current = suggestion else { return }
        dismissed.insert(current.id)
        suggestion = nil
        Task { await refreshSuggestion() }
    }

    /// Create a fresh draft story and return its id. The shelf calls
    /// this when the user taps the "+" FAB; the screen then pushes
    /// the story editor onto its navigation path keyed by the
    /// returned id.
    public func createDraft() async -> String? {
        guard let story = try? await repository.insertStory(
            userId:   userId,
            title:    "Untitled story",
            subtitle: nil
        ) else { return nil }
        return story.id
    }
}
