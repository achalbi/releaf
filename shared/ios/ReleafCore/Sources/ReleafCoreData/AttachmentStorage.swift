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
 *   Application Support/<appFolderName>/attachments/<UUIDv7>.<ext>
 *
 * Application Support sits outside the iCloud auto-backup sweep, which
 * matches where the SQLite file lives — consistent mental model for
 * "local cache the sync worker re-asserts from Drive on fresh install".
 *
 * PR #4a moved this from
 * `apps/releaf/ios/Releaf/Features/Notepad/Sections/AttachmentStorage.swift`
 * into ReleafCoreData and parameterized the app folder name.
 *
 * Folder name parameterization:
 *   `appFolderName` defaults to "Releaf" so existing call sites work
 *   unchanged. QuickInk's app entry point (Phase 3) will set it to
 *   "QuickInk" once at process start. Mutable static is acceptable here
 *   because the value is process-wide and never changes after init.
 */

import Foundation

public enum AttachmentStorage {

    /// App-specific subfolder under Application Support. Each app sets
    /// this once at startup. Defaults to "Releaf" for backward compat
    /// with existing Releaf call sites; QuickInk overrides at app init.
    ///
    /// The static-mutable shape is deliberate: this is a process-wide
    /// constant in practice (set once, read everywhere). A struct with
    /// dependency injection would be more correct but would force every
    /// call site to thread an instance through, which buys nothing for
    /// a single-app process.
    public static var appFolderName: String = "Releaf"

    /// Full directory URL, created on first access. Throws if the app
    /// container isn't writable — rare enough that the caller can surface
    /// a generic error to the user.
    public static func directory() throws -> URL {
        let fm = FileManager.default
        let base = try fm.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        let dir = base
            .appendingPathComponent(appFolderName, isDirectory: true)
            .appendingPathComponent("attachments", isDirectory: true)
        if !fm.fileExists(atPath: dir.path) {
            try fm.createDirectory(at: dir, withIntermediateDirectories: true)
        }
        return dir
    }

    /// Writes `data` with the given extension (no leading dot) and returns
    /// the stored file's URL. Returns nil on any failure; the caller can
    /// decide whether to surface it to the user.
    public static func write(_ data: Data, ext: String) -> URL? {
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
