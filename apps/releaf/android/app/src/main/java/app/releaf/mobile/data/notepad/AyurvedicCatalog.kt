/*
 * AyurvedicCatalog.kt
 *
 * Single seam the data layer uses to seed a fresh `NotepadEntry` with
 * an Ayurvedic plant: the picked plant's vernacular `name` lands in
 * the row's `title` column, and `(commonName) epithet · usedFor`
 * lands in `description`.
 *
 * Selection: **per-entry, deterministic on the entry id** (UUIDv7).
 * Two entries created on the same day get different plants because
 * UUIDv7 carries a random tail per row; the same id always resolves
 * to the same plant so a re-derive (e.g. on a sync round-trip) lands
 * on the same row. Mirror of iOS `AyurvedicCatalog.plant(forId:)` so
 * a notepad row seeded on one platform shows the same plant on the
 * other once it round-trips through Drive.
 *
 * The plant pool itself is the auto-generated `DailyPlants.all` list
 * under `ui.theme`, sourced from `design-system/design-tokens.json`
 * (90 plants today). Importing a UI-package list from the data layer
 * is a small layer-crossing — but the file is generated from the
 * shared tokens file, so it's the canonical data asset, not UI logic.
 *
 * Repository-level rule (see `NotepadRepository.create`): both fields
 * are auto-filled together — and ONLY when both were left blank by
 * the caller. If the caller supplied a title or a description we
 * keep their values verbatim and skip the seed entirely.
 */

package app.releaf.mobile.data.notepad

import app.releaf.mobile.ui.theme.DailyPlant
import app.releaf.mobile.ui.theme.DailyPlants

object AyurvedicCatalog {

    /**
     * Plant for a fresh entry with the given [entryId]. Selection is
     * a stable djb2 hash of the id modulo the catalog size — so the
     * same id always picks the same plant, and consecutive UUIDv7s
     * (which differ in their random tail) reliably pick different
     * rows. That makes two entries created on the same day get
     * different plants, which is the intended behaviour now that the
     * notepad supports multiple pages per day.
     */
    fun forNewEntry(entryId: String): DailyPlant {
        val pool = DailyPlants.all
        if (pool.isEmpty()) {
            // Defensive: should never happen — generator emits at
            // least one entry. Fall back to the first slot rather
            // than throwing on an unexpectedly-empty pool.
            error("AyurvedicCatalog: DailyPlants.all is empty — check the token generator output")
        }
        val bucket = (entryId.djb2Hash().mod(pool.size) + pool.size).mod(pool.size)
        return pool[bucket]
    }

    /**
     * Render a plant as the seed description, paired with `name` in
     * the row's `title` column. Format:
     * `(<commonName>) <epithet> · <usedFor>`.
     *
     * Example: `(cinnamon) the sweet bark · blood sugar, warming,
     * baking, masala`. Collapses what used to render as a hero block
     * into the field directly under the title so that information is
     * one tap away without needing the modal drawer to read it.
     */
    fun formatDescription(plant: DailyPlant): String =
        "(${plant.commonName}) ${plant.epithet} · ${plant.usedFor}"

    /**
     * djb2 string hash. Used because Kotlin's `String.hashCode()` is
     * specified to be stable across runs of a given JVM, but mirroring
     * the Swift side's explicit djb2 keeps the picked plant identical
     * across platforms for the same entry id (Swift's built-in
     * `hashValue` is salted per-process, so iOS uses djb2 too).
     */
    private fun String.djb2Hash(): Int {
        var hash = 5381
        for (byte in this.encodeToByteArray()) {
            hash = ((hash shl 5) + hash) + (byte.toInt() and 0xFF)
        }
        return hash
    }
}
