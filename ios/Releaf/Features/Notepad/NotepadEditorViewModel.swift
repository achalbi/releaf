/*
 * NotepadEditorViewModel.swift
 *
 * Backs the single-entry editor. Pulls an existing row from the repo on
 * first load (or stays in "new draft" mode for a fresh entry), tracks
 * draft state locally, and commits on demand. No debounced autosave yet —
 * the editor screen calls `save()` on back-press and in `onDisappear`.
 *
 * The entryId argument is either a UUIDv7 or the sentinel `newEntryId`,
 * matching Android's NotepadEditorViewModel contract.
 *
 * In addition to title + notes, the VM owns the four side-channel lists
 * the page editor uses on Android — contacts, todos, locations,
 * attachments — so the iOS notepad editor can show the same five feature
 * sections. All four live as JSON columns on `notepad_entries`; the VM
 * parses them on bootstrap, mutates in-memory lists, and serializes back
 * on save.
 */

import Foundation
import Combine
import ReleafData

@MainActor
public final class NotepadEditorViewModel: ObservableObject {

    /// Sentinel for "compose a new entry". Passed on the NotepadEditor
    /// navigation route instead of a real UUID.
    public static let newEntryId: String = "new"

    @Published public private(set) var isLoading: Bool = true

    /// The backing row if the editor is editing an existing entry. Nil
    /// while loading AND when the VM is in new-draft mode.
    @Published public private(set) var entry: NotepadEntry?

    @Published public var title: String = ""
    @Published public var notes: String = ""

    /// Category label for grouping / filtering. Nil = uncategorised.
    /// Holds either one of the predefined names (Home / Work /
    /// Personal / Health / Travel / Ideas — see
    /// `NotepadCategory.predefined`) or a free-form user string.
    /// Mirrors `entry.category`.
    @Published public var category: String? = nil

    /// Local-calendar date (YYYY-MM-DD) the entry is filed under. Defaults
    /// to today for fresh drafts; mirrors `entry.entryDate` once loaded.
    /// Editable via the date chip in the editor UI.
    @Published public var entryDate: String = ""

    @Published public private(set) var contacts: [NotepadContact] = []
    @Published public private(set) var todos: [NotepadTodo] = []
    @Published public private(set) var locations: [GeoLocation] = []
    @Published public private(set) var attachments: [Attachment] = []

    /// "Is there enough content to persist on back-nav?" — matches
    /// Android's `canSave` computed prop. Any of the feature-section lists
    /// being non-empty is reason enough to create a row, so a photo-only
    /// draft doesn't get dropped.
    public var canSave: Bool {
        let hasTitle = !title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        let hasNotes = !notes.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        return hasTitle || hasNotes
            || !contacts.isEmpty
            || !todos.isEmpty
            || !locations.isEmpty
            || !attachments.isEmpty
    }

    private let repository: NotepadRepository
    private let entryId: String
    private let userId: String

    /// Prevents double-creation on rapid fires of `save()` (e.g. back-tap
    /// + onDisappear racing). Flipped on the first successful create.
    private var hasPersistedNewEntry: Bool = false

    public init(
        repository: NotepadRepository,
        entryId: String,
        userId: String
    ) {
        self.repository = repository
        self.entryId = entryId
        self.userId = userId
    }

    public func bootstrap() async {
        if entryId == Self.newEntryId {
            // Don't create the row until the user types something — the
            // list shouldn't show a blank entry if they back out.
            entryDate = IsoClock.todayLocalDate()
            isLoading = false
            return
        }
        do {
            let loaded = try await repository.findById(entryId)
            self.entry = loaded
            self.title = loaded?.title ?? ""
            self.notes = loaded?.notes ?? ""
            self.category    = loaded?.category
            self.entryDate   = loaded?.entryDate ?? IsoClock.todayLocalDate()
            self.contacts    = loaded?.contacts.parseContacts()       ?? []
            self.todos       = loaded?.todos.parseTodos()             ?? []
            self.locations   = loaded?.locations.parseLocations()     ?? []
            self.attachments = loaded?.attachments.parseAttachments() ?? []
        } catch {
            // Stay in loading-false / nil-entry state; the view shows an
            // empty editor, same as a new-entry draft.
            entryDate = IsoClock.todayLocalDate()
        }
        isLoading = false
    }

    // MARK: - Contacts

