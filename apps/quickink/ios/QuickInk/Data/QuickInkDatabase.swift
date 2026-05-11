/*
 * QuickInkDatabase.swift
 *
 * GRDB hub for QuickInk's SQLite tables — `notepad_entries`,
 * `sync_state`, `captures`, `ocr_results` — plus the FTS5 virtual
 * tables for notes search and OCR-text search. One shared
 * `DatabaseQueue` serves the notepad list, scan flow, and the
 * (future) sync worker. Mirror of Releaf's `ReleafDatabase.swift`,
 * trimmed to QuickInk's surface per QUICKINK_PROPOSAL.md §3.
 *
 * File location: the DB lives at
 * `<Application Support>/QuickInk/quickink.db`. Distinct dir from
 * Releaf's `<Application Support>/Releaf/releaf.db` so two apps on
 * the same device don't collide; both are sandboxed per-app
 * anyway, but the explicit dir nesting makes intent clear.
 *
 * Migration posture (matches Releaf's flatten):
 *   - Single `v1_initial` migration that matches the schema in
 *     `shared/design-system/migrations/quickink/v1_initial.sql`
 *     byte-for-byte. The .sql file is the canonical reference; the
 *     CREATE statements below mirror it. Future schema bumps add
 *     `registerMigration("vN_description")` blocks rather than
 *     editing v1.
 *   - Dogfood flag `eraseDatabaseOnSchemaChange = true` wipes the
 *     file whenever the registered-migration list differs from the
 *     on-disk history. Remove before real user data lands.
 */

import Foundation
import GRDB

public final class QuickInkDatabase: @unchecked Sendable {

    public static let shared = QuickInkDatabase()

    /// `DatabaseQueue` over the on-disk SQLite file. GRDB serializes
    /// access through the queue, so concurrent read/write from
    /// SwiftUI's main actor + the (future) sync worker's background
    /// task is safe without explicit locks.
    public let dbQueue: DatabaseQueue

    // MARK: - Construction

