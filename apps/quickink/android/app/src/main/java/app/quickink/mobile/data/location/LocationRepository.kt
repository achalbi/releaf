/*
 * LocationRepository.kt
 *
 * Wraps [LocationDao] for the Home chip rail (create / rename /
 * reorder / soft-delete) and the location picker on capture detail.
 * Mirror of TagRepository.
 */

package app.quickink.mobile.data.location

import app.releaf.mobile.data.common.IsoClock
import app.releaf.mobile.data.common.Uuidv7
import kotlinx.coroutines.flow.Flow

class LocationRepository(
    private val locationDao: LocationDao,
) {

    fun observe(userId: String): Flow<List<LocationEntity>> =
        locationDao.observeActive(userId)

    suspend fun list(userId: String): List<LocationEntity> =
        locationDao.listActive(userId)

    /**
     * Insert a single location. Returns `false` if the (user_id, name)
     * UNIQUE constraint already has this name — the existing row is
     * left untouched.
     */
    suspend fun insert(
        userId: String,
        name: String,
        position: Int,
        id: String = Uuidv7.generate(),
        color: String? = null,
        latitude: Double? = null,
        longitude: Double? = null,
        address: String? = null,
    ): Boolean {
        val now = IsoClock.nowIso()
        val rowId = locationDao.insert(
            LocationEntity(
                id           = id,
                userId       = userId,
                name         = name,
                position     = position,
                color        = color,
                latitude     = latitude,
                longitude    = longitude,
                address      = address,
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
     * Home chip-rail "Add" affordance when the user types a name that
     * may or may not already exist.
     */
    suspend fun findOrCreate(
        userId: String,
        name: String,
        latitude: Double? = null,
        longitude: Double? = null,
        address: String? = null,
    ): LocationEntity {
        locationDao.findByName(userId, name)?.let { return it }
        val now = IsoClock.nowIso()
        val candidate = LocationEntity(
            id           = Uuidv7.generate(),
            userId       = userId,
            name         = name,
            position     = Int.MAX_VALUE,
            color        = null,
            latitude     = latitude,
            longitude    = longitude,
            address      = address,
            driveFileId  = null,
            createdAt    = now,
            updatedAt    = now,
            dirty        = true,
            deletedAt    = null,
        )
        val rowId = locationDao.insert(candidate)
        return if (rowId != -1L) candidate
        else locationDao.findByName(userId, name)
            ?: error("Race: insert returned -1 but findByName missed for name=$name")
    }

    /** Set or clear the physical place attached to a location row. */
    suspend fun setLocation(
        id: String,
        latitude: Double?,
        longitude: Double?,
        address: String?,
    ) {
        locationDao.setCoordinates(
            id        = id,
            latitude  = latitude,
            longitude = longitude,
            address   = address,
            timestamp = IsoClock.nowIso(),
        )
    }

    suspend fun rename(id: String, newName: String) {
        locationDao.rename(id, newName, IsoClock.nowIso())
    }

    suspend fun softDelete(id: String) {
        locationDao.softDelete(id, IsoClock.nowIso())
    }

    suspend fun setColor(id: String, color: String?) {
        locationDao.setColor(id, color, IsoClock.nowIso())
    }

    suspend fun reorder(ids: List<String>) {
        val now = IsoClock.nowIso()
        ids.forEachIndexed { index, id ->
            locationDao.setPosition(id, index, now)
        }
    }

    /**
     * Seed the default locations if the user has no active rows.
     * Idempotent — call from app launch or first sign-in. Honors the
     * (user_id, name) UNIQUE constraint, so a partial earlier seed
     * run is safe to retry.
     */
    suspend fun seedDefaultsIfEmpty(userId: String) {
        if (locationDao.listActive(userId).isNotEmpty()) return
        DEFAULT_SEED.forEachIndexed { index, name ->
            insert(userId = userId, name = name, position = index)
        }
    }

    companion object {
        /** Default seed locations rendered as the user's starting set. */
        val DEFAULT_SEED: List<String> = listOf("Home", "Work")

        fun isPredefined(name: String): Boolean = name in DEFAULT_SEED
    }
}
