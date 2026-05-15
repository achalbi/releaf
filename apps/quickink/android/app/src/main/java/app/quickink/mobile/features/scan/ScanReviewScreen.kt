/*
 * ScanReviewScreen.kt
 *
 * Shown after the user finishes a scan. Layout (top → bottom):
 *
 *   1. Big category-button grid  — the user picks a category
 *      (or none) for the in-flight capture. Tap-to-toggle
 *      persists immediately via `controller.setCategory(name)`.
 *   2. Saved page preview        — the first-page JPEG the
 *      scanner produced, so the user can confirm what was saved
 *      while still on this surface.
 *   3. Status indicator          — small progress / saved /
 *      failed badge. The hero used to be the progress UI; now
 *      it sits beneath the actionable affordances.
 *   4. Done button                — terminal-state-only.
 *
 * Mirror of iOS `ScanReviewScreen.swift`.
 */

package app.quickink.mobile.features.scan

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.quickink.mobile.QuickInkApp
import app.quickink.mobile.data.folder.FolderEntity
import app.quickink.mobile.data.folder.FolderRepository
import app.quickink.mobile.data.tag.TagRepository
import app.quickink.mobile.features.workspace.AutoTagSuggester
import app.releaf.mobile.data.common.IsoClock
import app.releaf.mobile.data.common.Uuidv7
import app.quickink.mobile.features.onboarding.OnboardingPrimaryButton
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkRadius
import app.quickink.mobile.ui.theme.QuickInkSpacing
import app.quickink.mobile.ui.theme.quickInkDotGridBackground
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppSpacing
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import app.quickink.mobile.ui.theme.QuickInkFonts

@Composable
fun ScanReviewScreen(
    controller: ScanFlowController,
    userId: String,
) {
    val state by controller.state.collectAsState()
    val selectedFolderId by controller.selectedFolderId.collectAsState()
    val previewImageUri by controller.previewImageUri.collectAsState()
    val selectedPaperSize by controller.selectedPaperSize.collectAsState()
    val colors = LocalQuickInkColors.current

    val context = LocalContext.current
    val app = context.applicationContext as QuickInkApp
    val categoryRepo = remember(app) { TagRepository(app.database.tagDao()) }
    val categories by remember(userId, categoryRepo) {
        categoryRepo.observe(userId)
    }.collectAsState(initial = emptyList())
    // Folder picker — the scan-review primary picker is folders
    // now (the previous "category" grid attached a tag; that
    // surface moved to the post-save tag picker + AI-suggested
    // chips above). Folder writes the capture's `folder_id`
    // through `ScanFlowController.setFolder`.
    val folderRepo = remember(app) { FolderRepository(folderDao = app.database.folderDao()) }
    val folders by remember(userId, folderRepo) {
        folderRepo.observe(userId)
    }.collectAsState(initial = emptyList())

    val isFailed = state is ScanFlowController.State.Failed
    val isRecognizing = state is ScanFlowController.State.Recognizing

    // Workspace v1 Phase E.2 — auto-tag suggestions on the scan
    // review surface (per brief §5). Capture row + OCR rows exist
    // by the time we land here because the controller creates them
    // as each page completes; we read by captureId from state.
    val captureId = when (val s = state) {
        is ScanFlowController.State.Recognizing -> s.captureId
        is ScanFlowController.State.Complete    -> s.captureId
        else                                    -> null
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val suggestedTags = androidx.compose.runtime.remember(captureId, categories) {
        androidx.compose.runtime.mutableStateOf<List<String>>(emptyList())
    }
    val acceptedTagNames = androidx.compose.runtime.remember(captureId) {
        androidx.compose.runtime.mutableStateOf<Set<String>>(emptySet())
    }
    androidx.compose.runtime.LaunchedEffect(captureId, state, categories) {
        val id = captureId ?: return@LaunchedEffect
        // Wait for at least one page to finish OCR so suggestions
        // have something to fire on.
        val capture = app.database.captureDao().findById(id) ?: return@LaunchedEffect
        val ocrText = app.database.ocrResultDao().findFirstTextForCapture(id)
        suggestedTags.value = AutoTagSuggester.suggest(
            ocrText           = ocrText,
            existingTagNames  = categories.map { it.name }.toSet(),
            currentlyAttached = acceptedTagNames.value,
            captureDateIso    = capture.createdAt,
        )
    }

    // Lift the content past the system status bar + add visual
    // breathing room so the category buttons clear the notch on
    // edge-to-edge devices. Same pattern as Library / Settings.
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .quickInkDotGridBackground(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(
                    start  = QuickInkSpacing.s5,
                    end    = QuickInkSpacing.s5,
                    top    = statusBarTop + QuickInkSpacing.s7,
                    bottom = QuickInkSpacing.s5,
                ),
            verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s5),
        ) {
            // Phase E.2 — AI-suggested chip strip above the
            // category grid. Renders only while we have a
            // captureId in flight and the suggester produced hits.
            val cid = captureId
            val suggestions = suggestedTags.value
            if (cid != null && suggestions.isNotEmpty() && !isFailed) {
                ScanReviewSuggestions(
                    names = suggestions,
                    onAccept = { name ->
                        scope.launch {
                            val tag = categoryRepo.findOrCreate(userId, name)
                            val now = IsoClock.nowIso()
                            app.database.captureTagDao().attachTag(
                                joinId    = Uuidv7.generate(),
                                captureId = cid,
                                tagId     = tag.id,
                                source    = "ai-suggested",
                                timestamp = now,
                            )
                            acceptedTagNames.value = acceptedTagNames.value + name
                            // Drop the accepted chip immediately
                            // so the strip shows what's left to act
                            // on. Re-running the suggester would
                            // re-emit nothing because the new
                            // currentlyAttached set excludes this
                            // tag.
                            suggestedTags.value = suggestions.filter { it != name }
                        }
                    },
                )
            }

            if (folders.isNotEmpty() && !isFailed) {
                FolderButtonsGrid(
                    folders          = folders,
                    selectedFolderId = selectedFolderId,
                    onSelect         = { controller.setFolder(it) },
                )
            }

            if (!isFailed) {
                PaperSizeChipRow(
                    selected = selectedPaperSize,
                    onSelect = { controller.setPaperSize(it) },
                )
            }

            if (!isFailed) {
                SavedImagePreview(previewImageUri = previewImageUri)
            }

            StatusIndicator(state = state)
        }

        if (!isRecognizing) {
            OnboardingPrimaryButton(
                label   = "Done",
                onClick = { controller.dismiss() },
            )
            Spacer(Modifier.size(AppSpacing.s5))
        }
    }
}

