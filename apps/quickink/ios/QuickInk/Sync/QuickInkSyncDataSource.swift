/*
 * QuickInkSyncDataSource.swift
 *
 * QuickInk's implementation of `ReleafCoreSync.SyncDataSource`.
 * Mirror of `apps/releaf/.../Sync/ReleafSyncDataSource.swift` but
 * for QuickInk's three entity kinds: notepad_entries, captures,
 * ocr_results.
 *
 * Pagination policy: same as Releaf's iOS — single-batch with
 * `nextCursor: nil`. Paginated implementations would land here
 * when a real QuickInk user accumulates enough dirty rows that
 * one Drive payload exceeds ~4 MB.
 *
 * Row reads:
 *   - `NotepadEntry` (from ReleafCoreNotes) is a GRDB
 *     `FetchableRecord` / `PersistableRecord`, so notepad reads
 *     use the typed surface — same shape Releaf's iOS uses.
 *   - `captures` and `ocr_results` don't have iOS-side typed
 *     records yet (Slice 1 used raw SQL for the
 *     `CaptureRepository`'s inserts and we kept that convention).
 *     This file uses `Row.fetchAll(db, sql:)` for them; if more
 *     code starts touching these tables, defining `CaptureRow` /
 *     `OcrResultRow` GRDB records becomes worthwhile.
 *
 * `lastAppliedManifestEtag` is a v1 no-op (returns nil, set is
 * also no-op) — matches Releaf's iOS stance.
 *
 * See:
 *   - shared/ios/ReleafCore/Sources/ReleafCoreSync/SyncDataSource.swift — protocol
 *   - apps/releaf/.../Sync/ReleafSyncDataSource.swift — reference
 *   - QUICKINK_PROPOSAL.md §1 — design rationale
 */

import Foundation
import GRDB
import ReleafCoreNotes
import ReleafCoreSync

public final class QuickInkSyncDataSource: SyncDataSource, @unchecked Sendable {

    private let database: QuickInkDatabase
    private let userId: String

    public init(database: QuickInkDatabase = .shared, userId: String) {
        self.database = database
        self.userId = userId
    }

    // MARK: - Identity

    /// Drive folder layout — `Thoughtbasics/QuickInk/...`. The
    /// "Thoughtbasics" wrapper holds future thoughts-line apps; the
    /// QuickInk subfolder is the actual app root that all paths
    /// (captures, ocr_results, notepad_entries, categories,
    /// tombstones, manifest.json) hang off. `ensureRootFolder` on
    /// the Drive client walks slash-separated paths automatically.
    public let driveRootFolderName: String = "Thoughtbasics/QuickInk"

    public let schemaVersion: SchemaVersion = .current

    public let appId: String = "quickink"

    // MARK: - Outbound: collect dirty rows

