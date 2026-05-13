/*
 * CategoriesSettingsScreen.kt
 *
 * Settings → Categories. CRUD list of the user's categories.
 * Backed by `TagRepository` — same data source the scan-
 * review chip picker reads, so an add/remove here flows back into
 * the picker on the next scan with no extra wiring.
 *
 * Phase-3 scope: list + add (text field) + soft-delete (icon
 * button). Reorder is in the repository surface but doesn't have
 * UI yet — lands in a follow-up alongside drag-handles.
 *
 * Mirror of iOS `CategoriesSettingsScreen.swift`.
 */

package app.quickink.mobile.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.quickink.mobile.QuickInkApp
import app.quickink.mobile.data.tag.TagEntity
import app.quickink.mobile.data.tag.TagRepository
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkRadius
import app.quickink.mobile.ui.theme.QuickInkSpacing
import app.quickink.mobile.ui.theme.quickInkDotGridBackground
import kotlinx.coroutines.launch

@Composable
fun CategoriesSettingsScreen(
    userId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as QuickInkApp
    val repository = remember(app) {
        TagRepository(
            tagDao = app.database.tagDao(),
            captureDao  = app.database.captureDao(),
        )
    }
    val scope = rememberCoroutineScope()
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current

    val categories by remember(userId, repository) {
        repository.observe(userId)
    }.collectAsState(initial = emptyList())

    var newName by remember { mutableStateOf("") }
    var addError by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<TagEntity?>(null) }
    var renameDraft by remember { mutableStateOf("") }

    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .quickInkDotGridBackground()
            .padding(top = statusBarTop + QuickInkSpacing.s4),
    ) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = QuickInkSpacing.s2, vertical = QuickInkSpacing.s2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector       = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint              = colors.ink,
                )
            }
            Text(text = "Tags", style = type.pageTitle, color = colors.ink)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = QuickInkSpacing.s5, vertical = QuickInkSpacing.s4),
            verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s5),
        ) {
            // Add row
            Column(verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2)) {
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
                ) {
                    OutlinedTextField(
                        value         = newName,
                        onValueChange = {
                            newName = it
                            if (addError != null) addError = null
                        },
                        modifier      = Modifier.weight(1f),
                        placeholder   = { Text("New category", style = type.body, color = colors.muted) },
                        singleLine    = true,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Words,
                            imeAction      = ImeAction.Done,
                        ),
                        shape         = RoundedCornerShape(QuickInkRadius.pill),
                        colors        = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = colors.border,
                            unfocusedBorderColor = colors.border,
                        ),
                    )

                    val canAdd = newName.trim().isNotEmpty()
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(if (canAdd) colors.accent else colors.muted)
                            .clickable(enabled = canAdd) {
                                val name = newName.trim()
                                val position = (categories.maxOfOrNull { it.position } ?: -1) + 1
                                scope.launch {
                                    val inserted = repository.insert(
                                        userId   = userId,
                                        name     = name,
                                        position = position,
                                    )
                                    if (inserted) {
                                        newName = ""
                                        addError = null
                                    } else {
                                        addError = "“$name” already exists."
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector       = Icons.Filled.Add,
                            contentDescription = "Add category",
                            tint              = colors.textOnAccent,
                            modifier          = Modifier.size(18.dp),
                        )
                    }
                }
                addError?.let {
                    Text(text = it, style = type.meta, color = colors.danger)
                }
            }

            // List or empty state
            if (categories.isEmpty()) {
                EmptyState()
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2)) {
                    categories.forEach { cat ->
                        CategoryRow(
                            entity   = cat,
                            onRename = {
                                renameDraft = cat.name
                                renameTarget = cat
                            },
                            onDelete = { scope.launch { repository.softDelete(cat.id) } },
                        )
                    }
                }
            }
        }
    }

    val target = renameTarget
    if (target != null) {
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title            = { Text("Rename category", style = type.body, color = colors.ink) },
            text             = {
                Column(verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2)) {
                    Text(
                        text  = "Existing scans tagged with this category will be updated to the new name.",
                        style = type.meta,
                        color = colors.inkSoft,
                    )
                    OutlinedTextField(
                        value         = renameDraft,
                        onValueChange = { renameDraft = it },
                        singleLine    = true,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Words,
                            imeAction      = ImeAction.Done,
                        ),
                        shape         = RoundedCornerShape(QuickInkRadius.pill),
                        colors        = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = colors.border,
                            unfocusedBorderColor = colors.border,
                        ),
                    )
                }
            },
            confirmButton    = {
                TextButton(onClick = {
                    val trimmed = renameDraft.trim()
                    val current = renameTarget
                    renameTarget = null
                    if (current != null && trimmed.isNotEmpty() && trimmed != current.name) {
                        scope.launch {
                            repository.renameAndPropagate(
                                id      = current.id,
                                oldName = current.name,
                                newName = trimmed,
                                userId  = userId,
                            )
                        }
                    }
                }) {
                    Text("Save", color = colors.accent)
                }
            },
            dismissButton    = {
                TextButton(onClick = { renameTarget = null }) {
                    Text("Cancel", color = colors.ink)
                }
            },
            containerColor   = colors.surface,
        )
    }
}

@Composable
private fun CategoryRow(
    entity: TagEntity,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    val isPredefined = TagRepository.isPredefined(entity.name)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(color = colors.surface, shape = RoundedCornerShape(QuickInkRadius.md))
            .border(
                width = 1.dp,
                color = colors.border,
                shape = RoundedCornerShape(QuickInkRadius.md),
            )
            .padding(horizontal = QuickInkSpacing.s4, vertical = QuickInkSpacing.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text  = entity.name,
            style = type.body,
            color = colors.ink,
        )
        if (isPredefined) {
            Spacer(Modifier.size(QuickInkSpacing.s2))
            Icon(
                imageVector       = Icons.Filled.Lock,
                contentDescription = "Predefined category — read only",
                tint              = colors.muted,
                modifier          = Modifier.size(14.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        if (!isPredefined) {
            IconButton(onClick = onRename) {
                Icon(
                    imageVector       = Icons.Filled.Edit,
                    contentDescription = "Rename ${entity.name}",
                    tint              = colors.muted,
                    modifier          = Modifier.size(18.dp),
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector       = Icons.Filled.Delete,
                    contentDescription = "Delete ${entity.name}",
                    tint              = colors.muted,
                    modifier          = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun EmptyState() {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(color = colors.surface, shape = RoundedCornerShape(QuickInkRadius.md))
            .border(
                width = 1.dp,
                color = colors.border,
                shape = RoundedCornerShape(QuickInkRadius.md),
            )
            .padding(QuickInkSpacing.s4),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
    ) {
        Text(text = "No categories yet", style = type.body, color = colors.ink)
        Text(
            text  = "Add one above so you can tag scans on the review screen.",
            style = type.meta,
            color = colors.inkSoft,
        )
    }
}
