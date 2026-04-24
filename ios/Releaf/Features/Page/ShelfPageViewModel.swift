/*
 * ShelfPageViewModel.swift
 *
 * Room/GRDB-backed VM for the variant-1 page screen. Observes a
 * single `PageEntity`, parses its JSON side-channels (attachments,
 * todos, contacts, locations), and maps everything onto the domain
 * `Page` shape the variant UI already speaks.
 */

import Foundation
import ReleafData

@MainActor
public final class ShelfPageViewModel: ObservableObject {

    public enum State: Equatable {
        case loading
        case loaded(Page)
        case failed(String)
    }

    @Published public private(set) var state: State = .loading

    private let pageId: String
    private let repository: PageRepository
    private var streamTask: Task<Void, Never>?

    public init(pageId: String, repository: PageRepository = PageRepository()) {
        self.pageId = pageId
        self.repository = repository
    }

    public func start() {
        streamTask?.cancel()
        streamTask = Task { [weak self, repository, pageId] in
            guard let self else { return }
            do {
                for try await entity in repository.observeById(pageId) {
                    if let entity {
                        self.state = .loaded(Self.toDomain(entity))
                    } else {
                        self.state = .failed("Page not found")
                    }
                }
            } catch is CancellationError {
                // view left
            } catch {
                self.state = .failed("Couldn't load page")
            }
        }
    }

    public func stop() {
        streamTask?.cancel()
        streamTask = nil
    }

    deinit { streamTask?.cancel() }

    // MARK: - Mapping

    private static func toDomain(_ entity: PageEntity) -> Page {
        let attachments = entity.attachments.parseAttachments()
        let contacts    = entity.contacts.parseContacts()
        let locations   = entity.locations.parseLocations()
        let todos       = entity.todos.parseTodos()

        let photos = attachments
            .filter { $0.type == Attachment.typePhoto }
            .map { a in
                Photo(id: a.id, driveFileId: nil, caption: nil,
                      capturedAt: parseISO(a.capturedAt) ?? Date(timeIntervalSince1970: 0),
                      width: nil, height: nil)
            }
        let scans = attachments
            .filter { $0.type == Attachment.typeScan }
            .map { a in
                ScannedDocument(
                    id: a.id,
                    driveFileId: nil,
                    title: "Scan",
                    pageCount: 1,
                    scannedAt: parseISO(a.capturedAt) ?? Date(timeIntervalSince1970: 0)
                )
            }

        let noteBlocks = entity.notes
            .components(separatedBy: "\n\n")
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
            .enumerated()
            .map { index, body in
                Note(id: "\(entity.id)-note-\(index)", body: body,
                     createdAt: parseISO(entity.updatedAt) ?? Date(timeIntervalSince1970: 0))
            }

        let domainTodos = todos.enumerated().map { index, t in
            TodoItem(id: t.id, body: t.text, done: t.done, position: index)
        }

        let domainContacts = contacts.map { nc in
            Contact(
                id:    nc.id,
                name:  nc.name,
                phone: nil,
                email: nil,
                notes: nc.role
            )
        }

        let domainLocations = locations.map { loc in
            LocationPin(
                id: loc.id,
                name: loc.address ?? "Pinned location",
                latitude: loc.lat,
                longitude: loc.lng,
                capturedAt: parseISO(loc.capturedAt) ?? Date(timeIntervalSince1970: 0),
                notes: nil
            )
        }

        return Page(
            id:               entity.id,
            notebookId:       "",
            chapterId:        entity.chapterId,
            title:            entity.title?.nonEmpty ?? "Untitled page",
            capturedOn:       humanCapturedOn(entity.createdAt),
            updatedAt:        parseISO(entity.updatedAt) ?? Date(timeIntervalSince1970: 0),
            notes:            noteBlocks,
            photos:           photos,
            voiceNotes:       [],
            todoItems:        domainTodos,
            scannedDocuments: scans,
            contacts:         domainContacts,
            locations:        domainLocations,
            tags:             []
        )
    }
}

// MARK: - Helpers

private func parseISO(_ s: String) -> Date? {
    let f = ISO8601DateFormatter()
    f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    if let d = f.date(from: s) { return d }
    f.formatOptions = [.withInternetDateTime]
    return f.date(from: s)
}

private func humanCapturedOn(_ iso: String) -> String? {
    guard let date = parseISO(iso) else { return nil }
    let f = DateFormatter()
    f.dateFormat = "EEE · MMM d · yyyy"
    return f.string(from: date)
}

private extension String {
    var nonEmpty: String? {
        trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : self
    }
}
