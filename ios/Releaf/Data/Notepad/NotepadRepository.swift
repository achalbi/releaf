/*
 * NotepadRepository.swift
 *
 * App-facing notepad store. Wraps the GRDB DatabaseQueue into a small
 * async/AsyncSequence API that SwiftUI views can consume via `.task` +
 * `for await`. Mirror of the Kotlin `NotepadRepository` — same create/save/
 * soft-delete/restore/search surface so the view-model logic stays
 * structurally identical on both platforms.
 *
 * Observation uses GRDB 7's `ValueObservation.values(in:)` which emits a
 * fresh snapshot of the tracked query on every committed transaction. That
 * replaces the Room `Flow<List<NotepadEntry>>` on the Android side. The
 * default scheduler delivers on the main queue, which is what SwiftUI
 * wants — we rely on that default here rather than passing an explicit
 * scheduler.
 *
 * Soft-delete convention matches Android: we never hard-delete a row.
 * Sync worker will mirror the tombstone to Drive on its next pass; an Undo
 * swipe clears `deleted_at` and re-dirties so the worker re-asserts the
 * row as live.
 */

import Foundation
import GRDB

public final class NotepadRepository: @unchecked Sendable {

    private let dbQueue: DatabaseQueue

    public init(database: ReleafDatabase = .shared) {
        self.dbQueue = database.dbQueue
    }

    // MARK: - Observation

    /// AsyncSequence of active (non-deleted) entries for a user, newest
    /// first. Drops a fresh snapshot on every committed transaction
    /// touching `notepad_entries`. Cancel by discarding the iterator.
    public func observeActive(userId: String) -> AsyncThrowingStream<[NotepadEntry], Error> {
        let observation = ValueObservation.tracking { db in
            try NotepadEntry
                .filter(sql: "user_id = ? AND deleted_at IS NULL", arguments: [userId])
                .order(Column("updated_at").desc)
                .fetchAll(db)
        }
        return bridge(observation.values(in: dbQueue))
    }

    /// AsyncSequence of a single entry — nil when not found or soft-deleted.
    public func observeById(_ id: String) -> AsyncThrowingStream<NotepadEntry?, Error> {
        let observation = ValueObservation.tracking { db in
            try NotepadEntry
                .filter(sql: "id = ? AND deleted_at IS NULL", arguments: [id])
                .fetchOne(db)
        }
        return bridge(observation.values(in: dbQueue))
    }

    /// One-shot lookup. Returns nil for unknown OR soft-deleted ids — the
    /// editor uses this on first open to decide "existing vs. new draft".
    public func findById(_ id: String) async throws -> NotepadEntry? {
        try await dbQueue.read { db in
            try NotepadEntry
                .filter(sql: "id = ?", arguments: [id])
                .fetchOne(db)
        }
    }

    /// Full-text search over `notes`, scoped to one user and to live rows.
    /// Raw query goes through `FtsQuery.build` which strips punctuation and
    /// appends prefix wildcards. Empty/noise queries short-circuit to `[]`
    /// rather than raising an FTS5 MATCH error.
    ///
    /// Returned as an AsyncSequence so the UI can live-update as the user
    /// types without re-running the query from scratch each keystroke.
    public func search(userId: String, rawQuery: String) -> AsyncThrowingStream<[NotepadEntry], Error> {
        guard let match = FtsQuery.build(rawQuery) else {
            // No usable terms — publish a single empty snapshot and finish.
            return AsyncThrowingStream { continuation in
                continuation.yield([])
                continuation.finish()
            }
        }
        let observation = ValueObservation.tracking { db in
            try NotepadEntry.fetchAll(db, sql: """
                SELECT n.* FROM notepad_entries n
                JOIN fts_notepad_notes fts ON fts.notepad_entry_id = n.id
                WHERE n.user_id = ?
                  AND n.deleted_at IS NULL
                  AND fts_notepad_notes MATCH ?
                ORDER BY rank
                """, arguments: [userId, match])
        }
        return bridge(observation.values(in: dbQueue))
    }

    // MARK: - Mutations

