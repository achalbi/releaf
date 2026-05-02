-- =============================================================
-- QuickInk — v2_capture_categories.sql
--
-- Adds:
--   1. `captures.category` — TEXT, nullable. Pre-tagged at capture
--      time on the scan review screen. Lives on `captures` (per-
--      capture) rather than `notepad_entries` (per-day) because the
--      user picks the category for *that scan*, not for the day.
--      The day's notepad_entries.category becomes "the most recent
--      scan's category" — derived, not authoritative.
--
--   2. `categories` table — user-configurable list of tags shown in
--      the review screen's picker and managed in Settings →
--      Categories. Synced to Drive like any other QuickInk entity
--      (dirty bit + drive_file_id + deleted_at trio). Seeded with
--      "Ideas / Projects / Meetings / Todo / Study / Journal" on
--      first launch (seeding lives in app code, not this migration,
--      since seeds need a userId which is a runtime value).
--
-- Both apps' migrators (iOS GRDB DatabaseMigrator + Android Room
-- @AutoMigration / Migration) run this file's intent in their
-- respective DSLs. The SQL here is the canonical record per the
-- pattern set by v1_initial.sql.
-- =============================================================


-- ─── captures.category ───────────────────────────────────────

ALTER TABLE captures ADD COLUMN category TEXT;


-- ─── categories table ────────────────────────────────────────

-- One row per user-defined category. Sort order is determined by
-- `position` (caller-managed; lower values render first). Soft-
-- deletes via `deleted_at` so a removed category that was already
-- assigned to historical captures keeps the captures.category
-- string-match working — captures.category is a TEXT *value*, not
-- an FK, intentionally.
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

    -- Each user has a single namespace for category names. The
    -- partial uniqueness via index below excludes tombstones so a
    -- "delete then re-add same name" cycle is allowed.
    UNIQUE (user_id, name)
);


-- ─── Indexes ─────────────────────────────────────────────────

CREATE INDEX idx_categories_user_position ON categories (user_id, position);
CREATE INDEX idx_categories_dirty         ON categories (dirty)      WHERE dirty = 1;
CREATE INDEX idx_categories_tombstone     ON categories (deleted_at) WHERE deleted_at IS NOT NULL;
