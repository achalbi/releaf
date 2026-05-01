/*
 * RecentActivityRepository.kt
 *
 * Phase-2 source — reads the real `audit_events` table written by
 * the four user-facing repositories on every mutation. Phase-1's
 * "combine over updated_at columns" approach is preserved as the
 * backfill path for the very first launch (so an existing user with
 * a populated database doesn't see a blank timeline before the new
 * audit log accumulates fresh events).
 *
 * The public Flow shape (Flow<List<ActivityItem>>) is unchanged
 * from phase 1 — UI consumers (HomeTimelineCard, ActivityScreen)
 * keep working without changes.
 */

package app.releaf.mobile.data.activity

import app.releaf.mobile.data.common.Uuidv7
import app.releaf.mobile.data.notebook.ChapterDao
import app.releaf.mobile.data.notebook.NotebookDao
import app.releaf.mobile.data.notebook.PageDao
import app.releaf.mobile.data.notepad.NotepadDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RecentActivityRepository(
    private val auditDao: AuditDao,
    private val notepadDao: NotepadDao,
    private val pageDao: PageDao,
    private val chapterDao: ChapterDao,
    private val notebookDao: NotebookDao,
) {
    /**
     * Live feed for the signed-in user. Reads directly from
     * `audit_events`, capped to [maxItems], newest first.
     *
     * Sub-mutations (todo toggle, contact add) still don't appear
     * separately in phase 2 — those would require richer audit
     * actions in the editor VMs (phase 3, optional).
     */
    fun observe(userId: String, maxItems: Int = 50): Flow<List<ActivityItem>> =
        auditDao.observe(userId, maxItems).map { events ->
            events.map { it.toActivityItem() }
        }

    /**
     * Idempotent first-launch seed. Walks the four entity tables and
     * synthesizes one `Created` event per live row, tagged
     * `source = "system"` so future filters can exclude backfill
     * noise. No-op when the audit log already has any rows for the
     * user.
     *
     * Called once per process from ReleafApp.onCreate() inside an
     * appScope launch — runs in the background; the timeline will
     * fill in as the rows land.
     */
    suspend fun backfillIfEmpty(userId: String) {
        if (auditDao.countForUser(userId) > 0) return
        val events = buildList<AuditEvent> {
            notepadDao.activeRows(userId).forEach { e ->
                add(syntheticCreated(
                    userId     = e.userId,
                    timestamp  = e.createdAt,
                    entityType = AuditEntity.NotepadEntry,
                    entityId   = e.id,
                    title      = e.title ?: e.entryDate,
                ))
            }
            pageDao.activeRows().forEach { p ->
                add(syntheticCreated(
                    userId     = userId,
                    timestamp  = p.createdAt,
                    entityType = AuditEntity.Page,
                    entityId   = p.id,
                    title      = p.title ?: "Untitled page",
                ))
            }
            chapterDao.activeRows().forEach { c ->
                add(syntheticCreated(
                    userId     = userId,
                    timestamp  = c.createdAt,
                    entityType = AuditEntity.Chapter,
                    entityId   = c.id,
                    title      = c.title.ifBlank { "Untitled chapter" },
                ))
            }
            notebookDao.activeRows().forEach { n ->
                add(syntheticCreated(
                    userId     = userId,
                    timestamp  = n.createdAt,
                    entityType = AuditEntity.Notebook,
                    entityId   = n.id,
                    title      = n.title.ifBlank { "Untitled notebook" },
                ))
            }
        }
        if (events.isNotEmpty()) {
            auditDao.insertAll(events)
        }
    }

    /**
     * Drop events older than [retentionDays] days back from now.
     * `null` means "keep forever" — caller short-circuits there.
     * Returns rows affected so the worker can surface the count for
     * telemetry. Idempotent and cheap; safe to run on every
     * scheduled tick.
     */
    suspend fun prune(retentionDays: Int?): Int {
        if (retentionDays == null) return 0
        val cutoff = java.time.Instant.now()
            .minus(retentionDays.toLong(), java.time.temporal.ChronoUnit.DAYS)
            .toString()
        return auditDao.pruneOlderThan(cutoff)
    }

    /**
     * One-shot total — feeds the Settings ▸ Activity card so the
     * user can see how much they're storing.
     */
    suspend fun countForUser(userId: String): Int = auditDao.countForUser(userId)

    /**
     * Wipe every audit row for a user. Returns rows deleted. The
     * backfill is idempotent against `count == 0`, so calling
     * `backfillIfEmpty` after a clear re-seeds the log from current
     * entity state — that's the intended "reset and start fresh"
     * behavior.
     */
    suspend fun clearForUser(userId: String): Int =
        auditDao.deleteAllForUser(userId)

    private fun syntheticCreated(
        userId: String,
        timestamp: String,
        entityType: String,
        entityId: String,
        title: String,
    ) = AuditEvent(
        id         = Uuidv7.generate(),
        userId     = userId,
        timestamp  = timestamp,
        action     = AuditAction.Created.name,
        entityType = entityType,
        entityId   = entityId,
        title      = title,
        source     = "system",
        // Backfill rows are local-only. If we ever sync the audit
        // log to Drive, mark them dirty=0 to keep the seed scoped
        // to the device that ran the migration.
        dirty      = false,
    )
}

/* ---------- AuditEvent → ActivityItem mapping ---------- */

private fun AuditEvent.toActivityItem(): ActivityItem = ActivityItem(
    id        = id,
    kind      = when (entityType) {
        AuditEntity.NotepadEntry -> ActivityKind.NotepadEntry
        AuditEntity.Page         -> ActivityKind.Page
        AuditEntity.Chapter      -> ActivityKind.Chapter
        AuditEntity.Notebook     -> ActivityKind.Notebook
        AuditEntity.Photo        -> ActivityKind.Photo
        AuditEntity.Scan         -> ActivityKind.Scan
        AuditEntity.Voice        -> ActivityKind.Voice
        AuditEntity.Todo         -> ActivityKind.Todo
        AuditEntity.Contact      -> ActivityKind.Contact
        AuditEntity.Location     -> ActivityKind.Location
        else                     -> ActivityKind.NotepadEntry
    },
    action    = runCatching { ActivityAction.valueOf(action) }
        .getOrDefault(ActivityAction.Updated),
    entityId  = entityId,
    timestamp = timestamp,
    title     = title ?: "Untitled",
    context   = context,
)
