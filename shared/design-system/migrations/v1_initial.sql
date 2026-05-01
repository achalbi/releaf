-- ============================================================================
-- Releaf v1_initial — seed schema
-- ============================================================================
-- Migration number : 1
-- Slug             : v1_initial
-- Platforms        : iOS (GRDB) + Android (Room via RoomDatabase.Callback)
-- Source of truth  : this file. Platform adapters load and execute it verbatim.
--
-- Conventions encoded here (see docs/OPEN_QUESTIONS.md for rationale):
--   - IDs            : TEXT (UUIDv7, 36-char canonical hyphenated form).
--   - Timestamps     : TEXT, ISO-8601 UTC with ms (YYYY-MM-DDTHH:MM:SS.sssZ).
--                      Application layer writes them; DEFAULT is a strftime
--                      fallback that produces a compliant form.
--   - Dates          : TEXT, ISO-8601 (YYYY-MM-DD), guarded by GLOB CHECK.
--   - Booleans       : INTEGER 0/1, guarded by CHECK (x IN (0,1)).
--   - JSON           : stored as TEXT; validated at app layer; parsed with
--                      json1 (always compiled into SQLite on iOS/Android).
--   - Soft delete    : deleted_at NULL by default; sync worker propagates
--                      tombstones. FTS triggers scope to deleted_at IS NULL.
--   - Dirty bit      : dirty INTEGER NOT NULL DEFAULT 1 on every mutable row;
--                      cleared by sync worker after successful upload.
--   - FTS5 tokenizer : unicode61 remove_diacritics 2.
--   - Exactly-one-parent : polymorphic-parent tables (captures, todo_lists,
--                      reference_links) CHECK that exactly one parent FK is
--                      non-null. Not deferred; merge re-parents write the
--                      full triple in one UPDATE (see OPEN_QUESTIONS.md #1).
--   - user_id        : TEXT scoping column on daily_logs, notepad_entries,
--                      user_settings. Other tables are implicitly scoped to
--                      the active signed-in user; v2 may widen this.
--
-- Foreign keys are enforced. The adapter layer MUST run `PRAGMA foreign_keys
-- = ON` on every connection. SQLite defaults it to OFF.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. Cross-cutting labels
-- ----------------------------------------------------------------------------

CREATE TABLE projects (
    id              TEXT PRIMARY KEY NOT NULL,
    name            TEXT NOT NULL,
    color_hex       TEXT,                                                     -- e.g. '#E77850'
    position        INTEGER NOT NULL DEFAULT 1024,
    drive_file_id   TEXT,
    created_at      TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    updated_at      TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    dirty           INTEGER NOT NULL DEFAULT 1 CHECK (dirty IN (0, 1)),
    deleted_at      TEXT
);

CREATE TABLE tags (
    id              TEXT PRIMARY KEY NOT NULL,
    name            TEXT NOT NULL,
    color_hex       TEXT,
    drive_file_id   TEXT,
    created_at      TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    updated_at      TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    dirty           INTEGER NOT NULL DEFAULT 1 CHECK (dirty IN (0, 1)),
    deleted_at      TEXT,
    UNIQUE (name)
);

-- ----------------------------------------------------------------------------
-- 2. Notebook / Chapter / Page hierarchy
-- ----------------------------------------------------------------------------

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
);

CREATE TABLE chapters (
    id              TEXT PRIMARY KEY NOT NULL,
    notebook_id     TEXT NOT NULL REFERENCES notebooks(id) ON DELETE RESTRICT,
    title           TEXT NOT NULL,
    position        INTEGER NOT NULL DEFAULT 1024,
    drive_file_id   TEXT,
    created_at      TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    updated_at      TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    dirty           INTEGER NOT NULL DEFAULT 1 CHECK (dirty IN (0, 1)),
    deleted_at      TEXT
);

CREATE TABLE page_templates (
    id                  TEXT PRIMARY KEY NOT NULL,
    name                TEXT NOT NULL,
    default_notes       TEXT NOT NULL DEFAULT '',                             -- canonical Markdown
    default_todo_list   TEXT,                                                 -- JSON: {enabled, hide_completed, items:[...]}
    drive_file_id       TEXT,
    created_at          TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    updated_at          TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    dirty               INTEGER NOT NULL DEFAULT 1 CHECK (dirty IN (0, 1)),
    deleted_at          TEXT
);

