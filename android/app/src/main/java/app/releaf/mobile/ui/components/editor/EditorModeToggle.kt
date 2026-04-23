/*
 * EditorModeToggle.kt
 *
 * Binary Edit ⇄ Overview mode for the notepad and notebook-page
 * editors, surfaced as a list/grid icon pair. Designed to live in each
 * editor's top bar (between Back and Delete) so it doesn't claim any
 * vertical space.
 *
 *   - EDIT     — list icon, single-scroll editor (rich text + inline sections).
 *   - OVERVIEW — grid icon, CaptureTabBar layout matching PageDetailView.
 *
 * The currently-selected mode gets a coral-filled pill background; the
 * other icon renders as a plain coral glyph on the canvas.
 */

package app.releaf.mobile.ui.components.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppAccent
import app.releaf.mobile.ui.theme.AppSpacing

enum class EditorMode { EDIT, OVERVIEW }

/**
 * Compact two-icon toggle. Tap either icon to switch modes; the active
 * one is visibly filled. Used by both the notepad and notebook-page
 * editor top bars.
 */
@Composable
fun EditorModeIconToggle(
    mode: EditorMode,
    onChange: (EditorMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ModeIcon(
            icon         = Icons.AutoMirrored.Filled.ViewList,
            description  = "Edit mode",
            isActive     = mode == EditorMode.EDIT,
            onClick      = { onChange(EditorMode.EDIT) },
        )
        ModeIcon(
            icon         = Icons.Filled.GridView,
            description  = "Overview mode",
            isActive     = mode == EditorMode.OVERVIEW,
            onClick      = { onChange(EditorMode.OVERVIEW) },
        )
    }
}

@Composable
private fun ModeIcon(
    icon: ImageVector,
    description: String,
    isActive: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isActive) AppAccent.primary else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector        = icon,
            contentDescription = description,
            tint               = if (isActive) AppColors.OnAccent else AppAccent.primary,
            modifier           = Modifier.size(18.dp),
        )
    }
}
