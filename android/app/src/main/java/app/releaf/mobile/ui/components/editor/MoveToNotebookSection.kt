/*
 * MoveToNotebookSection.kt
 *
 * Notepad-editor panel that converts a daily entry into a notebook
 * page inside a chosen chapter. Renders at the bottom of the notepad
 * editor, below the other feature sections, mirroring the position of
 * [MergeSection].
 *
 * Contract:
 *   - Tap "Choose a notebook and chapter" → opens a modal picker with
 *     the user's active notebooks; tapping a notebook reveals its
 *     chapters; tapping a chapter selects a destination and closes
 *     the sheet.
 *   - "Move" button fires `onMove(chapterId)` once a chapter is
 *     selected. Callers are expected to navigate away because the
 *     source notepad entry is soft-deleted.
 *   - Disabled entirely on a fresh unsaved draft (nothing to move) —
 *     the caller passes `enabled = false` in that case.
 */

package app.releaf.mobile.ui.components.editor

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.releaf.mobile.data.notebook.ChapterEntity
import app.releaf.mobile.data.notebook.NotebookEntity
import app.releaf.mobile.ui.components.AppButton
import app.releaf.mobile.ui.components.AppButtonVariant
import app.releaf.mobile.ui.theme.AppAccent
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppRadius
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography

/**
 * Data blob returned by the picker. Both labels are already resolved
 * (notebook + chapter title) so the selected-destination card doesn't
 * need to re-query the DB every recomposition.
 */
data class MoveDestination(
    val notebookId: String,
    val notebookTitle: String,
    val chapterId: String,
    val chapterTitle: String,
)

@Composable
fun MoveToNotebookSection(
    notebooks: List<NotebookEntity>,
    chaptersForPicker: List<ChapterEntity>,
    onOpenChaptersFor: (notebookId: String?) -> Unit,
    enabled: Boolean,
    onMove: (chapterId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selected by remember { mutableStateOf<MoveDestination?>(null) }
    var showPicker by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            "MOVE TO NOTEBOOK",
            style = AppTypography.Eyebrow,
            color = AppAccent.primary,
            modifier = Modifier.padding(bottom = AppSpacing.s2),
        )

        // Outer card — matches the visual language of the other editor
        // sections (CardSolid surface, rounded corners, hairline
        // border).
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(AppRadius.lg))
                .background(AppColors.CardSolid)
                .border(
                    width = 1.dp,
                    color = AppColors.BorderDefault,
                    shape = RoundedCornerShape(AppRadius.lg),
                ),
        ) {
            HeaderRow()
            SectionDivider()

            Column(
                modifier = Modifier.padding(AppSpacing.s4),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.s3),
            ) {
                Text(
                    "Move this page into a chapter",
                    style = AppTypography.SectionTitle.copy(fontSize = 16.sp),
                    color = AppColors.TextPrimary,
                )
                Text(
                    "Convert this daily page into a notebook page inside " +
                        "one of your chapters.",
                    style = AppTypography.Body,
                    color = AppColors.TextSecondary,
                )

                Text(
                    "Destination",
                    style = AppTypography.SectionTitle.copy(fontSize = 16.sp),
                    color = AppColors.TextPrimary,
                    modifier = Modifier.padding(top = AppSpacing.s2),
                )

                DestinationCard(
                    destination = selected,
                    enabled = enabled,
                    onTap = {
                        if (enabled) {
                            onOpenChaptersFor(null)
                            showPicker = true
                        }
                    },
                )

                Text(
                    text = if (selected != null) {
                        "Tap \u201CMove\u201D to convert this page into a " +
                            "notebook page."
                    } else {
                        "Choose a notebook in the modal, then pick a " +
                            "chapter to continue."
                    },
                    style = AppTypography.Meta,
                    color = AppColors.TextTertiary,
                )

                val isReady = enabled && selected != null
                AppButton(
                    text = "Move to notebook",
                    onClick = {
                        val dest = selected ?: return@AppButton
                        if (!enabled) return@AppButton
                        onMove(dest.chapterId)
                    },
                    variant = if (isReady) AppButtonVariant.Primary
                              else AppButtonVariant.Secondary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    if (showPicker) {
        DestinationPickerSheet(
            notebooks = notebooks,
            chapters = chaptersForPicker,
            onExpandNotebook = { nb -> onOpenChaptersFor(nb?.id) },
            onSelectChapter = { nb, ch ->
                selected = MoveDestination(
                    notebookId    = nb.id,
                    notebookTitle = nb.title,
                    chapterId     = ch.id,
                    chapterTitle  = ch.title,
                )
                onOpenChaptersFor(null)
                showPicker = false
            },
            onDismiss = {
                onOpenChaptersFor(null)
                showPicker = false
            },
        )
    }
}

@Composable
private fun HeaderRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(AppSpacing.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text  = "Move to notebook",
            style = AppTypography.SectionTitle,
            color = AppColors.TextPrimary,
            modifier = Modifier.weight(1f),
        )
        // "Notepad to notebook" pill — just the label, no affordance.
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(AppRadius.pill))
                .background(AppColors.Subtle)
                .padding(horizontal = AppSpacing.s3, vertical = 6.dp),
        ) {
            Text(
                text  = "Notepad to notebook",
                style = AppTypography.Tag,
                color = AppColors.TextSecondary,
            )
        }
    }
}

