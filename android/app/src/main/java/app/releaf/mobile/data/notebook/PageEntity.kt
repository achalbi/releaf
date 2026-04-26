/*
 * PageEntity.kt
 *
 * Room entity mirroring the `pages` table in
 * `design-system/migrations/v1_initial.sql`. A page is the authored leaf of
 * the notebook → chapter → page hierarchy and carries the note body that
 * FTS5 indexes (`fts_page_notes`).
 *
 * This file only models the page row itself. The sibling capture rows
 * (photos, voice notes, scans) live in the `captures` table, which will be
 * modeled in a later phase — today the page just stores its title + markdown
 * notes plus the conflict-resolution stub for sync.
 */

package app.releaf.mobile.data.notebook

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "pages",
    indices = [
        // Primary list-pattern: pages within a chapter, ordered by position.
        Index(value = ["chapter_id", "position"]),
        Index("project_id"),
        Index("updated_at"),
        Index("deleted_at"),
        Index("archived_at"),
    ],
)
data class PageEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "chapter_id")
    val chapterId: String,

    @ColumnInfo(name = "project_id")
    val projectId: String? = null,

    @ColumnInfo(name = "template_id")
    val templateId: String? = null,

    /** Nullable — the UI falls back to a derived default if absent. */
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
     * JSON array of TodoItem. Supplementary to any checklist items the
     * user writes inline in the notes body — these are first-class
     * tasks surfaced in the TODOS section of the page editor.
     */
    @ColumnInfo(name = "todos", defaultValue = "'[]'")
    val todos: String = "[]",

    /**
     * JSON array of Attachment (photos + scans). Kept on-page as JSON
     * rather than a separate `captures` table for now — see
     * PageAttachments.kt for the trade-off.
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
     * JSON array of SubPage — the horizontal sub-pages the user swipes
     * between inside this page. Source of truth for notes + strokes
     * once populated. Empty string `'[]'` means "this is a legacy row
     * with content in the flat `notes` + `sketch_strokes` columns" —
     * the VM synthesizes a single sub-page from them on load.
     */
    @ColumnInfo(name = "sub_pages", defaultValue = "'[]'")
    val subPages: String = "[]",

    @ColumnInfo(name = "position", defaultValue = "1024")
    val position: Long = 1024L,

    /** JSON: `{local_notes, remote_notes, remote_updated_at}` or null. */
    @ColumnInfo(name = "conflict_stub")
    val conflictStub: String? = null,

    @ColumnInfo(name = "drive_file_id")
    val driveFileId: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: String,

    @ColumnInfo(name = "updated_at")
    val updatedAt: String,

    @ColumnInfo(name = "dirty", defaultValue = "1")
    val dirty: Boolean = true,

    @ColumnInfo(name = "deleted_at")
    val deletedAt: String? = null,

    /**
     * JSON array of free-form tag strings. Added in v17 — never
     * null at the row level (Migration16To17 backfills `'[]'` and
     * the column default keeps it that way for new rows). Drive
     * surfaces tag pills under the page title.
     */
    @ColumnInfo(name = "tags", defaultValue = "'[]'")
    val tags: String = "[]",

    /**
     * JSON array of `{id, body, createdAt}` objects — the typed
     * `Note` collection on the Drive-facing `Page` model. Added in
     * v18 so each note round-trips with its own id + timestamp
     * instead of being collapsed into a single markdown blob in
     * `notes`. The `notes` column still carries the joined
     * markdown for FTS indexing; this column is the source of
     * truth for note identity.
     */
    @ColumnInfo(name = "page_notes_json", defaultValue = "'[]'")
    val pageNotesJson: String = "[]",

    /**
     * Soft-archive timestamp (separate from `deletedAt`). Added in
     * v17 — non-nil means the page lives in the archive bin and is
     * filtered out of the active page list. Independent of
     * `deletedAt` (true tombstone).
     */
    @ColumnInfo(name = "archived_at")
    val archivedAt: String? = null,
)
