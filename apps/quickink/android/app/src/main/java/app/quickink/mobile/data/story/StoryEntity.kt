/*
 * StoryEntity.kt
 *
 * Room @Entity for the `story` table — one row per curated narrative
 * the user has assembled (see design/STORIES_HANDOFF.md). The items
 * inside a story live in `story_item` (sibling package); this row
 * carries only the story-level metadata: title, cover, theme, time
 * range, and share state.
 *
 * Schema mirrors iOS GRDB migration v14_stories. `cover_item_id` is
 * intentionally NOT declared as a Room foreign key — the handoff
 * doc's don't-do list requires it to be NULLed rather than dropped
 * when the referenced item is removed, which we enforce in
 * `StoryRepository.softDeleteItem`. A Room @ForeignKey with
 * ON DELETE SET NULL would also create a circular reference between
 * the two tables, which Room rejects.
 *
 * Mirror of iOS `Story` in `Stories/StoryModels.swift`.
 */

package app.quickink.mobile.data.story

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "story",
    indices = [
        Index(value = ["user_id", "updated_at"], name = "idx_story_user"),
        Index(value = ["dirty"], name = "idx_story_dirty"),
        Index(value = ["deleted_at"], name = "idx_story_tombstone"),
    ],
)
data class StoryEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "user_id")
    val userId: String,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "subtitle")
    val subtitle: String? = null,

    /**
     * Optional pointer to a `story_item.id` whose backing photo /
     * scan supplies the cover hero. NULL means "no cover picked
     * yet" — the reader + shelf both fall back to a paper-warm
     * gradient cover when this is unset (per the handoff doc's
     * smart-cover answer to non-photo stories).
     */
    @ColumnInfo(name = "cover_item_id")
    val coverItemId: String? = null,

    /** Raw value of `Story.CoverStyle`: photo / typographic / gradient. */
    @ColumnInfo(name = "cover_style", defaultValue = "'photo'")
    val coverStyle: String = "photo",

    /** Raw value of `Story.ThemeStyle`: editorial / scrapbook / minimal. */
    @ColumnInfo(name = "theme_style", defaultValue = "'editorial'")
    val themeStyle: String = "editorial",

    /** Raw value of `Story.GroupingMode`: timeline / activity / custom. */
    @ColumnInfo(name = "grouping_mode", defaultValue = "'timeline'")
    val groupingMode: String = "timeline",

    @ColumnInfo(name = "time_range_start")
    val timeRangeStart: String? = null,

    @ColumnInfo(name = "time_range_end")
    val timeRangeEnd: String? = null,

    /** Raw value of `Story.Status`: draft / published. */
    @ColumnInfo(name = "status", defaultValue = "'draft'")
    val status: String = "draft",

    /** Raw value of `Story.ShareMode`: private / public_link / in_app / exported. */
    @ColumnInfo(name = "share_mode", defaultValue = "'private'")
    val shareMode: String = "private",

    @ColumnInfo(name = "share_slug")
    val shareSlug: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: String,

    @ColumnInfo(name = "updated_at")
    val updatedAt: String,

    @ColumnInfo(name = "dirty", defaultValue = "1")
    val dirty: Boolean = true,

    @ColumnInfo(name = "deleted_at")
    val deletedAt: String? = null,
) {
    enum class CoverStyle(val raw: String) {
        PHOTO("photo"), TYPOGRAPHIC("typographic"), GRADIENT("gradient"),
    }
    enum class ThemeStyle(val raw: String) {
        EDITORIAL("editorial"), SCRAPBOOK("scrapbook"), MINIMAL("minimal"),
    }
    enum class GroupingMode(val raw: String) {
        TIMELINE("timeline"), ACTIVITY("activity"), CUSTOM("custom"),
    }
    enum class Status(val raw: String) {
        DRAFT("draft"), PUBLISHED("published"),
    }
    enum class ShareMode(val raw: String) {
        PRIVATE("private"),
        PUBLIC_LINK("public_link"),
        IN_APP("in_app"),
        EXPORTED("exported"),
    }
}
