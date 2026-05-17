/*
 * StoryRepository.kt
 *
 * Persistence + reactive reads for the Stories feature. Mirrors the
 * shape of `FolderRepository`: a small wrapper that owns the two
 * Room DAOs, exposes coroutine-suspended writes, and re-emits the
 * DAO's `Flow` reads for the shelf UI to collect.
 *
 * Phase 1 surface area: list / insert / soft-delete for both
 * `story` and `story_item`, plus the shelf projection
 * ([StoryShelfRow]) that bundles per-story item count + latest-item
 * date. The auto-suggestion engine (Phase 5) and Drive sync
 * (Phase 6) land on top of this without re-shaping the repo.
 *
 * Mirror of iOS `Stories/StoryRepository.swift`.
 */

package app.quickink.mobile.data.story

import app.quickink.mobile.data.storyitem.StoryItemDao
import app.quickink.mobile.data.storyitem.StoryItemEntity
import app.quickink.mobile.data.storyvoiceclip.StoryVoiceClipDao
import app.quickink.mobile.data.storyvoiceclip.StoryVoiceClipEntity
import app.releaf.mobile.data.common.IsoClock
import app.releaf.mobile.data.common.Uuidv7
import kotlinx.coroutines.flow.Flow

class StoryRepository(
    private val storyDao:     StoryDao,
    private val storyItemDao: StoryItemDao,
    private val storyVoiceClipDao: StoryVoiceClipDao? = null,
) {

    // ── Reads ───────────────────────────────────────────────────

    fun observeShelf(userId: String): Flow<List<StoryShelfRow>> =
        storyDao.observeShelf(userId)

    suspend fun listShelf(userId: String): List<StoryShelfRow> =
        storyDao.listShelf(userId)

    suspend fun fetchStory(id: String): StoryEntity? =
        storyDao.findById(id)

    fun observeItems(storyId: String): Flow<List<StoryItemEntity>> =
        storyItemDao.observeForStory(storyId)

    suspend fun listItems(storyId: String): List<StoryItemEntity> =
        storyItemDao.listForStory(storyId)

    suspend fun countActive(userId: String): Int =
        storyDao.countActive(userId)

    // ── Writes ──────────────────────────────────────────────────

    suspend fun insertStory(
        userId: String,
        title: String,
        subtitle: String? = null,
        coverStyle: StoryEntity.CoverStyle = StoryEntity.CoverStyle.PHOTO,
        themeStyle: StoryEntity.ThemeStyle = StoryEntity.ThemeStyle.EDITORIAL,
        groupingMode: StoryEntity.GroupingMode = StoryEntity.GroupingMode.TIMELINE,
        id: String = Uuidv7.generate(),
    ): StoryEntity? {
        val now = IsoClock.nowIso()
        val candidate = StoryEntity(
            id            = id,
            userId        = userId,
            title         = title,
            subtitle      = subtitle,
            coverItemId   = null,
            coverStyle    = coverStyle.raw,
            themeStyle    = themeStyle.raw,
            groupingMode  = groupingMode.raw,
            timeRangeStart = null,
            timeRangeEnd  = null,
            status        = StoryEntity.Status.DRAFT.raw,
            shareMode     = StoryEntity.ShareMode.PRIVATE.raw,
            shareSlug     = null,
            createdAt     = now,
            updatedAt     = now,
            dirty         = true,
            deletedAt     = null,
        )
        val rowId = storyDao.insert(candidate)
        return if (rowId != -1L) candidate else null
    }

    suspend fun insertItem(
        storyId: String,
        position: Int,
        kind: StoryItemEntity.Kind,
        refId: String? = null,
        text: String? = null,
        caption: String? = null,
        occurredAt: String? = null,
        layout: StoryItemEntity.Layout = StoryItemEntity.Layout.FULL,
        id: String = Uuidv7.generate(),
    ): StoryItemEntity? {
        val now = IsoClock.nowIso()
        val candidate = StoryItemEntity(
            id          = id,
            storyId     = storyId,
            position    = position,
            kind        = kind.raw,
            refId       = refId,
            text        = text,
            caption     = caption,
            occurredAt  = occurredAt,
            layout      = layout.raw,
            createdAt   = now,
            updatedAt   = now,
            dirty       = true,
            deletedAt   = null,
        )
        val rowId = storyItemDao.insert(candidate)
        return if (rowId != -1L) candidate else null
    }

    suspend fun setCoverItem(storyId: String, itemId: String?) {
        storyDao.setCoverItem(storyId, itemId, IsoClock.nowIso())
    }

    // ── Phase 6 publish ─────────────────────────────────────────

    /** Stamp `share_mode = public_link` + slug on the story row.
     *  Marks dirty so the next Drive sync push carries the share
     *  state. Mirror of iOS `markPublished`. */
    suspend fun markPublished(storyId: String, slug: String) {
        val now = IsoClock.nowIso()
        storyDao.setShareMode(
            id        = storyId,
            shareMode = StoryEntity.ShareMode.PUBLIC_LINK.raw,
            shareSlug = slug,
            status    = StoryEntity.Status.PUBLISHED.raw,
            timestamp = now,
        )
    }

    /** Revert a story from public_link to private. Clears slug,
     *  drops status back to draft. */
    suspend fun markUnpublished(storyId: String) {
        val now = IsoClock.nowIso()
        storyDao.setShareMode(
            id        = storyId,
            shareMode = StoryEntity.ShareMode.PRIVATE.raw,
            shareSlug = null,
            status    = StoryEntity.Status.DRAFT.raw,
            timestamp = now,
        )
    }

    suspend fun softDeleteStory(id: String) {
        storyDao.softDelete(id, IsoClock.nowIso())
    }

    /**
     * Soft-delete an item AND null any cover_item_id on its parent
     * story that points at it. Per the handoff doc's don't-do list:
     * "Don't drop the `cover_item_id` foreign key when the referenced
     * item is removed from the story. Null it instead. The story
     * should survive removal of its cover item."
     *
     * Also cascade-tombstones any attached voice clip — the SQL
     * ON DELETE CASCADE only fires on hard-delete, which we never do.
     */
    suspend fun softDeleteItem(id: String) {
        val now = IsoClock.nowIso()
        storyDao.clearCoverItemReferences(id, now)
        storyItemDao.softDelete(id, now)
        storyItemDao.softDeleteAttachedVoiceClips(id, now)
    }

    // ── Editor edits ────────────────────────────────────────────

    suspend fun updateTitle(storyId: String, title: String) {
        storyDao.setTitle(storyId, title, IsoClock.nowIso())
    }

    suspend fun updateSubtitle(storyId: String, subtitle: String?) {
        storyDao.setSubtitle(storyId, subtitle, IsoClock.nowIso())
    }

    suspend fun updateItemCaption(itemId: String, caption: String?) {
        storyItemDao.setCaption(itemId, caption, IsoClock.nowIso())
    }

    suspend fun updateItemText(itemId: String, text: String?) {
        storyItemDao.setText(itemId, text, IsoClock.nowIso())
    }

    suspend fun updateItemLayout(itemId: String, layout: StoryItemEntity.Layout) {
        storyItemDao.setLayout(itemId, layout.raw, IsoClock.nowIso())
    }

    /**
     * Commit a reordered position list to the DB. Each pair is
     * `(itemId, newPosition)`. Mirrors the pattern the drag-to-
     * reorder UI uses on release.
     */
    suspend fun updatePositions(updates: List<Pair<String, Int>>) {
        if (updates.isEmpty()) return
        val now = IsoClock.nowIso()
        updates.forEach { (itemId, position) ->
            storyItemDao.setPosition(itemId, position, now)
        }
    }

    // ── Voice clips ─────────────────────────────────────────────

    /**
     * Insert a freshly-recorded voice clip. Row lands dirty so the
     * next sync push uploads the .m4a + metadata. The caller is
     * responsible for first inserting the parent `story_item` of
     * `kind = 'voice_clip'` and passing its id here.
     */
    suspend fun insertVoiceClip(
        storyItemId: String,
        userId: String,
        audioUri: String,
        durationMs: Long,
        id: String = Uuidv7.generate(),
    ): StoryVoiceClipEntity? {
        val dao = storyVoiceClipDao ?: error("storyVoiceClipDao not wired")
        val now = IsoClock.nowIso()
        val candidate = StoryVoiceClipEntity(
            id                  = id,
            storyItemId         = storyItemId,
            userId              = userId,
            audioUri            = audioUri,
            durationMs          = durationMs,
            transcription       = null,
            transcriptionSource = null,
            driveFileId         = null,
            audioDriveFileId    = null,
            createdAt           = now,
            updatedAt           = now,
            dirty               = true,
            deletedAt           = null,
        )
        val rowId = dao.insert(candidate)
        return if (rowId != -1L) candidate else null
    }

    suspend fun fetchVoiceClipForItem(storyItemId: String): StoryVoiceClipEntity? {
        val dao = storyVoiceClipDao ?: error("storyVoiceClipDao not wired")
        return dao.findForItem(storyItemId)
    }

    // ── Dev seeding (release-stripped via the BuildConfig.DEBUG
    //    gate on the caller side; the methods themselves stay
    //    compile-time accessible so unit tests can exercise them) ──

    /**
     * Inserts the three fixture stories from
     * `design/stories-mockup-v3.html` §7.1 so the shelf has cards to
     * render in a fresh QA build. Idempotent — short-circuits when
     * the user already has any active stories.
     *
     * Called from `QuickInkRoot.MainShell` inside a `BuildConfig.DEBUG`
     * guard so production builds never invoke it.
     */
    suspend fun seedDevStoriesIfEmpty(userId: String) {
        if (countActive(userId) > 0) return
        seedDevStory(
            userId      = userId,
            title       = "Mira's first month",
            coverStyle  = StoryEntity.CoverStyle.PHOTO,
            shareMode   = StoryEntity.ShareMode.PUBLIC_LINK,
            itemCount   = 14,
            anchorYear  = 2026,
            anchorMonth = 4,
        )
        seedDevStory(
            userId      = userId,
            title       = "Lisbon notebook",
            coverStyle  = StoryEntity.CoverStyle.GRADIENT,
            shareMode   = StoryEntity.ShareMode.PRIVATE,
            itemCount   = 22,
            anchorYear  = 2026,
            anchorMonth = 3,
        )
        seedDevStory(
            userId      = userId,
            title       = "Renovation log",
            coverStyle  = StoryEntity.CoverStyle.TYPOGRAPHIC,
            shareMode   = StoryEntity.ShareMode.PUBLIC_LINK,
            itemCount   = 36,
            anchorYear  = 2026,
            anchorMonth = 5,
        )
    }

    private suspend fun seedDevStory(
        userId: String,
        title: String,
        coverStyle: StoryEntity.CoverStyle,
        shareMode: StoryEntity.ShareMode,
        itemCount: Int,
        anchorYear: Int,
        anchorMonth: Int,
    ) {
        val anchorIso = devSeedIso(anchorYear, anchorMonth, day = 15)
        val now       = IsoClock.nowIso()
        val storyId   = Uuidv7.generate()
        val status = if (shareMode == StoryEntity.ShareMode.PUBLIC_LINK)
            StoryEntity.Status.PUBLISHED else StoryEntity.Status.DRAFT
        val shareSlug = if (shareMode == StoryEntity.ShareMode.PUBLIC_LINK)
            "dev-${storyId.take(8)}" else null
        val story = StoryEntity(
            id            = storyId,
            userId        = userId,
            title         = title,
            subtitle      = null,
            coverItemId   = null,
            coverStyle    = coverStyle.raw,
            themeStyle    = StoryEntity.ThemeStyle.EDITORIAL.raw,
            groupingMode  = StoryEntity.GroupingMode.TIMELINE.raw,
            timeRangeStart = anchorIso,
            timeRangeEnd  = anchorIso,
            status        = status.raw,
            shareMode     = shareMode.raw,
            shareSlug     = shareSlug,
            createdAt     = anchorIso,
            updatedAt     = now,
            dirty         = true,
            deletedAt     = null,
        )
        storyDao.insert(story)
        for (index in 0 until itemCount) {
            storyItemDao.insert(
                StoryItemEntity(
                    id          = Uuidv7.generate(),
                    storyId     = storyId,
                    position    = (index + 1) * 1024,
                    kind        = StoryItemEntity.Kind.TEXT_BLOCK.raw,
                    refId       = null,
                    text        = "Dev item ${index + 1}.",
                    caption     = null,
                    occurredAt  = anchorIso,
                    layout      = StoryItemEntity.Layout.FULL.raw,
                    createdAt   = anchorIso,
                    updatedAt   = anchorIso,
                    dirty       = true,
                    deletedAt   = null,
                )
            )
        }
    }

    private fun devSeedIso(year: Int, month: Int, day: Int): String {
        val dt = java.time.OffsetDateTime.of(
            year, month, day, 12, 0, 0, 0,
            java.time.ZoneOffset.UTC,
        )
        // `IsoClock.nowIso()` emits "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'";
        // mirror that exact shape so the SQL MAX(...) sorts agree
        // with production rows in mixed datasets.
        return java.time.format.DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .format(dt)
    }
}
