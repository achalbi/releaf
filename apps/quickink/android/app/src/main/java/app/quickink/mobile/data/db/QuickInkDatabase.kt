/*
 * QuickInkDatabase.kt
 *
 * Room database hub for QuickInk. Models the four tables in
 * `shared/design-system/migrations/quickink/v1_initial.sql`:
 *   - notepad_entries  (NotepadEntry @Entity from :shared:notes)
 *   - sync_state       (SyncStateEntity @Entity from :shared:sync)
 *   - captures         (CaptureEntity @Entity, this app target)
 *   - ocr_results      (OcrResultEntity @Entity, this app target)
 *
 * Schema management today: Room generates the DDL from @Entity
 * annotations. The .sql source-of-truth file in
 * `shared/design-system/migrations/quickink/` is *documentation* —
 * not executed at runtime. FTS5 virtual tables + triggers
 * (which @Entity can't express) are installed via `SchemaCallback`
 * below with SQL copied verbatim from that file.
 *
 * Mirror of Releaf's `ReleafDatabase.kt` shape; trimmed entity list
 * per QUICKINK_PROPOSAL.md §3.
 *
 * Dogfood: `fallbackToDestructiveMigration()` wipes on version bump.
 * Remove before any real user data exists.
 */

package app.quickink.mobile.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import app.quickink.mobile.data.analytics.AnalyticsOutboxDao
import app.quickink.mobile.data.analytics.AnalyticsOutboxEntity
import app.quickink.mobile.data.capture.CaptureDao
import app.quickink.mobile.data.capture.CaptureEntity
import app.quickink.mobile.data.capturelocation.CaptureLocationDao
import app.quickink.mobile.data.capturelocation.CaptureLocationEntity
import app.quickink.mobile.data.capturetag.CaptureTagDao
import app.quickink.mobile.data.capturetag.CaptureTagEntity
import app.quickink.mobile.data.folder.FolderDao
import app.quickink.mobile.data.folder.FolderEntity
import app.quickink.mobile.data.location.LocationDao
import app.quickink.mobile.data.location.LocationEntity
import app.quickink.mobile.data.ocr.OcrResultDao
import app.quickink.mobile.data.ocr.OcrResultEntity
import app.quickink.mobile.data.panchanga.PanchangaDao
import app.quickink.mobile.data.panchanga.PanchangaEntity
import app.quickink.mobile.data.captureperson.CapturePersonDao
import app.quickink.mobile.data.captureperson.CapturePersonEntity
import app.quickink.mobile.data.person.PersonDao
import app.quickink.mobile.data.person.PersonEntity
import app.quickink.mobile.data.profile.ProfileSettingsDao
import app.quickink.mobile.data.profile.ProfileSettingsEntity
import app.quickink.mobile.data.smartcollection.SmartCollectionDao
import app.quickink.mobile.data.smartcollection.SmartCollectionEntity
import app.quickink.mobile.data.tag.TagDao
import app.quickink.mobile.data.tag.TagEntity
import app.quickink.mobile.data.story.StoryDao
import app.quickink.mobile.data.story.StoryEntity
import app.quickink.mobile.data.storyitem.StoryItemDao
import app.quickink.mobile.data.storyitem.StoryItemEntity
import app.quickink.mobile.data.storyvoiceclip.StoryVoiceClipDao
import app.quickink.mobile.data.storyvoiceclip.StoryVoiceClipEntity
import app.quickink.mobile.data.voicenote.VoiceNoteDao
import app.quickink.mobile.data.voicenote.VoiceNoteEntity
import app.releaf.mobile.data.notepad.NotepadDao
import app.releaf.mobile.data.notepad.NotepadEntry
import app.releaf.mobile.data.sync.SyncStateDao
import app.releaf.mobile.data.sync.SyncStateEntity

