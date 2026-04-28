/*
 * CategoryManagementSection.kt
 *
 * Settings card for managing notepad-entry categories. Predefined
 * categories (Home / Work / Personal / Health / Travel / Ideas) are
 * shown read-only — they're built into the app and can't be removed.
 * Custom categories (anything the user has typed in the editor's
 * picker) get rename + delete actions:
 *
 *   - Rename: bulk-updates every active entry that carries the old
 *     label to the new label. If the new label matches a predefined
 *     name (case-insensitive), the canonical-cased form wins so the
 *     chip row deduplicates instead of forking.
 *   - Delete: bulk-clears the label from every active entry that
 *     carries it (sets `category = NULL`). The entries themselves
 *     stay live; only the label is dropped.
 *
 * Adding a category is intentionally NOT done here — the user adds
 * one by typing it into the editor's category picker. That keeps
 * "categories the user has invented" in lockstep with "categories
 * actually attached to entries", so a typo on creation can be fixed
 * here later.
 */

package app.releaf.mobile.features.settings

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.releaf.mobile.ReleafApp
import app.releaf.mobile.auth.AuthState
import app.releaf.mobile.data.notepad.NotepadCategory
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppRadius
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography
import app.releaf.mobile.ui.theme.UiPreferences
import kotlinx.coroutines.launch

@Composable
fun CategoryManagementSection(
    modifier: Modifier = Modifier,
) {
    val context   = LocalContext.current
    val releafApp = context.applicationContext as ReleafApp
    val signedIn  = releafApp.authStore.state.collectAsState().value as? AuthState.SignedIn
    val userId    = signedIn?.session?.userId
    val scope     = rememberCoroutineScope()
    val prefs     = remember(context) { UiPreferences.get(context) }
    val prefsState by prefs.state.collectAsState()
    val userOrder = prefsState.notepadCategoryOrder

    // Customs derived from the user's currently-active notepad
    // entries. Re-derived on every observation tick so renames /
    // deletes / new entries land in the chip list without manual
    // refresh.
    val customs by produceState(initialValue = emptyList<String>(), userId) {
        if (userId == null) {
            value = emptyList()
            return@produceState
        }
        releafApp.notepadRepository.observeActive(userId).collect { entries ->
            value = NotepadCategory.deriveCustomCategories(entries)
        }
    }

    // Effective display order: predefined + customs merged into a
    // single list, with the user's preferred ordering applied.
    // `applyOrder` filters out names the user explicitly ordered but
    // that no longer exist (e.g. a deleted custom), and appends any
    // newly-discovered names at the end.
    val ordered = remember(userOrder, customs) {
        NotepadCategory.applyOrder(userOrder, customs)
    }

    // Pending-action state. `renameTarget` opens the rename dialog
    // pre-filled with the current name; `deleteTarget` opens the
    // confirm-delete dialog. Both nulls = no dialog showing.
    var renameTarget by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.md))
            .background(AppColors.CardSolid)
            .border(1.dp, AppColors.BorderDefault, RoundedCornerShape(AppRadius.md))
            .padding(AppSpacing.s4),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s3),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s1)) {
            Text(
                text  = "NOTEPAD",
                style = AppTypography.Eyebrow,
                color = AppColors.TextSecondary,
            )
            Text(
                text  = "Categories",
                style = AppTypography.SectionTitle,
                color = AppColors.TextPrimary,
            )
            Text(
                text  = "Drag … wait, tap the ↑ / ↓ arrows to reorder. Predefined names are built in (no rename / delete). Add a new category by typing it into the picker on any notepad entry.",
                style = AppTypography.Meta,
                color = AppColors.TextSecondary,
            )
        }

        // Single ordered list. Each row carries up/down to reorder;
        // custom rows additionally get rename + trash.
        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s1)) {
            if (ordered.isEmpty()) {
                Text(
                    text  = "No categories yet — type one into the picker on a notepad entry to add it.",
                    style = AppTypography.Meta,
                    color = AppColors.TextTertiary,
                    modifier = Modifier.padding(vertical = AppSpacing.s1),
                )
            } else {
                ordered.forEachIndexed { index, name ->
                    val editable = !NotepadCategory.isPredefined(name)
                    CategoryRow(
                        name        = name,
                        editable    = editable,
                        canMoveUp   = index > 0,
                        canMoveDown = index < ordered.lastIndex,
                        onMoveUp    = {
                            val moved = ordered.toMutableList().also {
                                val tmp = it[index - 1]
                                it[index - 1] = it[index]
                                it[index]     = tmp
                            }
                            prefs.setNotepadCategoryOrder(moved)
                        },
                        onMoveDown  = {
                            val moved = ordered.toMutableList().also {
                                val tmp = it[index + 1]
                                it[index + 1] = it[index]
                                it[index]     = tmp
                            }
                            prefs.setNotepadCategoryOrder(moved)
                        },
                        onRename    = { renameTarget = name },
                        onDelete    = { deleteTarget = name },
                    )
                }
            }
        }
    }

    // ── Rename dialog ──
    val renameName = renameTarget
    if (renameName != null && userId != null) {
        var draft by remember(renameName) { mutableStateOf(renameName) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename category") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s2)) {
                    Text(
                        text  = "Every entry currently filed under \"$renameName\" will be moved to the new name.",
                        style = AppTypography.Body,
                        color = AppColors.TextSecondary,
                    )
                    OutlinedTextField(
                        value         = draft,
                        onValueChange = { draft = it },
                        singleLine    = true,
                        label         = { Text("New name") },
                        modifier      = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                val trimmed = draft.trim()
                val canSave = trimmed.isNotEmpty() && !trimmed.equals(renameName, ignoreCase = true)
                TextButton(
                    onClick = {
                        renameTarget = null
                        scope.launch {
                            releafApp.notepadRepository.renameCategory(
                                userId  = userId,
                                oldName = renameName,
                                newName = trimmed,
                            )
                        }
                    },
                    enabled = canSave,
                ) {
                    Text("Rename", color = if (canSave) AppColors.ThemeGreenDeep else AppColors.TextTertiary)
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
                    Text("Cancel", color = AppColors.TextSecondary)
                }
            },
        )
    }

    // ── Delete confirm ──
    val deleteName = deleteTarget
    if (deleteName != null && userId != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete \"$deleteName\"?") },
            text  = {
                Text(
                    text  = "Every entry currently filed under \"$deleteName\" will become uncategorised. The entries themselves stay put.",
                    style = AppTypography.Body,
                    color = AppColors.TextSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    scope.launch {
                        releafApp.notepadRepository.deleteCategory(
                            userId = userId,
                            name   = deleteName,
                        )
                    }
                }) {
                    Text("Delete", color = AppColors.Danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("Cancel", color = AppColors.TextSecondary)
                }
            },
        )
    }
}

