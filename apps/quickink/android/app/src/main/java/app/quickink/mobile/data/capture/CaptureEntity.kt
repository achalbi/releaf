/*
 * CaptureEntity.kt
 *
 * Room @Entity for the `captures` table. One row per scan session
 * (a multi-page document the user captured via
 * DocumentScannerLauncher). Schema mirrors
 * `shared/design-system/migrations/quickink/v1_initial.sql`.
 *
 * Lives in the QuickInk app target (not :shared:scan) because
 * captures is a QuickInk-specific table — Releaf doesn't have an
 * equivalent today.
 */

package app.quickink.mobile.data.capture

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "captures",
    indices = [
        Index(value = ["user_id", "created_at"], name = "idx_captures_user_created"),
        Index(value = ["dirty"], name = "idx_captures_dirty"),
        Index(value = ["deleted_at"], name = "idx_captures_tombstone"),
        // Workspace v1: folder-detail screen lists captures in a
        // folder, newest first. See v4_workspace.sql.
        Index(value = ["folder_id", "created_at"], name = "idx_captures_folder_created"),
        // Workspace v1: Continue card lookup — most-recently-opened
        // capture per user.
        Index(value = ["user_id", "last_opened_at"], name = "idx_captures_last_opened"),
    ],
)
data class CaptureEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "user_id")
    val userId: String,

    @ColumnInfo(name = "title")
    val title: String?,

    /** file:// or content:// pointing at the scanner-produced PDF. */
    @ColumnInfo(name = "pdf_uri")
    val pdfUri: String,

    /** file:// pointing at a first-page preview JPEG (thumbnail). */
    @ColumnInfo(name = "preview_uri")
    val previewUri: String?,

    @ColumnInfo(name = "page_count", defaultValue = "0")
    val pageCount: Int,

    /**
     * How the capture was created. `"scan"` (default) — went through
     * the ML Kit / VisionKit document scanner. `"import"` — was
     * brought in from the system photo picker (single image, no
     * edge detection, single-page PDF synthesised app-side). Drives
     * the "Import" pill in the Library cards. Stored as a free-form
     * TEXT so a later third source ("share-extension", etc.) can
     * land without another migration. Defaulted at the column level
     * so legacy rows synced down from Drive (without this field)
     * read back as scans.
     */
    @ColumnInfo(name = "source", defaultValue = "scan")
    val source: String = "scan",

    /**
     * Page-size class. `"card"` (business cards, +4 pts/page in the
     * tree score), `"a4"` (default, +2 pts/page), or `"small"`
     * (reserved for future smaller-than-A4 PDF imports, +1 pt/page).
     * Free-form TEXT so future formats land without another schema
     * bump; defaulted to `"a4"` so legacy rows synced down from
     * Drive (without the field on the wire) read back as standard
     * pages — no retroactive credit for historical card scans.
     */
    @ColumnInfo(name = "paper_size", defaultValue = "a4")
    val paperSize: String = "a4",

    /**
     * Decimal-degree latitude captured at scan / import time. Null
     * when the user has the "Attach location to scans" toggle off,
     * when system location permission is denied, or when the fetch
     * failed (offline / no signal). Pairs with [longitude] — either
     * both set or both null; the writer never persists half of a
     * coordinate. Round-trips through Drive sync via
     * `CapturePayloadV2`.
     */
    @ColumnInfo(name = "latitude")
    val latitude: Double? = null,

    /** Paired with [latitude]. See that field's docstring. */
    @ColumnInfo(name = "longitude")
    val longitude: Double? = null,

    /**
     * Reverse-geocoded city (e.g. "San Francisco"). Sourced from
     * Android's `Geocoder.getFromLocation` `locality` field at write
     * time. Stays as the producer device's locale-aware string —
     * we don't re-geocode on cross-device sync. Surfaced as the
     * "City" row on the Details card.
     */
    @ColumnInfo(name = "locality")
    val locality: String? = null,

    /**
     * Reverse-geocoded neighbourhood / area (e.g. "Mission District").
     * `Geocoder.subLocality`. Surfaced as the "Area" row on the
     * Details card.
     */
    @ColumnInfo(name = "sub_locality")
    val subLocality: String? = null,

    /**
     * Formatted full street address built from `Geocoder` results —
     * e.g. "1234 Main St, Mission District, San Francisco, CA
     * 94110, USA". Surfaced as the "Address" row on the Details
     * card. Round-trips through Drive verbatim — receivers see
     * exactly what the capturing device's locale produced.
     */
    @ColumnInfo(name = "address")
    val address: String? = null,

    /**
     * Workspace v1: the folder this capture lives in. Nullable at the
     * column level so the v4 migration can backfill in a second pass
     * (every existing capture is moved into the seeded "Unfiled"
     * folder on first launch after upgrade — see
     * `FolderRepository.seedDefaultsIfNeeded` + `CaptureRepository
     * .backfillFolderId`). After backfill, app code asserts non-null
     * on read. A capture cannot live nowhere and cannot live in two
     * folders — one row, one folder.
     */
    @ColumnInfo(name = "folder_id")
    val folderId: String? = null,

    /**
     * Workspace v1: ISO timestamp of the last time the user opened
     * this capture in the PDF reader. Written on a debounced
     * page-scroll signal (~500ms) so a quick skim doesn't pollute
     * the Continue card. NULL = never opened. Most-recent across the
     * user's captures powers the Workspace home Continue card.
     */
    @ColumnInfo(name = "last_opened_at")
    val lastOpenedAt: String? = null,

    /**
     * Workspace v1: 1-indexed page the user was on when they last
     * closed the reader. Paired with [lastOpenedAt] — either both
     * set or both null. NULL after migration; populated on first
     * reopen.
     */
    @ColumnInfo(name = "last_opened_page")
    val lastOpenedPage: Int? = null,

    /**
     * Workspace v1: install id of the device that last touched the
     * capture. Reserved for the future cross-device Continue UX
     * ("you on iPhone, 2h ago"). Not surfaced in the v1 home
     * screen — column reservation only.
     */
    @ColumnInfo(name = "last_opened_device")
    val lastOpenedDevice: String? = null,

    @ColumnInfo(name = "conflict_stub")
    val conflictStub: String?,

    @ColumnInfo(name = "drive_file_id")
    val driveFileId: String?,

    /**
     * Drive file id of the per-row PDF binary upload. NULL = the
     * PDF hasn't been pushed to Drive yet (or the local file went
     * missing before upload). Populated by `SyncRepository` via
     * `markPdfSynced` after `DriveClient.uploadBinary` returns.
     * Defaulted so legacy construction sites keep compiling — only
     * the upload pipeline writes a non-null value.
     */
    @ColumnInfo(name = "pdf_drive_file_id")
    val pdfDriveFileId: String? = null,

    /**
     * Drive file id of the per-row preview-JPEG upload. Same
     * lifecycle as [pdfDriveFileId]; null when the binary hasn't
     * been mirrored to Drive.
     */
    @ColumnInfo(name = "preview_drive_file_id")
    val previewDriveFileId: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: String,

    @ColumnInfo(name = "updated_at")
    val updatedAt: String,

    @ColumnInfo(name = "dirty", defaultValue = "1")
    val dirty: Boolean,

    @ColumnInfo(name = "deleted_at")
    val deletedAt: String?,
)

/**
 * Display title for [CaptureEntity] across the app's lists and
 * thumbnails. Priority: user-set [title] (trimmed, non-empty) →
 * [primaryTagName] (the capture's first attached tag) → [fallback].
 * Centralised so the Library, Home recents, Search hits, and tag
 * drill-down all surface the same string.
 */
fun CaptureEntity.displayTitle(
    primaryTagName: String?,
    fallback: String = "Scan",
): String {
    val titled = title?.trim().orEmpty()
    if (titled.isNotEmpty()) return titled
    val tag = primaryTagName?.trim().orEmpty()
    if (tag.isNotEmpty()) return tag
    return fallback
}

/**
 * Convenience overload for the legacy callers that didn't have a
 * primary-tag lookup in hand. Equivalent to `displayTitle(null,
 * fallback)` — falls straight from [title] to [fallback].
 */
fun CaptureEntity.displayTitle(fallback: String = "Scan"): String =
    displayTitle(primaryTagName = null, fallback = fallback)
