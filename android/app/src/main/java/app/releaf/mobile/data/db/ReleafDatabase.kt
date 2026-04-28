/*
 * ReleafDatabase.kt
 *
 * Room database hub. Models notepad_entries + the notebook/chapter/page trio
 * from design-system/migrations/v1_initial.sql. Remaining tables (captures,
 * tasks, etc.) get added as their features land.
 *
 * Schema management today: Room generates the DDL from @Entity annotations.
 * The v1_initial.sql source of truth is NOT yet executed verbatim — see the
 * note at the top of NotepadEntry.kt. FTS5 virtual tables + triggers are
 * installed via `SchemaCallback` below with SQL copied verbatim from the
 * .sql file, since @Entity can't express them.
 *
 * Dogfood: fallbackToDestructiveMigration() wipes on version bump. Remove
 * before any real user data exists.
 */

package app.releaf.mobile.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import app.releaf.mobile.data.callhistory.CallHistoryDao
import app.releaf.mobile.data.callhistory.CallHistoryEntity
import app.releaf.mobile.data.notebook.BookSeriesDao
import app.releaf.mobile.data.notebook.BookSeriesEntity
import app.releaf.mobile.data.notebook.ChapterDao
import app.releaf.mobile.data.notebook.ChapterEntity
import app.releaf.mobile.data.notebook.NotebookDao
import app.releaf.mobile.data.notebook.NotebookEntity
import app.releaf.mobile.data.notebook.PageDao
import app.releaf.mobile.data.notebook.PageEntity
import app.releaf.mobile.data.shelf.ShelfDao
import app.releaf.mobile.data.shelf.ShelfEntity
import app.releaf.mobile.data.notepad.NotepadDao
import app.releaf.mobile.data.notepad.NotepadEntry
import app.releaf.mobile.data.perspective.PerspectiveDao
import app.releaf.mobile.data.perspective.PerspectiveEntity
import app.releaf.mobile.data.reminder.ReminderDao
import app.releaf.mobile.data.reminder.ReminderEntity
import app.releaf.mobile.data.sync.SyncStateDao
import app.releaf.mobile.data.sync.SyncStateEntity
import app.releaf.mobile.data.task.TaskDao
import app.releaf.mobile.data.task.TaskEntity

@Database(
    entities = [
        NotepadEntry::class,
        NotebookEntity::class,
        ChapterEntity::class,
        PageEntity::class,
        ShelfEntity::class,
        BookSeriesEntity::class,
        SyncStateEntity::class,
        TaskEntity::class,
        ReminderEntity::class,
        PerspectiveEntity::class,
        CallHistoryEntity::class,
        app.releaf.mobile.data.activity.AuditEvent::class,
    ],
    // v20: adds `notepad_entries.category` (nullable TEXT). Lets a
    // notepad entry be filed under one of the predefined categories
    // (Home / Work / Personal / Health / Travel / Ideas) or any
    // user-typed custom string. NULL = uncategorised — same convention
    // the description column uses. No backfill: existing rows stay
    // uncategorised until the user opens them and picks one.
    //
    // v19: rebuilds archive-column indices under Room's naming. v17
    // shipped raw `idx_pages_archived_at` / `idx_chapters_archived_at`
    // partial indices that the entity declarations didn't match, so
    // Room's schema validator rejected them on cold start. Both
    // entities now carry `@Index("archived_at")`; this migration
    // drops the old names and creates the Room-owned ones, while
    // Migration16To17 stops creating the orphan indices for fresh
    // installs.
    //
    // v18: adds `pages.page_notes_json` so each `Note` round-trips
    // with its own id + createdAt instead of being collapsed into
    // the single markdown blob in `pages.notes`. The markdown column
    // sticks around — FTS triggers index it, and the legacy fallback
    // path on read still wraps it as one Note when `page_notes_json`
    // is empty (covers rows written before this migration).
    //
    // v17: backfills the columns the real `LocalDriveRepository`
    // depends on but the original schema never carried —
    //   pages.tags        JSON array, default `[]`. Page-level free-form
    //                     tags, persisted alongside the page row.
    //   pages.archived_at ISO timestamp, nullable. Page-level archive
    //                     bin separate from `deleted_at` (true tombstone).
    //   chapters.archived_at  Same shape as pages.archived_at — gives the
    //                         chapter detail a recoverable archive state.
    // Notebooks already have archived_at (added in v3→v4) so v17 only
    // touches pages + chapters.
    version = 20,
    exportSchema = true,
)
abstract class ReleafDatabase : RoomDatabase() {

