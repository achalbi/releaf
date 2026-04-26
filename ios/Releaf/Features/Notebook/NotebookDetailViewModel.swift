/*
 * NotebookDetailViewModel.swift
 *
 * Loads a single notebook's chapters + page summaries and owns the
 * state for the notebook-level overflow actions: rename, new
 * chapter, archive.
 *
 * Each action has a corresponding `func` here + a matching
 * `@Published` flag for any UI surface (sheet, alert, toast) it
 * presents. The view binds to the flags rather than managing
 * presentation itself, mirroring the PageDetailViewModel pattern.
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

    /// One-shot toast emitted after an action fires. Mirrors the
    /// shape used on `PageDetailViewModel.Toast` so the same
    /// `ToastView` can render either — message + optional inline
    /// follow-up pill (e.g. "Undo").
    public struct Toast: Equatable, Identifiable {
        public let id = UUID()
        public let message: String
        public let actionLabel: String?
        public let actionKind: ToastActionKind?

        public init(
            message: String,
            actionLabel: String? = nil,
            actionKind: ToastActionKind? = nil
        ) {
            self.message = message
            self.actionLabel = actionLabel
            self.actionKind = actionKind
        }
    }

    /// Discrete tags for toast follow-up actions. The view
    /// dispatches back through `performToastAction(_:)` which
    /// translates the tag into the right async call. Carrying the
    /// chapter id on the chapter undo means a single restore
    /// pipeline regardless of how many chapters the user just
    /// archived in a row.
    public enum ToastActionKind: Equatable {
        case undoArchiveNotebook
        case undoArchiveChapter(String)
    }

    @Published public private(set) var state: State = .loading
    @Published public var presentingRenameSheet: Bool = false
    @Published public var presentingNewChapterSheet: Bool = false
    @Published public var confirmingArchive: Bool = false
    @Published public var toast: Toast?

    /// Per-chapter overflow state. Held as the chapter id (or nil
    /// when nothing is targeted) so the same sheet/alert pair drives
    /// every row without duplicating state for each chapter.
    @Published public var renamingChapterId: String? = nil
    @Published public var archivingChapterId: String? = nil

    private let notebookId: String
    private let repository: DriveRepository

    public init(notebookId: String, repository: DriveRepository = LocalDriveRepository.shared) {
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

    // MARK: - Overflow actions

    public func presentRename() {
        guard case .loaded = state else { return }
        presentingRenameSheet = true
    }

    public func presentNewChapter() {
        presentingNewChapterSheet = true
    }

    public func archiveNotebook() {
        guard case .loaded = state else { return }
        confirmingArchive = true
    }

    /// Apply a rename. Refreshes loaded state with the updated
    /// notebook so the title visibly changes immediately.
    public func renameNotebook(to title: String) async {
        let trimmed = title.trimmingCharacters(in: .whitespacesAndNewlines)
        let resolved = trimmed.isEmpty ? "Untitled notebook" : trimmed
        do {
            let updated = try await repository.renameNotebook(id: notebookId, title: resolved)
            if case .loaded(let detail) = state {
                state = .loaded(NotebookDetail(notebook: updated, chapters: detail.chapters))
            }
            presentingRenameSheet = false
            toast = Toast(message: "Renamed · \(resolved)")
        } catch {
            toast = Toast(message: "Couldn't rename — try again")
        }
    }

    /// Create a new chapter and append it to the loaded detail.
    public func createChapter(title: String) async {
        let trimmed = title.trimmingCharacters(in: .whitespacesAndNewlines)
        let resolved = trimmed.isEmpty ? "Untitled chapter" : trimmed
        do {
            let created = try await repository.createChapter(notebookId: notebookId, title: resolved)
            if case .loaded(let detail) = state {
                state = .loaded(NotebookDetail(
                    notebook: detail.notebook,
                    chapters: detail.chapters + [created]
                ))
            }
            presentingNewChapterSheet = false
            toast = Toast(message: "New chapter · \(resolved)")
        } catch {
            toast = Toast(message: "Couldn't create — try again")
        }
    }

    /// Confirm and apply archival.
    public func confirmArchiveNotebook() async {
        confirmingArchive = false
        do {
            let updated = try await repository.archiveNotebook(id: notebookId)
            if case .loaded(let detail) = state {
                state = .loaded(NotebookDetail(notebook: updated, chapters: detail.chapters))
            }
            toast = Toast(
                message: "Notebook archived",
                actionLabel: "Undo",
                actionKind: .undoArchiveNotebook
            )
        } catch {
            toast = Toast(message: "Couldn't archive — try again")
        }
    }

    // MARK: - Chapter overflow actions

    /// Open the per-chapter rename sheet bound to `chapterId`.
    public func presentRenameChapter(_ chapterId: String) {
        renamingChapterId = chapterId
    }

    /// Surface the destructive-confirm alert for `chapterId`.
    public func archiveChapter(_ chapterId: String) {
        archivingChapterId = chapterId
    }

    /// Look up the current title of a chapter by id — used to pre-fill
    /// the rename sheet's text field.
    public func chapterTitle(_ chapterId: String) -> String {
        guard case .loaded(let detail) = state,
              let c = detail.chapters.first(where: { $0.id == chapterId }) else { return "" }
        return c.title
    }

    /// Apply a rename to the chapter currently held in `renamingChapterId`.
    /// Updates loaded detail in place so the section header updates
    /// without a full reload.
    public func renameChapter(to title: String) async {
        guard let chapterId = renamingChapterId else { return }
        let trimmed = title.trimmingCharacters(in: .whitespacesAndNewlines)
        let resolved = trimmed.isEmpty ? "Untitled chapter" : trimmed
        do {
            let updated = try await repository.renameChapter(id: chapterId, title: resolved)
            if case .loaded(let detail) = state {
                let merged = detail.chapters.map { $0.id == chapterId ? updated : $0 }
                state = .loaded(NotebookDetail(notebook: detail.notebook, chapters: merged))
            }
            renamingChapterId = nil
            toast = Toast(message: "Renamed · \(resolved)")
        } catch {
            toast = Toast(message: "Couldn't rename — try again")
        }
    }

    /// Confirm archive for the chapter currently held in `archivingChapterId`.
    /// Drops the chapter from the loaded list (it's no longer active).
    public func confirmArchiveChapter() async {
        guard let chapterId = archivingChapterId else { return }
        archivingChapterId = nil
        do {
            _ = try await repository.archiveChapter(id: chapterId)
            if case .loaded(let detail) = state {
                let remaining = detail.chapters.filter { $0.id != chapterId }
                state = .loaded(NotebookDetail(notebook: detail.notebook, chapters: remaining))
            }
            toast = Toast(
                message: "Chapter archived",
                actionLabel: "Undo",
                actionKind: .undoArchiveChapter(chapterId)
            )
        } catch {
            toast = Toast(message: "Couldn't archive — try again")
        }
    }

    /// Run the follow-up action attached to the current toast.
    /// Tagged dispatch (rather than a closure on Toast) keeps the
    /// data class equatable and the action set auditable in one
    /// place.
    public func performToastAction(_ kind: ToastActionKind) async {
        // Clear immediately so the action doesn't double-fire if
        // tapped mid-animation. The follow-up's own toast will be
        // set by the action itself.
        toast = nil
        switch kind {
        case .undoArchiveNotebook:
            await restoreNotebook()
        case .undoArchiveChapter(let chapterId):
            do {
                let restored = try await repository.restoreChapter(id: chapterId)
                if case .loaded(let detail) = state {
                    let merged = detail.chapters + [restored]
                    state = .loaded(NotebookDetail(notebook: detail.notebook, chapters: merged))
                }
                toast = Toast(message: "Chapter restored")
            } catch {
                toast = Toast(message: "Couldn't restore — try again")
            }
        }
    }

    /// Inverse of archive. Surfaces from the ArchivedBanner shown
    /// at the top of an already-archived notebook.
    public func restoreNotebook() async {
        do {
            let updated = try await repository.restoreNotebook(id: notebookId)
            if case .loaded(let detail) = state {
                state = .loaded(NotebookDetail(notebook: updated, chapters: detail.chapters))
            }
            toast = Toast(message: "Notebook restored")
        } catch {
            toast = Toast(message: "Couldn't restore — try again")
        }
    }
}
