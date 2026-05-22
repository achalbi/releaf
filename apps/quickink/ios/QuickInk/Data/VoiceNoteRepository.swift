/*
 * VoiceNoteRepository.swift
 *
 * Persistence + reactive observation for voice notes attached to a
 * capture. Mirrors the shape of `CaptureRepository` / `TagRepository`:
 * one shared `QuickInkDatabase.shared` queue, GRDB's `ValueObservation`
 * for the live list publisher, plain async/await for writes.
 *
 * Lifecycle:
 *   - `insert(...)`       — append a new note after the recorder
 *                           commits a clip.
 *   - `setTranscription(...)` — fill in (or clear) the transcript once
 *                           the on-device recognizer finishes.
 *   - `softDelete(id:)`   — tombstones the row so the next sync push
 *                           propagates the delete; the file is removed
 *                           when the row's drive id is cleared on the
 *                           next pull.
 *   - `observeForCapture(_:)` — Combine publisher of the live list,
 *                           filtered to active rows for the capture
 *                           and ordered created_at ASC so the section
 *                           reads top-to-bottom in recording order.
 */

import Combine
import Foundation
import GRDB
import ReleafCoreData

public final class VoiceNoteRepository: @unchecked Sendable {

    private let dbQueue: DatabaseQueue

    public init(database: QuickInkDatabase = .shared) {
        self.dbQueue = database.dbQueue
    }

    // MARK: - Reads

    /// Live list of active voice notes for the capture, oldest-first.
    /// Powers the section list under the document detail screen.
    public func observeForCapture(_ captureId: String) -> AnyPublisher<[VoiceNoteEntity], Error> {
        ValueObservation
            .tracking { db in
                try VoiceNoteEntity
                    .filter(VoiceNoteEntity.Columns.captureId == captureId)
                    .filter(VoiceNoteEntity.Columns.deletedAt == nil)
                    .order(VoiceNoteEntity.Columns.createdAt.asc)
                    .fetchAll(db)
            }
            .publisher(in: dbQueue)
            .eraseToAnyPublisher()
    }

    public func fetchOne(id: String) async throws -> VoiceNoteEntity? {
        try await dbQueue.read { db in
            try VoiceNoteEntity
                .filter(VoiceNoteEntity.Columns.id == id)
                .filter(VoiceNoteEntity.Columns.deletedAt == nil)
                .fetchOne(db)
        }
    }

    public func firstForCapture(_ captureId: String) async throws -> VoiceNoteEntity? {
        try await dbQueue.read { db in
            try VoiceNoteEntity
                .filter(VoiceNoteEntity.Columns.captureId == captureId)
                .filter(VoiceNoteEntity.Columns.deletedAt == nil)
                .order(VoiceNoteEntity.Columns.createdAt.asc)
                .fetchOne(db)
        }
    }

    /// Cheap point-in-time existence check used by
    /// `VoiceNoteCapturePane` to auto-advance past the recorder
    /// when a voice note has already been pre-attached (e.g. the
    /// audio track extracted from a Photo-mode video capture).
    /// Read-only, single query, no observation overhead.
    public func anyForCapture(_ captureId: String) async throws -> Bool {
        try await dbQueue.read { db in
            try VoiceNoteEntity
                .filter(VoiceNoteEntity.Columns.captureId == captureId)
                .filter(VoiceNoteEntity.Columns.deletedAt == nil)
                .fetchCount(db) > 0
        }
    }

    // MARK: - Writes

    /// Append a fresh voice note. Generates a UUIDv7 id and current
    /// timestamp; the row lands dirty so the next sync push uploads
    /// the .m4a binary + the row metadata.
    @discardableResult
    public func insert(
        captureId: String,
        userId: String,
        audioUri: String,
        durationMs: Int
    ) async throws -> VoiceNoteEntity {
        let now = IsoClock.nowIso()
        var entity = VoiceNoteEntity(
            id:                  Uuidv7.generate(),
            captureId:           captureId,
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

    /// Store the recognized text + which backend produced it. Caller
    /// passes `nil` to clear ("retry" → spinner). Marks the row dirty
    /// so the transcript syncs alongside everything else.
    public func setTranscription(
        id: String,
        transcription: String?,
        source: String?
    ) async throws {
        let now = IsoClock.nowIso()
        try await dbQueue.write { db in
            try db.execute(sql: """
                UPDATE voice_notes
                SET transcription = ?,
                    transcription_source = ?,
                    updated_at = ?,
                    dirty = 1
                WHERE id = ?
                """, arguments: [transcription, source, now, id])
        }
    }

    /// Soft-delete. Tombstone keeps the row visible to sync so the
    /// delete reaches other devices; the row stays in the DB until the
    /// sync worker confirms the remote delete landed.
    public func softDelete(id: String) async throws {
        let now = IsoClock.nowIso()
        try await dbQueue.write { db in
            try db.execute(sql: """
                UPDATE voice_notes
                SET deleted_at = ?,
                    updated_at = ?,
                    dirty = 1
                WHERE id = ?
                """, arguments: [now, now, id])
        }
    }
}