CREATE TABLE pages (
    id              TEXT PRIMARY KEY NOT NULL,
    chapter_id      TEXT NOT NULL REFERENCES chapters(id) ON DELETE RESTRICT,
    project_id      TEXT REFERENCES projects(id) ON DELETE SET NULL,
    template_id     TEXT REFERENCES page_templates(id) ON DELETE SET NULL,
    title           TEXT,                                                     -- nullable; UI falls back to a default
    notes           TEXT NOT NULL DEFAULT '',                                 -- canonical CommonMark
    contacts        TEXT NOT NULL DEFAULT '[]',                               -- JSON array of Contact
    locations       TEXT NOT NULL DEFAULT '[]',                               -- JSON array of Location
    position        INTEGER NOT NULL DEFAULT 1024,
    conflict_stub   TEXT,                                                     -- JSON: {local_notes, remote_notes, remote_updated_at} | NULL
    drive_file_id   TEXT,
    created_at      TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    updated_at      TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    dirty           INTEGER NOT NULL DEFAULT 1 CHECK (dirty IN (0, 1)),
    deleted_at      TEXT
);

-- ----------------------------------------------------------------------------
-- 3. Daily surface (DailyLog + NotepadEntry, joined by date)
-- ----------------------------------------------------------------------------

CREATE TABLE daily_logs (
    id              TEXT PRIMARY KEY NOT NULL,
    user_id         TEXT NOT NULL,
    entry_date      TEXT NOT NULL CHECK (
                        length(entry_date) = 10 AND
                        entry_date GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]'
                    ),
    drive_file_id   TEXT,
    created_at      TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    updated_at      TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    dirty           INTEGER NOT NULL DEFAULT 1 CHECK (dirty IN (0, 1)),
    deleted_at      TEXT,
    UNIQUE (user_id, entry_date)
);

CREATE TABLE notepad_entries (
    id                      TEXT PRIMARY KEY NOT NULL,
    user_id                 TEXT NOT NULL,                                    -- join target for daily_logs
    entry_date              TEXT NOT NULL CHECK (
                                length(entry_date) = 10 AND
                                entry_date GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]'
                            ),
    project_id              TEXT REFERENCES projects(id) ON DELETE SET NULL,
    title                   TEXT,
    notes                   TEXT NOT NULL DEFAULT '',                         -- canonical CommonMark
    contacts                TEXT NOT NULL DEFAULT '[]',                       -- JSON array
    locations               TEXT NOT NULL DEFAULT '[]',                       -- JSON array
    allow_blank_content     INTEGER NOT NULL DEFAULT 0 CHECK (allow_blank_content IN (0, 1)),
    conflict_stub           TEXT,                                             -- JSON | NULL
    drive_file_id           TEXT,
    created_at              TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    updated_at              TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    dirty                   INTEGER NOT NULL DEFAULT 1 CHECK (dirty IN (0, 1)),
    deleted_at              TEXT
);

-- ----------------------------------------------------------------------------
-- 4. Captures (unified: photo | voice | scan)
-- ----------------------------------------------------------------------------

CREATE TABLE captures (
    id                          TEXT PRIMARY KEY NOT NULL,
    kind                        TEXT NOT NULL CHECK (kind IN ('photo', 'voice', 'scan')),

    -- Exactly-one-parent: CHECK below enforces this invariant.
    parent_page_id              TEXT REFERENCES pages(id) ON DELETE RESTRICT,
    parent_notepad_entry_id     TEXT REFERENCES notepad_entries(id) ON DELETE RESTRICT,
    parent_daily_log_id         TEXT REFERENCES daily_logs(id) ON DELETE SET NULL,

    project_id                  TEXT REFERENCES projects(id) ON DELETE SET NULL,
    position                    INTEGER NOT NULL DEFAULT 1024,

    -- Media / sync
    media_path                  TEXT,                                         -- relative path under releaf/media/<kind>/
    drive_file_id               TEXT,
    byte_size                   INTEGER,
    mime_type                   TEXT,

    -- User-authored / derived text
    caption                     TEXT,                                         -- photos primarily
    extracted_text              TEXT,                                         -- OCR (scans) / transcript (voice); empty for photos
    ai_summary                  TEXT,                                         -- v2; schema ready in v1
    ai_summary_generated_at     TEXT,

    -- Photo-only
    photo_width                 INTEGER,
    photo_height                INTEGER,

    -- Voice-only
    voice_duration_seconds      INTEGER,
    voice_recorded_at           TEXT,
    voice_language              TEXT,                                         -- e.g. 'en-US'; per-recording override

    -- Scan-only
    scan_title                  TEXT,
    scan_confidence             REAL,
    scan_pdf_path               TEXT,
    scan_pdf_drive_file_id      TEXT,

    created_at                  TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    updated_at                  TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    dirty                       INTEGER NOT NULL DEFAULT 1 CHECK (dirty IN (0, 1)),
    deleted_at                  TEXT,

    CHECK (
        (CASE WHEN parent_page_id          IS NOT NULL THEN 1 ELSE 0 END) +
        (CASE WHEN parent_notepad_entry_id IS NOT NULL THEN 1 ELSE 0 END) +
        (CASE WHEN parent_daily_log_id     IS NOT NULL THEN 1 ELSE 0 END) = 1
    )
);

