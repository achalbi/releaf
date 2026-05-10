/*
 * CategoryRepository.kt
 *
 * Wraps [CategoryDao] for the Settings → Categories surface and
 * the scan-review picker. Mirror of `CategoryRepository.swift` in
 * QuickInk's iOS target.
 */

package app.quickink.mobile.data.category

import app.quickink.mobile.data.capture.CaptureDao
import app.releaf.mobile.data.common.IsoClock
import app.releaf.mobile.data.common.Uuidv7
import kotlinx.coroutines.flow.Flow

class CategoryRepository(
    private val categoryDao: CategoryDao,
    private val captureDao: CaptureDao? = null,
) {

    /**
     * Live list of a user's active categories. See
     * [CategoryDao.observeActive] for ordering.
     */
    fun observe(userId: String): Flow<List<CategoryEntity>> =
        categoryDao.observeActive(userId)

    /**
     * Insert a single category. Returns `false` if the
     * (user_id, name) UNIQUE constraint already has this name —
     * the existing row is left untouched.
     */
    suspend fun insert(
        userId: String,
        name: String,
        position: Int,
        id: String = Uuidv7.generate(),
    ): Boolean {
        val now = IsoClock.nowIso()
        val rowId = categoryDao.insert(
            CategoryEntity(
                id           = id,
                userId       = userId,
                name         = name,
                position     = position,
                driveFileId  = null,
                createdAt    = now,
                updatedAt    = now,
                dirty        = true,
                deletedAt    = null,
            ),
        )
        return rowId != -1L
    }

    suspend fun rename(id: String, newName: String) {
        categoryDao.rename(id, newName, IsoClock.nowIso())
    }

    /**
     * Rename a category and propagate the change to every capture
     * row that references it by name. `captures.category` stores
     * the value (not an FK), so a plain rename of `categories.name`
     * would orphan the historical tags otherwise. Both writes mark
     * their rows dirty so sync picks them up. No-op for the
     * captures-side write when this repository was constructed
     * without a `captureDao` (e.g. read-only callers).
     */
    suspend fun renameAndPropagate(
        id: String,
        oldName: String,
        newName: String,
        userId: String,
    ) {
        val now = IsoClock.nowIso()
        categoryDao.rename(id, newName, now)
        captureDao?.renameCategory(userId, oldName, newName, now)
    }

    suspend fun softDelete(id: String) {
        categoryDao.softDelete(id, IsoClock.nowIso())
    }

    suspend fun reorder(ids: List<String>) {
        val now = IsoClock.nowIso()
        ids.forEachIndexed { index, id ->
            categoryDao.setPosition(id, index, now)
        }
    }

    /**
     * Seed the default 6 categories if the user has no active rows.
     * Idempotent — call from app launch or first sign-in. Honors
     * the (user_id, name) UNIQUE constraint, so a partial earlier
     * seed run is safe to retry.
     */
    suspend fun seedDefaultsIfEmpty(userId: String) {
        if (categoryDao.listActive(userId).isNotEmpty()) return
        DEFAULT_SEED.forEachIndexed { index, name ->
            insert(userId = userId, name = name, position = index)
        }
    }

    companion object {
        /**
         * Default seed names rendered as the user's starting set.
         * Matches `CategoryRepository.swift`'s `defaultSeed`. Names
         * in this list are treated as system-managed: the Settings
         * → Categories screen hides delete + rename affordances for
         * them.
         */
        val DEFAULT_SEED: List<String> = listOf(
            "Ideas", "Projects", "Meetings", "Todo", "Business Card", "Journal",
        )

        /** True for the 6 seed names users get on first launch. */
        fun isPredefined(name: String): Boolean = name in DEFAULT_SEED
    }
}
