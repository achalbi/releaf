/*
 * AuditEvent.kt
 *
 * Phase-2 of the activity tracker — a real append-only event log,
 * not a derived view over `updated_at` columns. Every successful
 * mutation through the four user-facing repositories (notepad / page
 * / chapter / notebook) writes one row here.
 *
 * Sortable by id (uuidv7 sorts chronologically), but we also store
 * the explicit `timestamp` so queries can `ORDER BY timestamp DESC`
 * without parsing the id. Soft-delete of the SOURCE entity does NOT
 * delete the corresponding audit rows — the log is kept past entity
 * lifecycles so users can see "deleted notebook X" historically.
 *
 * Pruning runs on a retention window (default 365 days) — see
 * AuditRepository.pruneOlderThan. Hard-deleted by id; no soft-delete
 * because the audit log itself doesn't need an audit log.
 */

package app.releaf.mobile.data.activity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "audit_events",
    indices = [
        Index("user_id"),
        Index("timestamp"),
        Index(value = ["entity_type", "entity_id"]),
    ],
)
data class AuditEvent(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "user_id")
    val userId: String,

    /** ISO-8601 UTC with ms — same format as the rest of the schema. */
    @ColumnInfo(name = "timestamp")
    val timestamp: String,

    /**
     * What happened. Stored as the enum's string name so adding new
     * actions is additive — older clients see unrecognized values
     * and fall back to "Updated" in the UI.
     */
    @ColumnInfo(name = "action")
    val action: String,

    /**
     * Which family of entity changed. Free-form lowercase string
     * (notepad_entry / page / chapter / notebook today) so future
     * entity types can be added without a schema change.
     */
    @ColumnInfo(name = "entity_type")
    val entityType: String,

    @ColumnInfo(name = "entity_id")
    val entityId: String,

    /**
     * Human-readable label for the row at the time of the event —
     * notebook title, notepad title, etc. Snapshot, not a join.
     * This means deleting the source entity doesn't strip the audit
     * row's label; the user still sees "Deleted notebook My Garden"
     * after the row is gone.
     */
    @ColumnInfo(name = "title")
    val title: String? = null,

    /**
     * Where the event came from — `user` for direct mutations,
     * `sync` for changes pulled from Drive, `system` for migrations
     * / backfills. Lets the UI hide system-noise events on demand.
     */
    @ColumnInfo(name = "source", defaultValue = "'user'")
    val source: String = "user",

    /**
     * Sync bookkeeping mirrors the other tables — phase 2 keeps
     * audit local-only, but the column lands now so a future Drive
     * pipeline doesn't need a schema change.
     */
    @ColumnInfo(name = "dirty", defaultValue = "1")
    val dirty: Boolean = true,

    /**
     * Free-form display string giving the user hierarchy context for
     * the event — e.g. "Releaf garden › Chapter 1 › Page A" for a
     * page-level photo add, or the entry date for a notepad capture.
     * Snapshotted at log-time so it survives parent renames.
     *
     * Optional: entity-level events (notebook created, etc.) leave it
     * null since the title alone is enough.
     */
    @ColumnInfo(name = "context")
    val context: String? = null,
)
