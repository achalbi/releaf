/*
 * QuickInkBinarySync.swift
 *
 * Phase 6 — back up the actual scanned PDFs + preview JPEGs to
 * Drive. Sits alongside `SyncRepository` (which only ships JSON
 * metadata) and runs in the same scheduler tick:
 *
 *   1. uploadAndCascade  — for every active capture missing a Drive
 *      binary id, read the local file, upload to Drive at a date-
 *      bucketed path, and stamp the resulting Drive file id back
 *      onto the local row. For tombstoned captures with non-null
 *      Drive ids, trash the binaries and null the ids out.
 *   2. restorePending    — for every active capture whose row has
 *      a Drive id but whose local file is missing (fresh-device
 *      restore from Drive's metadata only), download the binary
 *      back into `AttachmentStorage` and rewrite `pdf_uri` /
 *      `preview_uri` to the new local path.
 *
 * Drive layout matches `QuickInkSyncDataSource`'s JSON paths so a
 * folder listing reads naturally:
 *   Thoughtbasics/QuickInk/<yyyy>/<mm>/<dd>/<id>.json    (metadata)
 *   Thoughtbasics/QuickInk/<yyyy>/<mm>/<dd>/<id>.pdf     (binary)
 *   Thoughtbasics/QuickInk/<yyyy>/<mm>/<dd>/<id>.jpg     (preview)
 *
 * Mirror of Android `QuickInkBinarySync.kt`.
 */

import Foundation
import GRDB
import ReleafCoreData
import ReleafCoreDrive

@MainActor
public final class QuickInkBinarySync: @unchecked Sendable {

    private let database: QuickInkDatabase
    private let driveClient: DriveClient
    private let driveRootFolderName: String

    public init(
        database: QuickInkDatabase = .shared,
        driveClient: DriveClient,
        driveRootFolderName: String = "Thoughtbasics/QuickInk"
    ) {
        self.database = database
        self.driveClient = driveClient
        self.driveRootFolderName = driveRootFolderName
    }

    // MARK: - Upload + cascade

