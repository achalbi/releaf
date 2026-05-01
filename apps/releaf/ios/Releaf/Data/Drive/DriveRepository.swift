/*
 * DriveRepository.swift
 * App-facing storage interface. Maps domain objects ↔ Drive JSON.
 *
 * Every app-facing API goes through this. Views + ViewModels never see
 * `DriveClient` directly.
 */

import Foundation
import ReleafCoreDrive  // DriveClient / DriveError / DriveFile (moved in PR #3b)

public struct NotebookDetail: Equatable, Sendable {
    public let notebook: Notebook
    public let chapters: [Chapter]

    public init(notebook: Notebook, chapters: [Chapter]) {
        self.notebook = notebook
        self.chapters = chapters
    }
}

public protocol DriveRepository: AnyObject, Sendable {
    func listNotebooks() async throws -> [Notebook]
    func loadNotebook(id: String) async throws -> NotebookDetail
    func loadChapters(notebookId: String) async throws -> [Chapter]
    func loadPage(id: String) async throws -> Page

    /// Re-parent a page under a different notebook + chapter. When
    /// `toChapterId` is nil the implementation chooses the
    /// destination's first chapter; the picker uses the explicit
    /// arg when the user drills in.
    func movePage(pageId: String, toNotebookId: String, toChapterId: String?) async throws

    /// All templates available to the user — app-seeded plus
    /// user-saved. Returned in display order.
    func listPageTemplates() async throws -> [PageTemplate]

    /// Apply a template's pre-filled content onto an existing page.
    /// Pre-fields prepend / concat onto the page's current content;
    /// applying never deletes captures. Returns the updated page.
    @discardableResult
    func applyTemplate(toPageId pageId: String, templateId: String) async throws -> Page

    /// Soft-delete the page by stamping `archivedAt = now`. Returns
    /// the updated page so the ViewModel can refresh state without a
    /// re-fetch. Idempotent — archiving an already-archived page is
    /// a no-op (returns the existing record).
    @discardableResult
    func archivePage(id: String) async throws -> Page

    /// Inverse of `archivePage` — clears `archivedAt`. Idempotent.
    @discardableResult
    func restorePage(id: String) async throws -> Page

    /// Make a copy of `id` in the same chapter, with a new id and a
    /// suffixed title ("X (copy)"). Notes / todos / captures carry
    /// over by value; the duplicate has its own ids on every nested
    /// row so edits don't leak across copies. Returns the new page.
    @discardableResult
    func duplicatePage(id: String) async throws -> Page

    /// Archived pages across every notebook. Used by the Notebook
    /// list's "Archived" overflow item to surface a flat picker.
    /// Each row carries the notebook + chapter context so the user
    /// can see where the archived page lives.
    func listArchivedPages() async throws -> [ArchivedPage]

    /// Rename a notebook. Returns the updated notebook so the
    /// caller can refresh its loaded view without a re-fetch.
    @discardableResult
    func renameNotebook(id: String, title: String) async throws -> Notebook

    /// Create a new empty chapter under `notebookId`. Position is
    /// chosen by the implementation (typically end-of-list).
    /// Returns the new chapter so the caller can navigate or
    /// scroll to it.
    @discardableResult
    func createChapter(notebookId: String, title: String) async throws -> Chapter

    /// Soft-delete the notebook. Idempotent.
    @discardableResult
    func archiveNotebook(id: String) async throws -> Notebook

    /// Inverse of `archiveNotebook` — clears `archivedAt`. Idempotent.
    @discardableResult
    func restoreNotebook(id: String) async throws -> Notebook

    /// Soft-delete the chapter. Idempotent.
    @discardableResult
    func archiveChapter(id: String) async throws -> Chapter

    /// Inverse of `archiveChapter` — clears `archivedAt`. Idempotent.
    @discardableResult
    func restoreChapter(id: String) async throws -> Chapter

    /// Update a chapter's title. Returns the updated chapter.
    @discardableResult
    func renameChapter(id: String, title: String) async throws -> Chapter

    /// Replace a page's tags with the given list. Order is
    /// preserved exactly; duplicates are de-duped case-insensitively
    /// at the call site, not here. Returns the updated page.
    @discardableResult
    func setPageTags(pageId: String, tags: [String]) async throws -> Page

    /// Create a brand-new empty page in the given notebook + chapter.
    /// The repo allocates a fresh id and timestamps; callers receive
    /// the loaded page back so they can navigate straight to it.
    /// Used by the Quick Capture flow on the home shell, where the
    /// user picks a capture mode and lands inside an empty page
    /// ready to be filled in.
    @discardableResult
    func createPage(notebookId: String, chapterId: String, title: String) async throws -> Page

    /// Resolve a default destination for a fresh page when the user
    /// hasn't picked one explicitly (e.g. the Quick Capture flow on
    /// the home shell). Returns the most-recently-updated active
    /// notebook + its first active chapter. Throws `notFound` when
    /// the user has no active notebooks at all — caller surfaces a
    /// friendly empty state.
    func defaultCaptureDestination() async throws -> (notebookId: String, chapterId: String)
}