    public func nextDirtyBatch(after cursor: SyncCursor?, limit: Int) async throws -> DirtyBatch {
        if cursor != nil {
            return DirtyBatch(entries: [], nextCursor: nil)
        }

        let userId = self.userId
        let entries = try await database.dbQueue.read { db -> [DirtyEntry] in
            var out: [DirtyEntry] = []

            // ---- notepad_entries (typed via GRDB) ----
            let notepad = try NotepadEntry
                .filter(sql: "user_id = ? AND (deleted_at IS NULL OR dirty = 1)",
                        arguments: [userId])
                .fetchAll(db)
            for row in notepad where row.deletedAt == nil {
                if let entry = try Self.makeEntry(
                    id: row.id,
                    kind: DrivePath.kindNotepadEntry,
                    drivePath: DrivePath.notepadEntry(entryDate: row.entryDate, entryId: row.id),
                    updatedAt: row.updatedAt,
                    encodable: row.toV2Payload()
                ) { out.append(entry) }
            }

            // ---- captures (raw SQL — no typed record yet) ----
            let captureRows = try Row.fetchAll(db, sql: """
                SELECT * FROM captures
                WHERE user_id = ? AND (deleted_at IS NULL OR dirty = 1)
                """, arguments: [userId])
            for row in captureRows where (row["deleted_at"] as String?) == nil {
                let payload = CapturePayloadV2(
                    id:                 row["id"],
                    userId:             row["user_id"],
                    title:              row["title"] as String?,
                    pdfUri:             row["pdf_uri"],
                    previewUri:         row["preview_uri"] as String?,
                    pageCount:          row["page_count"],
                    // Legacy slot — always nil post-A.3c (column
                    // dropped). See `CapturePayloadV2.category`.
                    category:           nil,
                    pdfDriveFileId:     row["pdf_drive_file_id"] as String?,
                    previewDriveFileId: row["preview_drive_file_id"] as String?,
                    videoDriveFileId:   row["video_drive_file_id"] as String?,
                    // Older rows pre-v4 had no source column; the
                    // ALTER TABLE default (`'scan'`) means SELECT
                    // always returns a value, but tolerate a nil
                    // read for safety so an unexpected schema state
                    // doesn't crash the sync export.
                    source:             (row["source"] as String?) ?? "scan",
                    // `paper_size` landed in v11; same back-compat
                    // tolerance — fall back to "a4" if the column
                    // is absent from the row read.
                    paperSize:          (row["paper_size"] as String?) ?? "a4",
                    latitude:           row["latitude"] as Double?,
                    longitude:          row["longitude"] as Double?,
                    locality:           row["locality"] as String?,
                    subLocality:        row["sub_locality"] as String?,
                    address:            row["address"] as String?,
                    notes:              row["notes"] as String?,
                    videoUri:           row["video_uri"] as String?,
                    isFavorite:         (row["is_favorite"] as Bool?) ?? false,
                    createdAt:          row["created_at"],
                    updatedAt:          row["updated_at"]
                )
                if let entry = try Self.makeEntry(
                    id: row["id"],
                    kind: DrivePath.kindCapture,
                    drivePath: DrivePath.quickInkCapture(
                        createdAt: row["created_at"],
                        id:        row["id"]
                    ),
                    updatedAt: row["updated_at"],
                    encodable: payload
                ) { out.append(entry) }
            }

            // ---- tags (typed record). Wire kind stays kindCategory
            // for back-compat with older clients on Drive; new
            // payloads land under `tags/` (DrivePath.tag) — readers
            // resolve via the manifest's per-row path. Cleanup of
            // orphaned `categories/` files is a follow-up after the
            // brief's two-week soak.
            let tagRows = try TagEntity
                .filter(Column("user_id") == userId)
                .filter(Column("dirty") == true)
                .filter(Column("deleted_at") == nil)
                .fetchAll(db)
            for row in tagRows {
                let payload = TagPayloadV1(
                    id:        row.id,
                    userId:    row.userId,
                    name:      row.name,
                    position:  row.position,
                    color:     row.color,
                    createdAt: row.createdAt,
                    updatedAt: row.updatedAt
                )
                if let entry = try Self.makeEntry(
                    id: row.id,
                    kind: DrivePath.kindCategory,
                    drivePath: DrivePath.tag(id: row.id),
                    updatedAt: row.updatedAt,
                    encodable: payload
                ) { out.append(entry) }
            }

            // ---- folders (Workspace v1) ----
            let folderRows = try FolderEntity
                .filter(Column("user_id") == userId)
                .filter(Column("dirty") == true)
                .filter(Column("deleted_at") == nil)
                .fetchAll(db)
            for row in folderRows {
                let payload = FolderPayloadV1(
                    id:        row.id,
                    userId:    row.userId,
                    name:      row.name,
                    color:     row.color,
                    position:  row.position,
                    coverUri:  row.coverUri,
                    isDefault: row.isDefault,
                    isShared:  row.isShared,
                    createdAt: row.createdAt,
                    updatedAt: row.updatedAt
                )
                if let entry = try Self.makeEntry(
                    id: row.id,
                    kind: DrivePath.kindFolder,
                    drivePath: DrivePath.folder(id: row.id),
                    updatedAt: row.updatedAt,
                    encodable: payload
                ) { out.append(entry) }
            }

            // ---- capture_tags. FK to captures handles ownership;
            // no user_id column on the join row itself, so the dirty
            // filter alone is enough. ----
            let captureTagRows = try CaptureTagEntity
                .filter(Column("dirty") == true)
                .filter(Column("deleted_at") == nil)
                .fetchAll(db)
            for row in captureTagRows {
                let payload = CaptureTagPayloadV1(
                    id:        row.id,
                    captureId: row.captureId,
                    tagId:     row.tagId,
                    source:    row.source,
                    createdAt: row.createdAt,
                    updatedAt: row.updatedAt
                )
                if let entry = try Self.makeEntry(
                    id: row.id,
                    kind: DrivePath.kindCaptureTag,
                    drivePath: DrivePath.captureTag(id: row.id),
                    updatedAt: row.updatedAt,
                    encodable: payload
                ) { out.append(entry) }
            }

            // ---- smart_collections (Workspace v1) ----
            let smartCollectionRows = try SmartCollectionEntity
                .filter(Column("user_id") == userId)
                .filter(Column("dirty") == true)
                .filter(Column("deleted_at") == nil)
                .fetchAll(db)
            for row in smartCollectionRows {
                let payload = SmartCollectionPayloadV1(
                    id:        row.id,
                    userId:    row.userId,
                    name:      row.name,
                    icon:      row.icon,
                    color:     row.color,
                    ruleJson:  row.ruleJson,
                    position:  row.position,
                    isSeeded:  row.isSeeded,
                    createdAt: row.createdAt,
                    updatedAt: row.updatedAt
                )
                if let entry = try Self.makeEntry(
                    id: row.id,
                    kind: DrivePath.kindSmartCollection,
                    drivePath: DrivePath.smartCollection(id: row.id),
                    updatedAt: row.updatedAt,
                    encodable: payload
                ) { out.append(entry) }
            }

            // ---- locations (Workspace — Places) ----
            let locationRows = try LocationEntity
                .filter(Column("user_id") == userId)
                .filter(Column("dirty") == true)
                .filter(Column("deleted_at") == nil)
                .fetchAll(db)
            for row in locationRows {
                let payload = row.toV1Payload()
                if let entry = try Self.makeEntry(
                    id: row.id,
                    kind: DrivePath.kindLocation,
                    drivePath: DrivePath.location(id: row.id),
                    updatedAt: row.updatedAt,
                    encodable: payload
                ) { out.append(entry) }
            }

            // ---- capture_locations. Join row; no user_id column,
            // so the dirty filter alone is sufficient (mirror of
            // capture_tags above). ----
            let captureLocationRows = try CaptureLocationEntity
                .filter(Column("dirty") == true)
                .filter(Column("deleted_at") == nil)
                .fetchAll(db)
            for row in captureLocationRows {
                let payload = row.toV1Payload()
                if let entry = try Self.makeEntry(
                    id: row.id,
                    kind: DrivePath.kindCaptureLocation,
                    drivePath: DrivePath.captureLocation(id: row.id),
                    updatedAt: row.updatedAt,
                    encodable: payload
                ) { out.append(entry) }
            }

            // ---- people (Workspace — People) ----
            let personRows = try PersonEntity
                .filter(Column("user_id") == userId)
                .filter(Column("dirty") == true)
                .filter(Column("deleted_at") == nil)
                .fetchAll(db)
            for row in personRows {
                let payload = row.toV1Payload()
                if let entry = try Self.makeEntry(
                    id: row.id,
                    kind: DrivePath.kindPerson,
                    drivePath: DrivePath.person(id: row.id),
                    updatedAt: row.updatedAt,
                    encodable: payload
                ) { out.append(entry) }
            }

            // ---- capture_people. Join row; same pattern as
            // capture_locations / capture_tags. ----
            let capturePersonRows = try CapturePersonEntity
                .filter(Column("dirty") == true)
                .filter(Column("deleted_at") == nil)
                .fetchAll(db)
            for row in capturePersonRows {
                let payload = row.toV1Payload()
                if let entry = try Self.makeEntry(
                    id: row.id,
                    kind: DrivePath.kindCapturePerson,
                    drivePath: DrivePath.capturePerson(id: row.id),
                    updatedAt: row.updatedAt,
                    encodable: payload
                ) { out.append(entry) }
            }

            // ---- voice_notes (typed via GRDB) ----
            let voiceNoteRows = try VoiceNoteEntity
                .filter(Column("user_id") == userId)
                .filter(Column("deleted_at") == nil)
                .fetchAll(db)
            let dirtyVoiceNoteIds = try Set(VoiceNoteEntity
                .filter(Column("user_id") == userId)
                .filter(Column("dirty") == true)
                .fetchAll(db)
                .map(\.id))
            let voiceNotesToPush = voiceNoteRows.filter { dirtyVoiceNoteIds.contains($0.id) }
            for row in voiceNotesToPush {
                let payload = VoiceNotePayloadV1(
                    id:                  row.id,
                    captureId:           row.captureId,
                    userId:              row.userId,
                    audioUri:            row.audioUri,
                    durationMs:          row.durationMs,
                    transcription:       row.transcription,
                    transcriptionSource: row.transcriptionSource,
                    audioDriveFileId:    row.audioDriveFileId,
                    createdAt:           row.createdAt,
                    updatedAt:           row.updatedAt
                )
                if let entry = try Self.makeEntry(
                    id: row.id,
                    kind: DrivePath.kindVoiceNote,
                    drivePath: DrivePath.quickInkVoiceNote(
                        createdAt: row.createdAt,
                        captureId: row.captureId,
                        id:        row.id
                    ),
                    updatedAt: row.updatedAt,
                    encodable: payload
                ) { out.append(entry) }
            }

            // ---- stories ----
            let storyRows = try Story
                .filter(Column("user_id") == userId)
                .filter(Column("deleted_at") == nil)
                .filter(Column("dirty") == true)
                .fetchAll(db)
            for row in storyRows {
                let payload = StoryPayloadV1(
                    id:             row.id,
                    userId:         row.userId,
                    title:          row.title,
                    subtitle:       row.subtitle,
                    coverItemId:    row.coverItemId,
                    coverStyle:     row.coverStyle,
                    themeStyle:     row.themeStyle,
                    groupingMode:   row.groupingMode,
                    timeRangeStart: row.timeRangeStart,
                    timeRangeEnd:   row.timeRangeEnd,
                    status:         row.status,
                    shareMode:      row.shareMode,
                    shareSlug:      row.shareSlug,
                    createdAt:      row.createdAt,
                    updatedAt:      row.updatedAt
                )
                if let entry = try Self.makeEntry(
                    id: row.id,
                    kind: DrivePath.kindStory,
                    drivePath: DrivePath.story(id: row.id),
                    updatedAt: row.updatedAt,
                    encodable: payload
                ) { out.append(entry) }
            }

            // ---- story_items (not user-scoped — FK to story) ----
            let storyItemRows = try StoryItem
                .filter(Column("deleted_at") == nil)
                .filter(Column("dirty") == true)
                .fetchAll(db)
            for row in storyItemRows {
                let payload = StoryItemPayloadV1(
                    id:         row.id,
                    storyId:    row.storyId,
                    position:   row.position,
                    kind:       row.kind,
                    refId:      row.refId,
                    text:       row.text,
                    caption:    row.caption,
                    occurredAt: row.occurredAt,
                    layout:     row.layout,
                    createdAt:  row.createdAt,
                    updatedAt:  row.updatedAt
                )
                if let entry = try Self.makeEntry(
                    id: row.id,
                    kind: DrivePath.kindStoryItem,
                    drivePath: DrivePath.storyItem(id: row.id),
                    updatedAt: row.updatedAt,
                    encodable: payload
                ) { out.append(entry) }
            }

            // ---- story_voice_clips ----
            let storyVoiceClipRows = try StoryVoiceClip
                .filter(Column("user_id") == userId)
                .filter(Column("deleted_at") == nil)
                .filter(Column("dirty") == true)
                .fetchAll(db)
            for row in storyVoiceClipRows {
                let payload = StoryVoiceClipPayloadV1(
                    id:                  row.id,
                    storyItemId:         row.storyItemId,
                    userId:              row.userId,
                    audioUri:            row.audioUri,
                    durationMs:          row.durationMs,
                    transcription:       row.transcription,
                    transcriptionSource: row.transcriptionSource,
                    audioDriveFileId:    row.audioDriveFileId,
                    createdAt:           row.createdAt,
                    updatedAt:           row.updatedAt
                )
                if let entry = try Self.makeEntry(
                    id: row.id,
                    kind: DrivePath.kindStoryVoiceClip,
                    drivePath: DrivePath.quickInkStoryVoiceClip(
                        createdAt:   row.createdAt,
                        storyItemId: row.storyItemId,
                        id:          row.id
                    ),
                    updatedAt: row.updatedAt,
                    encodable: payload
                ) { out.append(entry) }
            }

            // ---- profile_settings (typed via GRDB). One row per user;
            // carries display-name override, phone, punchline,
            // transcription-language allowlist, and the photo's
            // Drive-file linkage. Mirror of Android's profile_settings
            // dirty-batch step. `photo_local_uri` is excluded from
            // the payload — device-local. ----
            let profileSettingsRows = try ProfileSettingsEntity
                .filter(Column("user_id") == userId)
                .filter(Column("dirty") == true)
                .filter(Column("deleted_at") == nil)
                .fetchAll(db)
            for row in profileSettingsRows {
                let payload = ProfileSettingsPayloadV1(
                    id:                     row.id,
                    userId:                 row.userId,
                    displayName:            row.displayName,
                    phoneNumber:            row.phoneNumber,
                    personalityPunchline:   row.personalityPunchline,
                    transcriptionLanguages: row.transcriptionLanguages,
                    photoDriveFileId:       row.photoDriveFileId,
                    photoUpdatedAt:         row.photoUpdatedAt,
                    createdAt:              row.createdAt,
                    updatedAt:              row.updatedAt
                )
                if let entry = try Self.makeEntry(
                    id: row.id,
                    kind: DrivePath.kindProfileSettings,
                    drivePath: DrivePath.profileSettings(id: row.id),
                    updatedAt: row.updatedAt,
                    encodable: payload
                ) { out.append(entry) }
            }

            // ---- ocr_results (raw SQL; not user-scoped — FK to captures) ----
            let ocrRows = try Row.fetchAll(db, sql: """
                SELECT * FROM ocr_results
                WHERE deleted_at IS NULL OR dirty = 1
                """)
            for row in ocrRows where (row["deleted_at"] as String?) == nil {
                let payload = OcrResultPayloadV2(
                    id:            row["id"],
                    captureId:     row["capture_id"],
                    pageIndex:     row["page_index"],
                    language:      row["language"] as String?,
                    confidence:    row["confidence"] as Double?,
                    text:          row["text"],
                    blocks:        JSONAny.parseOrEmptyArray(row["blocks_json"]),
                    engine:        row["engine"],
                    engineVersion: row["engine_version"] as String?,
                    createdAt:     row["created_at"],
                    updatedAt:     row["updated_at"]
                )
                if let entry = try Self.makeEntry(
                    id: row["id"],
                    kind: DrivePath.kindOcrResult,
                    drivePath: DrivePath.quickInkOcrResult(
                        createdAt:  row["created_at"],
                        captureId:  row["capture_id"],
                        pageIndex:  row["page_index"]
                    ),
                    updatedAt: row["updated_at"],
                    encodable: payload
                ) { out.append(entry) }
            }

            return out
        }

        return DirtyBatch(entries: entries, nextCursor: nil)
    }

