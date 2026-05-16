/*
 * CaptureTagRepository.swift
 *
 * GRDB wrapper around the `capture_tags` many-to-many join.
 * Provides idempotent attach / detach helpers, the per-capture
 * tag-id Flow observation, and the seed helper used by the
 * v8 backfill pass.
 *
 * Mirror of `CaptureTagDao.kt` in QuickInk's Android target.
 */

import Foundation
import Combine
import GRDB
import ReleafCoreData

public final class CaptureTagRepository: @unchecked Sendable {

    private let dbQueue: DatabaseQueue

    public init(database: QuickInkDatabase = .shared) {
        self.dbQueue = database.dbQueue
    }

    /// Live tag ids attached to a capture, oldest-first.
    public func observeTagIds(captureId: String) -> AnyPublisher<[String], Error> {
        ValueObservation.tracking { db in
            try String.fetchAll(db, sql: """
                SELECT tag_id FROM capture_tags
                WHERE capture_id = ? AND deleted_at IS NULL
                ORDER BY created_at ASC
                """, arguments: [captureId])
        }
        .publisher(in: dbQueue)
        .eraseToAnyPublisher()
    }

    /// One-shot variant for non-Combine callers (e.g. the
    /// auto-tag suggester).
    public func listTagIds(captureId: String) async throws -> [String] {
        try await dbQueue.read { db in
            try String.fetchAll(db, sql: """
                SELECT tag_id FROM capture_tags
                WHERE capture_id = ? AND deleted_at IS NULL
                ORDER BY created_at ASC
                """, arguments: [captureId])
        }
    }

    /// Idempotent attach. If an active row already exists for the
    /// pair, no-op. If a tombstoned row exists, revive it (preserves
    /// the existing id + drive_file_id so the Drive payload doesn't
    /// churn across attach/detach cycles). Otherwise insert fresh.
    public func attachTag(
        captureId: String,
        tagId: String,
        source: String = "manual"
    ) async throws {
        let now = IsoClock.nowIso()
        try await dbQueue.write { db in
            // Existing pair (active OR tombstoned).
            let existing = try CaptureTagEntity
                .filter(Column("capture_id") == captureId)
                .filter(Column("tag_id") == tagId)
                .fetchOne(db)
            if let existing {
                if existing.deletedAt != nil {
                    try db.execute(sql: """
                        UPDATE capture_tags
                        SET deleted_at = NULL,
                            updated_at = ?,
                            source     = ?,
                            dirty      = 1
                        WHERE id = ?
                        """, arguments: [now, source, existing.id])
                }
                return
            }
            let row = CaptureTagEntity(
                id:        Uuidv7.generate(),
                captureId: captureId,
                tagId:     tagId,
                source:    source,
                createdAt: now,
                updatedAt: now,
                dirty:     true,
            )
            do { try row.insert(db) }
            catch let e as DatabaseError where e.resultCode == .SQLITE_CONSTRAINT {
                // Race lost — another writer landed the same pair.
            }
        }
    }

    /// Live count of active captures that carry EVERY tag id in
    /// `tagIds`. Drives the tag-library intersect builder. Returns
    /// 0 when the input is empty.
    public func observeIntersectCount(
        userId: String,
        tagIds: [String]
    ) -> AnyPublisher<Int, Error> {
        guard !tagIds.isEmpty else {
            return Just(0).setFailureType(to: Error.self).eraseToAnyPublisher()
        }
        let placeholders = tagIds.map { _ in "?" }.joined(separator: ",")
        let tagCount = tagIds.count
        var args: [DatabaseValueConvertible] = tagIds
        args.append(userId)
        args.append(tagCount)
        return ValueObservation.tracking { db in
            try Int.fetchOne(db, sql: """
                SELECT COUNT(*) FROM (
                    SELECT capture_tags.capture_id
                    FROM capture_tags
                    JOIN captures ON captures.id = capture_tags.capture_id
                    WHERE capture_tags.tag_id IN (\(placeholders))
                      AND capture_tags.deleted_at IS NULL
                      AND captures.user_id = ?
                      AND captures.deleted_at IS NULL
                    GROUP BY capture_tags.capture_id
                    HAVING COUNT(DISTINCT capture_tags.tag_id) = ?
                )
                """, arguments: StatementArguments(args)) ?? 0
        }
        .publisher(in: dbQueue)
        .eraseToAnyPublisher()
    }

