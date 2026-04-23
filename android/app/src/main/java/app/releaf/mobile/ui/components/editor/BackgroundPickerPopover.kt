/*
 * BackgroundPickerPopover.kt
 *
 * DropdownMenu that the sub-page indicator's "Background" icon opens.
 * Lets the user pick one of five patterns and adjust the pattern
 * scale (background-only zoom — text and strokes are unaffected).
 */

package app.releaf.mobile.ui.components.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.releaf.mobile.data.notebook.SubPage
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppAccent
import app.releaf.mobile.ui.theme.AppRadius
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography
import androidx.compose.foundation.Canvas

private const val MinScale = 0.5f
private const val MaxScale = 2.0f
private const val ScaleStep = 0.25f

/** Display label for each pattern id, in the order they appear in the picker. */
private data class PatternOption(val id: String, val label: String)

private val PatternOptions = listOf(
    PatternOption(SubPage.BG_PLAIN, "Plain"),
    PatternOption(SubPage.BG_GRID,  "Grid"),
    PatternOption(SubPage.BG_DOTS,  "Dots"),
    PatternOption(SubPage.BG_LINES, "Lines"),
    PatternOption(SubPage.BG_RULED, "Ruled"),
)

@Composable
fun BackgroundPickerPopover(
    expanded: Boolean,
    background: String,
    scale: Float,
    onDismiss: () -> Unit,
    onBackgroundChange: (String) -> Unit,
    onScaleChange: (Float) -> Unit,
    onSaveToPhotos: () -> Unit,
) {
    DropdownMenu(
        expanded         = expanded,
        onDismissRequest = onDismiss,
        modifier         = Modifier
            .background(AppColors.CardSolid)
            .padding(AppSpacing.s3)
            .width(280.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s3)) {
            // --- Patterns -------------------------------------------------
            Text(
                text  = "BACKGROUND",
                style = AppTypography.Eyebrow,
                color = AppColors.TextSecondary,
            )
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2),
            ) {
                PatternOptions.forEach { opt ->
                    PatternSwatch(
                        patternId = opt.id,
                        label     = opt.label,
                        selected  = opt.id == background,
                        enabled   = true,
                        onClick   = { onBackgroundChange(opt.id) },
                        modifier  = Modifier.weight(1f),
                    )
                }
            }

            // --- Zoom -----------------------------------------------------
            Row(
                modifier          = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text  = "ZOOM",
                    style = AppTypography.Eyebrow,
                    color = AppColors.TextSecondary,
                )
                Spacer(Modifier.weight(1f))
                StepButton(
                    icon     = Icons.Filled.Remove,
                    label    = "Zoom out",
                    enabled  = scale > MinScale + 0.001f,
                    onClick  = {
                        onScaleChange((scale - ScaleStep).coerceIn(MinScale, MaxScale))
                    },
                )
                Text(
                    text     = "${(scale * 100).toInt()}%",
                    style    = AppTypography.Button,
                    color    = AppColors.TextPrimary,
                    modifier = Modifier
                        .padding(horizontal = AppSpacing.s2)
                        .width(48.dp),
                )
                StepButton(
                    icon     = Icons.Filled.Add,
                    label    = "Zoom in",
                    enabled  = scale < MaxScale - 0.001f,
                    onClick  = {
                        onScaleChange((scale + ScaleStep).coerceIn(MinScale, MaxScale))
                    },
                )
            }

            // --- Save snapshot to this page's Photos section -------------
            //
            // Exports the current sub-page (background + text + strokes)
            // as a JPG and attaches it to the page's Photos section.
            // Nothing is written to the phone's gallery — this is an
            // in-page attachment. Toast feedback lives in the pager.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(AppRadius.sm))
                    .clickable(onClick = onSaveToPhotos)
                    .padding(vertical = AppSpacing.s2, horizontal = AppSpacing.s1),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector        = Icons.Filled.Image,
                    contentDescription = "Add to Photos",
                    tint               = AppAccent.primary,
                    modifier           = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(AppSpacing.s2))
                Column(Modifier.weight(1f)) {
                    Text(
                        text  = "Add to Photos",
                        style = AppTypography.Button,
                        color = AppColors.TextPrimary,
                    )
                    Text(
                        text  = "Snapshots this page as a JPG in the Photos section.",
                        style = AppTypography.Meta,
                        color = AppColors.TextSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun PatternSwatch(
    patternId: String,
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val borderColor = when {
        selected -> AppAccent.primary
        else     -> AppColors.BorderDefault
    }
    val alpha = if (enabled) 1f else 0.4f
    // Resolve theme colors in the composable so the non-composable
    // `drawSwatchPattern` draw lambda can use them without needing a
    // composable context of its own.
    val lineColor = AppColors.BorderDefault.copy(alpha = alpha * 0.8f)
    val marginColor = AppAccent.primary.copy(alpha = alpha * 0.5f)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(AppSpacing.s2))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(AppSpacing.s1),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s1),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(AppSpacing.s1))
                .background(AppColors.Canvas.copy(alpha = alpha))
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = borderColor.copy(alpha = alpha),
                    shape = RoundedCornerShape(AppSpacing.s1),
                ),
        ) {
            Canvas(modifier = Modifier.fillMaxWidth().height(44.dp)) {
                drawSwatchPattern(patternId, lineColor, marginColor)
            }
        }
        Text(
            text  = label,
            style = AppTypography.Meta.copy(fontWeight = FontWeight.SemiBold),
            color = if (selected) AppAccent.primary else AppColors.TextSecondary,
        )
    }
}

private fun DrawScope.drawSwatchPattern(
    patternId: String,
    lineColor: Color,
    marginColor: Color,
) {
    val spacing = 8f * density
    val stroke = 1f * density

    when (patternId) {
        SubPage.BG_PLAIN -> { /* nothing */ }
        SubPage.BG_GRID  -> {
            var x = spacing
            while (x < size.width) {
                drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), stroke)
                x += spacing
            }
            var y = spacing
            while (y < size.height) {
                drawLine(lineColor, Offset(0f, y), Offset(size.width, y), stroke)
                y += spacing
            }
        }
        SubPage.BG_DOTS  -> {
            var y = spacing
            val r = 1.2f * density
            while (y < size.height) {
                var x = spacing
                while (x < size.width) {
                    drawCircle(lineColor, radius = r, center = Offset(x, y))
                    x += spacing
                }
                y += spacing
            }
        }
        SubPage.BG_LINES -> {
            var y = spacing
            while (y < size.height) {
                drawLine(lineColor, Offset(0f, y), Offset(size.width, y), stroke)
                y += spacing
            }
        }
        SubPage.BG_RULED -> {
            var y = spacing
            while (y < size.height) {
                drawLine(lineColor, Offset(0f, y), Offset(size.width, y), stroke)
                y += spacing
            }
            val x = size.width * 2f / 3f
            drawLine(marginColor, Offset(x, 0f), Offset(x, size.height), stroke)
        }
    }
}

@Composable
private fun StepButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (enabled) AppAccent.primary else AppColors.TextTertiary
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(AppSpacing.s2))
            .border(1.dp, tint.copy(alpha = 0.4f), RoundedCornerShape(AppSpacing.s2))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector        = icon,
            contentDescription = label,
            tint               = tint,
            modifier           = Modifier.size(16.dp),
        )
    }
}
