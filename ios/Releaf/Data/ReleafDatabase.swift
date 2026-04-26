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

        // v2 — adds the `tasks` table powering the workspace-level
        // Tasks screen launched from the home card. Mirrors the
        // Android v4→v5 migration; column shapes match
        // design-system/migrations/v1_initial.sql §5 (subset).
        migrator.registerMigration("v2_tasks") { db in
            try db.execute(sql: """
                CREATE TABLE tasks (
                    id             TEXT PRIMARY KEY NOT NULL,
                    user_id        TEXT NOT NULL,
                    title          TEXT NOT NULL,
                    description    TEXT,
                    due_date       TEXT CHECK (due_date IS NULL OR (
                                       length(due_date) = 10 AND
                                       due_date GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]'
                                   )),
                    completed      INTEGER NOT NULL DEFAULT 0 CHECK (completed IN (0, 1)),
                    completed_at   TEXT,
                    priority       INTEGER NOT NULL DEFAULT 0,
                    created_at     TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
                    updated_at     TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
                    dirty          INTEGER NOT NULL DEFAULT 1 CHECK (dirty IN (0, 1)),
                    deleted_at     TEXT
                )
                """)
            try db.execute(sql: "CREATE INDEX idx_tasks_user_id    ON tasks(user_id)")
            try db.execute(sql: "CREATE INDEX idx_tasks_due_date   ON tasks(due_date)")
            try db.execute(sql: "CREATE INDEX idx_tasks_completed  ON tasks(completed)")
            try db.execute(sql: "CREATE INDEX idx_tasks_updated_at ON tasks(updated_at)")
            try db.execute(sql: "CREATE INDEX idx_tasks_deleted_at ON tasks(deleted_at)")
        }

        // v3 — introduces the Shelf → Book → Chapter → Page
        // hierarchy. Adds `shelves` + `book_series` tables, four
        // columns on `notebooks` (`shelf_id` NOT NULL with default
        // 'shelf-general', `series_id` nullable, `volume_number`
        // DEFAULT 1, `volume_name` nullable), and seeds the default
        // "General" shelf. Mirrors Android's v11→v12 migration.
        migrator.registerMigration("v3_shelves_and_volumes") { db in
            try db.execute(sql: """
                CREATE TABLE shelves (
                    id              TEXT PRIMARY KEY NOT NULL,
                    name            TEXT NOT NULL,
                    color_hex       TEXT,
                    position        INTEGER NOT NULL DEFAULT 1024,
                    created_at      TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
                    updated_at      TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
                    dirty           INTEGER NOT NULL DEFAULT 1 CHECK (dirty IN (0, 1)),
                    deleted_at      TEXT
                )
                """)
            try db.execute(sql: "CREATE INDEX idx_shelves_position   ON shelves(position)")
            try db.execute(sql: "CREATE INDEX idx_shelves_deleted_at ON shelves(deleted_at)")

            try db.execute(sql: """
                CREATE TABLE book_series (
                    id              TEXT PRIMARY KEY NOT NULL,
                    shelf_id        TEXT NOT NULL,
                    name            TEXT NOT NULL,
                    created_at      TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
                    updated_at      TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
                    dirty           INTEGER NOT NULL DEFAULT 1 CHECK (dirty IN (0, 1)),
                    deleted_at      TEXT
                )
                """)
            try db.execute(sql: "CREATE INDEX idx_book_series_shelf_id   ON book_series(shelf_id)")
            try db.execute(sql: "CREATE INDEX idx_book_series_deleted_at ON book_series(deleted_at)")

            // Seed the default shelf before the ALTER so the
            // DEFAULT 'shelf-general' points at a live row.
            try db.execute(sql: """
                INSERT OR IGNORE INTO shelves (
                    id, name, color_hex, position, created_at, updated_at, dirty
                ) VALUES (
                    'shelf-general', 'General', '#7AA874', 1024,
                    strftime('%Y-%m-%dT%H:%M:%fZ', 'now'),
                    strftime('%Y-%m-%dT%H:%M:%fZ', 'now'),
                    1
                )
                """)

            // iOS's base `notebooks` table was created in v1 without
            // `description` / `archived_at` (Android has them from
            // migration 3→4). Those live on Android-only today;
            // we're only adding the shelf/volume columns here so
            // the two schemas converge on the new hierarchy.
            try db.execute(sql: "ALTER TABLE notebooks ADD COLUMN shelf_id TEXT NOT NULL DEFAULT 'shelf-general'")
            try db.execute(sql: "ALTER TABLE notebooks ADD COLUMN series_id TEXT")
            try db.execute(sql: "ALTER TABLE notebooks ADD COLUMN volume_number INTEGER NOT NULL DEFAULT 1")
            try db.execute(sql: "ALTER TABLE notebooks ADD COLUMN volume_name TEXT")
            try db.execute(sql: "CREATE INDEX idx_notebooks_shelf_id  ON notebooks(shelf_id)")
            try db.execute(sql: "CREATE INDEX idx_notebooks_series_id ON notebooks(series_id) WHERE series_id IS NOT NULL")
        }

        // v4 — adds the `call_history` table. Local log of outbound
        // calls placed from inside the app. Rows are written on dial
        // and updated in-place by CXCallObserver as the call
        // connects / ends. Mirrors Android's v12→v13 migration.
        migrator.registerMigration("v4_call_history") { db in
            try db.execute(sql: """
                CREATE TABLE call_history (
                    id                TEXT PRIMARY KEY NOT NULL,
                    user_id           TEXT NOT NULL,
                    contact_name      TEXT NOT NULL,
                    phone_number      TEXT NOT NULL,
                    source            TEXT NOT NULL,
                    started_at        TEXT NOT NULL,
                    connected_at      TEXT,
                    ended_at          TEXT,
                    duration_seconds  INTEGER
                )
                """)
            try db.execute(sql: "CREATE INDEX idx_call_history_user_id    ON call_history(user_id)")
            try db.execute(sql: "CREATE INDEX idx_call_history_started_at ON call_history(started_at)")
        }

        // v5 — adds the `description` column on `notepad_entries`.
        // Optional free-text subtitle — same role the column plays on
        // `notebooks`. Nullable, no default; existing rows round-trip
        // as NULL (the UI treats that as "no description yet").
        // Mirrors Android's v15→v16 migration and the shared
        // design-system/migrations/v2_notepad_description.sql.
        migrator.registerMigration("v5_notepad_description") { db in
            try db.execute(sql: "ALTER TABLE notepad_entries ADD COLUMN description TEXT")
        }

        // v6 — backfills the columns the `DriveRepository` protocol
        // depends on but the original v1 schema never carried:
        //
        //   - pages.tags            JSON array, default `[]`. Stores
        //                           free-form tag strings shown as
        //                           pills on the page detail surface.
        //   - pages.archived_at     ISO timestamp, nullable. The
        //                           archive bin shows pages where
        //                           this is non-NULL; deleted_at
        //                           stays separate (deleted = real
        //                           soft-delete).
        //   - chapters.archived_at  Same shape as pages.archived_at.
        //   - notebooks.archived_at Same shape; splits "archive" from
        //                           "soft-delete" so the archive bin
        //                           can hold recoverable notebooks
        //                           without confusion.
        //
        // Aligns with Android's archive_at semantics on
        // NotebookEntity (already present there from Migration3To4).
        migrator.registerMigration("v6_archive_and_tags") { db in
            try db.execute(sql: "ALTER TABLE pages ADD COLUMN tags TEXT NOT NULL DEFAULT '[]'")
            try db.execute(sql: "ALTER TABLE pages ADD COLUMN archived_at TEXT")
            try db.execute(sql: "ALTER TABLE chapters ADD COLUMN archived_at TEXT")
            try db.execute(sql: "ALTER TABLE notebooks ADD COLUMN archived_at TEXT")
            try db.execute(sql: "CREATE INDEX idx_pages_archived_at ON pages (archived_at) WHERE archived_at IS NOT NULL")
            try db.execute(sql: "CREATE INDEX idx_chapters_archived_at ON chapters (archived_at) WHERE archived_at IS NOT NULL")
            try db.execute(sql: "CREATE INDEX idx_notebooks_archived_at ON notebooks (archived_at) WHERE archived_at IS NOT NULL")
        }

        // v7 — adds `pages.page_notes_json`. Stores the typed Note
        // array (id + body + createdAt per element) so each Note
        // keeps its identity through the persistence round-trip;
        // the legacy `pages.notes` markdown column lost ids and
        // timestamps when multiple notes were joined together.
        // The markdown column stays so FTS keeps working — the
        // mapper joins all note bodies into it on every write.
        // Mirrors Android's Migration17To18.
        migrator.registerMigration("v7_page_notes_json") { db in
            try db.execute(sql: "ALTER TABLE pages ADD COLUMN page_notes_json TEXT NOT NULL DEFAULT '[]'")
        }

        return migrator
    }
}
