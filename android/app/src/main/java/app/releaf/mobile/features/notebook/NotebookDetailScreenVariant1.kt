/*
 * NotebookDetailScreenVariant1.kt
 * Editorial chapters screen — colored hero block with notebook stats
 * then a cream body listing chapters with serif numerals.
 * Shares [NotebookDetailViewModel] with the classic screen.
 */

package app.releaf.mobile.features.notebook

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.releaf.mobile.data.domain.Chapter
import app.releaf.mobile.data.domain.Notebook
import app.releaf.mobile.data.drive.NotebookDetail
import app.releaf.mobile.features.home.relativeShort
import app.releaf.mobile.ui.components.ShelfPalette
import app.releaf.mobile.ui.components.ShelfTheme
import app.releaf.mobile.ui.theme.AppAccent
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppRadius
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun NotebookDetailScreenVariant1(
    onBack: () -> Unit,
    onOpenPage: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ShelfDetailViewModel = viewModel(factory = ShelfDetailViewModel.Factory),
) {
    val state by viewModel.state.collectAsState()

    Box(modifier.fillMaxSize().background(AppColors.Canvas)) {
        when (val s = state) {
            ShelfDetailUiState.Loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = app.releaf.mobile.ui.theme.AppAccent.primary)
                }
            }
            is ShelfDetailUiState.Failed -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(s.message, style = AppTypography.Body, color = AppColors.TextSecondary)
                }
            }
            is ShelfDetailUiState.Loaded -> Loaded(
                detail = s.detail,
                onBack = onBack,
                onOpenPage = onOpenPage,
                onNewChapter = { viewModel.createChapter() },
                onChapterTap = { chapter ->
                    val firstPage = chapter.pages.firstOrNull()
                    if (firstPage != null) {
                        onOpenPage(firstPage.id)
                    } else {
                        viewModel.createPage(chapterId = chapter.id, onCreated = onOpenPage)
                    }
                },
                onAddVolume = { name ->
                    viewModel.addVolume(volumeName = name)
                },
            )
        }
    }
}

@Composable
private fun Loaded(
    detail: NotebookDetail,
    onBack: () -> Unit,
    onOpenPage: (String) -> Unit,
    onNewChapter: () -> Unit,
    onChapterTap: (Chapter) -> Unit,
    onAddVolume: (String?) -> Unit,
) {
    val palette = remember(detail.notebook.colorToken) {
        ShelfTheme.palette(detail.notebook.colorToken)
    }
    val scroll = rememberScrollState()
    val sorted = remember(detail.chapters) {
        detail.chapters.sortedByDescending { it.position }
    }
    val currentChapter = remember(sorted) {
        sorted.maxByOrNull { it.updatedAt } ?: sorted.firstOrNull()
    }
    var showAddVolumeDialog by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
        Hero(
            notebook = detail.notebook,
            palette = palette,
            currentChapter = currentChapter,
            onBack = onBack,
            onAddVolume = { showAddVolumeDialog = true },
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.s5, vertical = AppSpacing.s5),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.s4),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("CHAPTERS", style = AppTypography.Eyebrow, color = AppColors.ThemeGreenDeep,
                     modifier = Modifier.weight(1f))
                Text("Sort ↓", style = AppTypography.Meta, color = AppColors.TextSecondary)
                Spacer(Modifier.width(AppSpacing.s4))
                Text("Filter", style = AppTypography.Meta, color = AppColors.TextSecondary)
            }

            if (sorted.isEmpty()) {
                Text("No chapters yet.", style = AppTypography.Body, color = AppColors.TextSecondary)
            } else {
                sorted.forEach { chapter ->
                    ChapterRow(
                        chapter = chapter,
                        palette = palette,
                        isCurrent = chapter.id == currentChapter?.id,
                        onClick = { onChapterTap(chapter) },
                    )
                }
            }

            NewChapterButton(palette = palette, onClick = onNewChapter)
            Spacer(Modifier.height(AppSpacing.s10))
        }
    }

    if (showAddVolumeDialog) {
        AddVolumeDialog(
            bookTitle = detail.notebook.title,
            onDismiss = { showAddVolumeDialog = false },
            onConfirm = { volumeLabel ->
                onAddVolume(volumeLabel)
                showAddVolumeDialog = false
            },
        )
    }
}

@Composable
private fun Hero(
    notebook: Notebook,
    palette: ShelfPalette,
    currentChapter: Chapter?,
    onBack: () -> Unit,
    onAddVolume: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.background)
            .padding(horizontal = AppSpacing.s5)
            .padding(top = AppSpacing.s5, bottom = AppSpacing.s6),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s4),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = palette.onBackground,
                modifier = Modifier
                    .size(20.dp)
                    .clickable { onBack() },
            )
            Spacer(Modifier.width(AppSpacing.s2))
            Text(
                text = breadcrumb(notebook),
                style = AppTypography.Eyebrow,
                color = palette.onBackground,
                modifier = Modifier.weight(1f),
            )
            // "+ Vol" affordance — promotes the book to a series on
            // first tap (via ensureSeriesFor) and appends the next
            // volume. Lives in the hero so it's discoverable from
            // wherever the user lands.
            Text(
                text = "+ Volume",
                style = AppTypography.Button,
                color = palette.onBackground,
                modifier = Modifier.clickable { onAddVolume() },
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s2)) {
            Text(
                text = eyebrowFor(notebook),
                style = AppTypography.Eyebrow,
                color = palette.onBackground,
            )
            Text(
                text = notebook.title,
                color = palette.onBackground,
                fontSize = 42.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Serif,
            )
            Text(
                text = notebookMeta(notebook),
                style = AppTypography.Meta,
                color = palette.onBackgroundMuted,
            )
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(palette.onBackgroundMuted.copy(alpha = 0.35f))
        )

        Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.s5)) {
            StatBlock(label = "READING",
                      value = currentChapter?.let { "Ch. %02d".format(it.position) } ?: "—",
                      palette = palette)
            StatBlock(label = "LAST EDIT",
                      value = relativeShort(notebook.updatedAt), palette = palette)
            StatBlock(label = "TAGGED",
                      value = firstTag(currentChapter) ?: "—", palette = palette)
        }
    }
}

