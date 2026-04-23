/*
 * PerspectiveRepository.kt
 *
 * Thin wrapper over [PerspectiveDao] — handles id/timestamp
 * generation, name normalisation, default-seeding on first use, and
 * "ensure by name" (idempotent create used when a task title
 * introduces a new @tag that doesn't yet have a tile).
 */

package app.releaf.mobile.data.perspective

import app.releaf.mobile.data.common.IsoClock
import app.releaf.mobile.data.common.Uuidv7
import kotlinx.coroutines.flow.Flow

class PerspectiveRepository(
    private val dao: PerspectiveDao,
) {
    fun observeActive(userId: String): Flow<List<PerspectiveEntity>> =
        dao.observeActive(userId)

    /**
     * Seed a freshly-installed user with Home / Work / Errands so the
     * Perspectives view isn't empty on first open. No-op if the user
     * already has any active perspectives — prevents re-seeding after
     * the user deletes everything on purpose.
     */
    suspend fun ensureSeed(userId: String) {
        if (dao.countActive(userId) > 0) return
        val now = IsoClock.nowIso()
        val defaults = listOf(
            Triple("home",    "home",          0),
            Triple("work",    "work",          1),
            Triple("errands", "shopping_cart", 2),
        )
        defaults.forEach { (name, icon, order) ->
            dao.upsert(
                PerspectiveEntity(
                    id        = Uuidv7.generate(),
                    userId    = userId,
                    name      = name,
                    iconKey   = icon,
                    sortOrder = order,
                    createdAt = now,
                    updatedAt = now,
                )
            )
        }
    }

    /**
     * Create a new perspective. Returns the created (or pre-existing,
     * if the name was already taken) entity, or null if the
     * normalised name ends up empty. Sort-order lands at the tail.
     */
    suspend fun create(
        userId: String,
        rawName: String,
        iconKey: String = "label",
    ): PerspectiveEntity? {
        val normalized = normalizeName(rawName)
        if (normalized.isEmpty()) return null
        dao.findByName(userId, normalized)?.let { return it }
        val now = IsoClock.nowIso()
        val tail = dao.countActive(userId)
        val entity = PerspectiveEntity(
            id        = Uuidv7.generate(),
            userId    = userId,
            name      = normalized,
            iconKey   = iconKey.ifBlank { "label" },
            sortOrder = tail,
            createdAt = now,
            updatedAt = now,
        )
        dao.upsert(entity)
        return entity
    }

    /**
     * Idempotent "upsert by name" — used by the task write-path when
     * a user types `@foo` in a task and no matching tile exists yet.
     * Returns the entity (existing or newly-created) or null if the
     * name normalises away to nothing.
     */
    suspend fun ensure(userId: String, rawName: String): PerspectiveEntity? {
        val normalized = normalizeName(rawName)
        if (normalized.isEmpty()) return null
        dao.findByName(userId, normalized)?.let { return it }
        return create(userId, normalized)
    }

    suspend fun softDelete(id: String) {
        dao.softDelete(id = id, nowIso = IsoClock.nowIso())
    }

    /**
     * Normalise a user-entered name into a slug that can safely sit
     * in a `@tag` and round-trip through [extractContext]:
     *  - lowercase
     *  - trim whitespace
     *  - drop a leading `@` if the user typed it
     *  - replace whitespace / dots / slashes with `-`
     *  - strip anything not `[a-z0-9_-]`
     *  - collapse runs of `-` / `_` to a single character
     */
    internal fun normalizeName(raw: String): String {
        val trimmed = raw.trim().removePrefix("@").lowercase()
        val dashed  = trimmed.replace(Regex("[\\s./\\\\]+"), "-")
        val cleaned = dashed.replace(Regex("[^a-z0-9_-]"), "")
        return cleaned.replace(Regex("[-_]{2,}"), "-").trim('-', '_')
    }
}
