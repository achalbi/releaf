/*
 * StoryEditorViewModel.swift
 *
 * State holder for `StoryEditorScreen`. Owns:
 *   - the story header (title / subtitle / cover) — observed live
 *   - the ordered item list — observed live via GRDB ValueObservation
 *   - the "Saved just now" auto-save toast (debounced text edits, hard
 *     saves on inserts / removes / reorder commits)
 *
 * Per-field text edits debounce to 500 ms before hitting the DB so
 * a long typing burst isn't an N-write sequence. Inserts / deletes /
 * layout / reorder commit immediately.
 *
 * Mirror of Android `StoryEditorViewModel.kt`.
 */

import Combine
import Foundation
import GRDB

@MainActor
public final class StoryEditorViewModel: ObservableObject {

    public let storyId: String
    public let userId: String

    @Published public private(set) var story: Story? = nil
    @Published public private(set) var items: [StoryItem] = []
    @Published public private(set) var savedJustNow: Bool = false

    private let repository: StoryRepository
    private let database: QuickInkDatabase
    private var storyCancellable: AnyCancellable?
    private var itemsCancellable: AnyCancellable?
    private var savedToastTask: Task<Void, Never>?
    /// Holds pending debounce tasks keyed by item id (so multiple
    /// items can be edited in parallel without one cancelling
    /// another's flush).
    private var pendingTextDebounces: [String: Task<Void, Never>] = [:]
    private var pendingTitleDebounce: Task<Void, Never>?

    public init(
        storyId: String,
        userId: String,
        repository: StoryRepository = StoryRepository(),
        database: QuickInkDatabase = .shared
    ) {
        self.storyId = storyId
        self.userId  = userId
        self.repository = repository
        self.database = database
    }

    public func start() {
        let queue = database.dbQueue
        let sid = storyId
        storyCancellable = ValueObservation
            .tracking { db in
                try Story
                    .filter(Story.Columns.id == sid)
                    .filter(Story.Columns.deletedAt == nil)
                    .fetchOne(db)
            }
            .publisher(in: queue)
            .receive(on: DispatchQueue.main)
            .sink(receiveCompletion: { _ in }, receiveValue: { [weak self] in self?.story = $0 })

        itemsCancellable = ValueObservation
            .tracking { db in
                try StoryItem
                    .filter(StoryItem.Columns.storyId == sid)
                    .filter(StoryItem.Columns.deletedAt == nil)
                    .order(StoryItem.Columns.position.asc)
                    .fetchAll(db)
            }
            .publisher(in: queue)
            .receive(on: DispatchQueue.main)
            .sink(receiveCompletion: { _ in }, receiveValue: { [weak self] in self?.items = $0 })
    }

    // MARK: - Story header edits

