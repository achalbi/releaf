/*
 * NewBookDialog.kt
 *
 * Modal used by the shelves view "+ New notebook" action. Asks for
 * a book name and a shelf. A "+ New shelf…" row opens a second,
 * smaller dialog that creates a shelf and preselects it.
 *
 * The dialog itself holds no schema knowledge — it produces
 * `(title, shelfId)` and hands back to [ShelvesViewModel] for the
 * actual persistence.
 */

package app.releaf.mobile.features.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.releaf.mobile.data.domain.Shelf
import app.releaf.mobile.ui.theme.AppAccent
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppRadius
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography

@Composable
fun NewBookDialog(
    shelves: List<Shelf>,
    onDismiss: () -> Unit,
    onCreateShelf: (String, (String) -> Unit) -> Unit,
    onConfirm: (title: String, shelfId: String) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var selectedShelfId by remember(shelves) {
        mutableStateOf(shelves.firstOrNull()?.id ?: "shelf-general")
    }
    var showShelfList by remember { mutableStateOf(false) }
    var showNewShelfDialog by remember { mutableStateOf(false) }

    val selectedShelf = shelves.firstOrNull { it.id == selectedShelfId }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(title.trim(), selectedShelfId)
                    onDismiss()
                },
                enabled = true,
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        title = { Text("New book", style = AppTypography.SectionTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s3)) {
                LabeledField(label = "Book name") {
                    BasicTextField(
                        value = title,
                        onValueChange = { title = it },
                        singleLine = true,
                        cursorBrush = SolidColor(AppAccent.primary),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = ImeAction.Done,
                        ),
                        textStyle = AppTypography.Body.copy(color = AppColors.TextPrimary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(AppRadius.md))
                            .background(AppColors.InputBg)
                            .border(1.dp, AppColors.BorderDefault, RoundedCornerShape(AppRadius.md))
                            .padding(horizontal = AppSpacing.s3, vertical = AppSpacing.s3),
                    )
                }

                LabeledField(label = "Shelf") {
                    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s2)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(AppRadius.md))
                                .border(1.dp, AppColors.BorderDefault, RoundedCornerShape(AppRadius.md))
                                .clickable { showShelfList = !showShelfList }
                                .padding(horizontal = AppSpacing.s3, vertical = AppSpacing.s3),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                selectedShelf?.name ?: "General",
                                style = AppTypography.Body,
                                color = AppColors.TextPrimary,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                if (showShelfList) "▲" else "▼",
                                style = AppTypography.Meta,
                                color = AppColors.TextSecondary,
                            )
                        }
                        if (showShelfList) {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 180.dp)
                                    .clip(RoundedCornerShape(AppRadius.md))
                                    .background(AppColors.CardSolid)
                                    .border(1.dp, AppColors.BorderDefault, RoundedCornerShape(AppRadius.md)),
                            ) {
                                items(shelves, key = { it.id }) { shelf ->
                                    ShelfRow(
                                        name = shelf.name,
                                        selected = shelf.id == selectedShelfId,
                                        onClick = {
                                            selectedShelfId = shelf.id
                                            showShelfList = false
                                        },
                                    )
                                }
                                item {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                showShelfList = false
                                                showNewShelfDialog = true
                                            }
                                            .padding(AppSpacing.s3),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(
                                            Icons.Filled.Add,
                                            contentDescription = null,
                                            tint = AppAccent.primary,
                                            modifier = Modifier.width(18.dp),
                                        )
                                        Spacer(Modifier.width(AppSpacing.s2))
                                        Text(
                                            "New shelf…",
                                            style = AppTypography.Body,
                                            color = AppAccent.primary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
    )

    if (showNewShelfDialog) {
        NewShelfDialog(
            onDismiss = { showNewShelfDialog = false },
            onConfirm = { name ->
                onCreateShelf(name) { newId ->
                    selectedShelfId = newId
                    showNewShelfDialog = false
                }
            },
        )
    }
}

@Composable
private fun ShelfRow(name: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) AppAccent.soft else AppColors.CardSolid)
            .clickable { onClick() }
            .padding(AppSpacing.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, style = AppTypography.Body, color = AppColors.TextPrimary,
             modifier = Modifier.weight(1f))
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = AppAccent.primary,
                modifier = Modifier.width(18.dp),
            )
        }
    }
}

@Composable
private fun NewShelfDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = name.trim().isNotEmpty(),
            ) { Text("Create shelf") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        title = { Text("New shelf", style = AppTypography.SectionTitle) },
        text = {
            LabeledField(label = "Shelf name") {
                BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    cursorBrush = SolidColor(AppAccent.primary),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Done,
                    ),
                    textStyle = AppTypography.Body.copy(color = AppColors.TextPrimary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(AppRadius.md))
                        .background(AppColors.InputBg)
                        .border(1.dp, AppColors.BorderDefault, RoundedCornerShape(AppRadius.md))
                        .padding(horizontal = AppSpacing.s3, vertical = AppSpacing.s3),
                )
            }
        },
    )
}

@Composable
private fun LabeledField(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s1)) {
        Text(label, style = AppTypography.Eyebrow, color = AppColors.TextSecondary)
        content()
    }
}