    public func nextTombstoneBatch(after cursor: SyncCursor?, limit: Int) async throws -> TombstoneBatch {
        if cursor != nil {
            return TombstoneBatch(entries: [], nextCursor: nil)
        }

        let userId = self.userId
        let entries = try await database.dbQueue.read { db -> [PendingTombstone] in
            var out: [PendingTombstone] = []

            // notepad_entries — user-scoped.
            let notepadRows = try Row.fetchAll(db, sql: """
                SELECT id, deleted_at, updated_at FROM notepad_entries
                WHERE user_id = ? AND deleted_at IS NOT NULL AND dirty = 1
                """, arguments: [userId])
            for row in notepadRows {
                out.append(PendingTombstone(
                    kind: DrivePath.kindNotepadEntry,
                    id: row["id"],
                    deletedAt: (row["deleted_at"] as String?) ?? row["updated_at"]
                ))
            }

            // captures — user-scoped.
            let captureRows = try Row.fetchAll(db, sql: """
                SELECT id, deleted_at, updated_at FROM captures
                WHERE user_id = ? AND deleted_at IS NOT NULL AND dirty = 1
                """, arguments: [userId])
            for row in captureRows {
                out.append(PendingTombstone(
                    kind: DrivePath.kindCapture,
                    id: row["id"],
                    deletedAt: (row["deleted_at"] as String?) ?? row["updated_at"]
                ))
            }

            // categories — user-scoped.
            let categoryTombstones = try Row.fetchAll(db, sql: """
                SELECT id, deleted_at, updated_at FROM tags
                WHERE user_id = ? AND deleted_at IS NOT NULL AND dirty = 1
                """, arguments: [userId])
            for row in categoryTombstones {
                out.append(PendingTombstone(
                    kind: DrivePath.kindCategory,
                    id: row["id"],
                    deletedAt: (row["deleted_at"] as String?) ?? row["updated_at"]
                ))
            }

            // folders / capture_tags / smart_collections — Workspace v1
            // tombstones.
            let folderTombstones = try Row.fetchAll(db, sql: """
                SELECT id, deleted_at, updated_at FROM folders
                WHERE user_id = ? AND deleted_at IS NOT NULL AND dirty = 1
                """, arguments: [userId])
            for row in folderTombstones {
                out.append(PendingTombstone(
                    kind: DrivePath.kindFolder,
                    id: row["id"],
                    deletedAt: (row["deleted_at"] as String?) ?? row["updated_at"]
                ))
            }
            let captureTagTombstones = try Row.fetchAll(db, sql: """
                SELECT id, deleted_at, updated_at FROM capture_tags
                WHERE deleted_at IS NOT NULL AND dirty = 1
                """)
            for row in captureTagTombstones {
                out.append(PendingTombstone(
                    kind: DrivePath.kindCaptureTag,
                    id: row["id"],
                    deletedAt: (row["deleted_at"] as String?) ?? row["updated_at"]
                ))
            }
            let smartCollectionTombstones = try Row.fetchAll(db, sql: """
                SELECT id, deleted_at, updated_at FROM smart_collections
                WHERE user_id = ? AND deleted_at IS NOT NULL AND dirty = 1
                """, arguments: [userId])
            for row in smartCollectionTombstones {
                out.append(PendingTombstone(
                    kind: DrivePath.kindSmartCollection,
                    id: row["id"],
                    deletedAt: (row["deleted_at"] as String?) ?? row["updated_at"]
                ))
            }

            // locations / capture_locations / people / capture_people
            // — Workspace Places + People tombstones.
            let locationTombstones = try Row.fetchAll(db, sql: """
                SELECT id, deleted_at, updated_at FROM locations
                WHERE user_id = ? AND deleted_at IS NOT NULL AND dirty = 1
                """, arguments: [userId])
            for row in locationTombstones {
                out.append(PendingTombstone(
                    kind: DrivePath.kindLocation,
                    id: row["id"],
                    deletedAt: (row["deleted_at"] as String?) ?? row["updated_at"]
                ))
            }
            let captureLocationTombstones = try Row.fetchAll(db, sql: """
                SELECT id, deleted_at, updated_at FROM capture_locations
                WHERE deleted_at IS NOT NULL AND dirty = 1
                """)
            for row in captureLocationTombstones {
                out.append(PendingTombstone(
                    kind: DrivePath.kindCaptureLocation,
                    id: row["id"],
                    deletedAt: (row["deleted_at"] as String?) ?? row["updated_at"]
                ))
            }
            let personTombstones = try Row.fetchAll(db, sql: """
                SELECT id, deleted_at, updated_at FROM people
                WHERE user_id = ? AND deleted_at IS NOT NULL AND dirty = 1
                """, arguments: [userId])
            for row in personTombstones {
                out.append(PendingTombstone(
                    kind: DrivePath.kindPerson,
                    id: row["id"],
                    deletedAt: (row["deleted_at"] as String?) ?? row["updated_at"]
                ))
            }
            let capturePersonTombstones = try Row.fetchAll(db, sql: """
                SELECT id, deleted_at, updated_at FROM capture_people
                WHERE deleted_at IS NOT NULL AND dirty = 1
                """)
            for row in capturePersonTombstones {
                out.append(PendingTombstone(
                    kind: DrivePath.kindCapturePerson,
                    id: row["id"],
                    deletedAt: (row["deleted_at"] as String?) ?? row["updated_at"]
                ))
            }

            // voice_notes — user-scoped.
            let voiceTombstones = try Row.fetchAll(db, sql: """
                SELECT id, deleted_at, updated_at FROM voice_notes
                WHERE user_id = ? AND deleted_at IS NOT NULL AND dirty = 1
                """, arguments: [userId])
            for row in voiceTombstones {
                out.append(PendingTombstone(
                    kind: DrivePath.kindVoiceNote,
                    id: row["id"],
                    deletedAt: (row["deleted_at"] as String?) ?? row["updated_at"]
                ))
            }

            // profile_settings — user-scoped. In practice almost
            // never set (a user always has a profile while signed
            // in), but covered for sync framework parity with
            // Android.
            let profileSettingsTombstones = try Row.fetchAll(db, sql: """
                SELECT id, deleted_at, updated_at FROM profile_settings
                WHERE user_id = ? AND deleted_at IS NOT NULL AND dirty = 1
                """, arguments: [userId])
            for row in profileSettingsTombstones {
                out.append(PendingTombstone(
                    kind: DrivePath.kindProfileSettings,
                    id: row["id"],
                    deletedAt: (row["deleted_at"] as String?) ?? row["updated_at"]
                ))
            }

            // ocr_results — not user-scoped at the row level.
            let ocrRows = try Row.fetchAll(db, sql: """
                SELECT id, deleted_at, updated_at FROM ocr_results
                WHERE deleted_at IS NOT NULL AND dirty = 1
                """)
            for row in ocrRows {
                out.append(PendingTombstone(
                    kind: DrivePath.kindOcrResult,
                    id: row["id"],
                    deletedAt: (row["deleted_at"] as String?) ?? row["updated_at"]
                ))
            }

            // story — user-scoped.
            let storyTombstones = try Row.fetchAll(db, sql: """
                SELECT id, deleted_at, updated_at FROM story
                WHERE user_id = ? AND deleted_at IS NOT NULL AND dirty = 1
                """, arguments: [userId])
            for row in storyTombstones {
                out.append(PendingTombstone(
                    kind: DrivePath.kindStory,
                    id: row["id"],
                    deletedAt: (row["deleted_at"] as String?) ?? row["updated_at"]
                ))
            }

            // story_item — not user-scoped (FK to story).
            let storyItemTombstones = try Row.fetchAll(db, sql: """
                SELECT id, deleted_at, updated_at FROM story_item
                WHERE deleted_at IS NOT NULL AND dirty = 1
                """)
            for row in storyItemTombstones {
                out.append(PendingTombstone(
                    kind: DrivePath.kindStoryItem,
                    id: row["id"],
                    deletedAt: (row["deleted_at"] as String?) ?? row["updated_at"]
                ))
            }

            // story_voice_clip — user-scoped.
            let storyVoiceClipTombstones = try Row.fetchAll(db, sql: """
                SELECT id, deleted_at, updated_at FROM story_voice_clip
                WHERE user_id = ? AND deleted_at IS NOT NULL AND dirty = 1
                """, arguments: [userId])
            for row in storyVoiceClipTombstones {
                out.append(PendingTombstone(
                    kind: DrivePath.kindStoryVoiceClip,
                    id: row["id"],
                    deletedAt: (row["deleted_at"] as String?) ?? row["updated_at"]
                ))
            }

            return out
        }

        return TombstoneBatch(entries: entries, nextCursor: nil)
    }