/// One entry in the cross-notebook archive picker. The notebook
/// title and chapter title are joined into `notebookTitle / chapterTitle`
/// for the breadcrumb line; full structured fields are present in
/// case a future iteration wants per-component navigation.
public struct ArchivedPage: Identifiable, Equatable, Sendable {
    public let id: String
    public let title: String
    public let notebookId: String
    public let notebookTitle: String
    public let chapterId: String
    public let chapterTitle: String
    public let archivedAt: Date

    public init(
        id: String,
        title: String,
        notebookId: String,
        notebookTitle: String,
        chapterId: String,
        chapterTitle: String,
        archivedAt: Date
    ) {
        self.id = id
        self.title = title
        self.notebookId = notebookId
        self.notebookTitle = notebookTitle
        self.chapterId = chapterId
        self.chapterTitle = chapterTitle
        self.archivedAt = archivedAt
    }
}

/// In-memory fake — preview / test scaffold only. Production
/// surfaces all read + write through `LocalDriveRepository` against
/// the GRDB-backed `ReleafDatabase`; nothing in `Features/...`
/// should reach for `FakeDriveRepository.shared` at runtime.
///
/// Why it stays in the tree:
/// - SwiftUI previews need a synchronous, allocation-cheap repo
///   that doesn't touch the disk. `FakeDriveRepository()` (or
///   `inMemoryFake`) is exactly that.
/// - `seededTemplates` (below) is the canonical template catalog
///   referenced by `LocalDriveRepository.listPageTemplates()`.
///   When templates move to the DB, the static can move with them.
///
/// Mutable storage + a process-wide `shared` instance + an
/// `NSLock` are kept so the few preview hosts that exercise the
/// shared instance (e.g. `Variant1` previews) get session-stable
/// state. They're a no-op for production.
public final class FakeDriveRepository: DriveRepository, @unchecked Sendable {
    /// Process-wide singleton. ViewModels default to this so every
    /// surface in the app reads + writes through the same in-memory
    /// store. Tests / previews can still construct their own
    /// instance with custom seeds when they need isolation.
    public static let shared = FakeDriveRepository()

    private var notebooks: [Notebook]
    private var chaptersByNotebook: [String: [Chapter]]
    private var pagesById: [String: Page]
    private let lock = NSLock()

    public init(
        notebooks: [Notebook] = FakeDriveRepository.seededNotebooks,
        chaptersByNotebook: [String: [Chapter]] = FakeDriveRepository.seededChapters,
        pagesById: [String: Page] = FakeDriveRepository.seededPages
    ) {
        self.notebooks = notebooks
        self.chaptersByNotebook = chaptersByNotebook
        self.pagesById = pagesById
    }

    // MARK: - Lock helpers

    /// Run a state mutation under the repo's lock. Release as soon
    /// as the closure returns so async sleeps stay outside the
    /// critical section.
    private func withLock<T>(_ body: () throws -> T) rethrows -> T {
        lock.lock()
        defer { lock.unlock() }
        return try body()
    }

    /// Locate the chapter whose `id == chapterId` and the notebook
    /// it belongs to. Returns the (notebookId, index) so callers can
    /// mutate `chaptersByNotebook[notebookId][index]` in place.
    /// Caller must already hold the lock.
    private func _locateChapter(_ chapterId: String) -> (notebookId: String, index: Int)? {
        for (nbId, list) in chaptersByNotebook {
            if let idx = list.firstIndex(where: { $0.id == chapterId }) {
                return (nbId, idx)
            }
        }
        return nil
    }

    public func listNotebooks() async throws -> [Notebook] {
        try await Task.sleep(nanoseconds: 150_000_000)
        return withLock { notebooks }
    }

    public func loadNotebook(id: String) async throws -> NotebookDetail {
        try await Task.sleep(nanoseconds: 150_000_000)
        return try withLock {
            guard let nb = notebooks.first(where: { $0.id == id }) else {
                throw DriveError.notFound
            }
            return NotebookDetail(notebook: nb, chapters: chaptersByNotebook[id] ?? [])
        }
    }

    public func loadChapters(notebookId: String) async throws -> [Chapter] {
        try await Task.sleep(nanoseconds: 100_000_000)
        return withLock { chaptersByNotebook[notebookId] ?? [] }
    }

    public func loadPage(id: String) async throws -> Page {
        try await Task.sleep(nanoseconds: 150_000_000)
        return try withLock {
            guard let page = pagesById[id] else { throw DriveError.notFound }
            return page
        }
    }

