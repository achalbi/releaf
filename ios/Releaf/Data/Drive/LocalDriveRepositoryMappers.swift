/*
 * LocalDriveRepositoryMappers.swift
 *
 * Bidirectional mappers between the GRDB persistence shapes
 * (`*Entity` records) and the Drive-facing domain shapes (`Page`,
 * `Notebook`, `Chapter`, etc. in `Data/Domain/Models.swift`).
 *
 * The persistence layer was modelled around the Notepad's JSON-in-
 * column convention (`contacts`, `locations`, `todos`,
 * `attachments`, `tags` are all TEXT columns containing serialised
 * JSON arrays). The domain layer is richer and typed —
 * `[Contact]`, `[LocationPin]`, `[TodoItem]`, separate `[Photo]` /
 * `[VoiceNote]` / `[ScannedDocument]` arrays demuxed from the one
 * `attachments` column by `Attachment.type`.
 *
 * The mapping is intentionally lossy in places — the persistence
 * `Attachment` doesn't carry every field the domain `Photo` /
 * `ScannedDocument` declare (no `caption`, no `pageCount`, etc.).
 * Those fields default to nil/0 on the read side. The capture UI
 * doesn't currently expose them for editing, so the round-trip
 * stays consistent in practice.
 */

import Foundation

// MARK: - Date helpers

private let isoFormatterFractional: ISO8601DateFormatter = {
    let f = ISO8601DateFormatter()
    f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    return f
}()

private let isoFormatter: ISO8601DateFormatter = {
    let f = ISO8601DateFormatter()
    f.formatOptions = [.withInternetDateTime]
    return f
}()

/// Parse the `created_at` / `updated_at` / `archived_at` ISO strings
/// the schema persists. Tries the fractional-seconds form first
/// (the schema's `strftime('%Y-%m-%dT%H:%M:%fZ', 'now')` default
/// emits this) and falls back to the plain form for hand-written
/// timestamps.
internal func parseISODate(_ s: String?) -> Date? {
    guard let s else { return nil }
    if let d = isoFormatterFractional.date(from: s) { return d }
    return isoFormatter.date(from: s)
}

/// Format a `Date` for storage. Always emits the fractional form so
/// reads round-trip cleanly through `parseISODate`.
internal func formatISODate(_ d: Date) -> String {
    isoFormatterFractional.string(from: d)
}

// MARK: - Color token <-> hex

/// The four leaf-theme primary hexes that the design system
/// resolves through `ShelfTheme.palette(...)`. Keeping the lookup
/// here so the data layer doesn't depend on DesignSystem.
internal enum LeafTokenHex {
    static let coral  = "#E07856"
    static let green  = "#7AA874"
    static let yellow = "#F4C430"
    static let dry    = "#B8956A"

    /// Inverse map: hex string → token name. Returns nil when the
    /// hex doesn't match one of the four canonical leaf themes —
    /// caller falls through to a nil colorToken (UI defaults to
    /// the global accent palette).
    static func token(forHex hex: String?) -> String? {
        let normalized = hex?.uppercased().replacingOccurrences(of: "#", with: "")
        switch normalized {
        case "E07856": return "coral"
        case "7AA874": return "green"
        case "F4C430": return "yellow"
        case "B8956A": return "dry"
        default:       return nil
        }
    }

    /// Forward map: token name → canonical hex with leading `#`.
    /// nil token round-trips as nil so unknown tokens don't silently
    /// land in the persisted column.
    static func hex(forToken token: String?) -> String? {
        switch token?.lowercased() {
        case "coral":  return coral
        case "green":  return green
        case "yellow": return yellow
        case "dry":    return dry
        default:       return nil
        }
    }
}

// MARK: - Tags JSON

private let jsonEncoderTags: JSONEncoder = {
    let e = JSONEncoder()
    e.outputFormatting = [.withoutEscapingSlashes]
    return e
}()

private let jsonDecoderTags = JSONDecoder()

internal func parseTagsJson(_ json: String) -> [String] {
    guard !json.isEmpty,
          let data = json.data(using: .utf8) else { return [] }
    return (try? jsonDecoderTags.decode([String].self, from: data)) ?? []
}

