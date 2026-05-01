/*
 * SyncDataSource.swift
 *
 * The seam that lets `SyncRepository` (in this same module, arriving in
 * PR #3b) serve both Releaf and QuickInk without knowing either app's
 * tables.
 *
 * Each app implements this protocol against its own SQLite store.
 * `SyncRepository` orchestrates a sync pass — manifest read, version
 * gate, push dirty rows, push tombstones, pull delta, write manifest —
 * and asks the data source for everything that's app-specific:
 *
 *   - "What rows are dirty?"           → nextDirtyBatch
 *   - "What deletions need pushing?"   → nextTombstoneBatch
 *   - "Apply this remote upsert."      → applyRemoteUpsert
 *   - "Apply this remote tombstone."   → applyRemoteTombstone
 *   - "These IDs uploaded clean."      → markSynced
 *
 * The contract intentionally speaks bytes, not rows. The data source is
 * responsible for decoding/encoding the canonical-JSON payloads
 * documented in docs/DRIVE_SCHEMA.md — the sync repo never sees a
 * column name and never opens a database connection. That's what lets
 * Releaf and QuickInk keep diverging schemas while sharing the
 * orchestrator.
 *
 * `kind` is a string, not an enum, for the same reason: an enum here
 * would force the shared module to know about every app's entity
 * kinds (Releaf has notebooks, chapters, pages, tasks, …; QuickInk
 * adds ocr_results). A string keeps the contract open and the
 * manifest layout uniform.
 *
 * Cursors are opaque so the data source can paginate however it likes
 * (per-table, per-updated_at, per-shard) without leaking that detail.
 *
 * See docs/QUICKINK_DESIGN.md §1 for the full design rationale + the
 * Releaf and QuickInk implementation notes.
 */

import Foundation

// MARK: - Protocol

public protocol SyncDataSource: Sendable {

    // ─── Identity / configuration ──────────────────────────────────────

    /// Top-level Drive folder name. Releaf returns `"Releaf"`, QuickInk
    /// returns `"QuickInk"`. The sync repository concatenates this with
    /// the manifest path it computes.
    var driveRootFolderName: String { get }

    /// Schema version this app currently speaks. Embedded into the
    /// manifest. A peer device that reads a manifest with a major
    /// version higher than its own returns `versionBlocked: true` from
    /// the sync pass — the user gets prompted to update the app.
    var schemaVersion: SchemaVersion { get }

    /// Stable identifier for the app — `"releaf"` or `"quickink"`.
    /// Tagged onto manifest entries so multi-app future debugging
    /// (e.g. inspecting a Drive folder shared between sibling apps)
    /// stays sane.
    var appId: String { get }

    // ─── Outbound: collect dirty rows ──────────────────────────────────

    /// Snapshot the next chunk of locally-dirty entities the sync worker
    /// should push this round. Implementations stream from each entity
    /// kind's dirty index in `updated_at` order. The worker keeps
    /// calling until it gets back a batch with `nextCursor == nil`.
    ///
    /// Pagination is data-source-defined: the cursor is opaque to the
    /// worker. A typical implementation packs `(entity_kind, updated_at,
    /// id)` so each new call resumes after the last row from the
    /// previous batch.
    ///
    /// `limit` is a hint — implementations may return fewer rows (e.g.
    /// to keep Drive payload size bounded) but should not return more.
    func nextDirtyBatch(after cursor: SyncCursor?, limit: Int) async throws -> DirtyBatch

    /// Snapshot the next chunk of tombstones to propagate. Same paging
    /// shape as `nextDirtyBatch` but the cursor namespace is independent.
    func nextTombstoneBatch(after cursor: SyncCursor?, limit: Int) async throws -> TombstoneBatch

    // ─── Inbound: apply remote changes ─────────────────────────────────

    /// Apply a remote upsert. The data source is responsible for:
    ///   1. Decoding `change.payload` per its own canonical schema
    ///      (the bytes are the same canonical JSON it emitted on the
    ///      uploading side).
    ///   2. Last-write-wins comparison against the local row by
    ///      `updatedAt` — local wins ties.
    ///   3. Clearing the local `dirty` bit if the remote write
    ///      supersedes ours.
    ///   4. Stamping `drive_file_id` from the manifest path if it's
    ///      a new local row.
    ///
    /// Idempotent: if the local row is already at this version the
    /// data source returns without writing.
    func applyRemoteUpsert(_ change: RemoteUpsert) async throws

    /// Apply a remote tombstone. Soft-delete the local row matching
    /// `(kind, id)` by setting `deleted_at = tombstone.deletedAt`,
    /// `dirty = 0`. Idempotent.
    func applyRemoteTombstone(_ tombstone: RemoteTombstone) async throws

    // ─── Bookkeeping ───────────────────────────────────────────────────

    /// Mark the given local rows as cleanly synced. Called by the worker
    /// after a successful Drive upload, in batches.
    ///
    /// For each ack: set `dirty = 0` and stamp `drive_file_id` if newly
    /// uploaded. The data source MUST do this race-safely — only flip
    /// to `dirty = 0` if `updated_at` still matches the value reported
    /// in the original `DirtyEntry` (otherwise the row was modified
    /// between the read and the upload, and we shouldn't clear the bit).
    func markSynced(_ acks: [SyncAck]) async throws

    /// Most recent remote manifest etag the app has applied. Lets the
    /// worker skip the full pull when Drive hasn't changed. Nil on
    /// first sync after install.
    func lastAppliedManifestEtag() async throws -> String?

    /// Persist the etag of the manifest the worker just applied. Called
    /// at the end of a successful sync pass.
    func setLastAppliedManifestEtag(_ etag: String) async throws
}

// MARK: - Identity types

