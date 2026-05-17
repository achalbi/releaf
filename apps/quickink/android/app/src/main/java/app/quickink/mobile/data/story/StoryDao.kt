/*
 * StoryDao.kt
 *
 * Room DAO for the `story` table. Read queries power the Stories
 * shelf (§7.1 of the v3 mockup); write queries cover insert / cover-
 * item-set / soft-delete. Item-level reads/writes live in
 * [StoryItemDao].
 *
 * Mirror of iOS `StoryRepository.swift`.
 */

package app.quickink.mobile.data.story

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Shelf projection — a [StoryEntity] enriched with the two values the
 * §7.1 card surfaces alongside the title: how many items the story
 * holds (active rows only) and the most recent item's effective date
 * (`occurred_at` falls back to `created_at`) so the meta line can
 * render "14 items · Apr 2026".
 */
data class StoryShelfRow(
    @Embedded val story: StoryEntity,
    @androidx.room.ColumnInfo(name = "item_count") val itemCount: Int,
    @androidx.room.ColumnInfo(name = "latest_item_at") val latestItemAt: String?,
)

@Dao
interface StoryDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: StoryEntity): Long

    @Update
    suspend fun update(entity: StoryEntity)

    @Query("SELECT * FROM story WHERE id = :id AND deleted_at IS NULL LIMIT 1")
    suspend fun findById(id: String): StoryEntity?

    @Query("""
        SELECT s.*,
               COUNT(i.id) AS item_count,
               MAX(COALESCE(i.occurred_at, i.created_at)) AS latest_item_at
        FROM story s
        LEFT JOIN story_item i
          ON i.story_id = s.id AND i.deleted_at IS NULL
        WHERE s.user_id = :userId AND s.deleted_at IS NULL
        GROUP BY s.id
        ORDER BY s.updated_at DESC
    """)
    fun observeShelf(userId: String): Flow<List<StoryShelfRow>>

    @Query("""
        SELECT s.*,
               COUNT(i.id) AS item_count,
               MAX(COALESCE(i.occurred_at, i.created_at)) AS latest_item_at
        FROM story s
        LEFT JOIN story_item i
          ON i.story_id = s.id AND i.deleted_at IS NULL
        WHERE s.user_id = :userId AND s.deleted_at IS NULL
        GROUP BY s.id
        ORDER BY s.updated_at DESC
    """)
    suspend fun listShelf(userId: String): List<StoryShelfRow>

    @Query("""
        UPDATE story
        SET cover_item_id = :itemId, updated_at = :timestamp, dirty = 1
        WHERE id = :id
    """)
    suspend fun setCoverItem(id: String, itemId: String?, timestamp: String)

    /**
     * Null out the cover pointer on any story that referenced the
     * just-deleted item. Per the handoff doc's don't-do list: never
     * drop the `cover_item_id` FK, NULL it.
     */
    @Query("""
        UPDATE story
        SET cover_item_id = NULL, updated_at = :timestamp, dirty = 1
        WHERE cover_item_id = :itemId
    """)
    suspend fun clearCoverItemReferences(itemId: String, timestamp: String)

    @Query("""
        UPDATE story
        SET title = :title, updated_at = :timestamp, dirty = 1
        WHERE id = :id
    """)
    suspend fun setTitle(id: String, title: String, timestamp: String)

    @Query("""
        UPDATE story
        SET subtitle = :subtitle, updated_at = :timestamp, dirty = 1
        WHERE id = :id
    """)
    suspend fun setSubtitle(id: String, subtitle: String?, timestamp: String)

    @Query("""
        UPDATE story
        SET share_mode = :shareMode,
            share_slug = :shareSlug,
            status     = :status,
            updated_at = :timestamp,
            dirty      = 1
        WHERE id = :id
    """)
    suspend fun setShareMode(
        id: String,
        shareMode: String,
        shareSlug: String?,
        status: String,
        timestamp: String,
    )

    @Query("""
        UPDATE story
        SET deleted_at = :timestamp, updated_at = :timestamp, dirty = 1
        WHERE id = :id
    """)
    suspend fun softDelete(id: String, timestamp: String)

    @Query("SELECT COUNT(*) FROM story WHERE user_id = :userId AND deleted_at IS NULL")
    suspend fun countActive(userId: String): Int

    // ── Sync surface ────────────────────────────────────────────

    /** All currently-dirty rows (any state). Used by the sync push +
     *  tombstone-emit paths. Mirror of [VoiceNoteDao.dirtyRows]. */
    @Query("SELECT * FROM story WHERE dirty = 1")
    suspend fun dirtyRows(): List<StoryEntity>

    /** Live rows for a user — backs the sync push of un-deleted state. */
    @Query("""
        SELECT * FROM story
        WHERE user_id = :userId AND deleted_at IS NULL
        ORDER BY created_at ASC
    """)
    suspend fun activeRows(userId: String): List<StoryEntity>

    /**
     * Upsert from remote sync. Real UPDATE under the hood (not REPLACE
     * clobber) gated on `updated_at` so a slow restore can't overwrite
     * a faster local edit. Mirror of [VoiceNoteDao.upsertFromRemote].
     */
    @Transaction
    suspend fun upsertFromRemote(entity: StoryEntity) {
        val rowId = insert(entity)
        if (rowId != -1L) return
        val existing = findById(entity.id) ?: return
        if (existing.updatedAt < entity.updatedAt) {
            update(entity)
        }
    }
}
