/*
 * SmartCollectionEditorDialog.kt
 *
 * Workspace v1 minimum-viable smart-collection editor. Lets the
 * user combine:
 *   - a name
 *   - an optional folder filter (folder_is)
 *   - an optional date-range preset (date_range over created_at)
 *
 * Tag-based collections already have a fast-path in the tag
 * library's "Save as collection" affordance, so this editor
 * focuses on the OTHER clause types. Full multi-clause-type
 * authoring (tag_is_not, source_is, OCR signals) lands when the
 * use case is clearer.
 *
 * Mirror of `SmartCollectionEditorView.swift` (iOS).
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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

@Composable
internal fun SmartCollectionEditorDialog(
    folders: List<FolderEntity>,
    initialName: String = "",
    initialFolderId: String? = null,
    initialDatePreset: String? = null,
    isEdit: Boolean = false,
    onDismiss: () -> Unit,
    onSubmit: (name: String, folderId: String?, datePreset: String?) -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current

    var name        by remember(initialName)       { mutableStateOf(initialName) }
    var folderId    by remember(initialFolderId)   { mutableStateOf(initialFolderId) }
    var datePreset  by remember(initialDatePreset) { mutableStateOf(initialDatePreset) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text  = if (isEdit) "Edit smart collection"
                        else        "New smart collection",
                style = type.body.copy(fontWeight = FontWeight.SemiBold, fontSize = 17.sp),
                color = colors.ink,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3)) {
                OutlinedTextField(
                    value         = name,
                    onValueChange = { name = it },
                    singleLine    = true,
                    placeholder   = { Text("Name", color = colors.muted) },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction      = ImeAction.Done,
                    ),
                    shape         = RoundedCornerShape(QuickInkRadius.md),
                    colors        = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = colors.border,
                        unfocusedBorderColor = colors.border,
                    ),
                )

                Text(
                    text  = "FOLDER",
                    style = type.label.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                            letterSpacing = 1.2.sp),
                    color = colors.muted,
                )
                ChoiceWrapRow(
                    options    = listOf(null to "Any") + folders.map { it.id to it.name },
                    selected   = folderId,
                    onSelect   = { folderId = it },
                )

                Text(
                    text  = "WHEN CREATED",
                    style = type.label.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                            letterSpacing = 1.2.sp),
                    color = colors.muted,
                )
                ChoiceWrapRow(
                    options    = listOf(
                        null to "Any time",
                        "this_week" to "This week",
                        "this_month" to "This month",
                        "last_30_days" to "Last 30 days",
                        "this_quarter" to "This quarter",
                    ),
                    selected   = datePreset,
                    onSelect   = { datePreset = it },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(name.trim(), folderId, datePreset) },
                enabled = folderId != null || datePreset != null,
            ) {
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

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ChoiceWrapRow(
    options: List<Pair<String?, String>>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement   = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { (id, label) ->
            val isActive = selected == id
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(
                        if (isActive) colors.ink else colors.surface,
                        RoundedCornerShape(999.dp),
                    )
                    .border(
                        1.dp,
                        if (isActive) colors.ink else colors.border,
                        RoundedCornerShape(999.dp),
                    )
                    .clickable { onSelect(id) }
                    .padding(horizontal = 11.dp, vertical = 5.dp),
            ) {
                Text(
                    text  = label,
                    style = type.label.copy(fontSize = 11.5.sp),
                    color = if (isActive) androidx.compose.ui.graphics.Color.White
                            else colors.inkSoft,
                )
            }
        }
    }
}

