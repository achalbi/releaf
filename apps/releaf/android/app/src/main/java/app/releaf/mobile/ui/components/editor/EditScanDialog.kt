/*
 * EditScanDialog.kt
 *
 * Tiny editor for a scan attachment's title + category. Opened from
 * the ScanRow's overflow menu. The user types a new title into a
 * single-line text field and taps a category chip to pick one of the
 * seven [ScanCategory] values. Save fires back through
 * `onSave(id, title, categoryId)` which the screen wires to
 * `viewModel.updateScan(…)`.
 *
 * Clearing the title field + saving clears the override, so the list
 * falls back to the OCR-derived title. "None" isn't an option on the
 * category picker — scans always belong to one of the seven buckets;
 * the default / fallback is GENERAL.
 */

package app.releaf.mobile.ui.components.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import app.releaf.mobile.data.notebook.Attachment
import app.releaf.mobile.data.notebook.ScanCategory
import app.releaf.mobile.ui.theme.AppAccent
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppRadius
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun EditScanDialog(
    attachment: Attachment,
    /** Category currently applied (may be the derived or overridden one). */
    currentCategory: ScanCategory,
    onSave: (id: String, title: String?, categoryId: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    // Seed the title field with the current override OR the derived
    // title fallback the list already shows — keeps "what you see is
    // what you edit" honest.
    var title by remember(attachment.id) {
        mutableStateOf(attachment.title.orEmpty())
    }
    var category by remember(attachment.id) {
        mutableStateOf(currentCategory)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title            = { Text("Edit scan") },
        text             = {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s3)) {
                Text(
                    text  = "TITLE",
                    style = AppTypography.Eyebrow,
                    color = AppColors.TextSecondary,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(AppRadius.md))
                        .background(AppColors.InputBg)
                        .border(
                            width = 1.dp,
                            color = AppColors.BorderDefault,
                            shape = RoundedCornerShape(AppRadius.md),
                        )
                        .padding(horizontal = AppSpacing.s3, vertical = AppSpacing.s2),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (title.isEmpty()) {
                        Text(
                            text  = "Scan",
                            style = AppTypography.Body,
                            color = AppColors.TextTertiary,
                        )
                    }
                    BasicTextField(
                        value         = title,
                        onValueChange = { title = it },
                        singleLine    = true,
                        textStyle     = AppTypography.Body.copy(color = AppColors.TextPrimary),
                        cursorBrush   = SolidColor(AppAccent.primary),
                        modifier      = Modifier.fillMaxWidth(),
                    )
                }

                Spacer(Modifier.size(AppSpacing.s1))
                Text(
                    text  = "CATEGORY",
                    style = AppTypography.Eyebrow,
                    color = AppColors.TextSecondary,
                )
                FlowRow(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2),
                    verticalArrangement   = Arrangement.spacedBy(AppSpacing.s2),
                ) {
                    ScanCategory.entries.forEach { cat ->
                        CategoryPickChip(
                            category = cat,
                            selected = cat == category,
                            onClick  = { category = cat },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // Blank title → clear the override (null) so the list
                // falls back to the OCR-derived name.
                val trimmed = title.trim().takeIf { it.isNotBlank() }
                onSave(attachment.id, trimmed, category.name)
            }) {
                Text("Save", color = AppAccent.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = AppColors.TextSecondary)
            }
        },
    )
}

@Composable
private fun CategoryPickChip(
    category: ScanCategory,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (selected) AppAccent.primary else AppColors.NeutralSoft
    val fg = if (selected) AppColors.OnAccent else AppColors.Neutral
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(AppRadius.pill))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = AppSpacing.s3, vertical = AppSpacing.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text  = category.label,
            style = AppTypography.Tag,
            color = fg,
        )
    }
}
