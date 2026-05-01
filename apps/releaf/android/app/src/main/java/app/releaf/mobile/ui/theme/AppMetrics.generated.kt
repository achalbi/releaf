// GENERATED — DO NOT EDIT.
// Run `node design-system/scripts/generate-tokens.mjs` to regenerate.
//
// Source: design-system/design-tokens.json (metric.*)

package app.releaf.mobile.ui.theme

/**
 * Non-color, non-typographic metrics shared between iOS and Android.
 * Today this is the Re-Leaf paper-saved math: per-capture multipliers
 * plus the sheets-per-tree constant.
 */
object AppMetrics {

    /** Sheets of paper a single capture of each kind would have replaced. */
    object PaperPerCapture {
        /** One scan replaces one printed page. */
        const val Scan: Double = 1.0
        /** Per Note row — roughly a quarter-page of writing each. */
        const val Note: Double = 0.3
        /** A voice memo replaces a tenth of a sheet of dictation. */
        const val Voice: Double = 0.1
        /** A captured contact replaces a business card. */
        const val Contact: Double = 0.1
        /** A pinned place replaces a sliver of a printed map / address slip. */
        const val Place: Double = 0.05
        /** A captured photo replaces a small printed reference image. */
        const val Photo: Double = 0.05
        /** A todo replaces a sticky-note's worth of paper. */
        const val Todo: Double = 0.05
    }

    /** Letter-size sheets a mature pine yields. Used to convert sheets saved into a tree-fraction for the FOREST tile and the paper-saved detail sheet. */
    const val sheetsPerTree: Double = 8333.0
}