    /// Run one upload-and-cascade pass for the given user. Idempotent:
    /// captures already mirrored to Drive are skipped, tombstoned
    /// rows whose Drive ids are already nulled are skipped.
    public func uploadAndCascade(userId: String, accessToken: String) async throws {
        let repo = CaptureRepository(database: database)
        let pending = try await repo.pendingBinaryRows(userId: userId)

        // Voice notes — gather missing-audio + tombstoned-with-audio
        // rows. Loaded once here so we can decide up-front whether to
        // do an early-out on a fully-quiet pass (nothing to upload at
        // all, neither captures nor voice).
        let voiceUploads = try await pendingVoiceUploads(userId: userId)
        let voiceTombstones = try await pendingVoiceTombstones(userId: userId)

        // Story voice clips — same shape as voice notes, keyed off
        // story_item_id rather than capture_id.
        let storyVoiceUploads = try await pendingStoryVoiceUploads(userId: userId)
        let storyVoiceTombstones = try await pendingStoryVoiceTombstones(userId: userId)

        if pending.isEmpty &&
            voiceUploads.isEmpty && voiceTombstones.isEmpty &&
            storyVoiceUploads.isEmpty && storyVoiceTombstones.isEmpty { return }

        let root = try await driveClient.ensureRootFolder(
            named: driveRootFolderName,
            accessToken: accessToken
        )

        // ---- voice notes ----
        for row in voiceUploads {
            guard row.audioDriveFileId == nil else { continue }
            guard let bytes = readLocalFile(uri: row.audioUri) else { continue }
            let path = "\(dateBucket(row.createdAt))/\(row.captureId)/voice-\(row.id).m4a"
            if let driveFile = try? await driveClient.uploadBinaryAtPath(
                bytes,
                contentType: "audio/mp4",
                relativePath: path,
                rootFolderId: root.id,
                accessToken: accessToken
            ) {
                try? await markVoiceAudioSynced(id: row.id, driveFileId: driveFile.id)
            }
        }
        for row in voiceTombstones {
            if let audioId = row.audioDriveFileId {
                try? await driveClient.trash(fileId: audioId, accessToken: accessToken)
                try? await markVoiceAudioSynced(id: row.id, driveFileId: nil)
            }
        }

        // ---- story voice clips ----
        for row in storyVoiceUploads {
            guard row.audioDriveFileId == nil else { continue }
            guard let bytes = readLocalFile(uri: row.audioUri) else { continue }
            let path = "\(dateBucket(row.createdAt))/\(row.storyItemId)/storyvoice-\(row.id).m4a"
            if let driveFile = try? await driveClient.uploadBinaryAtPath(
                bytes,
                contentType: "audio/mp4",
                relativePath: path,
                rootFolderId: root.id,
                accessToken: accessToken
            ) {
                try? await markStoryVoiceAudioSynced(id: row.id, driveFileId: driveFile.id)
            }
        }
        for row in storyVoiceTombstones {
            if let audioId = row.audioDriveFileId {
                try? await driveClient.trash(fileId: audioId, accessToken: accessToken)
                try? await markStoryVoiceAudioSynced(id: row.id, driveFileId: nil)
            }
        }

        for row in pending {
            if row.deletedAt != nil {
                // Cascade trash. Best-effort — a 404 from Drive is OK
                // (file already gone). We always null the local id
                // so we don't keep retrying.
                if let pdfId = row.pdfDriveFileId {
                    try? await driveClient.trash(fileId: pdfId, accessToken: accessToken)
                    try? await repo.markPdfSynced(captureId: row.id, driveFileId: nil)
                }
                if let previewId = row.previewDriveFileId {
                    try? await driveClient.trash(fileId: previewId, accessToken: accessToken)
                    try? await repo.markPreviewSynced(captureId: row.id, driveFileId: nil)
                }
                if let videoId = row.videoDriveFileId {
                    try? await driveClient.trash(fileId: videoId, accessToken: accessToken)
                    try? await repo.markVideoSynced(captureId: row.id, driveFileId: nil)
                }
            } else {
                // Live row — upload missing binaries.
                if row.pdfDriveFileId == nil, let bytes = readLocalFile(uri: row.pdfUri) {
                    let path = "\(dateBucket(row.createdAt))/\(row.id).pdf"
                    if let driveFile = try? await driveClient.uploadBinaryAtPath(
                        bytes,
                        contentType: "application/pdf",
                        relativePath: path,
                        rootFolderId: root.id,
                        accessToken: accessToken
                    ) {
                        try? await repo.markPdfSynced(captureId: row.id, driveFileId: driveFile.id)
                    }
                }

                if row.previewDriveFileId == nil,
                   let previewUri = row.previewUri,
                   let bytes = readLocalFile(uri: previewUri) {
                    let path = "\(dateBucket(row.createdAt))/\(row.id).jpg"
                    if let driveFile = try? await driveClient.uploadBinaryAtPath(
                        bytes,
                        contentType: "image/jpeg",
                        relativePath: path,
                        rootFolderId: root.id,
                        accessToken: accessToken
                    ) {
                        try? await repo.markPreviewSynced(captureId: row.id, driveFileId: driveFile.id)
                    }
                }

                // Video — only set for hold-to-record Photo-mode
                // captures. Same date-bucketed path as the PDF +
                // preview so a folder listing groups all three.
                // `video/mp4` is the MIME we ship even though iOS
                // hands us a `.mov` container — the underlying H.264
                // / AAC streams are the same, and Drive doesn't
                // care about the wrapper. We keep the `.mp4`
                // extension on the Drive object so the Android
                // download path doesn't have to special-case the
                // platform-specific container.
                if row.videoDriveFileId == nil,
                   let videoUri = row.videoUri,
                   let bytes = readLocalFile(uri: videoUri) {
                    let path = "\(dateBucket(row.createdAt))/\(row.id).mp4"
                    if let driveFile = try? await driveClient.uploadBinaryAtPath(
                        bytes,
                        contentType: "video/mp4",
                        relativePath: path,
                        rootFolderId: root.id,
                        accessToken: accessToken
                    ) {
                        try? await repo.markVideoSynced(captureId: row.id, driveFileId: driveFile.id)
                    }
                }
            }
        }
    }

    // MARK: - Restore

