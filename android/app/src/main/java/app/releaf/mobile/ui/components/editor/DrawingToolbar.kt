/*
 * DrawingToolbar.kt
 *
 * Freehand-drawing controls that pair with `DrawingOverlay`. Shown inline
 * with the rich-text format bar in Edit mode.
 *
 * Layout: a compact top row with the pen + erase mode toggles and the
 * nib + thickness pickers; a bottom row with the color swatches and
 * the ink-shade stops. The whole thing is horizontally scrollable so
 * it survives narrow screens without wrapping.
 *
 * All render choices (color swatch, nib style, width, shade) live on
 * the screen's state and are passed in — the toolbar itself is
 * stateless.
 */

package app.releaf.mobile.ui.components.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.FormatPaint
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.releaf.mobile.data.notebook.Stroke
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppAccent
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography

// ---- Design tokens for the drawing surface --------------------------------
//
// The five-color palette + three shade stops + two nibs + three
// thicknesses are the full v1 spec — encoded as stable constants here
// so every call site (toolbar, stroke renderer, saveable state) reads
// from the same source.

/** Primary palette — each can be tuned via the shade picker. */
val DrawingPalette: List<Color> = listOf(
    Color(0xFF463C31), // Neutral700
    Color(0xFFC65A3E), // Coral700
    Color(0xFF5B8C52), // forest green
    Color(0xFFE8B923), // goldenrod
    Color(0xFF8B7355), // warm tan
)

/** Ink shade stops in display order: 100/80/60%.
 *
 *  Stored as alpha multipliers on whatever base color is picked, so a
 *  "Shade" chip at 60% renders the color at 60% of its saturation —
 *  enough to read as a lighter stroke without disappearing. We dropped
 *  the 40/20 stops that an earlier build had because anything below
 *  60% was visually indistinguishable from background noise at the
 *  thin-nib preset. */
val DrawingShades: List<Float> = listOf(1.0f, 0.8f, 0.6f)

data class ThicknessPreset(val label: String, val widthDp: Float)

/** Five thickness stops that roughly double each step — S (2dp) for a
 *  fine point through XXL (20dp) for a bold marker. The chip preview
 *  renders each preset at its actual thickness, so labels aren't
 *  surfaced in the UI. */
val DrawingThicknesses: List<ThicknessPreset> = listOf(
    ThicknessPreset("S",   2f),
    ThicknessPreset("M",   4f),
    ThicknessPreset("L",   8f),
    ThicknessPreset("XL",  14f),
    ThicknessPreset("XXL", 20f),
)

data class NibPreset(
    val id: String,
    val label: String,
    val icon: ImageVector,
    /** Subset of `DrawingThicknesses` this nib is allowed to use. The
     *  toolbar shows only these chips when the nib is active so the
     *  user can't pick, say, a 2dp "Ballpoint-fine" highlighter that
     *  reads as a pencil mark. */
    val allowedWidths: List<ThicknessPreset>,
    /** Width to fall back to when switching into this nib from one
     *  whose current width isn't in [allowedWidths]. */
    val defaultWidth: Float,
    /** Shade to snap to whenever the user switches INTO this nib.
     *  Ballpoint wants fully-opaque ink (100%), highlighter wants a
     *  translucent swipe (60%) — matching how each tool behaves in
     *  the physical world. Always applied on nib change, even if the
     *  incoming shade is also in `DrawingShades`, so the UI's "nib =
     *  preset" mental model holds. */
    val defaultShade: Float,
)

/** Two nibs in v1 — Ballpoint (uniform stroke) and Highlighter
 *  (semi-transparent wide stroke). The Fountain nib was removed after
 *  user testing — too close visually to Ballpoint at typical
 *  thicknesses to justify a third option on the toolbar.
 *
 *  Each nib ships with its own subset of the thickness stops and its
 *  own default shade. A ballpoint at 14dp would look like a marker
 *  (not a pen), and a highlighter at 100% opacity would look like a
 *  paint stroke (not a highlighter). Constraining per-nib keeps each
 *  tool's identity readable. */
val DrawingNibs: List<NibPreset> = listOf(
    NibPreset(
        id            = Stroke.NIB_BALLPOINT,
        label         = "Ballpoint",
        icon          = Icons.Filled.Create,
        allowedWidths = listOf(DrawingThicknesses[1], DrawingThicknesses[2]), // M, L
        defaultWidth  = DrawingThicknesses[1].widthDp,                         // M (4dp)
        defaultShade  = 1.0f,                                                  // 100%
    ),
    NibPreset(
        id            = Stroke.NIB_HIGHLIGHTER,
        label         = "Highlighter",
        icon          = Icons.Filled.FormatPaint,
        allowedWidths = listOf(DrawingThicknesses[2], DrawingThicknesses[3]), // L, XL
        defaultWidth  = DrawingThicknesses[2].widthDp,                         // L (8dp)
        defaultShade  = 0.6f,                                                  // 60%
    ),
)

/** Default thickness for a nib by id. Returns M (4dp) for any
 *  unrecognised id — covers legacy stroke data whose nib field no
 *  longer matches a known preset. */