@Composable
private fun StatBlock(label: String, value: String, palette: ShelfPalette) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s1)) {
        Text(label, style = AppTypography.Eyebrow, color = palette.onBackgroundMuted)
        Text(
            text = value,
            color = palette.onBackground,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Serif,
        )
    }
}

@Composable
private fun ChapterRow(
    chapter: Chapter,
    palette: ShelfPalette,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    val numberColor = if (isCurrent) AppColors.TextPrimary else palette.background.copy(alpha = 0.55f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.md))
            .background(if (isCurrent) AppColors.CardSolid else Color.Transparent)
            .then(
                if (isCurrent)
                    Modifier.border(1.dp, AppColors.BorderDefault, RoundedCornerShape(AppRadius.md))
                else Modifier
            )
            .clickable { onClick() }
            .padding(vertical = AppSpacing.s3, horizontal = AppSpacing.s3),
    ) {
        if (isCurrent) {
            Box(
                Modifier
                    .width(3.dp)
                    .height(48.dp)
                    .align(Alignment.CenterStart)
                    .background(palette.background, shape = RoundedCornerShape(AppRadius.pill))
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = if (isCurrent) AppSpacing.s3 else 0.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = "%02d".format(chapter.position),
                color = numberColor,
                fontSize = 38.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Serif,
                modifier = Modifier.width(64.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(chapter.title, style = AppTypography.SectionTitle, color = AppColors.TextPrimary)
                Text(chapterMeta(chapter), style = AppTypography.Meta, color = AppColors.TextSecondary)
            }
            if (isCurrent) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(AppRadius.pill))
                        .background(AppColors.SuccessSoft)
                        .padding(horizontal = AppSpacing.s3, vertical = 4.dp),
                ) {
                    Text("now", style = AppTypography.Tag, color = AppColors.GreenText)
                }
            }
        }
    }
}

@Composable
private fun NewChapterButton(palette: ShelfPalette, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.md))
            .border(1.5.dp, palette.background, RoundedCornerShape(AppRadius.md))
            .clickable { onClick() }
            .padding(vertical = AppSpacing.s3),
        contentAlignment = Alignment.Center,
    ) {
        Text("+ New chapter", style = AppTypography.Button, color = palette.background)
    }
}

/**
 * Simple dialog for the "+ Volume" hero action. Volume name is
 * optional — leaving it blank lets the repository fall back to
 * "<series> vol <n>" (which reads as "Plant log vol 2" etc.).
 */
@Composable
private fun AddVolumeDialog(
    bookTitle: String,
    onDismiss: () -> Unit,
    onConfirm: (String?) -> Unit,
) {
    var label by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                onConfirm(label.trim().ifEmpty { null })
            }) { Text("Add volume") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        title = { Text("Add a new volume", style = AppTypography.SectionTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s3)) {
                Text(
                    "A new volume will be added to \u201c$bookTitle\u201D. " +
                        "Leave the label blank to use the default \u201c$bookTitle vol N\u201D.",
                    style = AppTypography.Meta,
                    color = AppColors.TextSecondary,
                )
                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s1)) {
                    Text(
                        "Volume label (optional)",
                        style = AppTypography.Eyebrow,
                        color = AppColors.TextSecondary,
                    )
                    BasicTextField(
                        value = label,
                        onValueChange = { label = it },
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
            }
        },
    )
}

// ---------- helpers ----------

private fun breadcrumb(nb: Notebook): String =
    "SHELVES / ${nb.shelfName ?: "SHELF"}"

private fun eyebrowFor(nb: Notebook): String {
    val shelf = nb.shelfName ?: nb.title.uppercase()
    val vol   = nb.volumeNumber
    return if (vol != null) "$shelf · VOL %02d".format(vol) else shelf
}

private fun notebookMeta(nb: Notebook): String {
    val started = "Started " + DateTimeFormatter.ofPattern("MMM")
        .withZone(ZoneId.systemDefault())
        .format(nb.updatedAt)
    return "$started · ${nb.chapterCount} chapters · ${nb.pageCount} pages"
}

private fun chapterMeta(ch: Chapter): String {
    val parts = mutableListOf<String>()
    parts += "${ch.pages.size} page${if (ch.pages.size == 1) "" else "s"}"
    parts += "edited ${relativeShort(ch.updatedAt)}"
    val photos = ch.pages.sumOf { it.counts.photos }
    if (photos > 0) parts += "$photos photo${if (photos == 1) "" else "s"}"
    return parts.joinToString(" · ")
}

private fun firstTag(ch: Chapter?): String? =
    ch?.pages?.asSequence()?.mapNotNull { it.tags.firstOrNull() }?.firstOrNull()