CREATE TABLE capture_revisions (
    id              TEXT PRIMARY KEY NOT NULL,
    capture_id      TEXT NOT NULL REFERENCES captures(id) ON DELETE CASCADE,
    field           TEXT NOT NULL CHECK (field IN ('extracted_text')),        -- v2 may add 'caption'
    content         TEXT,
    created_at      TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE TABLE capture_tags (
    capture_id      TEXT NOT NULL REFERENCES captures(id) ON DELETE CASCADE,
    tag_id          TEXT NOT NULL REFERENCES tags(id)     ON DELETE CASCADE,
    created_at      TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    PRIMARY KEY (capture_id, tag_id)
);

CREATE TABLE page_tags (
    page_id         TEXT NOT NULL REFERENCES pages(id) ON DELETE CASCADE,
    tag_id          TEXT NOT NULL REFERENCES tags(id)  ON DELETE CASCADE,
    created_at      TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    PRIMARY KEY (page_id, tag_id)
);

-- ----------------------------------------------------------------------------
-- 5. Tasks + subtasks + reminders
-- ----------------------------------------------------------------------------

CREATE TABLE tasks (
    id              TEXT PRIMARY KEY NOT NULL,
    title           TEXT NOT NULL,
    description     TEXT,
    due_date        TEXT CHECK (due_date IS NULL OR (
                        length(due_date) = 10 AND
                        due_date GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]'
                    )),
    completed       INTEGER NOT NULL DEFAULT 0 CHECK (completed IN (0, 1)),
    completed_at    TEXT,
    project_id      TEXT REFERENCES projects(id) ON DELETE SET NULL,
    position        INTEGER NOT NULL DEFAULT 1024,
    drive_file_id   TEXT,
    created_at      TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    updated_at      TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    dirty           INTEGER NOT NULL DEFAULT 1 CHECK (dirty IN (0, 1)),
    deleted_at      TEXT
);

CREATE TABLE task_subtasks (
    id              TEXT PRIMARY KEY NOT NULL,
    task_id         TEXT NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    parent_subtask_id TEXT REFERENCES task_subtasks(id) ON DELETE CASCADE,   -- hierarchical
    title           TEXT NOT NULL,
    position        INTEGER NOT NULL DEFAULT 1024,
    completed       INTEGER NOT NULL DEFAULT 0 CHECK (completed IN (0, 1)),
    completed_at    TEXT,
    created_at      TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    updated_at      TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    dirty           INTEGER NOT NULL DEFAULT 1 CHECK (dirty IN (0, 1)),
    deleted_at      TEXT
);

CREATE TABLE reminders (
    id                  TEXT PRIMARY KEY NOT NULL,
    task_id             TEXT NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    fire_at             TEXT NOT NULL,                                       -- ISO-8601 UTC
    snoozed_until       TEXT,                                                -- ISO-8601 UTC; supersedes fire_at when set
    delivered           INTEGER NOT NULL DEFAULT 0 CHECK (delivered IN (0, 1)),
    delivered_at        TEXT,
    os_notification_id  TEXT,                                                -- platform-specific handle (UNUserNotificationRequest id / Android notif id)
    created_at          TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    updated_at          TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    dirty               INTEGER NOT NULL DEFAULT 1 CHECK (dirty IN (0, 1)),
    deleted_at          TEXT
);

-- ----------------------------------------------------------------------------
-- 6. Inline todo lists (polymorphic parent)
-- ----------------------------------------------------------------------------

