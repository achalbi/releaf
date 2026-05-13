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
    public static let kindOcrResult      = "ocr_result"
    public static let kindTask           = "task"
    public static let kindTag            = "tag"
    public static let kindProject        = "project"
    public static let kindReferenceLink  = "reference_link"
    public static let kindPageTemplate   = "page_template"
    /// QuickInk-only — user-configurable category for `captures.category`.
    /// Semantically renamed to "tag" in Workspace v1; the wire kind
    /// keeps the legacy string for back-compat with payloads on
    /// Drive written by older clients during the rollout window.
    public static let kindCategory       = "category"

    // ─── Workspace v1 ──────────────────────────────────────────
    /// QuickInk Workspace folder ("intent" axis; one per capture).
    public static let kindFolder           = "folder"
    /// QuickInk capture↔tag many-to-many join row.
    public static let kindCaptureTag       = "capture_tag"
    /// QuickInk rule-based saved view.
    public static let kindSmartCollection  = "smart_collection"

    // ---- folder names ----
    public static let folderNotebooks       = "notebooks"
    public static let folderChapters        = "chapters"
    public static let folderPages           = "pages"
    public static let folderNotepadEntries  = "notepad_entries"
    public static let folderDailyLogs       = "daily_logs"
    public static let folderCaptures        = "captures"
    /// QuickInk's OCR-result tree — `ocr/{captureId}/page-{N}.json`.
    public static let folderOcr             = "ocr"
    /// QuickInk's category list — `categories/{id}.json`. Legacy
    /// prefix; Workspace v1 writes go under [folderTags] instead.
    /// Kept for back-compat reads of payloads on Drive from older
    /// clients.
    public static let folderCategories      = "categories"
    /// QuickInk's tag list — `tags/{id}.json`. Workspace v1
    /// destination; readers fall back to `categories/` during the
    /// rollout soak.
    public static let folderTags            = "tags"
    /// QuickInk Workspace folders — `folders/{id}.json`.
    public static let folderFolders         = "folders"
    /// QuickInk capture↔tag joins — `capture_tags/{id}.json`.
    public static let folderCaptureTags     = "capture_tags"
    /// QuickInk smart collections — `smart_collections/{id}.json`.
    public static let folderSmartCollections = "smart_collections"
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

    /// QuickInk's per-capture file — `captures/{id}.json`.
    public static func capture(id: String) -> String { "\(folderCaptures)/\(id).json" }

    /// Date-bucketed QuickInk capture path — `{yyyy}/{mm}/{dd}/{id}.json`,
    /// relative to the data source's drive root. Used by the
    /// QuickInk sync data source so scans land under year/month/day
    /// folders inside `Thoughtbasics/QuickInk/...`. The bucket is
    /// derived from the first 10 chars of `createdAt` (ISO-8601 date
    /// prefix); a malformed timestamp falls back to `0000/00/00`
    /// rather than crashing.
    public static func quickInkCapture(createdAt: String, id: String) -> String {
        "\(quickInkDateBucket(from: createdAt))/\(id).json"
    }

    /// QuickInk's per-page OCR-result file —
    /// `ocr/{captureId}/page-{pageIndex}.json`. Page index is 0-based,
    /// matching `ocr_results.page_index`.
    public static func ocrResult(captureId: String, pageIndex: Int) -> String {
        "\(folderOcr)/\(captureId)/page-\(pageIndex).json"
    }

    /// Date-bucketed QuickInk OCR-result path —
    /// `{yyyy}/{mm}/{dd}/{captureId}/page-{N}.json`. Co-locates the
    /// OCR rows with their parent capture's day folder.
    public static func quickInkOcrResult(createdAt: String, captureId: String, pageIndex: Int) -> String {
        "\(quickInkDateBucket(from: createdAt))/\(captureId)/page-\(pageIndex).json"
    }

    /// `YYYY/MM/DD` triplet derived from an ISO-8601 timestamp's
    /// date prefix. Falls back to `0000/00/00` for malformed input
    /// so the sync layer never crashes on a single bad row — those
    /// rows just pile up in the same legacy bucket.
    private static func quickInkDateBucket(from iso: String) -> String {
        guard iso.count >= 10 else { return "0000/00/00" }
        let yyyy = String(iso.prefix(4))
        let mm   = String(iso.dropFirst(5).prefix(2))
        let dd   = String(iso.dropFirst(8).prefix(2))
        return "\(yyyy)/\(mm)/\(dd)"
    }

    /// QuickInk's per-category file — `categories/{id}.json`.
    /// Legacy reader path; Workspace v1 writes go through [tag].
    public static func category(id: String) -> String { "\(folderCategories)/\(id).json" }

    /// QuickInk's per-tag file — `tags/{id}.json` (Workspace v1).
    public static func tag(id: String) -> String { "\(folderTags)/\(id).json" }

    /// QuickInk Workspace folder payload — `folders/{id}.json`.
    public static func folder(id: String) -> String { "\(folderFolders)/\(id).json" }

    /// QuickInk capture↔tag join payload — `capture_tags/{id}.json`.
    public static func captureTag(id: String) -> String { "\(folderCaptureTags)/\(id).json" }

    /// QuickInk smart-collection payload — `smart_collections/{id}.json`.
    public static func smartCollection(id: String) -> String { "\(folderSmartCollections)/\(id).json" }

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
