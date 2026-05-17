/*
 * StoryNotePickerSheet.kt
 *
 * Stories Phase 2 follow-up — single-select picker over the user's
 * notepad entries. Mirror of iOS `StoryNotePickerSheet.swift`.
 */

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.quickink.mobile.features.stories

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.quickink.mobile.QuickInkApp
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkRadius
import app.quickink.mobile.ui.theme.QuickInkSpacing
import kotlinx.coroutines.flow.first

@Composable
fun StoryNotePickerSheet(
    userId: String,
    onPick: (entryId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors  = LocalQuickInkColors.current
    val type    = LocalQuickInkTypography.current
    val context = LocalContext.current
    val app     = remember(context) { context.applicationContext as QuickInkApp }

    var rows by remember { mutableStateOf<List<PickerRow>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(userId) {
        val entries = app.database.notepadDao().observeActive(userId).first()
        rows = entries.take(100).map { entry ->
            val body = entry.notes.trim()
            val excerpt = if (body.isEmpty()) "" else
                if (body.length > 60) body.take(60) + "…" else body
            val title = entry.title?.trim()?.takeIf { it.isNotEmpty() } ?: "Daily note"
            PickerRow(
                id        = entry.id,
                title     = title,
                excerpt   = excerpt,
                entryDate = entry.entryDate,
            )
        }
        loading = false
    }

    val sheetState = rememberModalBottomSheetState()
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
            Text(text = "Choose a note", style = type.editorial, color = colors.ink)
            Text(
                text     = "Daily notes from your journal.",
                style    = type.bodyItalic,
                color    = colors.inkSoft,
                modifier = Modifier.padding(bottom = QuickInkSpacing.s3),
            )
            when {
                loading -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = QuickInkSpacing.s5),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    }
                }
                rows.isEmpty() -> {
                    Text(
                        text  = "No notes yet. Write one in the notepad first.",
                        style = type.bodyItalic,
                        color = colors.inkSoft,
                    )
                }
                else -> {
                    LazyColumn(
                        contentPadding = PaddingValues(bottom = QuickInkSpacing.s3),
                        verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
                        modifier = Modifier.height(400.dp),
                    ) {
                        items(rows.size) { idx ->
                            val row = rows[idx]
                            PickerRowView(row = row, onClick = { onPick(row.id) })
                        }
                    }
                }
            }
        }
    }
}

private data class PickerRow(
    val id: String,
    val title: String,
    val excerpt: String,
    val entryDate: String,
)

@Composable
private fun PickerRowView(row: PickerRow, onClick: () -> Unit) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(QuickInkRadius.md))
            .background(colors.bg)
            .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.md))
            .clickable(onClick = onClick)
            .padding(QuickInkSpacing.s2 + 2.dp),
    ) {
        Text(
            text     = row.title,
            style    = type.editorial,
            color    = colors.ink,
            maxLines = 1,
        )
        if (row.excerpt.isNotEmpty()) {
            Text(
                text      = row.excerpt,
                style     = type.bodyItalic,
                color     = colors.inkSoft,
                fontSize  = 12.sp,
                fontStyle = FontStyle.Italic,
                maxLines  = 2,
                modifier  = Modifier.padding(top = 4.dp),
            )
        }
        Text(
            text     = row.entryDate,
            color    = colors.muted,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