    /// Designated initializer. Defaults to a file-backed store so
    /// the app persists across launches; tests and previews pass
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
            fatalError("QuickInkDatabase: failed to open DatabaseQueue: \(error)")
        }
        self.dbQueue = queue

        do {
            try Self.migrator.migrate(queue)
        } catch {
            fatalError("QuickInkDatabase: migration failed: \(error)")
        }
    }

    /// URL for the on-disk SQLite file. Application Support lives
    /// outside the iCloud-autobackup sweep and is the right place
    /// for a SQLite cache that we'll be rebuilding from Drive on
    /// fresh installs anyway.
    private static func defaultDatabaseURL() throws -> URL {
        let fm = FileManager.default
        let base = try fm.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        let dir = base.appendingPathComponent("QuickInk", isDirectory: true)
        if !fm.fileExists(atPath: dir.path) {
            try fm.createDirectory(at: dir, withIntermediateDirectories: true)
        }
        return dir.appendingPathComponent("quickink.db")
    }

    // MARK: - Migrator

    /// Single-migration drop-in of QuickInk's v1 schema. Keep this
    /// in sync with `shared/design-system/migrations/quickink/v1_initial.sql`
    /// — when that file gains new tables or columns, add a new
    /// `registerMigration("vN_description")` rather than editing
    /// this one (see the dogfood note at the top).
    private static var migrator: DatabaseMigrator {
        var migrator = DatabaseMigrator()

        // Dogfood-only. Wipes the DB when the migration history on
        // disk diverges from the code's registered migrations.
        // Remove before real users have data.
        migrator.eraseDatabaseOnSchemaChange = true

        migrator.registerMigration("v1_initial") { db in
            // ─── Tables ─────────────────────────────────────────

            // notepad_entries — column list MUST match Releaf's
            // notepad_entries (CI shared-tables diff). See the .sql
            // file's header for the rationale on why QuickInk
            // carries unused side-channel columns.
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
                    description             TEXT,
                    category                TEXT,
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

            // sync_state — mirrors :shared:sync's SyncStateEntity.
            try db.execute(sql: """
                CREATE TABLE sync_state (
                    drive_file_id           TEXT PRIMARY KEY NOT NULL,
                    local_etag              TEXT,
                    remote_etag             TEXT,
                    last_pulled_at          TEXT,
                    last_pushed_at          TEXT,
                    conflict_stub           TEXT
                )
                """)

            // captures — one row per scan session.
            try db.execute(sql: """
                CREATE TABLE captures (
                    id                      TEXT PRIMARY KEY NOT NULL,
                    user_id                 TEXT NOT NULL,
                    title                   TEXT,
                    pdf_uri                 TEXT NOT NULL,
                    preview_uri             TEXT,
                    page_count              INTEGER NOT NULL DEFAULT 0,
                    conflict_stub           TEXT,
                    drive_file_id           TEXT,
                    created_at              TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
                    updated_at              TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
                    dirty                   INTEGER NOT NULL DEFAULT 1 CHECK (dirty IN (0, 1)),
                    deleted_at              TEXT
                )
                """)

            // ocr_results — one row per scanned page; per §6.2.
            try db.execute(sql: """
                CREATE TABLE ocr_results (
                    id                      TEXT PRIMARY KEY NOT NULL,
                    capture_id              TEXT NOT NULL REFERENCES captures(id) ON DELETE CASCADE,
                    page_index              INTEGER NOT NULL,
                    language                TEXT,
                    confidence              REAL,
                    text                    TEXT NOT NULL,
                    blocks_json             TEXT NOT NULL,
                    engine                  TEXT NOT NULL,
                    engine_version          TEXT,
                    drive_file_id           TEXT,
                    created_at              TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
                    updated_at              TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
                    dirty                   INTEGER NOT NULL DEFAULT 1 CHECK (dirty IN (0, 1)),
                    deleted_at              TEXT,
                    UNIQUE (capture_id, page_index)
                )
                """)

            // ─── Indexes ────────────────────────────────────────

            try db.execute(sql: "CREATE INDEX idx_notepad_entries_user_date    ON notepad_entries (user_id, entry_date)")
            try db.execute(sql: "CREATE INDEX idx_notepad_entries_dirty        ON notepad_entries (dirty) WHERE dirty = 1")
            try db.execute(sql: "CREATE INDEX idx_notepad_entries_tombstone    ON notepad_entries (deleted_at) WHERE deleted_at IS NOT NULL")

            try db.execute(sql: "CREATE INDEX idx_captures_user_created        ON captures (user_id, created_at)")
            try db.execute(sql: "CREATE INDEX idx_captures_dirty               ON captures (dirty) WHERE dirty = 1")
            try db.execute(sql: "CREATE INDEX idx_captures_tombstone           ON captures (deleted_at) WHERE deleted_at IS NOT NULL")

            try db.execute(sql: "CREATE INDEX idx_ocr_results_capture          ON ocr_results (capture_id)")
            try db.execute(sql: "CREATE INDEX idx_ocr_results_dirty            ON ocr_results (dirty) WHERE dirty = 1")
            try db.execute(sql: "CREATE INDEX idx_ocr_results_tombstone        ON ocr_results (deleted_at) WHERE deleted_at IS NOT NULL")

            // ─── FTS5 + triggers ────────────────────────────────

            try db.execute(sql: """
                CREATE VIRTUAL TABLE fts_notepad_notes USING fts5(
                    notepad_entry_id UNINDEXED,
                    notes,
                    tokenize = 'unicode61 remove_diacritics 2'
                )
                """)
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

            try db.execute(sql: """
                CREATE VIRTUAL TABLE fts_ocr_text USING fts5(
                    ocr_result_id UNINDEXED,
                    text,
                    tokenize = 'unicode61 remove_diacritics 2'
                )
                """)
            try db.execute(sql: """
                CREATE TRIGGER ocr_results_fts_ai AFTER INSERT ON ocr_results
                WHEN new.deleted_at IS NULL AND new.text <> ''
                BEGIN
                    INSERT INTO fts_ocr_text(ocr_result_id, text)
                    VALUES (new.id, new.text);
                END
                """)
            try db.execute(sql: """
                CREATE TRIGGER ocr_results_fts_au AFTER UPDATE ON ocr_results
                BEGIN
                    DELETE FROM fts_ocr_text WHERE ocr_result_id = old.id;
                    INSERT INTO fts_ocr_text(ocr_result_id, text)
                    SELECT new.id, new.text
                    WHERE new.deleted_at IS NULL AND new.text <> '';
                END
                """)
            try db.execute(sql: """
                CREATE TRIGGER ocr_results_fts_ad AFTER DELETE ON ocr_results
                BEGIN
                    DELETE FROM fts_ocr_text WHERE ocr_result_id = old.id;
                END
                """)
        }

        // ─── v2_capture_categories ──────────────────────────────
        //
        // Adds `captures.category` and the `categories` table per
        // `shared/design-system/migrations/quickink/v2_capture_categories.sql`.
        // Default seed values live in app code (Settings + first-
        // launch seeding) — migrations don't know `userId`.
        migrator.registerMigration("v2_capture_categories") { db in
            try db.execute(sql: "ALTER TABLE captures ADD COLUMN category TEXT")

            try db.execute(sql: """
                CREATE TABLE categories (
                    id              TEXT PRIMARY KEY NOT NULL,
                    user_id         TEXT NOT NULL,
                    name            TEXT NOT NULL,
                    position        INTEGER NOT NULL DEFAULT 0,
                    drive_file_id   TEXT,
                    created_at      TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
                    updated_at      TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
                    dirty           INTEGER NOT NULL DEFAULT 1 CHECK (dirty IN (0, 1)),
                    deleted_at      TEXT,
                    UNIQUE (user_id, name)
                )
                """)

            try db.execute(sql: "CREATE INDEX idx_categories_user_position ON categories (user_id, position)")
            try db.execute(sql: "CREATE INDEX idx_categories_dirty         ON categories (dirty)      WHERE dirty = 1")
            try db.execute(sql: "CREATE INDEX idx_categories_tombstone     ON categories (deleted_at) WHERE deleted_at IS NOT NULL")
        }

        // ─── v3_capture_drive_binaries ──────────────────────────
        //
        // Adds two columns to `captures` so each row tracks the
        // Drive file id of its uploaded PDF + preview JPEG. NULL =
        // not uploaded yet; populated by `markPdfSynced` /
        // `markPreviewSynced` after `SyncRepository` pushes the
        // binaries via `DriveClient.uploadBinary`.
        migrator.registerMigration("v3_capture_drive_binaries") { db in
            try db.execute(sql: "ALTER TABLE captures ADD COLUMN pdf_drive_file_id TEXT")
            try db.execute(sql: "ALTER TABLE captures ADD COLUMN preview_drive_file_id TEXT")
        }

        // ─── v4_capture_source ──────────────────────────────────
        //
        // Adds `captures.source` so the Library cards can flag
        // captures that came from the system photo picker
        // ("import") versus the document scanner ("scan", default).
        // Free-form TEXT instead of an enum so a future third
        // source ("share-extension", etc.) can land without another
        // migration. Defaulted at the column level so legacy rows
        // synced from older clients (without the field on the wire)
        // read back as scans.
        migrator.registerMigration("v4_capture_source") { db in
            try db.execute(sql: "ALTER TABLE captures ADD COLUMN source TEXT NOT NULL DEFAULT 'scan'")
        }

        // ─── v5_analytics_outbox ────────────────────────────────
        //
        // Adds the `analytics_outbox` table that AnalyticsRepository
        // writes capture / identify events into for the QuickInk
        // backend (api-quickink.thoughtbasics.com). Mirror of
        // Android's Room v6 entity. Standalone — no FKs to the rest
        // of the QuickInk schema, so a `captures` row is free to
        // exist without a matching analytics outbox entry (and vice
        // versa) without breaking referential integrity.
        //
        // Composite index on (next_attempt_at, created_at) covers
        // the worker's `WHERE next_attempt_at <= now ORDER BY
        // created_at` read pattern in `AnalyticsRepository.nextBatch`
        // — without it, every flush full-scans the table.
        migrator.registerMigration("v5_analytics_outbox") { db in
            try db.execute(sql: """
                CREATE TABLE analytics_outbox (
                    id                  TEXT PRIMARY KEY NOT NULL,
                    kind                TEXT NOT NULL,
                    payload_json        TEXT NOT NULL,
                    created_at          TEXT NOT NULL,
                    attempts            INTEGER NOT NULL DEFAULT 0,
                    next_attempt_at     TEXT NOT NULL,
                    last_error          TEXT
                )
                """)
            try db.execute(sql: """
                CREATE INDEX idx_analytics_outbox_due
                    ON analytics_outbox (next_attempt_at, created_at)
                """)
        }

        // ─── v6_capture_geolocation ─────────────────────────────
        //
        // Adds geolocation fields to `captures` so a scan can carry
        // the device's last-known position at the time of capture +
        // the reverse-geocoded place name (sub-locality and locality)
        // for display on the detail card and search-by-place follow-
        // ups. All four columns are nullable — older rows, captures
        // taken with the user's "Location for scans" toggle off, or
        // failed reverse-geocode attempts all read back as NULL.
        //
        // Schema choices:
        //   - latitude / longitude as REAL keeps the on-disk format
        //     simple (no compound type) and matches the precision
        //     CLLocationCoordinate2D returns from CoreLocation.
        //   - locality is the city (CLPlacemark.locality), sub-
        //     locality is the neighbourhood / area (CLPlacemark.sub-
        //     Locality). Both are TEXT so we round-trip the user's
        //     locale-aware string verbatim through Drive sync.
        //
        // Mirror of Android's Room v7 schema bump.
        migrator.registerMigration("v6_capture_geolocation") { db in
            try db.execute(sql: "ALTER TABLE captures ADD COLUMN latitude REAL")
            try db.execute(sql: "ALTER TABLE captures ADD COLUMN longitude REAL")
            try db.execute(sql: "ALTER TABLE captures ADD COLUMN locality TEXT")
            try db.execute(sql: "ALTER TABLE captures ADD COLUMN sub_locality TEXT")
        }

        return migrator
    }
}