    /// Pull binaries from Drive for any active capture whose row
    /// has a Drive id but no readable local file. Used after a
    /// fresh-device restore: the JSON metadata syncs first, then
    /// this fills in the actual PDFs + previews.
    public func restorePending(userId: String, accessToken: String) async throws {
        let dbQueue = database.dbQueue
        struct LocalRow {
            let id: String
            let pdfUri: String
            let previewUri: String?
            let videoUri: String?
            let pdfDriveFileId: String?
            let previewDriveFileId: String?
            let videoDriveFileId: String?
        }

        let rows: [LocalRow] = try await dbQueue.read { db in
            let raw = try Row.fetchAll(db, sql: """
                SELECT id, pdf_uri, preview_uri, video_uri,
                       pdf_drive_file_id, preview_drive_file_id, video_drive_file_id
                FROM captures
                WHERE user_id = ? AND deleted_at IS NULL
                  AND (
                       pdf_drive_file_id     IS NOT NULL
                    OR preview_drive_file_id IS NOT NULL
                    OR video_drive_file_id   IS NOT NULL
                  )
                """, arguments: [userId])
            return raw.map { row in
                LocalRow(
                    id: row["id"],
                    pdfUri: row["pdf_uri"],
                    previewUri: row["preview_uri"] as String?,
                    videoUri: row["video_uri"] as String?,
                    pdfDriveFileId: row["pdf_drive_file_id"] as String?,
                    previewDriveFileId: row["preview_drive_file_id"] as String?,
                    videoDriveFileId: row["video_drive_file_id"] as String?
                )
            }
        }

        let repo = CaptureRepository(database: database)
        for row in rows {
            // PDF — restore if Drive has it and local file is missing.
            if let pdfDriveId = row.pdfDriveFileId, !localFileExists(uri: row.pdfUri) {
                if let bytes = try? await driveClient.downloadBytes(
                    fileId: pdfDriveId,
                    accessToken: accessToken
                ),
                   let url = AttachmentStorage.write(bytes, ext: "pdf") {
                    try? await repo.updateLocalUris(
                        captureId: row.id,
                        pdfUri: url.absoluteString,
                        previewUri: nil
                    )
                }
            }
            // Preview JPEG.
            if let previewDriveId = row.previewDriveFileId,
               !localFileExists(uri: row.previewUri) {
                if let bytes = try? await driveClient.downloadBytes(
                    fileId: previewDriveId,
                    accessToken: accessToken
                ),
                   let url = AttachmentStorage.write(bytes, ext: "jpg") {
                    try? await repo.updateLocalUris(
                        captureId: row.id,
                        pdfUri: nil,
                        previewUri: url.absoluteString
                    )
                }
            }
            // Video — only present for hold-to-record Photo-mode
            // captures. Same restore shape as the PDF + preview
            // path: download the bytes, drop them into
            // AttachmentStorage, then rewrite `video_uri` to the
            // local file:// path so the detail screen's player
            // resolves on this device.
            if let videoDriveId = row.videoDriveFileId,
               !localFileExists(uri: row.videoUri) {
                if let bytes = try? await driveClient.downloadBytes(
                    fileId: videoDriveId,
                    accessToken: accessToken
                ),
                   let url = AttachmentStorage.write(bytes, ext: "mp4") {
                    try? await repo.updateLocalUris(
                        captureId: row.id,
                        pdfUri: nil,
                        previewUri: nil,
                        videoUri: url.absoluteString
                    )
                }
            }
        }
    }

    /// Pull voice-note audio binaries from Drive for any active row
    /// whose `audio_drive_file_id` is set but whose local file is
    /// missing. Mirror of the PDF / preview restore loop above.
    public func restorePendingVoiceNotes(userId: String, accessToken: String) async throws {
        let dbQueue = database.dbQueue
        let rows: [VoiceNoteEntity] = try await dbQueue.read { db in
            try VoiceNoteEntity
                .filter(Column("user_id") == userId)
                .filter(Column("deleted_at") == nil)
                .filter(Column("audio_drive_file_id") != nil)
                .fetchAll(db)
        }
        for row in rows {
            guard !localFileExists(uri: row.audioUri),
                  let driveId = row.audioDriveFileId else { continue }
            if let bytes = try? await driveClient.downloadBytes(
                fileId: driveId,
                accessToken: accessToken
            ),
               let url = AttachmentStorage.write(bytes, ext: "m4a") {
                try? await setVoiceAudioUri(id: row.id, uri: url.absoluteString)
            }
        }
    }

