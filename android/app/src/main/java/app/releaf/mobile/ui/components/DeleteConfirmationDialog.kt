/*
 * DeleteConfirmationDialog.kt
 *
 * Shared destructive-action guard. Every delete path (notebook, chapter,
 * page) routes through this before touching the repository so the user
 * always gets a "Delete / Cancel" prompt with context about what's about
 * to disappear. Cascading consequences (deleting a notebook kills its
 * chapters and pages, etc.) should be spelled out in `message`.
 */

package app.releaf.mobile.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography

@Composable
fun DeleteConfirmationDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    confirmLabel: String = "Delete",
    cancelLabel: String = "Cancel",
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                style = AppTypography.SectionTitle,
                color = AppColors.TextPrimary,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s2)) {
                Text(
                    text = message,
                    style = AppTypography.Body,
                    color = AppColors.TextSecondary,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = AppColors.Danger)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(cancelLabel, color = AppColors.TextSecondary)
            }
        },
        containerColor = AppColors.CardSolid,
    )
}
