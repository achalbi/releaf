/*
 * ShelfRepository.kt
 *
 * Orchestrates shelf reads/writes. Ensures a default "General" shelf
 * always exists so the app has a valid parent for fresh books even
 * on fresh installs (the migration seed covers upgraders).
 */

package app.releaf.mobile.data.shelf

import app.releaf.mobile.data.common.IsoClock
import app.releaf.mobile.data.common.Uuidv7
import kotlinx.coroutines.flow.Flow

class ShelfRepository(
    private val dao: ShelfDao,
) {

    fun observeActive(): Flow<List<ShelfEntity>> = dao.observeActive()

    fun observeById(id: String): Flow<ShelfEntity?> = dao.observeById(id)

    suspend fun findById(id: String): ShelfEntity? = dao.findById(id)

    /**
     * Create a shelf. Caller supplies the display name; everything
     * else (id, timestamps, dirty) is filled in here.
     */
    suspend fun createShelf(name: String, colorHex: String? = null): ShelfEntity {
        val now = IsoClock.nowIso()
        val shelf = ShelfEntity(
            id        = Uuidv7.generate(),
            name      = name.trim().ifEmpty { "Untitled shelf" },
            colorHex  = colorHex,
            createdAt = now,
            updatedAt = now,
            dirty     = true,
        )
        dao.upsert(shelf)
        return shelf
    }

    suspend fun rename(id: String, name: String, colorHex: String? = null) {
        val current = dao.findById(id) ?: return
        val cleaned = name.trim().ifEmpty { current.name }
        if (cleaned == current.name && colorHex == current.colorHex) return
        dao.upsert(
            current.copy(
                name      = cleaned,
                colorHex  = colorHex ?: current.colorHex,
                updatedAt = IsoClock.nowIso(),
                dirty     = true,
            )
        )
    }

    suspend fun softDelete(id: String) {
        dao.softDelete(id = id, nowIso = IsoClock.nowIso())
    }

    suspend fun undoSoftDelete(id: String) {
        dao.restore(id = id, nowIso = IsoClock.nowIso())
    }

    /**
     * Ensure the default "General" shelf exists. Called at app
     * startup; no-op when the migration has already seeded it.
     */
    suspend fun ensureDefaultShelf(): ShelfEntity {
        dao.findById(ShelfEntity.DEFAULT_GENERAL_ID)?.let { return it }
        val now = IsoClock.nowIso()
        val shelf = ShelfEntity(
            id        = ShelfEntity.DEFAULT_GENERAL_ID,
            name      = "General",
            colorHex  = "#7AA874",
            position  = 1024L,
            createdAt = now,
            updatedAt = now,
            dirty     = true,
        )
        dao.upsert(shelf)
        return shelf
    }
}
