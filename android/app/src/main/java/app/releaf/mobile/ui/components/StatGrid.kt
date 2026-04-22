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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.releaf.mobile.ui.theme.AppColors
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
)

// ---------- StatGrid ----------

@Composable
fun StatGrid(
    items: List<StatItem>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s3),
    ) {
        items.forEach { item ->
            StatCard(
                item = item,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// ---------- Card ----------

@Composable
private fun StatCard(
    item: StatItem,
    modifier: Modifier = Modifier,
) {
    val labelColor = when (item.tone) {
        StatTone.Neutral -> AppColors.TextSecondary
        StatTone.Coral   -> AppColors.CoralDeep
        StatTone.Green   -> AppColors.Green
        StatTone.Info    -> AppColors.Info
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(AppRadius.lg))
            .background(AppColors.CardSolid)
            .border(
                width = 1.dp,
                color = AppColors.BorderDefault,
                shape = RoundedCornerShape(AppRadius.lg),
            )
            .padding(AppSpacing.s4),
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
            style = AppTypography.StatNumber,
            color = AppColors.TextPrimary,
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
