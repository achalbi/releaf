/*
 * ReLeafStrip.kt
 *
 * Two-tile strip that sits between the CaptureTabBar and the AT A
 * GLANCE grid on the page Overview. Reuses the same card vocabulary
 * as StatGrid so it reads as a section the page always had — not a
 * new component class.
 *
 * Left tile  — SHEETS SAVED + tree silhouette in the bottom-right
 * Right tile — FOREST + five-segment progress dotline
 *
 * Tapping the "RE-LEAF" eyebrow above the tiles fires `onShowDetail`,
 * which the parent wires to the PaperSavedSheet. Eyebrow is the only
 * tap target; the tiles themselves are read-only.
 *
 * Mirrors `ReLeafStrip.swift` (iOS).
 */

package app.releaf.mobile.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppRadius
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography
import app.releaf.mobile.ui.theme.ReleafImpact

@Composable
fun ReLeafStrip(
    impact: ReleafImpact,
    modifier: Modifier = Modifier,
    onShowDetail: () -> Unit = {},
    /** Optional override for the eyebrow + SHEETS SAVED label
     *  tint. Nil → strip stays in its default themeGreenDeep
     *  tone. Tree silhouette + progress dots intentionally stay
     *  green: those visuals stand for "trees" semantically and
     *  shouldn't morph with notebook color. */
    accentOverride: androidx.compose.ui.graphics.Color? = null,
) {
    val eyebrowTint = accentOverride ?: AppColors.ThemeGreenDeep
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s2),
    ) {
        // Eyebrow + info icon. Tappable area is the whole row; the
        // tiles below are read-only.
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(AppRadius.sm))
                .clickable(onClick = onShowDetail)
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text  = "RE-LEAF",
                style = AppTypography.Eyebrow,
                color = eyebrowTint,
            )
            Icon(
                imageVector        = Icons.Filled.Info,
                contentDescription = "Show how paper saved is counted",
                tint               = eyebrowTint.copy(alpha = 0.55f),
                modifier           = Modifier.size(11.dp),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2)) {
            SheetsSavedTile(
                value      = impact.formattedSheets,
                accentTint = eyebrowTint,
                modifier   = Modifier.weight(1f),
            )
            ForestTile(
                impact   = impact,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// MARK: - SHEETS SAVED tile

@Composable
private fun SheetsSavedTile(
    value: String,
    accentTint: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(AppRadius.lg))
            .background(AppColors.CardSolid)
            .border(1.dp, AppColors.BorderDefault, RoundedCornerShape(AppRadius.lg)),
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.s4),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.s2),
        ) {
            Text(
                text  = "SHEETS SAVED",
                style = AppTypography.Eyebrow,
                color = accentTint,
            )
            Text(
                text     = value,
                style    = AppTypography.StatNumber.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Serif),
                color    = AppColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
            Text(
                text  = "paper not printed",
                style = AppTypography.Tag,
                color = AppColors.TextTertiary,
            )
        }

        TreeSilhouette(
            modifier = Modifier
                .size(width = 56.dp, height = 52.dp)
                .offset(x = 6.dp, y = 6.dp)
                .align(Alignment.BottomEnd)
        )
    }
}

// MARK: - FOREST tile

@Composable
private fun ForestTile(
    impact: ReleafImpact,
    modifier: Modifier = Modifier,
) {
    val readout = impact.treeReadout
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(AppRadius.lg))
            .background(AppColors.CardSolid)
            .border(1.dp, AppColors.BorderDefault, RoundedCornerShape(AppRadius.lg)),
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.s4),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.s2),
        ) {
            Text(
                text  = "FOREST",
                style = AppTypography.Eyebrow,
                color = AppColors.TextSecondary,
            )
            Text(
                text     = readout.number,
                style    = AppTypography.StatNumber.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Serif),
                color    = AppColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
            Text(
                text  = readout.unit,
                style = AppTypography.Tag,
                color = AppColors.TextTertiary,
            )
            ForestProgressDots(treeFraction = impact.treeFraction)
        }
    }
}

// MARK: - Five-segment progress dotline

private const val SegmentCount = 5

@Composable
private fun ForestProgressDots(treeFraction: Double) {
    Row(
        modifier              = Modifier.fillMaxWidth().padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        for (idx in 0 until SegmentCount) {
            val threshold = (idx + 1).toDouble() / SegmentCount.toDouble()
            val color = when {
                treeFraction >= threshold && threshold == 1.0 -> AppColors.Green
                treeFraction >= threshold && threshold >= 0.8 -> AppColors.ThemeGreenDeep
                treeFraction >= threshold                     -> AppColors.ThemeGreenPrimary
                else                                          -> AppColors.ThemeGreenPrimary.copy(alpha = 0.25f)
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(5.dp)
                    .clip(CircleShape)
                    .background(color)
            )
        }
    }
}

// MARK: - Tree silhouette

@Composable
private fun TreeSilhouette(modifier: Modifier = Modifier) {
    val trunk      = Color(0xFF8B7355)
    val midGreen   = AppColors.ThemeGreenPrimary
    val deepGreen  = AppColors.ThemeGreenDeep
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        // Trunk
        drawRoundRect(
            color    = trunk,
            topLeft  = Offset(w * 0.46f, h * 0.62f),
            size     = Size(w * 0.08f, h * 0.34f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5f, 1.5f),
        )
        // Canopy back
        drawOval(
            color   = midGreen,
            topLeft = Offset(w * 0.10f, h * 0.05f),
            size    = Size(w * 0.80f, h * 0.65f),
        )
        // Canopy right
        drawOval(
            color   = deepGreen,
            topLeft = Offset(w * 0.45f, 0f),
            size    = Size(w * 0.55f, h * 0.50f),
        )
        // Canopy left
        drawOval(
            color   = deepGreen,
            topLeft = Offset(0f, h * 0.05f),
            size    = Size(w * 0.50f, h * 0.45f),
        )
    }
}

// MARK: - Preview

@Preview(showBackground = true, backgroundColor = 0xFFF5EEDF, widthDp = 360)
@Composable
private fun ReLeafStripPreview() {
    Column(
        modifier              = Modifier.padding(AppSpacing.s4).background(AppColors.Canvas),
        verticalArrangement   = Arrangement.spacedBy(AppSpacing.s4),
    ) {
        ReLeafStrip(
            impact = ReleafImpact.from(
                photos = 2, voiceNotes = 1, todoItems = 1,
                scans = 2, contacts = 1, places = 1, notes = 1,
            )
        )
        ReLeafStrip(
            impact = ReleafImpact.from(
                photos = 84, voiceNotes = 24, todoItems = 47,
                scans = 52, contacts = 8, places = 14, notes = 38,
            )
        )
    }
}
