/*
 * AuditLogger.kt
 *
 * Thin facade the four user-facing repositories call after every
 * successful mutation. Owns:
 *   - userId resolution (via AuthStore — entities like NotebookEntity
 *     don't carry user_id, so the logger pulls the active user at
 *     log-time)
 *   - action enum → string mapping
 *   - timestamp + uuidv7 generation
 *
 * Failures here are swallowed — losing an audit event must NOT
 * propagate up and roll back the underlying mutation. The log is
 * always best-effort; sync (phase 3) catches missed cross-device
 * events anyway.
 */

package app.releaf.mobile.data.activity

import app.releaf.mobile.auth.AuthState
import app.releaf.mobile.auth.AuthStore
import app.releaf.mobile.data.common.IsoClock
import app.releaf.mobile.data.common.Uuidv7

/**
 * Action ladder. Add new values — never remove existing ones, since
 * stored events use the string name. Older clients that don't know
 * a new value just render it as the raw string.
 */
enum class AuditAction { Created, Updated, Deleted, Restored, Merged, Moved }

/** Logical entity names — kept as constants so call sites don't typo. */
object AuditEntity {
    // Container entities — create / update / delete events.
    const val NotepadEntry = "notepad_entry"
    const val Page         = "page"
    const val Chapter      = "chapter"
    const val Notebook     = "notebook"

    // Sub-event captures — written from editor VMs when the user
    // adds a single piece of content to a container. `entityId`
    // points at the parent (notepad entry or page) so a tap can
    // navigate. `title` snapshots the captured item label, `context`
    // snapshots the parent breadcrumb.
    const val Photo    = "photo"
    const val Scan     = "scan"
    const val Voice    = "voice"
    const val Todo     = "todo"
    const val Contact  = "contact"
    const val Location = "location"
}

class AuditLogger(
    private val dao: AuditDao,
    private val authStore: AuthStore,
) {
    /**
     * Log one event. `userId` lets callers override the resolution —
     * NotepadEntry.userId is the source of truth for notepad
     * mutations, so we pass it explicitly. For entities without a
     * user_id column we fall back to the active session.
     *
     * Drops the event silently when no user is signed in (orphan
     * events would never be readable anyway since the feed is
     * user-scoped).
     */
    suspend fun log(
        action: AuditAction,
        entityType: String,
        entityId: String,
        title: String? = null,
        userId: String? = null,
        source: String = "user",
        context: String? = null,
    ) {
        val resolvedUserId = userId
            ?: (authStore.state.value as? AuthState.SignedIn)?.session?.userId
            ?: return
        runCatching {
            dao.insert(
                AuditEvent(
                    id         = Uuidv7.generate(),
                    userId     = resolvedUserId,
                    timestamp  = IsoClock.nowIso(),
                    action     = action.name,
                    entityType = entityType,
                    entityId   = entityId,
                    title      = title,
                    source     = source,
                    dirty      = true,
                    context    = context,
                ),
            )
        }
        // .runCatching swallows errors — losing an audit row must
        // never roll back a successful mutation.
    }
}
