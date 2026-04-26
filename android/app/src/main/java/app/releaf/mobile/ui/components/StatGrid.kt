/*
 * StatGrid.kt
 *
 * 3-column dashboard glance: giant number + eyebrow label per cell.
 * Stays 3-up on narrow mobile widths by design. Numbers shrink when long;
 * the layout never collapses to 2 columns.
 *
 * Ported from Inkcreate mobile DS.
 */

package app.releaf.mobile.ui.components

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppAccent
import app.releaf.mobile.ui.theme.AppRadius
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography

// ---------- Item model ----------

enum class StatTone { Neutral, Coral, Green, Info }

data class StatItem(
    val label: String,
    val value: String,
    val tone: StatTone = StatTone.Neutral,
    val key: String = label,
    /** Optional tap handler. When non-null the card gets a ripple +
     *  `clickable` modifier. Null-default so read-only stat grids
     *  (home dashboard, archive) don't pick up unintended click
     *  affordances. */
    val onClick: (() -> Unit)? = null,
    /** Optional capture mode — when set, the card carries a small
     *  leaf-droplet glyph in its top-right corner, color-keyed by
     *  [leafDropletTintFor]. Used by the page-overview AT A GLANCE
     *  grid so each tile reads as a category, not a stat. */
    val mode: CaptureMode? = null,
)

// ---------- StatGrid ----------

@Composable
fun StatGrid(
    items: List<StatItem>,
    modifier: Modifier = Modifier,
    /** Typeface for the big number on every cell. Default keeps the
     *  long-standing sans look; the page Overview opts into [FontFamily.Serif]
     *  so its AT A GLANCE tiles read editorial — same family as the
     *  page title above them. */
    valueFamily: FontFamily = FontFamily.SansSerif,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s3),
    ) {
        items.forEach { item ->
            StatCard(
                item = item,
                valueFamily = valueFamily,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// ---------- Card ----------

@Composable
private fun StatCard(
    item: StatItem,
    valueFamily: FontFamily,
    modifier: Modifier = Modifier,
) {
    val labelColor = when (item.tone) {
        StatTone.Neutral -> AppColors.TextSecondary
        StatTone.Coral   -> AppAccent.deep
        StatTone.Green   -> AppColors.Green
        StatTone.Info    -> AppColors.Info
    }

    // Treat "0", "—", or empty as a zero-count tile — fades the
    // card so the non-zero categories pop in the grid. Anything
    // numeric ≥ 1 keeps full weight.
    val trimmed = item.value.trim()
    val isEmpty = trimmed.isEmpty() || trimmed == "—" || trimmed.toIntOrNull() == 0

    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .clip(RoundedCornerShape(AppRadius.lg))
            .background(AppColors.CardSolid)
            .border(
                width = 1.dp,
                color = AppColors.BorderDefault,
                shape = RoundedCornerShape(AppRadius.lg),
            )
            // Soft fade on empty tiles. Same shape, lower visual
            // weight — the eye drifts to the active categories first.
            .alpha(if (isEmpty) 0.55f else 1f)
            .then(
                if (item.onClick != null) Modifier.clickable(onClick = item.onClick)
                else Modifier,
            ),
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.s4),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.s3),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = item.label.uppercase(),
                style = AppTypography.Eyebrow,
                color = labelColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Text(
                text = item.value,
                style = AppTypography.StatNumber.copy(fontFamily = valueFamily),
                color = if (isEmpty) AppColors.TextTertiary else AppColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }

        // Hide the droplet glyph entirely on empty tiles — those
        // categories aren't "active" yet, so a colored corner mark
        // would mis-signal use.
        if (!isEmpty && item.mode != null) {
            LeafDropletGlyph(
                tint = leafDropletTintFor(item.mode),
                modifier = Modifier
                    .align(androidx.compose.ui.Alignment.TopEnd)
                    .padding(top = AppSpacing.s3, end = AppSpacing.s3),
            )
        }
    }
}

// ---------- StatList ----------

/**
 * Vertical companion to [StatGrid]. Renders the same `List<StatItem>`
 * as full-width rows — droplet glyph + label + value — in a single
 * card. Used by the page Overview when the user flips the view
 * toggle to list mode; the stat data is identical, only the
 * presentation density changes.
 */
@Composable
fun StatList(
    items: List<StatItem>,
    modifier: Modifier = Modifier,
    valueFamily: FontFamily = FontFamily.SansSerif,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.lg))
            .background(AppColors.CardSolid)
            .border(1.dp, AppColors.BorderDefault, RoundedCornerShape(AppRadius.lg)),
    ) {
        Column {
            items.forEachIndexed { index, item ->
                StatRow(item = item, valueFamily = valueFamily)
                if (index < items.size - 1) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(0.5.dp)
                            .background(AppColors.BorderDefault)
                    )
                }
            }
        }
    }
}

@Composable
private fun StatRow(
    item: StatItem,
    valueFamily: FontFamily,
) {
    val labelColor = when (item.tone) {
        StatTone.Neutral -> AppColors.TextSecondary
        StatTone.Coral   -> AppAccent.deep
        StatTone.Green   -> AppColors.Green
        StatTone.Info    -> AppColors.Info
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.s4, vertical = AppSpacing.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (item.mode != null) {
            LeafDropletGlyph(
                tint     = leafDropletTintFor(item.mode),
                size     = 13.dp,
                modifier = Modifier.padding(end = AppSpacing.s3),
            )
        }
        Text(
            text     = item.label.uppercase(),
            style    = AppTypography.Eyebrow,
            color    = labelColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text     = item.value,
            style    = AppTypography.SectionTitle.copy(fontFamily = valueFamily),
            color    = AppColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
    }
}

// ---------- Preview ----------

@Preview(showBackground = true, backgroundColor = 0xFFF5EEE3, widthDp = 390)
@Composable
private fun StatGridPreview() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.Canvas)
            .padding(AppSpacing.s4),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s4),
    ) {
        StatGrid(
            items = listOf(
                StatItem("Notebooks", "4", StatTone.Coral),
                StatItem("Pages",     "3", StatTone.Neutral),
                StatItem("Today",     "0", StatTone.Green),
            ),
        )
        StatGrid(
            items = listOf(
                StatItem("Photos",   "128",  StatTone.Coral),
                StatItem("Contacts", "42",   StatTone.Info),
                StatItem("Minutes",  "2.4k", StatTone.Neutral),
            ),
        )
    }
}