    // MARK: - Inbound: apply remote changes

    public func applyRemoteUpsert(_ change: RemoteUpsert) async throws {
        let decoder = JSONDecoder()
        let driveFileId = change.driveFileId.isEmpty ? nil : change.driveFileId

        try await database.dbQueue.write { db in
            switch change.kind {
            case DrivePath.kindNotepadEntry:
                let p = try decoder.decode(NotepadEntryPayloadV2.self, from: change.payload)
                var row = p.toEntity(driveFileId: driveFileId)
                row.dirty = false
                try row.save(db)

            case DrivePath.kindCapture:
                let p = try decoder.decode(CapturePayloadV2.self, from: change.payload)
                try Self.upsertCaptureRow(db, payload: p, driveFileId: driveFileId)

            case DrivePath.kindOcrResult:
                let p = try decoder.decode(OcrResultPayloadV2.self, from: change.payload)
                try Self.upsertOcrResultRow(db, payload: p, driveFileId: driveFileId)

            case DrivePath.kindCategory:
                let p = try decoder.decode(TagPayloadV1.self, from: change.payload)
                try Self.upsertCategoryRow(db, payload: p, driveFileId: driveFileId)

            case DrivePath.kindFolder:
                let p = try decoder.decode(FolderPayloadV1.self, from: change.payload)
                try Self.upsertFolderRow(db, payload: p, driveFileId: driveFileId)

            case DrivePath.kindCaptureTag:
                let p = try decoder.decode(CaptureTagPayloadV1.self, from: change.payload)
                try Self.upsertCaptureTagRow(db, payload: p, driveFileId: driveFileId)

            case DrivePath.kindSmartCollection:
                let p = try decoder.decode(SmartCollectionPayloadV1.self, from: change.payload)
                try Self.upsertSmartCollectionRow(db, payload: p, driveFileId: driveFileId)

            case DrivePath.kindLocation:
                let p = try decoder.decode(LocationPayloadV1.self, from: change.payload)
                try Self.upsertLocationRow(db, payload: p, driveFileId: driveFileId)

            case DrivePath.kindCaptureLocation:
                let p = try decoder.decode(CaptureLocationPayloadV1.self, from: change.payload)
                try Self.upsertCaptureLocationRow(db, payload: p, driveFileId: driveFileId)

            case DrivePath.kindPerson:
                let p = try decoder.decode(PersonPayloadV1.self, from: change.payload)
                try Self.upsertPersonRow(db, payload: p, driveFileId: driveFileId)

            case DrivePath.kindCapturePerson:
                let p = try decoder.decode(CapturePersonPayloadV1.self, from: change.payload)
                try Self.upsertCapturePersonRow(db, payload: p, driveFileId: driveFileId)

            case DrivePath.kindVoiceNote:
                let p = try decoder.decode(VoiceNotePayloadV1.self, from: change.payload)
                try Self.upsertVoiceNoteRow(db, payload: p, driveFileId: driveFileId)

            case DrivePath.kindStory:
                let p = try decoder.decode(StoryPayloadV1.self, from: change.payload)
                try Self.upsertStoryRow(db, payload: p)

            case DrivePath.kindStoryItem:
                let p = try decoder.decode(StoryItemPayloadV1.self, from: change.payload)
                try Self.upsertStoryItemRow(db, payload: p)

            case DrivePath.kindStoryVoiceClip:
                let p = try decoder.decode(StoryVoiceClipPayloadV1.self, from: change.payload)
                try Self.upsertStoryVoiceClipRow(db, payload: p, driveFileId: driveFileId)

            case DrivePath.kindProfileSettings:
                let p = try decoder.decode(ProfileSettingsPayloadV1.self, from: change.payload)
                try Self.upsertProfileSettingsRow(db, payload: p, driveFileId: driveFileId)

            default:
                // Forward-compat: unknown kind, skip.
                break
            }
        }
    }

    public func applyRemoteTombstone(_ tombstone: RemoteTombstone) async throws {
        let table = Self.tableFor(kind: tombstone.kind)
        guard !table.isEmpty else { return }
        try await database.dbQueue.write { db in
            try db.execute(sql: """
                UPDATE \(table)
                SET deleted_at = ?, updated_at = ?, dirty = 0
                WHERE id = ?
                """, arguments: [tombstone.deletedAt, tombstone.deletedAt, tombstone.id])
        }
    }

    // MARK: - Bookkeeping

