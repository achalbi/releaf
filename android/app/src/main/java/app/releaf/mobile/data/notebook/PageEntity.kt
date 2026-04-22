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
     */
    @ColumnInfo(name = "sketch_strokes", defaultValue = "'[]'")
    val sketchStrokes: String = "[]",

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
)
