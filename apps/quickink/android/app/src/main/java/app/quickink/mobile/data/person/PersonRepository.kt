/*
 * PersonRepository.kt
 *
 * Wraps [PersonDao] for the Home chip rail, the Workspace section,
 * and the capture-detail picker. Mirror of LocationRepository.
 */

package app.quickink.mobile.data.person

import app.releaf.mobile.data.common.IsoClock
import app.releaf.mobile.data.common.Uuidv7
import kotlinx.coroutines.flow.Flow

class PersonRepository(
    private val personDao: PersonDao,
) {

    fun observe(userId: String): Flow<List<PersonEntity>> =
        personDao.observeActive(userId)

    suspend fun list(userId: String): List<PersonEntity> =
        personDao.listActive(userId)

    /**
     * Insert a single person. Returns `false` if the (user_id, name)
     * UNIQUE constraint already has this name — the existing row is
     * left untouched.
     */
    suspend fun insert(
        userId: String,
        name: String,
        position: Int,
        id: String = Uuidv7.generate(),
        color: String? = null,
        contactLookupKey: String? = null,
        contactPhone: String? = null,
        contactEmail: String? = null,
        contactPhotoUri: String? = null,
    ): Boolean {
        val now = IsoClock.nowIso()
        val rowId = personDao.insert(
            PersonEntity(
                id               = id,
                userId           = userId,
                name             = name,
                position         = position,
                color            = color,
                contactLookupKey = contactLookupKey,
                contactPhone     = contactPhone,
                contactEmail     = contactEmail,
                contactPhotoUri  = contactPhotoUri,
                driveFileId      = null,
                createdAt        = now,
                updatedAt        = now,
                dirty            = true,
                deletedAt        = null,
            ),
        )
        return rowId != -1L
    }

    /** Set or clear the linked device contact + cached fields. */
    suspend fun setContactLink(
        id: String,
        lookupKey: String?,
        phone: String?,
        email: String?,
        photoUri: String?,
    ) {
        personDao.setContact(
            id        = id,
            lookupKey = lookupKey,
            phone     = phone,
            email     = email,
            photoUri  = photoUri,
            timestamp = IsoClock.nowIso(),
        )
    }

    /**
     * Find-or-create by name within a user's namespace. Used by the
     * Home chip-rail "Add" affordance and the capture-detail picker.
     */
    suspend fun findOrCreate(
        userId: String,
        name: String,
        contactLookupKey: String? = null,
        contactPhone: String? = null,
        contactEmail: String? = null,
        contactPhotoUri: String? = null,
    ): PersonEntity {
        personDao.findByName(userId, name)?.let { return it }
        val now = IsoClock.nowIso()
        val candidate = PersonEntity(
            id               = Uuidv7.generate(),
            userId           = userId,
            name             = name,
            position         = Int.MAX_VALUE,
            color            = null,
            contactLookupKey = contactLookupKey,
            contactPhone     = contactPhone,
            contactEmail     = contactEmail,
            contactPhotoUri  = contactPhotoUri,
            driveFileId      = null,
            createdAt        = now,
            updatedAt        = now,
            dirty            = true,
            deletedAt        = null,
        )
        val rowId = personDao.insert(candidate)
        return if (rowId != -1L) candidate
        else personDao.findByName(userId, name)
            ?: error("Race: insert returned -1 but findByName missed for name=$name")
    }

    suspend fun rename(id: String, newName: String) {
        personDao.rename(id, newName, IsoClock.nowIso())
    }

    suspend fun softDelete(id: String) {
        personDao.softDelete(id, IsoClock.nowIso())
    }

    suspend fun setColor(id: String, color: String?) {
        personDao.setColor(id, color, IsoClock.nowIso())
    }

    suspend fun reorder(ids: List<String>) {
        val now = IsoClock.nowIso()
        ids.forEachIndexed { index, id ->
            personDao.setPosition(id, index, now)
        }
    }

    /**
     * Seed the default people if the user has no active rows.
     * Idempotent — call from app launch or first sign-in. Honors
     * the (user_id, name) UNIQUE constraint, so a partial earlier
     * seed run is safe to retry.
     */
    suspend fun seedDefaultsIfEmpty(userId: String) {
        if (personDao.listActive(userId).isNotEmpty()) return
        DEFAULT_SEED.forEachIndexed { index, name ->
            insert(userId = userId, name = name, position = index)
        }
    }

    companion object {
        /** Default seed people rendered as the user's starting set. */
        val DEFAULT_SEED: List<String> = listOf("Me")

        fun isPredefined(name: String): Boolean = name in DEFAULT_SEED
    }
}
