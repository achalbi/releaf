/*
 * ExportSheet.kt
 *
 * Bottom-sheet picker offering export destinations. Triggered from
 * `NoteEditorScreen`'s Export floating-action button. Wraps
 * Material3's `ModalBottomSheet` with QuickInk styling.
 *
 * Format options (per the mockup brief):
 *   - PDF              — searchable when the user's experimental
 *                        toggle is on.
 *   - Markdown         — `.md` with the OCR text + title.
 *   - Image            — single-page PNG/JPEG export.
 *   - Plain text       — `.txt` for clipboard / quick paste.
 *
 * Each tap fires `onSelect(format)` and dismisses. The actual
 * export pipeline lives in a follow-up.
 *
 * Mirror of iOS `ExportSheet.swift`.
 */

package app.quickink.mobile.features.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.automirrored.filled.Subject
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkRadius
import app.quickink.mobile.ui.theme.QuickInkSpacing

enum class ExportFormat(
    val label: String,
    val subtitle: String,
    val icon: ImageVector,
) {
    Pdf(
        label    = "PDF",
        subtitle = "Searchable layout · ideal for sharing",
        icon     = Icons.Filled.PictureAsPdf,
    ),
    Markdown(
        label    = "Markdown",
        subtitle = ".md with the OCR transcript as the body",
        icon     = Icons.Filled.Code,
    ),
    Image(
        label    = "Image",
        subtitle = "Single-page PNG of the captured page",
        icon     = Icons.Filled.Image,
    ),
    Plain(
        label    = "Plain text",
        subtitle = "Just the OCR text — quick paste",
        icon     = Icons.AutoMirrored.Filled.Subject,
    );
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(
    searchablePdfEnabled: Boolean,
    onSelect: (ExportFormat) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        containerColor   = colors.bg,
        dragHandle       = {
            Box(modifier = Modifier.padding(top = QuickInkSpacing.s2, bottom = QuickInkSpacing.s3)) {
                Box(
                    modifier = Modifier
                        .size(width = 40.dp, height = 4.dp)
                        .clip(RoundedCornerShape(QuickInkRadius.pill))
                        .background(colors.border)
                )
            }
        },
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = QuickInkSpacing.s5, vertical = QuickInkSpacing.s2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Export this note", style = type.heading, color = colors.ink)
                Text(
                    text  = "Pick a format — we'll generate it on this device.",
                    style = type.meta,
                    color = colors.inkSoft,
                )
            }
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(colors.borderSoft)
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector       = Icons.Filled.Close,
                    contentDescription = "Close",
                    tint              = colors.inkSoft,
                    modifier          = Modifier.size(14.dp),
                )
            }
        }

        Spacer(Modifier.size(QuickInkSpacing.s2))
        HorizontalDivider(color = colors.border, thickness = 1.dp)

        Column(
            modifier            = Modifier.padding(
                horizontal = QuickInkSpacing.s5,
                vertical   = QuickInkSpacing.s4,
            ),
            verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
        ) {
            ExportFormat.values().forEach { format ->
                FormatRow(
                    format               = format,
                    searchablePdfEnabled = searchablePdfEnabled,
                    onClick              = { onSelect(format) },
                )
            }
            // Bottom inset for the gesture bar.
            Spacer(Modifier.size(QuickInkSpacing.s4))
        }
    }
}

@Composable
private fun FormatRow(
    format: ExportFormat,
    searchablePdfEnabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    val subtitle = when {
        format == ExportFormat.Pdf && searchablePdfEnabled  ->
            "Searchable layout · OCR text layer included"
        format == ExportFormat.Pdf && !searchablePdfEnabled ->
            "Flat layout · enable Searchable PDF in Settings for OCR"
        else -> format.subtitle
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(QuickInkRadius.md))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.md))
            .clickable(onClick = onClick)
            .padding(QuickInkSpacing.s4),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(QuickInkRadius.md))
                .background(colors.accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector       = format.icon,
                contentDescription = null,
                tint              = colors.accent,
                modifier          = Modifier.size(18.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = format.label, style = type.label, color = colors.ink)
            Text(text = subtitle, style = type.caption, color = colors.muted)
        }
        Icon(
            imageVector       = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint              = colors.muted,
            modifier          = Modifier.size(14.dp),
        )
    }
}
