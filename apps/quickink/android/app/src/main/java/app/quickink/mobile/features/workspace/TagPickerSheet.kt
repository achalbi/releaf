/*
 * TagPickerSheet.kt
 *
 * Workspace v1 Screen 6 — bottom sheet for attaching / detaching
 * tags from a single capture. Manual entry only in this commit
 * (Phase C.2); AI-suggested chips ship in Phase E alongside
 * auto-tagging.
 *
 * UX flow:
 *   1. The sheet opens with the capture's currently-attached tags
 *      shown as removable chips at the top.
 *   2. The user can type a new tag name and tap Enter to add it
 *      (find-or-create via [TagRepository.findOrCreate]).
 *   3. The "All tags" scroll list shows every tag in the user's
 *      namespace; tapping a row toggles its check state.
 *   4. Save commits the diff against the original attachment set
 *      via [CaptureTagDao.attachTag] / [detachTag]. Cancel
 *      discards.
 *
 * Mirror of `TagPickerSheet.swift` (iOS — lands in Phase C iOS
 * mirror).
 */

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.quickink.mobile.features.workspace

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.quickink.mobile.QuickInkApp
import app.quickink.mobile.data.tag.TagRepository
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkRadius
import app.quickink.mobile.ui.theme.QuickInkSpacing
import app.releaf.mobile.data.common.IsoClock
import app.releaf.mobile.data.common.Uuidv7
import kotlinx.coroutines.launch

/** Tag name normalization. Per brief §10 #3 — lowercase, hyphens
 *  preserved, spaces collapsed to hyphens, 32-char cap, ASCII +
 *  digits + hyphen only. Trimmed on both sides. */
internal fun normalizeTagName(raw: String): String {
    val trimmed = raw.trim().lowercase()
    val ascii = trimmed
        .map { ch ->
            when {
                ch.isLetterOrDigit() -> ch
                ch == '-' || ch == ' ' || ch == '_' -> '-'
                else -> '-'
            }
        }
        .joinToString("")
        .trim('-')
    // Collapse runs of hyphens.
    val collapsed = buildString {
        var prevHyphen = false
        for (c in ascii) {
            if (c == '-') {
                if (!prevHyphen) append(c)
                prevHyphen = true
            } else {
                append(c)
                prevHyphen = false
            }
        }
    }
    return collapsed.take(32)
}

@Composable
fun TagPickerSheet(
    captureId: String,
    userId: String,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val colors  = LocalQuickInkColors.current
    val type    = LocalQuickInkTypography.current
    val context = LocalContext.current
    val app     = remember(context) { context.applicationContext as QuickInkApp }
    val scope   = rememberCoroutineScope()

    val tagRepo = remember(app) {
        TagRepository(app.database.tagDao(), app.database.captureDao())
    }

    val allTags by remember(userId) {
        app.database.tagDao().observeActive(userId)
    }.collectAsState(initial = emptyList())

    val originalIds by remember(captureId) {
        app.database.captureTagDao().observeTagIdsForCapture(captureId)
    }.collectAsState(initial = emptyList())

    // AI suggestions — read OCR text + the capture's createdAt
    // and run the rule engine against the user's existing tag set.
    var suggestedNames by remember(captureId) { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(captureId, allTags) {
        val ocrText = app.database.ocrResultDao().findFirstTextForCapture(captureId)
        val capture = app.database.captureDao().findById(captureId)
        suggestedNames = AutoTagSuggester.suggest(
            ocrText            = ocrText,
            existingTagNames   = allTags.map { it.name }.toSet(),
            currentlyAttached  = originalIds
                .mapNotNull { id -> allTags.firstOrNull { it.id == id }?.name }
                .toSet(),
            captureDateIso     = capture?.createdAt,
        )
    }

    // Working set — initialised from the on-disk attachments the
    // first time they arrive. After that the user's toggles win
    // until Save / Cancel. `seeded` guards the one-time copy.
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var seeded by remember { mutableStateOf(false) }
    LaunchedEffect(originalIds) {
        if (!seeded && originalIds.isNotEmpty()) {
            selectedIds = originalIds.toSet()
            seeded = true
        }
    }

    var newTagInput by remember { mutableStateOf("") }

    val countDelta = selectedIds.size

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        containerColor   = colors.surface,
    ) {
        Column(modifier = Modifier.padding(horizontal = QuickInkSpacing.s4, vertical = QuickInkSpacing.s2)) {
            Text(
                text  = "Add tags",
                style = type.editorial.copy(fontSize = 19.sp, fontWeight = FontWeight.SemiBold),
                color = colors.ink,
            )
            Text(
                text  = "Tags help you find this doc from anywhere in the workspace.",
                style = type.meta,
                color = colors.muted,
                modifier = Modifier.padding(top = 4.dp, bottom = QuickInkSpacing.s3),
            )

            // ─── New-tag input ────────────────────────────────
            NewTagInputRow(
                input         = newTagInput,
                onChange      = { newTagInput = it },
                onSubmit      = {
                    val normalized = normalizeTagName(newTagInput)
                    if (normalized.isEmpty()) {
                        newTagInput = ""
                        return@NewTagInputRow
                    }
                    scope.launch {
                        val tag = tagRepo.findOrCreate(userId, normalized)
                        selectedIds = selectedIds + tag.id
                        newTagInput = ""
                    }
                },
            )

            // ─── AI-suggested strip ───────────────────────────
            if (suggestedNames.isNotEmpty()) {
                Spacer(Modifier.height(QuickInkSpacing.s3))
                AiSuggestedStrip(
                    names    = suggestedNames,
                    onAccept = { name ->
                        scope.launch {
                            val tag = tagRepo.findOrCreate(userId, name)
                            selectedIds = selectedIds + tag.id
                            // Tagged source becomes "ai-suggested"
                            // on Save (the writer below uses this
                            // path; manual taps stay "manual").
                            suggestedNames = suggestedNames.filter { it != name }
                        }
                    },
                )
            }

            Spacer(Modifier.height(QuickInkSpacing.s3))

            HorizontalDivider(color = colors.borderSoft)

            // ─── All-tags list ────────────────────────────────
            Text(
                text  = "ALL TAGS",
                style = type.label.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp),
                color = colors.muted,
                modifier = Modifier.padding(top = QuickInkSpacing.s3, bottom = 4.dp),
            )

            if (allTags.isEmpty()) {
                Text(
                    text  = "No tags yet. Type one above to start.",
                    style = type.meta,
                    color = colors.muted,
                    modifier = Modifier.padding(vertical = QuickInkSpacing.s2),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                ) {
                    items(allTags, key = { it.id }) { tag ->
                        val isSelected = tag.id in selectedIds
                        AllTagsRow(
                            name       = tag.name,
                            isSelected = isSelected,
                            onToggle   = {
                                selectedIds = if (isSelected) selectedIds - tag.id
                                              else selectedIds + tag.id
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(QuickInkSpacing.s2))
            HorizontalDivider(color = colors.borderSoft)

            // ─── Footer CTAs ──────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = QuickInkSpacing.s3, bottom = QuickInkSpacing.s2),
                horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(QuickInkRadius.md))
                        .background(colors.ink)
                        .clickable {
                            val finalSelected = selectedIds
                            val toDetach = originalIds.toSet() - finalSelected
                            val toAttach = finalSelected - originalIds.toSet()
                            scope.launch {
                                val now = IsoClock.nowIso()
                                val joinDao = app.database.captureTagDao()
                                for (tagId in toAttach) {
                                    joinDao.attachTag(
                                        joinId    = Uuidv7.generate(),
                                        captureId = captureId,
                                        tagId     = tagId,
                                        source    = "manual",
                                        timestamp = now,
                                    )
                                }
                                for (tagId in toDetach) {
                                    joinDao.detachTag(captureId, tagId, now)
                                }
                                onDismiss()
                            }
                        }
                        .padding(vertical = 13.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text  = "Save $countDelta ${if (countDelta == 1) "tag" else "tags"}",
                        style = type.body.copy(fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp),
                        color = Color.White,
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(QuickInkRadius.md))
                        .background(colors.borderSoft)
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 18.dp, vertical = 13.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text  = "Cancel",
                        style = type.body.copy(fontWeight = FontWeight.Medium, fontSize = 13.5.sp),
                        color = colors.ink,
                    )
                }
            }
        }
    }
}