    public func markSynced(_ acks: [SyncAck]) async throws {
        guard !acks.isEmpty else { return }
        try await database.dbQueue.write { db in
            for ack in acks {
                let table = Self.tableFor(kind: ack.kind)
                guard !table.isEmpty else { continue }

                // Race-safe clear of the dirty bit. Same pattern Releaf's
                // iOS uses — only flip dirty=0 if updated_at still
                // matches; otherwise the row was edited mid-upload and
                // the next pass picks it up fresh.
                try db.execute(sql: """
                    UPDATE \(table)
                    SET dirty = 0, drive_file_id = ?
                    WHERE id = ?
                      AND dirty = 1
                      AND updated_at = ?
                    """, arguments: [ack.driveFileId, ack.id, ack.updatedAt])

                // Tombstone-clear path — no updated_at guard. Same
                // pattern as Android's `markTombstoneSynced`.
                try db.execute(sql: """
                    UPDATE \(table)
                    SET dirty = 0
                    WHERE id = ? AND deleted_at IS NOT NULL
                    """, arguments: [ack.id])
            }
        }
    }

    public func lastAppliedManifestEtag() async throws -> String? {
        // v1: always pulls the manifest. v2 etag-skip lands later.
        nil
    }

    public func setLastAppliedManifestEtag(_ etag: String) async throws {
        // No-op until v2 etag tracking lands.
    }

    /// Active, dirty user-created items for the Home "N pending" pill.
    /// Tombstones and derived rows are excluded from this display count
    /// so deleting a dirty item removes it from the visible pending
    /// total immediately. `countDirtyRowsForSync` still includes
    /// tombstones so remote deletes are pushed.
    public func countLocalDirtyItems(userId: String) async throws -> Int {
        try await database.dbQueue.read { db -> Int in
            let notepad: Int = (try? Int.fetchOne(db, sql: """
                SELECT COUNT(*) FROM notepad_entries
                WHERE user_id = ? AND dirty = 1 AND deleted_at IS NULL
                """, arguments: [userId])) ?? 0
            let captures: Int = (try? Int.fetchOne(db, sql: """
                SELECT COUNT(*) FROM captures
                WHERE user_id = ? AND dirty = 1 AND deleted_at IS NULL
                """, arguments: [userId])) ?? 0
            let voiceNotes: Int = (try? Int.fetchOne(db, sql: """
                SELECT COUNT(*) FROM voice_notes
                JOIN captures ON captures.id = voice_notes.capture_id
                WHERE voice_notes.user_id = ?
                  AND voice_notes.dirty = 1
                  AND voice_notes.deleted_at IS NULL
                  AND captures.deleted_at IS NULL
                """, arguments: [userId])) ?? 0
            return notepad + captures + voiceNotes
        }
    }

    /// Cheap aggregate count of every locally-dirty row that the sync
    /// worker would push on its next pass. Used only for scheduling,
    /// not for the Home pill; includes tombstones and derived rows.
    public func countDirtyRowsForSync(userId: String) async throws -> Int {
        try await database.dbQueue.read { db -> Int in
            let notepad: Int = (try? Int.fetchOne(db, sql: """
                SELECT COUNT(*) FROM notepad_entries
                WHERE user_id = ? AND dirty = 1
                """, arguments: [userId])) ?? 0
            let captures: Int = (try? Int.fetchOne(db, sql: """
                SELECT COUNT(*) FROM captures
                WHERE user_id = ? AND dirty = 1
                """, arguments: [userId])) ?? 0
            let ocr: Int = (try? Int.fetchOne(db, sql: """
                SELECT COUNT(*) FROM ocr_results
                JOIN captures ON captures.id = ocr_results.capture_id
                WHERE captures.user_id = ? AND ocr_results.dirty = 1
                """, arguments: [userId])) ?? 0
            let tags: Int = (try? Int.fetchOne(db, sql: """
                SELECT COUNT(*) FROM tags
                WHERE user_id = ? AND dirty = 1
                """, arguments: [userId])) ?? 0
            let profileSettings: Int = (try? Int.fetchOne(db, sql: """
                SELECT COUNT(*) FROM profile_settings
                WHERE user_id = ? AND dirty = 1
                """, arguments: [userId])) ?? 0
            let folders: Int = (try? Int.fetchOne(db, sql: """
                SELECT COUNT(*) FROM folders
                WHERE user_id = ? AND dirty = 1
                """, arguments: [userId])) ?? 0
            let captureTags: Int = (try? Int.fetchOne(db, sql: """
                SELECT COUNT(*) FROM capture_tags
                JOIN captures ON captures.id = capture_tags.capture_id
                WHERE captures.user_id = ? AND capture_tags.dirty = 1
                """, arguments: [userId])) ?? 0
            let smartCollections: Int = (try? Int.fetchOne(db, sql: """
                SELECT COUNT(*) FROM smart_collections
                WHERE user_id = ? AND dirty = 1
                """, arguments: [userId])) ?? 0
            let voiceNotes: Int = (try? Int.fetchOne(db, sql: """
                SELECT COUNT(*) FROM voice_notes
                WHERE user_id = ? AND dirty = 1
                """, arguments: [userId])) ?? 0
            let locations: Int = (try? Int.fetchOne(db, sql: """
                SELECT COUNT(*) FROM locations
                WHERE user_id = ? AND dirty = 1
                """, arguments: [userId])) ?? 0
            let captureLocations: Int = (try? Int.fetchOne(db, sql: """
                SELECT COUNT(*) FROM capture_locations
                JOIN captures ON captures.id = capture_locations.capture_id
                WHERE captures.user_id = ? AND capture_locations.dirty = 1
                """, arguments: [userId])) ?? 0
            let people: Int = (try? Int.fetchOne(db, sql: """
                SELECT COUNT(*) FROM people
                WHERE user_id = ? AND dirty = 1
                """, arguments: [userId])) ?? 0
            let capturePeople: Int = (try? Int.fetchOne(db, sql: """
                SELECT COUNT(*) FROM capture_people
                JOIN captures ON captures.id = capture_people.capture_id
                WHERE captures.user_id = ? AND capture_people.dirty = 1
                """, arguments: [userId])) ?? 0
            let stories: Int = (try? Int.fetchOne(db, sql: """
                SELECT COUNT(*) FROM story
                WHERE user_id = ? AND dirty = 1
                """, arguments: [userId])) ?? 0
            let storyItems: Int = (try? Int.fetchOne(db, sql: """
                SELECT COUNT(*) FROM story_item
                JOIN story ON story.id = story_item.story_id
                WHERE story.user_id = ? AND story_item.dirty = 1
                """, arguments: [userId])) ?? 0
            let storyVoiceClips: Int = (try? Int.fetchOne(db, sql: """
                SELECT COUNT(*) FROM story_voice_clip
                WHERE user_id = ? AND dirty = 1
                """, arguments: [userId])) ?? 0
            return notepad + captures + ocr + tags + profileSettings +
                folders + captureTags + smartCollections + voiceNotes +
                locations + captureLocations + people + capturePeople +
                stories + storyItems + storyVoiceClips
        }
    }

    // MARK: - Helpers

    private static func tableFor(kind: String) -> String {
        switch kind {
        case DrivePath.kindNotepadEntry:    return "notepad_entries"
        case DrivePath.kindCapture:         return "captures"
        case DrivePath.kindOcrResult:       return "ocr_results"
        case DrivePath.kindCategory:        return "tags"
        case DrivePath.kindFolder:          return "folders"
        case DrivePath.kindCaptureTag:      return "capture_tags"
        case DrivePath.kindSmartCollection: return "smart_collections"
        case DrivePath.kindLocation:        return "locations"
        case DrivePath.kindCaptureLocation: return "capture_locations"
        case DrivePath.kindPerson:          return "people"
        case DrivePath.kindCapturePerson:   return "capture_people"
        case DrivePath.kindVoiceNote:       return "voice_notes"
        case DrivePath.kindStory:           return "story"
        case DrivePath.kindStoryItem:       return "story_item"
        case DrivePath.kindStoryVoiceClip:  return "story_voice_clip"
        case DrivePath.kindProfileSettings: return "profile_settings"
        default: return ""
        }
    }

