/*
 * LeafDropletGlyphTints.kt  (app target)
 *
 * The `LeafDropletGlyph` composable itself moved into :shared:designsystem
 * during PR #4h so that PageHeaderControls (which uses it for the
 * eyebrow) could be shared. What stays here is the CaptureMode-aware
 * tint lookup — :shared:designsystem has no business knowing about
 * Releaf's 8 capture modes, so that mapping is layered on as an
 * app-target sibling file in the same Kotlin package.
 *
 * Color is keyed to the capture mode so the six tiles in the AT A
 * GLANCE grid read as a small palette rather than uniform decoration:
 *
 *   photos   → deep forest green
 *   scans    → mid leaf green
 *   todo     → sprouty light green
 *   contacts → info blue (people, not plants)
 *   place    → coral (location pin)
 *   voice    → warning amber (transcripts decay)
 *   overview → mid leaf green (rarely used — glyph drives stat tiles,
 *              and the overview tile sits in the strip header instead)
 *   notes    → deep coral, matching the brand accent for the writing
 *              surface among the other section glyphs
 *
 * Mirrors iOS `LeafDropletGlyph.tint(for:)` byte-for-byte and matches
 * the iOS PR #4g app-target extension on `LeafDropletGlyph`.
 */

package app.releaf.mobile.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import app.releaf.mobile.ui.theme.AppColors

@Composable
@ReadOnlyComposable
fun leafDropletTintFor(mode: CaptureMode): Color = when (mode) {
    CaptureMode.Photos   -> AppColors.Green
    CaptureMode.Scans    -> AppColors.ThemeGreenPrimary
    CaptureMode.Todo     -> AppColors.ThemeGreenPrimary.copy(alpha = 0.55f)
    CaptureMode.Contacts -> AppColors.Info
    CaptureMode.Location -> AppColors.CoralDeep
    CaptureMode.Voice    -> AppColors.Warning
    CaptureMode.Overview -> AppColors.ThemeGreenPrimary
    CaptureMode.Notes    -> AppColors.CoralDeep
}
