/*
 * ProfileSettingsDao.kt
 *
 * Room DAO for `profile_settings`. Mirrors the surface shape of
 * `TagDao` (observe / dirtyRows / markSynced / markTombstoneSynced)
 * so the entity drops into QuickInkSyncDataSource without bespoke
 * handling.
 *
 * One row per user. Callers pass `userId`; the DAO upserts on the
 * single-row PK (which equals `user_id`).
 */

package app.quickink.mobile.data.profile

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileSettingsDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: ProfileSettingsEntity): Long

    @Update
    suspend fun update(entity: ProfileSettingsEntity)

    @Query("SELECT * FROM profile_settings WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): ProfileSettingsEntity?

    @Query("SELECT * FROM profile_settings WHERE user_id = :userId LIMIT 1")
    suspend fun findByUser(userId: String): ProfileSettingsEntity?

    /**
     * Live single-row observation for the Profile screen. Emits
     * `null` until the first row exists (e.g. on a fresh sign-in
     * before the prefs-migration has populated the table).
     */
    @Query("""
        SELECT * FROM profile_settings
        WHERE user_id = :userId AND deleted_at IS NULL
        LIMIT 1
    """)
    fun observe(userId: String): Flow<ProfileSettingsEntity?>

    /**
     * Upsert from a remote payload, last-write-wins on `updated_at`.
     * Same shape as `TagDao.upsertFromRemote` — see comment
     * there for the rationale (insert-IGNORE silently drops cross-
     * device updates, hence the explicit branch).
     */
    @Transaction
    suspend fun upsertFromRemote(entity: ProfileSettingsEntity) {
        val rowId = insert(entity)
        if (rowId != -1L) return
        val existing = findById(entity.id) ?: return
        if (existing.updatedAt < entity.updatedAt) {
            update(entity)
        }
    }

    /**
     * One-shot upsert for the local-write path (Profile screen
     * edits, prefs migration). Always writes — caller has already
     * stamped `updated_at` and `dirty = 1`.
     */
    @Transaction
    suspend fun upsertLocal(entity: ProfileSettingsEntity) {
        val rowId = insert(entity)
        if (rowId == -1L) update(entity)
    }

    /**
     * Update just the display name + dirty + updated_at. Avoids a
     * read-modify-write round trip from the call site for the
     * common per-field edit on Profile screen.
     */
    @Query("""
        UPDATE profile_settings
        SET display_name = :displayName,
            updated_at   = :timestamp,
            dirty        = 1
        WHERE id = :id
    """)
    suspend fun setDisplayName(id: String, displayName: String?, timestamp: String)

    @Query("""
        UPDATE profile_settings
        SET phone_number = :phone,
            updated_at   = :timestamp,
            dirty        = 1
        WHERE id = :id
    """)
    suspend fun setPhoneNumber(id: String, phone: String?, timestamp: String)

    @Query("""
        UPDATE profile_settings
        SET personality_punchline = :line,
            updated_at            = :timestamp,
            dirty                 = 1
        WHERE id = :id
    """)
    suspend fun setPersonalityPunchline(id: String, line: String?, timestamp: String)

    /**
     * Update the transcription-languages allowlist. `codes` is a
     * comma-separated string of BCP-47 codes (e.g. "en,hi,kn"); null
     * means "no preference set" and the transcriber falls back to
     * device locale + English.
     */
    @Query("""
        UPDATE profile_settings
        SET transcription_languages = :codes,
            updated_at              = :timestamp,
            dirty                   = 1
        WHERE id = :id
    """)
    suspend fun setTranscriptionLanguages(id: String, codes: String?, timestamp: String)

    /**
     * Photo binary changed (picked or cleared). Stamps a separate
     * `photo_updated_at` so the restore worker can decide whether
     * its local file is stale without re-downloading on every
     * metadata-only edit.
     *
     * `photoDriveFileId` is left untouched here — the upload path
     * fills it in later via [markPhotoBinarySynced]. Setting
     * photo_drive_file_id to null when the user clears their photo
     * is a separate path: callers pass null/null to wipe both.
     */
    @Query("""
        UPDATE profile_settings
        SET photo_local_uri    = :localUri,
            photo_updated_at   = :timestamp,
            photo_drive_file_id = NULL,
            updated_at         = :timestamp,
            dirty              = 1
        WHERE id = :id
    """)
    suspend fun setPhoto(id: String, localUri: String?, timestamp: String)

    /**
     * Stamp the Drive file id of the photo binary after a
     * successful upload. Doesn't move `updated_at` or `dirty` —
     * the metadata row is already in flight or already clean by
     * the time this fires (binary upload is asynchronous w.r.t.
     * the metadata sync ack).
     */
    @Query("""
        UPDATE profile_settings
        SET photo_drive_file_id = :driveFileId
        WHERE id = :id
    """)
    suspend fun markPhotoBinarySynced(id: String, driveFileId: String?)

    // ─── Sync surface (Phase 4 parity with TagDao) ───────────

    @Query("SELECT * FROM profile_settings WHERE dirty = 1")
    suspend fun dirtyRows(): List<ProfileSettingsEntity>

    @Query("""
        UPDATE profile_settings
        SET dirty = 0, drive_file_id = :driveFileId
        WHERE id = :id
          AND dirty = 1
          AND updated_at = :updatedAtSnapshot
    """)
    suspend fun markSynced(id: String, driveFileId: String, updatedAtSnapshot: String): Int

    @Query("""
        UPDATE profile_settings
        SET dirty = 0
        WHERE id = :id AND deleted_at IS NOT NULL
    """)
    suspend fun markTombstoneSynced(id: String): Int
}