CREATE TABLE todo_lists (
    id                          TEXT PRIMARY KEY NOT NULL,
    parent_page_id              TEXT REFERENCES pages(id) ON DELETE CASCADE,
    parent_notepad_entry_id     TEXT REFERENCES notepad_entries(id) ON DELETE CASCADE,
    enabled                     INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1)),
    hide_completed              INTEGER NOT NULL DEFAULT 0 CHECK (hide_completed IN (0, 1)),
    created_at                  TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    updated_at                  TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    dirty                       INTEGER NOT NULL DEFAULT 1 CHECK (dirty IN (0, 1)),
    deleted_at                  TEXT,

    CHECK (
        (CASE WHEN parent_page_id          IS NOT NULL THEN 1 ELSE 0 END) +
        (CASE WHEN parent_notepad_entry_id IS NOT NULL THEN 1 ELSE 0 END) = 1
    ),
    UNIQUE (parent_page_id),            -- at most one list per page
    UNIQUE (parent_notepad_entry_id)    -- at most one list per entry
);

CREATE TABLE todo_items (
    id              TEXT PRIMARY KEY NOT NULL,
    todo_list_id    TEXT NOT NULL REFERENCES todo_lists(id) ON DELETE CASCADE,
    content         TEXT NOT NULL,
    completed       INTEGER NOT NULL DEFAULT 0 CHECK (completed IN (0, 1)),
    completed_at    TEXT,
    position        INTEGER NOT NULL DEFAULT 1024,
    created_at      TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    updated_at      TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    dirty           INTEGER NOT NULL DEFAULT 1 CHECK (dirty IN (0, 1)),
    deleted_at      TEXT
);

-- ----------------------------------------------------------------------------
-- 7. Reference links (polymorphic parent)
-- ----------------------------------------------------------------------------

CREATE TABLE reference_links (
    id                          TEXT PRIMARY KEY NOT NULL,
    parent_page_id              TEXT REFERENCES pages(id) ON DELETE CASCADE,
    parent_notepad_entry_id     TEXT REFERENCES notepad_entries(id) ON DELETE CASCADE,
    url                         TEXT NOT NULL,
    title                       TEXT,
    description                 TEXT,
    preview_image_path          TEXT,                                        -- cached locally; not synced to Drive (regenerable)
    position                    INTEGER NOT NULL DEFAULT 1024,
    created_at                  TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    updated_at                  TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    dirty                       INTEGER NOT NULL DEFAULT 1 CHECK (dirty IN (0, 1)),
    deleted_at                  TEXT,

    CHECK (
        (CASE WHEN parent_page_id          IS NOT NULL THEN 1 ELSE 0 END) +
        (CASE WHEN parent_notepad_entry_id IS NOT NULL THEN 1 ELSE 0 END) = 1
    )
);

-- ----------------------------------------------------------------------------
-- 8. Sync + settings (local-only, not synced to Drive)
-- ----------------------------------------------------------------------------

