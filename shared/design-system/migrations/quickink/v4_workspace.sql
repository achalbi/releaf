-- =============================================================
-- QuickInk — v4_workspace.sql
--
-- Lands the Workspace data model — the two-axis IA from the
-- WORKSPACE_SPEC.md / Workspace v1 design brief:
--
--   folders   = intent  (one per capture, color-coded, future
--                        unit of sharing)
--   tags      = content (many per capture, free-form, AI-augmentable)
--
-- This migration:
--
--   1. Renames the existing `categories` table → `tags`. The table
--      always held flat, user-named, free-form labels with one tag
--      per capture; the design just promotes them to a many-to-many
--      relationship. The rename is paid in full so the data model
--      reads cleanly. Indexes rename to match. UI files / DAOs /
--      sync payloads / Drive payload prefix all rename in lockstep
--      (see brief §2 for full cost breakdown).
--
--   2. Adds `tags.color` — nullable hex text (e.g. `#E66943`), used
--      for the chip color on the tag picker bottom sheet (Workspace
--      Screen 6).
--
--   3. Creates `folders` — one row per user-defined folder. Captures
--      reference it via `captures.folder_id`. `is_default = 1` marks
--      the seeded "Unfiled" folder (exactly one per user). `is_shared`
--      is a reserved column for the post-v1 share flow.
--
--   4. Creates `capture_tags` — many-to-many join. Each row syncs
--      independently (own id + dirty + tombstone), so a tag added on
--      phone A reaches phone B without re-syncing the whole capture.
--      `source` distinguishes manual vs AI-suggested vs migration-
--      seeded rows for analytics + future trust signals.
--
--   5. Creates `smart_collections` — rule-based saved views (e.g.
--      "Invoices this month"). The rule lives in `rule_json` as an
--      AND-of-clauses array (see brief §3 for grammar). Stats are
--      recomputed on read; no denormalization.
--
--   6. Adds four columns to `captures`:
--        folder_id          NULL initially; backfilled into "Unfiled"
--        last_opened_at     ISO timestamp, written by the PDF reader
--        last_opened_page   1-indexed page number
--        last_opened_device install id, for future cross-device
--                           "continue on iPhone" UX
--
--   7. Drops `captures.category` (the legacy single-tag TEXT field).
--      Every non-null value is materialized into a `capture_tags`
--      row *before* the column drop, in the same script. Done in v1
--      per the brief — no fallback release. SQLite needs a table
--      rebuild for column drop (CREATE-INSERT-DROP-RENAME pattern).
--
-- Notes for migrators:
--   - Android: Room rebuilds on @Database version bump
--     (`fallbackToDestructiveMigration` is still on). Replace with
--     real `Migration` blocks before any real user data exists. This
--     .sql is the canonical record.
--   - iOS: GRDB DatabaseMigrator runs each step in its own DSL block.
--     Mirror this file when the iOS pass lands (Phase A.iOS).
--   - The Drive payload prefix moves from `categories/` to `tags/` —
--     handled in sync code (DrivePath.kt + SyncRepository), not here.
-- =============================================================


-- ─── 1. Rename `categories` → `tags` ─────────────────────────

-- The table is renamed, not recreated. Existing rows survive the
-- migration verbatim (id, user_id, name, position, drive_file_id,
-- timestamps, dirty, deleted_at). UI / DAO / payload-class renames
-- happen in app code, not here.
ALTER TABLE categories RENAME TO tags;

-- SQLite indexes follow the table rename in modern releases (3.25+),
-- but the index *names* don't auto-rename. Drop + recreate for
-- consistency with the new table name.
DROP INDEX IF EXISTS idx_categories_user_position;
DROP INDEX IF EXISTS idx_categories_dirty;
DROP INDEX IF EXISTS idx_categories_tombstone;

CREATE INDEX idx_tags_user_position ON tags (user_id, position);
CREATE INDEX idx_tags_dirty         ON tags (dirty)      WHERE dirty = 1;
CREATE INDEX idx_tags_tombstone     ON tags (deleted_at) WHERE deleted_at IS NOT NULL;


-- ─── 2. Add `tags.color` ─────────────────────────────────────

-- Nullable. The tag-picker chip falls back to the accent tint when
-- color is NULL; users assign explicit colors only when they want a
-- chip to stand out. Stored as text (hex with leading `#`, e.g.
-- "#E66943") so the palette can grow without a schema migration.
ALTER TABLE tags ADD COLUMN color TEXT;


-- ─── 3. `folders` table ──────────────────────────────────────

