/*
 * PerspectiveEntity.kt
 *
 * User-managed perspective (a.k.a. "context" in OmniFocus-speak) —
 * a named filter surface on the Tasks screen. Tasks belong to a
 * perspective when their title contains a matching `@name` tag
 * (parsed via the shared `extractContext` helper in TasksScreen);
 * this entity is the persistent side of that pairing so a user can
 * keep a "@personal" tile even when no tasks currently use it, and
 * can remove tiles for tags they no longer care about.
 *
 * Tasks are NOT joined to perspectives by foreign key — the link is
 * purely the @tag in the title, which keeps the task schema stable
 * and means a task can (in principle) show up in multiple tiles
 * later. For today a task has at most one @tag at a time.
 */

package app.releaf.mobile.data.perspective

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "perspectives",
    indices = [
        Index("user_id"),
        Index("name"),
        Index("deleted_at"),
    ],
)
data class PerspectiveEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    /** Scoping column — perspectives are per-user. */
    @ColumnInfo(name = "user_id")
    val userId: String,

    /**
     * The `@tag` key (lower-case, slug-safe — `[a-z0-9_-]`). Also the
     * matcher used against task titles via `extractContext`. Uniquely
     * identifies the perspective within a user; enforced in the
     * repository layer (`findByName` before insert) since SQLite's
     * unique indexes on soft-deleted rows would need partial index
     * support we don't rely on.
     */
    @ColumnInfo(name = "name")
    val name: String,

    /**
     * Which icon to show on the tile. One of a small hand-picked set
     * (see `PerspectiveIcon` / the `iconForKey` mapper on the UI
     * side) — defaults to the generic "label" tag if unspecified.
     */
    @ColumnInfo(name = "icon_key", defaultValue = "label")
    val iconKey: String = "label",

    /**
     * Ascending sort key for the tile row; lower values render first.
     * The repository assigns `count(active)` at insert time so new
     * tiles land at the end of the row without the UI having to
     * think about ordering.
     */
    @ColumnInfo(name = "sort_order", defaultValue = "0")
    val sortOrder: Int = 0,

    /** ISO-8601 UTC with ms. See IsoClock. */
    @ColumnInfo(name = "created_at")
    val createdAt: String,

    @ColumnInfo(name = "updated_at")
    val updatedAt: String,

    /** ISO-8601 UTC when soft-deleted; null = active. */
    @ColumnInfo(name = "deleted_at")
    val deletedAt: String? = null,
)