internal func encodeTagsJson(_ tags: [String]) -> String {
    guard let data = try? jsonEncoderTags.encode(tags),
          let s = String(data: data, encoding: .utf8) else { return "[]" }
    return s
}

// MARK: - PageEntity ↔ Page

extension PageEntity {

    /// Decode the JSON child columns + scalar fields into the
    /// Drive-facing `Page` shape used by ViewModels and views.
    /// Caller passes `notebookId` because PageEntity stores only
    /// `chapterId` — the parent notebook is resolved one level up.
    func toDomainPage(notebookId: String) -> Page {
        // Decode the JSON child columns.
        let notepadContacts: [NotepadContact] = contacts.parseContacts()
        let notepadLocs:     [GeoLocation]    = locations.parseLocations()
        let notepadTodos:    [NotepadTodo]    = todos.parseTodos()
        let attachmentList:  [Attachment]     = attachments.parseAttachments()
        let tagList:         [String]         = parseTagsJson(tags)

        // Demux the `attachments` array into the three typed
        // domain collections by `type`. Unknown types fall through
        // and are silently dropped — better to lose an exotic
        // attachment than crash the page load.
        var photos: [Photo] = []
        var voices: [VoiceNote] = []
        var scans:  [ScannedDocument] = []
        for att in attachmentList {
            let captured = parseISODate(att.capturedAt) ?? Date()
            switch att.type {
            case Attachment.typePhoto:
                photos.append(Photo(
                    id: att.id,
                    driveFileId: att.uri,
                    caption: nil,
                    capturedAt: captured,
                    width: nil,
                    height: nil
                ))
            case Attachment.typeVoice:
                voices.append(VoiceNote(
                    id: att.id,
                    driveFileId: att.uri,
                    durationMs: att.durationMs ?? 0,
                    recordedAt: captured,
                    transcription: att.transcript
                ))
            case Attachment.typeScan:
                scans.append(ScannedDocument(
                    id: att.id,
                    driveFileId: att.uri,
                    title: att.previewUri ?? "Scan",
                    pageCount: 1,
                    scannedAt: captured
                ))
            default:
                continue
            }
        }

        // Notes have two representations on disk:
        //   - `notes` — the markdown blob FTS indexes against.
        //     Joined bodies; loses individual identity.
        //   - `pageNotesJson` — the typed array (id + body +
        //     createdAt per element). Source of truth as of v7.
        // Prefer the typed array when populated; fall back to
        // wrapping the legacy markdown as one synthesized Note for
        // rows written before v7 (the migration backfills the
        // column to `"[]"` so legacy rows look "empty" here and
        // trigger the fallback).
        let storedNotes = pageNotesJson.parsePageNotes()
        let noteList: [Note]
        if !storedNotes.isEmpty {
            noteList = storedNotes.map { stored in
                Note(
                    id: stored.id,
                    body: stored.body,
                    createdAt: parseISODate(stored.createdAt) ?? Date()
                )
            }
        } else {
            let trimmedNotes = notes.trimmingCharacters(in: .whitespacesAndNewlines)
            noteList = trimmedNotes.isEmpty
                ? []
                : [Note(id: "\(id)-note", body: notes, createdAt: parseISODate(updatedAt) ?? Date())]
        }

        return Page(
            id: id,
            notebookId: notebookId,
            chapterId: chapterId,
            title: title ?? "",
            capturedOn: capturedOnDisplay(),
            updatedAt: parseISODate(updatedAt) ?? Date(),
            notes: noteList,
            photos: photos,
            voiceNotes: voices,
            todoItems: notepadTodos.enumerated().map { idx, todo in
                TodoItem(
                    id: todo.id,
                    body: todo.text,
                    done: todo.done,
                    position: idx
                )
            },
            scannedDocuments: scans,
            contacts: notepadContacts.map { c in
                Contact(
                    id: c.id,
                    name: c.name,
                    phone: c.phone,
                    email: c.email,
                    // The notepad shape holds both `role` and
                    // `organization` — fold them into the Drive
                    // domain's single `notes` slot, separated by
                    // " · " when both are present, so neither
                    // is silently dropped.
                    notes: [c.role, c.organization]
                        .compactMap { $0?.trimmingCharacters(in: .whitespaces) }
                        .filter { !$0.isEmpty }
                        .joined(separator: " · ")
                        .nilIfEmpty()
                )
            },
            locations: notepadLocs.map { loc in
                LocationPin(
                    id: loc.id,
                    name: loc.address ?? "",
                    latitude: loc.lat,
                    longitude: loc.lng,
                    capturedAt: parseISODate(loc.capturedAt) ?? Date(),
                    notes: nil
                )
            },
            tags: tagList,
            archivedAt: parseISODate(archivedAt)
        )
    }

