/*
 * DrivePath.swift
 *
 * Deterministic Drive paths per `docs/DRIVE_SCHEMA.md` §"Root layout".
 * Mirror of Android's `DrivePath.kt` — byte-for-byte identical output
 * for the same inputs is a hard invariant.
 *
 * See the Android file's header for the spec-deviation rationale
 * (flat paths for notebook/chapter/page, date-bucketed for notepad/
 * daily_log, per-id for the rest).
 */

import Foundation

public enum DrivePath {

    public static let rootFolder = "Releaf"
    public static let manifest   = "manifest.json"

    // ---- entity kinds ----
    public static let kindNotebook       = "notebook"
    public static let kindChapter        = "chapter"
    public static let kindPage           = "page"
    public static let kindNotepadEntry   = "notepad_entry"
    public static let kindDailyLog       = "daily_log"
    public static let kindCapture        = "capture"
    public static let kindTask           = "task"
    public static let kindTag            = "tag"
    public static let kindProject        = "project"
    public static let kindReferenceLink  = "reference_link"
    public static let kindPageTemplate   = "page_template"

    // ---- folder names ----
    public static let folderNotebooks       = "notebooks"
    public static let folderChapters        = "chapters"
    public static let folderPages           = "pages"
    public static let folderNotepadEntries  = "notepad_entries"
    public static let folderDailyLogs       = "daily_logs"
    public static let folderCaptures        = "captures"
    public static let folderTasks           = "tasks"
    public static let folderTombstones      = "tombstones"

    // ---- path builders ----

    public static func notebook(id: String) -> String { "\(folderNotebooks)/\(id).json" }

    public static func chapter(id: String) -> String { "\(folderChapters)/\(id).json" }

    public static func page(id: String) -> String { "\(folderPages)/\(id).json" }

    /// `entryDate` is YYYY-MM-DD.
    public static func notepadEntry(entryDate: String, entryId: String) -> String {
        precondition(isYyyyMmDd(entryDate), "entryDate must be YYYY-MM-DD, got \(entryDate)")
        let yyyy = String(entryDate.prefix(4))
        let mm   = String(entryDate.dropFirst(5).prefix(2))
        return "\(folderNotepadEntries)/\(yyyy)/\(mm)/\(entryId).json"
    }

    /// `logDate` is YYYY-MM-DD.
    public static func dailyLog(logDate: String) -> String {
        precondition(isYyyyMmDd(logDate), "logDate must be YYYY-MM-DD, got \(logDate)")
        let yyyy = String(logDate.prefix(4))
        return "\(folderDailyLogs)/\(yyyy)/\(logDate).json"
    }

    public static func task(id: String) -> String { "\(folderTasks)/\(id).json" }

    public static func tombstone(id: String) -> String { "\(folderTombstones)/\(id).json" }

    // ---- helpers ----

    private static func isYyyyMmDd(_ s: String) -> Bool {
        guard s.count == 10 else { return false }
        let chars = Array(s)
        guard chars[4] == "-", chars[7] == "-" else { return false }
        for idx in [0, 1, 2, 3, 5, 6, 8, 9] where !chars[idx].isNumber { return false }
        return true
    }
}
