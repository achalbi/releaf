/*
 * CapturePersonEntity.kt
 *
 * Room @Entity for the `capture_people` many-to-many join — links
 * captures to user-defined people. Each row syncs independently
 * (own id + dirty + tombstone trio). Mirror of CaptureLocationEntity.
 */

package app.quickink.mobile.data.captureperson

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.quickink.mobile.data.capture.CaptureEntity
import app.quickink.mobile.data.person.PersonEntity

@Entity(
    tableName = "capture_people",
    foreignKeys = [
        ForeignKey(
            entity = CaptureEntity::class,
            parentColumns = ["id"],
            childColumns = ["capture_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = PersonEntity::class,
            parentColumns = ["id"],
            childColumns = ["person_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(value = ["capture_id"], name = "idx_capture_people_capture"),
        Index(value = ["person_id"], name = "idx_capture_people_person"),
        Index(value = ["dirty"], name = "idx_capture_people_dirty"),
        Index(value = ["deleted_at"], name = "idx_capture_people_tombstone"),
        Index(value = ["capture_id", "person_id"], name = "idx_capture_people_pair"),
    ],
)
data class CapturePersonEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "capture_id")
    val captureId: String,

    @ColumnInfo(name = "person_id")
    val personId: String,

    /**
     * Provenance of this assignment. `"manual"` is the common case;
     * future sources (e.g. AI face-recognition) land without a
     * migration.
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
