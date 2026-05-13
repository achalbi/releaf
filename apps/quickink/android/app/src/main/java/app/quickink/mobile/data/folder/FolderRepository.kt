/*
 * FolderRepository.kt
 *
 * Wraps [FolderDao] for the Workspace home folder list (Screen 1),
 * folder CRUD, and the seed/backfill that runs once per user on
 * first launch after the v4 upgrade.
 *
 * One-time migration responsibilities (Phase A.3):
 *   - Seed the default "Unfiled" folder per user (idempotent).
 *   - Backfill every capture's `folder_id` to point at Unfiled.
 *   - Materialize `captures.category` → `capture_tags` (one row per
 *     non-null category value), so the legacy single-tag-per-capture
 *     data survives the column drop scheduled for A.3c.
 *
 * Mirror of `FolderRepository.swift` in QuickInk's iOS target
 * (lands in the iOS Phase A pass).
 */

package app.quickink.mobile.data.folder

import android.content.Context
import app.quickink.mobile.data.capture.CaptureDao
import app.quickink.mobile.data.capturetag.CaptureTagDao
import app.quickink.mobile.data.capturetag.CaptureTagEntity
import app.quickink.mobile.data.tag.TagDao
import app.quickink.mobile.data.tag.TagRepository
import app.releaf.mobile.data.common.IsoClock
import app.releaf.mobile.data.common.Uuidv7
import kotlinx.coroutines.flow.Flow

