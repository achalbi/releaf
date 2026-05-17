/*
 * StoryVoiceClipEntity.kt
 *
 * Room @Entity for the `story_voice_clip` table — one row per inline
 * voice clip attached to a `StoryItem` of `kind = 'voice_clip'`.
 * Mirror of the existing `voice_notes` table but keyed off
 * `story_item_id` rather than `capture_id`. The .m4a lives in
 * AttachmentStorage; this row points at it through `audio_uri`.
 *
 * AAC-LC 64 kbps mono, max 10 s per the handoff doc. Drive sync
 * mirrors voice_notes: two drive-id columns so transcript edits
 * don't re-upload the binary.
 *
 * Mirror of iOS `StoryVoiceClip` in `Stories/StoryModels.swift`.
 */

package app.quickink.mobile.data.storyvoiceclip

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.quickink.mobile.data.storyitem.StoryItemEntity

@Entity(
    tableName = "story_voice_clip",
    foreignKeys = [
        ForeignKey(
            entity         = StoryItemEntity::class,
            parentColumns  = ["id"],
            childColumns   = ["story_item_id"],
            onDelete       = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["story_item_id", "created_at"], name = "idx_story_voice_clip_item"),
        Index(value = ["user_id", "created_at"], name = "idx_story_voice_clip_user"),
        Index(value = ["dirty"], name = "idx_story_voice_clip_dirty"),
        Index(value = ["deleted_at"], name = "idx_story_voice_clip_tombstone"),
    ],
)
data class StoryVoiceClipEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "story_item_id")
    val storyItemId: String,

    @ColumnInfo(name = "user_id")
    val userId: String,

    /** `file://` absolute path of the on-disk .m4a. */
    @ColumnInfo(name = "audio_uri")
    val audioUri: String,

    @ColumnInfo(name = "duration_ms", defaultValue = "0")
    val durationMs: Long = 0,

    @ColumnInfo(name = "transcription")
    val transcription: String? = null,

    @ColumnInfo(name = "transcription_source")
    val transcriptionSource: String? = null,

    /** Drive id of the JSON metadata row. */
    @ColumnInfo(name = "drive_file_id")
    val driveFileId: String? = null,

    /** Drive id of the uploaded .m4a binary. */
    @ColumnInfo(name = "audio_drive_file_id")
    val audioDriveFileId: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: String,

    @ColumnInfo(name = "updated_at")
    val updatedAt: String,

    @ColumnInfo(name = "dirty", defaultValue = "1")
    val dirty: Boolean = true,

    @ColumnInfo(name = "deleted_at")
    val deletedAt: String? = null,
)