    /// Friendlier "Sat · Apr 25 · 2026" style for the
    /// `capturedOn` field. `created_at` in storage is the source —
    /// the schema doesn't have a separate captured-on column.
    private func capturedOnDisplay() -> String? {
        guard let date = parseISODate(createdAt) else { return nil }
        let formatter = DateFormatter()
        formatter.dateFormat = "EEE · MMM d · yyyy"
        return formatter.string(from: date)
    }

    /// Build a `PageSummary` for the chapter list. Counts come from
    /// the JSON child arrays without fully decoding into the rich
    /// domain types — we just need the array lengths.
    func toPageSummary() -> PageSummary {
        let attachmentList: [Attachment] = attachments.parseAttachments()
        let photoCount = attachmentList.lazy.filter { $0.type == Attachment.typePhoto }.count
        let voiceCount = attachmentList.lazy.filter { $0.type == Attachment.typeVoice }.count
        let scanCount  = attachmentList.lazy.filter { $0.type == Attachment.typeScan  }.count
        return PageSummary(
            id: id,
            title: title ?? "",
            capturedOn: capturedOnDisplay(),
            updatedAt: parseISODate(updatedAt) ?? Date(),
            counts: PageCounts(
                photos: photoCount,
                voiceNotes: voiceCount,
                todoItems: todos.parseTodos().count,
                scannedDocuments: scanCount,
                contacts: contacts.parseContacts().count,
                locations: locations.parseLocations().count
            ),
            tags: parseTagsJson(tags)
        )
    }
}

extension Page {

    /// Build a `PageEntity` ready to upsert. Encodes the typed
    /// domain collections into the Notepad-shape JSON columns.
    /// Lossy on a few decoration fields (Photo.caption,
    /// ScannedDocument.pageCount, etc.) that the persistence layer
    /// doesn't carry — see file header for the rationale.
    func toEntity() -> PageEntity {
        // Re-encode child collections into the Notepad-shape JSON.
        let contactsJson: String = contacts.map { c in
            // Contact.notes folds back into role only — losing the
            // " · "-separated organization context. Acceptable
            // since the capture UI doesn't expose that as separate
            // fields for editing.
            NotepadContact(
                id: c.id,
                name: c.name,
                role: c.notes,
                phone: c.phone,
                email: c.email,
                organization: nil
            )
        }.toJsonString()

        let locationsJson: String = locations.map { l in
            GeoLocation(
                id: l.id,
                lat: l.latitude,
                lng: l.longitude,
                address: l.name.isEmpty ? nil : l.name,
                capturedAt: formatISODate(l.capturedAt)
            )
        }.toJsonString()

        let todosJson: String = todoItems
            .sorted { $0.position < $1.position }
            .map { t in
                NotepadTodo(id: t.id, text: t.body, done: t.done)
            }
            .toJsonString()

        // Re-mux the three typed attachment domain types into one
        // Notepad attachment array, tagged by `type`. Order is
        // photos → voice → scans so callers that didn't already
        // sort by capturedAt get a deterministic on-disk shape.
        let attachmentJson: String = (
            photos.map { p in
                Attachment(
                    id: p.id,
                    type: Attachment.typePhoto,
                    uri: p.driveFileId ?? "",
                    previewUri: p.caption,
                    capturedAt: formatISODate(p.capturedAt),
                    durationMs: nil,
                    transcript: nil
                )
            } +
            voiceNotes.map { v in
                Attachment(
                    id: v.id,
                    type: Attachment.typeVoice,
                    uri: v.driveFileId ?? "",
                    previewUri: nil,
                    capturedAt: formatISODate(v.recordedAt),
                    durationMs: v.durationMs,
                    transcript: v.transcription
                )
            } +
            scannedDocuments.map { s in
                Attachment(
                    id: s.id,
                    type: Attachment.typeScan,
                    uri: s.driveFileId ?? "",
                    previewUri: s.title,
                    capturedAt: formatISODate(s.scannedAt),
                    durationMs: nil,
                    transcript: nil
                )
            }
        ).toJsonString()

        // Notes have two representations on disk:
        //   - `pageNotesJson` — typed array (id + body + createdAt
        //     per element). Source of truth for note identity.
        //   - `notes` — markdown blob, joined bodies. Kept in sync
        //     so the FTS triggers index against it; the join is a
        //     derived view, not the canonical store.
        let pageNotesArray: [StoredPageNote] = notes.map { n in
            StoredPageNote(
                id: n.id,
                body: n.body,
                createdAt: formatISODate(n.createdAt)
            )
        }
        let pageNotesJsonText: String = pageNotesArray.toJsonString()
        let ftsNotesText: String = notes.map { $0.body }.joined(separator: "\n\n")

        return PageEntity(
            id: id,
            chapterId: chapterId,
            projectId: nil,
            templateId: nil,
            title: title.isEmpty ? nil : title,
            notes: ftsNotesText,
            contacts: contactsJson,
            locations: locationsJson,
            todos: todosJson,
            attachments: attachmentJson,
            tags: encodeTagsJson(tags),
            pageNotesJson: pageNotesJsonText,
            position: 1024,
            conflictStub: nil,
            driveFileId: nil,
            createdAt: formatISODate(updatedAt),
            updatedAt: formatISODate(updatedAt),
            dirty: true,
            deletedAt: nil,
            archivedAt: archivedAt.map(formatISODate)
        )
    }
}

