/*
 * SmartCollectionEditorDialog.kt
 *
 * Workspace v1 smart-collection editor. Six clause types from
 * `SmartCollectionRule.kt` are now reachable from the UI:
 *
 *   - folder_is        (one folder)
 *   - date_range       (created_at preset)
 *   - tag_is           (one or more tags the capture must carry)
 *   - tag_is_not       (one or more tags the capture must NOT carry)
 *   - source_is        (scan / import / share-extension)
 *   - has_handwriting / has_signature / has_ocr_text (OCR signals;
 *     evaluator returns false until Phase E lights up the columns,
 *     but authoring them is exposed today so users can pre-build
 *     rules that activate when the signals arrive).
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.quickink.mobile.data.folder.FolderEntity
import app.quickink.mobile.data.smartcollection.SmartCollectionRuleInput
import app.quickink.mobile.data.tag.TagEntity
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkRadius
import app.quickink.mobile.ui.theme.QuickInkSpacing

@Composable
internal fun SmartCollectionEditorDialog(
    folders: List<FolderEntity>,
    tags: List<TagEntity> = emptyList(),
    initialName: String = "",
    initialInput: SmartCollectionRuleInput = SmartCollectionRuleInput(),
    isEdit: Boolean = false,
    onDismiss: () -> Unit,
    onSubmit: (name: String, input: SmartCollectionRuleInput) -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current

    var name        by remember(initialName) { mutableStateOf(initialName) }
    var input       by remember(initialInput) { mutableStateOf(initialInput) }

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
            // The dialog body holds a lot of clauses now; keep the
            // height bounded by Material's AlertDialog and scroll
            // internally when the user surfaces more sections than
            // fit on a small screen.
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3),
            ) {
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

                SectionLabel("FOLDER")
                ChoiceWrapRow(
                    options    = listOf(null to "Any") + folders.map { it.id to it.name },
                    selected   = input.folderId,
                    onSelect   = { input = input.copy(folderId = it) },
                )

                SectionLabel("WHEN CREATED")
                ChoiceWrapRow(
                    options    = listOf(
                        null to "Any time",
                        "this_week" to "This week",
                        "this_month" to "This month",
                        "last_30_days" to "Last 30 days",
                        "this_quarter" to "This quarter",
                    ),
                    selected   = input.datePreset,
                    onSelect   = { input = input.copy(datePreset = it) },
                )

                if (tags.isNotEmpty()) {
                    SectionLabel("MUST HAVE TAG")
                    TagMultiSelectRow(
                        tags     = tags,
                        selected = input.tagIncludeIds,
                        onToggle = { id ->
                            val next = input.tagIncludeIds.toMutableList().apply {
                                if (id in this) remove(id) else add(id)
                            }
                            input = input.copy(tagIncludeIds = next)
                        },
                    )

                    SectionLabel("MUST NOT HAVE TAG")
                    TagMultiSelectRow(
                        tags     = tags,
                        selected = input.tagExcludeIds,
                        onToggle = { id ->
                            val next = input.tagExcludeIds.toMutableList().apply {
                                if (id in this) remove(id) else add(id)
                            }
                            input = input.copy(tagExcludeIds = next)
                        },
                    )
                }

                SectionLabel("SOURCE")
                ChoiceWrapRow(
                    options  = listOf(
                        null to "Any",
                        "scan" to "Scan",
                        "import" to "Import",
                        "share-extension" to "Share",
                    ),
                    selected = input.sourceValue,
                    onSelect = { input = input.copy(sourceValue = it) },
                )

                SectionLabel("OCR SIGNALS")
                // Each OCR signal is a TRI-STATE chip: unset (no
                // clause), require true, require false. Tap cycles
                // through the three states. The dimmed third state
                // (require absent) is rare but cheap to expose and
                // matches the rule grammar's `Boolean` payload.
                OcrTriStateChip(
                    label   = "Handwriting",
                    state   = input.hasHandwriting,
                    onCycle = { input = input.copy(hasHandwriting = it) },
                )
                OcrTriStateChip(
                    label   = "Signature",
                    state   = input.hasSignature,
                    onCycle = { input = input.copy(hasSignature = it) },
                )
                OcrTriStateChip(
                    label   = "OCR text",
                    state   = input.hasOcrText,
                    onCycle = { input = input.copy(hasOcrText = it) },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(name.trim(), input) },
                enabled = !input.isEmpty,
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

@Composable
private fun SectionLabel(text: String) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current
    Text(
        text  = text,
        style = type.label.copy(
            fontSize      = 10.sp,
            fontWeight    = FontWeight.Bold,
            letterSpacing = 1.2.sp,
        ),
        color = colors.muted,
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
            ChipBox(label = label, active = isActive) { onSelect(id) }
                .let { /* Compose returns Unit; nothing else needed */ }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TagMultiSelectRow(
    tags: List<TagEntity>,
    selected: List<String>,
    onToggle: (String) -> Unit,
) {
    if (tags.isEmpty()) return
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement   = Arrangement.spacedBy(6.dp),
    ) {
        tags.forEach { tag ->
            val isActive = tag.id in selected
            ChipBox(label = tag.name, active = isActive) { onToggle(tag.id) }
        }
    }
}

@Composable
private fun OcrTriStateChip(
    label: String,
    state: Boolean?,
    onCycle: (Boolean?) -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current
    // null → "Any" (no clause); true → "Yes"; false → "No".
    // Cycle order matches that triple.
    val (suffix, isActive) = when (state) {
        null  -> "Any" to false
        true  -> "Yes" to true
        false -> "No"  to true
    }
    val cycled: Boolean? = when (state) {
        null  -> true
        true  -> false
        false -> null
    }
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
            .clickable { onCycle(cycled) }
            .padding(horizontal = 11.dp, vertical = 5.dp),
    ) {
        Text(
            text  = "$label · $suffix",
            style = type.label.copy(fontSize = 11.5.sp),
            color = if (isActive) androidx.compose.ui.graphics.Color.White
                    else colors.inkSoft,
        )
    }
}

@Composable
private fun ChipBox(label: String, active: Boolean, onTap: () -> Unit) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(
                if (active) colors.ink else colors.surface,
                RoundedCornerShape(999.dp),
            )
            .border(
                1.dp,
                if (active) colors.ink else colors.border,
                RoundedCornerShape(999.dp),
            )
            .clickable { onTap() }
            .padding(horizontal = 11.dp, vertical = 5.dp),
    ) {
        Text(
            text  = label,
            style = type.label.copy(fontSize = 11.5.sp),
            color = if (active) androidx.compose.ui.graphics.Color.White
                    else colors.inkSoft,
        )
    }
}
