/*
 * StoryItemMenuSheet.kt
 *
 * Per-item ⋯ menu from §7.3b. Mirror of iOS `StoryItemMenuSheet.swift`.
 */

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.quickink.mobile.features.stories

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
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.quickink.mobile.data.storyitem.StoryItemEntity
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkRadius
import app.quickink.mobile.ui.theme.QuickInkSpacing
import kotlinx.coroutines.delay

@Composable
fun StoryItemMenuSheet(
    item: StoryItemEntity,
    isCoverItem: Boolean,
    onSetAsCover: () -> Unit,
    onLayoutChange: (StoryItemEntity.Layout) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current
    val sheetState = rememberModalBottomSheetState()
    var stubToast by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(stubToast) {
        if (stubToast != null) {
            delay(1_800)
            stubToast = null
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        containerColor   = colors.surface,
    ) {
        Column(modifier = Modifier.padding(bottom = QuickInkSpacing.s4)) {
            // Item preview
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = QuickInkSpacing.s4, vertical = QuickInkSpacing.s2)
                    .border(0.5.dp, colors.borderSoft, RoundedCornerShape(0.dp)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3),
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.paper1),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(previewTitle(item), style = type.editorial, color = colors.ink, maxLines = 1)
                    Text(previewSubtitle(item), style = type.bodyItalic, color = colors.inkSoft, maxLines = 1)
                }
            }
            Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(colors.borderSoft))

            ActionRow(icon = Icons.Outlined.Edit, label = "Edit caption",
                onClick = { stubToast = "Tap a caption inline to edit" })
            ActionRow(
                icon = if (isCoverItem) Icons.Filled.Star else Icons.Outlined.StarBorder,
                label = if (isCoverItem) "Cover item" else "Set as cover",
                onClick = onSetAsCover,
            )

            // Layout pills
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = QuickInkSpacing.s4, vertical = QuickInkSpacing.s2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.GridView, contentDescription = null, tint = colors.inkSoft, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(QuickInkSpacing.s2))
                Text("Layout", style = type.body, color = colors.ink)
                Spacer(modifier = Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    LayoutPill(label = "Full",  selected = item.layout == StoryItemEntity.Layout.FULL.raw,  onClick = { onLayoutChange(StoryItemEntity.Layout.FULL) })
                    LayoutPill(label = "Half",  selected = item.layout == StoryItemEntity.Layout.HALF.raw,  onClick = { onLayoutChange(StoryItemEntity.Layout.HALF) })
                    LayoutPill(label = "Grid",  selected = item.layout == StoryItemEntity.Layout.GRID.raw,  onClick = { onLayoutChange(StoryItemEntity.Layout.GRID) })
                }
            }

            Divider()
            ActionRow(icon = Icons.Filled.Refresh, label = "Replace with another item",
                onClick = { stubToast = "Replace coming soon" })
            ActionRow(icon = Icons.Filled.ArrowUpward,   label = "Move up",   onClick = onMoveUp)
            ActionRow(icon = Icons.Filled.ArrowDownward, label = "Move down", onClick = onMoveDown)
            Divider()
            ActionRow(icon = Icons.Outlined.Delete, label = "Remove from story",
                tint = colors.accent, onClick = onRemove)

            if (stubToast != null) {
                Text(stubToast!!, style = type.bodyItalic, color = colors.inkSoft,
                    modifier = Modifier.padding(horizontal = QuickInkSpacing.s4, vertical = QuickInkSpacing.s2))
            }
        }
    }
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    label: String,
    tint: Color? = null,
    onClick: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current
    val effectiveTint = tint ?: colors.ink
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = QuickInkSpacing.s4, vertical = QuickInkSpacing.s2 + 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = effectiveTint, modifier = Modifier.size(20.dp).padding(end = 0.dp))
        Spacer(modifier = Modifier.width(QuickInkSpacing.s2))
        Text(label, style = type.body, color = effectiveTint)
    }
}

@Composable
private fun LayoutPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalQuickInkColors.current
    val bg = if (selected) colors.accentSoft else Color.Transparent
    val border = if (selected) colors.accent.copy(alpha = 0.4f) else colors.border
    val fg = if (selected) colors.accent else colors.inkSoft
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(QuickInkRadius.pill))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(QuickInkRadius.pill))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(label, color = fg, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Divider() {
    val colors = LocalQuickInkColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(0.5.dp)
            .background(colors.borderSoft)
            .padding(vertical = QuickInkSpacing.s1, horizontal = QuickInkSpacing.s4),
    )
}

private fun previewTitle(item: StoryItemEntity): String = when (item.kind) {
    StoryItemEntity.Kind.TEXT_BLOCK.raw        -> item.text ?: "Paragraph"
    StoryItemEntity.Kind.HANDWRITTEN_NOTE.raw  -> item.text ?: "Handwritten note"
    StoryItemEntity.Kind.DATE_DIVIDER.raw      -> item.text ?: "Date divider"
    StoryItemEntity.Kind.PLACE_PIN.raw         -> item.text ?: "Place pin"
    StoryItemEntity.Kind.VOICE_CLIP.raw        -> "Voice clip"
    else                                       -> item.caption ?: "Item"
}

private fun previewSubtitle(item: StoryItemEntity): String =
    item.caption ?: item.kind.replace('_', ' ')
