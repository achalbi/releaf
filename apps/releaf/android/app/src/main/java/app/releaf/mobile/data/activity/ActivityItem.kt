/*
 * ActivityItem.kt
 *
 * Phase-1 activity feed primitive — derived view, not a stored entity.
 * The feed is computed from the `updated_at` columns already on the
 * notepad / page / chapter / notebook tables; nothing new lands on
 * disk. When (and if) phase 2 lands a real `audit_events` table this
 * type stays unchanged — only the source switches.
 *
 * Each row carries enough metadata for the timeline UI to render an
 * icon, label, time, and a tap target without re-querying. The
 * `entityId` keeps the row navigable.
 */

package app.releaf.mobile.data.activity

/**
 * Which side of the app the row originated from. Drives the icon /
 * accent palette in the timeline UI. The `Photo / Scan / Voice /
 * Todo / Contact / Location` variants are sub-event captures
 * (phase 3.5) — the parent entity (NotepadEntry or Page) is
 * referenced via `entityId`, the captured item label sits in
 * `title`, and the breadcrumb in `context`.
 */
enum class ActivityKind {
    NotepadEntry,
    Page,
    Chapter,
    Notebook,
    Photo,
    Scan,
    Voice,
    Todo,
    Contact,
    Location,
}

/**
 * Audit-log action ladder. Phase 2 expanded this from
 * Created/Updated/Deleted to include Restored / Merged / Moved as
 * the consuming repos started emitting those explicitly. Add new
 * values — never remove existing ones (the audit table stores the
 * string name).
 */
enum class ActivityAction {
    Created,
    Updated,
    Deleted,
    Restored,
    Merged,
    Moved,
}

/**
 * One row in the activity feed.
 *
 * @param id stable identity for LazyColumn keys; combines kind + entity id + action.
 * @param entityId the underlying notepad/page/chapter/notebook id (or, for sub-event
 *   captures, the parent entity id) — used for navigation.
 * @param timestamp ISO-8601 UTC with ms.
 * @param title human label rendered in the row.
 * @param context breadcrumb-style hierarchy string for sub-event captures
 *   (e.g. "Releaf garden › Chapter 1 › Page A"); null for entity-level events.
 */
data class ActivityItem(
    val id: String,
    val kind: ActivityKind,
    val action: ActivityAction,
    val entityId: String,
    val timestamp: String,
    val title: String,
    val context: String? = null,
)
