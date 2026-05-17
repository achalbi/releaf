/*
 * StoryItemEntity.kt
 *
 * Room @Entity for the `story_item` table — one row per entry in a
 * story (see design/STORIES_HANDOFF.md). `kind` discriminates between
 * a reference to an existing capture / photo / note / voice-clip
 * (`refId` carries the foreign id) and an inline block (`text`
 * carries the body). `position` is 1024-spaced so a reorder is a
 * single update (`new = (prev + next) / 2`) instead of a full
 * rewrite — the repository renormalizes on collision.
 *
 * The `story_id` foreign key cascades on delete so removing a whole
 * story removes all its items. `cover_item_id` on the parent
 * `StoryEntity` is the inverse direction and is NOT a SQL foreign
 * key — see `StoryEntity.kt` for why.
 *
 * Mirror of iOS `StoryItem` in `Stories/StoryModels.swift`.
 */

package app.quickink.mobile.data.storyitem

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.quickink.mobile.data.story.StoryEntity

@Entity(
    tableName = "story_item",
    foreignKeys = [
        ForeignKey(
            entity         = StoryEntity::class,
            parentColumns  = ["id"],
            childColumns   = ["story_id"],
            onDelete       = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["story_id", "position"], name = "idx_story_item_story_pos"),
        Index(value = ["dirty"], name = "idx_story_item_dirty"),
        Index(value = ["deleted_at"], name = "idx_story_item_tombstone"),
    ],
)
data class StoryItemEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "story_id")
    val storyId: String,

    /** 1024-spaced ordinal — see file header. */
    @ColumnInfo(name = "position")
    val position: Int,

    /** Raw value of `StoryItemEntity.Kind`. See the enum for legal values. */
    @ColumnInfo(name = "kind")
    val kind: String,

    /**
     * Foreign id of the source row when `kind` references an existing
     * capture/photo/note/voice-clip. NULL for the inline kinds
     * (text_block / handwritten_note / date_divider / place_pin).
     */
    @ColumnInfo(name = "ref_id")
    val refId: String? = null,

    /**
     * Inline text body for the inline kinds (text_block /
     * handwritten_note). NULL for reference kinds.
     */
    @ColumnInfo(name = "text")
    val text: String? = null,

    @ColumnInfo(name = "caption")
    val caption: String? = null,

    /**
     * Author-overridable timestamp used for ordering + the reader's
     * day markers. NULL falls back to the source item's date (via the
     * sibling capture/photo's `created_at`).
     */
    @ColumnInfo(name = "occurred_at")
    val occurredAt: String? = null,

    /** Raw value of `StoryItemEntity.Layout`: full / half / grid. */
    @ColumnInfo(name = "layout", defaultValue = "'full'")
    val layout: String = "full",

    @ColumnInfo(name = "created_at")
    val createdAt: String,

    @ColumnInfo(name = "updated_at")
    val updatedAt: String,

    @ColumnInfo(name = "dirty", defaultValue = "1")
    val dirty: Boolean = true,

    @ColumnInfo(name = "deleted_at")
    val deletedAt: String? = null,
) {
    enum class Kind(val raw: String) {
        DOCUMENT("document"),
        PHOTO("photo"),
        NOTE("note"),
        VOICE_CLIP("voice_clip"),
        TEXT_BLOCK("text_block"),
        HANDWRITTEN_NOTE("handwritten_note"),
        DATE_DIVIDER("date_divider"),
        PLACE_PIN("place_pin"),
    }
    enum class Layout(val raw: String) {
        FULL("full"), HALF("half"), GRID("grid"),
    }
}
