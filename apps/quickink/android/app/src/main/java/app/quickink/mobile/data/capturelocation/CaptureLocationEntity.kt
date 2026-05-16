/*
 * CaptureLocationEntity.kt
 *
 * Room @Entity for the `capture_locations` many-to-many join — links
 * captures to user-defined locations. Each row syncs independently
 * (own id + dirty + tombstone trio) so a location added on phone A
 * reaches phone B without re-syncing the entire capture. Mirror of
 * CaptureTagEntity.
 */

package app.quickink.mobile.data.capturelocation

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.quickink.mobile.data.capture.CaptureEntity
import app.quickink.mobile.data.location.LocationEntity

@Entity(
    tableName = "capture_locations",
    foreignKeys = [
        ForeignKey(
            entity = CaptureEntity::class,
            parentColumns = ["id"],
            childColumns = ["capture_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = LocationEntity::class,
            parentColumns = ["id"],
            childColumns = ["location_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(value = ["capture_id"], name = "idx_capture_locations_capture"),
        Index(value = ["location_id"], name = "idx_capture_locations_location"),
        Index(value = ["dirty"], name = "idx_capture_locations_dirty"),
        Index(value = ["deleted_at"], name = "idx_capture_locations_tombstone"),
        Index(value = ["capture_id", "location_id"], name = "idx_capture_locations_pair"),
    ],
)
data class CaptureLocationEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "capture_id")
    val captureId: String,

    @ColumnInfo(name = "location_id")
    val locationId: String,

    /**
     * Provenance of this location assignment. `"manual"` is the
     * common case (user picked it from the location chip rail);
     * future sources land without a migration.
     */
    @ColumnInfo(name = "source", defaultValue = "manual")
    val source: String = "manual",

    @ColumnInfo(name = "drive_file_id")
    val driveFileId: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: String,

    @ColumnInfo(name = "updated_at")
    val updatedAt: String,

    @ColumnInfo(name = "dirty", defaultValue = "1")
    val dirty: Boolean = true,

    @ColumnInfo(name = "deleted_at")
    val deletedAt: String? = null,
)