fun defaultWidthFor(nibId: String): Float =
    DrawingNibs.firstOrNull { it.id == nibId }?.defaultWidth
        ?: DrawingThicknesses[1].widthDp

/** Default shade (alpha multiplier) for a nib by id. Ballpoint → 1.0,
 *  Highlighter → 0.6; falls back to 1.0 for anything unrecognised. */
fun defaultShadeFor(nibId: String): Float =
    DrawingNibs.firstOrNull { it.id == nibId }?.defaultShade ?: 1.0f

// --- rememberSaveable helpers for the screen state ---

val DrawingModeSaver: Saver<DrawingMode, String> = Saver(
    save    = { it.name },
    restore = { runCatching { DrawingMode.valueOf(it) }.getOrDefault(DrawingMode.Off) },
)

/**
 * Color saver that rounds the color through its ARGB Int form. Loses
 * color-space info, which is fine for the fixed sRGB palette shipped
 * with the drawing toolbar.
 */
val DrawingColorSaver: Saver<Color, Int> = Saver(
    save    = { it.toArgb() },
    restore = { Color(it) },
)

@Composable
fun DrawingToolbar(
    mode: DrawingMode,
    onModeChange: (DrawingMode) -> Unit,
    color: Color,
    onColorChange: (Color) -> Unit,
    opacity: Float,
    onOpacityChange: (Float) -> Unit,
    widthDp: Float,
    onWidthChange: (Float) -> Unit,
    nib: String,
    onNibChange: (String) -> Unit,
    /** Tapped when the user wants to leave drawing mode entirely —
     *  wired up to the X in the top-right corner of the toolbar.
     *  Callers should flip their screen-level `drawingMode` back to
     *  `DrawingMode.Off`, which swaps the drawing toolbar out for the
     *  normal rich-text format bar. */
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(AppColors.CardSolid)
            .padding(horizontal = AppSpacing.s3, vertical = AppSpacing.s2),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s2),
    ) {
        // --- Row 1: modes + nibs + thickness + close ---------------------
        //
        // Outer Row keeps the close button pinned to the right edge no
        // matter how wide the scrollable controls grow. The inner Row
        // owns the horizontal scroll so the mode / nib / thickness
        // chips can overflow on narrow screens without pushing the X
        // off-screen.
        Row(
            modifier          = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier          = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ModeIcon(
                    icon   = Icons.Filled.Brush,
                    label  = "Pen",
                    active = mode == DrawingMode.Pen,
                ) {
                    onModeChange(if (mode == DrawingMode.Pen) DrawingMode.Off else DrawingMode.Pen)
                }
                ModeIcon(
                    // Custom ImageVector — Material Icons Extended 1.7.3
                    // has no `InkEraser`, so `RubberEraser` draws the same
                    // tilted-rubber-on-baseline shape directly. See
                    // `RubberEraserIcon.kt`. Tinted with the toolbar's
                    // Coral accent (the `ModeIcon` default) so it sits
                    // alongside Pen / nib / thickness chips as one of
                    // the mode affordances — the earlier Danger-red
                    // read as a destructive warning and didn't match
                    // the rest of the row.
                    icon   = RubberEraser,
                    label  = "Erase",
                    active = mode == DrawingMode.Eraser,
                ) {
                    onModeChange(if (mode == DrawingMode.Eraser) DrawingMode.Off else DrawingMode.Eraser)
                }
                // Text mode — tap on the sub-page to drop a free-form
                // text box at the tap point. Same toggle-to-off
                // pattern as Pen / Eraser so all three mode buttons
                // read consistently.
                TextModeButton(
                    active = mode == DrawingMode.Text,
                ) {
                    onModeChange(if (mode == DrawingMode.Text) DrawingMode.Off else DrawingMode.Text)
                }

                BarDivider()

                DrawingNibs.forEach { preset ->
                    ModeIcon(
                        icon   = preset.icon,
                        label  = preset.label,
                        active = nib == preset.id,
                    ) {
                        onNibChange(preset.id)
                        // Snap to the new nib's default width if the
                        // user's current width isn't in its allow-list.
                        // Without this the ThicknessChip row would show
                        // no active chip until the user picked one
                        // manually — confusing.
                        if (preset.allowedWidths.none { it.widthDp == widthDp }) {
                            onWidthChange(preset.defaultWidth)
                        }
                        // Always reset shade to the nib's preset —
                        // ballpoint → 100%, highlighter → 60%. Each
                        // nib has a "native" transparency and picking
                        // the nib should also pick that transparency.
                        // Users can still override via the shade chips
                        // after selecting the nib.
                        onOpacityChange(preset.defaultShade)
                    }
                }

                BarDivider()

                // Nib-scoped thickness picker. Only the widths the
                // current nib allows are shown — ballpoint gets M/L,
                // highlighter gets L/XL. Falls back to the full list
                // if the nib id isn't recognised (legacy data safety
                // net — shouldn't happen in practice).
                val activeNib = DrawingNibs.firstOrNull { it.id == nib }
                val visibleWidths = activeNib?.allowedWidths ?: DrawingThicknesses
                visibleWidths.forEach { preset ->
                    ThicknessChip(
                        preset = preset,
                        active = widthDp == preset.widthDp,
                        onClick = { onWidthChange(preset.widthDp) },
                    )
                }
            }

            // Close affordance — tilted slightly off Row-1's baseline
            // so it reads as an exit action, not a mode chip.
            Spacer(Modifier.width(AppSpacing.s2))
            CloseButton(onClick = onClose)
        }

        // --- Row 2: colors + opacity ---------------------------------------
        Row(
            modifier          = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DrawingPalette.forEach { swatch ->
                ColorSwatch(
                    swatch   = swatch,
                    selected = swatch.value == color.value,
                    onClick  = { onColorChange(swatch) },
                )
            }

            BarDivider()

            DrawingShades.forEach { stop ->
                ShadeChip(
                    shade     = stop,
                    baseColor = color,
                    selected  = stop == opacity,
                    onClick   = { onOpacityChange(stop) },
                )
            }
        }
    }
}

