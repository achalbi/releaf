/*
 * NoteEditorScreen.kt
 *
 * QuickInk's note editor — uses `NoteEditorController` for state.
 * Plain `BasicTextField` / `OutlinedTextField` for the body;
 * RichTextFormatBar wiring is a Slice 5 polish item.
 *
 * Mirror of iOS `NoteEditorScreen.swift`.
 */

package app.quickink.mobile.features.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.SolidColor
import app.quickink.mobile.QuickInkApp
import app.quickink.mobile.data.sync.QuickInkSyncScheduler
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography

@Composable
fun NoteEditorScreen(
    entryId: String,
    userId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val app     = context.applicationContext as QuickInkApp
    val scope   = rememberCoroutineScope()

    val controller = remember(entryId) {
        NoteEditorController(
            entryId   = entryId,
            userId    = userId,
            dao       = app.database.notepadDao(),
            scope     = scope,
            // Slice 4.2c — kick a one-shot sync after each save /
            // soft-delete so the user's edit reaches Drive in
            // seconds. The worker no-ops when Drive backup is off
            // (per QuickInkSyncWorker's gate) so this is safe to
            // fire unconditionally.
            onMutated = { QuickInkSyncScheduler.requestImmediate(app) },
        )
    }

    LaunchedEffect(controller) {
        controller.bootstrap()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.Canvas),
    ) {
        TopBar(
            showDelete = controller.entry != null,
            onBack     = {
                // Save-on-back matches iOS + Releaf's editor flow
                // — no separate Save button.
                if (controller.canSave) controller.save()
                onBack()
            },
            onDelete   = { controller.delete(onDeleted = onBack) },
        )

        if (controller.isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AppColors.ThemeGreenPrimary)
            }
        } else {
            EditorBody(controller)
        }
    }
}

@Composable
private fun TopBar(
    showDelete: Boolean,
    onBack: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.s2, vertical = AppSpacing.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector  = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint         = AppColors.TextPrimary,
            )
        }
        Spacer(Modifier.weight(1f))
        if (showDelete) {
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector  = Icons.Filled.Delete,
                    contentDescription = "Delete note",
                    tint         = AppColors.CoralDeep,
                )
            }
        }
    }
}

@Composable
private fun EditorBody(controller: NoteEditorController) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = AppSpacing.s5),
    ) {
        BasicTextField(
            value         = controller.title,
            onValueChange = { controller.title = it },
            textStyle     = AppTypography.PageTitle.copy(color = AppColors.TextPrimary),
            cursorBrush   = SolidColor(AppColors.ThemeGreenPrimary),
            singleLine    = true,
            modifier      = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                if (controller.title.isEmpty()) {
                    Text(
                        text  = "Title",
                        style = AppTypography.PageTitle,
                        color = AppColors.TextTertiary,
                    )
                }
                inner()
            },
        )

        Spacer(Modifier.padding(top = AppSpacing.s2))

        Text(
            text  = controller.entryDate,
            style = AppTypography.Meta,
            color = AppColors.TextSecondary,
        )

        Spacer(Modifier.padding(top = AppSpacing.s3))

        BasicTextField(
            value         = controller.notes,
            onValueChange = { controller.notes = it },
            textStyle     = AppTypography.Body.copy(color = AppColors.TextPrimary),
            cursorBrush   = SolidColor(AppColors.ThemeGreenPrimary),
            modifier      = Modifier
                .fillMaxSize()
                .padding(bottom = AppSpacing.s5),
            decorationBox = { inner ->
                if (controller.notes.isEmpty()) {
                    Text(
                        text  = "Start typing…",
                        style = AppTypography.Body,
                        color = AppColors.TextTertiary,
                    )
                }
                inner()
            },
        )
    }
}