@Database(
    entities = [
        NotepadEntry::class,
        SyncStateEntity::class,
        CaptureEntity::class,
        OcrResultEntity::class,
        // ─── Workspace v1 (Phase A) ────────────────────────
        // TagEntity replaces CategoryEntity in A.2 — table renamed
        // from `categories` to `tags`. Package data.category →
        // data.tag. Same semantics; promoted to many-to-many.
        TagEntity::class,
        ProfileSettingsEntity::class,
        AnalyticsOutboxEntity::class,
        FolderEntity::class,
        CaptureTagEntity::class,
        SmartCollectionEntity::class,
        // Calendar / panchanga — bundled Vontikoppal dataset table
        // seeded on first launch from `assets/panchanga_2026_27.csv`
        // via `PanchangaRepository.ensureLoaded()`.
        PanchangaEntity::class,
        // Document-attached voice notes. One row per recorded clip;
        // the .m4a lives in AttachmentStorage and the row points at
        // it through `audio_uri`. Foreign key to `captures` cascades
        // delete so removing a scan also removes its voice notes.
        VoiceNoteEntity::class,
        // User-defined locations ("Home", "Work", etc.) plus the
        // many-to-many `capture_locations` join. Seeded with "Home"
        // and "Work" on first launch (LocationRepository.seedDefaultsIfEmpty).
        LocationEntity::class,
        CaptureLocationEntity::class,
        // User-defined people ("Me", "Mom", "Dr. Rao", etc.) plus
        // the many-to-many `capture_people` join. Seeded with "Me"
        // on first launch (PersonRepository.seedDefaultsIfEmpty).
        PersonEntity::class,
        CapturePersonEntity::class,
        // Stories Phase 1 — curated narratives. `story` carries the
        // story-level metadata (title, cover, theme, share state);
        // `story_item` holds the ordered children. See design/
        // STORIES_HANDOFF.md. Mirror of iOS GRDB v14_stories.
        StoryEntity::class,
        StoryItemEntity::class,
        // Stories Phase 2 — inline voice clips, one per `story_item`
        // of `kind = 'voice_clip'`. Mirror of `voice_notes` keyed off
        // story_item_id. iOS GRDB v15_story_voice_clips.
        StoryVoiceClipEntity::class,
    ],
    // v24 — Adds `profile_settings.transcription_languages` so the
    // user's picked transcription-language allowlist (comma-separated
    // BCP-47 codes like "en,hi,kn") syncs alongside display name +
    // phone + punchline. Drives the LID → constrained transcribe
    // pipeline in `SpeechTranscriber` and its iOS counterpart.
    // Nullable; null means "user hasn't picked yet — fall back to
    // device locale + English." Mirror of iOS GRDB
    // v18_profile_settings (iOS lands the entire table at v18 since
    // it didn't carry profile_settings before this change). Room
    // rebuilds destructively under `fallbackToDestructiveMigration`
    // until real users have data.
    //
    // v23 — Adds `captures.video_drive_file_id` so the binary-sync
    // worker can mirror the raw .mp4 to Drive alongside the PDF +
    // preview JPEG, and the restore pass can re-download it on
    // other devices. Mirror of iOS GRDB v17_capture_video_drive_file_id.
    // Nullable; populated by `QuickInkBinarySync` after a
    // successful upload, nulled on tombstone cascade. Room rebuilds
    // destructively under `fallbackToDestructiveMigration` until
    // real users have data.
    //
    // v22 — Adds `captures.video_uri` so the hold-to-record Photo-
    // mode path can keep the raw .mp4 as a re-watchable artifact
    // on the detail screen. Mirror of iOS GRDB v16_capture_video_uri.
    // Nullable string; older rows + every non-Photo capture read
    // back as null. Room rebuilds destructively under
    // `fallbackToDestructiveMigration` until real users have data.
    //
    // v21 — Stories Phase 2. Adds `story_voice_clip` table for
    // inline voice clips attached to a `story_item` of
    // `kind = 'voice_clip'`. Mirror of the existing `voice_notes`
    // table but keyed off `story_item_id`. CASCADE on item delete.
    // Mirror of iOS GRDB v15_story_voice_clips.
    //
    // v20 — Stories Phase 1. Adds `story` + `story_item` tables for
    // the curated-narrative feature (see design/STORIES_HANDOFF.md).
    // `story_item.story_id` cascades on delete so removing a story
    // cleans up its items. `story.cover_item_id` is intentionally
    // NOT a Room foreign key — see the entity file for why.
    // Mirror of iOS GRDB v14_stories. Room rebuilds destructively
    // under `fallbackToDestructiveMigration` until real users have
    // data.
    //
    // v14 — Voice notes. Adds the `voice_notes` table that the
    // Document detail screen's "Voice notes" section persists into.
    // Mirror of iOS GRDB v12_voice_notes migration. Room rebuilds
    // destructively under `fallbackToDestructiveMigration` until
    // real users have data.
    //
    // v13 — Sustainability hero size-weighted score. Adds
    // `captures.paper_size` so each capture row carries its page-size
    // class (card / a4 / small), which the home screen's tree-points
    // calculation reads to weight each page differently (+4 / +2 / +1
    // pts respectively). Mirror of iOS GRDB v11_capture_paper_size.
    //
    // v12 — Calendar feature. Adds the `panchanga` table that backs
    // the standalone Calendar screen (masa / paksha / thithi /
    // special_day rows for the Vontikoppal dataset). Schema mirrors
    // the Releaf Android entity. Room rebuilds destructively under
    // `fallbackToDestructiveMigration` until real users have data.
    //
    // v11 — Workspace v1 Phase A.3c. Drops the `captures.category`
    // TEXT column. Per-capture single-label semantics now live in
    // the `capture_tags` join (materialized in A.3a). The shared
    // schema canonical record is v4_workspace.sql (the column drop
    // sits in the same migration script per the brief §2). Room
    // rebuilds destructively until real users have data.
    //
    // v10 — Workspace v1 Phase A.2. Renames `categories` table to
    // `tags`; CategoryEntity → TagEntity (package data.tag).
    // Tagging is now a many-to-many relationship via the
    // capture_tags join landed in v9. Drive payload prefix moves
    // from `categories/` to `tags/` (handled in sync code, Phase
    // A.3). UI files / string keys rename in a follow-up commit.
    //
    // v9 — Workspace v1 Phase A.1. Adds three new tables (folders,
    // capture_tags, smart_collections) for the two-axis IA + smart
    // collections (see WORKSPACE_SPEC.md + plan doc). Adds four
    // columns to `captures`: folder_id, last_opened_at,
    // last_opened_page, last_opened_device. Adds `color` to
    // `categories` (renamed to `tags` in A.2). The SQL canonical
    // record is shared/design-system/migrations/quickink/
    // v4_workspace.sql; Room rebuilds destructively here until
    // real users have data.
    //
    // v8 — adds `captures.address`, the formatted full street
    // address built from `Geocoder.getFromLocation` results
    // (Phase 7.1 — Address). Mirror of iOS GRDB
    // `v7_capture_address` migration. v7 added latitude /
    // longitude / locality / sub_locality columns to `captures`
    // so each scan can carry its capture-time geolocation. v6
    // added the `analytics_outbox` table that AnalyticsRepository
    // writes capture / identify events into for the QuickInk
    // backend (api-quickink.thoughtbasics.com). v5 — adds
    // `captures.source` ("scan" / "import") so the Library cards
    // can flag captures that came in from the system photo
    // picker rather than the document scanner. v4 — adds the
    // `profile_settings` table (display name, phone, personality
    // punchline, photo URI/Drive id) so profile state syncs
    // across devices instead of being stuck in local
    // SharedPreferences. v3 — captures.pdf_drive_file_id +
    // captures.preview_drive_file_id (Drive ids of the per-row
    // binary uploads). v2 added the categories table +
    // captures.category column. `fallbackToDestructiveMigration`
    // below handles the rebuild; when real users have data we'll
    // register real Migration objects.
    // v19 — Adds `contact_lookup_key`, `contact_phone`,
    // `contact_email`, `contact_photo_uri` to `people` so each
    // person row can optionally link to a device contact and cache
    // the snapshot fields needed to render + share without hitting
    // the contacts ContentProvider on every read. `lookup_key` +
    // `photo_uri` are device-local and stay off the wire; phone +
    // email travel in the sync payload.
    //
    // v18 — People. Adds `people` and `capture_people` tables for
    // the Home-screen people chip rail + the document-detail
    // people picker. Mirror of the location / capture_locations
    // shape: many-to-many between captures and user-defined
    // people, each row syncs independently, seeded with "Me" on
    // first launch.
    //
    // v17 — Adds `latitude`, `longitude`, `address` to `locations`
    // so each user-defined place can carry a real GPS / reverse-
    // geocoded address. Populated by the location editor's "Use
    // current location" and "Search address" affordances. All three
    // are nullable so the "Home" / "Work" seed and manual-typed
    // rows stay valid until the user sets a place.
    //
    // v16 — Locations. Adds `locations` and `capture_locations`
    // tables for the Home-screen location chip rail. Mirror of the
    // tag / capture_tags shape: many-to-many between captures and
    // user-defined places, each row syncs independently, seeded
    // with "Home" and "Work" on first launch.
    //
    // v15 — Document-level notes column on `captures` (mirror of iOS
    // GRDB v13_capture_notes). Append target for the voice-note
    // transcript editor: after a clip is recorded and the editor
    // saves, the edited text lands both on the voice note's
    // `transcription` field AND is appended here as a new paragraph
    // so the capture carries the running notes across all clips.
    // Free-form TEXT, nullable, no index — read pattern is "load the
    // whole field for the detail screen" so a full-text index isn't
    // useful yet.
    version       = 24,
    exportSchema  = true,
)
abstract class QuickInkDatabase : RoomDatabase() {