    public func movePage(pageId: String, toNotebookId: String, toChapterId: String?) async throws {
        try await Task.sleep(nanoseconds: 200_000_000)
        try withLock {
            guard let page = pagesById[pageId] else { throw DriveError.notFound }
            guard notebooks.contains(where: { $0.id == toNotebookId }) else {
                throw DriveError.notFound
            }
            // Resolve the destination chapter — explicit if given,
            // otherwise the destination notebook's first chapter.
            // No chapters in the target → leave chapterId empty
            // (the page lands at the notebook's root for now).
            let chaptersInTarget = chaptersByNotebook[toNotebookId] ?? []
            let resolvedChapterId: String
            if let toChapterId {
                guard chaptersInTarget.contains(where: { $0.id == toChapterId }) else {
                    throw DriveError.notFound
                }
                resolvedChapterId = toChapterId
            } else {
                resolvedChapterId = chaptersInTarget.first?.id ?? ""
            }
            // Persist: rewrite the page entry with new
            // notebookId + chapterId and bump updatedAt.
            pagesById[pageId] = Page(
                id: page.id, notebookId: toNotebookId, chapterId: resolvedChapterId,
                title: page.title, capturedOn: page.capturedOn,
                updatedAt: Date(),
                notes: page.notes, photos: page.photos,
                voiceNotes: page.voiceNotes, todoItems: page.todoItems,
                scannedDocuments: page.scannedDocuments,
                contacts: page.contacts, locations: page.locations,
                tags: page.tags, archivedAt: page.archivedAt
            )
        }
    }

    @discardableResult
    public func archivePage(id: String) async throws -> Page {
        try await Task.sleep(nanoseconds: 150_000_000)
        return try withLock {
            guard let page = pagesById[id] else { throw DriveError.notFound }
            if page.archivedAt != nil { return page }
            let updated = Page(
                id: page.id, notebookId: page.notebookId, chapterId: page.chapterId,
                title: page.title, capturedOn: page.capturedOn,
                updatedAt: Date(),
                notes: page.notes, photos: page.photos, voiceNotes: page.voiceNotes,
                todoItems: page.todoItems, scannedDocuments: page.scannedDocuments,
                contacts: page.contacts, locations: page.locations, tags: page.tags,
                archivedAt: Date()
            )
            pagesById[id] = updated
            return updated
        }
    }

    @discardableResult
    public func restorePage(id: String) async throws -> Page {
        try await Task.sleep(nanoseconds: 150_000_000)
        return try withLock {
            guard let page = pagesById[id] else { throw DriveError.notFound }
            if page.archivedAt == nil { return page }
            let updated = Page(
                id: page.id, notebookId: page.notebookId, chapterId: page.chapterId,
                title: page.title, capturedOn: page.capturedOn,
                updatedAt: Date(),
                notes: page.notes, photos: page.photos, voiceNotes: page.voiceNotes,
                todoItems: page.todoItems, scannedDocuments: page.scannedDocuments,
                contacts: page.contacts, locations: page.locations, tags: page.tags,
                archivedAt: nil
            )
            pagesById[id] = updated
            return updated
        }
    }

    @discardableResult
    public func renameNotebook(id: String, title: String) async throws -> Notebook {
        try await Task.sleep(nanoseconds: 150_000_000)
        return try withLock {
            guard let idx = notebooks.firstIndex(where: { $0.id == id }) else {
                throw DriveError.notFound
            }
            let nb = notebooks[idx]
            let updated = Notebook(
                id: nb.id, title: title, description: nb.description,
                colorToken: nb.colorToken, position: nb.position,
                archivedAt: nb.archivedAt, updatedAt: Date(),
                chapterCount: nb.chapterCount, pageCount: nb.pageCount,
                shelfName: nb.shelfName, volumeNumber: nb.volumeNumber,
                status: nb.status, iconKey: nb.iconKey,
                shelfId: nb.shelfId, seriesId: nb.seriesId,
                seriesVolumeNumber: nb.seriesVolumeNumber,
                volumeLabel: nb.volumeLabel
            )
            notebooks[idx] = updated
            return updated
        }
    }

    @discardableResult
    public func createChapter(notebookId: String, title: String) async throws -> Chapter {
        try await Task.sleep(nanoseconds: 150_000_000)
        return try withLock {
            guard notebooks.contains(where: { $0.id == notebookId }) else {
                throw DriveError.notFound
            }
            let existing = chaptersByNotebook[notebookId] ?? []
            let nextPosition = (existing.map(\.position).max() ?? 0) + 1
            let resolved = title.trimmingCharacters(in: .whitespacesAndNewlines)
            let chapter = Chapter(
                id: "ch-\(UUID().uuidString.prefix(8).lowercased())",
                notebookId: notebookId,
                title: resolved.isEmpty ? "Untitled chapter" : resolved,
                position: nextPosition,
                updatedAt: Date(),
                pages: []
            )
            chaptersByNotebook[notebookId, default: []].append(chapter)
            return chapter
        }
    }

    @discardableResult
    public func archiveNotebook(id: String) async throws -> Notebook {
        try await Task.sleep(nanoseconds: 150_000_000)
        return try withLock {
            guard let idx = notebooks.firstIndex(where: { $0.id == id }) else {
                throw DriveError.notFound
            }
            let nb = notebooks[idx]
            if nb.archivedAt != nil { return nb }
            let updated = Notebook(
                id: nb.id, title: nb.title, description: nb.description,
                colorToken: nb.colorToken, position: nb.position,
                archivedAt: Date(), updatedAt: Date(),
                chapterCount: nb.chapterCount, pageCount: nb.pageCount,
                shelfName: nb.shelfName, volumeNumber: nb.volumeNumber,
                status: .archived, iconKey: nb.iconKey,
                shelfId: nb.shelfId, seriesId: nb.seriesId,
                seriesVolumeNumber: nb.seriesVolumeNumber,
                volumeLabel: nb.volumeLabel
            )
            notebooks[idx] = updated
            return updated
        }
    }

