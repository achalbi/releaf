/*
 * HomeQuickCaptureSection.kt
 * Six quick-capture pills under the library card — one per capture
 * mode the notepad supports: notes, photos, scans, voice, todos,
 * contacts. Tap routes to the notepad tab (future iteration will
 * route through to a pre-selected capture mode on the new entry).
 */

package app.releaf.mobile.features.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.releaf.mobile.ui.theme.AppAccent
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppRadius
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography
import app.releaf.mobile.ui.theme.LocalFontWeight

enum class QuickCaptureMode { Notes, Photos, Scans, Voice, Todos, Location }

private data class PillSpec(
    val mode: QuickCaptureMode,
    val label: String,
    val icon: ImageVector,
)

@Composable
fun HomeQuickCaptureSection(
    onCapture: (QuickCaptureMode) -> Unit,
    counts: Map<QuickCaptureMode, Int> = emptyMap(),
    modifier: Modifier = Modifier,
) {
    val specs = listOf(
        PillSpec(QuickCaptureMode.Notes,    "Notes",    Icons.Filled.EditNote),
        PillSpec(QuickCaptureMode.Photos,   "Photos",   Icons.Filled.CameraAlt),
        PillSpec(QuickCaptureMode.Scans,    "Scans",    Icons.Filled.DocumentScanner),
        PillSpec(QuickCaptureMode.Voice,    "Voice",    Icons.Filled.Mic),
        PillSpec(QuickCaptureMode.Todos,    "Todos",    Icons.Filled.CheckBox),
        PillSpec(QuickCaptureMode.Location, "Location", Icons.Filled.LocationOn),
    )
    val rows = specs.chunked(3)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.lg))
            .background(AppColors.CardSolid)
            .border(1.dp, AppColors.BorderDefault, RoundedCornerShape(AppRadius.lg))
            .padding(AppSpacing.s5),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s4),
    ) {
        Text(
            text = "HIGHLIGHT",
            style = AppTypography.Eyebrow,
            color = AppColors.ThemeGreenDeep,
        )
        Column {
            rows.forEachIndexed { rowIndex, row ->
                if (rowIndex > 0) {
                    HorizontalDivider(
                        thickness = 1.dp,
                        color     = AppColors.BorderDefault,
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min),
                ) {
                    row.forEachIndexed { colIndex, spec ->
                        if (colIndex > 0) {
                            VerticalDivider(
                                thickness = 1.dp,
                                color     = AppColors.BorderDefault,
                            )
                        }
                        Pill(
                            spec    = spec,
                            count   = counts[spec.mode] ?: 0,
                            onClick = { onCapture(spec.mode) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Pill(
    spec: PillSpec,
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clickable { onClick() }
            .padding(vertical = AppSpacing.s3),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s2),
    ) {
        Icon(
            imageVector = spec.icon,
            contentDescription = spec.label,
            tint = AppAccent.primary,
            modifier = Modifier.size(32.dp),
        )
        Text(
            text = spec.label.uppercase(),
            style = AppTypography.Eyebrow,
            color = AppColors.TextSecondary,
            maxLines = 1,
        )
        Text(
            text = "$count",
            color = if (count > 0) AppColors.TextPrimary else AppColors.TextTertiary,
            fontSize = 26.sp,
            fontWeight = LocalFontWeight.current,
            fontFamily = FontFamily.Serif,
            maxLines = 1,
        )
    }
}
