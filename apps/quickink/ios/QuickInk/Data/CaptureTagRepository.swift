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
}
