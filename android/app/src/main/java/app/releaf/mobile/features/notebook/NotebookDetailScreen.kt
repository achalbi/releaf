/*
 * NotebookDetailScreen.kt
 * Chapters + pages for a single notebook. Each page row opens PageDetailScreen.
 *
 * Presentation uses the Inkcreate-ported `PagePreviewRow` design-system
 * component rather than hand-rolled cards.
 */

package app.releaf.mobile.features.notebook

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import app.releaf.mobile.data.domain.Chapter
import app.releaf.mobile.data.domain.PageCounts
import app.releaf.mobile.data.domain.PageSummary
import app.releaf.mobile.ui.components.AppButton
import app.releaf.mobile.ui.components.AppButtonVariant
import app.releaf.mobile.ui.components.PagePreviewRow
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography

@Composable
fun NotebookDetailScreen(
    onBack: () -> Unit,
    onOpenPage: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: NotebookDetailViewModel = viewModel(factory = NotebookDetailViewModel.Factory),
) {
    val state by viewModel.state.collectAsState()
    val scroll = rememberScrollState()

    Box(modifier.fillMaxSize()) {
        when (val s = state) {
            NotebookDetailUiState.Loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AppColors.Coral)
                }
            }
            is NotebookDetailUiState.Failed -> {
                Column(
                    Modifier.fillMaxSize().padding(AppSpacing.s4),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(s.message, style = AppTypography.Body, color = AppColors.TextSecondary)
                    Spacer(Modifier.height(AppSpacing.s3))
                    AppButton(
                        "Try again",
                        onClick = viewModel::load,
                        variant = AppButtonVariant.Secondary,
                        fillWidth = false,
                    )
                    Spacer(Modifier.height(AppSpacing.s3))
                    AppButton("Back", onClick = onBack, variant = AppButtonVariant.Text, fillWidth = false)
                }
            }
            is NotebookDetailUiState.Loaded -> {
                val detail = s.detail
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(scroll)
                        .padding(AppSpacing.s4),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.s6),
                ) {
                    Header(
                        title = detail.notebook.title,
                        description = detail.notebook.description,
                        onBack = onBack,
                    )
                    if (detail.chapters.isEmpty()) {
                        Text(
                            "No chapters yet.",
                            style = AppTypography.Body,
                            color = AppColors.TextSecondary,
                        )
                    } else {
                        detail.chapters.forEach { ch ->
                            ChapterSection(chapter = ch, onOpenPage = onOpenPage)
                        }
                    }
                    Spacer(Modifier.height(AppSpacing.s10))
                }
            }
        }
    }
}

@Composable
private fun Header(title: String, description: String?, onBack: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s2)) {
        Text(
            "← Back",
            style = AppTypography.Button,
            color = AppColors.Coral,
            modifier = Modifier.clickable { onBack() },
        )
        Text("NOTEBOOK", style = AppTypography.Eyebrow, color = AppColors.Coral)
        Text(title, style = AppTypography.EditorialTitle, color = AppColors.TextPrimary)
        description?.let {
            Text(it, style = AppTypography.Body, color = AppColors.TextSecondary)
        }
    }
}

@Composable
private fun ChapterSection(chapter: Chapter, onOpenPage: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s2)) {
        Text(
            chapter.title.uppercase(),
            style = AppTypography.Eyebrow,
            color = AppColors.TextSecondary,
        )
        if (chapter.pages.isEmpty()) {
            Text("No pages yet.", style = AppTypography.Meta, color = AppColors.TextTertiary)
        } else {
            chapter.pages.forEach { page ->
                PagePreviewRow(
                    title = page.title,
                    meta = metaLine(page),
                    photoCount = page.counts.photos,
                    onClick = { onOpenPage(page.id) },
                )
            }
        }
    }
}

// meta = "{capturedOn · } {counts}"  — e.g. "Sun, Apr 19 · 3 photos · 2 to-dos"
private fun metaLine(page: PageSummary): String {
    val counts = countsLine(page.counts)
    val captured = page.capturedOn
    return when {
        captured.isNullOrBlank() -> counts
        counts == "Empty page"   -> captured
        else                     -> "$captured · $counts"
    }
}

private fun countsLine(c: PageCounts): String {
    val chips = buildList {
        if (c.photos > 0)           add("${c.photos} photo${s(c.photos)}")
        if (c.voiceNotes > 0)       add("${c.voiceNotes} voice")
        if (c.todoItems > 0)        add("${c.todoItems} to-do${s(c.todoItems)}")
        if (c.scannedDocuments > 0) add("${c.scannedDocuments} scan${s(c.scannedDocuments)}")
        if (c.contacts > 0)         add("${c.contacts} contact${s(c.contacts)}")
        if (c.locations > 0)        add("${c.locations} place${s(c.locations)}")
    }
    return if (chips.isEmpty()) "Empty page" else chips.joinToString(" · ")
}

private fun s(n: Int): String = if (n == 1) "" else "s"
