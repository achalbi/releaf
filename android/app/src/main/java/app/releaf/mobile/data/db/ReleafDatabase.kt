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
import app.releaf.mobile.data.notebook.ChapterDao
import app.releaf.mobile.data.notebook.ChapterEntity
import app.releaf.mobile.data.notebook.NotebookDao
import app.releaf.mobile.data.notebook.NotebookEntity
import app.releaf.mobile.data.notebook.PageDao
import app.releaf.mobile.data.notebook.PageEntity
import app.releaf.mobile.data.notepad.NotepadDao
import app.releaf.mobile.data.notepad.NotepadEntry
import app.releaf.mobile.data.sync.SyncStateDao
import app.releaf.mobile.data.sync.SyncStateEntity

@Database(
    entities = [
        NotepadEntry::class,
        NotebookEntity::class,
        ChapterEntity::class,
        PageEntity::class,
        SyncStateEntity::class,
    ],
    // v2: adds `sketch_strokes` TEXT column to `pages` and
    // `notepad_entries` for the freehand-drawing overlay. Migration
    // `Migration1To2` below ships the ALTER TABLE statements.
    version = 2,
    exportSchema = true,
)
abstract class ReleafDatabase : RoomDatabase() {

    abstract fun notepadDao(): NotepadDao
    abstract fun notebookDao(): NotebookDao
    abstract fun chapterDao(): ChapterDao
    abstract fun pageDao(): PageDao
    abstract fun syncStateDao(): SyncStateDao

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
                    .addMigrations(Migration1To2)
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
