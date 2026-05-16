/*
 * LocationEntity.kt
 *
 * Room @Entity for the `locations` table — user-defined places
 * ("Home", "Work", "Cafe", etc.) that captures can optionally be
 * tagged with. Many-to-many to captures via `capture_locations`,
 * mirroring the `tags` ↔ `capture_tags` shape.
 *
 * Seeded with "Home" and "Work" on first launch (see
 * LocationRepository.seedDefaultsIfEmpty).
 */

package app.quickink.mobile.data.location

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "locations",
    indices = [
        Index(value = ["user_id", "name"], unique = true),
        Index(value = ["user_id", "position"], name = "idx_locations_user_position"),
        Index(value = ["dirty"], name = "idx_locations_dirty"),
        Index(value = ["deleted_at"], name = "idx_locations_tombstone"),
    ],
)
data class LocationEntity(
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
     * Optional hex color for the location chip (e.g. "#E66943"). NULL →
     * UI falls back to the accent tint.
     */
    @ColumnInfo(name = "color")
    val color: String? = null,

    /**
     * Coordinates + formatted address. All three are NULL for
     * locations whose physical place hasn't been set yet (the
     * seeded "Home" / "Work" rows, or a freshly-typed name).
     * Populated by the location editor via "Use current location"
     * (GPS + reverse geocode) or "Search address" (forward geocode).
     */
    @ColumnInfo(name = "latitude")
    val latitude: Double? = null,

    @ColumnInfo(name = "longitude")
    val longitude: Double? = null,

    @ColumnInfo(name = "address")
    val address: String? = null,

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
