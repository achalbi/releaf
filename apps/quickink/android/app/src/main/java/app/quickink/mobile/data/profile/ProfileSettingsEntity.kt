/*
 * ProfileSettingsEntity.kt
 *
 * Room @Entity for the `profile_settings` table — the user's
 * profile-page state (display name override, phone, personality
 * line, profile photo URI + Drive linkage). Single row per user.
 *
 * Why this is its own table rather than columns on a hypothetical
 * `users` table: QuickInk doesn't model users as Room entities
 * (the canonical user identity lives in :shared:auth's session
 * store). Instead each per-user row across the app is keyed by
 * `user_id`. Profile settings follow the same pattern, with
 * `id == user_id` (one row per user).
 *
 * Sync columns mirror TagEntity's shape (drive_file_id,
 * created_at, updated_at, dirty, deleted_at) so the entity slots
 * into QuickInkSyncDataSource via the same dirty-batch / tombstone
 * machinery without bespoke handling. The `deleted_at` column is
 * almost never set in practice (a user always has a profile while
 * signed in), but it's required for sync framework parity.
 *
 * Photo binary linkage: `photo_local_uri` is the file:// URI on
 * THIS device's filesystem (filesDir/profile_photo.jpg). It's
 * device-local — never sent to Drive, never read from a remote
 * payload. The cross-device link is `photo_drive_file_id` (the
 * Drive file id for the binary blob) plus `photo_updated_at` (so
 * the restore worker knows when it has a stale local copy and
 * needs to re-download).
 */

package app.quickink.mobile.data.profile

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "profile_settings",
    indices = [
        Index(value = ["user_id"], unique = true),
        Index(value = ["dirty"], name = "idx_profile_settings_dirty"),
        Index(value = ["deleted_at"], name = "idx_profile_settings_tombstone"),
    ],
)
data class ProfileSettingsEntity(
    /**
     * Single-row PK. We use the user id as the entity id (one
     * profile per user). Mirrors the iOS shape so the cross-app
     * sync round-trip doesn't need an id-translation step.
     */
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "user_id")
    val userId: String,

    /**
     * User-set override of the auth-derived display name (e.g.
     * Google account name). Null means "use the auth-provider
     * name". Trimmed-empty input is normalised to null at the DAO
     * layer.
     */
    @ColumnInfo(name = "display_name")
    val displayName: String?,

    @ColumnInfo(name = "phone_number")
    val phoneNumber: String?,

    @ColumnInfo(name = "personality_punchline")
    val personalityPunchline: String?,

    /**
     * Local file URI of the photo on THIS device. Not synced.
     * `photo_drive_file_id` + `photo_updated_at` are the synced
     * fields that let other devices know to download the binary.
     */
    @ColumnInfo(name = "photo_local_uri")
    val photoLocalUri: String?,

    /**
     * Drive file id of the photo binary. Set after the photo
     * uploads successfully. Restore reads this to fetch the
     * binary on a new device.
     */
    @ColumnInfo(name = "photo_drive_file_id")
    val photoDriveFileId: String?,

    /**
     * ISO-8601 timestamp of when the photo was last changed
     * (picked / cleared). Distinct from `updated_at` (which moves
     * any time any field in the row changes) so the binary-restore
     * step can compare against a stable "is the local copy
     * already up-to-date?" anchor without re-downloading on every
     * metadata-only change (e.g. display name edit).
     */
    @ColumnInfo(name = "photo_updated_at")
    val photoUpdatedAt: String?,

    /**
     * Drive file id of the JSON metadata for this row. Mirrors
     * the same column on every other synced entity — populated by
     * QuickInkSyncDataSource.markSynced() when the metadata blob
     * is uploaded.
     */
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
