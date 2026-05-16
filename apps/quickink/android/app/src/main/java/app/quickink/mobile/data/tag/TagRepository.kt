/*
 * TagRepository.kt
 *
 * Wraps [TagDao] for the Settings → Tags surface, the scan-review
 * picker, the Workspace home tag cloud (Screen 1), the folder-detail
 * tag filter strip (Screen 2), and the tag picker bottom sheet
 * (Screen 6).
 *
 * Renamed from `CategoryRepository` in Phase A.2 of the Workspace
 * redesign. Post-A.3c the legacy `captures.category` column is
 * gone — the per-capture primary label lives in the `capture_tags`
 * join, so a tag rename naturally propagates (the join row
 * references the tag by id). The historical `renameAndPropagate`
 * helper collapses to a plain rename.
 *
 * Mirror of `TagRepository.swift` in QuickInk's iOS target.
 */

package app.quickink.mobile.data.tag

import android.content.Context
import app.quickink.mobile.data.capture.CaptureDao
import app.releaf.mobile.data.common.IsoClock
import app.releaf.mobile.data.common.Uuidv7
import kotlinx.coroutines.flow.Flow

class TagRepository(
    private val tagDao: TagDao,
    private val captureDao: CaptureDao? = null,
) {

    /**
     * Live list of a user's active tags. See [TagDao.observeActive]
     * for ordering.
     */
    fun observe(userId: String): Flow<List<TagEntity>> =
        tagDao.observeActive(userId)

    /**
     * Insert a single tag. Returns `false` if the (user_id, name)
     * UNIQUE constraint already has this name — the existing row is
     * left untouched.
     */
    suspend fun insert(
        userId: String,
        name: String,
        position: Int,
        id: String = Uuidv7.generate(),
        color: String? = null,
    ): Boolean {
        val now = IsoClock.nowIso()
        val rowId = tagDao.insert(
            TagEntity(
                id           = id,
                userId       = userId,
                name         = name,
                position     = position,
                color        = color,
                driveFileId  = null,
                createdAt    = now,
                updatedAt    = now,
                dirty        = true,
                deletedAt    = null,
            ),
        )
        return rowId != -1L
    }

    /**
     * Find-or-create by name within a user's namespace. Used by the
     * tag picker sheet (when the user types a new tag name) and by
     * the auto-tagging heuristic (Phase E) to materialize suggested
     * tags lazily. Returns the tag id either way.
     */
    suspend fun findOrCreate(userId: String, name: String): TagEntity {
        tagDao.findByName(userId, name)?.let { return it }
        // Insert; on race the second caller picks up the winner.
        val now = IsoClock.nowIso()
        val candidate = TagEntity(
            id           = Uuidv7.generate(),
            userId       = userId,
            name         = name,
            position     = Int.MAX_VALUE,
            color        = null,
            driveFileId  = null,
            createdAt    = now,
            updatedAt    = now,
            dirty        = true,
            deletedAt    = null,
        )
        val rowId = tagDao.insert(candidate)
        return if (rowId != -1L) candidate
        else tagDao.findByName(userId, name)
            ?: error("Race: insert returned -1 but findByName missed for name=$name")
    }

    suspend fun rename(id: String, newName: String) {
        tagDao.rename(id, newName, IsoClock.nowIso())
    }

    /**
     * Rename a tag. Post-A.3c the per-capture primary label lives
     * in `capture_tags` (which FKs the tag by id), so a rename
     * propagates to every attached capture for free — no
     * per-capture write needed. Kept as a named helper because
     * older callers spell the intent out clearly; the `oldName`
     * and `userId` arguments are now unused, retained only to
     * preserve the signature.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun renameAndPropagate(
        id: String,
        oldName: String,
        newName: String,
        userId: String,
    ) {
        tagDao.rename(id, newName, IsoClock.nowIso())
    }

    suspend fun softDelete(id: String) {
        tagDao.softDelete(id, IsoClock.nowIso())
    }

    suspend fun setColor(id: String, color: String?) {
        tagDao.setColor(id, color, IsoClock.nowIso())
    }

    suspend fun reorder(ids: List<String>) {
        val now = IsoClock.nowIso()
        ids.forEachIndexed { index, id ->
            tagDao.setPosition(id, index, now)
        }
    }

    /**
     * Seed the default tags if the user has no active rows.
     * Idempotent — call from app launch or first sign-in. Honors
     * the (user_id, name) UNIQUE constraint, so a partial earlier
     * seed run is safe to retry.
     *
     * The `#needs-review` tag is included to back the seeded
     * "Needs review" smart collection (see brief §3).
     */
    suspend fun seedDefaultsIfEmpty(userId: String) {
        if (tagDao.listActive(userId).isNotEmpty()) return
        DEFAULT_SEED.forEachIndexed { index, name ->
            insert(userId = userId, name = name, position = index)
        }
    }

    /**
     * One-shot migration that renames the legacy capitalized seed
     * names ("Ideas", "Projects", "Meetings", "Todo", "Business
     * Card", "Journal") to their kebab-case form so existing users
     * land on the same canonical form as fresh seeds and the AI
     * suggester chips. `capture_tags` rows reference the tag by id
     * so the rename propagates without touching the join.
     *
     * Guarded by a SharedPreferences flag so it only runs once per
     * install. Body is defensive — if the kebab target already
     * exists we soft-delete the capitalized row instead of trying
     * to rename. Mirror of iOS
     * `migrateLegacySeedNamesToKebabIfNeeded`.
     */
    suspend fun migrateLegacySeedNamesToKebabIfNeeded(
        context: Context,
        userId: String,
    ) {
        val prefs = context.applicationContext
            .getSharedPreferences("quickink_migrations", Context.MODE_PRIVATE)
        val flagKey = "seed-kebab-v1"
        if (prefs.getBoolean(flagKey, false)) return

        val renames = listOf(
            "Ideas"         to "ideas",
            "Projects"      to "projects",
            "Meetings"      to "meetings",
            "Todo"          to "todo",
            "Business Card" to "business-card",
            "Journal"       to "journal",
        )
        val active = tagDao.listActive(userId).associateBy { it.name }
        val now = IsoClock.nowIso()
        renames.forEach { (old, new) ->
            val oldRow = active[old] ?: return@forEach
            if (active[new] == null) {
                tagDao.rename(oldRow.id, new, now)
            } else {
                tagDao.softDelete(oldRow.id, now)
            }
        }
        prefs.edit().putBoolean(flagKey, true).apply()
    }

    /**
     * One-shot migration for users who were on a previous default
     * seed that included "Study" (which has since been replaced by
     * "business-card"). Renames Study → business-card. Post-A.3c
     * the rename propagates automatically through `capture_tags`
     * (the join row FKs the tag id, not its name), so the
     * historical per-capture retag pass is gone.
     *
     * Guarded by a SharedPreferences flag so it only runs once per
     * install. Body is defensive — if business-card already exists
     * we soft-delete Study instead of trying to rename. Mirror of
     * iOS `migrateLegacyStudyToBusinessCardIfNeeded`.
     */
    suspend fun migrateLegacyStudyToBusinessCardIfNeeded(
        context: Context,
        userId: String,
    ) {
        val prefs = context.applicationContext
            .getSharedPreferences("quickink_migrations", Context.MODE_PRIVATE)
        val flagKey = "study-to-business-card-v1"
        if (prefs.getBoolean(flagKey, false)) return

        val active       = tagDao.listActive(userId)
        val study        = active.firstOrNull { it.name == "Study" }
        val businessCard = active.firstOrNull { it.name == "business-card" }
        val now          = IsoClock.nowIso()

        if (study != null && businessCard == null) {
            tagDao.rename(study.id, "business-card", now)
        } else if (study != null && businessCard != null) {
            tagDao.softDelete(study.id, now)
        }

        prefs.edit().putBoolean(flagKey, true).apply()
    }

    companion object {
        /**
         * Default seed names rendered as the user's starting set.
         * Matches `TagRepository.swift`'s `defaultSeed`. Names in
         * this list are treated as system-managed: the Settings →
         * Tags screen hides delete + rename affordances for them.
         *
         * `#needs-review` is included to back the seeded "Needs
         * review" smart collection (Workspace v1; see brief §3).
         */
        val DEFAULT_SEED: List<String> = listOf(
            "ideas", "projects", "meetings", "todo", "business-card", "journal",
            "needs-review",
        )

        /** True for the seed names users get on first launch. */
        fun isPredefined(name: String): Boolean = name in DEFAULT_SEED
    }
}