    private static func makeEntry<T: Encodable>(
        id: String,
        kind: String,
        drivePath: String,
        updatedAt: String,
        encodable: T
    ) throws -> DirtyEntry? {
        let bytes = try CanonicalJson.encodeToData(encodable: encodable)
        return DirtyEntry(
            kind:          kind,
            id:            id,
            drivePath:     drivePath,
            payload:       bytes,
            payloadSha256: sha256Hex(bytes),
            updatedAt:     updatedAt
        )
    }

    /// Upsert a tags row from a remote payload. Last-write-wins
    /// on `updated_at`. The wire kind stays `kindCategory` (back-
    /// compat) but the table is `tags` post-v8.
    private static func upsertCategoryRow(_ db: Database, payload: TagPayloadV1, driveFileId: String?) throws {
        try db.execute(sql: """
            INSERT INTO tags (
                id, user_id, name, position, color,
                drive_file_id, created_at, updated_at, dirty
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0)
            ON CONFLICT(id) DO UPDATE SET
                user_id       = excluded.user_id,
                name          = excluded.name,
                position      = excluded.position,
                color         = excluded.color,
                drive_file_id = excluded.drive_file_id,
                updated_at    = excluded.updated_at,
                dirty         = 0
            WHERE tags.updated_at < excluded.updated_at
            """, arguments: [
                payload.id, payload.userId, payload.name, payload.position,
                payload.color,
                driveFileId, payload.createdAt, payload.updatedAt,
            ])
    }

    private static func upsertFolderRow(_ db: Database, payload: FolderPayloadV1, driveFileId: String?) throws {
        try db.execute(sql: """
            INSERT INTO folders (
                id, user_id, name, color, position,
                cover_uri, is_default, is_shared,
                drive_file_id, created_at, updated_at, dirty
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
            ON CONFLICT(id) DO UPDATE SET
                user_id       = excluded.user_id,
                name          = excluded.name,
                color         = excluded.color,
                position      = excluded.position,
                cover_uri     = excluded.cover_uri,
                is_default    = excluded.is_default,
                is_shared     = excluded.is_shared,
                drive_file_id = excluded.drive_file_id,
                updated_at    = excluded.updated_at,
                dirty         = 0
            WHERE folders.updated_at < excluded.updated_at
            """, arguments: [
                payload.id, payload.userId, payload.name, payload.color,
                payload.position, payload.coverUri,
                payload.isDefault, payload.isShared,
                driveFileId, payload.createdAt, payload.updatedAt,
            ])
    }

    private static func upsertCaptureTagRow(_ db: Database, payload: CaptureTagPayloadV1, driveFileId: String?) throws {
        try db.execute(sql: """
            INSERT INTO capture_tags (
                id, capture_id, tag_id, source,
                drive_file_id, created_at, updated_at, dirty
            ) VALUES (?, ?, ?, ?, ?, ?, ?, 0)
            ON CONFLICT(id) DO UPDATE SET
                capture_id    = excluded.capture_id,
                tag_id        = excluded.tag_id,
                source        = excluded.source,
                drive_file_id = excluded.drive_file_id,
                updated_at    = excluded.updated_at,
                dirty         = 0
            WHERE capture_tags.updated_at < excluded.updated_at
            """, arguments: [
                payload.id, payload.captureId, payload.tagId, payload.source,
                driveFileId, payload.createdAt, payload.updatedAt,
            ])
    }

    private static func upsertLocationRow(_ db: Database, payload: LocationPayloadV1, driveFileId: String?) throws {
        try db.execute(sql: """
            INSERT INTO locations (
                id, user_id, name, position, color,
                latitude, longitude, address,
                drive_file_id, created_at, updated_at, dirty
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
            ON CONFLICT(id) DO UPDATE SET
                user_id       = excluded.user_id,
                name          = excluded.name,
                position      = excluded.position,
                color         = excluded.color,
                latitude      = excluded.latitude,
                longitude     = excluded.longitude,
                address       = excluded.address,
                drive_file_id = excluded.drive_file_id,
                updated_at    = excluded.updated_at,
                dirty         = 0
            WHERE locations.updated_at < excluded.updated_at
            """, arguments: [
                payload.id, payload.userId, payload.name, payload.position,
                payload.color, payload.latitude, payload.longitude, payload.address,
                driveFileId, payload.createdAt, payload.updatedAt,
            ])
    }

    private static func upsertCaptureLocationRow(_ db: Database, payload: CaptureLocationPayloadV1, driveFileId: String?) throws {
        try db.execute(sql: """
            INSERT INTO capture_locations (
                id, capture_id, location_id, source,
                drive_file_id, created_at, updated_at, dirty
            ) VALUES (?, ?, ?, ?, ?, ?, ?, 0)
            ON CONFLICT(id) DO UPDATE SET
                capture_id    = excluded.capture_id,
                location_id   = excluded.location_id,
                source        = excluded.source,
                drive_file_id = excluded.drive_file_id,
                updated_at    = excluded.updated_at,
                dirty         = 0
            WHERE capture_locations.updated_at < excluded.updated_at
            """, arguments: [
                payload.id, payload.captureId, payload.locationId, payload.source,
                driveFileId, payload.createdAt, payload.updatedAt,
            ])
    }

    /// Upsert a `people` row from a remote payload. The wire shape
    /// omits the device-local `contact_lookup_key` / `contact_photo_uri`
    /// — those stay at whatever the local row already has, so a sync
    /// from another device doesn't drop a contact link the user
    /// previously set here.
    private static func upsertPersonRow(_ db: Database, payload: PersonPayloadV1, driveFileId: String?) throws {
        try db.execute(sql: """
            INSERT INTO people (
                id, user_id, name, position, color,
                contact_lookup_key, contact_phone, contact_email, contact_photo_uri,
                drive_file_id, created_at, updated_at, dirty
            ) VALUES (?, ?, ?, ?, ?, NULL, ?, ?, NULL, ?, ?, ?, 0)
            ON CONFLICT(id) DO UPDATE SET
                user_id        = excluded.user_id,
                name           = excluded.name,
                position       = excluded.position,
                color          = excluded.color,
                contact_phone  = excluded.contact_phone,
                contact_email  = excluded.contact_email,
                drive_file_id  = excluded.drive_file_id,
                updated_at     = excluded.updated_at,
                dirty          = 0
            WHERE people.updated_at < excluded.updated_at
            """, arguments: [
                payload.id, payload.userId, payload.name, payload.position,
                payload.color,
                payload.contactPhone, payload.contactEmail,
                driveFileId, payload.createdAt, payload.updatedAt,
            ])
    }

    private static func upsertCapturePersonRow(_ db: Database, payload: CapturePersonPayloadV1, driveFileId: String?) throws {
        try db.execute(sql: """
            INSERT INTO capture_people (
                id, capture_id, person_id, source,
                drive_file_id, created_at, updated_at, dirty
            ) VALUES (?, ?, ?, ?, ?, ?, ?, 0)
            ON CONFLICT(id) DO UPDATE SET
                capture_id    = excluded.capture_id,
                person_id     = excluded.person_id,
                source        = excluded.source,
                drive_file_id = excluded.drive_file_id,
                updated_at    = excluded.updated_at,
                dirty         = 0
            WHERE capture_people.updated_at < excluded.updated_at
            """, arguments: [
                payload.id, payload.captureId, payload.personId, payload.source,
                driveFileId, payload.createdAt, payload.updatedAt,
            ])
    }