    /// Create a fresh entry. Caller only supplies user + content — id,
    /// timestamps, entry_date, and defaults are filled in here so view-
    /// models don't duplicate this logic.
    ///
    /// The JSON payloads (`contacts`, `locations`, `todos`, `attachments`)
    /// are opt-in — the editor passes them explicitly when a brand-new
    /// draft has already accumulated section items before the first save.
    /// Without these a user who added a photo to a fresh entry and tapped
    /// back would lose it (the create path wouldn't see it).
    @discardableResult
    public func create(
        userId: String,
        title: String?,
        notes: String,
        entryDate: String? = nil,
        description: String? = nil,
        contacts: String = "[]",
        locations: String = "[]",
        todos: String = "[]",
        attachments: String = "[]"
    ) async throws -> NotepadEntry {
        let now = IsoClock.nowIso()
        let newId = Uuidv7.generate()

        // Auto-seed title + description as a *pair* from the same
        // Ayurvedic plant — title gets the Sanskrit/Hindi name, the
        // description gets "(commonName), epithet, Used for usedFor".
        // We only seed when BOTH fields were left blank: mixing an
        // authored title with an unrelated auto-description (or
        // vice-versa) would produce internally mismatched rows.
        let cleanedTitle       = title?.cleanedTitle
        let cleanedDescription = description?.cleanedTitle
        let seededTitle: String?
        let seededDescription: String?
        if cleanedTitle == nil && cleanedDescription == nil {
            let plant = AyurvedicCatalog.plant(forId: newId)
            seededTitle       = plant.name
            seededDescription = AyurvedicCatalog.description(for: plant)
        } else {
            seededTitle       = cleanedTitle
            seededDescription = cleanedDescription
        }

        let entry = NotepadEntry(
            id:          newId,
            userId:      userId,
            entryDate:   entryDate ?? IsoClock.todayLocalDate(),
            title:       seededTitle,
            description: seededDescription,
            notes:       notes,
            contacts:    contacts,
            locations:   locations,
            todos:       todos,
            attachments: attachments,
            createdAt:   now,
            updatedAt:   now,
            dirty:       true
        )
        try await dbQueue.write { db in
            // `insert` matches Room's REPLACE conflict strategy here
            // because the primary key is a freshly generated UUIDv7 —
            // collisions are effectively impossible.
            try entry.insert(db)
        }
        return entry
    }

    /// Persist edits. Bumps updated_at + sets dirty=1 unconditionally — the
    /// sync worker is the only thing allowed to clear dirty.
    public func save(_ entry: NotepadEntry) async throws {
        var row = entry
        row.title = entry.title?.cleanedTitle
        row.description = entry.description?.cleanedTitle
        row.updatedAt = IsoClock.nowIso()
        row.dirty = true
        // Snapshot to a `let` before the @Sendable write closure —
        // Swift 6 rejects capture-by-reference of mutable locals.
        let snapshot = row
        try await dbQueue.write { db in
            try snapshot.update(db)
        }
    }

    /// Merge `secondaryId`'s content into `primaryId` and soft-delete the
    /// secondary. The primary keeps its title, entry-date, user, and
    /// created-at; notes, todos, contacts, locations, and attachments from
    /// the secondary are appended in order.
    ///
    /// Returns `true` when both rows existed and the merge committed.
    /// Returns `false` for any no-op (missing row, same id, already-deleted
    /// secondary) so the caller can surface an error. The whole operation
    /// runs inside a single write transaction so we can't end up with the
    /// primary updated and the secondary still live (or vice-versa).
    ///
    /// Merge is purely local — the sync worker picks both sides up on its
    /// next pass: the primary as a dirty-edit upload, the secondary as a
    /// tombstone.
    @discardableResult
    public func merge(primaryId: String, secondaryId: String) async throws -> Bool {
        guard primaryId != secondaryId else { return false }
        return try await dbQueue.write { db in
            let primary = try NotepadEntry
                .filter(sql: "id = ?", arguments: [primaryId])
                .fetchOne(db)
            let secondary = try NotepadEntry
                .filter(sql: "id = ?", arguments: [secondaryId])
                .fetchOne(db)
            guard let primary, let secondary,
                  primary.deletedAt == nil, secondary.deletedAt == nil
            else { return false }

            // Concatenate typed lists back through the JSON helpers so the
            // round-trip stays lossless — no schema drift from string-
            // manipulating the raw JSON here.
            let mergedContacts    = primary.contacts.parseContacts()    + secondary.contacts.parseContacts()
            let mergedTodos       = primary.todos.parseTodos()          + secondary.todos.parseTodos()
            let mergedLocations   = primary.locations.parseLocations()  + secondary.locations.parseLocations()
            let mergedAttachments = primary.attachments.parseAttachments() + secondary.attachments.parseAttachments()

            // Notes: two blank lines between bodies so the merged entry
            // reads as two distinct sections. Skip the separator when
            // either side is empty to avoid leading / trailing blanks.
            let mergedNotes: String = {
                switch (primary.notes.isEmpty, secondary.notes.isEmpty) {
                case (true, true):  return ""
                case (false, true): return primary.notes
                case (true, false): return secondary.notes
                case (false, false): return primary.notes + "\n\n" + secondary.notes
                }
            }()

            // Description: prefer primary's; fall back to secondary's
            // only when primary has none, so a merge never silently
            // overwrites text the user authored on the surviving row.
            let mergedDescription = primary.description?.cleanedTitle
                ?? secondary.description?.cleanedTitle

            let now = IsoClock.nowIso()
            var updated = primary
            updated.description = mergedDescription
            updated.notes       = mergedNotes
            updated.contacts    = mergedContacts.toJsonString()
            updated.todos       = mergedTodos.toJsonString()
            updated.locations   = mergedLocations.toJsonString()
            updated.attachments = mergedAttachments.toJsonString()
            updated.updatedAt   = now
            updated.dirty       = true
            try updated.update(db)

            try db.execute(sql: """
                UPDATE notepad_entries
                SET deleted_at = ?, updated_at = ?, dirty = 1
                WHERE id = ?
                """, arguments: [now, now, secondaryId])
            return true
        }
    }