    @discardableResult
    public func archiveChapter(id: String) async throws -> Chapter {
        try await Task.sleep(nanoseconds: 150_000_000)
        return try withLock {
            guard let location = _locateChapter(id) else { throw DriveError.notFound }
            let chapter = chaptersByNotebook[location.notebookId]![location.index]
            if chapter.archivedAt != nil { return chapter }
            let updated = Chapter(
                id: chapter.id, notebookId: chapter.notebookId,
                title: chapter.title, position: chapter.position,
                updatedAt: Date(), pages: chapter.pages,
                archivedAt: Date()
            )
            chaptersByNotebook[location.notebookId]![location.index] = updated
            return updated
        }
    }

    @discardableResult
    public func restoreNotebook(id: String) async throws -> Notebook {
        try await Task.sleep(nanoseconds: 150_000_000)
        return try withLock {
            guard let idx = notebooks.firstIndex(where: { $0.id == id }) else {
                throw DriveError.notFound
            }
            let nb = notebooks[idx]
            if nb.archivedAt == nil { return nb }
            let updated = Notebook(
                id: nb.id, title: nb.title, description: nb.description,
                colorToken: nb.colorToken, position: nb.position,
                archivedAt: nil, updatedAt: Date(),
                chapterCount: nb.chapterCount, pageCount: nb.pageCount,
                shelfName: nb.shelfName, volumeNumber: nb.volumeNumber,
                status: .active, iconKey: nb.iconKey,
                shelfId: nb.shelfId, seriesId: nb.seriesId,
                seriesVolumeNumber: nb.seriesVolumeNumber,
                volumeLabel: nb.volumeLabel
            )
            notebooks[idx] = updated
            return updated
        }
    }

    @discardableResult
    public func setPageTags(pageId: String, tags: [String]) async throws -> Page {
        try await Task.sleep(nanoseconds: 150_000_000)
        return try withLock {
            guard let page = pagesById[pageId] else { throw DriveError.notFound }
            let updated = Page(
                id: page.id, notebookId: page.notebookId, chapterId: page.chapterId,
                title: page.title, capturedOn: page.capturedOn,
                updatedAt: Date(),
                notes: page.notes, photos: page.photos,
                voiceNotes: page.voiceNotes, todoItems: page.todoItems,
                scannedDocuments: page.scannedDocuments,
                contacts: page.contacts, locations: page.locations,
                tags: tags,
                archivedAt: page.archivedAt
            )
            pagesById[pageId] = updated
            return updated
        }
    }

    @discardableResult
    public func restoreChapter(id: String) async throws -> Chapter {
        try await Task.sleep(nanoseconds: 150_000_000)
        return try withLock {
            guard let location = _locateChapter(id) else { throw DriveError.notFound }
            let chapter = chaptersByNotebook[location.notebookId]![location.index]
            if chapter.archivedAt == nil { return chapter }
            let updated = Chapter(
                id: chapter.id, notebookId: chapter.notebookId,
                title: chapter.title, position: chapter.position,
                updatedAt: Date(), pages: chapter.pages,
                archivedAt: nil
            )
            chaptersByNotebook[location.notebookId]![location.index] = updated
            return updated
        }
    }

    @discardableResult
    public func renameChapter(id: String, title: String) async throws -> Chapter {
        try await Task.sleep(nanoseconds: 150_000_000)
        let trimmed = title.trimmingCharacters(in: .whitespacesAndNewlines)
        let resolved = trimmed.isEmpty ? "Untitled chapter" : trimmed
        return try withLock {
            guard let location = _locateChapter(id) else { throw DriveError.notFound }
            let chapter = chaptersByNotebook[location.notebookId]![location.index]
            let updated = Chapter(
                id: chapter.id, notebookId: chapter.notebookId,
                title: resolved, position: chapter.position,
                updatedAt: Date(), pages: chapter.pages,
                archivedAt: chapter.archivedAt
            )
            chaptersByNotebook[location.notebookId]![location.index] = updated
            return updated
        }
    }

