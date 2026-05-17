/*
 * StoryRepository.swift
 *
 * Persistence + reactive reads for the Stories feature. Mirrors the
 * shape of `VoiceNoteRepository` — one shared `QuickInkDatabase.shared`
 * queue, GRDB's `ValueObservation` for the live shelf list, plain
 * async/await for writes.
 *
 * Phase 1 surface area: list / insert / softDelete for both `story`
 * and `story_item`, plus a shelf projection (`StoryShelfRow`) that
 * bundles the item count + latest-item date the §7.1 mockup card
 * needs. The auto-suggestion engine (Phase 5) and Drive sync (Phase
 * 6) land on top of this without re-shaping the repo.
 *
 * Mirror of Android `StoryRepository.kt`.
 */

import Combine
import Foundation
import GRDB
import ReleafCoreData

/// One row on the Stories shelf — a `Story` enriched with the two
/// values the §7.1 card surfaces alongside the title: how many items
/// the story holds, and the most recent item's date so the meta line
/// can render "14 items · Apr 2026".
public struct StoryShelfRow: Equatable {
    public let story: Story
    public let itemCount: Int
    public let latestItemAt: String?
}

public final class StoryRepository: @unchecked Sendable {

    private let dbQueue: DatabaseQueue

    public init(database: QuickInkDatabase = .shared) {
        self.dbQueue = database.dbQueue
    }

    // MARK: - Reads

    /// Live shelf list for the user — active stories, freshest first,
    /// each enriched with the item count + latest-item date. Backs the
    /// Stories tab.
    public func observeShelf(userId: String) -> AnyPublisher<[StoryShelfRow], Error> {
        ValueObservation
            .tracking { db in try Self.fetchShelfRows(db: db, userId: userId) }
            .publisher(in: dbQueue)
            .eraseToAnyPublisher()
    }

    public func listShelf(userId: String) async throws -> [StoryShelfRow] {
        try await dbQueue.read { db in
            try Self.fetchShelfRows(db: db, userId: userId)
        }
    }

    public func fetchStory(id: String) async throws -> Story? {
        try await dbQueue.read { db in
            try Story
                .filter(Story.Columns.id == id)
                .filter(Story.Columns.deletedAt == nil)
                .fetchOne(db)
        }
    }

    public func listItems(storyId: String) async throws -> [StoryItem] {
        try await dbQueue.read { db in
            try StoryItem
                .filter(StoryItem.Columns.storyId == storyId)
                .filter(StoryItem.Columns.deletedAt == nil)
                .order(StoryItem.Columns.position.asc)
                .fetchAll(db)
        }
    }

    // MARK: - Writes

    @discardableResult
    public func insertStory(
        userId: String,
        title: String,
        subtitle: String? = nil,
        coverStyle: Story.CoverStyle = .photo,
        themeStyle: Story.ThemeStyle = .editorial,
        groupingMode: Story.GroupingMode = .timeline
    ) async throws -> Story {
        let now = IsoClock.nowIso()
        var entity = Story(
            id:              Uuidv7.generate(),
            userId:          userId,
            title:           title,
            subtitle:        subtitle,
            coverItemId:     nil,
            coverStyle:      coverStyle.rawValue,
            themeStyle:      themeStyle.rawValue,
            groupingMode:    groupingMode.rawValue,
            timeRangeStart:  nil,
            timeRangeEnd:    nil,
            status:          Story.Status.draft.rawValue,
            shareMode:       Story.ShareMode.private.rawValue,
            shareSlug:       nil,
            createdAt:       now,
            updatedAt:       now,
            dirty:           true,
            deletedAt:       nil
        )
        try await dbQueue.write { db in
            try entity.insert(db)
        }
        return entity
    }

    @discardableResult
    public func insertItem(
        storyId: String,
        position: Int,
        kind: StoryItem.Kind,
        refId: String? = nil,
        text: String? = nil,
        caption: String? = nil,
        occurredAt: String? = nil,
        layout: StoryItem.Layout = .full
    ) async throws -> StoryItem {
        let now = IsoClock.nowIso()
        var entity = StoryItem(
            id:          Uuidv7.generate(),
            storyId:     storyId,
            position:    position,
            kind:        kind.rawValue,
            refId:       refId,
            text:        text,
            caption:     caption,
            occurredAt:  occurredAt,
            layout:      layout.rawValue,
            createdAt:   now,
            updatedAt:   now,
            dirty:       true,
            deletedAt:   nil
        )
        try await dbQueue.write { db in
            try entity.insert(db)
        }
        return entity
    }

