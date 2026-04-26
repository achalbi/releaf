/*
 * PageHeaderControls.kt
 *
 * Three small components that compose the top zone of PageDetailScreen:
 *
 *   - LeafEyebrow         — leaf glyph + tracked uppercase label
 *   - PageViewToggle      — two-tab segmented pill (list / grid)
 *   - PageOverflowButton  — round kebab button
 *
 * Decorative for now — the toggle is wired to a state holder so the
 * parent tracks the view mode, but routing list-vs-grid to actual
 * layouts is a follow-up. Same for the overflow: it accepts an action
 * lambda but the action sheet hasn't been designed yet.
 *
 * Mirrors the iOS `PageHeaderControls.swift`.
 */

package app.releaf.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.releaf.mobile.ui.theme.AppAccent
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppRadius
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography

// ---------- LeafEyebrow ----------

@Composable
fun LeafEyebrow(
    label: String,
    modifier: Modifier = Modifier,
    glyphTint: androidx.compose.ui.graphics.Color? = null,
    labelTint: androidx.compose.ui.graphics.Color? = null,
) {
    Row(
        modifier              = modifier,
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s1),
    ) {
        LeafDropletGlyph(
            tint = glyphTint ?: AppColors.ThemeGreenPrimary,
            size = 11.dp,
        )
        Text(
            text     = label.uppercase(),
            style    = AppTypography.Eyebrow,
            color    = labelTint ?: AppColors.ThemeGreenDeep,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------- PageViewMode ----------

enum class PageViewMode(
    val displayName: String,
    val icon: ImageVector,
) {
    List("List", Icons.AutoMirrored.Outlined.FormatListBulleted),
    Grid("Grid", Icons.Filled.GridView),
}

// ---------- PageViewToggle ----------

@Composable
fun PageViewToggle(
    selected: PageViewMode,
    onSelect: (PageViewMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(AppRadius.pill))
            .background(AppColors.CardSolid.copy(alpha = 0.6f))
            .border(1.dp, AppColors.BorderDefault, RoundedCornerShape(AppRadius.pill))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment     = Alignment.CenterVertically,
    ) {
        PageViewMode.values().forEach { mode ->
            ViewToggleSegment(
                mode       = mode,
                isSelected = selected == mode,
                onClick    = { onSelect(mode) },
            )
        }
    }
}

@Composable
private fun ViewToggleSegment(
    mode: PageViewMode,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (isSelected) AppColors.OnAccent else AppColors.TextSecondary
    val bg   = if (isSelected) AppAccent.primary else androidx.compose.ui.graphics.Color.Transparent

    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(bg)
            .clickable(onClick = onClick)
            .width(28.dp)
            .height(22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector        = mode.icon,
            contentDescription = mode.displayName,
            tint               = tint,
            modifier           = Modifier.size(13.dp),
        )
    }
}

// ---------- PageOverflowButton ----------

/**
 * Round overflow button that opens a Material3 [DropdownMenu] of the
 * parent's items. Caller provides the items as a `ColumnScope`
 * content lambda — typically a stack of [androidx.compose.material3.DropdownMenuItem]
 * rows. Anchor + expanded state are managed inside the component so
 * the call site only ever has to think about the actions.
 */
@Composable
fun PageOverflowButton(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(AppColors.CardSolid.copy(alpha = 0.6f))
                .border(1.dp, AppColors.BorderDefault, CircleShape)
                .clickable { expanded = true }
                .size(28.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector        = Icons.Filled.MoreVert,
                contentDescription = "More",
                tint               = AppColors.TextPrimary,
                modifier           = Modifier.size(15.dp),
            )
        }
        DropdownMenu(
            expanded         = expanded,
            onDismissRequest = { expanded = false },
            content          = content,
        )
    }
}

// ---------- Preview ----------

@Preview(showBackground = true, backgroundColor = 0xFFF5EEDF, widthDp = 360)
@Composable
private fun PageHeaderControlsPreview() {
    val mode = remember { mutableStateOf(PageViewMode.Grid) }
    Row(
        modifier              = Modifier.padding(AppSpacing.s4),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2),
    ) {
        LeafEyebrow("notepad · today", modifier = Modifier.padding(end = AppSpacing.s4))
        PageViewToggle(selected = mode.value, onSelect = { mode.value = it })
        PageOverflowButton {
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("Move to notebook") },
                onClick = {},
            )
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("Apply template") },
                onClick = {},
            )
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("Archive page") },
                onClick = {},
            )
        }
    }
}