    public func listArchivedPages() async throws -> [ArchivedPage] {
        try await Task.sleep(nanoseconds: 150_000_000)
        return withLock {
            // Build a quick lookup from chapterId → (notebookId,
            // chapter title) so we can fan archived-page rows out
            // with full breadcrumb context in one pass.
            let chapterIndex: [String: (notebookId: String, chapterTitle: String)] =
                chaptersByNotebook.flatMap { _, list in
                    list.map { ($0.id, ($0.notebookId, $0.title)) }
                }.reduce(into: [:]) { acc, pair in
                    acc[pair.0] = pair.1
                }
            let notebookTitle: [String: String] = Dictionary(
                uniqueKeysWithValues: notebooks.map { ($0.id, $0.title) }
            )
            let archivedPages = pagesById.values.compactMap { page -> ArchivedPage? in
                guard let archivedAt = page.archivedAt else { return nil }
                let chapter = chapterIndex[page.chapterId]
                return ArchivedPage(
                    id: page.id,
                    title: page.title,
                    notebookId: page.notebookId,
                    notebookTitle: notebookTitle[page.notebookId] ?? "",
                    chapterId: page.chapterId,
                    chapterTitle: chapter?.chapterTitle ?? "",
                    archivedAt: archivedAt
                )
            }
            return archivedPages.sorted { $0.archivedAt > $1.archivedAt }
        }
    }

    @discardableResult
    public func duplicatePage(id: String) async throws -> Page {
        try await Task.sleep(nanoseconds: 200_000_000)
        return try withLock {
            guard let page = pagesById[id] else { throw DriveError.notFound }
            let newId = "dup-\(UUID().uuidString.prefix(8).lowercased())"
            let copy = Page(
                id: newId,
                notebookId: page.notebookId,
                chapterId: page.chapterId,
                title: "\(page.title) (copy)",
                capturedOn: page.capturedOn,
                updatedAt: Date(),
                notes: page.notes.map {
                    Note(id: "\(newId)-n-\($0.id)", body: $0.body, createdAt: $0.createdAt)
                },
                photos: page.photos.map {
                    Photo(id: "\(newId)-ph-\($0.id)", driveFileId: $0.driveFileId,
                          caption: $0.caption, capturedAt: $0.capturedAt,
                          width: $0.width, height: $0.height)
                },
                voiceNotes: page.voiceNotes.map {
                    VoiceNote(id: "\(newId)-v-\($0.id)", driveFileId: $0.driveFileId,
                              durationMs: $0.durationMs, recordedAt: $0.recordedAt,
                              transcription: $0.transcription)
                },
                todoItems: page.todoItems.map {
                    TodoItem(id: "\(newId)-t-\($0.id)", body: $0.body,
                             done: $0.done, position: $0.position)
                },
                scannedDocuments: page.scannedDocuments.map {
                    ScannedDocument(id: "\(newId)-s-\($0.id)", driveFileId: $0.driveFileId,
                                    title: $0.title, pageCount: $0.pageCount,
                                    scannedAt: $0.scannedAt)
                },
                contacts: page.contacts.map {
                    Contact(id: "\(newId)-c-\($0.id)", name: $0.name,
                            phone: $0.phone, email: $0.email, notes: $0.notes)
                },
                locations: page.locations.map {
                    LocationPin(id: "\(newId)-l-\($0.id)", name: $0.name,
                                latitude: $0.latitude, longitude: $0.longitude,
                                capturedAt: $0.capturedAt, notes: $0.notes)
                },
                tags: page.tags
            )
            pagesById[newId] = copy
            return copy
        }
    }

    public func listPageTemplates() async throws -> [PageTemplate] {
        try await Task.sleep(nanoseconds: 100_000_000)
        return Self.seededTemplates
    }

    @discardableResult
    public func applyTemplate(toPageId pageId: String, templateId: String) async throws -> Page {
        try await Task.sleep(nanoseconds: 200_000_000)
        return try withLock {
            guard let page = pagesById[pageId] else { throw DriveError.notFound }
            guard let template = Self.seededTemplates.first(where: { $0.id == templateId }) else {
                throw DriveError.notFound
            }
            // Make template-derived ids unique per application — using
            // the destination pageId in the prefix means a user who
            // re-applies a template on different pages doesn't get
            // colliding note/todo ids in the in-memory store.
            let newNotes = template.preNotes.enumerated().map { idx, body in
                Note(id: "tmpl-n-\(template.id)-\(pageId)-\(idx)", body: body)
            }
            let basePosition = (page.todoItems.map(\.position).max() ?? -1) + 1
            let newTodos = template.preTodos.enumerated().map { idx, body in
                TodoItem(
                    id: "tmpl-t-\(template.id)-\(pageId)-\(idx)",
                    body: body,
                    position: basePosition + idx
                )
            }
            let updated = Page(
                id: page.id,
                notebookId: page.notebookId,
                chapterId: page.chapterId,
                title: page.title,
                capturedOn: page.capturedOn,
                updatedAt: Date(),
                notes: page.notes + newNotes,
                photos: page.photos,
                voiceNotes: page.voiceNotes,
                todoItems: page.todoItems + newTodos,
                scannedDocuments: page.scannedDocuments,
                contacts: page.contacts,
                locations: page.locations,
                tags: page.tags
            )
            pagesById[pageId] = updated
            return updated
        }
    }