CREATE TABLE folders (
    id              TEXT PRIMARY KEY NOT NULL,
    user_id         TEXT NOT NULL,
    name            TEXT NOT NULL,

    -- Hex color (`#E66943`). NOT NULL — every folder carries a
    -- visible identity in the Workspace home folder list. Seeded
    -- "Unfiled" uses a neutral stone color; user-created folders
    -- pick from the design's palette (coral, gold, green, blue,
    -- purple, pink, teal).
    color           TEXT NOT NULL,

    -- Caller-managed sort order; lower values render first. Mirrors
    -- categories.position. Seeded "Unfiled" gets position = 0 (top).
    position        INTEGER NOT NULL DEFAULT 0,

    -- Reserved for the design's "covers" folder-visual mode
    -- (Milanote-style cover image). Out of scope for v1 ship; the
    -- column exists so adding the visual later doesn't churn the
    -- schema again.
    cover_uri       TEXT,

    -- Exactly one row per user has `is_default = 1`. That's the
    -- seeded "Unfiled" folder. Used to guard the UI from deleting
    -- it and to backfill orphan captures when a user-folder is
    -- deleted (move-to-Unfiled, not cascade — per brief §10 #2).
    is_default      INTEGER NOT NULL DEFAULT 0 CHECK (is_default IN (0, 1)),

    -- Reserved column for the post-v1 share flow. Defaults to 0.
    -- Costs nothing to add now; saves a migration when sharing
    -- ships.
    is_shared       INTEGER NOT NULL DEFAULT 0 CHECK (is_shared IN (0, 1)),

    drive_file_id   TEXT,
    created_at      TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    updated_at      TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    dirty           INTEGER NOT NULL DEFAULT 1 CHECK (dirty IN (0, 1)),
    deleted_at      TEXT
);

CREATE INDEX idx_folders_user_position ON folders (user_id, position);
CREATE INDEX idx_folders_dirty         ON folders (dirty)      WHERE dirty = 1;
CREATE INDEX idx_folders_tombstone     ON folders (deleted_at) WHERE deleted_at IS NOT NULL;

-- One folder name per user, excluding tombstones — same pattern as
-- categories (now tags). The UNIQUE constraint can't be partial on
-- the inline column definition, so we use a partial index.
CREATE UNIQUE INDEX idx_folders_user_name_active
    ON folders (user_id, name)
    WHERE deleted_at IS NULL;


-- ─── 4. `capture_tags` many-to-many join ─────────────────────

CREATE TABLE capture_tags (
    id              TEXT PRIMARY KEY NOT NULL,
    capture_id      TEXT NOT NULL,
    tag_id          TEXT NOT NULL,

    -- "manual" | "ai-suggested" | "migration"
    -- "migration"   = the v4 backfill row carrying the legacy
    --                 captures.category value into the join.
    -- "ai-suggested"= written by the auto-tagging heuristic
    --                 (Phase E); kept as a flag so analytics can
    --                 measure suggestion acceptance.
    -- "manual"      = user-typed in the tag picker.
    source          TEXT NOT NULL DEFAULT 'manual',

    drive_file_id   TEXT,
    created_at      TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    updated_at      TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    dirty           INTEGER NOT NULL DEFAULT 1 CHECK (dirty IN (0, 1)),
    deleted_at      TEXT,

    -- FK relationships modeled in DDL but not enforced at the SQLite
    -- level (no `PRAGMA foreign_keys = ON` globally) — Room handles
    -- referential integrity in app code. The join rows soft-delete
    -- independently of the parent rows, so a tag deletion doesn't
    -- cascade-delete every capture_tags row; the sync worker
    -- tombstones the rows separately for cross-device propagation.
    FOREIGN KEY (capture_id) REFERENCES captures (id),
    FOREIGN KEY (tag_id)     REFERENCES tags     (id)
);

CREATE INDEX idx_capture_tags_capture   ON capture_tags (capture_id);
CREATE INDEX idx_capture_tags_tag       ON capture_tags (tag_id);
CREATE INDEX idx_capture_tags_dirty     ON capture_tags (dirty)      WHERE dirty = 1;
CREATE INDEX idx_capture_tags_tombstone ON capture_tags (deleted_at) WHERE deleted_at IS NOT NULL;

-- Unique-active constraint: a given (capture, tag) pair can have at
-- most one live join row. Soft-deleted rows are excluded so a
-- "tag X, untag X, tag X again" cycle works without primary-key
-- collisions.
CREATE UNIQUE INDEX idx_capture_tags_unique_active
    ON capture_tags (capture_id, tag_id)
    WHERE deleted_at IS NULL;


