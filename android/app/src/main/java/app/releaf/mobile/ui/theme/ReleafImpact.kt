/*
 * ReleafImpact.kt
 *
 * Pure value-type that turns a page's capture counts into the two
 * numbers the Re-Leaf strip displays: sheets of paper saved and the
 * fraction of a mature pine those sheets represent.
 *
 * The per-capture multipliers + sheets-per-tree constant come from
 * `AppMetrics.generated.kt`, which is emitted from the same JSON the
 * iOS side reads — both platforms compute identical numbers.
 *
 * Lives in `ui.theme` (alongside AppMetrics) so it stays in the
 * design-system layer with no domain dependency. Call sites in the
 * page detail screen pluck the counts off a Page and pass them in.
 */

package app.releaf.mobile.ui.theme

import java.util.Locale

data class ReleafImpact(
    /** Sheets of paper this page (or aggregate) replaced. */
    val sheetsSaved: Double,
    /** Fraction of one mature pine. `sheetsSaved / sheetsPerTree`. */
    val treeFraction: Double,
) {
    /** Tile-friendly sheets readout — "3.2", "0.5", "12.0". */
    val formattedSheets: String
        get() = String.format(Locale.US, "%.1f", sheetsSaved)

    /**
     * Tile-friendly tree readout. Switches unit at small values so a
     * single-page contribution reads as "0.03% of a pine" rather than
     * "0.00 trees standing", which would look broken.
     */
    val treeReadout: TreeReadout
        get() = when {
            treeFraction >= 0.01 -> TreeReadout(
                number = String.format(Locale.US, "%.2f", treeFraction),
                unit   = "trees standing",
            )
            treeFraction > 0     -> TreeReadout(
                number = String.format(Locale.US, "%.2f%%", treeFraction * 100.0),
                unit   = "of one pine",
            )
            else                 -> TreeReadout(
                number = "0.00",
                unit   = "trees standing",
            )
        }

    data class TreeReadout(val number: String, val unit: String)

    companion object {
        fun from(
            photos: Int = 0,
            voiceNotes: Int = 0,
            todoItems: Int = 0,
            scans: Int = 0,
            contacts: Int = 0,
            places: Int = 0,
            notes: Int = 0,
        ): ReleafImpact {
            val m = AppMetrics.PaperPerCapture
            val sheets =
                photos     * m.Photo +
                voiceNotes * m.Voice +
                todoItems  * m.Todo +
                scans      * m.Scan +
                contacts   * m.Contact +
                places     * m.Place +
                notes      * m.Note
            return ReleafImpact(
                sheetsSaved  = sheets,
                treeFraction = sheets / AppMetrics.sheetsPerTree,
            )
        }
    }
}
