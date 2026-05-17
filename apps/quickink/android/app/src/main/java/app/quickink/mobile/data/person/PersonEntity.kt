/*
 * PersonEntity.kt
 *
 * Room @Entity for the `people` table — user-defined people ("Me",
 * "Mom", "Dr. Rao", etc.) that captures can optionally be tagged
 * with. Many-to-many to captures via `capture_people`, mirroring
 * the locations / capture_locations and tags / capture_tags shapes.
 *
 * Seeded with "Me" on first launch (PersonRepository.seedDefaultsIfEmpty).
 */

package app.quickink.mobile.data.person

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "people",
    indices = [
        Index(value = ["user_id", "name"], unique = true),
        Index(value = ["user_id", "position"], name = "idx_people_user_position"),
        Index(value = ["dirty"], name = "idx_people_dirty"),
        Index(value = ["deleted_at"], name = "idx_people_tombstone"),
    ],
)
data class PersonEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "user_id")
    val userId: String,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "position", defaultValue = "0")
    val position: Int,

    /**
     * Optional hex color for the person chip (e.g. "#E66943"). NULL →
     * UI falls back to the accent tint.
     */
    @ColumnInfo(name = "color")
    val color: String? = null,

    /**
     * Optional link to a device contact. `lookup_key` is the stable
     * ContactsContract identifier (survives Android contact-id
     * shuffles); `phone`, `email`, `photo_uri` are cached snapshots
     * captured at link time so the row renders consistently without
     * hitting the contacts ContentProvider on every read.
     *
     * Only `phone` and `email` are portable across devices — those
     * travel in the sync payload. `lookup_key` and `photo_uri` are
     * device-local and stay out of the wire shape.
     */
    @ColumnInfo(name = "contact_lookup_key")
    val contactLookupKey: String? = null,

    @ColumnInfo(name = "contact_phone")
    val contactPhone: String? = null,

    @ColumnInfo(name = "contact_email")
    val contactEmail: String? = null,

    @ColumnInfo(name = "contact_photo_uri")
    val contactPhotoUri: String? = null,

    @ColumnInfo(name = "drive_file_id")
    val driveFileId: String?,

    @ColumnInfo(name = "created_at")
    val createdAt: String,

    @ColumnInfo(name = "updated_at")
    val updatedAt: String,

    @ColumnInfo(name = "dirty", defaultValue = "1")
    val dirty: Boolean,

    @ColumnInfo(name = "deleted_at")
    val deletedAt: String?,
)
