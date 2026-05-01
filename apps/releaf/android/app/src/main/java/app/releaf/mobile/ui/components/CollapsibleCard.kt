/*
 * CollapsibleCard.kt
 *
 * Reusable card with a header row (title, optional subtitle, trailing
 * actions, collapse chevron) and a body slot that hides when the card is
 * collapsed. The notebook hero, the Chapters section, the Pages section,
 * and the Current / Archive notebook lists all share this shape.
 *
 * The header's trailing-action slot is a small Row the caller fills with
 * `RoundIconButton` instances — this lets every card's chrome compose
 * without a bespoke data model per surface.
 */

package app.releaf.mobile.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.releaf.mobile.ui.theme.AppAccent
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppRadius
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography

/**
 * @param title header title
 * @param subtitle optional secondary line under the title
 * @param expanded current state — hoisted so callers can persist it across
 *   config changes via `rememberSaveable`.
 * @param onToggle flip the collapse chevron. Callers can ignore and leave
 *   the card permanently expanded by passing `onToggle = {}`.
 * @param trailing extra action slot rendered to the left of the chevron;
 *   typically one or two `RoundIconButton`s.
 * @param divider when true, draw a hairline between the header and body.
 * @param body expanded content.
 */
@Composable
fun CollapsibleCard(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    divider: Boolean = true,
    /** Style override for the card title. Defaults to the heavy
     *  [AppTypography.SectionTitle]; pass a lighter variant when the
     *  card is grouping list items rather than headlining a section. */
    titleStyle: androidx.compose.ui.text.TextStyle = AppTypography.SectionTitle,
    /** When false, the chevron expand/collapse affordance in the
     *  header is omitted. Useful for surfaces that should always
     *  render their body (the shelf cards on the library tab). The
     *  card still honors [expanded] so callers can hide the body
     *  programmatically if they want. */
    showCollapseToggle: Boolean = true,
    body: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.lg))
            .background(AppColors.CardSolid)
            .border(
                width = 1.dp,
                color = AppColors.BorderDefault,
                shape = RoundedCornerShape(AppRadius.lg),
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start  = AppSpacing.s4,
                    end    = AppSpacing.s3,
                    top    = AppSpacing.s3,
                    bottom = AppSpacing.s3,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = titleStyle,
                    color = AppColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = AppTypography.Meta,
                        color = AppColors.TextTertiary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (trailing != null) trailing()
            if (showCollapseToggle) {
                RoundIconButton(
                    icon = if (expanded) Icons.Filled.KeyboardArrowUp
                           else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    onClick = onToggle,
                )
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (divider) HairlineDivider()
                body()
            }
        }
    }
}

@Composable
fun HairlineDivider(color: Color = AppColors.BorderDefault) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(color),
    )
}

/**
 * Compact pill variant used for meta pills like "1 page", "Ch. 1",
 * "Order: 1". Sits on `AppColors.Subtle` by default; pass `accent = true`
 * for a coral-soft fill used by the "N active notebooks" counter.
 */
@Composable
fun MetaPill(
    text: String,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
) {
    val fill = if (accent) AppAccent.soft else AppColors.Subtle
    val content = if (accent) AppAccent.deep else AppColors.TextSecondary
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .clip(RoundedCornerShape(AppRadius.pill))
            .background(fill)
            .padding(horizontal = AppSpacing.s3, vertical = 4.dp),
    ) {
        Text(text = text, style = AppTypography.Tag, color = content)
    }
}
