/*
 * NotesListScreen.kt
 *
 * QuickInk's notes list. Reads `NotepadDao.observeActive(userId)`
 * directly via Compose state — no formal ViewModel, since
 * Releaf's Android `NotepadListViewModel.kt` is still in
 * Releaf's app target waiting on the DI refactor (see file
 * header on `NoteEditorController.kt`).
 *
 * Slice 4 deferred bits:
 *   - Search bar (the future shared VM has `query` + the
 *     `searchActive` raw FTS query; UI lands in Slice 5+)
 *   - Swipe-to-delete + undo toast
 *   - Recent captures inline alongside notes (Slice 6)
 *
 * Mirror of iOS `NotesListScreen.swift`.
 */

package app.quickink.mobile.features.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.releaf.mobile.data.notepad.NotepadDao
import app.releaf.mobile.data.notepad.NotepadEntry
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography

@Composable
fun NotesListScreen(
    dao: NotepadDao,
    userId: String,
    onBack: () -> Unit,
    onOpenEntry: (entryId: String) -> Unit,
) {
    val entries by dao.observeActive(userId).collectAsState(initial = emptyList())

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.Canvas),
    ) {
        TopBar(
            onBack    = onBack,
            onNewNote = { onOpenEntry(NoteEditorController.NEW_ENTRY_ID) },
        )

        if (entries.isEmpty()) {
            EmptyState()
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
            ) {
                items(entries, key = { it.id }) { entry ->
                    NotepadRow(
                        entry   = entry,
                        onTap   = { onOpenEntry(entry.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TopBar(onBack: () -> Unit, onNewNote: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.s2, vertical = AppSpacing.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector  = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint         = AppColors.TextPrimary,
            )
        }
        Text(
            text  = "Notes",
            style = AppTypography.PageTitle,
            color = AppColors.TextPrimary,
        )
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onNewNote) {
            Icon(
                imageVector  = Icons.Filled.Add,
                contentDescription = "New note",
                tint         = AppColors.TextPrimary,
            )
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector  = Icons.AutoMirrored.Filled.NoteAdd,
            contentDescription = null,
            tint         = AppColors.TextTertiary,
            modifier     = Modifier.size(48.dp),
        )
        Spacer(Modifier.size(AppSpacing.s2))
        Text(
            text  = "No notes yet",
            style = AppTypography.Body,
            color = AppColors.TextSecondary,
        )
        Text(
            text  = "Tap + to write your first one.",
            style = AppTypography.Meta,
            color = AppColors.TextTertiary,
        )
    }
}

@Composable
private fun NotepadRow(entry: NotepadEntry, onTap: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap)
            .padding(horizontal = AppSpacing.s5, vertical = AppSpacing.s3),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s1),
    ) {
        Text(
            text  = entry.title?.takeIf { it.isNotBlank() } ?: "Untitled",
            style = AppTypography.Body,
            color = AppColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (entry.notes.isNotBlank()) {
            Text(
                text     = entry.notes,
                style    = AppTypography.Meta,
                color    = AppColors.TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text  = entry.entryDate,
            style = AppTypography.Meta,
            color = AppColors.TextTertiary,
        )
    }
}
