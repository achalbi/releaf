// GENERATED — DO NOT EDIT.
// Run `node design-system/scripts/generate-tokens.mjs` to regenerate.
//
// Source: design-system/design-tokens.json (metric.*)

import Foundation

/// Non-color, non-typographic metrics shared between iOS and Android.
/// Today this is the Re-Leaf paper-saved math: per-capture multipliers
/// plus the sheets-per-tree constant.
public enum AppMetrics {

    /// Sheets of paper a single capture of each kind would have replaced.
    /// Multiply against `Page.counts.<kind>` to get sheets saved.
    public enum PaperPerCapture {
        /// One scan replaces one printed page.
        public static let scan: Double = 1.0
        /// Per Note row — roughly a quarter-page of writing each.
        public static let note: Double = 0.3
        /// A voice memo replaces a tenth of a sheet of dictation.
        public static let voice: Double = 0.1
        /// A captured contact replaces a business card.
        public static let contact: Double = 0.1
        /// A pinned place replaces a sliver of a printed map / address slip.
        public static let place: Double = 0.05
        /// A captured photo replaces a small printed reference image.
        public static let photo: Double = 0.05
        /// A todo replaces a sticky-note's worth of paper.
        public static let todo: Double = 0.05
    }

    /// Letter-size sheets a mature pine yields. Used to convert sheets saved into a tree-fraction for the FOREST tile and the paper-saved detail sheet.
    public static let sheetsPerTree: Double = 8333.0
}