    /// Pull story voice-clip .m4a binaries from Drive for any active
    /// row whose `audio_drive_file_id` is set but whose local file is
    /// missing. Mirror of [restorePendingVoiceNotes].
    public func restorePendingStoryVoiceClips(userId: String, accessToken: String) async throws {
        let dbQueue = database.dbQueue
        let rows: [StoryVoiceClip] = try await dbQueue.read { db in
            try StoryVoiceClip
                .filter(Column("user_id") == userId)
                .filter(Column("deleted_at") == nil)
                .filter(Column("audio_drive_file_id") != nil)
                .fetchAll(db)
        }
        for row in rows {
            guard !localFileExists(uri: row.audioUri),
                  let driveId = row.audioDriveFileId else { continue }
            if let bytes = try? await driveClient.downloadBytes(
                fileId: driveId,
                accessToken: accessToken
            ),
               let url = AttachmentStorage.write(bytes, ext: "m4a") {
                try? await setStoryVoiceAudioUri(id: row.id, uri: url.absoluteString)
            }
        }
    }

    // MARK: - Voice helpers

    private func pendingVoiceUploads(userId: String) async throws -> [VoiceNoteEntity] {
        try await database.dbQueue.read { db in
            try VoiceNoteEntity
                .filter(Column("user_id") == userId)
                .filter(Column("deleted_at") == nil)
                .filter(Column("audio_drive_file_id") == nil)
                .fetchAll(db)
        }
    }

    private func pendingVoiceTombstones(userId: String) async throws -> [VoiceNoteEntity] {
        try await database.dbQueue.read { db in
            try VoiceNoteEntity
                .filter(Column("user_id") == userId)
                .filter(Column("deleted_at") != nil)
                .filter(Column("dirty") == true)
                .filter(Column("audio_drive_file_id") != nil)
                .fetchAll(db)
        }
    }

    private func markVoiceAudioSynced(id: String, driveFileId: String?) async throws {
        try await database.dbQueue.write { db in
            try db.execute(sql: """
                UPDATE voice_notes SET audio_drive_file_id = ? WHERE id = ?
                """, arguments: [driveFileId, id])
        }
    }

    private func setVoiceAudioUri(id: String, uri: String) async throws {
        try await database.dbQueue.write { db in
            try db.execute(sql: """
                UPDATE voice_notes SET audio_uri = ? WHERE id = ?
                """, arguments: [uri, id])
        }
    }

    // MARK: - Story voice-clip helpers

    private func pendingStoryVoiceUploads(userId: String) async throws -> [StoryVoiceClip] {
        try await database.dbQueue.read { db in
            try StoryVoiceClip
                .filter(Column("user_id") == userId)
                .filter(Column("deleted_at") == nil)
                .filter(Column("audio_drive_file_id") == nil)
                .fetchAll(db)
        }
    }

    private func pendingStoryVoiceTombstones(userId: String) async throws -> [StoryVoiceClip] {
        try await database.dbQueue.read { db in
            try StoryVoiceClip
                .filter(Column("user_id") == userId)
                .filter(Column("deleted_at") != nil)
                .filter(Column("dirty") == true)
                .filter(Column("audio_drive_file_id") != nil)
                .fetchAll(db)
        }
    }

    private func markStoryVoiceAudioSynced(id: String, driveFileId: String?) async throws {
        try await database.dbQueue.write { db in
            try db.execute(sql: """
                UPDATE story_voice_clip SET audio_drive_file_id = ? WHERE id = ?
                """, arguments: [driveFileId, id])
        }
    }

    private func setStoryVoiceAudioUri(id: String, uri: String) async throws {
        try await database.dbQueue.write { db in
            try db.execute(sql: """
                UPDATE story_voice_clip SET audio_uri = ? WHERE id = ?
                """, arguments: [uri, id])
        }
    }

    // MARK: - Helpers

    /// `YYYY/MM/DD` bucket — same shape `DrivePath.quickInkCapture`
    /// uses, so binaries land beside their JSON manifest.
    private func dateBucket(_ iso: String) -> String {
        guard iso.count >= 10 else { return "0000/00/00" }
        let yyyy = String(iso.prefix(4))
        let mm   = String(iso.dropFirst(5).prefix(2))
        let dd   = String(iso.dropFirst(8).prefix(2))
        return "\(yyyy)/\(mm)/\(dd)"
    }

    private func readLocalFile(uri: String) -> Data? {
        guard let url = parseLocalURL(uri) else { return nil }
        return try? Data(contentsOf: url)
    }

    private func localFileExists(uri: String?) -> Bool {
        guard let uri, let url = parseLocalURL(uri) else { return false }
        return FileManager.default.fileExists(atPath: url.path)
    }

    private func parseLocalURL(_ raw: String) -> URL? {
        if raw.isEmpty { return nil }
        if let url = URL(string: raw), url.isFileURL { return url }
        return URL(fileURLWithPath: raw)
    }
}
