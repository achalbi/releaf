/*
 * StoryItemDao.kt
 *
 * Room DAO for the `story_item` table. Phase 1 surface: list-by-
 * story, insert, soft-delete. The editor (Phase 2) layers
 * reorder + replace + caption-edit on top.
 *
 * Mirror of iOS `StoryRepository.swift` item methods.
 */

package app.quickink.mobile.data.storyitem

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface StoryItemDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: StoryItemEntity): Long

    @Update
    suspend fun update(entity: StoryItemEntity)

    @Query("SELECT * FROM story_item WHERE id = :id AND deleted_at IS NULL LIMIT 1")
    suspend fun findById(id: String): StoryItemEntity?

    @Query("""
        SELECT * FROM story_item
        WHERE story_id = :storyId AND deleted_at IS NULL
        ORDER BY position ASC
    """)
    fun observeForStory(storyId: String): Flow<List<StoryItemEntity>>

    @Query("""
        SELECT * FROM story_item
        WHERE story_id = :storyId AND deleted_at IS NULL
        ORDER BY position ASC
    """)
    suspend fun listForStory(storyId: String): List<StoryItemEntity>

    @Query("""
        UPDATE story_item
        SET position = :position, updated_at = :timestamp, dirty = 1
        WHERE id = :id
    """)
    suspend fun setPosition(id: String, position: Int, timestamp: String)

    @Query("""
        UPDATE story_item
        SET caption = :caption, updated_at = :timestamp, dirty = 1
        WHERE id = :id
    """)
    suspend fun setCaption(id: String, caption: String?, timestamp: String)

    @Query("""
        UPDATE story_item
        SET text = :text, updated_at = :timestamp, dirty = 1
        WHERE id = :id
    """)
    suspend fun setText(id: String, text: String?, timestamp: String)

    @Query("""
        UPDATE story_item
        SET layout = :layout, updated_at = :timestamp, dirty = 1
        WHERE id = :id
    """)
    suspend fun setLayout(id: String, layout: String, timestamp: String)

    @Query("""
        UPDATE story_item
        SET deleted_at = :timestamp, updated_at = :timestamp, dirty = 1
        WHERE id = :id
    """)
    suspend fun softDelete(id: String, timestamp: String)

    /**
     * Cascade-tombstone any attached voice clip when a story_item is
     * soft-deleted. Mirror of [StoryRepository.softDeleteItem]'s iOS
     * GRDB transaction — the SQL ON DELETE CASCADE only fires on
     * hard-delete, which we never do.
     */
    @Query("""
        UPDATE story_voice_clip
        SET deleted_at = :timestamp, updated_at = :timestamp, dirty = 1
        WHERE story_item_id = :storyItemId AND deleted_at IS NULL
    """)
    suspend fun softDeleteAttachedVoiceClips(storyItemId: String, timestamp: String)

    // ── Sync surface ────────────────────────────────────────────

    @Query("SELECT * FROM story_item WHERE dirty = 1")
    suspend fun dirtyRows(): List<StoryItemEntity>

    @Query("""
        SELECT * FROM story_item
        WHERE deleted_at IS NULL
        ORDER BY position ASC
    """)
    suspend fun activeRows(): List<StoryItemEntity>

    @Transaction
    suspend fun upsertFromRemote(entity: StoryItemEntity) {
        val rowId = insert(entity)
        if (rowId != -1L) return
        val existing = findById(entity.id) ?: return
        if (existing.updatedAt < entity.updatedAt) {
            update(entity)
        }
    }
}
