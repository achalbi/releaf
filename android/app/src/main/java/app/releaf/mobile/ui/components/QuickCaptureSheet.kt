/*
 * QuickCaptureSheet.kt
 *
 * Bottom sheet presenting the 7 capture modes as large tappable rows.
 * Opens from the CaptureFab or the center Leaf in the BottomNav.
 *
 * Uses Material 3 ModalBottomSheet but overrides surface color and corner
 * shape to match Releaf tokens (cream canvas, 20dp top corners).
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppRadius
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography
import kotlinx.coroutines.launch

// ---------- QuickCaptureSheet ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickCaptureSheet(
    onDismiss: () -> Unit,
    onSelect: (CaptureMode) -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
    modes: List<CaptureMode> = CaptureMode.entries,
) {
    val scope = rememberCoroutineScope()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = AppColors.Canvas,
        shape = RoundedCornerShape(
            topStart = 20.dp,
            topEnd = 20.dp,
            bottomStart = 0.dp,
            bottomEnd = 0.dp,
        ),
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
        ) {
            // Drag handle
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = AppSpacing.s2, bottom = AppSpacing.s3)
                    .size(width = 40.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(AppColors.BorderStrong),
            )

            // Header
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = AppSpacing.s4,
                        end = AppSpacing.s4,
                        bottom = AppSpacing.s3,
                    ),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "NEW CAPTURE",
                    style = AppTypography.Eyebrow.copy(fontWeight = FontWeight.SemiBold),
                    color = AppColors.CoralDeep,
                )
                Text(
                    text = "What do you want to add?",
                    style = AppTypography.PageTitle,
                    color = AppColors.TextPrimary,
                )
            }

            LazyColumn(
                contentPadding = PaddingValues(
                    start = AppSpacing.s4,
                    end = AppSpacing.s4,
                    bottom = AppSpacing.s6,
                ),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.s2),
            ) {
                items(modes, key = { it.name }) { mode ->
                    CaptureRow(mode = mode) {
                        onSelect(mode)
                        scope.launch { sheetState.hide() }
                            .invokeOnCompletion { onDismiss() }
                    }
                }
            }
        }
    }
}

// ---------- Row ----------

@Composable
private fun CaptureRow(mode: CaptureMode, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.md))
            .background(AppColors.CardSolid)
            .border(
                width = 1.dp,
                color = AppColors.BorderDefault,
                shape = RoundedCornerShape(AppRadius.md),
            )
            .clickable(onClick = onClick)
            .padding(
                horizontal = AppSpacing.s4,
                vertical = AppSpacing.s3,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s3),
    ) {
        IconChip(icon = mode.icon)

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = mode.title,
                style = AppTypography.Button,
                color = AppColors.TextPrimary,
            )
            Text(
                text = mode.subtitle,
                style = AppTypography.Meta,
                color = AppColors.TextSecondary,
            )
        }

        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = AppColors.TextTertiary,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun IconChip(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(AppRadius.sm))
            .background(AppColors.CoralSoft),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = AppColors.CoralDeep,
            modifier = Modifier.size(18.dp),
        )
    }
}

// ---------- Host helper ----------

/**
 * Convenience composable: wire the sheet to a simple Boolean flag.
 *
 *     var open by remember { mutableStateOf(false) }
 *     if (open) QuickCaptureSheetHost(
 *         onDismiss = { open = false },
 *         onSelect  = { mode -> ... }
 *     )
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickCaptureSheetHost(
    onDismiss: () -> Unit,
    onSelect: (CaptureMode) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    QuickCaptureSheet(
        onDismiss = onDismiss,
        onSelect = onSelect,
        sheetState = sheetState,
    )
}

// ---------- Preview ----------

@Preview(showBackground = true, backgroundColor = 0xFFF5EEE3, widthDp = 390, heightDp = 720)
@Composable
private fun QuickCaptureSheetPreview() {
    // Previews can't reliably host a real ModalBottomSheet — render the
    // contents directly against the cream canvas to validate layout.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.Canvas),
    ) {
        Spacer(Modifier.size(AppSpacing.s6))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.s4),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "NEW CAPTURE",
                style = AppTypography.Eyebrow,
                color = AppColors.CoralDeep,
            )
            Text(
                text = "What do you want to add?",
                style = AppTypography.PageTitle,
                color = AppColors.TextPrimary,
            )
        }
        Spacer(Modifier.size(AppSpacing.s3))
        Column(
            modifier = Modifier.padding(horizontal = AppSpacing.s4),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.s2),
        ) {
            CaptureMode.entries.forEach { mode ->
                CaptureRow(mode = mode, onClick = {})
            }
        }
    }
}
