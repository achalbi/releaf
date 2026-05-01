/*
 * Breadcrumbs.kt
 *
 * Hierarchical location indicator used in place of a plain back button.
 * Each segment other than the last is tappable (acts as up-navigation);
 * the final segment renders as a muted "current location" label.
 *
 * Mobile convention is to cap at 2-3 segments — longer chains get
 * visually noisy and don't fit the top bar. Callers decide how much
 * hierarchy to show.
 */

package app.releaf.mobile.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppAccent
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography

/**
 * One crumb in a breadcrumb trail.
 *
 * @param label short text to render. Long labels get ellipsized so the
 *   trail stays on a single line.
 * @param onTap action when the crumb is clicked. `null` = terminal
 *   crumb (current location, not interactive).
 */
data class BreadcrumbSegment(
    val label: String,
    val onTap: (() -> Unit)? = null,
)

/**
 * Render the trail as `{parent} › {child} › …`. Tappable parents get
 * coral text (accent), the terminal crumb gets muted secondary text.
 *
 * Tappable segments wrap their label in a clickable Box that includes
 * generous internal padding so the touch target hits the recommended
 * 44dp on at least one axis even for short labels like "Home".
 */
@Composable
fun Breadcrumbs(
    segments: List<BreadcrumbSegment>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.defaultMinSize(minHeight = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s1),
    ) {
        segments.forEachIndexed { index, segment ->
            if (index > 0) {
                Text(
                    text  = "›",
                    style = AppTypography.Button,
                    color = AppColors.TextTertiary,
                )
            }
            val isTerminal = segment.onTap == null
            val color = if (isTerminal) AppColors.TextSecondary else AppAccent.primary
            if (isTerminal) {
                Text(
                    text     = segment.label,
                    style    = AppTypography.Button,
                    color    = color,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(vertical = AppSpacing.s2),
                )
            } else {
                // Padding inside clickable so the tap target includes
                // the gutter around the label. Without this, taps that
                // land on the small whitespace between the chevron and
                // the next label can miss the hit area entirely.
                Box(
                    modifier = Modifier
                        .clickable { segment.onTap?.invoke() }
                        .padding(
                            horizontal = AppSpacing.s2,
                            vertical   = AppSpacing.s2,
                        ),
                ) {
                    Text(
                        text     = segment.label,
                        style    = AppTypography.Button,
                        color    = color,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
