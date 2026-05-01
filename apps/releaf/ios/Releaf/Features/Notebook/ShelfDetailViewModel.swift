/*
 * ShelfDetailViewModel.swift
 *
 * Room/GRDB-backed VM for the variant-1 chapters screen. Observes
 * the notebook + its chapters + its pages and re-groups into the
 * domain `NotebookDetail` shape the UI already speaks. Display-only
 * fields (`shelfName`, `volumeNumber`, `iconKey`, `colorToken`) are
 * derived here from what the schema actually persists so the UI
 * doesn't carry duplicate mapping logic.
 */

import Foundation
import ReleafData

@MainActor
public final class ShelfDetailViewModel: ObservableObject {

    public enum State: Equatable {
        case loading
        case loaded(NotebookDetail)
        case failed(String)
    }

    @Published public private(set) var state: State = .loading

    private let notebookId: String
    private let notebookRepository: NotebookRepository
    private let chapterRepository: ChapterRepository
    private let pageRepository: PageRepository

    private var tasks: [Task<Void, Never>] = []
    private var latestNotebook: NotebookEntity?
    private var latestChapters: [ChapterEntity] = []
    private var latestPages: [PageEntity] = []

    public init(
        notebookId: String,
        notebookRepository: NotebookRepository = NotebookRepository(),
        chapterRepository: ChapterRepository = ChapterRepository(),
        pageRepository: PageRepository = PageRepository()
    ) {
        self.notebookId = notebookId
        self.notebookRepository = notebookRepository
        self.chapterRepository = chapterRepository
        self.pageRepository = pageRepository
    }