// MARK: - NotebookEntity ↔ Notebook

extension NotebookEntity {

    /// Decode the entity into the Drive-facing `Notebook` domain
    /// shape. Counts default to 0 — the count-aware reads should
    /// go through `NotebookRepository.observeShelves()` which
    /// joins live page + chapter counts. This single-row mapper is
    /// only suitable when the caller already has the counts
    /// elsewhere or doesn't need them (e.g. notebook-detail
    /// header).
    func toDomainNotebook(
        chapterCount: Int = 0,
        pageCount: Int = 0,
        shelfName: String? = nil
    ) -> Notebook {
        let archived = parseISODate(archivedAt)
        return Notebook(
            id: id,
            title: title,
            description: nil,
            colorToken: LeafTokenHex.token(forHex: colorHex),
            position: Int(position),
            archivedAt: archived,
            updatedAt: parseISODate(updatedAt) ?? Date(),
            chapterCount: chapterCount,
            pageCount: pageCount,
            shelfName: shelfName,
            volumeNumber: volumeNumber > 1 ? volumeNumber : nil,
            status: archived == nil ? .active : .archived,
            iconKey: nil,
            shelfId: shelfId,
            seriesId: seriesId,
            seriesVolumeNumber: volumeNumber,
            volumeLabel: volumeName
        )
    }
}

// MARK: - ChapterEntity ↔ Chapter

extension ChapterEntity {

    /// Decode the chapter row into the Drive-facing `Chapter`
    /// shape. Page summaries default to empty — the call site
    /// (typically `loadNotebook(id:)`) is expected to fetch the
    /// page rows for this chapter and build the summaries via
    /// `PageEntity.toPageSummary()`.
    func toDomainChapter(pages: [PageSummary] = []) -> Chapter {
        Chapter(
            id: id,
            notebookId: notebookId,
            title: title,
            position: Int(position),
            updatedAt: parseISODate(updatedAt) ?? Date(),
            pages: pages,
            archivedAt: parseISODate(archivedAt)
        )
    }
}

// MARK: - String convenience

private extension String {
    /// Returns nil if self is empty after trimming whitespace.
    /// Used to fold `[role, organization].joined()` — when both
    /// were nil/empty the joined result is "" and we want the
    /// domain field to be nil rather than empty.
    func nilIfEmpty() -> String? {
        let trimmed = trimmingCharacters(in: .whitespaces)
        return trimmed.isEmpty ? nil : trimmed
    }
}