    @discardableResult
    public func createPage(
        notebookId: String,
        chapterId: String,
        title: String
    ) async throws -> Page {
        try await Task.sleep(nanoseconds: 150_000_000)
        return try withLock {
            // Validate the destination exists. Quick Capture builds
            // its destination off `defaultCaptureDestination()` so
            // these checks normally pass; the throw guards against
            // explicit callers passing stale ids.
            guard notebooks.contains(where: { $0.id == notebookId }) else {
                throw DriveError.notFound
            }
            let chaptersInNotebook = chaptersByNotebook[notebookId] ?? []
            guard chaptersInNotebook.contains(where: { $0.id == chapterId }) else {
                throw DriveError.notFound
            }
            let now = Date()
            let resolvedTitle = title.trimmingCharacters(in: .whitespacesAndNewlines)
            // Match the seed-data format ("Sat · Apr 25 · 2026") so
            // newly captured pages display consistently in the
            // existing list rows.
            let formatter = DateFormatter()
            formatter.dateFormat = "EEE · MMM d · yyyy"
            let page = Page(
                id: "pg-\(UUID().uuidString.prefix(8).lowercased())",
                notebookId: notebookId,
                chapterId: chapterId,
                title: resolvedTitle.isEmpty ? "New page" : resolvedTitle,
                capturedOn: formatter.string(from: now),
                updatedAt: now,
                notes: [],
                photos: [],
                voiceNotes: [],
                todoItems: [],
                scannedDocuments: [],
                contacts: [],
                locations: [],
                tags: [],
                archivedAt: nil
            )
            pagesById[page.id] = page
            return page
        }
    }

    public func defaultCaptureDestination() async throws -> (notebookId: String, chapterId: String) {
        try await Task.sleep(nanoseconds: 50_000_000)
        return try withLock {
            // Pick the most-recently-updated active notebook (i.e.
            // not soft-deleted, not archived) so users land in the
            // surface they were last working in. Then take that
            // notebook's first active chapter for the destination.
            let active = notebooks.filter { $0.archivedAt == nil }
            guard let notebook = active.max(by: { $0.updatedAt < $1.updatedAt }) else {
                throw DriveError.notFound
            }
            let chapters = (chaptersByNotebook[notebook.id] ?? [])
                .filter { $0.archivedAt == nil }
                .sorted { $0.position < $1.position }
            guard let chapter = chapters.first else {
                throw DriveError.notFound
            }
            return (notebook.id, chapter.id)
        }
    }

    // MARK: - Seed

    /// Seeded set of page templates surfaced by `listPageTemplates()`.
    /// Hand-curated to cover the most common shapes the variant-1
    /// "what arrived?" surface invites. Order is the display order.
    public static let seededTemplates: [PageTemplate] = [
        PageTemplate(
            id: "tmpl-walk",
            title: "Daily walk",
            description: "Three to-dos for a walk and a place to drop a thought.",
            iconKey: "plant",
            preNotes: [
                "What surprised me on the walk today —"
            ],
            preTodos: [
                "Stretch before heading out",
                "Photograph one new thing",
                "Stop somewhere I haven't before"
            ]
        ),
        PageTemplate(
            id: "tmpl-recipe",
            title: "Recipe",
            description: "Ingredients on the left, steps on the right.",
            iconKey: "coffee",
            preNotes: [
                "INGREDIENTS\n— \n— \n— ",
                "METHOD\n1. \n2. \n3. ",
                "NOTES\nWhat I'd change next time —"
            ]
        ),
        PageTemplate(
            id: "tmpl-meeting",
            title: "Meeting notes",
            description: "Attendees, agenda, decisions, follow-ups.",
            iconKey: "chart",
            preNotes: [
                "ATTENDEES\n— ",
                "AGENDA\n1. \n2. ",
                "DECISIONS\n— ",
                "FOLLOW-UPS\n— "
            ],
            preTodos: [
                "Send minutes within 24 hours"
            ]
        ),
        PageTemplate(
            id: "tmpl-field",
            title: "Field journal",
            description: "Date, weather, observations, sketch.",
            iconKey: "sun",
            preNotes: [
                "WEATHER\n",
                "OBSERVATIONS\n— ",
                "SKETCH\n(snap a photo or doodle)"
            ]
        ),
        PageTemplate(
            id: "tmpl-morning",
            title: "Morning pages",
            description: "Three blank pages, no rules, no editing.",
            iconKey: "book",
            preNotes: ["", "", ""]
        ),
    ]

    public static let seededNotebooks: [Notebook] = [
        Notebook(
            id: "nb-1", title: "Plant log 2026",
            description: "A month off to walk, write, and cook again.",
            colorToken: "green", position: 0,
            updatedAt: Date().addingTimeInterval(-2 * 3600),
            chapterCount: 12, pageCount: 47,
            shelfName: "GARDEN", volumeNumber: 2,
            status: .active, iconKey: "plant"
        ),
        Notebook(
            id: "nb-2", title: "Sprint notes",
            description: "Three pages every day, in any form.",
            colorToken: "info", position: 1,
            updatedAt: Date().addingTimeInterval(-30 * 60),
            chapterCount: 6, pageCount: 28,
            shelfName: "WORK", volumeNumber: 7,
            status: .active, iconKey: "chart"
        ),
        Notebook(
            id: "nb-3", title: "Morning pages",
            description: "Things that worked at least twice.",
            colorToken: "dry",  position: 2,
            updatedAt: Date().addingTimeInterval(-24 * 3600),
            chapterCount: 5, pageCount: 33,
            shelfName: "DAILY", volumeNumber: 12,
            status: .paused, iconKey: "sun"
        ),
    ]

