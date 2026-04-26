/*
 * HomeDashboardViewModel.swift
 *
 * Powers the redesigned Home screen: a compact dashboard of
 * notebook + notepad stat cards.
 * All reads are Room/GRDB-backed — no drive-fake data — so the
 * Home dashboard always reflects what the user has actually
 * captured.
 */

import Foundation
import ReleafData

/// One notebook row on the Home dashboard's Recent list.
public struct NotebookListItem: Identifiable, Equatable {
    public let id: String
    public let title: String
    public let chapterCount: Int
    public let updatedLabel: String
    public let isArchived: Bool
}

/// One notepad row on the Home dashboard's Recent list.
public struct NotepadListItem: Identifiable, Equatable {
    public let id: String
    public let title: String
    public let entryDateLabel: String
    public let photoCount: Int
}

public struct HomeDashboardState: Equatable {
    public var isLoading: Bool
    public var totalNotebooks: Int
    public var activeNotebooks: Int
    public var archivedNotebooks: Int
    public var totalNotepadEntries: Int
    public var todayNotepadCount: Int
    public var totalNotepadPhotos: Int
    public var totalNotepadScans: Int
    public var totalNotepadVoice: Int
    public var totalNotepadContacts: Int
    public var totalNotepadLocations: Int
    public var openNotepadTodos: Int
    public var recentNotebooks: [NotebookListItem]
    public var recentNotepadEntries: [NotepadListItem]
    public var captureCounts: CaptureCountsByMode

    public static let initial = HomeDashboardState(
        isLoading: true,
        totalNotebooks: 0,
        activeNotebooks: 0,
        archivedNotebooks: 0,
        totalNotepadEntries: 0,
        todayNotepadCount: 0,
        totalNotepadPhotos: 0,
        totalNotepadScans: 0,
        totalNotepadVoice: 0,
        totalNotepadContacts: 0,
        totalNotepadLocations: 0,
        openNotepadTodos: 0,
        recentNotebooks: [],
        recentNotepadEntries: [],
        captureCounts: .empty
    )
}

@MainActor
public final class HomeDashboardViewModel: ObservableObject {

    @Published public private(set) var state: HomeDashboardState = .initial

    private let userId: String
    private let notebookRepository: NotebookRepository
    private let notepadRepository: NotepadRepository

    private var tasks: [Task<Void, Never>] = []
    private var latestShelves: [ShelfRecord] = []
    private var latestNotepad: [NotepadEntry] = []

