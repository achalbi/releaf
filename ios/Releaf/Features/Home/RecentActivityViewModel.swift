/*
 * RecentActivityViewModel.swift
 *
 * Drives both the Home timeline card and (later) a full ActivityScreen.
 * iOS mirror of Android's `features/activity/RecentActivityViewModel.kt`,
 * but the data path differs: Android reads from a real `audit_events`
 * table written by the four user-facing repositories on every mutation.
 * iOS hasn't built that table yet, so this view model synthesizes the
 * feed from the same `updated_at` columns the existing observables
 * already expose — the phase-1 approach.
 *
 * When/if iOS lands a real audit log, this view model's public API
 * stays the same; only the private observe-and-merge path changes.
 *
 * Each `recompute` walks the two latest snapshots, emits one
 * `ActivityItem` per row, and sorts newest-first capped to `maxItems`.
 */

import Foundation
import ReleafData

@MainActor
public final class RecentActivityViewModel: ObservableObject {
    /// `nonisolated` so the value can be referenced from a default-
    /// parameter expression (which Swift evaluates in nonisolated
    /// context). The constant is a pure literal — no actor state to
    /// protect.
    public nonisolated static let homeLimit: Int = 5

    @Published public private(set) var items: [ActivityItem] = []

    private let userId: String
    private let maxItems: Int
    private let notebookRepository: NotebookRepository
    private let notepadRepository: NotepadRepository

    private var tasks: [Task<Void, Never>] = []
    private var latestShelves: [ShelfRecord] = []
    private var latestNotepad: [NotepadEntry] = []

    public init(
        userId: String,
        maxItems: Int = RecentActivityViewModel.homeLimit,
        notebookRepository: NotebookRepository = NotebookRepository(),
        notepadRepository: NotepadRepository = NotepadRepository()
    ) {
        self.userId = userId
        self.maxItems = maxItems
        self.notebookRepository = notebookRepository
        self.notepadRepository = notepadRepository
    }

    public func start() {
        stop()
        tasks.append(Task { [weak self, notebookRepository] in
            guard let self else { return }
            do {
                for try await value in notebookRepository.observeShelves() {
                    self.latestShelves = value
                    self.recompute()
                }
            } catch {}
        })
        tasks.append(Task { [weak self, notepadRepository, userId] in
            guard let self else { return }
            do {
                for try await value in notepadRepository.observeActive(userId: userId) {
                    self.latestNotepad = value
                    self.recompute()
                }
            } catch {}
        })
    }

    public func stop() {
        tasks.forEach { $0.cancel() }
        tasks.removeAll()
    }

    deinit {
        tasks.forEach { $0.cancel() }
    }

    // MARK: - Recompute

    private func recompute() {
        var merged: [ActivityItem] = []

        // Notebook-side rows: one per notebook, action inferred from
        // whether updated_at == created_at. Synthetic — until the
        // audit log lands, we can't see deletes/moves.
        for record in latestShelves {
            let entity = record.notebook
            let action: ActivityAction = entity.updatedAt == entity.createdAt ? .created : .updated
            merged.append(ActivityItem(
                id: "notebook-\(entity.id)-\(action.rawValue)",
                kind: .notebook,
                action: action,
                entityId: entity.id,
                timestamp: entity.updatedAt,
                title: entity.title.nonEmpty ?? "Untitled notebook",
                context: nil
            ))
        }

        // Notepad-side rows.
        for entry in latestNotepad {
            let action: ActivityAction = entry.updatedAt == entry.createdAt ? .created : .updated
            merged.append(ActivityItem(
                id: "notepad-\(entry.id)-\(action.rawValue)",
                kind: .notepadEntry,
                action: action,
                entityId: entry.id,
                timestamp: entry.updatedAt,
                title: Self.displayTitle(for: entry),
                context: nil
            ))
        }

        // Newest-first by ISO-8601 string compare (works because the
        // strings are zulu-formatted with the same precision).
        merged.sort { $0.timestamp > $1.timestamp }
        if merged.count > maxItems { merged = Array(merged.prefix(maxItems)) }

        items = merged
    }

    private static func displayTitle(for entry: NotepadEntry) -> String {
        if let t = entry.title?.trimmingCharacters(in: .whitespacesAndNewlines), !t.isEmpty {
            return t
        }
        let firstLine = entry.notes
            .split(separator: "\n")
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .first(where: { !$0.isEmpty })
        if let firstLine { return String(firstLine.prefix(80)) }
        return "Untitled entry"
    }
}

private extension String {
    var nonEmpty: String? {
        trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : self
    }
}