/**
 * One category row inside the management card. Every row carries a
 * pair of up/down chevrons for reordering (greyed at the list ends);
 * custom rows additionally get a pencil + trash pair on the right.
 * Predefined rows can be reordered but not renamed or deleted —
 * they're built into the app.
 */
@Composable
private fun CategoryRow(
    name: String,
    editable: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector        = Icons.AutoMirrored.Filled.Label,
            contentDescription = null,
            tint               = if (editable) AppColors.ThemeGreenDeep else AppColors.TextTertiary,
            modifier           = Modifier.size(16.dp),
        )
        Spacer(Modifier.size(AppSpacing.s2))
        Text(
            text     = name,
            style    = AppTypography.Body,
            color    = AppColors.TextPrimary,
            modifier = Modifier.weight(1f),
        )
        ReorderArrow(
            icon    = Icons.Filled.ArrowUpward,
            label   = "Move $name up",
            enabled = canMoveUp,
            onClick = onMoveUp,
        )
        ReorderArrow(
            icon    = Icons.Filled.ArrowDownward,
            label   = "Move $name down",
            enabled = canMoveDown,
            onClick = onMoveDown,
        )
        if (editable) {
            RowAction(
                icon    = Icons.Filled.Edit,
                label   = "Rename $name",
                tint    = AppColors.TextSecondary,
                onClick = onRename,
            )
            RowAction(
                icon    = Icons.Filled.DeleteOutline,
                label   = "Delete $name",
                tint    = AppColors.Danger,
                onClick = onDelete,
            )
        }
    }
}

/** Up / down chevron — disabled (faded, no-op) when the row is at
 *  the corresponding end of the list. */
@Composable
private fun ReorderArrow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector        = icon,
            contentDescription = label,
            tint               = if (enabled) AppColors.TextSecondary else AppColors.TextTertiary,
            modifier           = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun RowAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector        = icon,
            contentDescription = label,
            tint               = tint,
            modifier           = Modifier.size(18.dp),
        )
    }
}