    abstract fun notepadDao(): NotepadDao
    abstract fun notebookDao(): NotebookDao
    abstract fun chapterDao(): ChapterDao
    abstract fun pageDao(): PageDao
    abstract fun shelfDao(): ShelfDao
    abstract fun bookSeriesDao(): BookSeriesDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun taskDao(): TaskDao
    abstract fun reminderDao(): ReminderDao
    abstract fun perspectiveDao(): PerspectiveDao
    abstract fun callHistoryDao(): CallHistoryDao
    abstract fun auditDao(): app.releaf.mobile.data.activity.AuditDao

    /**
     * One-shot schema installer for SQL that Room's annotation model can't
     * express — currently FTS5 virtual tables + their trigger wiring. The SQL
     * here is a verbatim copy from v1_initial.sql; keep it in sync when that
     * file changes (tracked by the "source of truth" follow-up in
     * NotepadEntry.kt).
     */
    private object SchemaCallback : Callback() {
        // Room 2.7+ routes `onCreate` through `SQLiteConnection` when a
        // driver is set (we use BundledSQLiteDriver). The legacy
        // `onCreate(SupportSQLiteDatabase)` overload is never called on
        // that path, so override only this one.
        override fun onCreate(connection: SQLiteConnection) {
            // FTS5 virtual table — see v1_initial.sql §FTS5.
            connection.execSQL(
                """
                CREATE VIRTUAL TABLE IF NOT EXISTS fts_notepad_notes USING fts5(
                    notepad_entry_id UNINDEXED,
                    notes,
                    tokenize = 'unicode61 remove_diacritics 2'
                )
                """.trimIndent()
            )

            // Keep the FTS mirror in sync via triggers. Verbatim from v1_initial.sql.
            connection.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS notepad_entries_fts_ai
                AFTER INSERT ON notepad_entries
                WHEN new.deleted_at IS NULL AND new.notes <> ''
                BEGIN
                    INSERT INTO fts_notepad_notes(notepad_entry_id, notes)
                    VALUES (new.id, new.notes);
                END
                """.trimIndent()
            )
            connection.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS notepad_entries_fts_au
                AFTER UPDATE ON notepad_entries
                BEGIN
                    DELETE FROM fts_notepad_notes WHERE notepad_entry_id = old.id;
                    INSERT INTO fts_notepad_notes(notepad_entry_id, notes)
                    SELECT new.id, new.notes
                    WHERE new.deleted_at IS NULL AND new.notes <> '';
                END
                """.trimIndent()
            )
            connection.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS notepad_entries_fts_ad
                AFTER DELETE ON notepad_entries
                BEGIN
                    DELETE FROM fts_notepad_notes WHERE notepad_entry_id = old.id;
                END
                """.trimIndent()
            )

            // Pages FTS5 virtual table + triggers — verbatim from v1_initial.sql §FTS5.
            connection.execSQL(
                """
                CREATE VIRTUAL TABLE IF NOT EXISTS fts_page_notes USING fts5(
                    page_id UNINDEXED,
                    notes,
                    tokenize = 'unicode61 remove_diacritics 2'
                )
                """.trimIndent()
            )
            connection.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS pages_fts_ai
                AFTER INSERT ON pages
                WHEN new.deleted_at IS NULL AND new.notes <> ''
                BEGIN
                    INSERT INTO fts_page_notes(page_id, notes)
                    VALUES (new.id, new.notes);
                END
                """.trimIndent()
            )
            connection.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS pages_fts_au
                AFTER UPDATE ON pages
                BEGIN
                    DELETE FROM fts_page_notes WHERE page_id = old.id;
                    INSERT INTO fts_page_notes(page_id, notes)
                    SELECT new.id, new.notes
                    WHERE new.deleted_at IS NULL AND new.notes <> '';
                END
                """.trimIndent()
            )
            connection.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS pages_fts_ad
                AFTER DELETE ON pages
                BEGIN
                    DELETE FROM fts_page_notes WHERE page_id = old.id;
                END
                """.trimIndent()
            )
        }

        override fun onOpen(connection: SQLiteConnection) {
            // Existing dogfood DBs may predate the callback that installs
            // FTS. Search should never depend on a reinstall, so make the
            // virtual tables/triggers idempotent and rebuild their mirrors
            // from the base tables whenever the DB opens.
            connection.execSQL(
                """
                CREATE VIRTUAL TABLE IF NOT EXISTS fts_notepad_notes USING fts5(
                    notepad_entry_id UNINDEXED,
                    notes,
                    tokenize = 'unicode61 remove_diacritics 2'
                )
                """.trimIndent()
            )
            connection.execSQL(
                """
                CREATE VIRTUAL TABLE IF NOT EXISTS fts_page_notes USING fts5(
                    page_id UNINDEXED,
                    notes,
                    tokenize = 'unicode61 remove_diacritics 2'
                )
                """.trimIndent()
            )
            connection.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS notepad_entries_fts_ai
                AFTER INSERT ON notepad_entries
                WHEN new.deleted_at IS NULL AND new.notes <> ''
                BEGIN
                    INSERT INTO fts_notepad_notes(notepad_entry_id, notes)
                    VALUES (new.id, new.notes);
                END
                """.trimIndent()
            )
            connection.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS notepad_entries_fts_au
                AFTER UPDATE ON notepad_entries
                BEGIN
                    DELETE FROM fts_notepad_notes WHERE notepad_entry_id = old.id;
                    INSERT INTO fts_notepad_notes(notepad_entry_id, notes)
                    SELECT new.id, new.notes
                    WHERE new.deleted_at IS NULL AND new.notes <> '';
                END
                """.trimIndent()
            )
            connection.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS notepad_entries_fts_ad
                AFTER DELETE ON notepad_entries
                BEGIN
                    DELETE FROM fts_notepad_notes WHERE notepad_entry_id = old.id;
                END
                """.trimIndent()
            )
            connection.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS pages_fts_ai
                AFTER INSERT ON pages
                WHEN new.deleted_at IS NULL AND new.notes <> ''
                BEGIN
                    INSERT INTO fts_page_notes(page_id, notes)
                    VALUES (new.id, new.notes);
                END
                """.trimIndent()
            )
            connection.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS pages_fts_au
                AFTER UPDATE ON pages
                BEGIN
                    DELETE FROM fts_page_notes WHERE page_id = old.id;
                    INSERT INTO fts_page_notes(page_id, notes)
                    SELECT new.id, new.notes
                    WHERE new.deleted_at IS NULL AND new.notes <> '';
                END
                """.trimIndent()
            )
            connection.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS pages_fts_ad
                AFTER DELETE ON pages
                BEGIN
                    DELETE FROM fts_page_notes WHERE page_id = old.id;
                END
                """.trimIndent()
            )
            connection.execSQL("DELETE FROM fts_notepad_notes")
            connection.execSQL(
                """
                INSERT INTO fts_notepad_notes(notepad_entry_id, notes)
                SELECT id, notes
                FROM notepad_entries
                WHERE deleted_at IS NULL AND notes <> ''
                """.trimIndent()
            )
            connection.execSQL("DELETE FROM fts_page_notes")
            connection.execSQL(
                """
                INSERT INTO fts_page_notes(page_id, notes)
                SELECT id, notes
                FROM pages
                WHERE deleted_at IS NULL AND notes <> ''
                """.trimIndent()
            )
        }
    }

    /**
     * v1 → v2: add `sketch_strokes` column (defaults to empty JSON array)
     * on both `pages` and `notepad_entries`. Matches the @ColumnInfo
     * defaults on the entities so existing rows round-trip cleanly.
     */
    private object Migration1To2 : Migration(1, 2) {
        // Same driver-path story as SchemaCallback above: override the
        // SQLiteConnection overload, since the legacy one isn't called
        // when BundledSQLiteDriver is set. The default
        // `migrate(SQLiteConnection)` throws NotImplementedError.
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "ALTER TABLE pages ADD COLUMN sketch_strokes TEXT NOT NULL DEFAULT '[]'",
            )
            connection.execSQL(
                "ALTER TABLE notepad_entries ADD COLUMN sketch_strokes TEXT NOT NULL DEFAULT '[]'",
            )
        }
    }

    /**
     * v2 → v3: add `sub_pages` JSON column for the horizontal sub-page
     * pager. Empty default; the VM lazy-migrates legacy rows (flat
     * `notes` + `sketch_strokes`) into a single sub-page on first load.
     */
    private object Migration2To3 : Migration(2, 3) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "ALTER TABLE pages ADD COLUMN sub_pages TEXT NOT NULL DEFAULT '[]'",
            )
            connection.execSQL(
                "ALTER TABLE notepad_entries ADD COLUMN sub_pages TEXT NOT NULL DEFAULT '[]'",
            )
        }
    }

    /**
     * v3 → v4: add `description` on notebooks + chapters (nullable, no
     * default — the hero / chapter cards treat null as "no description
     * yet"), and `archived_at` on notebooks so the Archive tab has
     * something to observe. Indexed so `observeArchived()` and the
     * active filter stay cheap.
     */
    private object Migration3To4 : Migration(3, 4) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE notebooks ADD COLUMN description TEXT")
            connection.execSQL("ALTER TABLE notebooks ADD COLUMN archived_at TEXT")
            connection.execSQL("ALTER TABLE chapters ADD COLUMN description TEXT")
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS index_notebooks_archived_at " +
                    "ON notebooks(archived_at)"
            )
        }
    }

    /**
     * v4 → v5: adds the `tasks` table. Column shapes mirror
     * design-system/migrations/v1_initial.sql §5 for the subset of
     * fields we need today (id + user scope + basic content +
     * completed state + priority + timestamps). Sync bookkeeping
     * (`dirty`, `deleted_at`) matches the sibling tables.
     */
    private object Migration4To5 : Migration(4, 5) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                """
                CREATE TABLE IF NOT EXISTS tasks (
                    id             TEXT NOT NULL PRIMARY KEY,
                    user_id        TEXT NOT NULL,
                    title          TEXT NOT NULL,
                    description    TEXT,
                    due_date       TEXT,
                    completed      INTEGER NOT NULL DEFAULT 0,
                    completed_at   TEXT,
                    priority       INTEGER NOT NULL DEFAULT 0,
                    created_at     TEXT NOT NULL,
                    updated_at     TEXT NOT NULL,
                    dirty          INTEGER NOT NULL DEFAULT 1,
                    deleted_at     TEXT
                )
                """.trimIndent()
            )
            connection.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_user_id ON tasks(user_id)")
            connection.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_due_date ON tasks(due_date)")
            connection.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_completed ON tasks(completed)")
            connection.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_updated_at ON tasks(updated_at)")
            connection.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_deleted_at ON tasks(deleted_at)")
        }
    }

    /**
     * v5 → v6: adds the `reminders` table. Timestamps are stored as
     * epoch millis (not ISO strings like the other tables) because the
     * scheduler consumes them directly — re-parsing on every fire
     * would waste cycles for no gain.
     */
    private object Migration5To6 : Migration(5, 6) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                """
                CREATE TABLE IF NOT EXISTS reminders (
                    id             TEXT NOT NULL PRIMARY KEY,
                    user_id        TEXT NOT NULL,
                    title          TEXT NOT NULL,
                    note           TEXT,
                    remind_at      INTEGER NOT NULL,
                    completed_at   INTEGER,
                    fired_at       INTEGER,
                    created_at     INTEGER NOT NULL,
                    updated_at     INTEGER NOT NULL,
                    deleted_at     INTEGER
                )
                """.trimIndent()
            )
            connection.execSQL("CREATE INDEX IF NOT EXISTS index_reminders_user_id ON reminders(user_id)")
            connection.execSQL("CREATE INDEX IF NOT EXISTS index_reminders_remind_at ON reminders(remind_at)")
            connection.execSQL("CREATE INDEX IF NOT EXISTS index_reminders_completed_at ON reminders(completed_at)")
            connection.execSQL("CREATE INDEX IF NOT EXISTS index_reminders_deleted_at ON reminders(deleted_at)")
        }
    }

    /**
     * v6 → v7: adds the Kanban `status` column on `tasks`. Default
     * 'todo' so newly-migrated rows land in the To do column of the
     * Boards view. Backfill rule: anything already `completed = 1`
     * becomes `status = 'done'` so the initial board render matches
     * reality without forcing the user to re-tick.
     *
     * The `doing` value never appears in the backfill — a task is
     * only "doing" once the user explicitly starts it.
     */
    private object Migration6To7 : Migration(6, 7) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "ALTER TABLE tasks ADD COLUMN status TEXT NOT NULL DEFAULT 'todo'"
            )
            connection.execSQL(
                "UPDATE tasks SET status = 'done' WHERE completed = 1"
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS index_tasks_status ON tasks(status)"
            )
        }
    }

    /**
     * v7 → v8: adds the `perspectives` table. No backfill — the
     * repository seeds Home / Work / Errands on first observe when
     * the table is empty, so an upgrading user lands with three
     * default tiles (matching what the hard-coded DEFAULT_CONTEXTS
     * used to produce) even though their existing tasks don't
     * change.
     */
    private object Migration7To8 : Migration(7, 8) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                """
                CREATE TABLE IF NOT EXISTS perspectives (
                    id           TEXT NOT NULL PRIMARY KEY,
                    user_id      TEXT NOT NULL,
                    name         TEXT NOT NULL,
                    icon_key     TEXT NOT NULL DEFAULT 'label',
                    sort_order   INTEGER NOT NULL DEFAULT 0,
                    created_at   TEXT NOT NULL,
                    updated_at   TEXT NOT NULL,
                    deleted_at   TEXT
                )
                """.trimIndent()
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS index_perspectives_user_id ON perspectives(user_id)"
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS index_perspectives_name ON perspectives(name)"
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS index_perspectives_deleted_at ON perspectives(deleted_at)"
            )
        }
    }

    /**
     * v8 → v9: adds `task_id` on `reminders` so a reminder can be
     * attached to a task. Null = standalone reminder (the existing
     * Reminders screen path). Indexed for the "find reminder for
     * this task" lookup the Edit sheet does on every open.
     */
    private object Migration8To9 : Migration(8, 9) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "ALTER TABLE reminders ADD COLUMN task_id TEXT"
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS index_reminders_task_id ON reminders(task_id)"
            )
        }
    }

    /**
     * v9 → v10: adds `recurs_every_days` on `reminders`. Nullable,
     * default NULL so existing rows stay one-shot.
     */
    private object Migration9To10 : Migration(9, 10) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "ALTER TABLE reminders ADD COLUMN recurs_every_days INTEGER"
            )
        }
    }

    /**
     * v10 → v11: adds `perspective_id` FK column on `reminders`.
     * Nullable, default NULL — existing rows keep their current
     * behaviour (tag chip falls back to @tag-in-title parsing).
     */
    private object Migration10To11 : Migration(10, 11) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "ALTER TABLE reminders ADD COLUMN perspective_id TEXT"
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS index_reminders_perspective_id ON reminders(perspective_id)"
            )
        }
    }

    /**
     * v11 → v12: introduces Shelf → Book → Chapter → Page hierarchy.
     *
     * Additions:
     *  - `shelves` table (top-level grouping; editable name, color,
     *    order).
     *  - `book_series` table (groups notebooks that are volumes of
     *    the same book). A notebook belongs to a series only when
     *    there's more than one volume; single-volume books keep
     *    `series_id = NULL`.
     *  - Four columns on `notebooks`: `shelf_id` (FK, NOT NULL —
     *    defaults to `shelf-general` via DEFAULT so existing rows
     *    pick it up in one sweep), `series_id` (FK, nullable),
     *    `volume_number` (INTEGER DEFAULT 1), `volume_name`
     *    (TEXT, nullable — UI derives "<book> vol <n>" when a
     *    series has >1 volumes).
     *
     * Seed: inserts the default "General" shelf so upgrading users
     * land with their existing notebooks already assigned to it.
     */
    private object Migration11To12 : Migration(11, 12) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                """
                CREATE TABLE IF NOT EXISTS shelves (
                    id              TEXT NOT NULL PRIMARY KEY,
                    name            TEXT NOT NULL,
                    color_hex       TEXT,
                    position        INTEGER NOT NULL DEFAULT 1024,
                    created_at      TEXT NOT NULL,
                    updated_at      TEXT NOT NULL,
                    dirty           INTEGER NOT NULL DEFAULT 1,
                    deleted_at      TEXT
                )
                """.trimIndent()
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS index_shelves_position ON shelves(position)"
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS index_shelves_deleted_at ON shelves(deleted_at)"
            )

            connection.execSQL(
                """
                CREATE TABLE IF NOT EXISTS book_series (
                    id              TEXT NOT NULL PRIMARY KEY,
                    shelf_id        TEXT NOT NULL,
                    name            TEXT NOT NULL,
                    created_at      TEXT NOT NULL,
                    updated_at      TEXT NOT NULL,
                    dirty           INTEGER NOT NULL DEFAULT 1,
                    deleted_at      TEXT
                )
                """.trimIndent()
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS index_book_series_shelf_id ON book_series(shelf_id)"
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS index_book_series_deleted_at ON book_series(deleted_at)"
            )

            // Seed the default shelf BEFORE adding shelf_id to
            // notebooks so the ALTER with DEFAULT 'shelf-general'
            // has a valid target row for the logical FK.
            connection.execSQL(
                """
                INSERT OR IGNORE INTO shelves (
                    id, name, color_hex, position, created_at, updated_at, dirty
                ) VALUES (
                    'shelf-general', 'General', '#7AA874', 1024,
                    strftime('%Y-%m-%dT%H:%M:%fZ', 'now'),
                    strftime('%Y-%m-%dT%H:%M:%fZ', 'now'),
                    1
                )
                """.trimIndent()
            )

            connection.execSQL(
                "ALTER TABLE notebooks ADD COLUMN shelf_id TEXT NOT NULL DEFAULT 'shelf-general'"
            )
            connection.execSQL(
                "ALTER TABLE notebooks ADD COLUMN series_id TEXT"
            )
            connection.execSQL(
                "ALTER TABLE notebooks ADD COLUMN volume_number INTEGER NOT NULL DEFAULT 1"
            )
            connection.execSQL(
                "ALTER TABLE notebooks ADD COLUMN volume_name TEXT"
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS index_notebooks_shelf_id ON notebooks(shelf_id)"
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS index_notebooks_series_id ON notebooks(series_id)"
            )
        }
    }

    /**
     * v12 → v13: adds the `call_history` table. Local log of
     * outbound calls placed from inside the app; rows are written
     * on dial and updated in-place by the TelephonyCallback
     * observer once OFFHOOK → IDLE transitions fire. Not sync'd to
     * Drive.
     */
    private object Migration12To13 : Migration(12, 13) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                """
                CREATE TABLE IF NOT EXISTS call_history (
                    id                TEXT NOT NULL PRIMARY KEY,
                    user_id           TEXT NOT NULL,
                    contact_name      TEXT NOT NULL,
                    phone_number      TEXT NOT NULL,
                    source            TEXT NOT NULL,
                    started_at        TEXT NOT NULL,
                    connected_at      TEXT,
                    ended_at          TEXT,
                    duration_seconds  INTEGER
                )
                """.trimIndent()
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS index_call_history_user_id ON call_history(user_id)"
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS index_call_history_started_at ON call_history(started_at)"
            )
        }
    }

    /**
     * v13 → v14: adds the `audit_events` table — phase 2 of the
     * activity tracker. Append-only log written by the four user-
     * facing repositories on every successful mutation. Indexed by
     * user_id, timestamp (descending feed reads), and (entity_type,
     * entity_id) so per-entity history lookups stay cheap.
     */
    private object Migration13To14 : Migration(13, 14) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                """
                CREATE TABLE IF NOT EXISTS audit_events (
                    id           TEXT NOT NULL PRIMARY KEY,
                    user_id      TEXT NOT NULL,
                    timestamp    TEXT NOT NULL,
                    action       TEXT NOT NULL,
                    entity_type  TEXT NOT NULL,
                    entity_id    TEXT NOT NULL,
                    title        TEXT,
                    source       TEXT NOT NULL DEFAULT 'user',
                    dirty        INTEGER NOT NULL DEFAULT 1
                )
                """.trimIndent()
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS index_audit_events_user_id ON audit_events(user_id)"
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS index_audit_events_timestamp ON audit_events(timestamp)"
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS index_audit_events_entity_type_entity_id " +
                    "ON audit_events(entity_type, entity_id)"
            )
        }
    }

    /**
     * v14 → v15: adds the `context` column to `audit_events`. Holds the
     * free-form hierarchy breadcrumb the timeline UI renders next to
     * sub-event captures (photo / voice / todo / contact / location /
     * scan) — e.g. "Releaf garden › Chapter 1 › Page A" — snapshotted
     * at log time so it survives parent renames.
     */
    private object Migration14To15 : Migration(14, 15) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE audit_events ADD COLUMN context TEXT")
        }
    }

    /**
     * v15 → v16: adds the `description` column on `notepad_entries`.
     * Nullable, no default — existing rows treat NULL as "no description
     * yet" (same convention as the notebooks/chapters description
     * columns added in 3 → 4). Mirrors the shared
     * design-system/migrations/v2_notepad_description.sql file.
     */
    private object Migration15To16 : Migration(15, 16) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE notepad_entries ADD COLUMN description TEXT")
        }
    }

    /**
     * v16 → v17: real-only foundation. Adds the three columns that
     * `LocalDriveRepository` reads + writes today but the v1 schema
     * never carried. Mirrors iOS's `v6_archive_and_tags` migration
     * so a row from one platform deserialises cleanly on the other.
     *
     *   pages.tags          JSON array of free-form tag strings.
     *                       Defaults to `'[]'` so existing rows
     *                       round-trip as zero tags.
     *   pages.archived_at   ISO timestamp; nullable. Splits archive
     *                       (recoverable, in archive bin) from
     *                       deleted_at (true tombstone).
     *   chapters.archived_at Same shape — chapters can now be archived
     *                       without being soft-deleted.
     */
    private object Migration16To17 : Migration(16, 17) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE pages ADD COLUMN tags TEXT NOT NULL DEFAULT '[]'")
            connection.execSQL("ALTER TABLE pages ADD COLUMN archived_at TEXT")
            connection.execSQL("ALTER TABLE chapters ADD COLUMN archived_at TEXT")
        }
    }

    /**
     * v17 → v18: add `pages.page_notes_json`. Stores the typed Note
     * array (id + body + createdAt per element) so each Note keeps
     * its identity through the persistence round-trip — joining the
     * bodies into the existing markdown column lost ids and
     * timestamps. The markdown column stays so FTS keeps working;
     * the mapper joins all note bodies into it on every write.
     */
    private object Migration17To18 : Migration(17, 18) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE pages ADD COLUMN page_notes_json TEXT NOT NULL DEFAULT '[]'")
        }
    }

    /**
     * v18 → v19: replaces the orphan `idx_*_archived_at` partial
     * indices that Migration16To17 used to create with full indices
     * Room owns directly (`index_pages_archived_at`,
     * `index_chapters_archived_at`). The entities now declare
     * `@Index("archived_at")` on both tables, so the validator
     * expects the Room-naming variants. `IF EXISTS` on the drops
     * keeps fresh installs happy (they never had the old names
     * after the Migration16To17 fix).
     */
    private object Migration18To19 : Migration(18, 19) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("DROP INDEX IF EXISTS idx_pages_archived_at")
            connection.execSQL("DROP INDEX IF EXISTS idx_chapters_archived_at")
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS index_pages_archived_at ON pages(archived_at)"
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS index_chapters_archived_at ON chapters(archived_at)"
            )
        }
    }

    /**
     * v19 → v20: adds `notepad_entries.category`. Nullable, no default —
     * existing rows treat NULL as "uncategorised" (same convention as
     * the description column added in 15→16). Predefined categories
     * (Home / Work / Personal / Health / Travel / Ideas) and custom
     * user-typed strings both use this single column; the predefined
     * list is just the UI seed for the picker, not a separate table.
     */
    private object Migration19To20 : Migration(19, 20) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE notepad_entries ADD COLUMN category TEXT")
        }
    }

    companion object {
        private const val DB_NAME = "releaf.db"

        @Volatile private var instance: ReleafDatabase? = null

        fun get(context: Context): ReleafDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ReleafDatabase::class.java,
                    DB_NAME,
                )
                    // Bundled SQLite — the system SQLite on some Android
                    // images (including the API-35 emulator) is compiled
                    // without FTS5, which crashes `CREATE VIRTUAL TABLE ...
                    // USING fts5` in SchemaCallback below. The bundled
                    // driver ships its own SQLite with FTS5 linked in, so
                    // search works on every device.
                    .setDriver(BundledSQLiteDriver())
                    .addCallback(SchemaCallback)
                    .addMigrations(
                        Migration1To2,
                        Migration2To3,
                        Migration3To4,
                        Migration4To5,
                        Migration5To6,
                        Migration6To7,
                        Migration7To8,
                        Migration8To9,
                        Migration9To10,
                        Migration10To11,
                        Migration11To12,
                        Migration12To13,
                        Migration13To14,
                        Migration14To15,
                        Migration15To16,
                        Migration16To17,
                        Migration17To18,
                        Migration18To19,
                        Migration19To20,
                    )
                    // Dogfood installs at v2-v6 (pre-flatten) are handled
                    // here as a downgrade: the DB wipes, Room recreates
                    // the v1 schema from the current entity set, and
                    // SchemaCallback re-installs the FTS virtual tables
                    // + triggers. Normal upgrade path (new versions
                    // going forward) must ship real Migration objects —
                    // this fallback does NOT cover that case.
                    .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                    .build()
                    .also { instance = it }
            }
    }
}