/// Schema version embedded in the Drive manifest. Major bumps gate
/// cross-version reads; minor bumps are forward-compatible additions.
public struct SchemaVersion: Equatable, Sendable, Codable {
    public let major: Int
    public let minor: Int

    public init(major: Int, minor: Int) {
        self.major = major
        self.minor = minor
    }
}

// MARK: - Outbound types

/// A single locally-dirty entity ready to upload. The data source did
/// the canonical-JSON serialisation; the sync worker just moves bytes.
public struct DirtyEntry: Sendable, Equatable {
    /// Stable, app-defined kind tag. Examples:
    /// `"notebook"`, `"chapter"`, `"page"`, `"notepad_entry"`,
    /// `"capture"`, `"task"`, `"reminder"`, `"ocr_result"`.
    /// Used as the manifest bucket and as the routing key for inbound
    /// upserts on the receiving side.
    public let kind: String

    /// UUIDv7. Stable across devices. NOT Drive's `fileId`.
    public let id: String

    /// Path under the Drive root where this entity's bytes land.
    /// Computed by the data source per the per-kind layout in
    /// docs/DRIVE_SCHEMA.md (e.g. `notebooks/{nb}/{ch}/{page}.json`,
    /// `notepad_entries/{yyyy}/{mm}/{id}.json`).
    public let drivePath: String

    /// Canonical-JSON bytes. Sort-keyed, no extraneous whitespace —
    /// the same bytes the sha256 below was computed over.
    public let payload: Data

    /// SHA-256 of `payload`, hex-encoded. Used for manifest entries
    /// and for skip-if-unchanged comparison against the remote
    /// manifest.
    public let payloadSha256: String

    /// `updated_at` from the source row, ISO-8601 UTC with ms.
    /// Used for ordering across batches and for race-safe
    /// `markSynced`.
    public let updatedAt: String

    public init(
        kind: String,
        id: String,
        drivePath: String,
        payload: Data,
        payloadSha256: String,
        updatedAt: String
    ) {
        self.kind = kind
        self.id = id
        self.drivePath = drivePath
        self.payload = payload
        self.payloadSha256 = payloadSha256
        self.updatedAt = updatedAt
    }
}

/// One page of dirty entries from `nextDirtyBatch`.
public struct DirtyBatch: Sendable, Equatable {
    public let entries: [DirtyEntry]

    /// nil → no more pages; otherwise pass back into the next call.
    public let nextCursor: SyncCursor?

    public init(entries: [DirtyEntry], nextCursor: SyncCursor?) {
        self.entries = entries
        self.nextCursor = nextCursor
    }
}

/// A single locally-recorded deletion ready to propagate as a
/// tombstone file under `tombstones/`.
public struct TombstoneEntry: Sendable, Equatable {
    public let kind: String
    public let id: String
    /// ISO-8601 UTC timestamp when the local deletion happened.
    public let deletedAt: String

    public init(kind: String, id: String, deletedAt: String) {
        self.kind = kind
        self.id = id
        self.deletedAt = deletedAt
    }
}

public struct TombstoneBatch: Sendable, Equatable {
    public let entries: [TombstoneEntry]
    public let nextCursor: SyncCursor?

    public init(entries: [TombstoneEntry], nextCursor: SyncCursor?) {
        self.entries = entries
        self.nextCursor = nextCursor
    }
}

/// Opaque pagination cursor. The data source defines its content; the
/// sync worker treats it as a black box and round-trips it verbatim.
public struct SyncCursor: Sendable, Equatable {
    public let opaque: String

    public init(_ opaque: String) {
        self.opaque = opaque
    }
}

// MARK: - Inbound types

/// A remote upsert pulled from Drive that the data source should apply
/// to the local store. The bytes are exactly what the uploader sent —
/// canonical JSON, sort-keyed, no whitespace differences.
public struct RemoteUpsert: Sendable, Equatable {
    public let kind: String
    public let id: String
    public let payload: Data

    /// `updated_at` parsed out of the manifest entry. Used by the data
    /// source for last-write-wins.
    public let updatedAt: String

    /// Drive `fileId` of the payload file. The data source stamps this
    /// onto the local row's `drive_file_id` column when it applies.
    public let driveFileId: String

    public init(
        kind: String,
        id: String,
        payload: Data,
        updatedAt: String,
        driveFileId: String
    ) {
        self.kind = kind
        self.id = id
        self.payload = payload
        self.updatedAt = updatedAt
        self.driveFileId = driveFileId
    }
}

/// A remote tombstone pulled from Drive. The data source soft-deletes
/// the matching local row (setting `deleted_at`, clearing `dirty`).
public struct RemoteTombstone: Sendable, Equatable {
    public let kind: String
    public let id: String
    public let deletedAt: String

    public init(kind: String, id: String, deletedAt: String) {
        self.kind = kind
        self.id = id
        self.deletedAt = deletedAt
    }
}

/// One row from a successful upload. The data source flips `dirty = 0`
/// for the matching `(kind, id, updatedAt)` tuple and stamps
/// `drive_file_id`.
public struct SyncAck: Sendable, Equatable {
    public let kind: String
    public let id: String

    /// The `updatedAt` value that was on the row when it was uploaded.
    /// The data source MUST only clear the dirty bit if the local row
    /// still has this `updatedAt` — otherwise it's been edited since
    /// the upload started and we shouldn't mark it clean.
    public let updatedAt: String

    /// Drive `fileId` returned by the upload. Stamped onto the local
    /// row's `drive_file_id` column.
    public let driveFileId: String

    public init(
        kind: String,
        id: String,
        updatedAt: String,
        driveFileId: String
    ) {
        self.kind = kind
        self.id = id
        self.updatedAt = updatedAt
        self.driveFileId = driveFileId
    }
}
