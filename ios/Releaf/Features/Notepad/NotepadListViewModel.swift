/*
 * NotepadListViewModel.swift
 *
 * Observes the signed-in user's active notepad entries from the local DB.
 * Soft-deletes are filtered in the repository — this VM only ever sees
 * live rows.
 *
 * Search: backed by a single `@Published` query string. A blank query
 * subscribes to `observeActive`; a non-blank one debounces 150ms, then
 * switches to the FTS5-backed `search` stream. Switching streams cancels
 * the previous Task so old results don't arrive out of order.
 *
 * Undo toast: `softDelete` spawns a 4s-TTL toast with an Undo action. The
 * view renders it as an overlay so there's no SwiftUI snackbar dependency
 * to wire up; parity with Android's Snackbar + SnackbarResult pattern.
 */

import Foundation
import Combine
import ReleafData

@MainActor
public final class NotepadListViewModel: ObservableObject {

    /// Transient post-delete banner with an Undo action.
    public struct UndoToast: Equatable, Identifiable {
        public let id = UUID()
        public let entryId: String
    }

    @Published public private(set) var entries: [NotepadEntry] = []
    @Published public var query: String = "" {
        didSet {
            // Always kick the stream on change, even empty→empty is cheap.
            if oldValue != query { reload() }
        }
    }

    @Published public var toast: UndoToast? = nil

    // Coalesces rapid typing into a single stream subscription.
    private let debounceNanos: UInt64 = 150_000_000

    private let repository: NotepadRepository
    private let userId: String

    private var streamTask: Task<Void, Never>?
    private var toastTask: Task<Void, Never>?

    public init(
        repository: NotepadRepository,
        userId: String
    ) {
        self.repository = repository
        self.userId = userId
    }

    /// Kick off the initial subscription. Idempotent — calling again after
    /// cancelation resumes from the current query.
    public func start() {
        if streamTask == nil { reload() }
    }

    public func clearQuery() { query = "" }

    // MARK: - Mutations

    /// Soft-delete + show undo toast. Parity with Android: Room is the
    /// source of truth, so the row vanishes from the stream immediately.
    public func softDelete(_ entry: NotepadEntry) {
        let id = entry.id
        Task { [repository] in
            try? await repository.softDelete(id: id)
        }
        showUndoToast(for: id)
    }

    /// Tapped by the Undo button on the toast. Clears the toast regardless
    /// of whether the restore succeeded — the list stream will re-populate
    /// the row either way.
    public func undoDelete(_ entryId: String) {
        Task { [repository] in
            try? await repository.undoSoftDelete(id: entryId)
        }
        dismissToast()
    }

    public func dismissToast() {
        toastTask?.cancel()
        toastTask = nil
        toast = nil
    }

    // MARK: - Stream management

    private func reload() {
        streamTask?.cancel()
        let q = query
        streamTask = Task { [weak self] in
            guard let self else { return }
            // Debounce only non-blank queries. The initial empty-query load
            // paints immediately on launch — no artificial delay.
            if !q.isBlank {
                try? await Task.sleep(nanoseconds: self.debounceNanos)
                if Task.isCancelled { return }
            }
            do {
                if q.isBlank {
                    for try await batch in self.repository.observeActive(userId: self.userId) {
                        if Task.isCancelled { break }
                        self.entries = batch
                    }
                } else {
                    for try await batch in self.repository.search(userId: self.userId, rawQuery: q) {
                        if Task.isCancelled { break }
                        self.entries = batch
                    }
                }
            } catch {
                // Stream ended with an error (e.g. DB access issue). Drain
                // the list rather than keeping stale rows — the view shows
                // the empty state, which is the right fallback while we
                // aren't plumbing a proper error banner yet.
                self.entries = []
            }
        }
    }

    private func showUndoToast(for entryId: String) {
        toastTask?.cancel()
        toast = UndoToast(entryId: entryId)
        toastTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 4_000_000_000)
            guard let self, !Task.isCancelled else { return }
            self.toast = nil
        }
    }

    deinit {
        streamTask?.cancel()
        toastTask?.cancel()
    }
}

private extension String {
    /// Android-ish `isBlank` — empty or whitespace-only.
    var isBlank: Bool {
        trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }
}