    /// Live tag counts (id → doc count) for the current user — same
    /// query the home tag cloud reads. Surfaced here so the tag
    /// library can show "31 documents" per card.
    public func observeTagCounts(userId: String) -> AnyPublisher<[TagCount], Error> {
        ValueObservation.tracking { [userId] db in
            try TagCount.fetchAll(db, sql: """
                SELECT capture_tags.tag_id AS tag_id, COUNT(*) AS doc_count
                FROM capture_tags
                JOIN captures ON captures.id = capture_tags.capture_id
                WHERE capture_tags.deleted_at IS NULL
                  AND captures.deleted_at IS NULL
                  AND captures.user_id = ?
                GROUP BY capture_tags.tag_id
                """, arguments: [userId])
        }
        .publisher(in: dbQueue)
        .eraseToAnyPublisher()
    }

    /// Soft-detach. No-op if no active row exists.
    public func detachTag(captureId: String, tagId: String) async throws {
        let now = IsoClock.nowIso()
        try await dbQueue.write { db in
            try db.execute(sql: """
                UPDATE capture_tags
                SET deleted_at = ?,
                    updated_at = ?,
                    dirty      = 1
                WHERE capture_id = ?
                  AND tag_id = ?
                  AND deleted_at IS NULL
                """, arguments: [now, now, captureId, tagId])
        }
    }

    /// Soft-delete every active join row for a capture in a single
    /// pass. Used by `CaptureRepository.attachOrEnsurePrimaryTag`
    /// when the user clears their pick (nil / blank name), and by
    /// any future "remove all tags" affordance.
    public func softDeleteByCaptureId(captureId: String) async throws {
        let now = IsoClock.nowIso()
        try await dbQueue.write { db in
            try db.execute(sql: """
                UPDATE capture_tags
                SET deleted_at = ?,
                    updated_at = ?,
                    dirty      = 1
                WHERE capture_id = ?
                  AND deleted_at IS NULL
                """, arguments: [now, now, captureId])
        }
    }

    /// For every active capture with at least one active tag, emit
    /// (capture_id, tag_name) for the *earliest-attached* tag — the
    /// closest analogue to the legacy `captures.category` "primary
    /// label". Drives the legacy Library / Search / category-grid
    /// surfaces post-A.3c column drop. Window function picks one
    /// row per capture deterministically; SQLite 3.25+ is bundled
    /// with iOS so this is always safe.
    ///
    /// Live set of capture ids whose attached tags include the
    /// supplied tag NAME. Drives the per-tag drill on Workspace
    /// (tap a tag chip → list every doc that carries that tag, not
    /// just the docs where it's the primary tag). Case-sensitive on
    /// the tag name since the rest of the app stores tags in
    /// canonical kebab form via [normalizeTagName].
    public func observeCaptureIdsForTagName(
        userId: String,
        tagName: String
    ) -> AnyPublisher<Set<String>, Error> {
        ValueObservation.tracking { [userId, tagName] db -> Set<String> in
            let ids = try String.fetchAll(db, sql: """
                SELECT capture_tags.capture_id
                FROM capture_tags
                JOIN tags     ON tags.id     = capture_tags.tag_id
                JOIN captures ON captures.id = capture_tags.capture_id
                WHERE captures.user_id      = ?
                  AND captures.deleted_at   IS NULL
                  AND capture_tags.deleted_at IS NULL
                  AND tags.deleted_at       IS NULL
                  AND tags.name             = ?
                """, arguments: [userId, tagName])
            return Set(ids)
        }
        .publisher(in: dbQueue)
        .eraseToAnyPublisher()
    }

    /// Mirror of Android's `CaptureTagDao.observePrimaryTagNames`.
    public func observePrimaryTagNames(
        userId: String
    ) -> AnyPublisher<[String: String], Error> {
        ValueObservation.tracking { [userId] db -> [String: String] in
            struct Pair: Decodable, FetchableRecord {
                let captureId: String
                let tagName: String
                enum CodingKeys: String, CodingKey {
                    case captureId = "capture_id"
                    case tagName   = "tag_name"
                }
            }
            let rows = try Pair.fetchAll(db, sql: """
                WITH ranked AS (
                    SELECT
                        capture_tags.capture_id AS capture_id,
                        tags.name               AS tag_name,
                        ROW_NUMBER() OVER (
                            PARTITION BY capture_tags.capture_id
                            ORDER BY capture_tags.created_at ASC, capture_tags.id ASC
                        ) AS rn
                    FROM capture_tags
                    JOIN tags     ON tags.id     = capture_tags.tag_id
                    JOIN captures ON captures.id = capture_tags.capture_id
                    WHERE captures.user_id    = ?
                      AND captures.deleted_at IS NULL
                      AND capture_tags.deleted_at IS NULL
                      AND tags.deleted_at     IS NULL
                )
                SELECT capture_id, tag_name FROM ranked WHERE rn = 1
                """, arguments: [userId])
            var map: [String: String] = [:]
            for r in rows { map[r.captureId] = r.tagName }
            return map
        }
        .publisher(in: dbQueue)
        .eraseToAnyPublisher()
    }
}