    /// Upsert a `profile_settings` row from a remote payload. Last-
    /// write-wins on `updated_at`. The wire payload deliberately
    /// omits `photo_local_uri` (device-local) — we keep the existing
    /// local URI when the file still resolves and blank it otherwise.
    /// The binary-restore step will repopulate it via the photo's
    /// `photo_drive_file_id`. Mirror of Android's
    /// `ProfileSettingsDao.upsertFromRemote` + `toEntity`.
    private static func upsertProfileSettingsRow(_ db: Database, payload: ProfileSettingsPayloadV1, driveFileId: String?) throws {
        let existingLocalUri: String? = try Row.fetchOne(
            db,
            sql: "SELECT photo_local_uri FROM profile_settings WHERE id = ? LIMIT 1",
            arguments: [payload.id]
        )?["photo_local_uri"]

        let preservedLocalUri: String? = {
            guard let uri = existingLocalUri, Self.fileExistsAt(uri) else { return nil }
            return uri
        }()

        try db.execute(sql: """
            INSERT INTO profile_settings (
                id, user_id,
                display_name, phone_number, personality_punchline,
                transcription_languages,
                photo_local_uri, photo_drive_file_id, photo_updated_at,
                drive_file_id, created_at, updated_at, dirty
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
            ON CONFLICT(id) DO UPDATE SET
                user_id                 = excluded.user_id,
                display_name            = excluded.display_name,
                phone_number            = excluded.phone_number,
                personality_punchline   = excluded.personality_punchline,
                transcription_languages = excluded.transcription_languages,
                photo_drive_file_id     = excluded.photo_drive_file_id,
                photo_updated_at        = excluded.photo_updated_at,
                drive_file_id           = excluded.drive_file_id,
                updated_at              = excluded.updated_at,
                dirty                   = 0
            WHERE profile_settings.updated_at < excluded.updated_at
            """, arguments: [
                payload.id, payload.userId,
                payload.displayName, payload.phoneNumber, payload.personalityPunchline,
                payload.transcriptionLanguages,
                preservedLocalUri, payload.photoDriveFileId, payload.photoUpdatedAt,
                driveFileId, payload.createdAt, payload.updatedAt,
            ])
    }

    private static func upsertSmartCollectionRow(_ db: Database, payload: SmartCollectionPayloadV1, driveFileId: String?) throws {
        try db.execute(sql: """
            INSERT INTO smart_collections (
                id, user_id, name, icon, color, rule_json, position, is_seeded,
                drive_file_id, created_at, updated_at, dirty
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
            ON CONFLICT(id) DO UPDATE SET
                user_id       = excluded.user_id,
                name          = excluded.name,
                icon          = excluded.icon,
                color         = excluded.color,
                rule_json     = excluded.rule_json,
                position      = excluded.position,
                is_seeded     = excluded.is_seeded,
                drive_file_id = excluded.drive_file_id,
                updated_at    = excluded.updated_at,
                dirty         = 0
            WHERE smart_collections.updated_at < excluded.updated_at
            """, arguments: [
                payload.id, payload.userId, payload.name, payload.icon,
                payload.color, payload.ruleJson, payload.position, payload.isSeeded,
                driveFileId, payload.createdAt, payload.updatedAt,
            ])
    }

    /// Upsert a captures row from a remote payload. Raw SQL because we
    /// don't have a typed `CaptureRow` GRDB record yet — see file header.
    private static func upsertCaptureRow(_ db: Database, payload: CapturePayloadV2, driveFileId: String?) throws {
        // CRITICAL: `pdf_uri` and `preview_uri` are device-local
        // file:// paths. The remote payload carries the SOURCE
        // device's path, which is meaningless on this device.
        // Naively replacing the row with the remote URIs is what
        // produced the "open failed: ENOENT" symptom for cross-
        // device synced captures.
        //
        // Reconcile:
        //   - If we already have this capture locally with a working
        //     file://, keep our URIs (the local binary is correct).
        //   - Otherwise, accept the remote payload but blank out
        //     URIs that won't resolve here. The next restorePending
        //     pass / on-demand open will download from Drive using
        //     pdfDriveFileId.
        let existing: (pdfUri: String, previewUri: String?)? = try Row.fetchOne(
            db,
            sql: "SELECT pdf_uri, preview_uri FROM captures WHERE id = ? LIMIT 1",
            arguments: [payload.id]
        ).map { ($0["pdf_uri"], $0["preview_uri"] as String?) }

        let resolvedPdfUri: String = {
            if let cur = existing, Self.fileExistsAt(cur.pdfUri) {
                return cur.pdfUri
            }
            if payload.pdfDriveFileId != nil {
                return "" // wait for restorePending
            }
            return payload.pdfUri
        }()
        let resolvedPreviewUri: String? = {
            if let cur = existing,
               let p = cur.previewUri,
               Self.fileExistsAt(p) {
                return p
            }
            if payload.previewDriveFileId != nil {
                return nil
            }
            return payload.previewUri
        }()

        // `video_uri` carries a file:// path that's only valid on
        // the source device — same reconcile rule as pdf_uri /
        // preview_uri. When the local file is missing, blank the
        // URI and let `QuickInkBinarySync.restorePending` fill it
        // back in from `video_drive_file_id` on the next pass.
        let existingVideoUri: String? = try Row.fetchOne(
            db,
            sql: "SELECT video_uri FROM captures WHERE id = ? LIMIT 1",
            arguments: [payload.id]
        )?["video_uri"] as String?
        let resolvedVideoUri: String? = {
            if let cur = existingVideoUri, Self.fileExistsAt(cur) {
                return cur
            }
            // Source payload's URI won't resolve on this device —
            // drop it and rely on the binary-restore pass to
            // re-download from Drive using video_drive_file_id.
            if payload.videoDriveFileId != nil {
                return nil
            }
            // No Drive copy either — only a literal file:// path
            // that happens to be valid here counts.
            if let p = payload.videoUri, Self.fileExistsAt(p) {
                return p
            }
            return nil
        }()

        try db.execute(sql: """
            INSERT INTO captures (
                id, user_id, title, pdf_uri, preview_uri, page_count,
                source, paper_size, drive_file_id,
                pdf_drive_file_id, preview_drive_file_id, video_drive_file_id,
                latitude, longitude, locality, sub_locality, address, notes,
                video_uri, is_favorite,
                created_at, updated_at, dirty
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
            ON CONFLICT(id) DO UPDATE SET
                user_id               = excluded.user_id,
                title                 = excluded.title,
                pdf_uri               = excluded.pdf_uri,
                preview_uri           = excluded.preview_uri,
                page_count            = excluded.page_count,
                source                = excluded.source,
                paper_size            = excluded.paper_size,
                drive_file_id         = excluded.drive_file_id,
                pdf_drive_file_id     = excluded.pdf_drive_file_id,
                preview_drive_file_id = excluded.preview_drive_file_id,
                video_drive_file_id   = excluded.video_drive_file_id,
                latitude              = excluded.latitude,
                longitude             = excluded.longitude,
                locality              = excluded.locality,
                sub_locality          = excluded.sub_locality,
                address               = excluded.address,
                notes                 = excluded.notes,
                video_uri             = excluded.video_uri,
                is_favorite           = excluded.is_favorite,
                updated_at            = excluded.updated_at,
                dirty                 = 0
            WHERE captures.updated_at < excluded.updated_at
            """, arguments: [
                payload.id, payload.userId, payload.title,
                resolvedPdfUri, resolvedPreviewUri, payload.pageCount,
                payload.source, payload.paperSize, driveFileId,
                payload.pdfDriveFileId, payload.previewDriveFileId, payload.videoDriveFileId,
                payload.latitude, payload.longitude,
                payload.locality, payload.subLocality, payload.address,
                payload.notes,
                resolvedVideoUri, payload.isFavorite,
                payload.createdAt, payload.updatedAt,
            ])
    }

    /// Upsert a voice_notes row from a remote payload. Reconciles
    /// `audio_uri` the same way [upsertCaptureRow] reconciles
    /// `pdf_uri`: keep the local path if the file is here, otherwise
    /// blank it out and wait for the binary-restore pass to fill in
    /// from `audio_drive_file_id`.
    private static func upsertVoiceNoteRow(_ db: Database, payload: VoiceNotePayloadV1, driveFileId: String?) throws {
        // Defensive parent-existence check — voice_notes has a FK to
        // captures with ON DELETE CASCADE; applying a payload whose
        // parent isn't local yet would raise SQLITE_CONSTRAINT.
        let parentExists = try Bool.fetchOne(
            db,
            sql: "SELECT EXISTS(SELECT 1 FROM captures WHERE id = ? AND deleted_at IS NULL)",
            arguments: [payload.captureId]
        ) ?? false
        guard parentExists else { return }

        let existingAudioUri: String? = try Row.fetchOne(
            db,
            sql: "SELECT audio_uri FROM voice_notes WHERE id = ? LIMIT 1",
            arguments: [payload.id]
        ).map { $0["audio_uri"] as String? } ?? nil

        let resolvedAudioUri: String = {
            if let cur = existingAudioUri, Self.fileExistsAt(cur) { return cur }
            if payload.audioDriveFileId != nil { return "" }
            return payload.audioUri
        }()

        try db.execute(sql: """
            INSERT INTO voice_notes (
                id, capture_id, user_id, audio_uri, duration_ms,
                transcription, transcription_source,
                drive_file_id, audio_drive_file_id,
                created_at, updated_at, dirty
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
            ON CONFLICT(id) DO UPDATE SET
                capture_id           = excluded.capture_id,
                user_id              = excluded.user_id,
                audio_uri            = excluded.audio_uri,
                duration_ms          = excluded.duration_ms,
                transcription        = excluded.transcription,
                transcription_source = excluded.transcription_source,
                drive_file_id        = excluded.drive_file_id,
                audio_drive_file_id  = excluded.audio_drive_file_id,
                updated_at           = excluded.updated_at,
                dirty                = 0
            WHERE voice_notes.updated_at < excluded.updated_at
            """, arguments: [
                payload.id, payload.captureId, payload.userId,
                resolvedAudioUri, payload.durationMs,
                payload.transcription, payload.transcriptionSource,
                driveFileId, payload.audioDriveFileId,
                payload.createdAt, payload.updatedAt,
            ])
    }