-- Key-value. Current known keys:
--   'last_full_sync_at'          ISO-8601 UTC
--   'last_incremental_sync_at'   ISO-8601 UTC
--   'manifest_checksum'          hex SHA-256
--   'pending_count'              integer (quick badge for UI)
--   'drive_quota_exhausted_at'   ISO-8601 UTC | absent when healthy
CREATE TABLE sync_state (
    key         TEXT PRIMARY KEY NOT NULL,
    value       TEXT,
    updated_at  TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

-- Per-user key-value. Current known keys (settings sub-screens):
--   'capture.compression_tier'     'low' | 'standard' | 'high'
--   'capture.include_location'     '0' | '1'   (default '1' per OPEN_QUESTIONS #8)
--   'launcher.continue_scope'      'append_to_latest' | 'today' | 'new_shell'
--   'ai.provider'                  'off' | 'on_device' | 'gemini' | 'claude'
--   'notifications.reminders'      '0' | '1'
--   'notifications.daily_log'      '0' | '1'
--   'exports.sync_pdfs'            '0' | '1'   (default '0' per OPEN_QUESTIONS #9)
CREATE TABLE user_settings (
    user_id     TEXT NOT NULL,
    key         TEXT NOT NULL,
    value       TEXT,
    updated_at  TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    PRIMARY KEY (user_id, key)
);

-- ============================================================================
-- FTS5 virtual tables (not external-content; maintained by explicit triggers)
-- ============================================================================

CREATE VIRTUAL TABLE fts_notepad_notes USING fts5(
    notepad_entry_id UNINDEXED,
    notes,
    tokenize = 'unicode61 remove_diacritics 2'
);

CREATE VIRTUAL TABLE fts_capture_text USING fts5(
    capture_id UNINDEXED,
    kind UNINDEXED,
    extracted_text,
    tokenize = 'unicode61 remove_diacritics 2'
);

CREATE VIRTUAL TABLE fts_page_notes USING fts5(
    page_id UNINDEXED,
    notes,
    tokenize = 'unicode61 remove_diacritics 2'
);

CREATE VIRTUAL TABLE fts_task_title USING fts5(
    task_id UNINDEXED,
    title,
    tokenize = 'unicode61 remove_diacritics 2'
);

-- ============================================================================
-- FTS5 sync triggers
-- ============================================================================
-- Pattern (applied to notepad_entries, captures, pages, tasks):
--   INSERT: add FTS row iff deleted_at IS NULL and searchable field present.
--   UPDATE: delete any existing FTS row, then re-insert iff still eligible.
--   DELETE: delete FTS row.
-- Soft delete (deleted_at = <ts> via UPDATE) naturally removes from FTS via
-- the UPDATE path because the re-insert is guarded.
-- ============================================================================

-- ---------- notepad_entries ----------

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

-- ---------- pages ----------

CREATE TRIGGER pages_fts_ai AFTER INSERT ON pages
WHEN new.deleted_at IS NULL AND new.notes <> ''
BEGIN
    INSERT INTO fts_page_notes(page_id, notes)
    VALUES (new.id, new.notes);
END;

CREATE TRIGGER pages_fts_au AFTER UPDATE ON pages
BEGIN
    DELETE FROM fts_page_notes WHERE page_id = old.id;
    INSERT INTO fts_page_notes(page_id, notes)
    SELECT new.id, new.notes
    WHERE new.deleted_at IS NULL AND new.notes <> '';
END;

CREATE TRIGGER pages_fts_ad AFTER DELETE ON pages
BEGIN
    DELETE FROM fts_page_notes WHERE page_id = old.id;
END;

-- ---------- captures (extracted_text only; empty for photos) ----------

CREATE TRIGGER captures_fts_ai AFTER INSERT ON captures
WHEN new.deleted_at IS NULL AND new.extracted_text IS NOT NULL AND new.extracted_text <> ''
BEGIN
    INSERT INTO fts_capture_text(capture_id, kind, extracted_text)
    VALUES (new.id, new.kind, new.extracted_text);
END;

CREATE TRIGGER captures_fts_au AFTER UPDATE ON captures
BEGIN
    DELETE FROM fts_capture_text WHERE capture_id = old.id;
    INSERT INTO fts_capture_text(capture_id, kind, extracted_text)
    SELECT new.id, new.kind, new.extracted_text
    WHERE new.deleted_at IS NULL
      AND new.extracted_text IS NOT NULL
      AND new.extracted_text <> '';
END;

CREATE TRIGGER captures_fts_ad AFTER DELETE ON captures
BEGIN
    DELETE FROM fts_capture_text WHERE capture_id = old.id;
END;

-- ---------- tasks (title only) ----------

CREATE TRIGGER tasks_fts_ai AFTER INSERT ON tasks
WHEN new.deleted_at IS NULL AND new.title <> ''
BEGIN
    INSERT INTO fts_task_title(task_id, title)
    VALUES (new.id, new.title);
END;

CREATE TRIGGER tasks_fts_au AFTER UPDATE ON tasks
BEGIN
    DELETE FROM fts_task_title WHERE task_id = old.id;
    INSERT INTO fts_task_title(task_id, title)
    SELECT new.id, new.title
    WHERE new.deleted_at IS NULL AND new.title <> '';
END;

CREATE TRIGGER tasks_fts_ad AFTER DELETE ON tasks
BEGIN
    DELETE FROM fts_task_title WHERE task_id = old.id;
END;

-- ============================================================================
-- Indexes
-- ============================================================================
-- Hot query paths + the sync-worker's "find dirty" and "find tombstone" passes.
-- Positional indexes are composite: (parent, kind, position) so ORDER BY position
-- on a single-parent query is a covered, non-sorting scan.

-- captures: core lookups + sync scans
CREATE INDEX idx_captures_page_kind_position      ON captures (parent_page_id, kind, position)      WHERE parent_page_id IS NOT NULL;
CREATE INDEX idx_captures_entry_kind_position     ON captures (parent_notepad_entry_id, kind, position) WHERE parent_notepad_entry_id IS NOT NULL;
CREATE INDEX idx_captures_daily_kind              ON captures (parent_daily_log_id, kind)           WHERE parent_daily_log_id IS NOT NULL;
CREATE INDEX idx_captures_project                 ON captures (project_id)                          WHERE project_id IS NOT NULL;
CREATE INDEX idx_captures_dirty                   ON captures (dirty)                               WHERE dirty = 1;
CREATE INDEX idx_captures_tombstone               ON captures (deleted_at)                          WHERE deleted_at IS NOT NULL;

-- capture_revisions: "give me the revision history for capture X, newest first"
CREATE INDEX idx_capture_revisions_capture_created ON capture_revisions (capture_id, created_at DESC);

-- notebooks / chapters / pages: tree walks + positional sort
CREATE INDEX idx_chapters_notebook_position       ON chapters (notebook_id, position);
CREATE INDEX idx_pages_chapter_position           ON pages    (chapter_id, position);
CREATE INDEX idx_pages_project                    ON pages    (project_id) WHERE project_id IS NOT NULL;
CREATE INDEX idx_pages_dirty                      ON pages    (dirty)      WHERE dirty = 1;
CREATE INDEX idx_pages_tombstone                  ON pages    (deleted_at) WHERE deleted_at IS NOT NULL;

-- daily_logs / notepad_entries: by-date join (the linchpin)
CREATE INDEX idx_daily_logs_user_date             ON daily_logs       (user_id, entry_date);
CREATE INDEX idx_notepad_entries_user_date        ON notepad_entries  (user_id, entry_date);
CREATE INDEX idx_notepad_entries_project          ON notepad_entries  (project_id) WHERE project_id IS NOT NULL;
CREATE INDEX idx_notepad_entries_dirty            ON notepad_entries  (dirty)      WHERE dirty = 1;
CREATE INDEX idx_notepad_entries_tombstone        ON notepad_entries  (deleted_at) WHERE deleted_at IS NOT NULL;

-- tasks: due-date sort + completion filter + sync
CREATE INDEX idx_tasks_due_incomplete             ON tasks (due_date, position) WHERE completed = 0 AND deleted_at IS NULL;
CREATE INDEX idx_tasks_project                    ON tasks (project_id)         WHERE project_id IS NOT NULL;
CREATE INDEX idx_tasks_dirty                      ON tasks (dirty)              WHERE dirty = 1;

-- task_subtasks: parent-task lookups
CREATE INDEX idx_task_subtasks_task_position      ON task_subtasks (task_id, position);

-- reminders: scheduling scan
CREATE INDEX idx_reminders_task                   ON reminders (task_id);
CREATE INDEX idx_reminders_pending                ON reminders (fire_at)        WHERE delivered = 0 AND deleted_at IS NULL;

-- todo_lists + todo_items
CREATE INDEX idx_todo_items_list_position         ON todo_items (todo_list_id, position);

-- reference_links
CREATE INDEX idx_reference_links_page             ON reference_links (parent_page_id, position)          WHERE parent_page_id IS NOT NULL;
CREATE INDEX idx_reference_links_entry            ON reference_links (parent_notepad_entry_id, position) WHERE parent_notepad_entry_id IS NOT NULL;

-- Sync scans across sibling tables
CREATE INDEX idx_notebooks_dirty                  ON notebooks (dirty) WHERE dirty = 1;
CREATE INDEX idx_chapters_dirty                   ON chapters  (dirty) WHERE dirty = 1;
CREATE INDEX idx_projects_dirty                   ON projects  (dirty) WHERE dirty = 1;
CREATE INDEX idx_tags_dirty                       ON tags      (dirty) WHERE dirty = 1;
CREATE INDEX idx_page_templates_dirty             ON page_templates (dirty) WHERE dirty = 1;
CREATE INDEX idx_daily_logs_dirty                 ON daily_logs (dirty) WHERE dirty = 1;
CREATE INDEX idx_reminders_dirty                  ON reminders  (dirty) WHERE dirty = 1;
CREATE INDEX idx_todo_lists_dirty                 ON todo_lists (dirty) WHERE dirty = 1;
CREATE INDEX idx_todo_items_dirty                 ON todo_items (dirty) WHERE dirty = 1;
CREATE INDEX idx_reference_links_dirty            ON reference_links (dirty) WHERE dirty = 1;
CREATE INDEX idx_task_subtasks_dirty              ON task_subtasks (dirty) WHERE dirty = 1;

-- ============================================================================
-- End of v1_initial
-- ============================================================================
