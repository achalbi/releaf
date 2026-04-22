/*
 * AttachmentStorage.swift
 *
 * Writes photo / scan bytes into the app's own files directory so the
 * stored `Attachment.uri` remains valid across launches. iOS's
 * PhotosPicker and VNDocumentCameraViewController both hand us bytes
 * (via `loadTransferable(Data.self)` and `UIImage`, respectively); this
 * helper is the single place we decide how those land on disk.
 *
 * Directory layout:
 *   Application Support/Releaf/attachments/<UUIDv7>.<ext>
 *
 * Application Support sits outside the iCloud auto-backup sweep, which
 * matches where the SQLite file lives — consistent mental model for
 * "local cache the sync worker re-asserts from Drive on fresh install".
 */

import Foundation
import ReleafData

enum AttachmentStorage {

    /// Full directory URL, created on first access. Throws if the app
    /// container isn't writable — rare enough that the caller can surface
    /// a generic error to the user.
    static func directory() throws -> URL {
        let fm = FileManager.default
        let base = try fm.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        let dir = base
            .appendingPathComponent("Releaf", isDirectory: true)
            .appendingPathComponent("attachments", isDirectory: true)
        if !fm.fileExists(atPath: dir.path) {
            try fm.createDirectory(at: dir, withIntermediateDirectories: true)
        }
        return dir
    }

    /// Writes `data` with the given extension (no leading dot) and returns
    /// the stored file's URL. Returns nil on any failure; the caller can
    /// decide whether to surface it to the user.
    static func write(_ data: Data, ext: String) -> URL? {
        do {
            let dir = try directory()
            let url = dir.appendingPathComponent("\(Uuidv7.generate()).\(ext)")
            try data.write(to: url, options: .atomic)
            return url
        } catch {
            return nil
        }
    }
}