    /// Set / clear the cover item id on a story. Used by the editor's
    /// "Set as cover" action; also called by the item-removal path
    /// when the removed item was the cover (per the handoff doc's
    /// don't-do list: never drop the FK, null it).
    public func updateTitle(storyId: String, title: String) async throws {
        let now = IsoClock.nowIso()
        try await dbQueue.write { db in
            try db.execute(sql: """
                UPDATE story
                SET title = ?, updated_at = ?, dirty = 1
                WHERE id = ?
                """, arguments: [title, now, storyId])
        }
    }

    public func updateSubtitle(storyId: String, subtitle: String?) async throws {
        let now = IsoClock.nowIso()
        try await dbQueue.write { db in
            try db.execute(sql: """
                UPDATE story
                SET subtitle = ?, updated_at = ?, dirty = 1
                WHERE id = ?
                """, arguments: [subtitle, now, storyId])
        }
    }

    public func updateItemCaption(itemId: String, caption: String?) async throws {
        let now = IsoClock.nowIso()
        try await dbQueue.write { db in
            try db.execute(sql: """
                UPDATE story_item
                SET caption = ?, updated_at = ?, dirty = 1
                WHERE id = ?
                """, arguments: [caption, now, itemId])
        }
    }

    public func updateItemText(itemId: String, text: String?) async throws {
        let now = IsoClock.nowIso()
        try await dbQueue.write { db in
            try db.execute(sql: """
                UPDATE story_item
                SET text = ?, updated_at = ?, dirty = 1
                WHERE id = ?
                """, arguments: [text, now, itemId])
        }
    }

    public func updateItemLayout(itemId: String, layout: StoryItem.Layout) async throws {
        let now = IsoClock.nowIso()
        try await dbQueue.write { db in
            try db.execute(sql: """
                UPDATE story_item
                SET layout = ?, updated_at = ?, dirty = 1
                WHERE id = ?
                """, arguments: [layout.rawValue, now, itemId])
        }
    }

    /// Commit a reordered position list to the DB in one transaction.
    /// Each tuple is `(itemId, newPosition)`. Mirrors the pattern the
    /// drag-to-reorder UI uses on release. Caller is responsible for
    /// keeping the new positions strictly monotonic; if two collide
    /// the underlying `INTEGER` column accepts both but the resulting
    /// order is undefined for the colliding pair — callers should
    /// renormalize to 1024-spaced integers when they see a collision.
    public func updatePositions(_ updates: [(itemId: String, position: Int)]) async throws {
        guard !updates.isEmpty else { return }
        let now = IsoClock.nowIso()
        try await dbQueue.write { db in
            for (itemId, position) in updates {
                try db.execute(sql: """
                    UPDATE story_item
                    SET position = ?, updated_at = ?, dirty = 1
                    WHERE id = ?
                    """, arguments: [position, now, itemId])
            }
        }
    }

    // MARK: - Voice clips

    /// Insert a freshly-recorded voice clip. Generates a UUIDv7 id +
    /// current timestamp. Row lands dirty so the next sync push uploads
    /// the .m4a binary + metadata row. `storyItemId` must point at a
    /// story_item of `kind = .voiceClip` — the editor inserts the
    /// parent item first, then the clip.
    @discardableResult
    public func insertVoiceClip(
        storyItemId: String,
        userId: String,
        audioUri: String,
        durationMs: Int
    ) async throws -> StoryVoiceClip {
        let now = IsoClock.nowIso()
        var entity = StoryVoiceClip(
            id:                  Uuidv7.generate(),
            storyItemId:         storyItemId,
            userId:              userId,
            audioUri:            audioUri,
            durationMs:          durationMs,
            transcription:       nil,
            transcriptionSource: nil,
            driveFileId:         nil,
            audioDriveFileId:    nil,
            createdAt:           now,
            updatedAt:           now,
            dirty:               true,
            deletedAt:           nil
        )
        try await dbQueue.write { db in
            try entity.insert(db)
        }
        return entity
    }

    public func fetchVoiceClipForItem(_ storyItemId: String) async throws -> StoryVoiceClip? {
        try await dbQueue.read { db in
            try StoryVoiceClip
                .filter(StoryVoiceClip.Columns.storyItemId == storyItemId)
                .filter(StoryVoiceClip.Columns.deletedAt == nil)
                .order(StoryVoiceClip.Columns.createdAt.asc)
                .fetchOne(db)
        }
    }

    // MARK: - Phase 6 publish

