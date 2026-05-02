/*
 * PageEditorScreen.kt
 *
 * Page-editing surface — re-crop, rotate, retake. Reachable from
 * `ScanReviewScreen` after a capture completes (before the user
 * taps Done) and from `NoteEditorScreen`'s page tab when the user
 * wants to revise an existing scan.
 *
 * Wireframe today — the actual image transform pipeline (Bitmap
 * rotation, draggable crop corners, page re-capture via
 * GmsDocumentScanning) lands in a follow-up. Shipping the picker
 * chrome now means the route + theme + control surface are
 * settled when transforms slot in.
 *
 * Mirror of iOS `PageEditorScreen.swift`.
 */

package app.quickink.mobile.features.scan

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkRadius
import app.quickink.mobile.ui.theme.QuickInkSpacing

private enum class EditorTool { Crop, Rotate, Retake }

@Composable
fun PageEditorScreen(
    onCancel: () -> Unit,
    onSave: () -> Unit,
    onRetake: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current

    var rotationDeg by remember { mutableFloatStateOf(0f) }
    var activeTool by remember { mutableStateOf(EditorTool.Crop) }
    val animatedRotation by animateFloatAsState(targetValue = rotationDeg, label = "page-rotation")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg),
    ) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = QuickInkSpacing.s2, vertical = QuickInkSpacing.s2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text     = "Cancel",
                style    = type.label,
                color    = colors.inkSoft,
                modifier = Modifier
                    .clickable(onClick = onCancel)
                    .padding(QuickInkSpacing.s3),
            )
            Spacer(Modifier.weight(1f))
            Text(text = "Edit page", style = type.label, color = colors.ink)
            Spacer(Modifier.weight(1f))
            Text(
                text     = "Save",
                style    = type.label,
                color    = colors.accent,
                modifier = Modifier
                    .clickable(onClick = onSave)
                    .padding(QuickInkSpacing.s3),
            )
        }

        // Page canvas
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(QuickInkSpacing.s5),
            contentAlignment = Alignment.Center,
        ) {
            // Placeholder lined-paper page (real captured image
            // slots in here once the scan pipeline hands a Uri
            // through).
            var canvasSize by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { coords ->
                        canvasSize = androidx.compose.ui.geometry.Size(
                            coords.size.width.toFloat(),
                            coords.size.height.toFloat(),
                        )
                    }
                    .shadow(8.dp, RoundedCornerShape(QuickInkRadius.md))
                    .clip(RoundedCornerShape(QuickInkRadius.md))
                    .background(colors.surface)
                    .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.md))
                    .rotate(animatedRotation),
                contentAlignment = Alignment.Center,
            ) {
                // Lined-paper rules + coral margin.
                androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    val lineColor = colors.ink.copy(alpha = 0.10f)
                    val spacing = 16.dp.toPx()
                    var y = spacing
                    while (y < size.height) {
                        drawLine(
                            color       = lineColor,
                            start       = Offset(0f, y),
                            end         = Offset(size.width, y),
                            strokeWidth = 0.5f,
                        )
                        y += spacing
                    }
                }
                Box(
                    modifier = Modifier
                        .padding(start = 32.dp, top = 16.dp, bottom = 16.dp)
                        .width(1.5.dp)
                        .fillMaxSize()
                        .background(colors.accent.copy(alpha = 0.6f)),
                )
                Text(
                    text  = "scanned page",
                    style = type.handwritten.copy(fontSize = 28.sp),
                    color = colors.inkSoft.copy(alpha = 0.7f),
                )
            }

            // Crop handles overlay — only when crop tool active.
            if (activeTool == EditorTool.Crop) {
                CropHandle(modifier = Modifier.align(Alignment.TopStart).padding(16.dp))
                CropHandle(modifier = Modifier.align(Alignment.TopEnd).padding(16.dp))
                CropHandle(modifier = Modifier.align(Alignment.BottomStart).padding(16.dp))
                CropHandle(modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp))
            }
        }

        // Tool strip (segmented)
        Row(
            modifier = Modifier
                .padding(horizontal = QuickInkSpacing.s5)
                .fillMaxWidth()
                .clip(RoundedCornerShape(QuickInkRadius.pill))
                .background(colors.borderSoft)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ToolPill(
                kind     = EditorTool.Crop,
                icon     = Icons.Filled.Crop,
                label    = "Crop",
                active   = activeTool == EditorTool.Crop,
                onClick  = { activeTool = EditorTool.Crop },
                modifier = Modifier.weight(1f),
            )
            ToolPill(
                kind     = EditorTool.Rotate,
                icon     = Icons.Filled.RotateRight,
                label    = "Rotate",
                active   = activeTool == EditorTool.Rotate,
                onClick  = {
                    activeTool = EditorTool.Rotate
                    rotationDeg = (rotationDeg + 90f) % 360f
                },
                modifier = Modifier.weight(1f),
            )
            ToolPill(
                kind     = EditorTool.Retake,
                icon     = Icons.Filled.Refresh,
                label    = "Retake",
                active   = activeTool == EditorTool.Retake,
                onClick  = {
                    activeTool = EditorTool.Retake
                    onRetake()
                },
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.size(QuickInkSpacing.s4))

        // Action hint row
        Row(
            modifier              = Modifier
                .fillMaxWidth()
                .padding(horizontal = QuickInkSpacing.s5)
                .padding(bottom = QuickInkSpacing.s7),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector       = Icons.Filled.Info,
                contentDescription = null,
                tint              = colors.muted,
                modifier          = Modifier.size(12.dp),
            )
            Spacer(Modifier.size(QuickInkSpacing.s1))
            Text(
                text  = "Drag the corners to crop · Rotate steps 90°",
                style = type.caption,
                color = colors.muted,
            )
        }
    }
}

@Composable
private fun CropHandle(modifier: Modifier = Modifier) {
    val colors = LocalQuickInkColors.current
    Box(
        modifier = modifier
            .shadow(6.dp, CircleShape)
            .size(14.dp)
            .clip(CircleShape)
            .background(colors.accent)
    )
}

@Composable
private fun ToolPill(
    kind: EditorTool,
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(QuickInkRadius.pill))
            .background(if (active) colors.surface else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = QuickInkSpacing.s2),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector       = icon,
            contentDescription = null,
            tint              = if (active) colors.ink else colors.inkSoft,
            modifier          = Modifier.size(13.dp),
        )
        Spacer(Modifier.size(6.dp))
        Text(
            text  = label,
            style = type.label,
            color = if (active) colors.ink else colors.inkSoft,
        )
    }
}
