/*
 * CaptureTagDao.kt
 *
 * Room DAO for `capture_tags`. CRUD + lookup queries for the
 * Workspace tag-picker bottom sheet (Screen 6), the folder+tag
 * filter strip (Screen 2), and the tag library (Screen 4).
 *
 * Tag attach/detach goes through [attach] / [detach] rather than
 * direct INSERT/DELETE so the unique-active soft-delete dance
 * (re-tag after untag) stays in one place.
 */

package app.quickink.mobile.data.capturetag

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CaptureTagDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: CaptureTagEntity): Long

    @Update
    suspend fun update(entity: CaptureTagEntity)

    @Query("SELECT * FROM capture_tags WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): CaptureTagEntity?

    @Transaction
    suspend fun upsertFromRemote(entity: CaptureTagEntity) {
        val rowId = insert(entity)
        if (rowId != -1L) return
        val existing = findById(entity.id) ?: return
        if (existing.updatedAt < entity.updatedAt) {
            update(entity)
        }
    }

    /**
     * Idempotent attach. If an active join row already exists for
     * the pair, no-op. If a tombstoned row exists, revive it
     * (clear deleted_at + bump timestamps). Otherwise insert a
     * fresh row. Preserves the existing row's id so the Drive
     * payload filename doesn't churn across attach/detach cycles.
     */
    @Transaction
    suspend fun attachTag(
        joinId: String,
        captureId: String,
        tagId: String,
        source: String,
        timestamp: String,
    ) {
        val existing = findPair(captureId, tagId)
        if (existing == null) {
            insert(
                CaptureTagEntity(
                    id          = joinId,
                    captureId   = captureId,
                    tagId       = tagId,
                    source      = source,
                    driveFileId = null,
                    createdAt   = timestamp,
                    updatedAt   = timestamp,
                    dirty       = true,
                    deletedAt   = null,
                ),
            )
            return
        }
        if (existing.deletedAt != null) {
            // Reviving a tombstoned row preserves its id (and any
            // Drive backing). Bump timestamps + clear the tombstone.
            update(
                existing.copy(
                    deletedAt = null,
                    updatedAt = timestamp,
                    source    = source,
                    dirty     = true,
                ),
            )
        }
        // active row already present — no-op.
    }

    /** Soft-detach a tag from a capture. No-op if no active row. */
    @Transaction
    suspend fun detachTag(captureId: String, tagId: String, timestamp: String) {
        val existing = findPair(captureId, tagId) ?: return
        if (existing.deletedAt != null) return
        softDeleteById(existing.id, timestamp)
    }

    /**
     * Active join row for a (capture, tag) pair, if any. Used by
     * [attach] to revive a tombstoned row instead of inserting a
     * duplicate — preserves the original `id` so the Drive payload
     * filename doesn't churn.
     */
    @Query("""
        SELECT * FROM capture_tags
        WHERE capture_id = :captureId
          AND tag_id = :tagId
        ORDER BY deleted_at IS NULL DESC, updated_at DESC
        LIMIT 1
    """)
    suspend fun findPair(captureId: String, tagId: String): CaptureTagEntity?

    /**
     * Live list of tag ids on a single capture. Bound to the
     * docrow tag chips in the folder-detail screen (Screen 2)
     * and the tag picker's "currently attached" state (Screen 6).
     */
    @Query("""
        SELECT tag_id FROM capture_tags
        WHERE capture_id = :captureId AND deleted_at IS NULL
        ORDER BY created_at ASC
    """)
    fun observeTagIdsForCapture(captureId: String): Flow<List<String>>

    @Query("""
        SELECT tag_id FROM capture_tags
        WHERE capture_id = :captureId AND deleted_at IS NULL
        ORDER BY created_at ASC
    """)
    suspend fun listTagIdsForCapture(captureId: String): List<String>

    /**
     * Live list of capture ids tagged with a given tag. Drives the
     * "browse all docs with #aws" view from the tag library.
     */
    @Query("""
        SELECT capture_id FROM capture_tags
        WHERE tag_id = :tagId AND deleted_at IS NULL
        ORDER BY created_at DESC
    """)
    fun observeCaptureIdsForTag(tagId: String): Flow<List<String>>

    @Query("""
        SELECT capture_tags.capture_id AS capture_id, capture_tags.tag_id AS tag_id
        FROM capture_tags
        JOIN captures ON captures.id = capture_tags.capture_id
        JOIN tags     ON tags.id     = capture_tags.tag_id
        WHERE capture_tags.deleted_at IS NULL
          AND captures.deleted_at IS NULL
          AND tags.deleted_at IS NULL
          AND captures.user_id = :userId
    """)
    fun observeCaptureTagIds(userId: String): Flow<List<CaptureTagMembershipRow>>

    /**
     * One-shot version of [observeCaptureIdsForTag] for non-Flow
     * call sites — the `#tag` autocomplete on the search bar
     * intersects multiple tag-id results once per debounce
     * window, which doesn't need a live observation.
     */
    @Query("""
        SELECT capture_id FROM capture_tags
        WHERE tag_id = :tagId AND deleted_at IS NULL
        ORDER BY created_at DESC
    """)
    suspend fun captureIdsForTag(tagId: String): List<String>

    /**
     * Live list of capture ids tagged with a given tag NAME (in the
     * supplied user's namespace). Drives the per-tag drill on
     * Workspace home: tap a tag chip → list every doc that carries
     * that tag, not only docs where it's the primary attachment.
     */
    @Query("""
        SELECT capture_tags.capture_id FROM capture_tags
        JOIN tags     ON tags.id     = capture_tags.tag_id
        JOIN captures ON captures.id = capture_tags.capture_id
        WHERE captures.user_id      = :userId
          AND captures.deleted_at   IS NULL
          AND capture_tags.deleted_at IS NULL
          AND tags.deleted_at       IS NULL
          AND tags.name             = :tagName
        ORDER BY capture_tags.created_at DESC
    """)
    fun observeCaptureIdsForTagName(userId: String, tagName: String): Flow<List<String>>

    /**
     * Active-tag count per tag for the user. Used by the tag
     * cloud on Workspace home and the tag library's "31 documents"
     * subtitle. Excludes tombstoned join rows and tombstoned
     * captures — counts only active assignments (per brief §10 #5).
     */
    @Query("""
        SELECT capture_tags.tag_id AS tag_id, COUNT(*) AS doc_count
        FROM capture_tags
        JOIN captures ON captures.id = capture_tags.capture_id
        WHERE capture_tags.deleted_at IS NULL
          AND captures.deleted_at IS NULL
          AND captures.user_id = :userId
        GROUP BY capture_tags.tag_id
    """)
    fun observeTagCounts(userId: String): Flow<List<TagCount>>

    /**
     * Distinct tag ids that appear on at least one active capture
     * in the given folder. Drives the tag-strip filter on the
     * folder-detail screen — the strip only shows tags that
     * would actually narrow the result set.
     */
    @Query("""
        SELECT DISTINCT capture_tags.tag_id FROM capture_tags
        JOIN captures ON captures.id = capture_tags.capture_id
        WHERE captures.folder_id = :folderId
          AND captures.deleted_at IS NULL
          AND capture_tags.deleted_at IS NULL
    """)
    fun observeTagIdsInFolder(folderId: String): Flow<List<String>>

    /**
     * Count of active captures that carry *every* tag id in
     * [tagIds]. Drives the tag-library intersect builder
     * ("4 matching documents"). Caller passes the list of
     * selected tags; result is the AND intersection.
     *
     * Implementation: GROUP BY capture_id HAVING COUNT(DISTINCT) =
     * |tagIds|. Excludes tombstoned captures and tombstoned join
     * rows.
     */
    @Query("""
        SELECT COUNT(*) FROM (
            SELECT capture_tags.capture_id
            FROM capture_tags
            JOIN captures ON captures.id = capture_tags.capture_id
            WHERE capture_tags.tag_id IN (:tagIds)
              AND capture_tags.deleted_at IS NULL
              AND captures.user_id = :userId
              AND captures.deleted_at IS NULL
            GROUP BY capture_tags.capture_id
            HAVING COUNT(DISTINCT capture_tags.tag_id) = :tagCount
        )
    """)
    fun observeIntersectCount(userId: String, tagIds: List<String>, tagCount: Int): Flow<Int>

    /**
     * For every active capture in the user's namespace that has at
     * least one active tag attached, emit (capture_id, tag_name) for
     * the *earliest-attached* tag — the closest analogue to the
     * legacy `captures.category` "primary label". Drives the legacy
     * Library / Search / Category-grid surfaces post-A.3c column
     * drop. Window function picks one row per capture deterministically;
     * SQLite 3.25+ is bundled via [BundledSQLiteDriver] so this is
     * safe to run everywhere QuickInk runs.
     */
    @Query("""
        WITH ranked AS (
            SELECT
                capture_tags.capture_id AS capture_id,
                tags.name               AS tag_name,
                ROW_NUMBER() OVER (
                    PARTITION BY capture_tags.capture_id
                    ORDER BY capture_tags.created_at ASC, capture_tags.id ASC
                ) AS rn
            FROM capture_tags
            JOIN tags     ON tags.id     = capture_tags.tag_id
            JOIN captures ON captures.id = capture_tags.capture_id
            WHERE captures.user_id    = :userId
              AND captures.deleted_at IS NULL
              AND capture_tags.deleted_at IS NULL
              AND tags.deleted_at     IS NULL
        )
        SELECT capture_id, tag_name FROM ranked WHERE rn = 1
    """)
    fun observePrimaryTagNames(userId: String): Flow<List<CapturePrimaryTagRow>>

    /**
     * Soft-delete a single join row by id. Used by [detach] —
     * external callers should prefer that.
     */
    @Query("""
        UPDATE capture_tags
        SET deleted_at = :timestamp, updated_at = :timestamp, dirty = 1
        WHERE id = :id
    """)
    suspend fun softDeleteById(id: String, timestamp: String)

    /**
     * Soft-delete every join row pointing at a tag (used when a
     * tag itself is deleted in the tag library — analogous to
     * cascading the tombstone). Each row stays sync-able
     * independently so other devices learn about the removal.
     */
    @Query("""
        UPDATE capture_tags
        SET deleted_at = :timestamp, updated_at = :timestamp, dirty = 1
        WHERE tag_id = :tagId AND deleted_at IS NULL
    """)
    suspend fun softDeleteByTagId(tagId: String, timestamp: String)

    @Query("""
        UPDATE capture_tags
        SET deleted_at = :timestamp, updated_at = :timestamp, dirty = 1
        WHERE capture_id = :captureId AND deleted_at IS NULL
    """)
    suspend fun softDeleteByCaptureId(captureId: String, timestamp: String)

    // ─── Sync surface ─────────────────────────────────────────────

    @Query("SELECT * FROM capture_tags WHERE dirty = 1")
    suspend fun dirtyRows(): List<CaptureTagEntity>

    @Query("""
        UPDATE capture_tags
        SET dirty = 0, drive_file_id = :driveFileId
        WHERE id = :id
          AND dirty = 1
          AND updated_at = :updatedAtSnapshot
    """)
    suspend fun markSynced(id: String, driveFileId: String, updatedAtSnapshot: String): Int

    @Query("""
        UPDATE capture_tags
        SET dirty = 0
        WHERE id = :id AND deleted_at IS NOT NULL
    """)
    suspend fun markTombstoneSynced(id: String): Int
}

/**
 * Projection for the per-tag document-count query. One row per
 * tag with active captures attached.
 */
data class TagCount(
    @ColumnInfo(name = "tag_id")    val tagId: String,
    @ColumnInfo(name = "doc_count") val docCount: Int,
)

/**
 * Projection for [CaptureTagDao.observePrimaryTagNames]. One row
 * per capture that has at least one active tag — `tagName` is the
 * earliest-attached tag's name. Callers typically collect into a
 * `Map<String, String>` keyed by [captureId] for O(1) UI lookup.
 */
data class CapturePrimaryTagRow(
    @ColumnInfo(name = "capture_id") val captureId: String,
    @ColumnInfo(name = "tag_name")   val tagName: String,
)

data class CaptureTagMembershipRow(
    @ColumnInfo(name = "capture_id") val captureId: String,
    @ColumnInfo(name = "tag_id")     val tagId: String,
)
