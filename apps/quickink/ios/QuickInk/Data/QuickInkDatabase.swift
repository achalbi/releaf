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
import ReleafCoreData

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

        // ─── v7_capture_address ─────────────────────────────────
        //
        // Adds the formatted full street address alongside the
        // (locality, sub_locality) pair. The composed string —
        // built from CLPlacemark via CNPostalAddressFormatter —
        // gives the Details card a "1234 Main St, Mission
        // District, San Francisco, CA 94110, USA"-style line that
        // carries the street + ZIP + country which the
        // city/area pair alone can't surface.
        //
        // Nullable for the same reasons as the v6 columns: older
        // rows, location toggle off, denied permission, or a
        // failed geocode all read back as NULL. Round-trips through
        // Drive via `CapturePayloadV2.address`.
        //
        // Mirror of Android's Room v8 schema bump.
        migrator.registerMigration("v7_capture_address") { db in
            try db.execute(sql: "ALTER TABLE captures ADD COLUMN address TEXT")
        }

        // ─── v8_workspace ──────────────────────────────────────
        //
        // Workspace v1 (Phase A) — the two-axis IA from
        // shared/design-system/migrations/quickink/v4_workspace.sql.
        // Same SQL as Android's destructive rebuild; iOS lands it
        // as a real migration so the DB survives the version bump
        // once `eraseDatabaseOnSchemaChange` is removed.
        //
        // Adds:
        //   1. `tags` (renamed from `categories`) + a `color` column.
        //   2. `folders` — intent axis. is_default=1 row is the
        //      seeded "Unfiled" folder; is_shared reserved for the
        //      post-v1 share flow.
        //   3. `capture_tags` many-to-many join — each row syncs
        //      independently so cross-device tag attachments don't
        //      require re-uploading the parent capture.
        //   4. `smart_collections` — rule-based saved views;
        //      rule_json holds the AND-of-clauses grammar.
        //   5. `captures.folder_id`, `captures.last_opened_at`,
        //      `captures.last_opened_page`, `captures.last_opened_
        //      device`.
        //
        // captures.category column drop is deferred to a follow-up
        // (iOS A.3c) — same staging as Android. The materialize
        // step (categories → capture_tags rows) runs in app code on
        // first launch after upgrade; FolderRepository owns it.
        //
        // Mirror of Android's Room v10 + the canonical
        // v4_workspace.sql.
        migrator.registerMigration("v8_workspace") { db in
            // ─── Rename categories → tags ─────────────────────
            try db.execute(sql: "ALTER TABLE categories RENAME TO tags")
            try db.execute(sql: "DROP INDEX IF EXISTS idx_categories_user_position")
            try db.execute(sql: "DROP INDEX IF EXISTS idx_categories_dirty")
            try db.execute(sql: "DROP INDEX IF EXISTS idx_categories_tombstone")
            try db.execute(sql: "CREATE INDEX idx_tags_user_position ON tags (user_id, position)")
            try db.execute(sql: "CREATE INDEX idx_tags_dirty         ON tags (dirty)      WHERE dirty = 1")
            try db.execute(sql: "CREATE INDEX idx_tags_tombstone     ON tags (deleted_at) WHERE deleted_at IS NOT NULL")

            // Nullable hex color; UI falls back to accent tint.
            try db.execute(sql: "ALTER TABLE tags ADD COLUMN color TEXT")

            // ─── folders ──────────────────────────────────────
            try db.execute(sql: """
                CREATE TABLE folders (
                    id              TEXT PRIMARY KEY NOT NULL,
                    user_id         TEXT NOT NULL,
                    name            TEXT NOT NULL,
                    color           TEXT NOT NULL,
                    position        INTEGER NOT NULL DEFAULT 0,
                    cover_uri       TEXT,
                    is_default      INTEGER NOT NULL DEFAULT 0 CHECK (is_default IN (0, 1)),
                    is_shared       INTEGER NOT NULL DEFAULT 0 CHECK (is_shared IN (0, 1)),
                    drive_file_id   TEXT,
                    created_at      TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
                    updated_at      TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
                    dirty           INTEGER NOT NULL DEFAULT 1 CHECK (dirty IN (0, 1)),
                    deleted_at      TEXT
                )
                """)
            try db.execute(sql: "CREATE INDEX idx_folders_user_position ON folders (user_id, position)")
            try db.execute(sql: "CREATE INDEX idx_folders_dirty         ON folders (dirty)      WHERE dirty = 1")
            try db.execute(sql: "CREATE INDEX idx_folders_tombstone     ON folders (deleted_at) WHERE deleted_at IS NOT NULL")
            try db.execute(sql: """
                CREATE UNIQUE INDEX idx_folders_user_name_active
                    ON folders (user_id, name)
                    WHERE deleted_at IS NULL
                """)

            // ─── capture_tags ─────────────────────────────────
            try db.execute(sql: """
                CREATE TABLE capture_tags (
                    id              TEXT PRIMARY KEY NOT NULL,
                    capture_id      TEXT NOT NULL,
                    tag_id          TEXT NOT NULL,
                    source          TEXT NOT NULL DEFAULT 'manual',
                    drive_file_id   TEXT,
                    created_at      TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
                    updated_at      TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
                    dirty           INTEGER NOT NULL DEFAULT 1 CHECK (dirty IN (0, 1)),
                    deleted_at      TEXT,
                    FOREIGN KEY (capture_id) REFERENCES captures (id),
                    FOREIGN KEY (tag_id)     REFERENCES tags     (id)
                )
                """)
            try db.execute(sql: "CREATE INDEX idx_capture_tags_capture   ON capture_tags (capture_id)")
            try db.execute(sql: "CREATE INDEX idx_capture_tags_tag       ON capture_tags (tag_id)")
            try db.execute(sql: "CREATE INDEX idx_capture_tags_dirty     ON capture_tags (dirty)      WHERE dirty = 1")
            try db.execute(sql: "CREATE INDEX idx_capture_tags_tombstone ON capture_tags (deleted_at) WHERE deleted_at IS NOT NULL")
            try db.execute(sql: """
                CREATE UNIQUE INDEX idx_capture_tags_unique_active
                    ON capture_tags (capture_id, tag_id)
                    WHERE deleted_at IS NULL
                """)

            // ─── smart_collections ────────────────────────────
            try db.execute(sql: """
                CREATE TABLE smart_collections (
                    id              TEXT PRIMARY KEY NOT NULL,
                    user_id         TEXT NOT NULL,
                    name            TEXT NOT NULL,
                    icon            TEXT,
                    color           TEXT,
                    rule_json       TEXT NOT NULL,
                    position        INTEGER NOT NULL DEFAULT 0,
                    is_seeded       INTEGER NOT NULL DEFAULT 0 CHECK (is_seeded IN (0, 1)),
                    drive_file_id   TEXT,
                    created_at      TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
                    updated_at      TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
                    dirty           INTEGER NOT NULL DEFAULT 1 CHECK (dirty IN (0, 1)),
                    deleted_at      TEXT
                )
                """)
            try db.execute(sql: "CREATE INDEX idx_smart_collections_user_position ON smart_collections (user_id, position)")
            try db.execute(sql: "CREATE INDEX idx_smart_collections_dirty         ON smart_collections (dirty)      WHERE dirty = 1")
            try db.execute(sql: "CREATE INDEX idx_smart_collections_tombstone     ON smart_collections (deleted_at) WHERE deleted_at IS NOT NULL")

            // ─── captures additions ───────────────────────────
            try db.execute(sql: "ALTER TABLE captures ADD COLUMN folder_id TEXT")
            try db.execute(sql: "ALTER TABLE captures ADD COLUMN last_opened_at TEXT")
            try db.execute(sql: "ALTER TABLE captures ADD COLUMN last_opened_page INTEGER")
            try db.execute(sql: "ALTER TABLE captures ADD COLUMN last_opened_device TEXT")

            try db.execute(sql: """
                CREATE INDEX idx_captures_folder_created
                    ON captures (folder_id, created_at)
                    WHERE deleted_at IS NULL
                """)
            try db.execute(sql: """
                CREATE INDEX idx_captures_last_opened
                    ON captures (user_id, last_opened_at)
                    WHERE last_opened_at IS NOT NULL AND deleted_at IS NULL
                """)
        }

        // v9 — Workspace v1 Phase A.3c. Drops the legacy
        // `captures.category` TEXT column. Materializes every
        // non-null `category` value into a `capture_tags` row first
        // (find-or-create the tag by name in the user's namespace,
        // attach as source = "migration"), so users upgrading
        // straight from a pre-v8 schema or from v8 → v9 in a
        // single launch don't lose their data. Idempotent — the
        // partial-unique-index on (capture_id, tag_id) WHERE
        // deleted_at IS NULL keeps duplicate inserts from racing.
        //
        // SQLite's `ALTER TABLE DROP COLUMN` landed in 3.35 (2021)
        // and is available on every iOS we ship for, so a direct
        // DROP is fine — no table-rebuild dance needed. Whole
        // migration runs in GRDB's outer transaction so a failure
        // mid-materialize leaves the column intact for retry.
        //
        // Mirror of Android's Room v11; canonical SQL lives in
        // v4_workspace.sql §7.
        migrator.registerMigration("v9_capture_category_drop") { db in
            // 1. Find-or-create a tag per distinct (user_id,
            //    category) pair, then attach via capture_tags.
            let rows = try Row.fetchAll(db, sql: """
                SELECT id AS capture_id, user_id, category
                FROM captures
                WHERE category IS NOT NULL
                  AND deleted_at IS NULL
                """)
            for row in rows {
                let captureId: String = row["capture_id"]
                let userId:    String = row["user_id"]
                let name:      String = row["category"]
                let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
                if trimmed.isEmpty { continue }

                // Tag find-or-create. The (user_id, name) UNIQUE
                // partial index excluding tombstones keeps this
                // race-safe — we read, write-if-missing, and
                // re-read on the rare collision.
                var tagId: String? = try String.fetchOne(db, sql: """
                    SELECT id FROM tags
                    WHERE user_id = ? AND name = ? AND deleted_at IS NULL
                    """, arguments: [userId, trimmed])
                if tagId == nil {
                    let newId = Uuidv7.generate()
                    let now = IsoClock.nowIso()
                    do {
                        try db.execute(sql: """
                            INSERT INTO tags
                                (id, user_id, name, position, color,
                                 drive_file_id, created_at, updated_at,
                                 dirty, deleted_at)
                            VALUES (?, ?, ?, ?, NULL, NULL, ?, ?, 1, NULL)
                            """, arguments: [newId, userId, trimmed, Int.max, now, now])
                        tagId = newId
                    } catch let e as DatabaseError where e.resultCode == .SQLITE_CONSTRAINT {
                        tagId = try String.fetchOne(db, sql: """
                            SELECT id FROM tags
                            WHERE user_id = ? AND name = ? AND deleted_at IS NULL
                            """, arguments: [userId, trimmed])
                    }
                }
                guard let resolvedTagId = tagId else { continue }

                // Skip if an active or tombstoned join row already
                // exists for this pair — the unique-active index
                // would refuse the insert anyway.
                let already: Int = try Int.fetchOne(db, sql: """
                    SELECT COUNT(*) FROM capture_tags
                    WHERE capture_id = ? AND tag_id = ?
                    """, arguments: [captureId, resolvedTagId]) ?? 0
                if already > 0 { continue }

                let now = IsoClock.nowIso()
                do {
                    try db.execute(sql: """
                        INSERT INTO capture_tags
                            (id, capture_id, tag_id, source,
                             drive_file_id, created_at, updated_at,
                             dirty, deleted_at)
                        VALUES (?, ?, ?, 'migration', NULL, ?, ?, 1, NULL)
                        """, arguments: [Uuidv7.generate(), captureId, resolvedTagId, now, now])
                } catch let e as DatabaseError where e.resultCode == .SQLITE_CONSTRAINT {
                    // Race lost — another writer landed the same pair.
                }
            }

            // 2. Drop the column. Direct DROP on SQLite 3.35+
            //    (every iOS we support ships ≥ 3.36).
            try db.execute(sql: "ALTER TABLE captures DROP COLUMN category")
        }

        // ─── v10_panchanga ──────────────────────────────────────
        //
        // Adds the read-mostly `panchanga` table backing the Calendar
        // screen's Hindu-calendar panel. Seeded on first launch from
        // the bundled `Resources/panchanga_2026_27.csv` via
        // `PanchangaRepository.ensureLoaded()` — the migration only
        // creates the schema, never inserts rows (CSV parsing happens
        // off the migration's writer to keep the migration fast).
        //
        // Schema mirrors Releaf Android's `PanchangaEntity` Room
        // table: composite-string primary key `date#thithi_num`
        // (handles transition days carrying two tithis), an indexed
        // `date` column for per-day / per-month reads, and a
        // lower-cased `special_day_lc` mirror that lets the in-memory
        // festival search stay case-insensitive without function
        // calls in the WHERE clause.
        migrator.registerMigration("v10_panchanga") { db in
            try db.execute(sql: """
                CREATE TABLE panchanga (
                    id              TEXT PRIMARY KEY NOT NULL,
                    date            TEXT NOT NULL,
                    masa            TEXT NOT NULL DEFAULT '',
                    paksha          TEXT NOT NULL DEFAULT '',
                    thithi          TEXT NOT NULL DEFAULT '',
                    thithi_num      TEXT NOT NULL DEFAULT '',
                    special_day     TEXT NOT NULL DEFAULT '',
                    special_day_lc  TEXT NOT NULL DEFAULT ''
                )
                """)
            try db.execute(sql: "CREATE INDEX idx_panchanga_date           ON panchanga (date)")
            try db.execute(sql: "CREATE INDEX idx_panchanga_special_day_lc ON panchanga (special_day_lc)")
        }

        // ─── v11_capture_paper_size ─────────────────────────────
        //
        // Adds `captures.paper_size` so the sustainability hero can
        // weight each page differently based on its size — business
        // cards get a bulk-print bonus (+4 pts/page), A4 / Letter the
        // baseline (+2), smaller-than-A4 imports the minimum (+1).
        // Free-form TEXT (mirrors `source` in v4) so future formats
        // land without another migration. Defaulted to `'a4'` so
        // existing rows and rows synced from older clients without
        // the field on the wire read back as standard pages — no
        // retroactive credit for historical card scans, which is the
        // intentional behavior (we don't know which past captures
        // were card-mode without a per-row signal we never stored).
        //
        // Mirror of Android's Room v13 schema bump.
        migrator.registerMigration("v11_capture_paper_size") { db in
            try db.execute(sql: "ALTER TABLE captures ADD COLUMN paper_size TEXT NOT NULL DEFAULT 'a4'")
        }

        // ─── v12_voice_notes ────────────────────────────────────
        //
        // Document-attached voice notes. One row per recorded clip;
        // the clip itself lives in AttachmentStorage as an .m4a and
        // the row points at it through `audio_uri`. A capture can
        // have any number of notes; deleting the capture cascades to
        // its notes via the foreign key, the same way `ocr_results`
        // attach.
        //
        // Two drive-id columns instead of one:
        //   - `drive_file_id`        — the JSON metadata row on Drive
        //                              (mirror of `captures.drive_
        //                              file_id`).
        //   - `audio_drive_file_id`  — the uploaded .m4a binary
        //                              (mirror of `captures.pdf_drive_
        //                              file_id`).
        // Splitting them lets the sync worker update the transcript
        // without re-uploading the audio binary, which is the common
        // case after first sync.
        //
        // Mirror of Android's Room v14 schema bump.
        migrator.registerMigration("v12_voice_notes") { db in
            try db.execute(sql: """
                CREATE TABLE voice_notes (
                    id                      TEXT PRIMARY KEY NOT NULL,
                    capture_id              TEXT NOT NULL REFERENCES captures(id) ON DELETE CASCADE,
                    user_id                 TEXT NOT NULL,
                    audio_uri               TEXT NOT NULL,
                    duration_ms             INTEGER NOT NULL DEFAULT 0,
                    transcription           TEXT,
                    transcription_source    TEXT,
                    drive_file_id           TEXT,
                    audio_drive_file_id     TEXT,
                    created_at              TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
                    updated_at              TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
                    dirty                   INTEGER NOT NULL DEFAULT 1 CHECK (dirty IN (0, 1)),
                    deleted_at              TEXT
                )
                """)
            try db.execute(sql: "CREATE INDEX idx_voice_notes_capture   ON voice_notes (capture_id, created_at)")
            try db.execute(sql: "CREATE INDEX idx_voice_notes_user      ON voice_notes (user_id, created_at)")
            try db.execute(sql: "CREATE INDEX idx_voice_notes_dirty     ON voice_notes (dirty)      WHERE dirty = 1")
            try db.execute(sql: "CREATE INDEX idx_voice_notes_tombstone ON voice_notes (deleted_at) WHERE deleted_at IS NOT NULL")
        }

        // ─── v13_capture_notes ──────────────────────────────────
        //
        // Free-form notes column on `captures`. Append-only in the
        // current UX: after a voice note is recorded and the
        // transcript editor saves, the edited text is appended here
        // as a new paragraph. Nullable; older rows + rows synced
        // from pre-v13 clients read back as NULL. Round-trips
        // through Drive via `CapturePayloadV2.notes`.
        migrator.registerMigration("v13_capture_notes") { db in
            try db.execute(sql: "ALTER TABLE captures ADD COLUMN notes TEXT")
        }

        // ─── v14_stories ────────────────────────────────────────
        //
        // Stories feature Phase 1 — see design/STORIES_HANDOFF.md.
        // Two tables: `story` (the curated narrative itself) and
        // `story_item` (its ordered children — references to
        // captures, photos, notes, voice clips, or inline text /
        // handwritten / date-divider / place-pin blocks).
        //
        // `position` is 1024-spaced so a reorder is a single update
        // (new = (prev + next) / 2) instead of a full rewrite; we
        // renormalize on collision (handled in the repository).
        //
        // `cover_item_id` on `story` intentionally is NOT declared
        // as a SQL foreign key. Per the handoff doc's don't-do list:
        // "Don't drop the cover_item_id FK when the referenced item
        // is removed from the story. Null it instead." The
        // repository nulls it on item-removal; a real FK with
        // ON DELETE SET NULL would also require restating the
        // SQLite circular-reference dance which we avoid by
        // enforcing the rule in code.
        //
        // `story_id` on `story_item` DOES have ON DELETE CASCADE —
        // removing a whole story removes all its items.
        //
        // Dirty + deleted_at columns mirror the rest of the schema
        // so the Phase 6 public-link sync can use the same dirty-
        // flag pattern when it ships.
        migrator.registerMigration("v14_stories") { db in
            try db.execute(sql: """
                CREATE TABLE story (
                    id                  TEXT PRIMARY KEY NOT NULL,
                    user_id             TEXT NOT NULL,
                    title               TEXT NOT NULL,
                    subtitle            TEXT,
                    cover_item_id       TEXT,
                    cover_style         TEXT NOT NULL DEFAULT 'photo',
                    theme_style         TEXT NOT NULL DEFAULT 'editorial',
                    grouping_mode       TEXT NOT NULL DEFAULT 'timeline',
                    time_range_start    TEXT,
                    time_range_end      TEXT,
                    status              TEXT NOT NULL DEFAULT 'draft',
                    share_mode          TEXT NOT NULL DEFAULT 'private',
                    share_slug          TEXT,
                    created_at          TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
                    updated_at          TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
                    dirty               INTEGER NOT NULL DEFAULT 1 CHECK (dirty IN (0, 1)),
                    deleted_at          TEXT
                )
                """)
            try db.execute(sql: """
                CREATE TABLE story_item (
                    id                  TEXT PRIMARY KEY NOT NULL,
                    story_id            TEXT NOT NULL REFERENCES story(id) ON DELETE CASCADE,
                    position            INTEGER NOT NULL,
                    kind                TEXT NOT NULL,
                    ref_id              TEXT,
                    text                TEXT,
                    caption             TEXT,
                    occurred_at         TEXT,
                    layout              TEXT NOT NULL DEFAULT 'full',
                    created_at          TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
                    updated_at          TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
                    dirty               INTEGER NOT NULL DEFAULT 1 CHECK (dirty IN (0, 1)),
                    deleted_at          TEXT
                )
                """)
            try db.execute(sql: "CREATE INDEX idx_story_user            ON story (user_id, updated_at DESC)")
            try db.execute(sql: "CREATE INDEX idx_story_dirty           ON story (dirty) WHERE dirty = 1")
            try db.execute(sql: "CREATE INDEX idx_story_tombstone       ON story (deleted_at) WHERE deleted_at IS NOT NULL")
            try db.execute(sql: "CREATE INDEX idx_story_item_story_pos  ON story_item (story_id, position)")
            try db.execute(sql: "CREATE INDEX idx_story_item_dirty      ON story_item (dirty) WHERE dirty = 1")
            try db.execute(sql: "CREATE INDEX idx_story_item_tombstone  ON story_item (deleted_at) WHERE deleted_at IS NOT NULL")
        }

        // ─── v15_story_voice_clips ──────────────────────────────
        //
        // Stories Phase 2 — inline voice clips attached to a
        // `story_item` row of kind = 'voice_clip'. Mirror of the
        // existing `voice_notes` table (capture-attached voice
        // notes), but keyed off `story_item_id` rather than
        // `capture_id`. AAC-LC 64 kbps mono, max 10 s. Drive sync
        // mirrors voice_notes: two columns, one for the JSON
        // metadata row, one for the .m4a binary.
        migrator.registerMigration("v15_story_voice_clips") { db in
            try db.execute(sql: """
                CREATE TABLE story_voice_clip (
                    id                      TEXT PRIMARY KEY NOT NULL,
                    story_item_id           TEXT NOT NULL REFERENCES story_item(id) ON DELETE CASCADE,
                    user_id                 TEXT NOT NULL,
                    audio_uri               TEXT NOT NULL,
                    duration_ms             INTEGER NOT NULL DEFAULT 0,
                    transcription           TEXT,
                    transcription_source    TEXT,
                    drive_file_id           TEXT,
                    audio_drive_file_id     TEXT,
                    created_at              TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
                    updated_at              TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
                    dirty                   INTEGER NOT NULL DEFAULT 1 CHECK (dirty IN (0, 1)),
                    deleted_at              TEXT
                )
                """)
            try db.execute(sql: "CREATE INDEX idx_story_voice_clip_item      ON story_voice_clip (story_item_id, created_at)")
            try db.execute(sql: "CREATE INDEX idx_story_voice_clip_user      ON story_voice_clip (user_id, created_at)")
            try db.execute(sql: "CREATE INDEX idx_story_voice_clip_dirty     ON story_voice_clip (dirty) WHERE dirty = 1")
            try db.execute(sql: "CREATE INDEX idx_story_voice_clip_tombstone ON story_voice_clip (deleted_at) WHERE deleted_at IS NOT NULL")
        }

        return migrator
    }
}
