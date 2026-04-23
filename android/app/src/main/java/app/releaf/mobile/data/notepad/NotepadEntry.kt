/*
 * NotepadEntry.kt
 *
 * Room entity mirroring the `notepad_entries` table in
 * `design-system/migrations/v1_initial.sql`. Column names are the snake_case
 * form from the schema — Room maps them to camelCase Kotlin fields via
 * @ColumnInfo.
 *
 * Schema-source-of-truth gap: the design doc says v1_initial.sql is the
 * canonical schema and platform adapters "execute it verbatim". Today Room
 * generates its own DDL from these annotations instead. Column shapes match,
 * so queries are portable, but the CHECK constraints, FTS5 virtual table, and
 * triggers defined in the .sql file are NOT yet replicated. Tracked as a
 * follow-up: swap Room's schema generation for a RoomDatabase.Callback that
 * runs the .sql file verbatim, and export the schema in ./schemas so CI can
 * diff against it.
 */

package app.releaf.mobile.data.notepad

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "notepad_entries",
    indices = [
        Index("user_id"),
        Index("entry_date"),
        Index("updated_at"),
        Index("deleted_at"),
    ],
)
data class NotepadEntry(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "user_id")
    val userId: String,

    /** YYYY-MM-DD (local date the entry is filed under). */
    @ColumnInfo(name = "entry_date")
    val entryDate: String,

    @ColumnInfo(name = "project_id")
    val projectId: String? = null,

    @ColumnInfo(name = "title")
    val title: String? = null,

    /** Canonical CommonMark. Empty string is valid. */
    @ColumnInfo(name = "notes", defaultValue = "''")
    val notes: String = "",

    /** JSON array of Contact. Always a JSON string, never null. */
    @ColumnInfo(name = "contacts", defaultValue = "'[]'")
    val contacts: String = "[]",

    /** JSON array of Location. Always a JSON string, never null. */
    @ColumnInfo(name = "locations", defaultValue = "'[]'")
    val locations: String = "[]",

    /**
     * JSON array of TodoItem. Same shape as the pages table's `todos`
     * column — surfaced by the notepad editor's TODOS section.
     */
    @ColumnInfo(name = "todos", defaultValue = "'[]'")
    val todos: String = "[]",

    /**
     * JSON array of Attachment (photos + scans). Same shape as the pages
     * table's `attachments` column — parity keeps the editor UI and the
     * sync payload identical across the two surfaces.
     */
    @ColumnInfo(name = "attachments", defaultValue = "'[]'")
    val attachments: String = "[]",

    /**
     * JSON array of Stroke (freehand pen/highlighter strokes overlaying the
     * notes body). Coordinates are pinned — strokes don't reflow when text
     * edits push content around. See Stroke in PageAttachments.kt.
     *
     * Deprecated path now: when `sub_pages` carries content, it owns the
     * stroke list per sub-page. This column is kept in sync with the first
     * sub-page's strokes for back-compat and for rows that pre-date v3.
     */
    @ColumnInfo(name = "sketch_strokes", defaultValue = "'[]'")
    val sketchStrokes: String = "[]",

    /**
     * JSON array of SubPage — horizontal sub-pages inside this entry.
     * See PageEntity's twin field for the full contract.
     */
    @ColumnInfo(name = "sub_pages", defaultValue = "'[]'")
    val subPages: String = "[]",

    /** Allow empty notes + no contacts + no locations on save. */
    @ColumnInfo(name = "allow_blank_content", defaultValue = "0")
    val allowBlankContent: Boolean = false,

    /** JSON: `{local_notes, remote_notes, remote_updated_at}` or null. */
    @ColumnInfo(name = "conflict_stub")
    val conflictStub: String? = null,

    @ColumnInfo(name = "drive_file_id")
    val driveFileId: String? = null,

    /** ISO-8601 UTC with ms (e.g. `2026-04-21T10:15:30.123Z`). */
    @ColumnInfo(name = "created_at")
    val createdAt: String,

    @ColumnInfo(name = "updated_at")
    val updatedAt: String,

    /** 1 = needs upload to Drive; cleared by sync worker. */
    @ColumnInfo(name = "dirty", defaultValue = "1")
    val dirty: Boolean = true,

    /** ISO-8601 UTC when soft-deleted; null = active. */
    @ColumnInfo(name = "deleted_at")
    val deletedAt: String? = null,
)
