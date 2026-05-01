/*
 * LeafDropletGlyph.swift  (app target)
 *
 * The `LeafDropletGlyph` view itself moved into ReleafCoreDesignSystem
 * during PR #4g so that PageHeaderControls (which uses it for the
 * eyebrow) could be shared. What stays here is the CaptureMode-aware
 * tint lookup — the design-system package has no business knowing
 * about Releaf's 8 capture modes, so that mapping is layered on as an
 * app-target extension.
 *
 * Color is keyed to the capture mode so the six tiles in the AT A
 * GLANCE grid read as a small palette rather than uniform decoration:
 *
 *   photos   → deep green (the main "growth" category)
 *   scans    → mid green
 *   todo     → light green (smaller, sproutier)
 *   contacts → info blue (people, not plants)
 *   place    → coral (location markers — the only coral in the grid)
 *   voice    → warning amber (transcripts decay; ephemeral)
 *
 * The mapping is intentional and lives in `tint(for:)` so call sites
 * don't pick their own colors.
 */

import SwiftUI
import ReleafCoreDesignSystem

extension LeafDropletGlyph {
    /// Lookup helper — call sites just pass the CaptureMode the tile
    /// represents and get the right tint back. Lives on the glyph so a
    /// single import covers the whole pattern.
    public static func tint(for mode: CaptureMode) -> Color {
        switch mode {
        case .photos:   return AppColors.green                          // deep forest
        case .scans:    return AppColors.themeGreenPrimary              // mid leaf
        case .todo:     return AppColors.themeGreenPrimary.opacity(0.55) // sprout
        case .contacts: return AppColors.info                           // people
        case .location: return AppColors.coralDeep                      // pin
        case .voice:    return AppColors.warning                        // amber
        case .notes:    return AppColors.themeGreenPrimary              // ink (placeholder — tighten with design)
        case .overview: return AppColors.themeGreenPrimary
        }
    }
}