    public static let seededChapters: [String: [Chapter]] = [
        "nb-1": [
            Chapter(id: "ch-1", notebookId: "nb-1", title: "Seedling diary — week 3",
                position: 7,
                updatedAt: Date().addingTimeInterval(-2 * 3600),
                pages: [
                    PageSummary(id: "pg-1", title: "Seedlings found the light",
                        capturedOn: "Fri · Apr 24 · 2026",
                        updatedAt: Date().addingTimeInterval(-2 * 3600),
                        counts: PageCounts(photos: 2, voiceNotes: 1, todoItems: 3, locations: 1),
                        tags: ["tomato", "basil", "windowsill"]),
                    PageSummary(id: "pg-2", title: "Coffee shop, 9am",
                        capturedOn: "Apr 13, 2026",
                        counts: PageCounts(voiceNotes: 1, scannedDocuments: 1, contacts: 1)),
                ]),
            Chapter(id: "ch-2", notebookId: "nb-1", title: "Soil mix experiments",
                position: 6,
                pages: [
                    PageSummary(id: "pg-3", title: "Farmers market",
                        capturedOn: "Apr 19, 2026",
                        counts: PageCounts(photos: 3, todoItems: 2, contacts: 2)),
                    PageSummary(id: "pg-4", title: "Evening draft",
                        capturedOn: "Apr 20, 2026",
                        counts: PageCounts(todoItems: 5)),
                ]),
            Chapter(id: "ch-5", notebookId: "nb-1", title: "Herbs windowsill setup",
                position: 5, pages: []),
            Chapter(id: "ch-6", notebookId: "nb-1", title: "Composter first turn",
                position: 4, pages: []),
            Chapter(id: "ch-7", notebookId: "nb-1", title: "Seed order & sourcing",
                position: 3, pages: []),
            Chapter(id: "ch-8", notebookId: "nb-1", title: "Garden plan — layout",
                position: 2, pages: []),
        ],
        "nb-2": [
            Chapter(id: "ch-3", notebookId: "nb-2", title: "April", pages: [
                PageSummary(id: "pg-5", title: "Monday",
                    capturedOn: "Apr 20, 2026",
                    counts: PageCounts(voiceNotes: 1)),
                PageSummary(id: "pg-6", title: "Sunday",
                    capturedOn: "Apr 19, 2026"),
            ]),
        ],
        "nb-3": [
            Chapter(id: "ch-4", notebookId: "nb-3", title: "Bread", pages: [
                PageSummary(id: "pg-7", title: "Olive focaccia",
                    capturedOn: "Apr 10, 2026",
                    counts: PageCounts(photos: 1, todoItems: 6, scannedDocuments: 1)),
            ]),
        ],
    ]