    /// Soft delete. Flips deleted_at + dirty so the sync worker can
    /// propagate the tombstone to Drive on its next pass.
    public func softDelete(id: String) async throws {
        let now = IsoClock.nowIso()
        try await dbQueue.write { db in
            try db.execute(sql: """
                UPDATE notepad_entries
                SET deleted_at = ?, updated_at = ?, dirty = 1
                WHERE id = ?
                """, arguments: [now, now, id])
        }
    }

    /// Undo a soft delete. Clears deleted_at and bumps updated_at + dirty
    /// so the sync worker re-asserts the row on Drive as a live entity.
    /// Used by the Notepad list's "Undo" snackbar after a swipe-to-delete.
    public func undoSoftDelete(id: String) async throws {
        let now = IsoClock.nowIso()
        try await dbQueue.write { db in
            try db.execute(sql: """
                UPDATE notepad_entries
                SET deleted_at = NULL, updated_at = ?, dirty = 1
                WHERE id = ?
                """, arguments: [now, id])
        }
    }

    /// One-shot snapshot of every active row for a user, newest first.
    /// Used by the merge picker — it opens once, grabs the list, lets the
    /// user pick, and closes; full live observation is overkill for that
    /// flow so this stays a plain `async throws` read.
    public func activeRows(userId: String) async throws -> [NotepadEntry] {
        try await dbQueue.read { db in
            try NotepadEntry
                .filter(sql: "user_id = ? AND deleted_at IS NULL", arguments: [userId])
                .order(Column("updated_at").desc)
                .fetchAll(db)
        }
    }

    // MARK: - Sync worker helpers

    /// All rows needing upload — live edits AND tombstones. Callers
    /// distinguish via `deleted_at IS NOT NULL`.
    public func dirtyRows() async throws -> [NotepadEntry] {
        try await dbQueue.read { db in
            try NotepadEntry
                .filter(sql: "dirty = 1")
                .fetchAll(db)
        }
    }

    /// Count of live entries for a user — fed into the sync manifest's
    /// `entity_counts` map so a future pull can sanity-check Drive vs local.
    public func countActive(userId: String) async throws -> Int {
        try await dbQueue.read { db in
            try Int.fetchOne(db, sql: """
                SELECT COUNT(*) FROM notepad_entries
                WHERE user_id = ? AND deleted_at IS NULL
                """, arguments: [userId]) ?? 0
        }
    }

    /// Race-safe clear: only marks the row synced if its `updated_at` still
    /// matches the snapshot. A concurrent edit bumps updated_at, so the
    /// next sync pass picks the row up fresh. Returns rows affected (0 or
    /// 1) so the worker can tell whether it actually cleared dirty.
    @discardableResult
    public func markSynced(
        id: String,
        driveFileId: String,
        updatedAtSnapshot: String
    ) async throws -> Int {
        try await dbQueue.write { db in
            try db.execute(sql: """
                UPDATE notepad_entries
                SET dirty = 0, drive_file_id = ?
                WHERE id = ?
                  AND dirty = 1
                  AND updated_at = ?
                """, arguments: [driveFileId, id, updatedAtSnapshot])
            return db.changesCount
        }
    }

    /// Clear-dirty for a synced tombstone. No updated_at guard — tombstones
    /// don't get re-edited, and we don't want to race with an undo (which
    /// clears deleted_at, re-dirties, and the next pass re-uploads).
    @discardableResult
    public func markTombstoneSynced(id: String) async throws -> Int {
        try await dbQueue.write { db in
            try db.execute(sql: """
                UPDATE notepad_entries
                SET dirty = 0
                WHERE id = ? AND deleted_at IS NOT NULL
                """, arguments: [id])
            return db.changesCount
        }
    }
}

// MARK: - AsyncSequence bridge

/// GRDB's `values(in:)` returns an `AsyncValueObservation<Reducer>` with a
/// generic reducer type that leaks through the repository's public API.
/// Bridging it through an `AsyncThrowingStream` gives callers a single,
/// stable return type and lets iteration be cancelled by discarding the
/// iterator (the wrapper task is cancelled via `onTermination`).
private func bridge<S: AsyncSequence & Sendable>(_ sequence: S) -> AsyncThrowingStream<S.Element, Error>
where S.Element: Sendable {
    AsyncThrowingStream { continuation in
        let task = Task {
            do {
                for try await value in sequence {
                    continuation.yield(value)
                }
                continuation.finish()
            } catch {
                continuation.finish(throwing: error)
            }
        }
        continuation.onTermination = { _ in task.cancel() }
    }
}

// MARK: - String helpers

private extension String {
    /// Optional-text-field cleaning rule shared across create/save —
    /// trim, and treat the empty string as nil so we never store an
    /// empty-string title or description when the user deletes every
    /// character from the field. (Original use was title; description
    /// joined as a second caller with the same behavior.)
    var cleanedTitle: String? {
        let trimmed = trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
}
