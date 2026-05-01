/*
 * Manifest.kt
 *
 * v2 manifest wire format per `docs/DRIVE_SCHEMA.md` §`manifest.json`.
 * Replaces the v1 count-only manifest that shipped in an earlier phase.
 *
 * The manifest is the single source of truth for "what exists on Drive
 * and what its current checksum is." Sync compares local row hashes
 * against `entity_checksums[id].sha256` to decide what to upload/pull;
 * deletes propagate via `tombstones[id]`.
 *
 * Ordering invariant: the manifest is the last thing the sync worker
 * writes in a pass. Every payload / media blob / tombstone file is
 * durable on Drive before we commit the manifest. A crash mid-pass
 * leaves blobs durable but the manifest unchanged, so the next pass
 * retries cleanly.
 */

package app.releaf.mobile.data.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Current schema version emitted by this build. Bump per policy in
 *  `docs/DRIVE_SCHEMA.md` §"Version bump policy" and `OPEN_QUESTIONS §5`. */
object SchemaVersionConstants {
    const val MAJOR = 2
    const val MINOR = 0

    /** Migration counter — highest `vN_*.sql` the local DB has applied. */
    const val MIGRATION_VERSION = 1
}

@Serializable
data class SchemaVersion(
    @SerialName("major") val major: Int,
    @SerialName("minor") val minor: Int,
) {
    companion object {
        val CURRENT = SchemaVersion(SchemaVersionConstants.MAJOR, SchemaVersionConstants.MINOR)
    }
}

@Serializable
data class EntityChecksum(
    /** One of the `DrivePath.KIND_*` constants. */
    @SerialName("kind")       val kind: String,
    /** Drive path relative to `Releaf/` — deterministic per `DrivePath`. */
    @SerialName("path")       val path: String,
    /** Hex-lowercase SHA-256 of the canonical-JSON-serialized payload. */
    @SerialName("sha256")     val sha256: String,
    /** ISO-8601 UTC of the entity's `updated_at` when this checksum was taken. */
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class TombstoneEntry(
    @SerialName("kind")          val kind: String,
    @SerialName("deleted_at")    val deletedAt: String,
    @SerialName("device_id")     val deviceId: String,
    @SerialName("hard_delete_at") val hardDeleteAt: String? = null,
)

@Serializable
data class ManifestV2(
    @SerialName("schema_version")        val schemaVersion: SchemaVersion = SchemaVersion.CURRENT,
    @SerialName("migration_version")     val migrationVersion: Int       = SchemaVersionConstants.MIGRATION_VERSION,
    @SerialName("app_version")           val appVersion: String,
    @SerialName("device_id")             val deviceId: String,
    @SerialName("last_sync_at")          val lastSyncAt: String,
    @SerialName("client_generated_at")   val clientGeneratedAt: String,
    @SerialName("entity_checksums")      val entityChecksums: Map<String, EntityChecksum> = emptyMap(),
    @SerialName("tombstones")            val tombstones: Map<String, TombstoneEntry>      = emptyMap(),
)

/**
 * Tombstone payload (`tombstones/{id}.json`). Distinct from the
 * manifest's inline `tombstones[id]` entry — the file carries the
 * full record for any client that lost its manifest and needs to
 * reconstruct from the folder tree.
 */
@Serializable
data class TombstoneFile(
    @SerialName("id")              val id: String,
    @SerialName("kind")             val kind: String,
    @SerialName("deleted_at")       val deletedAt: String,
    @SerialName("device_id")        val deviceId: String,
    @SerialName("hard_delete_at")   val hardDeleteAt: String? = null,
)