/**
 * Two-column grid of folder buttons. Each button writes the
 * capture's `folder_id` through `ScanFlowController.setFolder`.
 * The selected button paints with the folder's stored color so
 * the picker reads the same as the Workspace home folder list.
 */
@Composable
private fun FolderButtonsGrid(
    folders: List<FolderEntity>,
    selectedFolderId: String?,
    onSelect: (String) -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current

    Column(verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3)) {
        Text(
            text  = "FOLDER",
            style = type.eyebrow,
            color = colors.muted,
        )

        // Manual two-column rows because the surrounding column is
        // a verticalScroll (LazyVerticalGrid + verticalScroll don't
        // compose). Pairs the folder list into rows of 2.
        folders.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3)) {
                pair.forEach { folder ->
                    val selected = folder.id == selectedFolderId
                    Box(modifier = Modifier.weight(1f)) {
                        FolderButton(
                            folder   = folder,
                            selected = selected,
                            onClick  = { onSelect(folder.id) },
                        )
                    }
                }
                if (pair.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun FolderButton(folder: FolderEntity, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current
    // Folder color drives the active fill so the button reads the
    // same as the corresponding folder tile on the Workspace home.
    // Falls back to the accent if the stored hex doesn't parse.
    val folderColor = remember(folder.color) {
        runCatching { Color(android.graphics.Color.parseColor(folder.color)) }
            .getOrDefault(colors.accent)
    }
    val bg          = if (selected) folderColor else colors.surface
    val fg          = if (selected) colors.textOnAccent else colors.ink
    val borderColor = if (selected) folderColor else colors.border
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(QuickInkRadius.md))
            .background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(QuickInkRadius.md))
            .clickable(onClick = onClick)
            .padding(horizontal = QuickInkSpacing.s2, vertical = QuickInkSpacing.s2),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
        ) {
            // Small filled swatch so unselected rows still telegraph
            // their folder color. Hidden on the active row because
            // the button itself is already painted the same hue.
            if (!selected) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(folderColor),
                )
            }
            Text(
                text      = folder.name,
                style     = type.cardTitle,
                color     = fg,
                textAlign = TextAlign.Center,
                maxLines  = 2,
            )
        }
    }
}

/**
 * Four-up chip row letting the user disambiguate the auto-detected
 * paper-size class (A4 / A5 / Letter / Custom). The auto-classifier
 * seeds the selection from the first page's rectified aspect ratio
 * plus the user's last pick — A4 vs A5 can't be told apart from
 * ratio alone (both are 1:√2 by ISO design), so this is the user's
 * escape hatch.
 *
 * `Card` isn't surfaced here because card-shaped captures flow
 * through the dedicated business-card capture surface, which writes
 * `PaperSize.Card` directly without going through this review
 * screen.
 */
@Composable
private fun PaperSizeChipRow(
    selected: PaperSize,
    onSelect: (PaperSize) -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current
    val options = listOf(
        PaperSize.A4     to "A4",
        PaperSize.A5     to "A5",
        PaperSize.Letter to "Letter",
        PaperSize.Custom to "Custom",
    )
    Column(verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2)) {
        Text(
            text       = "PAPER SIZE",
            style      = type.eyebrow,
            color      = colors.muted,
            fontFamily = QuickInkFonts.ui,
        )
        Row(
            modifier             = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            options.forEach { (size, label) ->
                PaperSizeChip(
                    label    = label,
                    selected = size == selected,
                    onClick  = { onSelect(size) },
                )
            }
        }
    }
}

