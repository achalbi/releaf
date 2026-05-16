/*
 * VoiceNoteDao.kt
 *
 * Room DAO for `voice_notes`. Mirrors `OcrResultDao`'s shape — basic
 * CRUD, per-capture observation Flow, last-write-wins upsert path
 * for sync.
 */

package app.quickink.mobile.data.voicenote

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface VoiceNoteDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: VoiceNoteEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entity: VoiceNoteEntity): Long

    @Update
    suspend fun update(entity: VoiceNoteEntity)

    /**
     * Upsert from remote sync. Same posture as
     * [OcrResultDao.upsertFromRemote]: real UPDATE under the hood
     * (not REPLACE clobber), gated on `updated_at` so a slow restore
     * can't overwrite a faster local edit.
     */
    @Transaction
    suspend fun upsertFromRemote(entity: VoiceNoteEntity) {
        val rowId = insertIfAbsent(entity)
        if (rowId != -1L) return
        val existing = findById(entity.id) ?: return
        if (existing.updatedAt < entity.updatedAt) {
            update(entity)
        }
    }

    @Query("SELECT * FROM voice_notes WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): VoiceNoteEntity?

    /**
     * Live, oldest-first list of active voice notes for a capture.
     * Powers the section under the document detail screen.
     */
    @Query("""
        SELECT * FROM voice_notes
        WHERE capture_id = :captureId AND deleted_at IS NULL
        ORDER BY created_at ASC
    """)
    fun observeForCapture(captureId: String): Flow<List<VoiceNoteEntity>>

    @Query("""
        SELECT * FROM voice_notes
        WHERE capture_id = :captureId AND deleted_at IS NULL
        ORDER BY created_at ASC
    """)
    suspend fun listForCapture(captureId: String): List<VoiceNoteEntity>

    @Query("""
        UPDATE voice_notes
        SET transcription = :text,
            transcription_source = :source,
            updated_at = :now,
            dirty = 1
        WHERE id = :id
    """)
    suspend fun setTranscription(id: String, text: String?, source: String?, now: String)

    @Query("""
        UPDATE voice_notes
        SET deleted_at = :now,
            updated_at = :now,
            dirty = 1
        WHERE id = :id
    """)
    suspend fun softDelete(id: String, now: String)

    @Query("SELECT * FROM voice_notes WHERE dirty = 1")
    suspend fun dirtyRows(): List<VoiceNoteEntity>

    /** Live rows for the given user. Used by the sync push path. */
    @Query("""
        SELECT * FROM voice_notes
        WHERE user_id = :userId AND deleted_at IS NULL
        ORDER BY created_at ASC
    """)
    suspend fun activeRows(userId: String): List<VoiceNoteEntity>

    /** Audio binary not yet pushed — driven by `QuickInkBinarySync`. */
    @Query("""
        SELECT * FROM voice_notes
        WHERE user_id = :userId
          AND deleted_at IS NULL
          AND audio_drive_file_id IS NULL
    """)
    suspend fun rowsMissingAudioUpload(userId: String): List<VoiceNoteEntity>

    /** Rows whose local audio file is missing but Drive has it. */
    @Query("""
        SELECT * FROM voice_notes
        WHERE user_id = :userId
          AND deleted_at IS NULL
          AND audio_drive_file_id IS NOT NULL
    """)
    suspend fun rowsWithRemoteAudio(userId: String): List<VoiceNoteEntity>

    @Query("""
        UPDATE voice_notes
        SET drive_file_id = :driveFileId,
            updated_at = :updatedAt,
            dirty = 0
        WHERE id = :id
          AND (updated_at = :updatedAt OR dirty = 1)
    """)
    suspend fun markSynced(id: String, driveFileId: String, updatedAt: String)

    @Query("""
        UPDATE voice_notes
        SET audio_drive_file_id = :driveFileId
        WHERE id = :id
    """)
    suspend fun markAudioSynced(id: String, driveFileId: String)

    /**
     * Clear the dirty flag once a tombstone push for this row has
     * been ack'd on Drive. Mirror of `CaptureDao.markTombstoneSynced`
     * — only mutates when there's a deleted_at, so calling it on a
     * non-tombstoned row is a safe no-op.
     */
    @Query("""
        UPDATE voice_notes
        SET dirty = 0
        WHERE id = :id AND deleted_at IS NOT NULL
    """)
    suspend fun markTombstoneSynced(id: String)

    @Query("""
        UPDATE voice_notes
        SET audio_uri = :uri
        WHERE id = :id
    """)
    suspend fun setAudioUri(id: String, uri: String)
}