    public func updateTitle(_ value: String) {
        pendingTitleDebounce?.cancel()
        pendingTitleDebounce = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 500_000_000)
            guard let self = self, !Task.isCancelled else { return }
            try? await self.repository.updateTitle(storyId: self.storyId, title: value)
            await self.flashSavedToast()
        }
    }

    public func updateSubtitle(_ value: String) {
        let sub = value.isEmpty ? nil : value
        Task { [weak self] in
            guard let self = self else { return }
            try? await self.repository.updateSubtitle(storyId: self.storyId, subtitle: sub)
            await self.flashSavedToast()
        }
    }

    // MARK: - Item edits

    public func updateItemCaption(_ itemId: String, _ value: String) {
        let caption: String? = value.isEmpty ? nil : value
        debounceItemText(itemId: itemId) { [weak self] in
            guard let self = self else { return }
            try? await self.repository.updateItemCaption(itemId: itemId, caption: caption)
            await self.flashSavedToast()
        }
    }

    public func updateItemText(_ itemId: String, _ value: String) {
        let text: String? = value.isEmpty ? nil : value
        debounceItemText(itemId: itemId) { [weak self] in
            guard let self = self else { return }
            try? await self.repository.updateItemText(itemId: itemId, text: text)
            await self.flashSavedToast()
        }
    }

    public func updateItemLayout(_ itemId: String, _ layout: StoryItem.Layout) {
        Task { [weak self] in
            guard let self = self else { return }
            try? await self.repository.updateItemLayout(itemId: itemId, layout: layout)
            await self.flashSavedToast()
        }
    }

    public func removeItem(_ itemId: String) {
        Task { [weak self] in
            guard let self = self else { return }
            try? await self.repository.softDeleteItem(id: itemId)
            await self.flashSavedToast()
        }
    }

    /// Insert a new item after the given preceding item (nil → append
    /// to the end). Returns the newly-inserted row's id.
    @discardableResult
    public func insertItem(
        after precedingId: String?,
        kind: StoryItem.Kind,
        text: String? = nil,
        caption: String? = nil
    ) async -> String? {
        let nextPosition = positionAfter(precedingId)
        guard let inserted = try? await repository.insertItem(
            storyId:   storyId,
            position:  nextPosition,
            kind:      kind,
            refId:     nil,
            text:      text,
            caption:   caption,
            occurredAt: nil,
            layout:    .full
        ) else { return nil }
        await flashSavedToast()
        return inserted.id
    }

    /// Inserts a capture-backed story_item (kind = `.photo` or
    /// `.document`) pointing at an existing `captures.id`. Used by
    /// the library picker.
    @discardableResult
    public func insertCaptureItem(
        after precedingId: String?,
        captureId: String,
        kind: StoryItem.Kind
    ) async -> String? {
        let position = positionAfter(precedingId)
        guard let item = try? await repository.insertItem(
            storyId:    storyId,
            position:   position,
            kind:       kind,
            refId:      captureId,
            text:       nil,
            caption:    nil,
            occurredAt: nil,
            layout:     .full
        ) else { return nil }
        await flashSavedToast()
        return item.id
    }

    /// Inserts a voice-clip story_item + the attached
    /// `story_voice_clip` row pointing at the .m4a on disk. Returns
    /// the new story_item id (the parent the caller should reference).
    @discardableResult
    public func insertVoiceClipItem(
        after precedingId: String?,
        audioUri: String,
        durationMs: Int
    ) async -> String? {
        let position = positionAfter(precedingId)
        guard let item = try? await repository.insertItem(
            storyId:    storyId,
            position:   position,
            kind:       .voiceClip,
            refId:      nil,
            text:       nil,
            caption:    nil,
            occurredAt: nil,
            layout:     .full
        ) else { return nil }
        _ = try? await repository.insertVoiceClip(
            storyItemId: item.id,
            userId:      userId,
            audioUri:    audioUri,
            durationMs:  durationMs
        )
        await flashSavedToast()
        return item.id
    }

    /// Commit a new ordering after a drag-reorder. The view passes the
    /// reordered list of item ids; this maps them to 1024-spaced
    /// positions and writes in one transaction.
    public func commitReorder(_ orderedIds: [String]) {
        let updates: [(itemId: String, position: Int)] = orderedIds.enumerated().map { idx, id in
            (id, (idx + 1) * 1024)
        }
        Task { [weak self] in
            guard let self = self else { return }
            try? await self.repository.updatePositions(updates)
            await self.flashSavedToast()
        }
    }

    public func setCover(itemId: String?) {
        Task { [weak self] in
            guard let self = self else { return }
            try? await self.repository.setCoverItem(storyId: self.storyId, itemId: itemId)
            await self.flashSavedToast()
        }
    }

    // MARK: - Helpers

    private func positionAfter(_ precedingId: String?) -> Int {
        guard let precedingId = precedingId,
              let precedingIdx = items.firstIndex(where: { $0.id == precedingId })
        else {
            return (items.last.map { $0.position + 1024 }) ?? 1024
        }
        let precedingPos = items[precedingIdx].position
        if precedingIdx + 1 < items.count {
            let nextPos = items[precedingIdx + 1].position
            let mid = (precedingPos + nextPos) / 2
            return mid != precedingPos ? mid : precedingPos + 1024
        }
        return precedingPos + 1024
    }

    private func debounceItemText(itemId: String, _ flush: @escaping () async -> Void) {
        pendingTextDebounces[itemId]?.cancel()
        pendingTextDebounces[itemId] = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 500_000_000)
            guard !Task.isCancelled else { return }
            await flush()
            self?.pendingTextDebounces[itemId] = nil
        }
    }

    private func flashSavedToast() async {
        savedJustNow = true
        savedToastTask?.cancel()
        savedToastTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 3_000_000_000)
            guard !Task.isCancelled else { return }
            await MainActor.run { self?.savedJustNow = false }
        }
    }
}