@Composable
private fun DestinationCard(
    destination: MoveDestination?,
    enabled: Boolean,
    onTap: () -> Unit,
) {
    val bg = if (enabled) AppAccent.soft else AppColors.Subtle
    val borderColor = if (enabled) AppAccent.border else AppColors.BorderDefault
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.md))
            .background(bg)
            .border(
                width = 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(AppRadius.md),
            )
            .clickable(enabled = enabled, onClick = onTap)
            .padding(AppSpacing.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text  = "SELECTED DESTINATION",
                style = AppTypography.Eyebrow,
                color = AppColors.TextTertiary,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = destination?.let { "${it.notebookTitle} \u203A ${it.chapterTitle}" }
                    ?: "Choose a notebook and chapter",
                style = AppTypography.SectionTitle.copy(fontSize = 16.sp),
                color = if (enabled) AppColors.TextPrimary else AppColors.TextTertiary,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
            contentDescription = null,
            tint = if (enabled) AppColors.TextSecondary else AppColors.TextTertiary,
            modifier = Modifier.size(16.dp),
        )
    }
}

/** Two-step picker in a bottom sheet. Tap a notebook to expand its
 *  chapter list inline; tap a chapter to select. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DestinationPickerSheet(
    notebooks: List<NotebookEntity>,
    chapters: List<ChapterEntity>,
    onExpandNotebook: (NotebookEntity?) -> Unit,
    onSelectChapter: (NotebookEntity, ChapterEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var localExpanded by remember { mutableStateOf<NotebookEntity?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        containerColor   = AppColors.Canvas,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.s4),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.s3),
        ) {
            Text(
                text  = "Choose destination",
                style = AppTypography.SectionTitle,
                color = AppColors.TextPrimary,
            )
            Text(
                text  = "Pick a notebook, then a chapter.",
                style = AppTypography.Meta,
                color = AppColors.TextSecondary,
            )
            if (notebooks.isEmpty()) {
                Text(
                    "No notebooks yet \u2014 create one first.",
                    style = AppTypography.Body,
                    color = AppColors.TextSecondary,
                    modifier = Modifier.padding(vertical = AppSpacing.s6),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.s2),
                ) {
                    items(notebooks, key = { it.id }) { nb ->
                        val isExpanded = localExpanded?.id == nb.id
                        NotebookPickRow(
                            notebook = nb,
                            expanded = isExpanded,
                            onTap = {
                                if (isExpanded) {
                                    localExpanded = null
                                    onExpandNotebook(null)
                                } else {
                                    localExpanded = nb
                                    onExpandNotebook(nb)
                                }
                            },
                        )
                        if (isExpanded) {
                            ChapterPickList(
                                chapters = chapters,
                                onPick   = { ch -> onSelectChapter(nb, ch) },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(AppSpacing.s3))
        }
    }
}

@Composable
private fun NotebookPickRow(
    notebook: NotebookEntity,
    expanded: Boolean,
    onTap: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.md))
            .background(if (expanded) AppAccent.soft else AppColors.CardSolid)
            .border(
                width = 1.dp,
                color = if (expanded) AppAccent.border else AppColors.BorderDefault,
                shape = RoundedCornerShape(AppRadius.md),
            )
            .clickable(onClick = onTap)
            .padding(horizontal = AppSpacing.s4, vertical = AppSpacing.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text  = notebook.title,
                style = AppTypography.SectionTitle.copy(fontSize = 16.sp),
                color = AppColors.TextPrimary,
            )
            notebook.description?.takeIf { it.isNotBlank() }?.let { desc ->
                Text(
                    text  = desc,
                    style = AppTypography.Meta,
                    color = AppColors.TextSecondary,
                )
            }
        }
        Icon(
            imageVector        = Icons.AutoMirrored.Filled.ArrowForwardIos,
            contentDescription = null,
            tint               = AppColors.TextSecondary,
            modifier           = Modifier.size(14.dp),
        )
    }
}

@Composable
private fun ChapterPickList(
    chapters: List<ChapterEntity>,
    onPick: (ChapterEntity) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = AppSpacing.s4, top = AppSpacing.s2),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s1),
    ) {
        if (chapters.isEmpty()) {
            Text(
                "No chapters in this notebook yet.",
                style = AppTypography.Meta,
                color = AppColors.TextTertiary,
                modifier = Modifier.padding(AppSpacing.s3),
            )
        } else {
            chapters.forEach { ch ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(AppRadius.sm))
                        .clickable { onPick(ch) }
                        .padding(horizontal = AppSpacing.s3, vertical = AppSpacing.s3),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector        = Icons.Filled.Check,
                        contentDescription = null,
                        tint               = AppAccent.primary,
                        modifier           = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.size(AppSpacing.s2))
                    Text(
                        text  = ch.title,
                        style = AppTypography.Body,
                        color = AppColors.TextPrimary,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(AppColors.BorderDefault),
    )
}
