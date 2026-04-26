/*
 * DrawerMetricsViewModel.swift
 *
 * Shell-level view model that aggregates live counts from each
 * feature's GRDB repository and publishes them as formatted
 * subtitle strings for the side-drawer menu items. Matches the
 * Android drawer wiring in MainActivity.SignedInShell.
 *
 * Reminders doesn't have a repository on iOS yet, so its subtitle
 * stays "—" until the table + repo land. Everything else reacts
 * in real time to database changes.
 */

import Foundation
import ReleafData

@MainActor
public final class DrawerMetricsViewModel: ObservableObject {
    public struct State {
        public var librarySubtitle: String   = "—"
        public var notepadSubtitle: String   = "—"
        public var tasksSubtitle: String     = "—"
        public var remindersSubtitle: String = "—"
        public var contactsSubtitle: String  = "—"

        public static let initial = State()
    }

    @Published public private(set) var state: State = .initial

    private var tasks: [Task<Void, Never>] = []
    private var latestNotebookCount: Int = 0
    private var latestShelfCount: Int    = 0
    private var latestNotepadEntries: [NotepadEntry] = []
    private var latestOpenTaskCount: Int = 0
    private var latestContactCount: Int  = 0

    private var userId: String = ""

    public init() {}

    /// Begin observing counts for the signed-in user. Calling `start`
    /// twice is safe — previously spawned tasks are cancelled first.
    public func start(userId: String) {
        self.userId = userId
        stop()

        let notebookRepo = NotebookRepository()
        let shelfRepo    = ShelfRepository()
        let notepadRepo  = NotepadRepository()
        let taskRepo     = TaskRepository()
        let contactRepo  = ContactDirectoryRepository()

        tasks.append(Task { [weak self] in
            guard let self else { return }
            do {
                for try await books in notebookRepo.observeActive() {
                    self.latestNotebookCount = books.count
                    self.recompute()
                }
            } catch {}
        })

        tasks.append(Task { [weak self] in
            guard let self else { return }
            do {
                for try await shelves in shelfRepo.observeActive() {
                    self.latestShelfCount = shelves.count
                    self.recompute()
                }
            } catch {}
        })

        tasks.append(Task { [weak self, userId] in
            guard let self else { return }
            do {
                for try await entries in notepadRepo.observeActive(userId: userId) {
                    self.latestNotepadEntries = entries
                    self.recompute()
                }
            } catch {}
        })

        tasks.append(Task { [weak self, userId] in
            guard let self else { return }
            do {
                for try await count in taskRepo.observeOpenCount(userId: userId) {
                    self.latestOpenTaskCount = count
                    self.recompute()
                }
            } catch {}
        })

        tasks.append(Task { [weak self, userId] in
            guard let self else { return }
            do {
                for try await contacts in contactRepo.observeAll(userId: userId) {
                    self.latestContactCount = contacts.count
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

    // ---- Recompute ----

    private func recompute() {
        let today = Self.todayIso()
        let todayCount = latestNotepadEntries
            .filter { $0.entryDate == today }
            .count
        let entryLabel = latestNotepadEntries.count == 1 ? "entry" : "entries"

        state = State(
            librarySubtitle:   "\(latestNotebookCount) books · \(latestShelfCount) shelves",
            notepadSubtitle:   "\(latestNotepadEntries.count) \(entryLabel) · \(todayCount) today",
            tasksSubtitle:     "\(latestOpenTaskCount) open",
            // Reminders repo isn't on iOS yet — keep a neutral dash
            // until the schema + repo ship.
            remindersSubtitle: "—",
            contactsSubtitle:  "\(latestContactCount) in your circle"
        )
    }

    private static func todayIso() -> String {
        let fmt = DateFormatter()
        fmt.calendar = Calendar(identifier: .iso8601)
        fmt.locale   = Locale(identifier: "en_US_POSIX")
        fmt.dateFormat = "yyyy-MM-dd"
        return fmt.string(from: Date())
    }
}
