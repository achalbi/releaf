/*
 * PageDetailViewModel.swift
 *
 * Loads a single Page (full payload with all seven capture modes) and
 * owns the state for the page-level overflow actions: archive,
 * duplicate, share, export PDF, move-to-notebook, apply-template.
 *
 * Each action has a corresponding `func` on this view model + a
 * matching `@Published` flag for any UI surface (alert, sheet, share
 * sheet) it presents. The view binds to the flags rather than
 * managing the presentation state itself, so the menu items can fire
 * `viewModel.archivePage()` (etc.) without the call site holding any
 * action state.
 *
 * Repository wiring: archive / duplicate currently mark the local
 * Page in-memory and log a TODO. Wiring the real DriveRepository
 * mutations is a bounded follow-up — the UI flows are already in
 * place so the ViewModel's contract doesn't change when the data
 * layer fills in.
 */

import Foundation
import ReleafData
import ReleafDesignSystem
#if canImport(UIKit)
import UIKit
#endif

@MainActor
public final class PageDetailViewModel: ObservableObject {
    public enum State: Equatable {
        case loading
        case loaded(Page)
        case failed(String)
    }

    /// Payload for the system share sheet. Carries text that the
    /// share sheet treats as the share content, and an optional
    /// `fileURL` for share targets that prefer a real file (the
    /// PDF-export path uses this; plain text-share leaves it nil).
    /// The view binds to the optional and presents the share sheet
    /// when non-nil; a nil reset is the dismissal contract.
    public struct ShareIntent: Equatable, Identifiable {
        public let id = UUID()
        public let title: String
        public let body: String
        public let fileURL: URL?

        public init(title: String, body: String, fileURL: URL? = nil) {
            self.title = title
            self.body = body
            self.fileURL = fileURL
        }
    }

    /// One-shot toast emitted after an action fires. The view
    /// consumes it and clears it. `actionLabel` + `actionKind`
    /// optionally surface a single inline pill the user can tap to
    /// follow up — currently used by the archive/restore flow to
    /// offer "Undo" inline. Passing both fields unlocks the pill;
    /// nil values render the toast as a plain message.
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

    /// Discrete tags for toast follow-up actions. Kept as an enum
    /// (rather than a closure) so Toast stays Equatable; the view
    /// dispatches back through `performToastAction(...)` which
    /// translates the tag into the right async call.
    public enum ToastActionKind: Equatable {
        case undoArchive
    }

    @Published public private(set) var state: State = .loading
    @Published public var confirmingArchive: Bool = false
    @Published public var shareIntent: ShareIntent?
    @Published public var presentingMoveSheet: Bool = false
    @Published public var presentingTemplateSheet: Bool = false
    @Published public var presentingTagEditor: Bool = false
    @Published public var toast: Toast?
    /// Notebooks the user has, populated when the Move-to-notebook
    /// picker opens. The picker renders directly off this list.
    @Published public private(set) var availableNotebooks: [Notebook] = []
    /// True while `availableNotebooks` is being fetched. The picker
    /// shows a spinner row while this is true.
    @Published public private(set) var loadingNotebooks: Bool = false
    /// Chapters per notebook id, lazy-loaded when the user expands
    /// a row in the picker. Empty for notebooks the user hasn't
    /// drilled into yet.
    @Published public private(set) var chaptersByNotebookId: [String: [Chapter]] = [:]
    /// Notebook ids currently being loaded; the picker shows a
    /// per-row spinner.
    @Published public private(set) var chaptersLoadingFor: Set<String> = []
    /// Templates available to apply, populated when the Apply-template
    /// picker opens. The picker renders directly off this list.
    @Published public private(set) var availableTemplates: [PageTemplate] = []

    /// Parent notebook of the loaded page. Loaded alongside the
    /// page so the header eyebrow can pick up the notebook's color
    /// (or fall back to default green when nil). Surfaces as a
    /// computed `colorToken` for the view to read directly.
    @Published public private(set) var parentNotebook: Notebook? = nil
    /// True while `availableTemplates` is being fetched.
    @Published public private(set) var loadingTemplates: Bool = false

    private let pageId: String
    private let repository: DriveRepository

    public init(pageId: String, repository: DriveRepository = LocalDriveRepository.shared) {
        self.pageId = pageId
        self.repository = repository
    }

    public func load() async {
        state = .loading
        do {
            let page = try await repository.loadPage(id: pageId)
            state = .loaded(page)
            // Best-effort lookup of the parent notebook so the
            // header eyebrow can tint with its color. Failure is
            // silent — header simply renders default green.
            await loadParentNotebook(notebookId: page.notebookId)
        } catch {
            state = .failed((error as? LocalizedError)?.errorDescription ?? "Couldn't load page")
        }
    }

    private func loadParentNotebook(notebookId: String) async {
        do {
            let detail = try await repository.loadNotebook(id: notebookId)
            self.parentNotebook = detail.notebook
        } catch {
            self.parentNotebook = nil
        }
    }

    // MARK: - Overflow actions

