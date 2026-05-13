/*
 * QuickInkSyncDataSource.kt
 *
 * QuickInk's implementation of `:shared:sync`'s `SyncDataSource`
 * interface. Mirror of Releaf's `ReleafSyncDataSource.kt` but for
 * QuickInk's three entity kinds: notepad_entries, captures,
 * ocr_results.
 *
 * Pagination policy: same as Releaf's — single-batch,
 * `nextCursor = null`. Paginated implementations would land here
 * when a real QuickInk user accumulates enough dirty rows that one
 * Drive payload exceeds ~4 MB.
 *
 * The OCR-blocks JSON encoding uses the round-trip in
 * `QuickInkSyncPayloads`: rows store `blocks_json` as a string
 * column, payloads carry it as a first-class JSON array on the
 * wire. Cross-app readers see the OCR blocks structurally without
 * a string-unwrap step.
 *
 * `lastAppliedManifestEtag` is a v1 no-op (returns null, set is
 * also no-op) — matches Releaf's stance. v2 etag-skip tracking
 * lands when the protocol does.
 *
 * See:
 *   - shared/android/shared/sync/.../SyncDataSource.kt — the protocol
 *   - apps/releaf/.../sync/ReleafSyncDataSource.kt — reference impl
 *   - QUICKINK_PROPOSAL.md §1 — design rationale
 */

package app.quickink.mobile.data.sync

import app.quickink.mobile.data.capture.CaptureDao
import app.quickink.mobile.data.capturetag.CaptureTagDao
import app.quickink.mobile.data.folder.FolderDao
import app.quickink.mobile.data.tag.TagDao
import app.quickink.mobile.data.ocr.OcrResultDao
import app.quickink.mobile.data.smartcollection.SmartCollectionDao
import app.releaf.mobile.data.notepad.NotepadDao
import app.releaf.mobile.data.sync.CanonicalJson
import app.releaf.mobile.data.sync.sha256Hex
import app.releaf.mobile.data.sync.DirtyBatch
import app.releaf.mobile.data.sync.DirtyEntry
import app.releaf.mobile.data.sync.DrivePath
import app.releaf.mobile.data.sync.ManifestV2
import app.releaf.mobile.data.sync.OrphanInfo
import app.releaf.mobile.data.sync.PendingTombstone
import app.releaf.mobile.data.sync.RemoteTombstone
import app.releaf.mobile.data.sync.RemoteUpsert
import app.releaf.mobile.data.sync.SchemaVersion
import app.releaf.mobile.data.sync.SyncAck
import app.releaf.mobile.data.sync.SyncCursor
import app.releaf.mobile.data.sync.SyncDataSource
import app.releaf.mobile.data.sync.SyncJson
import app.releaf.mobile.data.sync.TombstoneBatch
import kotlinx.serialization.json.Json