    /// Stories Phase 1 — upsert a `story` row. Last-write-wins on
    /// `updated_at`. No FK parent to check; `cover_item_id` is a soft
    /// reference (see StoryEntity for why).
    private static func upsertStoryRow(_ db: Database, payload: StoryPayloadV1) throws {
        try db.execute(sql: """
            INSERT INTO story (
                id, user_id, title, subtitle,
                cover_item_id, cover_style, theme_style, grouping_mode,
                time_range_start, time_range_end,
                status, share_mode, share_slug,
                created_at, updated_at, dirty
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
            ON CONFLICT(id) DO UPDATE SET
                user_id          = excluded.user_id,
                title            = excluded.title,
                subtitle         = excluded.subtitle,
                cover_item_id    = excluded.cover_item_id,
                cover_style      = excluded.cover_style,
                theme_style      = excluded.theme_style,
                grouping_mode    = excluded.grouping_mode,
                time_range_start = excluded.time_range_start,
                time_range_end   = excluded.time_range_end,
                status           = excluded.status,
                share_mode       = excluded.share_mode,
                share_slug       = excluded.share_slug,
                updated_at       = excluded.updated_at,
                dirty            = 0
            WHERE story.updated_at < excluded.updated_at
            """, arguments: [
                payload.id, payload.userId, payload.title, payload.subtitle,
                payload.coverItemId, payload.coverStyle, payload.themeStyle, payload.groupingMode,
                payload.timeRangeStart, payload.timeRangeEnd,
                payload.status, payload.shareMode, payload.shareSlug,
                payload.createdAt, payload.updatedAt,
            ])
    }

    /// Stories Phase 1 — upsert a `story_item` row. Parent `story`
    /// must exist locally first (FK cascade is on hard-delete only,
    /// but the FK constraint itself fires on insert).
    private static func upsertStoryItemRow(_ db: Database, payload: StoryItemPayloadV1) throws {
        let parentExists = try Bool.fetchOne(
            db,
            sql: "SELECT EXISTS(SELECT 1 FROM story WHERE id = ?)",
            arguments: [payload.storyId]
        ) ?? false
        guard parentExists else { return }

        try db.execute(sql: """
            INSERT INTO story_item (
                id, story_id, position, kind,
                ref_id, text, caption, occurred_at, layout,
                created_at, updated_at, dirty
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
            ON CONFLICT(id) DO UPDATE SET
                story_id    = excluded.story_id,
                position    = excluded.position,
                kind        = excluded.kind,
                ref_id      = excluded.ref_id,
                text        = excluded.text,
                caption     = excluded.caption,
                occurred_at = excluded.occurred_at,
                layout      = excluded.layout,
                updated_at  = excluded.updated_at,
                dirty       = 0
            WHERE story_item.updated_at < excluded.updated_at
            """, arguments: [
                payload.id, payload.storyId, payload.position, payload.kind,
                payload.refId, payload.text, payload.caption, payload.occurredAt, payload.layout,
                payload.createdAt, payload.updatedAt,
            ])
    }

    /// Stories Phase 2 — upsert a `story_voice_clip` row. Mirror of
    /// [upsertVoiceNoteRow]: parent existence check + cross-device
    /// URI reconciliation (keep local URI if file exists; else blank
    /// it so the binary-restore pass picks it up).
    private static func upsertStoryVoiceClipRow(_ db: Database, payload: StoryVoiceClipPayloadV1, driveFileId: String?) throws {
        let parentExists = try Bool.fetchOne(
            db,
            sql: "SELECT EXISTS(SELECT 1 FROM story_item WHERE id = ? AND deleted_at IS NULL)",
            arguments: [payload.storyItemId]
        ) ?? false
        guard parentExists else { return }

        let existingAudioUri: String? = try Row.fetchOne(
            db,
            sql: "SELECT audio_uri FROM story_voice_clip WHERE id = ? LIMIT 1",
            arguments: [payload.id]
        ).map { $0["audio_uri"] as String? } ?? nil

        let resolvedAudioUri: String = {
            if let cur = existingAudioUri, Self.fileExistsAt(cur) { return cur }
            if payload.audioDriveFileId != nil { return "" }
            return payload.audioUri
        }()

        try db.execute(sql: """
            INSERT INTO story_voice_clip (
                id, story_item_id, user_id, audio_uri, duration_ms,
                transcription, transcription_source,
                drive_file_id, audio_drive_file_id,
                created_at, updated_at, dirty
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
            ON CONFLICT(id) DO UPDATE SET
                story_item_id        = excluded.story_item_id,
                user_id              = excluded.user_id,
                audio_uri            = excluded.audio_uri,
                duration_ms          = excluded.duration_ms,
                transcription        = excluded.transcription,
                transcription_source = excluded.transcription_source,
                drive_file_id        = excluded.drive_file_id,
                audio_drive_file_id  = excluded.audio_drive_file_id,
                updated_at           = excluded.updated_at,
                dirty                = 0
            WHERE story_voice_clip.updated_at < excluded.updated_at
            """, arguments: [
                payload.id, payload.storyItemId, payload.userId,
                resolvedAudioUri, payload.durationMs,
                payload.transcription, payload.transcriptionSource,
                driveFileId, payload.audioDriveFileId,
                payload.createdAt, payload.updatedAt,
            ])
    }

    /// Best-effort filesystem-resolution check used by the cross-
    /// device URI reconciliation in [upsertCaptureRow]. Mirror of
    /// Android's `QuickInkSyncDataSource.fileExistsAt`.
    private static func fileExistsAt(_ uri: String?) -> Bool {
        guard let uri, !uri.isEmpty else { return false }
        let path: String? = {
            if let url = URL(string: uri), url.isFileURL { return url.path }
            return uri
        }()
        guard let path else { return false }
        return FileManager.default.fileExists(atPath: path)
    }

    /// Upsert an ocr_results row. Same raw-SQL story; `blocks_json` is
    /// re-serialised from the wire `JSONAny` into the column's compact
    /// string format.
    private static func upsertOcrResultRow(_ db: Database, payload: OcrResultPayloadV2, driveFileId: String?) throws {
        let blocksJson = payload.blocks.toCompactString()
        try db.execute(sql: """
            INSERT INTO ocr_results (
                id, capture_id, page_index, language, confidence,
                text, blocks_json, engine, engine_version, drive_file_id,
                created_at, updated_at, dirty
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
            ON CONFLICT(id) DO UPDATE SET
                capture_id     = excluded.capture_id,
                page_index     = excluded.page_index,
                language       = excluded.language,
                confidence     = excluded.confidence,
                text           = excluded.text,
                blocks_json    = excluded.blocks_json,
                engine         = excluded.engine,
                engine_version = excluded.engine_version,
                drive_file_id  = excluded.drive_file_id,
                updated_at     = excluded.updated_at,
                dirty          = 0
            WHERE ocr_results.updated_at < excluded.updated_at
            """, arguments: [
                payload.id, payload.captureId, payload.pageIndex,
                payload.language, payload.confidence, payload.text,
                blocksJson, payload.engine, payload.engineVersion,
                driveFileId, payload.createdAt, payload.updatedAt,
            ])
    }
}