    public static let seededPages: [String: Page] = {
        let list: [Page] = [
            Page(
                id: "pg-1", notebookId: "nb-1", chapterId: "ch-1",
                title: "Seedlings found the light",
                capturedOn: "Fri · Apr 24 · 2026",
                notes: [
                    Note(id: "n1", body:
                        "Moved the tray two feet closer to the window this morning. The " +
                        "tomato sprouts that were leaning are already righting themselves " +
                        "— by lunch, two had opened their first true leaves."),
                    Note(id: "n2", body:
                        "The basil is still sulking. Three weeks in and only the heartiest " +
                        "seedling has broken through. I'll give the rest until Sunday " +
                        "before starting fresh."),
                    Note(id: "n-quote", body:
                        "NOTE TO SELF\nRotate the tray each morning — they're always " +
                        "reaching one way."),
                ],
                photos: [
                    Photo(id: "ph1", caption: "TRAY A", width: 1620, height: 2160),
                    Photo(id: "ph2", caption: "DETAIL · 11AM", width: 2160, height: 1620),
                ],
                voiceNotes: [
                    VoiceNote(id: "v1", durationMs: 47_000,
                        transcription:
                            "A thing I keep coming back to — the distinction between being " +
                            "busy and being engaged. I want to stop optimizing for busy."),
                ],
                todoItems: [
                    TodoItem(id: "t1", body: "Pick up framing quote for the heron print", position: 0),
                    TodoItem(id: "t2", body: "Email the ranger about the trail closure",  position: 1),
                    TodoItem(id: "t3", body: "Charge the camera for tomorrow", done: true, position: 2),
                ],
                locations: [
                    LocationPin(id: "l1", name: "Second bridge, Arcata Marsh",
                                latitude: 40.8541, longitude: -124.0876,
                                notes: "Bring the longer lens next time."),
                ],
                tags: ["tomato", "basil", "windowsill"]
            ),
            Page(
                id: "pg-2", notebookId: "nb-1", chapterId: "ch-1",
                title: "Coffee shop, 9am", capturedOn: "Apr 13, 2026",
                notes: [
                    Note(id: "n3", body:
                        "Met Priya at Old Town. She's two years into the same sabbatical " +
                        "conversation I'm having now and had a lot to say about stop-dates."),
                ],
                voiceNotes: [
                    VoiceNote(id: "v2", durationMs: 112_000,
                        transcription:
                            "Priya's argument — if you don't set a return date, it's not " +
                            "a sabbatical, it's a resignation."),
                ],
                scannedDocuments: [
                    ScannedDocument(id: "s1", title: "Priya's reading list", pageCount: 2),
                ],
                contacts: [
                    Contact(id: "c1", name: "Priya Ramanathan",
                            phone: "+1 707 555 0144", email: "priya@priya.works",
                            notes: "Introduced by Sam. Follow up in June."),
                ]
            ),
            Page(
                id: "pg-3", notebookId: "nb-1", chapterId: "ch-2",
                title: "Farmers market", capturedOn: "Apr 19, 2026",
                notes: [
                    Note(id: "n4", body:
                        "Asparagus stand had the first local ones of the year. Picked up a " +
                        "business card from the flower farm — their ranunculus are absurd."),
                ],
                photos: [
                    Photo(id: "ph3", caption: "Asparagus, 7am"),
                    Photo(id: "ph4", caption: "Ranunculus bouquet"),
                    Photo(id: "ph5", caption: "Strawberries, not yet"),
                ],
                todoItems: [
                    TodoItem(id: "t4", body: "Order two pints of strawberries next Saturday", position: 0),
                    TodoItem(id: "t5", body: "Try the duck confit from the butcher booth",  position: 1),
                ],
                contacts: [
                    Contact(id: "c2", name: "Fog Hollow Farm",
                            phone: "+1 707 555 0912", email: "hello@foghollow.farm"),
                    Contact(id: "c3", name: "Ed, the butcher",
                            notes: "Saturdays only, cash preferred."),
                ]
            ),
            Page(
                id: "pg-4", notebookId: "nb-1", chapterId: "ch-2",
                title: "Evening draft", capturedOn: "Apr 20, 2026",
                todoItems: [
                    TodoItem(id: "t6",  body: "Outline chapter two",  done: true, position: 0),
                    TodoItem(id: "t7",  body: "Re-read last page of chapter one", position: 1),
                    TodoItem(id: "t8",  body: "Find that Annie Dillard quote",    position: 2),
                    TodoItem(id: "t9",  body: "Cut the second paragraph",         position: 3),
                    TodoItem(id: "t10", body: "Print draft for tomorrow's walk",  position: 4),
                ]
            ),
            Page(
                id: "pg-5", notebookId: "nb-2", chapterId: "ch-3",
                title: "Monday", capturedOn: "Apr 20, 2026",
                voiceNotes: [
                    VoiceNote(id: "v3", durationMs: 198_000,
                        transcription:
                            "Three pages, but spoken. One — the sabbatical is three weeks " +
                            "in and I haven't opened a single work tab. Two — I keep waiting " +
                            "for a breakthrough and I should stop. Three — the point was rest."),
                ]
            ),
            Page(
                id: "pg-6", notebookId: "nb-2", chapterId: "ch-3",
                title: "Sunday", capturedOn: "Apr 19, 2026",
                // Pre-seeded as archived so the cross-notebook
                // archive picker has at least one row to render
                // from the FakeDriveRepository state.
                archivedAt: Date().addingTimeInterval(-3 * 24 * 3600)
            ),
            Page(
                id: "pg-7", notebookId: "nb-3", chapterId: "ch-4",
                title: "Olive focaccia", capturedOn: "Apr 10, 2026",
                notes: [
                    Note(id: "n5", body:
                        "500g flour, 400g water, 10g salt, 5g dry yeast, 50g olive oil. " +
                        "Mix, rest 20 min, three stretch-and-folds, cold ferment 18-24 hours. " +
                        "Dimple with oil and salt. 230°C convection, 22 min."),
                ],
                photos: [Photo(id: "ph6", caption: "Final loaf, take three")],
                todoItems: [
                    TodoItem(id: "t11", body: "Buy Castelvetrano olives",         done: true, position: 0),
                    TodoItem(id: "t12", body: "Weigh flour to 500g exactly",      done: true, position: 1),
                    TodoItem(id: "t13", body: "Do three stretch-and-folds, 30 min apart", position: 2),
                    TodoItem(id: "t14", body: "Cold ferment overnight",           position: 3),
                    TodoItem(id: "t15", body: "Dimple with oil, flaky salt",      position: 4),
                    TodoItem(id: "t16", body: "Bake 230°C, 22 min",               position: 5),
                ],
                scannedDocuments: [
                    ScannedDocument(id: "s2", title: "Original scribbled recipe", pageCount: 1),
                ]
            ),
        ]
        return Dictionary(uniqueKeysWithValues: list.map { ($0.id, $0) })
    }()
}

public extension DriveRepository where Self == FakeDriveRepository {
    /// Convenience: `DriveRepository.inMemoryFake` for previews.
    static var inMemoryFake: FakeDriveRepository { FakeDriveRepository() }
}