class QuickInkSyncDataSource(
    private val notepadDao: NotepadDao,
    private val captureDao: CaptureDao,
    private val ocrResultDao: OcrResultDao,
    private val tagDao: TagDao,
    private val profileSettingsDao: app.quickink.mobile.data.profile.ProfileSettingsDao,
    // ─── Workspace v1 (Phase A.3b) ───────────────────────────────
    private val folderDao: FolderDao,
    private val captureTagDao: CaptureTagDao,
    private val smartCollectionDao: SmartCollectionDao,
    private val userId: String,
    private val json: Json = SyncJson,
) : SyncDataSource {

    // ─── Identity ──────────────────────────────────────────────────────

    /**
     * Drive folder layout — `Thoughtbasics/QuickInk/...`. The
     * "Thoughtbasics" wrapper holds future thoughts-line apps; the
     * QuickInk subfolder is the actual app root that all paths
     * (captures, ocr_results, notepad_entries, categories,
     * tombstones, manifest.json) hang off. `ensureRootFolder` on
     * the Drive client walks slash-separated paths automatically.
     */
    override val driveRootFolderName: String = "Thoughtbasics/QuickInk"

    override val schemaVersion: SchemaVersion = SchemaVersion.CURRENT

    override val appId: String = "quickink"

    // ─── Outbound: collect dirty rows ──────────────────────────────────

    override suspend fun nextDirtyBatch(after: SyncCursor?, limit: Int): DirtyBatch {
        // Single-batch implementation — see file header.
        if (after != null) return DirtyBatch(emptyList(), nextCursor = null)

        val entries = mutableListOf<DirtyEntry>()

        // ---- notepad entries (user-scoped) ----
        val notepadRows = (notepadDao.activeRows(userId) + notepadDao.dirtyRows()
            .filter { it.deletedAt == null && it.userId == userId })
            .distinctBy { it.id }
        for (row in notepadRows) {
            val payload = row.toV2Payload()
            val elem = json.encodeToJsonElement(NotepadEntryPayloadV2.serializer(), payload)
            val bytes = CanonicalJson.encodeToBytes(elem)
            entries += DirtyEntry(
                kind          = DrivePath.KIND_NOTEPAD_ENTRY,
                id            = row.id,
                drivePath     = DrivePath.notepadEntry(row.entryDate, row.id),
                payload       = bytes,
                payloadSha256 = sha256Hex(bytes),
                updatedAt     = row.updatedAt,
            )
        }

        // ---- captures (user-scoped) ----
        val captureRows = (captureDao.activeRows(userId) + captureDao.dirtyRows()
            .filter { it.deletedAt == null && it.userId == userId })
            .distinctBy { it.id }
        for (row in captureRows) {
            val payload = row.toV2Payload()
            val elem = json.encodeToJsonElement(CapturePayloadV2.serializer(), payload)
            val bytes = CanonicalJson.encodeToBytes(elem)
            entries += DirtyEntry(
                kind          = DrivePath.KIND_CAPTURE,
                id            = row.id,
                drivePath     = DrivePath.quickInkCapture(row.createdAt, row.id),
                payload       = bytes,
                payloadSha256 = sha256Hex(bytes),
                updatedAt     = row.updatedAt,
            )
        }

        // ---- tags (user-scoped). New writes live under `tags/`
        // (DrivePath.tag); legacy reads from `categories/` keep
        // working because the manifest stores the per-row path —
        // pulled rows go through the same KIND_CATEGORY apply
        // branch regardless of folder. Wire kind string stays
        // "category" for interop with clients on older builds
        // during the rollout window. A cleanup pass that deletes
        // the orphaned legacy files lands in a follow-up commit
        // after the brief's two-week soak window.
        for (row in tagDao.dirtyRows()
            .filter { it.deletedAt == null && it.userId == userId }) {
            val payload = row.toV1Payload()
            val elem = json.encodeToJsonElement(TagPayloadV1.serializer(), payload)
            val bytes = CanonicalJson.encodeToBytes(elem)
            entries += DirtyEntry(
                kind          = DrivePath.KIND_CATEGORY,
                id            = row.id,
                drivePath     = DrivePath.tag(row.id),
                payload       = bytes,
                payloadSha256 = sha256Hex(bytes),
                updatedAt     = row.updatedAt,
            )
        }

        // ---- folders (user-scoped, Workspace v1) ----
        for (row in folderDao.dirtyRows()
            .filter { it.deletedAt == null && it.userId == userId }) {
            val payload = row.toV1Payload()
            val elem = json.encodeToJsonElement(FolderPayloadV1.serializer(), payload)
            val bytes = CanonicalJson.encodeToBytes(elem)
            entries += DirtyEntry(
                kind          = DrivePath.KIND_FOLDER,
                id            = row.id,
                drivePath     = DrivePath.folder(row.id),
                payload       = bytes,
                payloadSha256 = sha256Hex(bytes),
                updatedAt     = row.updatedAt,
            )
        }

        // ---- capture_tags (no user_id column; FK to captures
        //      handles ownership. Filter via the captures table). ----
        val captureTagRows = captureTagDao.dirtyRows()
            .filter { it.deletedAt == null }
        for (row in captureTagRows) {
            val payload = row.toV1Payload()
            val elem = json.encodeToJsonElement(CaptureTagPayloadV1.serializer(), payload)
            val bytes = CanonicalJson.encodeToBytes(elem)
            entries += DirtyEntry(
                kind          = DrivePath.KIND_CAPTURE_TAG,
                id            = row.id,
                drivePath     = DrivePath.captureTag(row.id),
                payload       = bytes,
                payloadSha256 = sha256Hex(bytes),
                updatedAt     = row.updatedAt,
            )
        }

        // ---- smart_collections (user-scoped, Workspace v1) ----
        for (row in smartCollectionDao.dirtyRows()
            .filter { it.deletedAt == null && it.userId == userId }) {
            val payload = row.toV1Payload()
            val elem = json.encodeToJsonElement(SmartCollectionPayloadV1.serializer(), payload)
            val bytes = CanonicalJson.encodeToBytes(elem)
            entries += DirtyEntry(
                kind          = DrivePath.KIND_SMART_COLLECTION,
                id            = row.id,
                drivePath     = DrivePath.smartCollection(row.id),
                payload       = bytes,
                payloadSha256 = sha256Hex(bytes),
                updatedAt     = row.updatedAt,
            )
        }

        // ---- profile_settings (user-scoped, single row per user) ----
        for (row in profileSettingsDao.dirtyRows()
            .filter { it.deletedAt == null && it.userId == userId }) {
            val payload = row.toV1Payload()
            val elem = json.encodeToJsonElement(ProfileSettingsPayloadV1.serializer(), payload)
            val bytes = CanonicalJson.encodeToBytes(elem)
            entries += DirtyEntry(
                kind          = DrivePath.KIND_PROFILE_SETTINGS,
                id            = row.id,
                drivePath     = DrivePath.profileSettings(row.id),
                payload       = bytes,
                payloadSha256 = sha256Hex(bytes),
                updatedAt     = row.updatedAt,
            )
        }

        // ---- ocr_results (no direct user scoping; FK to captures
        //      handles cascade. For multi-user-per-install futures,
        //      filter by joining against captures.user_id).
        val ocrRows = (ocrResultDao.activeRows() + ocrResultDao.dirtyRows()
            .filter { it.deletedAt == null })
            .distinctBy { it.id }
        for (row in ocrRows) {
            val payload = row.toV2Payload()
            val elem = json.encodeToJsonElement(OcrResultPayloadV2.serializer(), payload)
            val bytes = CanonicalJson.encodeToBytes(elem)
            entries += DirtyEntry(
                kind          = DrivePath.KIND_OCR_RESULT,
                id            = row.id,
                drivePath     = DrivePath.quickInkOcrResult(
                    createdAt = row.createdAt,
                    captureId = row.captureId,
                    pageIndex = row.pageIndex,
                ),
                payload       = bytes,
                payloadSha256 = sha256Hex(bytes),
                updatedAt     = row.updatedAt,
            )
        }

        return DirtyBatch(entries, nextCursor = null)
    }

    override suspend fun nextTombstoneBatch(after: SyncCursor?, limit: Int): TombstoneBatch {
        if (after != null) return TombstoneBatch(emptyList(), nextCursor = null)

        val entries = mutableListOf<PendingTombstone>()

        for (row in notepadDao.dirtyRows().filter { it.deletedAt != null && it.userId == userId }) {
            entries += PendingTombstone(
                kind      = DrivePath.KIND_NOTEPAD_ENTRY,
                id        = row.id,
                deletedAt = row.deletedAt ?: row.updatedAt,
            )
        }
        for (row in captureDao.dirtyRows().filter { it.deletedAt != null && it.userId == userId }) {
            entries += PendingTombstone(
                kind      = DrivePath.KIND_CAPTURE,
                id        = row.id,
                deletedAt = row.deletedAt ?: row.updatedAt,
            )
        }
        for (row in tagDao.dirtyRows().filter { it.deletedAt != null && it.userId == userId }) {
            entries += PendingTombstone(
                kind      = DrivePath.KIND_CATEGORY,
                id        = row.id,
                deletedAt = row.deletedAt ?: row.updatedAt,
            )
        }
        for (row in profileSettingsDao.dirtyRows().filter { it.deletedAt != null && it.userId == userId }) {
            entries += PendingTombstone(
                kind      = DrivePath.KIND_PROFILE_SETTINGS,
                id        = row.id,
                deletedAt = row.deletedAt ?: row.updatedAt,
            )
        }
        for (row in ocrResultDao.dirtyRows().filter { it.deletedAt != null }) {
            entries += PendingTombstone(
                kind      = DrivePath.KIND_OCR_RESULT,
                id        = row.id,
                deletedAt = row.deletedAt ?: row.updatedAt,
            )
        }
        for (row in folderDao.dirtyRows().filter { it.deletedAt != null && it.userId == userId }) {
            entries += PendingTombstone(
                kind      = DrivePath.KIND_FOLDER,
                id        = row.id,
                deletedAt = row.deletedAt ?: row.updatedAt,
            )
        }
        for (row in captureTagDao.dirtyRows().filter { it.deletedAt != null }) {
            entries += PendingTombstone(
                kind      = DrivePath.KIND_CAPTURE_TAG,
                id        = row.id,
                deletedAt = row.deletedAt ?: row.updatedAt,
            )
        }
        for (row in smartCollectionDao.dirtyRows().filter { it.deletedAt != null && it.userId == userId }) {
            entries += PendingTombstone(
                kind      = DrivePath.KIND_SMART_COLLECTION,
                id        = row.id,
                deletedAt = row.deletedAt ?: row.updatedAt,
            )
        }

        return TombstoneBatch(entries, nextCursor = null)
    }

    // ─── Inbound: apply remote changes ─────────────────────────────────

    override suspend fun applyRemoteUpsert(change: RemoteUpsert) {
        val text = change.payload.toString(Charsets.UTF_8)
        val driveFileId = change.driveFileId.takeIf { it.isNotEmpty() }
        when (change.kind) {
            DrivePath.KIND_NOTEPAD_ENTRY -> {
                val p = json.decodeFromString(NotepadEntryPayloadV2.serializer(), text)
                // upsertFromRemote (not upsert): inserts if missing,
                // updates only when remote.updated_at > local.updated_at,
                // and uses real UPDATE under the hood instead of INSERT
                // OR REPLACE. Protects local edits made while a slow
                // restore is in flight from being silently clobbered.
                notepadDao.upsertFromRemote(p.toEntity(driveFileId = driveFileId))
            }
            DrivePath.KIND_CAPTURE -> {
                val p = json.decodeFromString(CapturePayloadV2.serializer(), text)
                // CRITICAL: `pdf_uri` and `preview_uri` are
                // device-local file:// paths. The remote payload
                // carries the SOURCE device's path, which is
                // meaningless on this device. Naively replacing the
                // row with the remote URIs is what produced the
                // "open failed: ENOENT" symptom for cross-device
                // synced captures.
                //
                // Reconcile:
                //   - If we already have this capture locally with
                //     a working file://, keep our URIs (the local
                //     binary is correct; the remote payload's URIs
                //     are someone else's device's paths).
                //   - Otherwise (new capture, or our local file is
                //     gone), accept the remote payload but blank
                //     out URIs that won't resolve here. The next
                //     restorePending pass / on-demand open will
                //     download from Drive using `pdfDriveFileId`.
                val existing = captureDao.findById(p.id)
                val resolvedPdfUri: String = when {
                    existing != null && fileExistsAt(existing.pdfUri) -> existing.pdfUri
                    p.pdfDriveFileId != null -> "" // wait for restorePending
                    else -> p.pdfUri // first device with this capture, accept verbatim
                }
                val resolvedPreviewUri: String? = when {
                    existing != null && existing.previewUri != null && fileExistsAt(existing.previewUri) -> existing.previewUri
                    p.previewDriveFileId != null -> null
                    else -> p.previewUri
                }
                // upsertFromRemote: see notepad branch above for why.
                // The cascade-delete trap was particularly bad here —
                // captures has child ocr_results with ON DELETE CASCADE,
                // so the old `insert(REPLACE)` path briefly nuked every
                // OCR row on every restore touch.
                captureDao.upsertFromRemote(
                    p.toEntity(driveFileId = driveFileId).copy(
                        pdfUri     = resolvedPdfUri,
                        previewUri = resolvedPreviewUri,
                    )
                )
            }
            DrivePath.KIND_OCR_RESULT -> {
                val p = json.decodeFromString(OcrResultPayloadV2.serializer(), text)
                // Defensive parent-existence check.
                //
                // `ocr_results.capture_id` is a FK to `captures.id`
                // with ON DELETE CASCADE. Drive sometimes holds
                // ORPHAN ocr_result entries — the parent capture was
                // deleted (or never uploaded), but the child ocr
                // JSON file is still there because tombstone
                // cascading isn't wired into the push path yet.
                // When pullDelta tries to apply such a row, Room
                // raises SQLITE_CONSTRAINT (FOREIGN KEY constraint
                // failed) and the catch in pullDelta logs it as an
                // applyFailed.
                //
                // Skipping cleanly here turns those into a quieter
                // "orphan, skipping" log line and lets the rest of
                // the restore proceed without false-alarm error
                // counts. The orphans on Drive aren't fixed by this
                // — that needs a Drive-side cleanup pass — but the
                // local restore stops misreporting them as failures.
                val parentExists = captureDao.findById(p.captureId) != null
                if (!parentExists) {
                    android.util.Log.w(
                        "QuickInkSync",
                        "applyRemoteUpsert: skipping orphan ocr_result " +
                            "id=${p.id} capture_id=${p.captureId} (parent " +
                            "capture not in local DB — likely a Drive " +
                            "data-hygiene gap where the parent capture " +
                            "was deleted but its ocr_result JSON wasn't " +
                            "tombstoned)."
                    )
                    return
                }
                // upsertFromRemote: same rationale as the other branches.
                ocrResultDao.upsertFromRemote(p.toEntity(driveFileId = driveFileId))
            }
            DrivePath.KIND_CATEGORY -> {
                val p = json.decodeFromString(TagPayloadV1.serializer(), text)
                // upsertFromRemote (not insert): the existing `insert`
                // uses OnConflictStrategy.IGNORE — meant for the
                // user-facing "rename collision = no-op" path, not for
                // the sync path. Without this fix, restore literally
                // could not update existing categories from remote;
                // a name change on another device would silently fail
                // to land here.
                tagDao.upsertFromRemote(p.toEntity(driveFileId = driveFileId))
            }
            DrivePath.KIND_PROFILE_SETTINGS -> {
                val p = json.decodeFromString(ProfileSettingsPayloadV1.serializer(), text)
                // Same upsert-from-remote pattern as Category. The
                // photo binary itself doesn't ride this payload —
                // QuickInkBinarySync downloads it separately keyed
                // off the row's `photo_drive_file_id` once Phase 1
                // metadata is applied.
                profileSettingsDao.upsertFromRemote(p.toEntity(driveFileId = driveFileId))
            }
            DrivePath.KIND_FOLDER -> {
                val p = json.decodeFromString(FolderPayloadV1.serializer(), text)
                folderDao.upsertFromRemote(p.toEntity(driveFileId = driveFileId))
            }
            DrivePath.KIND_CAPTURE_TAG -> {
                val p = json.decodeFromString(CaptureTagPayloadV1.serializer(), text)
                captureTagDao.upsertFromRemote(p.toEntity(driveFileId = driveFileId))
            }
            DrivePath.KIND_SMART_COLLECTION -> {
                val p = json.decodeFromString(SmartCollectionPayloadV1.serializer(), text)
                smartCollectionDao.upsertFromRemote(p.toEntity(driveFileId = driveFileId))
            }
            else -> {
                // Forward-compat: kind we don't recognize, skip.
            }
        }
    }

    override suspend fun applyRemoteTombstone(tombstone: RemoteTombstone) {
        val nowIso = tombstone.deletedAt
        when (tombstone.kind) {
            DrivePath.KIND_NOTEPAD_ENTRY -> notepadDao.softDelete(tombstone.id, nowIso)
            // markChildrenDirty = false: Drive already has the
            // parent tombstone (that's how we got here) and either
            // has child tombstones too OR will get them via the
            // orphan-cleanup pass on the next restore. If we marked
            // the children dirty=1 here, the foreground pending-push
            // tick would see them within 60s, surface a false-alarm
            // "pending to sync" banner on Home, and push redundant
            // tombstones back to Drive that don't change anything.
            // See `CaptureDao.softDelete`'s doc for the user-vs-
            // remote split rationale.
            DrivePath.KIND_CAPTURE       -> captureDao.softDelete(
                id                = tombstone.id,
                timestamp         = nowIso,
                markChildrenDirty = false,
            )
            // Phase 4 follow-up — `ocrResultDao.softDelete` lands
            // alongside the rest of the DAO sync surface so the
            // KIND_OCR_RESULT remote-tombstone branch isn't a
            // no-op anymore. In v1, the FK `captures(id) ON
            // DELETE CASCADE` plus Drive's tombstone-by-id model
            // means typical remote→local deletes still flow as
            // "capture tombstoned → SQLite cascade deletes
            // children", so this row-level path is rare. Wiring
            // it now anyway keeps the data source's tombstone
            // handling complete for the search-from-trash / undo
            // flows that may surface single-page tombstones later.
            DrivePath.KIND_OCR_RESULT    -> ocrResultDao.softDelete(tombstone.id, nowIso)
            DrivePath.KIND_CATEGORY      -> tagDao.softDelete(tombstone.id, nowIso)
            DrivePath.KIND_PROFILE_SETTINGS -> {
                // Profile-settings tombstones are exotic — a user
                // would only see one if their account was wiped on
                // another device. Soft-delete the row; the next sync
                // pass acks it. UI treats null/absent profile_settings
                // row the same as "fresh user, no overrides yet".
                val existing = profileSettingsDao.findById(tombstone.id) ?: return
                profileSettingsDao.upsertFromRemote(
                    existing.copy(deletedAt = nowIso, updatedAt = nowIso, dirty = false)
                )
            }
            DrivePath.KIND_FOLDER -> folderDao.softDelete(tombstone.id, nowIso)
            DrivePath.KIND_CAPTURE_TAG -> captureTagDao.softDeleteById(tombstone.id, nowIso)
            DrivePath.KIND_SMART_COLLECTION -> smartCollectionDao.softDelete(tombstone.id, nowIso)
        }
    }

    // ─── Bookkeeping ───────────────────────────────────────────────────

    override suspend fun markSynced(acks: List<SyncAck>) {
        // Same dual-call pattern as ReleafSyncDataSource — each DAO
        // method has a WHERE clause that matches only the right row
        // state, so calling both is safe and at most one mutates.
        for (ack in acks) {
            when (ack.kind) {
                DrivePath.KIND_NOTEPAD_ENTRY -> {
                    notepadDao.markSynced(ack.id, ack.driveFileId, ack.updatedAt)
                    notepadDao.markTombstoneSynced(ack.id)
                }
                DrivePath.KIND_CAPTURE -> {
                    captureDao.markSynced(ack.id, ack.driveFileId, ack.updatedAt)
                    captureDao.markTombstoneSynced(ack.id)
                }
                DrivePath.KIND_OCR_RESULT -> {
                    ocrResultDao.markSynced(ack.id, ack.driveFileId, ack.updatedAt)
                    ocrResultDao.markTombstoneSynced(ack.id)
                }
                DrivePath.KIND_CATEGORY -> {
                    tagDao.markSynced(ack.id, ack.driveFileId, ack.updatedAt)
                    tagDao.markTombstoneSynced(ack.id)
                }
                DrivePath.KIND_PROFILE_SETTINGS -> {
                    profileSettingsDao.markSynced(ack.id, ack.driveFileId, ack.updatedAt)
                    profileSettingsDao.markTombstoneSynced(ack.id)
                }
                DrivePath.KIND_FOLDER -> {
                    folderDao.markSynced(ack.id, ack.driveFileId, ack.updatedAt)
                    folderDao.markTombstoneSynced(ack.id)
                }
                DrivePath.KIND_CAPTURE_TAG -> {
                    captureTagDao.markSynced(ack.id, ack.driveFileId, ack.updatedAt)
                    captureTagDao.markTombstoneSynced(ack.id)
                }
                DrivePath.KIND_SMART_COLLECTION -> {
                    smartCollectionDao.markSynced(ack.id, ack.driveFileId, ack.updatedAt)
                    smartCollectionDao.markTombstoneSynced(ack.id)
                }
                // else: forward-compat — unknown kind, skip silently.
            }
        }
    }

    override suspend fun lastAppliedManifestEtag(): String? {
        // Etag-based skip-pull is a v2 optimization. v1 always pulls.
        // Same as Releaf's stance.
        return null
    }

    override suspend fun setLastAppliedManifestEtag(etag: String) {
        // No-op until v2 etag tracking lands.
    }

    /**
     * Find ocr_result entries in [manifest] whose parent capture
     * isn't in the same manifest's `entityChecksums`. These are the
     * data-hygiene orphans created by older builds that soft-deleted
     * captures without cascading to children — fixed going forward
     * by [CaptureDao.softDelete]'s cascade (PR-E parts 1+2), but the
     * historical orphans need explicit retirement.
     *
     * Path-based lookup: ocr_result Drive paths are
     * `ocr/<captureId>/page-<N>.json` (see `DrivePath.quickInkOcrResult`).
     * Pulling captureId out of the path is cheaper than downloading
     * each ocr_result payload to parse it from JSON.
     *
     * Returns an empty list when the manifest is consistent — the
     * common path on every run after the first cleanup completes.
     */
    override suspend fun findOrphanIds(manifest: ManifestV2): List<OrphanInfo> {
        // Build a set of capture ids actually present in the
        // manifest. Membership lookups are O(1) so the overall scan
        // is O(N) over manifest entries.
        val capturesInManifest: Set<String> = manifest.entityChecksums.asSequence()
            .filter { it.value.kind == DrivePath.KIND_CAPTURE }
            .map { it.key }
            .toHashSet()

        val orphans = mutableListOf<OrphanInfo>()
        for ((id, checksum) in manifest.entityChecksums) {
            if (checksum.kind != DrivePath.KIND_OCR_RESULT) continue
            val captureId = parseOcrCaptureIdFromPath(checksum.path) ?: continue
            if (captureId !in capturesInManifest) {
                orphans += OrphanInfo(
                    id              = id,
                    kind            = DrivePath.KIND_OCR_RESULT,
                    missingParentId = captureId,
                )
            }
        }
        return orphans
    }

    /**
     * Extract the parent capture id from an OCR-result Drive path.
     * Returns null on any path that doesn't look like a recognised
     * OCR-result file — unrecognised entries are skipped (treated as
     * "not an orphan we can identify") rather than producing a false-
     * positive cleanup.
     *
     * Two path shapes are accepted (both produced by [DrivePath]
     * helpers — see `quickInkOcrResult` for the current shape and
     * `ocrResult` for the legacy shape):
     *   - Date-bucketed: `<yyyy>/<mm>/<dd>/<captureId>/page-<N>.json`
     *     (5 segments). This is the shape today's data source emits
     *     for new dirty pushes.
     *   - Flat / legacy: `ocr/<captureId>/page-<N>.json` (3 segments).
     *     Older builds may have written some entries here.
     *
     * The capture id is always the second-to-last segment in both
     * shapes — the directory directly containing the `page-{N}.json`
     * file. Pulling it from there sidesteps having to special-case
     * the date prefix and survives any future path tweak that keeps
     * the same captureId-as-parent-dir convention.
     */
    private fun parseOcrCaptureIdFromPath(path: String): String? {
        val segments = path.split('/').filter { it.isNotEmpty() }
        // Need at least `<captureId>/<file>` — two segments — for the
        // captureId to be addressable.
        if (segments.size < 2) return null
        // File must look like `page-{N}.json` so we don't grab a
        // captureId from an unrelated path that happened to land
        // under a folder we don't recognise.
        val file = segments.last()
        if (!file.startsWith("page-") || !file.endsWith(".json")) return null
        return segments[segments.size - 2].takeIf { it.isNotEmpty() }
    }

    /**
     * Best-effort "does the file://… or content://… backing this URI
     * actually resolve here?" check. Used by the cross-device URI
     * reconciliation in [applyRemoteUpsert] for KIND_CAPTURE so we
     * keep a healthy local pdf/preview path instead of overwriting
     * it with the source device's (meaningless on this device) path.
     *
     * No `Context` reference is held by this data source, so we
     * route content:// URIs through `java.io.File` only — that's
     * the path AttachmentStorage produces (`file:///…/attachments/<UUID>.<ext>`).
     * Returns false on any error so reconciliation defaults to
     * "blank out and let restorePending fix it".
     */
    private fun fileExistsAt(uri: String?): Boolean {
        if (uri.isNullOrBlank()) return false
        val parsed = runCatching { android.net.Uri.parse(uri) }.getOrNull() ?: return false
        return runCatching {
            when (parsed.scheme) {
                "file" -> parsed.path?.let { java.io.File(it).exists() } ?: false
                null   -> java.io.File(uri).exists()
                else   -> false // content:// / others — be conservative, treat as missing
            }
        }.getOrDefault(false)
    }
}

