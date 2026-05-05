/*
 * SyncDataSource.kt
 *
 * The seam that lets `SyncRepository` (in this same module) serve both
 * Releaf and QuickInk without knowing either app's tables.
 *
 * Mirror of iOS's `SyncDataSource` Swift protocol — same shape, same
 * contract, same bytes-not-rows boundary. Each app implements this
 * interface against its own Room DAOs:
 *
 *   - Releaf  → ReleafSyncDataSource (apps/releaf/android/app/...)
 *   - QuickInk → QuickInkSyncDataSource (Phase 3, not yet present)
 *
 * The orchestrator hands the data source canonical-JSON bytes to apply
 * and asks for canonical-JSON bytes to upload. It never sees a column
 * name and never opens a database connection. That's what lets the two
 * apps keep diverging schemas while sharing the orchestrator.
 *
 * Why an interface, not an abstract class:
 *   - Plays nicer with Kotlin's `by` delegation if a future implementation
 *     wants to wrap another data source.
 *   - All operations are `suspend fun`s — no shared state across calls,
 *     no class invariants worth carrying.
 *
 * See docs/QUICKINK_DESIGN.md §1 for the full design rationale.
 */

@file:Suppress("unused") // public surface consumed across modules

package app.releaf.mobile.data.sync

interface SyncDataSource {

    // ─── Identity / configuration ──────────────────────────────────────

    /**
     * Top-level Drive folder name. Releaf returns `"Releaf"`, QuickInk
     * returns `"QuickInk"`. The sync repository concatenates this with
     * the manifest path it computes.
     */
    val driveRootFolderName: String

    /**
     * Schema version this app currently speaks. Embedded into the
     * manifest. A peer device that reads a manifest with a major
     * version higher than its own returns `versionBlocked = true` from
     * the sync pass — the user gets prompted to update the app.
     */
    val schemaVersion: SchemaVersion

    /**
     * Stable identifier for the app — `"releaf"` or `"quickink"`.
     * Tagged onto manifest entries so multi-app future debugging
     * (e.g. inspecting a Drive folder shared between sibling apps)
     * stays sane.
     */
    val appId: String

    // ─── Outbound: collect dirty rows ──────────────────────────────────

    /**
     * Snapshot the next chunk of locally-dirty entities the sync worker
     * should push this round. Implementations stream from each entity
     * kind's dirty index in `updated_at` order. The worker keeps
     * calling until it gets back a batch with `nextCursor == null`.
     *
     * Pagination is data-source-defined; the cursor is opaque to the
     * worker. A typical implementation packs `(entity_kind, updated_at,
     * id)` so each new call resumes after the last row of the previous
     * batch.
     *
     * `limit` is a hint — implementations may return fewer rows (e.g.
     * to keep Drive payload size bounded) but should not return more.
     */
    suspend fun nextDirtyBatch(after: SyncCursor?, limit: Int): DirtyBatch

    /**
     * Snapshot the next chunk of tombstones to propagate. Same paging
     * shape as [nextDirtyBatch] but the cursor namespace is independent.
     */
    suspend fun nextTombstoneBatch(after: SyncCursor?, limit: Int): TombstoneBatch

    // ─── Inbound: apply remote changes ─────────────────────────────────

    /**
     * Apply a remote upsert. The data source is responsible for:
     *   1. Decoding `change.payload` per its own canonical schema
     *      (the bytes are the same canonical JSON it emitted on the
     *      uploading side).
     *   2. Last-write-wins comparison against the local row by
     *      `updatedAt` — local wins ties.
     *   3. Clearing the local `dirty` bit if the remote write
     *      supersedes ours.
     *   4. Stamping `drive_file_id` from the manifest path if it's
     *      a new local row.
     *
     * Idempotent: if the local row is already at this version the
     * data source returns without writing.
     */
    suspend fun applyRemoteUpsert(change: RemoteUpsert)

    /**
     * Apply a remote tombstone. Soft-delete the local row matching
     * `(kind, id)` by setting `deleted_at = tombstone.deletedAt`,
     * `dirty = 0`. Idempotent.
     */
    suspend fun applyRemoteTombstone(tombstone: RemoteTombstone)

    // ─── Bookkeeping ───────────────────────────────────────────────────

    /**
     * Mark the given local rows as cleanly synced. Called by the worker
     * after a successful Drive upload, in batches.
     *
     * For each ack: set `dirty = 0` and stamp `drive_file_id` if newly
     * uploaded. The data source MUST do this race-safely — only flip
     * to `dirty = 0` if `updated_at` still matches the value reported
     * in the original [DirtyEntry] (otherwise the row was modified
     * between the read and the upload, and we shouldn't clear the bit).
     */
    suspend fun markSynced(acks: List<SyncAck>)

    /**
     * Most recent remote manifest etag the app has applied. Lets the
     * worker skip the full pull when Drive hasn't changed. Null on
     * first sync after install.
     */
    suspend fun lastAppliedManifestEtag(): String?

    /** Persist the etag of the manifest the worker just applied. */
    suspend fun setLastAppliedManifestEtag(etag: String)

    // ─── Drive-side cleanup ────────────────────────────────────────────

    /**
     * Identify entities in [manifest] whose parent isn't in the same
     * manifest's `entityChecksums` — i.e. orphans where the child JSON
     * file is still listed but the parent is gone. Used by
     * [SyncRepository.cleanupOrphans] to retire those entries on Drive.
     *
     * Typical example (QuickInk): an `ocr_result` whose `capture_id`
     * isn't in the manifest. Old builds soft-deleted captures without
     * cascading to children, so the next sync uploaded the parent
     * tombstone but left the child JSON in `entityChecksums` forever.
     * Every subsequent restore on any device sees the orphan and
     * either silently fails the FK insert (pre-PR-B/D) or harmlessly
     * skips it (post-PR-D). This hook lets us actually clean Drive
     * up.
     *
     * Default implementation returns an empty list — apps without
     * parent-child relationships in their synced kinds (or apps that
     * don't care about Drive-side cleanup) get no-op behaviour.
     */
    suspend fun findOrphanIds(manifest: ManifestV2): List<OrphanInfo> = emptyList()
}

