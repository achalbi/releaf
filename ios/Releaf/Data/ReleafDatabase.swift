/*
 * ReleafDatabase.swift
 *
 * GRDB hub for every persisted table — notepad_entries, notebooks,
 * chapters, pages — plus the FTS5 virtual tables and their maintenance
 * triggers. One shared DatabaseQueue serves both the notepad editor and
 * the notebook surfaces. Mirror of Android's `ReleafDatabase.kt`.
 *
 * File location: the DB lives at `<Application Support>/Releaf/releaf.db`.
 * Same filename as Android (`ReleafDatabase.DB_NAME`) so the mental
 * model stays symmetrical; each platform still has its own file.
 *
 * Migration posture (matches the Android flatten in this turn):
 *   - Single `v1_initial` migration that matches the current entity set
 *     byte-for-byte, sourced from design-system/migrations/v1_initial.sql.
 *   - Dogfood flag `eraseDatabaseOnSchemaChange = true` wipes the file
 *     whenever the registered-migration list differs from the on-disk
 *     history. Old dogfood installs (at `v1_notepad_entries` /
 *     `v2_notepad_sections` names) get wiped here. Remove this flag
 *     before real user data lands — at that point we need new
 *     `registerMigration` calls for every forward bump.
 */

import Foundation
import GRDB

public final class ReleafDatabase: @unchecked Sendable {

    public static let shared = ReleafDatabase()

    /// DatabaseQueue over a `releaf.db` file under Application Support.
    /// GRDB serializes access through the queue, so concurrent read/write
    /// from SwiftUI's main actor + the sync worker's background task is
    /// safe without explicit locks.
    public let dbQueue: DatabaseQueue

    // MARK: - Construction

    /// Designated initializer. Defaults to a file-backed store so the
    /// app persists across launches; tests and previews pass
    /// `inMemory: true` for an ephemeral database.
    public init(inMemory: Bool = false) {
        let queue: DatabaseQueue
        do {
            if inMemory {
                queue = try DatabaseQueue()
            } else {
                let url = try Self.defaultDatabaseURL()
                queue = try DatabaseQueue(path: url.path)
            }
        } catch {
            fatalError("ReleafDatabase: failed to open DatabaseQueue: \(error)")
        }
        self.dbQueue = queue

        do {
            try Self.migrator.migrate(queue)
        } catch {
            fatalError("ReleafDatabase: migration failed: \(error)")
        }
    }

    /// URL for the on-disk SQLite file. Application Support lives
    /// outside the iCloud-autobackup sweep and is the right place for a
    /// SQLite cache that we'll be rebuilding from Drive on fresh
    /// installs anyway.
    private static func defaultDatabaseURL() throws -> URL {
        let fm = FileManager.default
        let base = try fm.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        let dir = base.appendingPathComponent("Releaf", isDirectory: true)
        if !fm.fileExists(atPath: dir.path) {
            try fm.createDirectory(at: dir, withIntermediateDirectories: true)
        }
        return dir.appendingPathComponent("releaf.db")
    }

    // MARK: - Migrator

