/*
 * FolderEditorComponents.kt
 *
 * Bottom sheet + AlertDialogs for the Workspace home folder CRUD
 * affordances (Phase B.1).
 *
 *   - [FolderActionSheet] — long-press a folder row opens this.
 *     Options: Rename / Change color / Delete. Unsorted (the
 *     `is_default = 1` row) gets a read-only header instead.
 *   - [FolderEditorDialog] — used for both Create and Edit. Name
 *     field + 7-swatch color picker. Save / Cancel.
 *   - [FolderDeleteConfirmDialog] — confirms a soft-delete, calling
 *     out that captures move to Unsorted (never cascade-delete).
 *
 * All three return Unit and accept a `onDismiss` callback so the
 * hosting screen can clear its open-modal state.
 *
 * The 7-color palette mirrors the design brief's tagpill / folder
 * swatch set: coral, gold, green, blue, purple, pink, teal.
 * Stored as hex strings — TagEntity / FolderEntity color columns
 * are TEXT.
 */

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.quickink.mobile.features.workspace

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.quickink.mobile.data.folder.FolderEntity
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkRadius
import app.quickink.mobile.ui.theme.QuickInkSpacing

/**
 * Default 7-swatch folder palette — mirrors the design brief's
 * tagpill colors plus stone for Unsorted. Hex strings round-trip
 * directly through FolderEntity.color.
 */
val WorkspaceFolderPalette: List<String> = listOf(
    "#E66943", // coral
    "#E8AE17", // gold
    "#4F9E5A", // green
    "#3A78AE", // blue
    "#7A5DA8", // purple
    "#C75677", // pink
    "#2E8A86", // teal
)

/** Color reached for when a folder's stored value can't be parsed. */
private const val FALLBACK_FOLDER_COLOR = "#A8A29E"

/**
 * Parses [hex] (e.g. "#E66943") into a [Color]. Falls back to the
 * stone neutral on parse failure so a stray bad value never crashes
 * the swatch render.
 */
internal fun parseFolderColor(hex: String?): Color {
    val target = hex.takeUnless { it.isNullOrBlank() } ?: FALLBACK_FOLDER_COLOR
    return runCatching { Color(android.graphics.Color.parseColor(target)) }
        .getOrElse { Color(android.graphics.Color.parseColor(FALLBACK_FOLDER_COLOR)) }
}

// ─── Action sheet (long-press) ────────────────────────────────────

@Composable
fun FolderActionSheet(
    folder: FolderEntity,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onChangeColor: () -> Unit,
    onDelete: () -> Unit,
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
            // Header — folder name + color dot. Read-only for Unsorted.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = QuickInkSpacing.s2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(parseFolderColor(folder.color)),
                )
                Spacer(Modifier.size(QuickInkSpacing.s3))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text  = folder.name,
                        style = type.body.copy(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
                        color = colors.ink,
                    )
                    if (folder.isDefault) {
                        Text(
                            text  = "Default folder · can't be edited or deleted",
                            style = type.meta,
                            color = colors.muted,
                        )
                    }
                }
            }

            // Unsorted is non-editable; show nothing but the header.
            if (folder.isDefault) {
                Spacer(Modifier.height(QuickInkSpacing.s4))
                return@Column
            }

            HorizontalDivider(color = colors.borderSoft)

            SheetRow(label = "Rename", onClick = onRename)
            SheetRow(label = "Change color", onClick = onChangeColor)
            SheetRow(
                label = "Delete folder",
                onClick = onDelete,
                tint = colors.danger,
            )
            Spacer(Modifier.height(QuickInkSpacing.s2))
        }
    }
}

@Composable
private fun SheetRow(
    label: String,
    onClick: () -> Unit,
    tint: Color? = null,
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current
    Text(
        text     = label,
        style    = type.body.copy(fontSize = 15.sp),
        color    = tint ?: colors.ink,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
    )
}

// ─── Editor dialog (create + rename + recolor) ────────────────────

@Composable
fun FolderEditorDialog(
    mode: FolderEditorMode,
    initialName: String,
    initialColor: String,
    onDismiss: () -> Unit,
    onSubmit: (name: String, color: String) -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current

    var name  by remember(initialName)  { mutableStateOf(initialName) }
    var color by remember(initialColor) { mutableStateOf(initialColor) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = when (mode) {
                    FolderEditorMode.Create  -> "New folder"
                    FolderEditorMode.Rename  -> "Rename folder"
                    FolderEditorMode.Recolor -> "Change folder color"
                },
                style = type.body.copy(fontWeight = FontWeight.SemiBold, fontSize = 17.sp),
                color = colors.ink,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3)) {
                if (mode != FolderEditorMode.Recolor) {
                    OutlinedTextField(
                        value         = name,
                        onValueChange = { name = it },
                        singleLine    = true,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Words,
                            imeAction      = ImeAction.Done,
                        ),
                        shape         = RoundedCornerShape(QuickInkRadius.md),
                        colors        = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = colors.border,
                            unfocusedBorderColor = colors.border,
                        ),
                    )
                }
                if (mode != FolderEditorMode.Rename) {
                    Text(
                        text  = "Color",
                        style = type.label.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                        color = colors.inkSoft,
                    )
                    FolderColorPickerRow(
                        selected = color,
                        onPick   = { color = it },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val trimmed = name.trim()
                if (trimmed.isEmpty() && mode != FolderEditorMode.Recolor) return@TextButton
                onSubmit(trimmed, color)
            }) {
                Text("Save", color = colors.accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = colors.ink)
            }
        },
        containerColor = colors.surface,
    )
}

enum class FolderEditorMode { Create, Rename, Recolor }

@Composable
fun FolderColorPickerRow(
    selected: String,
    onPick: (String) -> Unit,
) {
    val colors = LocalQuickInkColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        WorkspaceFolderPalette.forEach { hex ->
            val isSelected = hex.equals(selected, ignoreCase = true)
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(parseFolderColor(hex), CircleShape)
                    .border(
                        width = if (isSelected) 2.dp else 0.dp,
                        color = if (isSelected) colors.ink else Color.Transparent,
                        shape = CircleShape,
                    )
                    .clickable(onClick = { onPick(hex) }),
            )
        }
    }
}

// ─── Delete confirmation ─────────────────────────────────────────

@Composable
fun FolderDeleteConfirmDialog(
    folder: FolderEntity,
    captureCount: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text  = "Delete \"${folder.name}\"?",
                style = type.body.copy(fontWeight = FontWeight.SemiBold, fontSize = 17.sp),
                color = colors.ink,
            )
        },
        text = {
            Text(
                text  = when (captureCount) {
                    0    -> "The folder is empty. Deleting it can't be undone."
                    1    -> "1 capture will move to Unsorted. The folder is removed from this and other devices."
                    else -> "$captureCount captures will move to Unsorted. The folder is removed from this and other devices."
                },
                style = type.meta,
                color = colors.inkSoft,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete", color = colors.danger)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = colors.ink)
            }
        },
        containerColor = colors.surface,
    )
}
