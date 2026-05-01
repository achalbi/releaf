-- =============================================================
-- QuickInk — v1_initial.sql
--
-- Forked from `shared/design-system/migrations/v1_initial.sql`
-- (Releaf's canonical schema), trimmed to QuickInk's MVP surface
-- per QUICKINK_PROPOSAL.md §3. Both apps share the conventions
-- below (UUIDv7 ids, ISO-8601 timestamps, dirty bit + drive_file_id
-- + deleted_at sync trio); the table set diverges intentionally so
-- a column-rename in one app doesn't auto-propagate.
--
-- Shared tables (column-list MUST match Releaf's v1_initial.sql —
-- a CI check diffs these and fails on divergence per §3):
--   - notepad_entries          (rich-text notes; FTS5-indexed body)
--   - sync_state               (per-Drive-file sync metadata; lifted
--                               from :shared:sync's SyncStateEntity)
--
-- QuickInk-only tables:
--   - captures                 (one row per scan session)
--   - ocr_results              (one row per scanned page; per §6.2)
--
-- Releaf-only tables NOT present here (per §1's "things QuickInk
-- does NOT need" list):
--   notebooks, chapters, pages, shelves, book_series, tasks,
--   task_subtasks, reminders, call_history, page_templates,
--   page_tags, todo_lists, todo_items, reference_links,
--   capture_revisions, contacts, locations, tags*, projects*
--
--   *projects + tags are mentioned in §3 but their concrete shape
--    is TBD; deferred to a future v2 migration when their use
--    cases are concrete.
-- =============================================================


-- ─── Tables ────────────────────────────────────────────────────

-- Rich-text notes, FTS5-indexed body. Identical column list to
-- Releaf's notepad_entries — keeps the CI shared-tables diff clean
-- and lets :shared:notes' NotepadEntry @Entity / NotepadDao work
-- unchanged across both apps.
--
-- QuickInk doesn't populate the side-channel JSON arrays
-- (contacts/locations/todos/attachments) — they exist on the row
-- because :shared:notes' NotepadEntry expects them, but stay at
-- their default '[]' until a future feature adds inline contacts /
-- todos / etc. to QuickInk's editor.
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
);

-- Per-Drive-file sync metadata. Mirrors :shared:sync's
-- SyncStateEntity. One row per remote file the sync worker tracks
-- (manifest.json + per-row JSON blobs).
CREATE TABLE sync_state (
    drive_file_id           TEXT PRIMARY KEY NOT NULL,
    local_etag              TEXT,
    remote_etag             TEXT,
    last_pulled_at          TEXT,
    last_pushed_at          TEXT,
    conflict_stub           TEXT
);

-- One row per scan session (a multi-page capture from
-- DocumentScannerLauncher / DocumentScannerView). The PDF + first-
-- page preview JPEG are file:// URIs into AttachmentStorage; the
-- page-level images sit alongside but aren't stored on this row —
-- they live as ocr_results' source images, addressed via
-- {capture_id, page_index}.
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
);

-- OCR results, one row per scanned page. Per QUICKINK_PROPOSAL.md
-- §6.2. `text` is the flat recognized text (mirrored into
-- notepad_entries.notes by the ingest path so FTS5 indexes scanned
-- words); `blocks_json` is the canonical positional record
-- (encoded `OcrResult.blocks` from :shared:scan / ReleafCoreScan).
-- Engine traceability fields let a future re-OCR pass tell which
-- rows came from which engine version.
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
);


-- ─── Indexes ───────────────────────────────────────────────────

-- notepad_entries
CREATE INDEX idx_notepad_entries_user_date    ON notepad_entries (user_id, entry_date);
CREATE INDEX idx_notepad_entries_dirty        ON notepad_entries (dirty) WHERE dirty = 1;
CREATE INDEX idx_notepad_entries_tombstone    ON notepad_entries (deleted_at) WHERE deleted_at IS NOT NULL;

-- captures
CREATE INDEX idx_captures_user_created        ON captures (user_id, created_at);
CREATE INDEX idx_captures_dirty               ON captures (dirty) WHERE dirty = 1;
CREATE INDEX idx_captures_tombstone           ON captures (deleted_at) WHERE deleted_at IS NOT NULL;

-- ocr_results
CREATE INDEX idx_ocr_results_capture          ON ocr_results (capture_id);
CREATE INDEX idx_ocr_results_dirty            ON ocr_results (dirty) WHERE dirty = 1;
CREATE INDEX idx_ocr_results_tombstone        ON ocr_results (deleted_at) WHERE deleted_at IS NOT NULL;


-- ─── FTS5 virtual tables + sync triggers ───────────────────────

-- notepad notes — full-text search over notepad_entries.notes.
-- Lifted verbatim from Releaf's v1; the table shape + trigger
-- contract is part of the cross-app shared convention.
CREATE VIRTUAL TABLE fts_notepad_notes USING fts5(
    notepad_entry_id UNINDEXED,
    notes,
    tokenize = 'unicode61 remove_diacritics 2'
);

CREATE TRIGGER notepad_entries_fts_ai AFTER INSERT ON notepad_entries
WHEN new.deleted_at IS NULL AND new.notes <> ''
BEGIN
    INSERT INTO fts_notepad_notes(notepad_entry_id, notes)
    VALUES (new.id, new.notes);
END;

CREATE TRIGGER notepad_entries_fts_au AFTER UPDATE ON notepad_entries
BEGIN
    DELETE FROM fts_notepad_notes WHERE notepad_entry_id = old.id;
    INSERT INTO fts_notepad_notes(notepad_entry_id, notes)
    SELECT new.id, new.notes
    WHERE new.deleted_at IS NULL AND new.notes <> '';
END;

CREATE TRIGGER notepad_entries_fts_ad AFTER DELETE ON notepad_entries
BEGIN
    DELETE FROM fts_notepad_notes WHERE notepad_entry_id = old.id;
END;


-- OCR text — separate FTS5 index over ocr_results.text so
-- QuickInk's notes search surface (and the future searchable-PDF
-- prototype) can reach scanned-doc words without parsing
-- blocks_json. Same trigger pattern; `ocr_result_id UNINDEXED` is
-- the row-id back-reference.
CREATE VIRTUAL TABLE fts_ocr_text USING fts5(
    ocr_result_id UNINDEXED,
    text,
    tokenize = 'unicode61 remove_diacritics 2'
);

CREATE TRIGGER ocr_results_fts_ai AFTER INSERT ON ocr_results
WHEN new.deleted_at IS NULL AND new.text <> ''
BEGIN
    INSERT INTO fts_ocr_text(ocr_result_id, text)
    VALUES (new.id, new.text);
END;

CREATE TRIGGER ocr_results_fts_au AFTER UPDATE ON ocr_results
BEGIN
    DELETE FROM fts_ocr_text WHERE ocr_result_id = old.id;
    INSERT INTO fts_ocr_text(ocr_result_id, text)
    SELECT new.id, new.text
    WHERE new.deleted_at IS NULL AND new.text <> '';
END;

CREATE TRIGGER ocr_results_fts_ad AFTER DELETE ON ocr_results
BEGIN
    DELETE FROM fts_ocr_text WHERE ocr_result_id = old.id;
END;