    /// Stamp `share_mode = public_link` + the returned slug on the
    /// story row. Marks dirty so the next Drive sync push carries
    /// the updated share state. Mirror of Android `markPublished`.
    public func markPublished(storyId: String, slug: String) async throws {
        let now = IsoClock.nowIso()
        try await dbQueue.write { db in
            try db.execute(sql: """
                UPDATE story
                SET share_mode = ?,
                    share_slug = ?,
                    status     = ?,
                    updated_at = ?,
                    dirty      = 1
                WHERE id = ?
                """, arguments: [
                    Story.ShareMode.publicLink.rawValue,
                    slug,
                    Story.Status.published.rawValue,
                    now,
                    storyId,
                ])
        }
    }

    /// Revert a story from `public_link` to `private`. Clears the
    /// slug. Status drops back to `draft` so the shelf pill goes
    /// from "Public link" to "Draft" in the same sync hop.
    public func markUnpublished(storyId: String) async throws {
        let now = IsoClock.nowIso()
        try await dbQueue.write { db in
            try db.execute(sql: """
                UPDATE story
                SET share_mode = ?,
                    share_slug = NULL,
                    status     = ?,
                    updated_at = ?,
                    dirty      = 1
                WHERE id = ?
                """, arguments: [
                    Story.ShareMode.private.rawValue,
                    Story.Status.draft.rawValue,
                    now,
                    storyId,
                ])
        }
    }

    public func setCoverItem(storyId: String, itemId: String?) async throws {
        let now = IsoClock.nowIso()
        try await dbQueue.write { db in
            try db.execute(sql: """
                UPDATE story
                SET cover_item_id = ?,
                    updated_at = ?,
                    dirty = 1
                WHERE id = ?
                """, arguments: [itemId, now, storyId])
        }
    }

    public func softDeleteStory(id: String) async throws {
        let now = IsoClock.nowIso()
        try await dbQueue.write { db in
            try db.execute(sql: """
                UPDATE story
                SET deleted_at = ?,
                    updated_at = ?,
                    dirty = 1
                WHERE id = ?
                """, arguments: [now, now, id])
        }
    }

    public func softDeleteItem(id: String) async throws {
        let now = IsoClock.nowIso()
        try await dbQueue.write { db in
            // Null the cover_item_id on the parent story if it
            // pointed at the item we're removing. Per the handoff
            // doc: "Don't drop the cover_item_id FK when the
            // referenced item is removed from the story. Null it
            // instead. The story should survive removal of its
            // cover item."
            try db.execute(sql: """
                UPDATE story
                SET cover_item_id = NULL,
                    updated_at = ?,
                    dirty = 1
                WHERE cover_item_id = ?
                """, arguments: [now, id])
            try db.execute(sql: """
                UPDATE story_item
                SET deleted_at = ?,
                    updated_at = ?,
                    dirty = 1
                WHERE id = ?
                """, arguments: [now, now, id])
            // Cascade the tombstone to any attached voice clip so
            // sync push propagates the delete; the SQL ON DELETE
            // CASCADE only fires on hard-delete, which we never do
            // in this app.
            try db.execute(sql: """
                UPDATE story_voice_clip
                SET deleted_at = ?,
                    updated_at = ?,
                    dirty = 1
                WHERE story_item_id = ? AND deleted_at IS NULL
                """, arguments: [now, now, id])
        }
    }

    // MARK: - Dev seeding

    #if DEBUG
    /// Debug-only seeder — inserts the three fixture stories from
    /// `design/stories-mockup-v3.html` §7.1 so the shelf has cards
    /// to render in a fresh QA build. Idempotent: short-circuits when
    /// the user already has any active stories. Production builds
    /// don't compile this method.
    public func seedDevStoriesIfEmpty(userId: String) async throws {
        let existing = try await dbQueue.read { db -> Int in
            try Story
                .filter(Story.Columns.userId == userId)
                .filter(Story.Columns.deletedAt == nil)
                .fetchCount(db)
        }
        guard existing == 0 else { return }

        try await seedDevStory(
            userId:      userId,
            title:       "Mira's first month",
            coverStyle:  .photo,
            shareMode:   .publicLink,
            itemCount:   14,
            anchorMonth: (year: 2026, month: 4)
        )
        try await seedDevStory(
            userId:      userId,
            title:       "Lisbon notebook",
            coverStyle:  .gradient,
            shareMode:   .private,
            itemCount:   22,
            anchorMonth: (year: 2026, month: 3)
        )
        try await seedDevStory(
            userId:      userId,
            title:       "Renovation log",
            coverStyle:  .typographic,
            shareMode:   .publicLink,
            itemCount:   36,
            anchorMonth: (year: 2026, month: 5)
        )
    }

