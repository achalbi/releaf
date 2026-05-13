/*
 * FolderPickerSheet.kt
 *
 * Bottom sheet that lists the user's active folders and lets them
 * pick one for a single capture. Phase B.2 — move-capture-to-folder.
 *
 * Used today from ScanDetailScreen's Actions card; reusable
 * anywhere a "move this capture" affordance lands later (folder
 * detail bulk-select, swipe action on the folder list, etc).
 *
 * The sheet writes via [CaptureDao.setFolder] directly — no
 * intermediate repository — because the operation is a single row
 * UPDATE and the row gets dirty-flagged for the next sync push.
 * Higher-level orchestration can move to a [FolderRepository]
 * method when a second caller exists.
 */

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.quickink.mobile.features.workspace

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.quickink.mobile.data.folder.FolderEntity
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkSpacing

/**
 * Folder picker bottom sheet.
 *
 * @param folders           Active folders to display, ordered by
 *                          position. Caller observes them from
 *                          [FolderDao.observeActive].
 * @param currentFolderId   Folder the capture is currently in;
 *                          renders a check on the matching row.
 *                          Null is fine — no row shows the check.
 * @param onDismiss         Tap-outside / drag-down close.
 * @param onPickFolder      Tapping a folder row. The sheet stays
 *                          open until the caller dismisses (so the
 *                          caller can confirm or perform async work
 *                          before tearing down the UI).
 */
@Composable
fun FolderPickerSheet(
    folders: List<FolderEntity>,
    currentFolderId: String?,
    onDismiss: () -> Unit,
    onPickFolder: (FolderEntity) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        containerColor   = colors.surface,
    ) {
        Column(modifier = Modifier.padding(horizontal = QuickInkSpacing.s4, vertical = QuickInkSpacing.s2)) {
            Text(
                text  = "Move to folder",
                style = type.body.copy(fontWeight = FontWeight.SemiBold, fontSize = 17.sp),
                color = colors.ink,
                modifier = Modifier.padding(vertical = QuickInkSpacing.s2),
            )
            Text(
                text  = "Each capture lives in one folder. Pick a destination — the capture moves immediately.",
                style = type.meta,
                color = colors.muted,
                modifier = Modifier.padding(bottom = QuickInkSpacing.s2),
            )

            HorizontalDivider(color = colors.borderSoft)

            folders.forEach { folder ->
                FolderPickerRow(
                    folder    = folder,
                    isCurrent = folder.id == currentFolderId,
                    onClick   = { onPickFolder(folder) },
                )
            }

            if (folders.isEmpty()) {
                Text(
                    text  = "No folders yet. Create one from the Workspace home.",
                    style = type.meta,
                    color = colors.muted,
                    modifier = Modifier.padding(vertical = QuickInkSpacing.s3),
                )
            }

            Spacer(Modifier.height(QuickInkSpacing.s3))
        }
    }
}

@Composable
private fun FolderPickerRow(
    folder: FolderEntity,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(parseFolderColor(folder.color)),
        )
        Spacer(Modifier.width(QuickInkSpacing.s3))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text  = folder.name,
                style = type.body.copy(fontWeight = FontWeight.Medium, fontSize = 14.sp),
                color = colors.ink,
            )
            if (folder.isDefault) {
                Text(
                    text  = "Default",
                    style = type.meta.copy(fontSize = 11.sp),
                    color = colors.muted,
                )
            }
        }
        if (isCurrent) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = "Current folder",
                tint = colors.accent,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
