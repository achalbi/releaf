/*
 * SyncJson.kt
 *
 * Default kotlinx.serialization Json configuration for everything that
 * crosses the Drive boundary — both the orchestrator (SyncRepository,
 * Manifest, TombstoneFile) and Releaf-side payload types (SyncPayloads).
 *
 * `ignoreUnknownKeys` lets a newer writer's extra fields flow past an
 * older reader without blowing up — forward-compat for minor schema
 * bumps per OPEN_QUESTIONS §5.
 *
 * `prettyPrint = false` and `encodeDefaults = true` keep the output
 * canonical-JSON friendly: stable across runs, no whitespace drift,
 * defaulted fields explicit on the wire.
 *
 * Lived in SyncPayloads.kt before PR #3c moved the orchestrator into
 * :shared:sync; promoted to its own file so both sides of the boundary
 * (the shared orchestrator and the Releaf-specific payload types)
 * consume the same instance.
 */

package app.releaf.mobile.data.sync

import kotlinx.serialization.json.Json

val SyncJson: Json = Json {
    prettyPrint       = false
    ignoreUnknownKeys = true
    encodeDefaults    = true
}
