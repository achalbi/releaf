/*
 * StoryAddSheet.kt
 *
 * The "+ Add" bottom sheet from §7.3a of the v3 mockup. Three
 * sections — capture / library / layout — each with icon + label +
 * hint rows. Mirror of iOS `StoryAddSheet.swift`.
 */

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.quickink.mobile.features.stories

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.quickink.mobile.data.storyitem.StoryItemEntity
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkSpacing
import kotlinx.coroutines.delay

@Composable
fun StoryAddSheet(
    precedingItemCaption: String?,
    /** User ID — threaded down so the library picker can scope its
     *  capture query. */
    userId: String,
    onPickInlineKind: (StoryItemEntity.Kind) -> Unit,
    onPickVoiceClip: (audioUri: String, durationMs: Long) -> Unit,
    /** Phase 2 follow-up — capture-backed item insertion. */
    onPickCapture: (captureId: String, kind: StoryItemEntity.Kind) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current
    val sheetState = rememberModalBottomSheetState()

    var showVoiceRecorder by remember { mutableStateOf(false) }
    var pickerFilter by remember { mutableStateOf<StoryPickerFilter?>(null) }
    var showNotePicker by remember { mutableStateOf(false) }
    var stubToast by remember { mutableStateOf<String?>(null) }

    fun showStub(message: String) {
        stubToast = message
    }

    LaunchedEffect(stubToast) {
        if (stubToast != null) {
            delay(1_800)
            stubToast = null
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        containerColor   = colors.surface,
    ) {
        Column(
            modifier = Modifier.padding(
                start  = QuickInkSpacing.s4,
                end    = QuickInkSpacing.s4,
                bottom = QuickInkSpacing.s6,
            ),
        ) {
            Text("Add", style = type.editorial, color = colors.ink)
            Text(
                text = buildAnnotatedString {
                    if (precedingItemCaption.isNullOrEmpty()) {
                        append("at the start of the story")
                    } else {
                        append("after — ")
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append("\"")
                            append(precedingItemCaption)
                            append("\"")
                        }
                    }
                },
                style = type.bodyItalic,
                color = colors.inkSoft,
                modifier = Modifier.padding(bottom = QuickInkSpacing.s3),
            )

            SectionHeader("CAPTURE NEW")
            AddRow(icon = Icons.Outlined.CameraAlt,  label = "Scan a page",         hint = "camera",     onClick = { showStub("Scan a page coming soon") })
            AddRow(icon = Icons.Outlined.Image,      label = "Take a photo",        hint = "camera",     onClick = { showStub("Take a photo coming soon") })
            AddRow(icon = Icons.Outlined.Mic,        label = "Record a voice note", hint = "tap & hold", onClick = { showVoiceRecorder = true })

            SectionDivider()
            SectionHeader("FROM YOUR LIBRARY")
            AddRow(icon = Icons.Outlined.Photo,      label = "Choose a photo",      hint = "picker",     onClick = { pickerFilter = StoryPickerFilter.PHOTO })
            AddRow(icon = Icons.Outlined.Description, label = "Choose a document",  hint = "picker",     onClick = { pickerFilter = StoryPickerFilter.DOCUMENT })
            AddRow(icon = Icons.AutoMirrored.Outlined.Notes, label = "Choose a note", hint = "picker",   onClick = { showNotePicker = true })

            SectionDivider()
            SectionHeader("LAYOUT")
            AddRow(icon = Icons.Outlined.TextFields,  label = "Write a paragraph",  hint = "serif",      onClick = { onPickInlineKind(StoryItemEntity.Kind.TEXT_BLOCK) })
            AddRow(icon = Icons.Outlined.Edit,        label = "Handwritten note",   hint = "Caveat",     onClick = { onPickInlineKind(StoryItemEntity.Kind.HANDWRITTEN_NOTE) })
            AddRow(icon = Icons.Outlined.CalendarToday, label = "Date divider",     hint = "— May 5 —",  onClick = { onPickInlineKind(StoryItemEntity.Kind.DATE_DIVIDER) })
            AddRow(icon = Icons.Filled.LocationOn,    label = "Place pin",          hint = "Shibuya",    onClick = { onPickInlineKind(StoryItemEntity.Kind.PLACE_PIN) })

            if (stubToast != null) {
                Text(
                    text     = stubToast!!,
                    style    = type.bodyItalic,
                    color    = colors.inkSoft,
                    modifier = Modifier.padding(top = QuickInkSpacing.s2),
                )
            }
        }
    }

    if (showVoiceRecorder) {
        StoryVoiceClipRecorderSheet(
            onSave   = { uri, durationMs ->
                showVoiceRecorder = false
                onPickVoiceClip(uri, durationMs)
            },
            onCancel = { showVoiceRecorder = false },
        )
    }

    val currentPicker = pickerFilter
    if (currentPicker != null) {
        StoryLibraryPickerSheet(
            userId    = userId,
            filter    = currentPicker,
            onPick    = { captureId ->
                val kind = if (currentPicker == StoryPickerFilter.PHOTO)
                    StoryItemEntity.Kind.PHOTO else StoryItemEntity.Kind.DOCUMENT
                pickerFilter = null
                onPickCapture(captureId, kind)
            },
            onDismiss = { pickerFilter = null },
        )
    }

    if (showNotePicker) {
        StoryNotePickerSheet(
            userId    = userId,
            onPick    = { entryId ->
                showNotePicker = false
                onPickCapture(entryId, StoryItemEntity.Kind.NOTE)
            },
            onDismiss = { showNotePicker = false },
        )
    }
}

@Composable
private fun SectionHeader(label: String) {
    val colors = LocalQuickInkColors.current
    Text(
        text          = label,
        color         = colors.muted,
        fontSize      = 11.sp,
        fontWeight    = FontWeight.Medium,
        letterSpacing = 1.5.sp,
        modifier      = Modifier.padding(top = QuickInkSpacing.s2, bottom = QuickInkSpacing.s1),
    )
}

@Composable
private fun SectionDivider() {
    val colors = LocalQuickInkColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(0.5.dp)
            .background(colors.borderSoft)
            .padding(vertical = QuickInkSpacing.s1),
    )
}

@Composable
private fun AddRow(
    icon: ImageVector,
    label: String,
    hint: String,
    onClick: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = QuickInkSpacing.s2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(colors.borderSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = colors.ink, modifier = Modifier.size(16.dp))
        }
        Text(text = label, style = type.body, color = colors.ink)
        Spacer(modifier = Modifier.weight(1f))
        Text(text = hint, style = type.bodyItalic, color = colors.muted, fontSize = 12.sp)
    }
}
