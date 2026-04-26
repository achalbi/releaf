/*
 * CallHistoryRepository.kt
 *
 * Thin persistence layer over the Room DAO. Exposes a `Flow` of
 * call-history entries for a user, plus the write primitives that
 * `CallObserver` uses to back-fill connect / end timestamps as the
 * OS reports them.
 */

package app.releaf.mobile.data.callhistory

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.util.UUID

data class CallHistoryRecord(
    val id: String,
    val userId: String,
    val contactName: String,
    val phoneNumber: String,
    val source: Source,
    val startedAt: Instant,
    val connectedAt: Instant?,
    val endedAt: Instant?,
    val durationSeconds: Long?,
) {
    enum class Source { APP, DEVICE }

    /** True once the OS reported IDLE after a prior OFFHOOK. */
    val isComplete: Boolean get() = endedAt != null && connectedAt != null

    /** True for dials that never reached a connected state (ringout, cancelled). */
    val wasMissedOrCancelled: Boolean get() = endedAt != null && connectedAt == null
}

class CallHistoryRepository(
    private val dao: CallHistoryDao,
) {

    fun observeAll(userId: String): Flow<List<CallHistoryRecord>> =
        dao.observeAll(userId).map { rows -> rows.map { it.toRecord() } }

    /**
     * Insert a fresh "started" row. Returns the generated id so the
     * caller can hand it to [CallObserver] for live updates as the
     * call progresses.
     */
    suspend fun recordStarted(
        userId: String,
        contactName: String,
        phoneNumber: String,
        source: CallHistoryRecord.Source,
    ): String {
        val id = UUID.randomUUID().toString()
        dao.insert(
            CallHistoryEntity(
                id            = id,
                userId        = userId,
                contactName   = contactName.ifBlank { phoneNumber },
                phoneNumber   = phoneNumber,
                source        = source.toDb(),
                startedAt     = Instant.now().toString(),
            )
        )
        return id
    }

    /** Back-fill `connected_at` once the OS reports OFFHOOK. */
    suspend fun recordConnected(id: String) {
        val existing = dao.find(id) ?: return
        if (existing.connectedAt != null) return
        dao.update(existing.copy(connectedAt = Instant.now().toString()))
    }

    /**
     * Back-fill `ended_at` and compute the cached duration. If we
     * never saw a connect, we still record the ended timestamp so
     * the row stops being "in progress" — duration stays null so
     * the UI can surface "missed / cancelled".
     */
    suspend fun recordEnded(id: String) {
        val existing = dao.find(id) ?: return
        if (existing.endedAt != null) return
        val endedAt = Instant.now()
        val connectedAt = existing.connectedAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val durationSeconds = connectedAt?.let {
            (endedAt.epochSecond - it.epochSecond).coerceAtLeast(0L)
        }
        dao.update(
            existing.copy(
                endedAt         = endedAt.toString(),
                durationSeconds = durationSeconds,
            )
        )
    }

    suspend fun delete(id: String) = dao.delete(id)

    suspend fun deleteAll(userId: String) = dao.deleteAll(userId)
}

private fun CallHistoryRecord.Source.toDb(): String = when (this) {
    CallHistoryRecord.Source.APP    -> "app"
    CallHistoryRecord.Source.DEVICE -> "device"
}

private fun String.toSource(): CallHistoryRecord.Source = when (this) {
    "app"    -> CallHistoryRecord.Source.APP
    "device" -> CallHistoryRecord.Source.DEVICE
    else     -> CallHistoryRecord.Source.APP
}

private fun CallHistoryEntity.toRecord(): CallHistoryRecord = CallHistoryRecord(
    id              = id,
    userId          = userId,
    contactName     = contactName,
    phoneNumber     = phoneNumber,
    source          = source.toSource(),
    startedAt       = Instant.parse(startedAt),
    connectedAt     = connectedAt?.let { runCatching { Instant.parse(it) }.getOrNull() },
    endedAt         = endedAt?.let { runCatching { Instant.parse(it) }.getOrNull() },
    durationSeconds = durationSeconds,
)
