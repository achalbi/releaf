/*
 * VoiceNoteRepository.kt
 *
 * Persistence + observation for voice notes attached to a capture.
 * Mirror of iOS's `VoiceNoteRepository.swift` — same insert / update
 * / soft-delete contract, plus a Flow-based reactive observer.
 *
 * Lifecycle:
 *   - insert(...)         → recorder commits, row lands dirty.
 *   - setTranscription(...) → fills in (or clears) the transcript.
 *   - softDelete(id)      → tombstones so the next sync push
 *                           propagates the delete.
 *   - observeForCapture() → live list for the section UI.
 */

package app.quickink.mobile.data.voicenote

import app.releaf.mobile.data.common.IsoClock
import app.releaf.mobile.data.common.Uuidv7
import kotlinx.coroutines.flow.Flow

class VoiceNoteRepository(
    private val voiceNoteDao: VoiceNoteDao,
) {

    fun observeForCapture(captureId: String): Flow<List<VoiceNoteEntity>> =
        voiceNoteDao.observeForCapture(captureId)

    suspend fun findById(id: String): VoiceNoteEntity? =
        voiceNoteDao.findById(id)

    suspend fun insert(
        captureId: String,
        userId: String,
        audioUri: String,
        durationMs: Long,
    ): VoiceNoteEntity {
        val now = IsoClock.nowIso()
        val entity = VoiceNoteEntity(
            id            = Uuidv7.generate(),
            captureId     = captureId,
            userId        = userId,
            audioUri      = audioUri,
            durationMs    = durationMs,
            transcription = null,
            createdAt     = now,
            updatedAt     = now,
            dirty         = true,
            deletedAt     = null,
        )
        voiceNoteDao.insert(entity)
        return entity
    }

    suspend fun setTranscription(id: String, text: String?, source: String?) {
        voiceNoteDao.setTranscription(id, text, source, IsoClock.nowIso())
    }

    suspend fun softDelete(id: String) {
        voiceNoteDao.softDelete(id, IsoClock.nowIso())
    }
}