    public func addContact(name: String, role: String? = nil) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        contacts.append(NotepadContact(
            id: Uuidv7.generate(),
            name: trimmed,
            role: role?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        ))
    }

    public func removeContact(id: String) {
        contacts.removeAll { $0.id == id }
    }

    // MARK: - Todos

    public func addTodo(text: String) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        todos.append(NotepadTodo(id: Uuidv7.generate(), text: trimmed, done: false))
    }

    public func toggleTodo(id: String) {
        todos = todos.map { t in
            t.id == id ? NotepadTodo(id: t.id, text: t.text, done: !t.done) : t
        }
    }

    public func removeTodo(id: String) {
        todos.removeAll { $0.id == id }
    }

    // MARK: - Locations

    public func addLocation(lat: Double, lng: Double, address: String? = nil) {
        locations.append(GeoLocation(
            id: Uuidv7.generate(),
            lat: lat,
            lng: lng,
            address: address,
            capturedAt: IsoClock.nowIso()
        ))
    }

    public func removeLocation(id: String) {
        locations.removeAll { $0.id == id }
    }

    // MARK: - Attachments

    public func addAttachment(type: String, uri: String, previewUri: String? = nil) {
        attachments.append(Attachment(
            id: Uuidv7.generate(),
            type: type,
            uri: uri,
            previewUri: previewUri,
            capturedAt: IsoClock.nowIso()
        ))
    }

    /// Voice-note variant that stamps the measured clip duration on the
    /// attachment so list rows can render "0:42" without re-probing the
    /// M4A on every render. Mirrors the Android twin on
    /// `NotepadEditorViewModel.addVoiceNote`.
    public func addVoiceNote(uri: String, durationMs: Int) {
        attachments.append(Attachment(
            id: Uuidv7.generate(),
            type: Attachment.typeVoice,
            uri: uri,
            previewUri: nil,
            capturedAt: IsoClock.nowIso(),
            durationMs: durationMs
        ))
    }

    /// Patch the transcript on an existing voice-note attachment.
    /// Called from the `VoiceSection` once `SFSpeechRecognizer` has
    /// finished running against the finalized .m4a — file-based
    /// recognition is async, so we persist the attachment up front and
    /// swap in the transcript once it's ready. Keyed by `uri` (which
    /// embeds a uuidv7 and is unique) so the section doesn't need to
    /// track the newly-assigned id across the transcription task.
    public func updateVoiceTranscript(uri: String, transcript: String?) {
        guard let idx = attachments.firstIndex(where: { $0.uri == uri }) else { return }
        let existing = attachments[idx]
        attachments[idx] = Attachment(
            id: existing.id,
            type: existing.type,
            uri: existing.uri,
            previewUri: existing.previewUri,
            capturedAt: existing.capturedAt,
            durationMs: existing.durationMs,
            transcript: transcript?.isEmpty == false ? transcript : nil
        )
    }

    /// Removes an attachment and, when no other attachment still points at
    /// the same file, deletes the underlying bytes from our own files
    /// directory. iOS photo/scan flows copy bytes in on capture, so we
    /// fully own these files — unlike Android where the picker gives us a
    /// content:// URI we merely hold a persistable grant on.
    public func removeAttachment(id: String) {
        guard let target = attachments.first(where: { $0.id == id }) else { return }
        let remaining = attachments.filter { $0.id != id }
        let stillReferenced = remaining.contains {
            $0.uri == target.uri || $0.previewUri == target.uri
        }
        attachments = remaining

        // Best-effort cleanup. If the URI isn't a file:// we can't delete,
        // and we don't want a failure here to stop the state update.
        guard !stillReferenced else { return }
        deleteFileIfLocal(target.uri)
        if let preview = target.previewUri {
            deleteFileIfLocal(preview)
        }
    }

    private func deleteFileIfLocal(_ uriString: String) {
        guard let url = URL(string: uriString), url.isFileURL else { return }
        try? FileManager.default.removeItem(at: url)
    }

    /// Persist the current draft. Fire-and-forget from the UI — no-ops if
    /// there's nothing to save (blank new entry the user backed out of, or
    /// existing entry unchanged). Safe to call multiple times in a row.
    ///
    /// Back-tap → `save()` and `onDisappear` → `save()` fire back-to-back on
    /// the main actor. This method collapses them into a single write by
    /// gating the create path on `hasPersistedNewEntry` and, on the update
    /// path, advancing `self.entry` to the target row synchronously before
    /// launching the IO — the second call then diffs clean and returns.
    public func save() {
        let existing = entry
        let titleSnapshot = title
        let notesSnapshot = notes
        let entryDateSnapshot = entryDate
        let categorySnapshot  = category?
            .trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        let contactsJson    = contacts.toJsonString()
        let todosJson       = todos.toJsonString()
        let locationsJson   = locations.toJsonString()
        let attachmentsJson = attachments.toJsonString()

        if existing == nil {
            guard canSave else { return }
            if hasPersistedNewEntry { return }
            hasPersistedNewEntry = true
            Task { [repository, userId] in
                let created = try? await repository.create(
                    userId:      userId,
                    title:       titleSnapshot,
                    notes:       notesSnapshot,
                    entryDate:   entryDateSnapshot.isEmpty ? IsoClock.todayLocalDate() : entryDateSnapshot,
                    category:    categorySnapshot,
                    contacts:    contactsJson,
                    locations:   locationsJson,
                    todos:       todosJson,
                    attachments: attachmentsJson
                )
                // Stash the created row so subsequent edits in the same
                // session go through the "update existing" branch rather
                // than firing another create.
                await MainActor.run {
                    if let created {
                        self.entry = created
                    }
                }
            }
            return
        }

        guard var row = existing else { return }
        let titleChanged       = (row.title ?? "") != titleSnapshot
        let categoryChanged    = (row.category?
            .trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty) != categorySnapshot
        let notesChanged       = row.notes != notesSnapshot
        let entryDateChanged   = !entryDateSnapshot.isEmpty && row.entryDate != entryDateSnapshot
        let contactsChanged    = row.contacts    != contactsJson
        let todosChanged       = row.todos       != todosJson
        let locationsChanged   = row.locations   != locationsJson
        let attachmentsChanged = row.attachments != attachmentsJson
        guard titleChanged || categoryChanged || notesChanged || entryDateChanged ||
              contactsChanged || todosChanged ||
              locationsChanged || attachmentsChanged else {
            return
        }

        row.title = titleSnapshot.isEmpty ? nil : titleSnapshot
        row.category = categorySnapshot
        row.notes = notesSnapshot
        if !entryDateSnapshot.isEmpty {
            row.entryDate = entryDateSnapshot
        }
        row.contacts    = contactsJson
        row.todos       = todosJson
        row.locations   = locationsJson
        row.attachments = attachmentsJson

        // Advance the baseline synchronously — see the doc comment. The
        // second save() in the same tick (back-tap → onDisappear) now sees
        // `entry` matching the current payload and short-circuits.
        self.entry = row

        Task { [repository] in
            try? await repository.save(row)
        }
    }

    public func delete(onDeleted: @escaping () -> Void) {
        guard let id = entry?.id else {
            onDeleted()
            return
        }
        Task { [repository] in
            try? await repository.softDelete(id: id)
            await MainActor.run { onDeleted() }
        }
    }

    // MARK: - Merge

    /// One-shot snapshot of every other saved notepad entry (the merge
    /// picker's option list). New/unsaved drafts still surface every
    /// other entry (nothing to exclude yet), matching the Kotlin twin.
    public func loadOtherEntries() async -> [NotepadEntry] {
        let rows = (try? await repository.activeRows(userId: userId)) ?? []
        let selfId = entry?.id
        return selfId == nil ? rows : rows.filter { $0.id != selfId }
    }

    /// Merge this entry with `otherId`. When `keepThisAsPrimary` is true,
    /// the other entry is folded into this one (this page stays open,
    /// just refreshed). When false, this entry is folded into the other
    /// and removed — the caller should navigate away because the editor
    /// is now showing a soft-deleted row. `onDone` fires on the main
    /// actor with the surviving row's id so callers can route.
    ///
    /// Flushes the current draft first so unsaved edits on this page
    /// are included in the merge rather than dropped on the floor.
    public func merge(
        otherId: String,
        keepThisAsPrimary: Bool,
        onDone: @escaping (String) -> Void
    ) {
        // Flush the current draft so in-flight edits land before the
        // merge transaction reads the row. `save()` is idempotent.
        save()
        Task { [repository, weak self] in
            // Tiny delay so the save() Task above wins the write lock
            // first. Without this the merge can read a stale snapshot.
            try? await Task.sleep(nanoseconds: 50_000_000) // 50ms
            guard let self else { return }
            let selfId = await MainActor.run { self.entry?.id }
            guard let selfId else { return }
            let primaryId   = keepThisAsPrimary ? selfId  : otherId
            let secondaryId = keepThisAsPrimary ? otherId : selfId
            let ok = (try? await repository.merge(
                primaryId: primaryId,
                secondaryId: secondaryId,
            )) ?? false
            if ok {
                await MainActor.run { onDone(primaryId) }
            }
        }
    }
}

// MARK: - Helpers

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}
