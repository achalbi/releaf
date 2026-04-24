/*
 * DriveRepository.swift
 * App-facing storage interface. Maps domain objects ↔ Drive JSON.
 *
 * Every app-facing API goes through this. Views + ViewModels never see
 * `DriveClient` directly.
 */

import Foundation

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
}

/// In-memory fake. Use for previews, tests, and the skeleton signed-in state.
public final class FakeDriveRepository: DriveRepository, @unchecked Sendable {
    private let notebooks: [Notebook]
    private let chaptersByNotebook: [String: [Chapter]]
    private let pagesById: [String: Page]

    public init(
        notebooks: [Notebook] = FakeDriveRepository.seededNotebooks,
        chaptersByNotebook: [String: [Chapter]] = FakeDriveRepository.seededChapters,
        pagesById: [String: Page] = FakeDriveRepository.seededPages
    ) {
        self.notebooks = notebooks
        self.chaptersByNotebook = chaptersByNotebook
        self.pagesById = pagesById
    }

    public func listNotebooks() async throws -> [Notebook] {
        try await Task.sleep(nanoseconds: 150_000_000)
        return notebooks
    }

    public func loadNotebook(id: String) async throws -> NotebookDetail {
        try await Task.sleep(nanoseconds: 150_000_000)
        guard let nb = notebooks.first(where: { $0.id == id }) else { throw DriveError.notFound }
        return NotebookDetail(notebook: nb, chapters: chaptersByNotebook[id] ?? [])
    }

    public func loadChapters(notebookId: String) async throws -> [Chapter] {
        try await Task.sleep(nanoseconds: 100_000_000)
        return chaptersByNotebook[notebookId] ?? []
    }

    public func loadPage(id: String) async throws -> Page {
        try await Task.sleep(nanoseconds: 150_000_000)
        guard let page = pagesById[id] else { throw DriveError.notFound }
        return page
    }

    // MARK: - Seed

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
                title: "Sunday", capturedOn: "Apr 19, 2026"
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