    /// Insert one fixture story plus a handful of `textBlock` items
    /// so the shelf row's count + latest-item date round-trip
    /// through the same SQL the production path uses.
    private func seedDevStory(
        userId: String,
        title: String,
        coverStyle: Story.CoverStyle,
        shareMode: Story.ShareMode,
        itemCount: Int,
        anchorMonth: (year: Int, month: Int)
    ) async throws {
        let now = IsoClock.nowIso()
        let storyId = Uuidv7.generate()
        let anchor  = Self.devSeedIso(year: anchorMonth.year, month: anchorMonth.month, day: 15)
        var story = Story(
            id:              storyId,
            userId:          userId,
            title:           title,
            subtitle:        nil,
            coverItemId:     nil,
            coverStyle:      coverStyle.rawValue,
            themeStyle:      Story.ThemeStyle.editorial.rawValue,
            groupingMode:    Story.GroupingMode.timeline.rawValue,
            timeRangeStart:  anchor,
            timeRangeEnd:    anchor,
            status:          shareMode == .publicLink
                ? Story.Status.published.rawValue
                : Story.Status.draft.rawValue,
            shareMode:       shareMode.rawValue,
            shareSlug:       shareMode == .publicLink ? "dev-\(storyId.prefix(8))" : nil,
            createdAt:       anchor,
            updatedAt:       now,
            dirty:           true,
            deletedAt:       nil
        )
        try await dbQueue.write { db in
            try story.insert(db)
            for index in 0..<itemCount {
                var item = StoryItem(
                    id:          Uuidv7.generate(),
                    storyId:     storyId,
                    position:    (index + 1) * 1024,
                    kind:        StoryItem.Kind.textBlock.rawValue,
                    refId:       nil,
                    text:        "Dev item \(index + 1).",
                    caption:     nil,
                    occurredAt:  anchor,
                    layout:      StoryItem.Layout.full.rawValue,
                    createdAt:   anchor,
                    updatedAt:   anchor,
                    dirty:       true,
                    deletedAt:   nil
                )
                try item.insert(db)
            }
        }
    }

    private static func devSeedIso(year: Int, month: Int, day: Int) -> String {
        var components = DateComponents()
        components.year = year
        components.month = month
        components.day = day
        components.hour = 12
        components.timeZone = TimeZone(identifier: "UTC")
        let date = Calendar(identifier: .gregorian).date(from: components) ?? Date()
        let fmt = ISO8601DateFormatter()
        fmt.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        fmt.timeZone = TimeZone(identifier: "UTC")
        return fmt.string(from: date)
    }
    #endif

    // MARK: - Internals

    private static func fetchShelfRows(db: Database, userId: String) throws -> [StoryShelfRow] {
        let stories = try Story
            .filter(Story.Columns.userId == userId)
            .filter(Story.Columns.deletedAt == nil)
            .order(Story.Columns.updatedAt.desc)
            .fetchAll(db)

        guard !stories.isEmpty else { return [] }

        // Per-story aggregates: count of active items + the most
        // recent item's effective date (prefer occurred_at, fall
        // back to created_at). Done in a single SQL pass keyed by
        // story_id so the shelf scales with the story count, not
        // the item count.
        struct Aggregate: FetchableRecord {
            let storyId: String
            let itemCount: Int
            let latestItemAt: String?
            init(row: Row) {
                storyId      = row["story_id"]
                itemCount    = row["item_count"]
                latestItemAt = row["latest_item_at"]
            }
        }
        let storyIds = stories.map(\.id)
        let placeholders = databaseQuestionMarks(count: storyIds.count)
        let aggregates = try Aggregate.fetchAll(db, sql: """
            SELECT story_id,
                   COUNT(*)                                       AS item_count,
                   MAX(COALESCE(occurred_at, created_at))         AS latest_item_at
            FROM story_item
            WHERE deleted_at IS NULL AND story_id IN (\(placeholders))
            GROUP BY story_id
            """, arguments: StatementArguments(storyIds))

        let aggByStory = Dictionary(uniqueKeysWithValues: aggregates.map { ($0.storyId, $0) })
        return stories.map { story in
            let agg = aggByStory[story.id]
            return StoryShelfRow(
                story:        story,
                itemCount:    agg?.itemCount ?? 0,
                latestItemAt: agg?.latestItemAt
            )
        }
    }
}

private func databaseQuestionMarks(count: Int) -> String {
    Array(repeating: "?", count: count).joined(separator: ", ")
}
