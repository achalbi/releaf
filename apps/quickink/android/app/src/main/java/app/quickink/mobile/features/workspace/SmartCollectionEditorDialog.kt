/*
 * SmartCollectionEditorDialog.kt
 *
 * Workspace v1 smart-collection editor. The commonly-used clause
 * types from `SmartCollectionRule.kt` are reachable from the UI:
 *
 *   - folder_is        (one folder)
 *   - date_range       (created_at preset)
 *   - tag_is           (one or more tags the capture must carry)
 *   - tag_is_not       (one or more tags the capture must NOT carry)
 *   - source_is        (scan / import / photo / video / share-extension)
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
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
    initialIcon: String? = null,
    initialColor: String? = null,
    isEdit: Boolean = false,
    onDismiss: () -> Unit,
    onSubmit: (name: String, input: SmartCollectionRuleInput,
               icon: String?, color: String?) -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current

    var name        by remember(initialName)  { mutableStateOf(initialName) }
    var input       by remember(initialInput) {
        mutableStateOf(
            initialInput.copy(
                hasHandwriting = null,
                hasSignature   = null,
                hasOcrText     = null,
            ),
        )
    }
    var iconSlug    by remember(initialIcon)  { mutableStateOf(initialIcon) }
    var colorHex    by remember(initialColor) {
        mutableStateOf(initialColor ?: WorkspaceFolderPalette.first())
    }

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
                FolderChoiceWrapRow(
                    folders  = folders,
                    selected = input.folderId,
                    onSelect = { input = input.copy(folderId = it) },
                )

                SectionLabel("WHEN CREATED")
                ChoiceWrapRow(
                    options    = listOf(
                        null to "Any time",
                        "today" to "Today",
                        "yesterday" to "Yesterday",
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
                        "photo" to "Photo",
                        "video" to "Video",
                        "share-extension" to "Share",
                    ),
                    selected = input.sourceValue,
                    onSelect = { input = input.copy(sourceValue = it) },
                )

                SectionLabel("ICON")
                IconPaletteRow(
                    selected   = iconSlug,
                    onSelect   = { iconSlug = it },
                )

                SectionLabel("COLOR")
                ColorPaletteRow(
                    selected   = colorHex,
                    onSelect   = { colorHex = it },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(name.trim(), input, iconSlug, colorHex) },
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
private fun FolderChoiceWrapRow(
    folders: List<FolderEntity>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement   = Arrangement.spacedBy(6.dp),
    ) {
        ChipBox(label = "Any", active = selected == null) { onSelect(null) }
        folders.forEach { folder ->
            ColoredChipBox(
                label  = folder.name,
                hue    = parseFolderColor(folder.color),
                active = selected == folder.id,
            ) {
                onSelect(folder.id)
            }
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
    val colors = LocalQuickInkColors.current
    val orderedTags = remember(tags) { orderedTagOptions(tags) }
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement   = Arrangement.spacedBy(6.dp),
    ) {
        orderedTags.forEach { tag ->
            val isActive = tag.id in selected
            ColoredChipBox(
                label  = tag.name,
                hue    = tagVocabularyHue(tag, colors.accent),
                active = isActive,
            ) {
                onToggle(tag.id)
            }
        }
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

@Composable
private fun ColoredChipBox(
    label: String,
    hue: Color,
    active: Boolean,
    onTap: () -> Unit,
) {
    val type = LocalQuickInkTypography.current
    val shape = RoundedCornerShape(999.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(if (active) hue else hue.copy(alpha = 0.12f), shape)
            .border(1.dp, hue, shape)
            .clickable { onTap() }
            .padding(horizontal = 11.dp, vertical = 5.dp),
    ) {
        Text(
            text  = label,
            style = type.label.copy(fontSize = 11.5.sp),
            color = if (active) Color.White else hue,
        )
    }
}

private fun parseTagColor(hex: String?, fallback: Color): Color {
    val target = hex.takeUnless { it.isNullOrBlank() } ?: return fallback
    return runCatching { Color(android.graphics.Color.parseColor(target)) }
        .getOrElse { fallback }
}

private fun tagVocabularyHue(tag: TagEntity, fallback: Color): Color =
    workspaceTagBuckets.firstOrNull { it.id == tag.bucket }?.hue
        ?: parseTagColor(tag.color, fallback)

private fun orderedTagOptions(tags: List<TagEntity>): List<TagEntity> {
    val bucketOrder = workspaceTagBuckets
        .mapIndexed { index, bucket -> bucket.id to index }
        .toMap()
    return tags.sortedWith(
        compareBy<TagEntity>(
            { tag -> tag.bucket?.let { bucketOrder[it] } ?: Int.MAX_VALUE },
            { tag -> tag.position },
            { tag -> tag.name.lowercase() },
            { tag -> tag.id },
        ),
    )
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun IconPaletteRow(
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    val colors = LocalQuickInkColors.current
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement   = Arrangement.spacedBy(6.dp),
    ) {
        SmartCollectionIconPalette.forEach { option ->
            val isActive = option.slug == selected
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (isActive) colors.ink else colors.surface,
                    )
                    .border(
                        1.dp,
                        if (isActive) colors.ink else colors.border,
                        RoundedCornerShape(8.dp),
                    )
                    .clickable {
                        // Tap a selected icon again to clear back to
                        // "no slug" — the card falls through to the
                        // palette's default sparkle.
                        onSelect(if (isActive) null else option.slug)
                    },
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                androidx.compose.material3.Icon(
                    imageVector        = option.icon,
                    contentDescription = option.slug,
                    tint               = if (isActive) androidx.compose.ui.graphics.Color.White
                                         else colors.inkSoft,
                    modifier           = Modifier.size(18.dp),
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ColorPaletteRow(
    selected: String?,
    onSelect: (String) -> Unit,
) {
    val colors = LocalQuickInkColors.current
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement   = Arrangement.spacedBy(6.dp),
    ) {
        WorkspaceFolderPalette.forEach { hex ->
            val isActive = hex.equals(selected, ignoreCase = true)
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(parseFolderColor(hex), CircleShape)
                    .border(
                        width = if (isActive) 2.dp else 0.dp,
                        color = if (isActive) colors.ink else Color.Transparent,
                        shape = CircleShape,
                    )
                    .clickable { onSelect(hex) },
            )
        }
    }
}