@Composable
private fun ModeIcon(
    icon: ImageVector,
    label: String,
    active: Boolean,
    tint: Color = AppAccent.primary,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(AppSpacing.s2))
            .background(if (active) tint.copy(alpha = 0.15f) else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector        = icon,
            contentDescription = label,
            tint               = tint,
            modifier           = Modifier.size(20.dp),
        )
    }
}

/**
 * Text-mode button. Renders as a bold "T" glyph because a generic
 * "text fields" Material icon doesn't read as clearly at this size —
 * a literal letter communicates the mode without a legend.
 */
@Composable
private fun TextModeButton(
    active: Boolean,
    tint: Color = AppAccent.primary,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(AppSpacing.s2))
            .background(if (active) tint.copy(alpha = 0.15f) else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text  = "T",
            style = AppTypography.Button.copy(fontWeight = FontWeight.Bold),
            color = tint,
        )
    }
}

@Composable
private fun ThicknessChip(preset: ThicknessPreset, active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(horizontal = AppSpacing.s1)
            .size(40.dp)
            .clip(RoundedCornerShape(AppSpacing.s2))
            .background(if (active) AppAccent.primary.copy(alpha = 0.15f) else Color.Transparent)
            .border(
                width = 1.dp,
                color = if (active) AppAccent.primary else AppColors.BorderDefault,
                shape = RoundedCornerShape(AppSpacing.s2),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // Use a line thickness preview instead of text — more honest.
        Box(
            modifier = Modifier
                .width(20.dp)
                .height(preset.widthDp.dp)
                .clip(RoundedCornerShape(50))
                .background(AppColors.TextPrimary),
        )
    }
}

@Composable
private fun ColorSwatch(swatch: Color, selected: Boolean, onClick: () -> Unit) {
    val border = if (selected) {
        BorderStroke(2.dp, AppColors.TextPrimary)
    } else {
        BorderStroke(1.dp, AppColors.BorderDefault)
    }
    Box(
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .size(28.dp)
            .clip(CircleShape)
            .background(swatch)
            .border(border, CircleShape)
            .clickable(onClick = onClick),
    )
}

/** Ink-shade chip — selects how translucent the stroke should render.
 *  Renamed from `OpacityChip` to match the user-facing "Shade"
 *  metaphor; the underlying value is still a raw alpha Float in
 *  [0,1] that `DrawingOverlay` multiplies through the base color. */
@Composable
private fun ShadeChip(
    shade: Float,
    baseColor: Color,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val border = if (selected) {
        BorderStroke(2.dp, AppColors.TextPrimary)
    } else {
        BorderStroke(1.dp, AppColors.BorderDefault)
    }
    Box(
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .size(width = 40.dp, height = 28.dp)
            .clip(RoundedCornerShape(AppSpacing.s1))
            .background(baseColor.copy(alpha = shade))
            .border(border, RoundedCornerShape(AppSpacing.s1))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text  = "${(shade * 100).toInt()}",
            style = AppTypography.Meta.copy(fontWeight = FontWeight.SemiBold),
            color = if (shade > 0.45f) Color.White else AppColors.TextPrimary,
        )
    }
}

@Composable
private fun BarDivider() {
    Spacer(Modifier.width(AppSpacing.s2))
    Box(
        Modifier
            .width(1.dp)
            .height(20.dp)
            .background(AppColors.BorderDefault),
    )
    Spacer(Modifier.width(AppSpacing.s2))
}

/** Dismiss affordance in the top-right corner of the drawing toolbar.
 *  Circular to read as a secondary action (tonal background, neutral
 *  tint) rather than one of the primary mode chips, which share the
 *  Coral accent. */
@Composable
private fun CloseButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(AppColors.BorderDefault.copy(alpha = 0.3f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector        = Icons.Filled.Close,
            contentDescription = "Close drawing tools",
            tint               = AppColors.TextSecondary,
            modifier           = Modifier.size(18.dp),
        )
    }
}