    public init(
        userId: String,
        notebookRepository: NotebookRepository = NotebookRepository(),
        notepadRepository: NotepadRepository = NotepadRepository()
    ) {
        self.userId = userId
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

    deinit { tasks.forEach { $0.cancel() } }

    // MARK: - Actions

    public func createNotebook(onCreated: @escaping (String) -> Void = { _ in }) {
        Task { [notebookRepository] in
            if let entity = try? await notebookRepository.createNotebook(title: "Untitled notebook") {
                await MainActor.run { onCreated(entity.id) }
            }
        }
    }

    public func createNotepadEntry(onCreated: @escaping (String) -> Void = { _ in }) {
        Task { [notepadRepository, userId] in
            if let entry = try? await notepadRepository.create(userId: userId, title: nil, notes: "") {
                await MainActor.run { onCreated(entry.id) }
            }
        }
    }

    // MARK: - Recompute

    private func recompute() {
        let total = latestShelves.count
        let active = latestShelves.count   // iOS schema has no archivedAt today
        let todayStr = Self.todayLocalString()
        let todayCount = latestNotepad.filter { $0.entryDate == todayStr }.count

        let recentNotebooks = latestShelves
            .sorted { ($0.notebook.updatedAt) > ($1.notebook.updatedAt) }
            .prefix(2)
            .map { record in
                NotebookListItem(
                    id: record.notebook.id,
                    title: record.notebook.title.nonEmpty ?? "Untitled notebook",
                    chapterCount: record.chapterCount,
                    updatedLabel: Self.relativeShort(
                        isoDate: record.notebook.updatedAt,
                        now: Date()
                    ),
                    isArchived: false
                )
            }

        let recentEntries = latestNotepad
            .sorted { $0.updatedAt > $1.updatedAt }
            .prefix(2)
            .map { entry -> NotepadListItem in
                let photoCount = entry.attachments.parseAttachments()
                    .filter { $0.type == Attachment.typePhoto }.count
                return NotepadListItem(
                    id: entry.id,
                    title: Self.displayTitle(for: entry),
                    entryDateLabel: Self.humanEntryDate(entry.entryDate),
                    photoCount: photoCount
                )
            }

        // Aggregate notepad-page feature stats across every entry:
        // photos / scans / voice come from the `attachments` JSON
        // discriminated by `type`, todos from `todos` JSON (open
        // only), contacts from the `contacts` JSON column. All are
        // in-memory reductions — no extra DB hit. Declared here
        // (above the `captureCounts` initializer) so the trees-saved
        // hero numbers can read them without a forward reference.
        let parsedAttachments = latestNotepad.map { $0.attachments.parseAttachments() }
        let totalNotepadPhotos   = parsedAttachments.reduce(0) { $0 + $1.filter { $0.type == Attachment.typePhoto }.count }
        let totalNotepadScans    = parsedAttachments.reduce(0) { $0 + $1.filter { $0.type == Attachment.typeScan  }.count }
        let totalNotepadVoice    = parsedAttachments.reduce(0) { $0 + $1.filter { $0.type == Attachment.typeVoice }.count }
        let totalNotepadContacts = latestNotepad.reduce(0) { acc, entry in
            acc + entry.contacts.parseContacts().count
        }
        let totalNotepadLocations = latestNotepad.reduce(0) { acc, entry in
            acc + entry.locations.parseLocations().count
        }
        let openNotepadTodos = latestNotepad.reduce(0) { acc, entry in
            acc + entry.todos.parseTodos().filter { !$0.done }.count
        }

        // Aggregate capture counts for the Home-tab trees-saved hero
        // by summing notebook-side + notepad-side contributions for
        // each mode. Notebook page count stands in as "notes" until
        // the captures-table migration lands with per-page breakdowns
        // of photos / scans / voice / contacts / locations.
        let captureCounts = CaptureCountsByMode(
            notes:     latestShelves.reduce(0) { $0 + $1.pageCount } + latestNotepad.count,
            photos:    totalNotepadPhotos,
            scans:     totalNotepadScans,
            voice:     totalNotepadVoice,
            contacts:  totalNotepadContacts,
            locations: totalNotepadLocations
        )

        state = HomeDashboardState(
            isLoading: false,
            totalNotebooks: total,
            activeNotebooks: active,
            archivedNotebooks: 0,
            totalNotepadEntries: latestNotepad.count,
            todayNotepadCount: todayCount,
            totalNotepadPhotos: totalNotepadPhotos,
            totalNotepadScans: totalNotepadScans,
            totalNotepadVoice: totalNotepadVoice,
            totalNotepadContacts: totalNotepadContacts,
            totalNotepadLocations: totalNotepadLocations,
            openNotepadTodos: openNotepadTodos,
            recentNotebooks: Array(recentNotebooks),
            recentNotepadEntries: Array(recentEntries),
            captureCounts: captureCounts
        )
    }

    // MARK: - Helpers

    private static func todayLocalString() -> String {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd"
        return f.string(from: Date())
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

    private static func humanEntryDate(_ iso: String) -> String {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd"
        guard let date = f.date(from: iso) else { return iso }
        let out = DateFormatter()
        out.dateFormat = "MMM d, yyyy"
        return out.string(from: date)
    }

    /// Best-effort "updated 4 days ago" / "2h ago". Parses ISO-8601 from
    /// the entity's `updatedAt` column.
    private static func relativeShort(isoDate: String, now: Date) -> String {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let parsed = f.date(from: isoDate) ?? {
            f.formatOptions = [.withInternetDateTime]
            return f.date(from: isoDate)
        }()
        guard let date = parsed else { return isoDate }
        let delta = max(0, Int(now.timeIntervalSince(date)))
        if delta < 60         { return "just now" }
        if delta < 3600       { return "\(delta / 60)m ago" }
        if delta < 86_400     { return "\(delta / 3600)h ago" }
        if delta < 2 * 86_400 { return "yesterday" }
        if delta < 7 * 86_400 { return "\(delta / 86_400) days ago" }
        let fmt = DateFormatter()
        fmt.dateFormat = "MMM d"
        return fmt.string(from: date)
    }
}

private extension String {
    var nonEmpty: String? {
        trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : self
    }
}