    /// Single-migration drop-in of the tables we're using today. Keep
    /// this in sync with `design-system/migrations/v1_initial.sql` —
    /// when that file gains new tables or columns, add a new
    /// `registerMigration("vN_description")` rather than editing this
    /// one (see the dogfood note at the top).
    private static var migrator: DatabaseMigrator {
        var migrator = DatabaseMigrator()

        // Dogfood-only. Wipes the DB when the migration history on disk
        // diverges from the code's registered migrations. Remove before
        // real users have data.
        migrator.eraseDatabaseOnSchemaChange = true

        migrator.registerMigration("v1_initial") { db in
            // =============================================================
            // Tables
            // =============================================================

            // ----- notepad_entries -----
            try db.execute(sql: """
                CREATE TABLE notepad_entries (
                    id                      TEXT PRIMARY KEY NOT NULL,
                    user_id                 TEXT NOT NULL,
                    entry_date              TEXT NOT NULL CHECK (
                                                length(entry_date) = 10 AND
                                                entry_date GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]'
                                            ),
                    project_id              TEXT,
                    title                   TEXT,
                    notes                   TEXT NOT NULL DEFAULT '',
                    contacts                TEXT NOT NULL DEFAULT '[]',
                    locations               TEXT NOT NULL DEFAULT '[]',
                    todos                   TEXT NOT NULL DEFAULT '[]',
                    attachments             TEXT NOT NULL DEFAULT '[]',
                    allow_blank_content     INTEGER NOT NULL DEFAULT 0 CHECK (allow_blank_content IN (0, 1)),
                    conflict_stub           TEXT,
                    drive_file_id           TEXT,
                    created_at              TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
                    updated_at              TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
                    dirty                   INTEGER NOT NULL DEFAULT 1 CHECK (dirty IN (0, 1)),
                    deleted_at              TEXT
                )
                """)

            // ----- notebooks -----
            try db.execute(sql: """
                CREATE TABLE notebooks (
                    id              TEXT PRIMARY KEY NOT NULL,
                    title           TEXT NOT NULL,
                    color_hex       TEXT,
                    position        INTEGER NOT NULL DEFAULT 1024,
                    drive_file_id   TEXT,
                    created_at      TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
                    updated_at      TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
                    dirty           INTEGER NOT NULL DEFAULT 1 CHECK (dirty IN (0, 1)),
                    deleted_at      TEXT
                )
                """)

            // ----- chapters -----
            try db.execute(sql: """
                CREATE TABLE chapters (
                    id              TEXT PRIMARY KEY NOT NULL,
                    notebook_id     TEXT NOT NULL,
                    title           TEXT NOT NULL,
                    position        INTEGER NOT NULL DEFAULT 1024,
                    drive_file_id   TEXT,
                    created_at      TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
                    updated_at      TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
                    dirty           INTEGER NOT NULL DEFAULT 1 CHECK (dirty IN (0, 1)),
                    deleted_at      TEXT
                )
                """)

            // ----- pages -----
            try db.execute(sql: """
                CREATE TABLE pages (
                    id              TEXT PRIMARY KEY NOT NULL,
                    chapter_id      TEXT NOT NULL,
                    project_id      TEXT,
                    template_id     TEXT,
                    title           TEXT,
                    notes           TEXT NOT NULL DEFAULT '',
                    contacts        TEXT NOT NULL DEFAULT '[]',
                    locations       TEXT NOT NULL DEFAULT '[]',
                    todos           TEXT NOT NULL DEFAULT '[]',
                    attachments     TEXT NOT NULL DEFAULT '[]',
                    position        INTEGER NOT NULL DEFAULT 1024,
                    conflict_stub   TEXT,
                    drive_file_id   TEXT,
                    created_at      TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
                    updated_at      TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
                    dirty           INTEGER NOT NULL DEFAULT 1 CHECK (dirty IN (0, 1)),
                    deleted_at      TEXT
                )
                """)

            // =============================================================
            // Indexes
            // =============================================================

            // notepad_entries
            try db.execute(sql: "CREATE INDEX idx_notepad_entries_user_date    ON notepad_entries (user_id, entry_date)")
            try db.execute(sql: "CREATE INDEX idx_notepad_entries_project      ON notepad_entries (project_id) WHERE project_id IS NOT NULL")
            try db.execute(sql: "CREATE INDEX idx_notepad_entries_dirty        ON notepad_entries (dirty) WHERE dirty = 1")
            try db.execute(sql: "CREATE INDEX idx_notepad_entries_tombstone    ON notepad_entries (deleted_at) WHERE deleted_at IS NOT NULL")

            // notebooks
            try db.execute(sql: "CREATE INDEX idx_notebooks_dirty              ON notebooks (dirty) WHERE dirty = 1")
            try db.execute(sql: "CREATE INDEX idx_notebooks_tombstone          ON notebooks (deleted_at) WHERE deleted_at IS NOT NULL")

            // chapters
            try db.execute(sql: "CREATE INDEX idx_chapters_notebook_position   ON chapters (notebook_id, position)")
            try db.execute(sql: "CREATE INDEX idx_chapters_dirty               ON chapters (dirty) WHERE dirty = 1")
            try db.execute(sql: "CREATE INDEX idx_chapters_tombstone           ON chapters (deleted_at) WHERE deleted_at IS NOT NULL")

            // pages
            try db.execute(sql: "CREATE INDEX idx_pages_chapter_position       ON pages (chapter_id, position)")
            try db.execute(sql: "CREATE INDEX idx_pages_project                ON pages (project_id) WHERE project_id IS NOT NULL")
            try db.execute(sql: "CREATE INDEX idx_pages_dirty                  ON pages (dirty) WHERE dirty = 1")
            try db.execute(sql: "CREATE INDEX idx_pages_tombstone              ON pages (deleted_at) WHERE deleted_at IS NOT NULL")

            // =============================================================
            // FTS5 virtual tables (non-external-content, trigger-maintained)
            // =============================================================

            try db.execute(sql: """
                CREATE VIRTUAL TABLE fts_notepad_notes USING fts5(
                    notepad_entry_id UNINDEXED,
                    notes,
                    tokenize = 'unicode61 remove_diacritics 2'
                )
                """)
            try db.execute(sql: """
                CREATE VIRTUAL TABLE fts_page_notes USING fts5(
                    page_id UNINDEXED,
                    notes,
                    tokenize = 'unicode61 remove_diacritics 2'
                )
                """)

            // =============================================================
            // FTS5 triggers — keep the mirror tables in sync with the
            // source of truth on INSERT / UPDATE / DELETE.
            // =============================================================

            // ----- notepad_entries -----
            try db.execute(sql: """
                CREATE TRIGGER notepad_entries_fts_ai AFTER INSERT ON notepad_entries
                WHEN new.deleted_at IS NULL AND new.notes <> ''
                BEGIN
                    INSERT INTO fts_notepad_notes(notepad_entry_id, notes)
                    VALUES (new.id, new.notes);
                END
                """)
            try db.execute(sql: """
                CREATE TRIGGER notepad_entries_fts_au AFTER UPDATE ON notepad_entries
                BEGIN
                    DELETE FROM fts_notepad_notes WHERE notepad_entry_id = old.id;
                    INSERT INTO fts_notepad_notes(notepad_entry_id, notes)
                    SELECT new.id, new.notes
                    WHERE new.deleted_at IS NULL AND new.notes <> '';
                END
                """)
            try db.execute(sql: """
                CREATE TRIGGER notepad_entries_fts_ad AFTER DELETE ON notepad_entries
                BEGIN
                    DELETE FROM fts_notepad_notes WHERE notepad_entry_id = old.id;
                END
                """)

            // ----- pages -----
            try db.execute(sql: """
                CREATE TRIGGER pages_fts_ai AFTER INSERT ON pages
                WHEN new.deleted_at IS NULL AND new.notes <> ''
                BEGIN
                    INSERT INTO fts_page_notes(page_id, notes)
                    VALUES (new.id, new.notes);
                END
                """)
            try db.execute(sql: """
                CREATE TRIGGER pages_fts_au AFTER UPDATE ON pages
                BEGIN
                    DELETE FROM fts_page_notes WHERE page_id = old.id;
                    INSERT INTO fts_page_notes(page_id, notes)
                    SELECT new.id, new.notes
                    WHERE new.deleted_at IS NULL AND new.notes <> '';
                END
                """)
            try db.execute(sql: """
                CREATE TRIGGER pages_fts_ad AFTER DELETE ON pages
                BEGIN
                    DELETE FROM fts_page_notes WHERE page_id = old.id;
                END
                """)
        }

        return migrator
    }
}