-- ─── 5. `smart_collections` table ────────────────────────────

CREATE TABLE smart_collections (
    id              TEXT PRIMARY KEY NOT NULL,
    user_id         TEXT NOT NULL,
    name            TEXT NOT NULL,

    -- Tabler icon name, e.g. "ti-receipt", "ti-eye", "ti-signature".
    -- Stored as a string so the icon palette can grow without a
    -- migration. NULL = default sparkle icon.
    icon            TEXT,

    -- Hex color for the icon background tint in the home strip card.
    -- NULL = accent-tint default.
    color           TEXT,

    -- AND-of-clauses array. See brief §3 for the v1 grammar.
    -- Example:
    --   [
    --     {"type":"folder_is","folder_id":"<id>"},
    --     {"type":"date_range","field":"created_at","preset":"this_month"}
    --   ]
    -- Validated in app code, not by the DB.
    rule_json       TEXT NOT NULL,

    position        INTEGER NOT NULL DEFAULT 0,

    -- 1 = shipped seed collection ("Invoices this month", "Needs
    -- review", "Contains signatures"). Lets us silently update the
    -- rule_json for seeded collections in a later release without
    -- overwriting user-created collections.
    is_seeded       INTEGER NOT NULL DEFAULT 0 CHECK (is_seeded IN (0, 1)),

    drive_file_id   TEXT,
    created_at      TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    updated_at      TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    dirty           INTEGER NOT NULL DEFAULT 1 CHECK (dirty IN (0, 1)),
    deleted_at      TEXT
);

CREATE INDEX idx_smart_collections_user_position ON smart_collections (user_id, position);
CREATE INDEX idx_smart_collections_dirty         ON smart_collections (dirty)      WHERE dirty = 1;
CREATE INDEX idx_smart_collections_tombstone     ON smart_collections (deleted_at) WHERE deleted_at IS NOT NULL;


-- ─── 6. Add columns to `captures` ────────────────────────────

-- Nullable initially so the migration can backfill in a second pass
-- (app-side, on first launch after upgrade — every existing capture
-- gets assigned to the seeded "Unfiled" folder). After backfill, app
-- code asserts non-null on read. A future migration can drop the
-- nullability; for now the column is permissive so older sync
-- payloads that predate folder_id can round-trip without losing
-- captures.
ALTER TABLE captures ADD COLUMN folder_id TEXT;

-- ISO timestamp ("strftime('%Y-%m-%dT%H:%M:%fZ', 'now')" shape).
-- Written by the PDF reader screen on a debounced page-scroll
-- signal (~500ms). Most-recent row across the user's captures is
-- what powers the Workspace home Continue card.
ALTER TABLE captures ADD COLUMN last_opened_at TEXT;

-- 1-indexed page number. Paired with last_opened_at — either both
-- set or both null; the writer never persists half of a pair. NULL
-- after migration; populated on first reopen.
ALTER TABLE captures ADD COLUMN last_opened_page INTEGER;

-- Install id of the device that last touched the capture. Lets the
-- continue card differentiate "you on iPhone, 2h ago" from "you on
-- Android, just now" once cross-device handoff UX ships. Not used
-- in the v1 home screen — column reservation only.
ALTER TABLE captures ADD COLUMN last_opened_device TEXT;


-- ─── 7. Materialize captures.category → capture_tags, then drop the column ─

-- SQLite doesn't support DROP COLUMN as a single ALTER on older
-- versions; even in 3.35+ it needs the column to be unconstrained.
-- We use the CREATE-INSERT-DROP-RENAME pattern for clarity and to
-- stay portable across SQLite builds.
--
-- The materialization seeds one capture_tags row per non-null
-- captures.category. The tag_id is looked up by name in the tags
-- table; we rely on app code to have ensured the tags rows exist
-- before this migration runs (every existing categories row carried
-- over via step 1; if a capture references a category string that
-- no longer has a row — possible after a delete — the LEFT JOIN
-- yields NULL tag_id and we skip the row).
--
-- This INSERT must run *before* the column drop. Wrap the section
-- in a transaction so a failure leaves the column in place.

BEGIN TRANSACTION;

INSERT INTO capture_tags (id, capture_id, tag_id, source, created_at, updated_at, dirty)
SELECT
    -- Synthesise a deterministic id from the capture+tag pair so a
    -- re-run of the migration is idempotent. UUIDv7 would be
    -- nicer but isn't available in pure SQLite — the
    -- "capture_id|tag_id" concat is unique-enough and gets replaced
    -- by app code's Uuidv7 generator on the first dirty-row sync.
    c.id || '|' || t.id           AS id,
    c.id                          AS capture_id,
    t.id                          AS tag_id,
    'migration'                   AS source,
    strftime('%Y-%m-%dT%H:%M:%fZ', 'now') AS created_at,
    strftime('%Y-%m-%dT%H:%M:%fZ', 'now') AS updated_at,
    1                             AS dirty
FROM captures c
JOIN tags     t ON t.name    = c.category
               AND t.user_id = c.user_id
               AND t.deleted_at IS NULL
WHERE c.category IS NOT NULL
  AND c.deleted_at IS NULL;

-- Now drop captures.category via table rebuild. We list every
-- *other* column explicitly so the rebuild loses the dropped one.
-- This list mirrors the captures schema after v3 + the four
-- additions from step 6 above. Keep this in sync with
-- CaptureEntity.kt.

CREATE TABLE captures_new (
    id                     TEXT PRIMARY KEY NOT NULL,
    user_id                TEXT NOT NULL,
    title                  TEXT,
    pdf_uri                TEXT NOT NULL,
    preview_uri            TEXT,
    page_count             INTEGER NOT NULL DEFAULT 0,
    source                 TEXT NOT NULL DEFAULT 'scan',
    latitude               REAL,
    longitude              REAL,
    locality               TEXT,
    sub_locality           TEXT,
    address                TEXT,
    conflict_stub          TEXT,
    drive_file_id          TEXT,
    pdf_drive_file_id      TEXT,
    preview_drive_file_id  TEXT,
    folder_id              TEXT,
    last_opened_at         TEXT,
    last_opened_page       INTEGER,
    last_opened_device     TEXT,
    created_at             TEXT NOT NULL,
    updated_at             TEXT NOT NULL,
    dirty                  INTEGER NOT NULL DEFAULT 1,
    deleted_at             TEXT
);

INSERT INTO captures_new
SELECT
    id, user_id, title, pdf_uri, preview_uri, page_count, source,
    latitude, longitude, locality, sub_locality, address,
    conflict_stub, drive_file_id, pdf_drive_file_id, preview_drive_file_id,
    folder_id, last_opened_at, last_opened_page, last_opened_device,
    created_at, updated_at, dirty, deleted_at
FROM captures;

DROP TABLE captures;
ALTER TABLE captures_new RENAME TO captures;

-- Recreate the captures indexes that the original table carried
-- (see v1_initial.sql). They live on the renamed table now.
CREATE INDEX idx_captures_user_created ON captures (user_id, created_at);
CREATE INDEX idx_captures_dirty        ON captures (dirty)      WHERE dirty = 1;
CREATE INDEX idx_captures_tombstone    ON captures (deleted_at) WHERE deleted_at IS NOT NULL;

-- New index for the Workspace folder-detail screen — list captures
-- in a folder, newest first.
CREATE INDEX idx_captures_folder_created ON captures (folder_id, created_at)
    WHERE deleted_at IS NULL;

-- New index for the Continue card lookup — most-recently-opened
-- capture per user.
CREATE INDEX idx_captures_last_opened ON captures (user_id, last_opened_at)
    WHERE last_opened_at IS NOT NULL AND deleted_at IS NULL;

COMMIT;


-- ─── 8. App-code follow-up (NOT in this script) ──────────────
--
-- These steps run in app code on first launch after upgrade,
-- because they need a userId (runtime value) and access to the
-- Uuidv7 generator:
--
--   - Seed a single `folders` row per user with is_default = 1,
--     name = "Unfiled", color = a neutral stone. Done by
--     FolderRepository.seedDefaultsIfNeeded(userId).
--
--   - Backfill every capture's folder_id to the seeded Unfiled
--     folder id. Done by CaptureRepository.backfillFolderId(userId).
--
--   - Replace the synthesized capture_tags ids (id = "capture|tag"
--     from step 7) with real UUIDv7 ids on the next dirty-row sync,
--     so the Drive payload filenames look like every other entity.
--
--   - Seed the three v1 smart collections: "Invoices this month",
--     "Needs review", "Contains signatures". Done by
--     SmartCollectionRepository.seedDefaultsIfNeeded(userId). Note:
--     "Needs review" requires a `#needs-review` tag to exist —
--     add it to the seed-tags list (alongside Ideas / Projects /
--     Meetings / Todo / Study / Journal) before seeding the
--     collection.
-- =============================================================