    public func start() {
        stop()

        tasks.append(Task { [weak self, notebookRepository, notebookId] in
            guard let self else { return }
            do {
                for try await value in notebookRepository.observeById(notebookId) {
                    self.latestNotebook = value
                    self.recompute()
                }
            } catch {}
        })
        tasks.append(Task { [weak self, chapterRepository, notebookId] in
            guard let self else { return }
            do {
                for try await value in chapterRepository.observeForNotebook(notebookId: notebookId) {
                    self.latestChapters = value
                    self.recompute()
                }
            } catch {}
        })
        tasks.append(Task { [weak self, pageRepository, notebookId] in
            guard let self else { return }
            do {
                for try await value in pageRepository.observeForNotebook(notebookId: notebookId) {
                    self.latestPages = value
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

    public func createChapter(title: String = "") {
        let resolved = title.trimmingCharacters(in: .whitespacesAndNewlines)
            .ifEmpty("Untitled chapter")
        Task { [chapterRepository, notebookId] in
            _ = try? await chapterRepository.createChapter(notebookId: notebookId, title: resolved)
        }
    }

    public func createPage(in chapterId: String, onCreated: @escaping (String) -> Void = { _ in }) {
        Task { [pageRepository] in
            if let created = try? await pageRepository.createPage(
                chapterId: chapterId, title: nil, notes: ""
            ) {
                await MainActor.run { onCreated(created.id) }
            }
        }
    }

    /// Add a new volume of this book. Promotes the book into a
    /// series on first tap (via `ensureSeriesFor`) and appends the
    /// next volume. `volumeName` is optional — leave nil to let the
    /// repository render "<series> vol <n>".
    public func addVolume(
        volumeName: String? = nil,
        onCreated: @escaping (String) -> Void = { _ in }
    ) {
        Task { [notebookRepository, notebookId] in
            do {
                let seriesId = try await notebookRepository.ensureSeriesFor(notebookId: notebookId)
                let created = try await notebookRepository.addVolumeToSeries(
                    seriesId:   seriesId,
                    volumeName: volumeName
                )
                await MainActor.run { onCreated(created.id) }
            } catch {
                // Swallow — UI will remain on the current book; the
                // caller sees nothing change, which is the same
                // no-op behavior as other failed writes today.
            }
        }
    }

    // MARK: - Recompute

    private func recompute() {
        guard let entity = latestNotebook else {
            state = latestChapters.isEmpty && latestPages.isEmpty
                ? .loading
                : .failed("Notebook not found")
            return
        }

        let pagesByChapter = Dictionary(grouping: latestPages, by: { $0.chapterId })
        let chapters: [Chapter] = latestChapters.map { chEntity in
            let pages = (pagesByChapter[chEntity.id] ?? [])
                .sorted { a, b in
                    if a.position != b.position { return a.position < b.position }
                    return a.createdAt < b.createdAt
                }
                .map(ShelfMapper.toPageSummary)
            return Chapter(
                id:         chEntity.id,
                notebookId: chEntity.notebookId,
                title:      chEntity.title,
                position:   max(Int(chEntity.position / 1024), 0),
                updatedAt:  ShelfMapper.parseISO(chEntity.updatedAt) ?? Date(timeIntervalSince1970: 0),
                pages:      pages
            )
        }

        let notebook = ShelfMapper.toNotebook(
            entity: entity,
            chapterCount: chapters.count,
            pageCount: latestPages.count
        )

        state = .loaded(NotebookDetail(notebook: notebook, chapters: chapters))
    }
}

// MARK: - Shared entity → domain mapper

enum ShelfMapper {

    static func toNotebook(
        entity: NotebookEntity,
        chapterCount: Int,
        pageCount: Int
    ) -> Notebook {
        let token = colorToken(for: entity.colorHex)
        return Notebook(
            id:           entity.id,
            title:        entity.title,
            description:  nil,
            colorToken:   token,
            position:     max(Int(entity.position / 1024), 0),
            archivedAt:   nil,
            updatedAt:    parseISO(entity.updatedAt) ?? Date(timeIntervalSince1970: 0),
            chapterCount: chapterCount,
            pageCount:    pageCount,
            shelfName:    shelfName(from: entity.title),
            volumeNumber: max(Int(entity.position / 1024), 1),
            status:       .active,
            iconKey:      iconKey(for: token)
        )
    }

    static func toPageSummary(_ entity: PageEntity) -> PageSummary {
        let attachments = entity.attachments.parseAttachments()
        let photos = attachments.filter { $0.type == Attachment.typePhoto }.count
        let scans  = attachments.filter { $0.type == Attachment.typeScan  }.count
        let voice  = attachments.filter { $0.type == Attachment.typeVoice }.count
        return PageSummary(
            id:         entity.id,
            title:      entity.title?.nonEmpty ?? "Untitled page",
            capturedOn: humanDate(entity.createdAt),
            updatedAt:  parseISO(entity.updatedAt) ?? Date(timeIntervalSince1970: 0),
            counts: PageCounts(
                photos: photos,
                voiceNotes: voice,
                scannedDocuments: scans
            ),
            tags: []
        )
    }

    static func colorToken(for hex: String?) -> String {
        let cleaned = hex?.uppercased().replacingOccurrences(of: "#", with: "")
        switch cleaned {
        case "7AA874": return "green"
        case "E07856": return "coral"
        case "F4C430": return "yellow"
        case "B8956A": return "dry"
        case "8E86DB", "6E66BB": return "info"
        default: return "green"
        }
    }

    static func iconKey(for colorToken: String) -> String {
        switch colorToken {
        case "green":  return "plant"
        case "info":   return "chart"
        case "dry":    return "sun"
        case "coral":  return "book"
        case "yellow": return "sun"
        default:       return "plant"
        }
    }

    static func shelfName(from title: String) -> String {
        let trimmed = title.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return "NOTEBOOK" }
        let head = trimmed
            .split(separator: " ", maxSplits: 1, omittingEmptySubsequences: true)
            .first.map(String.init) ?? trimmed
        let clean = head.split(separator: "—").first.map(String.init) ?? head
        let upper = clean.uppercased()
        return upper.isEmpty ? "NOTEBOOK" : upper
    }

    static func parseISO(_ s: String) -> Date? {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let d = f.date(from: s) { return d }
        f.formatOptions = [.withInternetDateTime]
        return f.date(from: s)
    }

    static func humanDate(_ s: String) -> String? {
        guard let date = parseISO(s) else { return nil }
        let f = DateFormatter()
        f.dateFormat = "MMM d, yyyy"
        return f.string(from: date)
    }
}

private extension String {
    var nonEmpty: String? { trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : self }
    func ifEmpty(_ fallback: String) -> String {
        trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? fallback : self
    }
}