    abstract fun notepadDao():           NotepadDao
    abstract fun syncStateDao():         SyncStateDao
    abstract fun captureDao():           CaptureDao
    abstract fun ocrResultDao():         OcrResultDao
    abstract fun tagDao():               TagDao
    abstract fun profileSettingsDao():   ProfileSettingsDao
    abstract fun analyticsOutboxDao():   AnalyticsOutboxDao

    // ─── Workspace v1 (Phase A) ──────────────────────────────────
    abstract fun folderDao():            FolderDao
    abstract fun captureTagDao():        CaptureTagDao
    abstract fun smartCollectionDao():   SmartCollectionDao

    // ─── Calendar (panchanga) ────────────────────────────────────
    abstract fun panchangaDao():         PanchangaDao

    // ─── Voice notes ─────────────────────────────────────────────
    abstract fun voiceNoteDao():         VoiceNoteDao

    // ─── Locations ───────────────────────────────────────────────
    abstract fun locationDao():          LocationDao
    abstract fun captureLocationDao():   CaptureLocationDao

    // ─── People ──────────────────────────────────────────────────
    abstract fun personDao():            PersonDao
    abstract fun capturePersonDao():     CapturePersonDao

    // ─── Stories ─────────────────────────────────────────────────
    abstract fun storyDao():             StoryDao
    abstract fun storyItemDao():         StoryItemDao
    abstract fun storyVoiceClipDao():    StoryVoiceClipDao