// ─── Components ──────────────────────────────────────────────

@Composable
private fun NewTagInputRow(
    input: String,
    onChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current
    val shape  = RoundedCornerShape(QuickInkRadius.md)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.bg, shape)
            .border(1.dp, colors.border, shape)
            .padding(horizontal = QuickInkSpacing.s3, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text  = "#",
            style = type.label.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold),
            color = colors.accent,
        )
        Spacer(Modifier.width(4.dp))
        BasicTextField(
            value         = input,
            onValueChange = onChange,
            singleLine    = true,
            textStyle     = type.body.copy(fontSize = 13.sp, color = colors.ink),
            cursorBrush   = androidx.compose.ui.graphics.SolidColor(colors.accent),
            modifier      = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                imeAction      = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (input.isEmpty()) {
                        Text(
                            text  = "Type a tag…",
                            style = type.body.copy(fontSize = 13.sp),
                            color = colors.muted,
                        )
                    }
                    inner()
                }
            },
        )
    }
}

@Composable
private fun AiSuggestedStrip(
    names: List<String>,
    onAccept: (String) -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current
    val shape  = RoundedCornerShape(QuickInkRadius.md)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.accentSoft.copy(alpha = 0.4f), shape)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text  = "✨",
                style = type.label.copy(fontSize = 12.sp),
                color = colors.accent,
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text  = "SUGGESTED FROM THIS DOCUMENT",
                style = type.label.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp),
                color = colors.accentDeep,
            )
        }
        Spacer(Modifier.height(QuickInkSpacing.s2))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            names.forEach { name ->
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.White.copy(alpha = 0.7f), RoundedCornerShape(999.dp))
                        .border(1.dp, colors.accent.copy(alpha = 0.25f), RoundedCornerShape(999.dp))
                        .clickable { onAccept(name) }
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text  = "+",
                        style = type.label.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                        color = colors.accentDeep,
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        text  = "#",
                        style = type.label.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                        color = colors.accent.copy(alpha = 0.7f),
                    )
                    Text(
                        text  = name,
                        style = type.label.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
                        color = colors.accentDeep,
                        modifier = Modifier.padding(start = 1.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun AllTagsRow(
    name: String,
    isSelected: Boolean,
    onToggle: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(
                    color = if (isSelected) colors.accent else Color.Transparent,
                    shape = RoundedCornerShape(5.dp),
                )
                .border(
                    width = if (isSelected) 0.dp else 1.5.dp,
                    color = if (isSelected) colors.accent else colors.border,
                    shape = RoundedCornerShape(5.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (isSelected) {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
        Spacer(Modifier.width(QuickInkSpacing.s2))
        Text(
            text  = "#",
            style = type.label.copy(fontSize = 13.5.sp),
            color = colors.accent.copy(alpha = 0.6f),
        )
        Text(
            text  = name,
            style = type.body.copy(fontWeight = FontWeight.Medium, fontSize = 13.5.sp),
            color = colors.ink,
            modifier = Modifier.padding(start = 1.dp),
        )
    }
}
