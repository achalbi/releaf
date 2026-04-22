/*
 * DrawingToolbar.kt
 *
 * Freehand-drawing controls that pair with `DrawingOverlay`. Shown inline
 * with the rich-text format bar in Edit mode.
 *
 * Layout: a compact top row with the three mode toggles (off / pen /
 * eraser) and the nib + thickness pickers; a bottom row with the color
 * swatches and the opacity stops. The whole thing is horizontally
 * scrollable so it survives narrow screens without wrapping.
 *
 * All render choices (color swatch, nib style, width, alpha) live in
 * `DrawingToolbarState` — the screen owns the state and passes mutations
 * back up so the value is saveable across configuration changes.
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
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Edit
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
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography

// ---- Design tokens for the drawing surface --------------------------------
//
// The five-color palette + five opacity stops + three nibs + three
// thicknesses are the full v1 spec — encoded as stable constants here
// so every call site (toolbar, stroke renderer, saveable state) reads
// from the same source.

/** Primary palette — each can be tuned via the opacity picker. */
val DrawingPalette: List<Color> = listOf(
    Color(0xFF463C31), // Neutral700
    Color(0xFFC65A3E), // Coral700
    Color(0xFF5B8C52), // forest green
    Color(0xFFE8B923), // goldenrod
    Color(0xFF8B7355), // warm tan
)

/** Opacity stops in display order: 100/80/60/40/20%. */
val DrawingOpacities: List<Float> = listOf(1.0f, 0.8f, 0.6f, 0.4f, 0.2f)

data class ThicknessPreset(val label: String, val widthDp: Float)

val DrawingThicknesses: List<ThicknessPreset> = listOf(
    ThicknessPreset("S", 2f),
    ThicknessPreset("M", 4f),
    ThicknessPreset("L", 8f),
)

data class NibPreset(val id: String, val label: String, val icon: ImageVector)

val DrawingNibs: List<NibPreset> = listOf(
    NibPreset(Stroke.NIB_BALLPOINT,   "Ballpoint",   Icons.Filled.Create),
    NibPreset(Stroke.NIB_FOUNTAIN,    "Fountain",    Icons.Filled.Edit),
    NibPreset(Stroke.NIB_HIGHLIGHTER, "Highlighter", Icons.Filled.FormatPaint),
)

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
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(AppColors.CardSolid)
            .padding(horizontal = AppSpacing.s3, vertical = AppSpacing.s2),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s2),
    ) {
        // --- Row 1: modes + nibs + thickness -------------------------------
        Row(
            modifier          = Modifier
                .fillMaxWidth()
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
                icon   = Icons.Filled.FormatPaint, // eraser glyph stand-in
                label  = "Erase",
                active = mode == DrawingMode.Eraser,
                tint   = AppColors.Danger,
            ) {
                onModeChange(if (mode == DrawingMode.Eraser) DrawingMode.Off else DrawingMode.Eraser)
            }

            BarDivider()

            DrawingNibs.forEach { preset ->
                ModeIcon(
                    icon   = preset.icon,
                    label  = preset.label,
                    active = nib == preset.id,
                ) {
                    onNibChange(preset.id)
                }
            }

            BarDivider()

            DrawingThicknesses.forEach { preset ->
                ThicknessChip(
                    preset = preset,
                    active = widthDp == preset.widthDp,
                    onClick = { onWidthChange(preset.widthDp) },
                )
            }
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

            DrawingOpacities.forEach { stop ->
                OpacityChip(
                    opacity  = stop,
                    baseColor = color,
                    selected = stop == opacity,
                    onClick  = { onOpacityChange(stop) },
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
    tint: Color = AppColors.Coral,
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

@Composable
private fun ThicknessChip(preset: ThicknessPreset, active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(horizontal = AppSpacing.s1)
            .size(40.dp)
            .clip(RoundedCornerShape(AppSpacing.s2))
            .background(if (active) AppColors.Coral.copy(alpha = 0.15f) else Color.Transparent)
            .border(
                width = 1.dp,
                color = if (active) AppColors.Coral else AppColors.BorderDefault,
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

@Composable
private fun OpacityChip(
    opacity: Float,
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
            .background(baseColor.copy(alpha = opacity))
            .border(border, RoundedCornerShape(AppSpacing.s1))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text  = "${(opacity * 100).toInt()}",
            style = AppTypography.Meta.copy(fontWeight = FontWeight.SemiBold),
            color = if (opacity > 0.45f) Color.White else AppColors.TextPrimary,
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
