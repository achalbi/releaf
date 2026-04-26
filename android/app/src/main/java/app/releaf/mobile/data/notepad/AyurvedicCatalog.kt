/*
 * AyurvedicCatalog.kt
 *
 * Single seam the data layer uses to seed a fresh `NotepadEntry` with
 * an Ayurvedic plant: the day's vernacular `name` lands in the row's
 * `title` column, and `(commonName) epithet` lands in `description`.
 *
 * The "Used for …" tail (`usedFor`) is intentionally dropped from the
 * description seed — that detail surfaces in the editor's leaf-icon
 * modal drawer (NotepadDailyPlantInfoSheet) on demand, not as default
 * body text the user has to delete.
 *
 * Selection: daily rotation, sourced from the auto-generated
 * `DailyPlants` list under `ui.theme`. Importing a UI-package list
 * from the data layer is a small layer-crossing — but the file is
 * generated from `design-system/design-tokens.json`, so it's the
 * shared source of truth, not UI logic. Treat it as a curated data
 * asset that just happens to live there.
 *
 * Repository-level rule (see `NotepadRepository.create`): both fields
 * are auto-filled together — and ONLY when both were left blank by
 * the caller. If the caller supplied a title or a description we
 * keep their values verbatim and skip the seed entirely.
 */

package app.releaf.mobile.data.notepad

import app.releaf.mobile.ui.theme.DailyPlant
import app.releaf.mobile.ui.theme.DailyPlants
import java.time.LocalDate

object AyurvedicCatalog {

    /**
     * Plant for a fresh entry being filed under [date]. Defaults to
     * today's rotation. Returning the generated [DailyPlant] keeps
     * the catalog single-sourced — same row that drives the page-of-
     * the-day surfaces also seeds the notepad title + description.
     */
    fun forNewEntry(date: LocalDate = LocalDate.now()): DailyPlant =
        DailyPlants.forToday(date)

    /**
     * Render a plant as the seed description, paired with `name` in
     * the row's `title` column. Format:
     * `(<commonName>) <epithet> · <usedFor>`.
     *
     * Example: `(cinnamon) the sweet bark · blood sugar, warming,
     * baking, masala`. Mirrors the screenshot's items 5+6+7 the
     * editor used to render as a hero block; collapsing them into
     * the `description` field keeps that information one tap away
     * (now in the field directly under the title) without needing
     * the modal drawer to read it.
     */
    fun formatDescription(plant: DailyPlant): String =
        "(${plant.commonName}) ${plant.epithet} · ${plant.usedFor}"
}