    companion object {
        @Volatile
        private var instance: QuickInkDatabase? = null

        fun get(context: Context): QuickInkDatabase =
            instance ?: synchronized(this) {
                instance ?: Room
                    .databaseBuilder(
                        context.applicationContext,
                        QuickInkDatabase::class.java,
                        // Distinct filename from Releaf's `releaf.db`
                        // — the two apps are sandboxed so it
                        // wouldn't collide anyway, but the explicit
                        // name keeps things grep-able if someone
                        // ever browses the app data dir.
                        "quickink.db",
                    )
                    // Bundled SQLite — supplies FTS5. System SQLite
                    // isn't guaranteed to include it, which crashes
                    // Room's FTS5 virtual tables at create time.
                    // Wired via setDriver here on the same Room 2.7+
                    // path Releaf uses.
                    .setDriver(BundledSQLiteDriver())
                    .addCallback(SchemaCallback)
                    // Dogfood: wipe on @Database version bump until
                    // real user data lands. Replace with
                    // `addMigrations(...)` blocks before any real
                    // QuickInk user has data.
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { instance = it }
            }
    }

    /**
     * Installs FTS5 virtual tables + their sync triggers on first
     * create. Mirrors the Releaf SchemaCallback pattern — Room
     * 2.7+ routes onCreate through `SQLiteConnection` when a driver
     * is set (which we do above with BundledSQLiteDriver), so we
     * override only that overload. SQL copied verbatim from
     * `shared/design-system/migrations/quickink/v1_initial.sql`.
     *
     * `IF NOT EXISTS` on each statement so a destructive-migration
     * rebuild that calls onCreate again doesn't error on the
     * existing virtual tables. Triggers are scoped to per-table
     * lifecycle anyway; recreating them on a fresh DB is fine.
     */
    private object SchemaCallback : Callback() {
        override fun onCreate(connection: SQLiteConnection) {
            // ─── notepad notes FTS ─────────────────────────────
            connection.execSQL("""
                CREATE VIRTUAL TABLE IF NOT EXISTS fts_notepad_notes USING fts5(
                    notepad_entry_id UNINDEXED,
                    notes,
                    tokenize = 'unicode61 remove_diacritics 2'
                )
            """.trimIndent())

            connection.execSQL("""
                CREATE TRIGGER IF NOT EXISTS notepad_entries_fts_ai
                AFTER INSERT ON notepad_entries
                WHEN new.deleted_at IS NULL AND new.notes <> ''
                BEGIN
                    INSERT INTO fts_notepad_notes(notepad_entry_id, notes)
                    VALUES (new.id, new.notes);
                END
            """.trimIndent())

            connection.execSQL("""
                CREATE TRIGGER IF NOT EXISTS notepad_entries_fts_au
                AFTER UPDATE ON notepad_entries
                BEGIN
                    DELETE FROM fts_notepad_notes WHERE notepad_entry_id = old.id;
                    INSERT INTO fts_notepad_notes(notepad_entry_id, notes)
                    SELECT new.id, new.notes
                    WHERE new.deleted_at IS NULL AND new.notes <> '';
                END
            """.trimIndent())

            connection.execSQL("""
                CREATE TRIGGER IF NOT EXISTS notepad_entries_fts_ad
                AFTER DELETE ON notepad_entries
                BEGIN
                    DELETE FROM fts_notepad_notes WHERE notepad_entry_id = old.id;
                END
            """.trimIndent())

            // ─── OCR text FTS ──────────────────────────────────
            connection.execSQL("""
                CREATE VIRTUAL TABLE IF NOT EXISTS fts_ocr_text USING fts5(
                    ocr_result_id UNINDEXED,
                    text,
                    tokenize = 'unicode61 remove_diacritics 2'
                )
            """.trimIndent())

            connection.execSQL("""
                CREATE TRIGGER IF NOT EXISTS ocr_results_fts_ai
                AFTER INSERT ON ocr_results
                WHEN new.deleted_at IS NULL AND new.text <> ''
                BEGIN
                    INSERT INTO fts_ocr_text(ocr_result_id, text)
                    VALUES (new.id, new.text);
                END
            """.trimIndent())

            connection.execSQL("""
                CREATE TRIGGER IF NOT EXISTS ocr_results_fts_au
                AFTER UPDATE ON ocr_results
                BEGIN
                    DELETE FROM fts_ocr_text WHERE ocr_result_id = old.id;
                    INSERT INTO fts_ocr_text(ocr_result_id, text)
                    SELECT new.id, new.text
                    WHERE new.deleted_at IS NULL AND new.text <> '';
                END
            """.trimIndent())

            connection.execSQL("""
                CREATE TRIGGER IF NOT EXISTS ocr_results_fts_ad
                AFTER DELETE ON ocr_results
                BEGIN
                    DELETE FROM fts_ocr_text WHERE ocr_result_id = old.id;
                END
            """.trimIndent())
        }
    }
}
