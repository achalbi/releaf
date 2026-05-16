/*
 * VoiceNoteEntity.kt
 *
 * Room @Entity for the `voice_notes` table. One row per voice note
 * recorded on a document's detail screen — the clip itself lives in
 * AttachmentStorage as an .m4a file and the row points at it through
 * `audio_uri`. A capture can have any number of voice notes;
 * deleting the capture cascades to its notes via the foreign key.
 *
 * Schema mirrors iOS GRDB `v12_voice_notes` migration byte-for-byte
 * so the Drive sync payload round-trips between platforms.
 *
 * Two drive-id columns:
 *   - `drive_file_id`        — JSON metadata on Drive.
 *   - `audio_drive_file_id`  — uploaded .m4a binary.
 * Split so the sync worker can update the transcript without re-
 * uploading the audio, the common case after first sync.
 */

package app.quickink.mobile.data.voicenote

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.quickink.mobile.data.capture.CaptureEntity

@Entity(
    tableName = "voice_notes",
    foreignKeys = [
        ForeignKey(
            entity        = CaptureEntity::class,
            parentColumns = ["id"],
            childColumns  = ["capture_id"],
            onDelete      = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["capture_id", "created_at"], name = "idx_voice_notes_capture"),
        Index(value = ["user_id", "created_at"],    name = "idx_voice_notes_user"),
        Index(value = ["dirty"],      name = "idx_voice_notes_dirty"),
        Index(value = ["deleted_at"], name = "idx_voice_notes_tombstone"),
    ],
)
data class VoiceNoteEntity(

    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "capture_id")
    val captureId: String,

    @ColumnInfo(name = "user_id")
    val userId: String,

    /** `file://` URL of the on-disk .m4a (AAC, 16kHz mono, 96kbps). */
    @ColumnInfo(name = "audio_uri")
    val audioUri: String,

    /** Committed clip duration in milliseconds. */
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long,

    /** Speech-to-text result. Null until the user taps Transcribe. */
    @ColumnInfo(name = "transcription")
    val transcription: String? = null,

    /**
     * Which backend produced the transcript — one of
     * `SpeechTranscriber.BACKEND_MLKIT` / `BACKEND_SHERPA`, or
     * `"sfspeech"` for transcripts that came in from iOS via Drive.
     */
    @ColumnInfo(name = "transcription_source")
    val transcriptionSource: String? = null,

    /** Drive id for the JSON metadata row. NULL until first push. */
    @ColumnInfo(name = "drive_file_id")
    val driveFileId: String? = null,

    /** Drive id for the uploaded .m4a. NULL until first push. */
    @ColumnInfo(name = "audio_drive_file_id")
    val audioDriveFileId: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: String,

    @ColumnInfo(name = "updated_at")
    val updatedAt: String,

    @ColumnInfo(name = "dirty")
    val dirty: Boolean = true,

    @ColumnInfo(name = "deleted_at")
    val deletedAt: String? = null,
)
