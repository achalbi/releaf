/*
 * StoryVoiceClipDao.kt
 *
 * Room DAO for `story_voice_clip`. Phase 2 surface: insert / list-
 * for-item / set-transcription / soft-delete. Drive sync wiring
 * lives in `QuickInkSyncDataSource` and follows the existing
 * voice_notes precedent.
 */

package app.quickink.mobile.data.storyvoiceclip

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface StoryVoiceClipDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: StoryVoiceClipEntity): Long

    @Update
    suspend fun update(entity: StoryVoiceClipEntity)

    @Query("SELECT * FROM story_voice_clip WHERE id = :id AND deleted_at IS NULL LIMIT 1")
    suspend fun findById(id: String): StoryVoiceClipEntity?

    @Query("""
        SELECT * FROM story_voice_clip
        WHERE story_item_id = :storyItemId AND deleted_at IS NULL
        ORDER BY created_at ASC
        LIMIT 1
    """)
    suspend fun findForItem(storyItemId: String): StoryVoiceClipEntity?

    @Query("""
        SELECT * FROM story_voice_clip
        WHERE story_item_id = :storyItemId AND deleted_at IS NULL
        ORDER BY created_at ASC
    """)
    fun observeForItem(storyItemId: String): Flow<List<StoryVoiceClipEntity>>

    @Query("""
        UPDATE story_voice_clip
        SET transcription = :transcription,
            transcription_source = :source,
            updated_at = :timestamp,
            dirty = 1
        WHERE id = :id
    """)
    suspend fun setTranscription(
        id: String,
        transcription: String?,
        source: String?,
        timestamp: String,
    )

    @Query("""
        UPDATE story_voice_clip
        SET deleted_at = :timestamp, updated_at = :timestamp, dirty = 1
        WHERE id = :id
    """)
    suspend fun softDelete(id: String, timestamp: String)

    // ── Sync surface ────────────────────────────────────────────

    @Query("SELECT * FROM story_voice_clip WHERE dirty = 1")
    suspend fun dirtyRows(): List<StoryVoiceClipEntity>

    @Query("""
        SELECT * FROM story_voice_clip
        WHERE user_id = :userId AND deleted_at IS NULL
        ORDER BY created_at ASC
    """)
    suspend fun activeRows(userId: String): List<StoryVoiceClipEntity>

    /** Audio binary not yet pushed — driven by `QuickInkBinarySync`. */
    @Query("""
        SELECT * FROM story_voice_clip
        WHERE user_id = :userId
          AND deleted_at IS NULL
          AND audio_drive_file_id IS NULL
    """)
    suspend fun rowsMissingAudioUpload(userId: String): List<StoryVoiceClipEntity>

    /** Audio binary uploaded to Drive — used by the restore pass to
     *  download any rows whose local .m4a is missing. */
    @Query("""
        SELECT * FROM story_voice_clip
        WHERE user_id = :userId
          AND deleted_at IS NULL
          AND audio_drive_file_id IS NOT NULL
    """)
    suspend fun rowsWithRemoteAudio(userId: String): List<StoryVoiceClipEntity>

    @Query("""
        UPDATE story_voice_clip
        SET audio_drive_file_id = :driveFileId, updated_at = :now, dirty = 0
        WHERE id = :id
    """)
    suspend fun markAudioSynced(id: String, driveFileId: String, now: String)

    @Query("""
        UPDATE story_voice_clip
        SET audio_uri = :uri, updated_at = :now
        WHERE id = :id
    """)
    suspend fun setAudioUri(id: String, uri: String, now: String)

    @Transaction
    suspend fun upsertFromRemote(entity: StoryVoiceClipEntity) {
        val rowId = insert(entity)
        if (rowId != -1L) return
        val existing = findById(entity.id) ?: return
        if (existing.updatedAt < entity.updatedAt) {
            update(entity)
        }
    }
}