@Composable
private fun PaperSizeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    Text(
        text       = label,
        fontFamily = QuickInkFonts.ui,
        fontSize   = 12.sp,
        fontWeight = FontWeight.Medium,
        color      = if (selected) colors.textOnAccent else colors.ink,
        modifier   = Modifier
            .clip(PaperSizeChipShape)
            .clickable(onClick = onClick)
            .background(
                color = if (selected) colors.accent else Color.White.copy(alpha = 0.85f),
                shape = PaperSizeChipShape,
            )
            .border(
                width = 1.dp,
                color = if (selected) colors.accent else colors.accent.copy(alpha = 0.25f),
                shape = PaperSizeChipShape,
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

private val PaperSizeChipShape = RoundedCornerShape(percent = 50)

@Composable
private fun SavedImagePreview(previewImageUri: String?) {
    val colors = LocalQuickInkColors.current
    val context = LocalContext.current

    if (previewImageUri.isNullOrBlank()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
                .clip(RoundedCornerShape(QuickInkRadius.md))
                .background(colors.borderSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector       = Icons.Filled.Description,
                contentDescription = null,
                tint              = colors.muted,
                modifier          = Modifier.size(48.dp),
            )
        }
    } else {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(Uri.parse(previewImageUri))
                .crossfade(true)
                .build(),
            contentDescription = "Saved scan preview",
            contentScale       = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 360.dp)
                .clip(RoundedCornerShape(QuickInkRadius.md))
                .background(colors.surface)
                .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.md)),
        )
    }
}

@Composable
private fun StatusIndicator(state: ScanFlowController.State) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current

    when (state) {
        is ScanFlowController.State.Idle -> { /* not rendered */ }

        is ScanFlowController.State.Recognizing -> {
            Row(
                modifier            = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment   = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    color    = colors.accent,
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.size(QuickInkSpacing.s2))
                Text(
                    text  = "Recognizing page ${state.completedPages} of ${state.totalPages}",
                    style = type.body,
                    color = colors.inkSoft,
                )
            }
        }

        is ScanFlowController.State.Complete -> {
            Row(
                modifier            = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment   = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector       = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint              = colors.success,
                    modifier          = Modifier.size(20.dp),
                )
                Spacer(Modifier.size(QuickInkSpacing.s2))
                Text(
                    text  = "Saved — text on ${state.successCount} of ${state.totalPages} pages",
                    style = type.body,
                    color = colors.inkSoft,
                )
            }
        }

        is ScanFlowController.State.Failed -> {
            Column(
                modifier             = Modifier.fillMaxWidth().padding(vertical = QuickInkSpacing.s5),
                horizontalAlignment  = Alignment.CenterHorizontally,
                verticalArrangement  = Arrangement.spacedBy(QuickInkSpacing.s2),
            ) {
                Icon(
                    imageVector       = Icons.Filled.Warning,
                    contentDescription = null,
                    tint              = colors.warning,
                    modifier          = Modifier.size(32.dp),
                )
                Text("Couldn't save", style = type.heading, color = colors.ink)
                Text(
                    text     = state.message,
                    style    = type.body,
                    color    = colors.inkSoft,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * Workspace v1 Phase E.2 — AI-suggested tag chips. Surfaces tags
 * inferred from the in-flight capture's OCR text. The user can
 * tap "+#name" to attach immediately (writes to capture_tags with
 * source = "ai-suggested"); rejecting is implicit — chips not
 * tapped are simply discarded when the user leaves the screen.
 */
@Composable
private fun ScanReviewSuggestions(
    names: List<String>,
    onAccept: (String) -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current
    val cardShape = RoundedCornerShape(QuickInkRadius.md)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(colors.accentSoft.copy(alpha = 0.4f), cardShape)
            .padding(QuickInkSpacing.s3),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text  = "SUGGESTED FROM THIS SCAN",
                style = type.label.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp),
                color = colors.accentDeep,
            )
        }
        Spacer(Modifier.height(QuickInkSpacing.s2))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement   = Arrangement.spacedBy(6.dp),
        ) {
            names.forEach { name ->
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(
                            Color.White.copy(alpha = 0.85f),
                            RoundedCornerShape(999.dp),
                        )
                        .border(
                            1.dp,
                            colors.accent.copy(alpha = 0.25f),
                            RoundedCornerShape(999.dp),
                        )
                        .clickable { onAccept(name) }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text  = "+",
                        style = type.label.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                        color = colors.accentDeep,
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        text  = "#",
                        style = type.label.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Bold),
                        color = colors.accent.copy(alpha = 0.7f),
                    )
                    Text(
                        text  = name,
                        style = type.label.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Medium),
                        color = colors.accentDeep,
                        modifier = Modifier.padding(start = 1.dp),
                    )
                }
            }
        }
    }
}