/**
 * Pointer to a single orphan entity on Drive — the manifest's child
 * record whose parent isn't in the same manifest. Returned by
 * [SyncDataSource.findOrphanIds] and consumed by
 * [SyncRepository.cleanupOrphans].
 */
data class OrphanInfo(
    /** The orphan entity's id (UUIDv7). */
    val id: String,
    /** The orphan's kind — e.g. `DrivePath.KIND_OCR_RESULT`. */
    val kind: String,
    /**
     * The id of the parent that's missing from the manifest.
     * Logged for diagnostics; not strictly needed by the cleanup
     * path itself.
     */
    val missingParentId: String,
)

// ─── Outbound types ────────────────────────────────────────────────────

/**
 * A single locally-dirty entity ready to upload. The data source did
 * the canonical-JSON serialisation; the sync worker just moves bytes.
 */
data class DirtyEntry(
    /**
     * Stable, app-defined kind tag. Examples: `"notebook"`, `"chapter"`,
     * `"page"`, `"notepad_entry"`, `"capture"`, `"task"`, `"reminder"`,
     * `"ocr_result"`. Used as the manifest bucket and as the routing
     * key for inbound upserts on the receiving side.
     */
    val kind: String,

    /** UUIDv7. Stable across devices. NOT Drive's `fileId`. */
    val id: String,

    /**
     * Path under the Drive root where this entity's bytes land.
     * Computed by the data source per the per-kind layout in
     * docs/DRIVE_SCHEMA.md.
     */
    val drivePath: String,

    /**
     * Canonical-JSON bytes. Sort-keyed, no extraneous whitespace —
     * the same bytes [payloadSha256] was computed over.
     */
    val payload: ByteArray,

    /** SHA-256 of [payload], hex-encoded. */
    val payloadSha256: String,

    /** `updated_at` from the source row, ISO-8601 UTC with ms. */
    val updatedAt: String,
) {
    // Data class equality over ByteArray uses reference equality by default;
    // override for value semantics.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DirtyEntry) return false
        return kind == other.kind &&
            id == other.id &&
            drivePath == other.drivePath &&
            payload.contentEquals(other.payload) &&
            payloadSha256 == other.payloadSha256 &&
            updatedAt == other.updatedAt
    }

    override fun hashCode(): Int {
        var result = kind.hashCode()
        result = 31 * result + id.hashCode()
        result = 31 * result + drivePath.hashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + payloadSha256.hashCode()
        result = 31 * result + updatedAt.hashCode()
        return result
    }
}

/** One page of dirty entries from [SyncDataSource.nextDirtyBatch]. */
data class DirtyBatch(
    val entries: List<DirtyEntry>,
    /** null → no more pages; otherwise pass back into the next call. */
    val nextCursor: SyncCursor?,
)

/**
 * A single locally-recorded deletion ready to propagate as a tombstone
 * file under `tombstones/`. Distinct from [TombstoneEntry] (in
 * Manifest.kt) — that one is the Drive wire format where the entity id
 * is the dictionary key. This is the data source's outbound batch
 * element, where the id is a field on the value because we're returning
 * a list, not a map.
 */
data class PendingTombstone(
    val kind: String,
    val id: String,
    /** ISO-8601 UTC timestamp when the local deletion happened. */
    val deletedAt: String,
)

data class TombstoneBatch(
    val entries: List<PendingTombstone>,
    val nextCursor: SyncCursor?,
)

/**
 * Opaque pagination cursor. The data source defines its content; the
 * sync worker treats it as a black box and round-trips it verbatim.
 */
@JvmInline
value class SyncCursor(val opaque: String)

// ─── Inbound types ─────────────────────────────────────────────────────

/**
 * A remote upsert pulled from Drive that the data source should apply
 * to the local store. The bytes are exactly what the uploader sent —
 * canonical JSON, sort-keyed, no whitespace differences.
 */
data class RemoteUpsert(
    val kind: String,
    val id: String,
    val payload: ByteArray,
    /** `updated_at` parsed out of the manifest entry. */
    val updatedAt: String,
    /** Drive `fileId` of the payload file. Stamped onto the local row. */
    val driveFileId: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RemoteUpsert) return false
        return kind == other.kind &&
            id == other.id &&
            payload.contentEquals(other.payload) &&
            updatedAt == other.updatedAt &&
            driveFileId == other.driveFileId
    }

    override fun hashCode(): Int {
        var result = kind.hashCode()
        result = 31 * result + id.hashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + updatedAt.hashCode()
        result = 31 * result + driveFileId.hashCode()
        return result
    }
}

/**
 * A remote tombstone pulled from Drive. The data source soft-deletes
 * the matching local row.
 */
data class RemoteTombstone(
    val kind: String,
    val id: String,
    val deletedAt: String,
)

/**
 * One row from a successful upload. The data source flips `dirty = 0`
 * for the matching `(kind, id, updatedAt)` tuple and stamps
 * `drive_file_id`.
 */
data class SyncAck(
    val kind: String,
    val id: String,
    /**
     * The `updatedAt` value that was on the row when it was uploaded.
     * The data source MUST only clear the dirty bit if the local row
     * still has this `updatedAt` — otherwise it's been edited since
     * the upload started.
     */
    val updatedAt: String,
    /** Drive `fileId` returned by the upload. */
    val driveFileId: String,
)