    /// Step 1 of archive — present a confirmation. The view binds an
    /// alert to `confirmingArchive`; tapping confirm calls `confirmArchive()`.
    public func archivePage() {
        guard case .loaded = state else { return }
        confirmingArchive = true
    }

    /// Step 2 of archive — performs the archival, refreshes the
    /// loaded page so the ArchivedBanner shows immediately, and
    /// surfaces a toast.
    public func confirmArchive() async {
        confirmingArchive = false
        do {
            let updated = try await repository.archivePage(id: pageId)
            state = .loaded(updated)
            // Surface an inline "Undo" pill on the toast — tapping
            // it dispatches back through `performToastAction(_:)`
            // which calls `restorePage()`. Auto-dismiss timing is
            // owned by the view; the pill simply gives the user a
            // shortcut while the toast is still on screen.
            toast = Toast(
                message: "Page archived",
                actionLabel: "Undo",
                actionKind: .undoArchive
            )
        } catch {
            toast = Toast(message: "Couldn't archive — try again")
        }
    }

    /// Run the follow-up action attached to the current toast.
    /// Tagged dispatch (rather than storing a closure on Toast)
    /// keeps the Toast struct Equatable and the action set
    /// auditable in one place.
    public func performToastAction(_ kind: ToastActionKind) async {
        // Clear the toast right away so the action button doesn't
        // double-fire if the user taps mid-animation. The result
        // toast (e.g. "Page restored") is set by the action itself.
        toast = nil
        switch kind {
        case .undoArchive:
            await restorePage()
        }
    }

    /// Open the tag editor sheet. The view binds a `.sheet(...)` to
    /// `presentingTagEditor` and seeds its draft state from the
    /// loaded page's `tags`.
    public func presentTagEditor() {
        guard case .loaded = state else { return }
        presentingTagEditor = true
    }

    /// Persist the user's edited tag list. De-dupes case-insensitively
    /// while preserving the order the user typed (first occurrence
    /// wins). Updates the loaded page so the new tags appear
    /// immediately wherever they're rendered.
    public func saveTags(_ tags: [String]) async {
        let cleaned = dedupedTags(tags)
        do {
            let updated = try await repository.setPageTags(pageId: pageId, tags: cleaned)
            state = .loaded(updated)
            presentingTagEditor = false
            toast = Toast(message: cleaned.isEmpty
                ? "Tags cleared"
                : "Tags updated · \(cleaned.count)")
        } catch {
            toast = Toast(message: "Couldn't save tags — try again")
        }
    }

    /// Copy a single tag to the system clipboard and emit a toast
    /// confirming the action. Bound to the long-press gesture on
    /// the read-only tag pills surfaced under the page title — the
    /// short-tap on the same pill goes through `presentTagEditor`.
    public func copyTagToClipboard(_ tag: String) {
        #if canImport(UIKit)
        UIPasteboard.general.string = tag
        #endif
        toast = Toast(message: "Copied · \(tag)")
    }

    /// Copy a deep-link to the current page onto the clipboard.
    /// The URL scheme (`releaf://page/{id}`) is a placeholder —
    /// the app doesn't yet handle inbound links, but the format
    /// is stable so users can save / share these strings now. The
    /// toast surfaces the URL itself so users can sanity-check
    /// what landed on the clipboard before pasting.
    public func copyPageLinkToClipboard() {
        guard case .loaded(let page) = state else { return }
        let url = "releaf://page/\(page.id)"
        #if canImport(UIKit)
        UIPasteboard.general.string = url
        #endif
        toast = Toast(message: "Copied · \(url)")
    }

    /// Copy a list of tags to the clipboard as a comma-separated
    /// string. Surfaces from the "Copy all" pill on the
    /// `EditTagsSheet`. Same toast pipeline as `copyTagToClipboard`.
    public func copyTagsToClipboard(_ tags: [String]) {
        let joined = tags.joined(separator: ", ")
        guard !joined.isEmpty else { return }
        #if canImport(UIKit)
        UIPasteboard.general.string = joined
        #endif
        toast = Toast(message: "Copied · \(tags.count) tag\(tags.count == 1 ? "" : "s")")
    }

    /// Copy the daily plant's headline (Sanskrit name + epithet) to
    /// the clipboard. Surfaces from the Copy pill on the
    /// `DailyPlantInfoSheet`. Same toast pipeline as `copyTag`.
    public func copyPlantHeadlineToClipboard(_ plant: DailyPlant) {
        let headline: String
        if plant.commonName.isEmpty {
            headline = "\(plant.name) — \(plant.epithet)"
        } else {
            headline = "\(plant.name) (\(plant.commonName)) — \(plant.epithet)"
        }
        #if canImport(UIKit)
        UIPasteboard.general.string = headline
        #endif
        toast = Toast(message: "Copied · \(plant.name)")
    }

    private func dedupedTags(_ tags: [String]) -> [String] {
        var seen = Set<String>()
        var out: [String] = []
        for raw in tags {
            let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            if trimmed.isEmpty { continue }
            let key = trimmed.lowercased()
            if seen.insert(key).inserted { out.append(trimmed) }
        }
        return out
    }