class FolderRepository(
    private val folderDao: FolderDao,
    private val captureDao: CaptureDao? = null,
    private val tagDao: TagDao? = null,
    private val captureTagDao: CaptureTagDao? = null,
) {

    fun observe(userId: String): Flow<List<FolderEntity>> =
        folderDao.observeActive(userId)

    suspend fun listActive(userId: String): List<FolderEntity> =
        folderDao.listActive(userId)

    /**
     * Create a user folder. Returns `null` on UNIQUE-name collision;
     * caller surfaces the failure in the UI.
     */
    suspend fun create(
        userId: String,
        name: String,
        color: String,
        position: Int = Int.MAX_VALUE,
        id: String = Uuidv7.generate(),
    ): FolderEntity? {
        val now = IsoClock.nowIso()
        val candidate = FolderEntity(
            id          = id,
            userId      = userId,
            name        = name,
            color       = color,
            position    = position,
            isDefault   = false,
            isShared    = false,
            createdAt   = now,
            updatedAt   = now,
            dirty       = true,
            deletedAt   = null,
        )
        val rowId = folderDao.insert(candidate)
        return if (rowId != -1L) candidate else null
    }

    suspend fun rename(id: String, newName: String) {
        folderDao.rename(id, newName, IsoClock.nowIso())
    }

    suspend fun setColor(id: String, color: String) {
        folderDao.setColor(id, color, IsoClock.nowIso())
    }

    suspend fun reorder(ids: List<String>) {
        val now = IsoClock.nowIso()
        ids.forEachIndexed { index, id ->
            folderDao.setPosition(id, index, now)
        }
    }

    /**
     * Soft-delete a folder. The captures inside are moved to Unfiled
     * first — never cascade-delete the contents (per brief §10 #2).
     * The default Unfiled folder itself is non-deletable; the DAO
     * query guards against it via `is_default = 0`.
     */
    suspend fun softDelete(userId: String, folderId: String) {
        val unfiled = folderDao.findDefault(userId) ?: return
        if (unfiled.id == folderId) return
        val now = IsoClock.nowIso()
        captureDao?.moveCapturesToFolder(folderId, unfiled.id, now)
        folderDao.softDelete(folderId, now)
    }

    // ────────────────────────────────────────────────────────────
    // First-launch seed + backfill (Phase A.3)
    // ────────────────────────────────────────────────────────────

    /**
     * Seed the default "Unfiled" folder if no `is_default` row
     * exists for this user. Idempotent. Returns the (possibly
     * pre-existing) default folder.
     */
    suspend fun seedDefaultsIfNeeded(userId: String): FolderEntity {
        folderDao.findDefault(userId)?.let { return it }
        val now = IsoClock.nowIso()
        val unfiled = FolderEntity(
            id          = Uuidv7.generate(),
            userId      = userId,
            name        = DEFAULT_FOLDER_NAME,
            color       = DEFAULT_FOLDER_COLOR,
            position    = 0,
            isDefault   = true,
            isShared    = false,
            createdAt   = now,
            updatedAt   = now,
            dirty       = true,
            deletedAt   = null,
        )
        val rowId = folderDao.insert(unfiled)
        // Race-safe: if another caller inserted first, re-query and
        // return that row.
        return if (rowId != -1L) unfiled
        else folderDao.findDefault(userId)
            ?: error("Race: seed returned -1 but findDefault missed for userId=$userId")
    }

    /**
     * Backfill every capture's `folder_id` to point at Unfiled for
     * rows where it's still NULL (i.e. created before v9 added the
     * column). Idempotent — captures already assigned to a folder
     * are untouched.
     *
     * Guarded by SharedPreferences so it runs once per install per
     * user; subsequent launches no-op even when called.
     */
    suspend fun backfillFolderIdsIfNeeded(context: Context, userId: String) {
        val capDao = captureDao ?: return
        val prefs = context.applicationContext
            .getSharedPreferences(MIGRATION_PREFS, Context.MODE_PRIVATE)
        val flag = backfillFolderIdsFlag(userId)
        if (prefs.getBoolean(flag, false)) return

        val unfiled = seedDefaultsIfNeeded(userId)
        capDao.assignOrphanCapturesToFolder(userId, unfiled.id, IsoClock.nowIso())

        prefs.edit().putBoolean(flag, true).apply()
    }

    /**
     * Materialize `captures.category` → `capture_tags`. Run once
     * per user on first launch after the v4 upgrade, before the
     * `captures.category` column is dropped in A.3c.
     *
     * For each capture with a non-null category:
     *   1. find-or-create the tag (by name, within the user's
     *      namespace),
     *   2. insert a `capture_tags` row with source = "migration"
     *      (or skip if a row already exists for the pair).
     *
     * Idempotent via SharedPreferences guard; the underlying inserts
     * are also race-safe via the unique-active index on
     * (capture_id, tag_id).
     */
    suspend fun materializeCategoryToTagsIfNeeded(
        context: Context,
        userId: String,
    ) {
        val capDao = captureDao ?: return
        val tDao   = tagDao ?: return
        val tagRepo = TagRepository(tDao, capDao)
        val ctDao  = captureTagDao ?: return

        val prefs = context.applicationContext
            .getSharedPreferences(MIGRATION_PREFS, Context.MODE_PRIVATE)
        val flag = materializeCategoryFlag(userId)
        if (prefs.getBoolean(flag, false)) return

        val now = IsoClock.nowIso()
        val captures = capDao.listWithCategory(userId)
        for (cap in captures) {
            val rawName = cap.category?.trim().orEmpty()
            if (rawName.isEmpty()) continue
            val tag = tagRepo.findOrCreate(userId, rawName)
            // Skip if a join row (active or tombstoned) already
            // exists for this pair — the unique-active index would
            // refuse the insert anyway.
            if (ctDao.findPair(cap.id, tag.id) != null) continue
            ctDao.insert(
                CaptureTagEntity(
                    id          = Uuidv7.generate(),
                    captureId   = cap.id,
                    tagId       = tag.id,
                    source      = SOURCE_MIGRATION,
                    createdAt   = now,
                    updatedAt   = now,
                    dirty       = true,
                    deletedAt   = null,
                ),
            )
        }

        prefs.edit().putBoolean(flag, true).apply()
    }

    /**
     * Convenience wrapper that runs all three first-launch steps in
     * order. Safe to call on every launch — each step is idempotent
     * and short-circuits when its work is done.
     */
    suspend fun runFirstLaunchMigrationIfNeeded(
        context: Context,
        userId: String,
    ) {
        seedDefaultsIfNeeded(userId)
        materializeCategoryToTagsIfNeeded(context, userId)
        backfillFolderIdsIfNeeded(context, userId)
    }

    companion object {
        const val DEFAULT_FOLDER_NAME = "Unfiled"

        /**
         * Neutral stone — matches the design's "no judgement" tone
         * for the default folder. User-created folders pick brighter
         * accents from the palette.
         */
        const val DEFAULT_FOLDER_COLOR = "#A8A29E"

        const val SOURCE_MIGRATION = "migration"

        private const val MIGRATION_PREFS = "quickink_migrations"

        private fun backfillFolderIdsFlag(userId: String): String =
            "workspace-folder-backfill-v1:$userId"

        private fun materializeCategoryFlag(userId: String): String =
            "workspace-category-materialize-v1:$userId"
    }
}