    /// Inverse of archive. Called from the `ArchivedBanner`'s Restore
    /// button. Refreshes the page so the banner disappears.
    public func restorePage() async {
        do {
            let updated = try await repository.restorePage(id: pageId)
            state = .loaded(updated)
            toast = Toast(message: "Page restored")
        } catch {
            toast = Toast(message: "Couldn't restore — try again")
        }
    }

    /// Duplicate is a one-tap action; no confirmation. Calls the
    /// repo, navigates the detail to the new copy (so the user can
    /// immediately edit it), and surfaces a toast.
    public func duplicatePage() async {
        do {
            let copy = try await repository.duplicatePage(id: pageId)
            state = .loaded(copy)
            toast = Toast(message: "Duplicated · \(copy.title)")
        } catch {
            toast = Toast(message: "Couldn't duplicate — try again")
        }
    }

    /// Builds a share intent off the loaded page. The view binds a
    /// `.sheet(item: $vm.shareIntent)` to actually present.
    public func presentShareSheet() {
        guard case .loaded(let page) = state else { return }
        shareIntent = ShareIntent(
            title: page.title,
            body: page.notes.first?.body ?? page.title
        )
    }

    /// Render the loaded page to a PDF, write it to the app's
    /// documents directory, and route the file URL through the
    /// share-sheet machinery so the user can hand the file off to
    /// Files / Mail / Messages / etc.
    public func exportPDF() async {
        guard case .loaded(let page) = state else { return }
        do {
            let url = try PdfExporter.export(page: page)
            shareIntent = ShareIntent(
                title: page.title,
                body: page.title,
                fileURL: url
            )
            toast = Toast(message: "PDF ready")
        } catch {
            toast = Toast(message: "Couldn't export — try again")
        }
    }

    /// Triggers the "Move to notebook" picker. The view binds a
    /// sheet to `presentingMoveSheet`; this method also kicks off the
    /// notebooks-list fetch so the picker has data ready.
    public func presentMoveToNotebook() {
        presentingMoveSheet = true
        Task { await loadAvailableNotebooks() }
    }

    /// Re-parent the current page under `notebookId`. When
    /// `chapterId` is nil the destination's first chapter is used
    /// (set by the repo); otherwise that exact chapter receives the
    /// page. Closes the sheet on success and surfaces a toast.
    public func selectNotebook(_ notebookId: String, chapterId: String? = nil) async {
        let nbTitle = availableNotebooks.first(where: { $0.id == notebookId })?.title ?? "notebook"
        let chapterTitle = chaptersByNotebookId[notebookId]?
            .first(where: { $0.id == chapterId })?
            .title
        let label: String = if let chapterTitle {
            "\(nbTitle) / \(chapterTitle)"
        } else {
            nbTitle
        }
        do {
            try await repository.movePage(
                pageId: pageId,
                toNotebookId: notebookId,
                toChapterId: chapterId
            )
            presentingMoveSheet = false
            toast = Toast(message: "Moved to \(label)")
        } catch {
            toast = Toast(message: "Couldn't move — try again")
        }
    }

    /// Lazy-load chapters for a notebook the user is expanding in
    /// the picker. No-op once chapters are already cached.
    public func loadChapters(forNotebookId notebookId: String) async {
        if chaptersByNotebookId[notebookId] != nil { return }
        if chaptersLoadingFor.contains(notebookId) { return }
        chaptersLoadingFor.insert(notebookId)
        defer { chaptersLoadingFor.remove(notebookId) }
        do {
            let chapters = try await repository.loadChapters(notebookId: notebookId)
            chaptersByNotebookId[notebookId] = chapters
        } catch {
            chaptersByNotebookId[notebookId] = []
        }
    }

    private func loadAvailableNotebooks() async {
        guard !loadingNotebooks else { return }
        loadingNotebooks = true
        defer { loadingNotebooks = false }
        do {
            availableNotebooks = try await repository.listNotebooks()
        } catch {
            availableNotebooks = []
            toast = Toast(message: "Couldn't load notebooks")
        }
    }

    /// Triggers the template picker and kicks off the templates-list
    /// fetch so the picker has data ready.
    public func presentTemplatePicker() {
        presentingTemplateSheet = true
        Task { await loadAvailableTemplates() }
    }

    /// Apply the chosen template's pre-filled content onto the
    /// current page. On success, refreshes the loaded page so the
    /// new notes / todos appear immediately, closes the sheet, and
    /// surfaces a toast naming the applied template.
    public func selectTemplate(_ templateId: String) async {
        let title = availableTemplates.first(where: { $0.id == templateId })?.title ?? "template"
        do {
            let updated = try await repository.applyTemplate(toPageId: pageId, templateId: templateId)
            state = .loaded(updated)
            presentingTemplateSheet = false
            toast = Toast(message: "Applied \(title)")
        } catch {
            toast = Toast(message: "Couldn't apply — try again")
        }
    }

    private func loadAvailableTemplates() async {
        guard !loadingTemplates else { return }
        loadingTemplates = true
        defer { loadingTemplates = false }
        do {
            availableTemplates = try await repository.listPageTemplates()
        } catch {
            availableTemplates = []
            toast = Toast(message: "Couldn't load templates")
        }
    }
}
