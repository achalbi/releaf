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
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.quickink.mobile.QuickInkApp
import app.quickink.mobile.data.tag.TagRepository
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

@Composable
fun ScanReviewScreen(
    controller: ScanFlowController,
    userId: String,
) {
    val state by controller.state.collectAsState()
    val selectedCategory by controller.selectedCategory.collectAsState()
    val previewImageUri by controller.previewImageUri.collectAsState()
    val colors = LocalQuickInkColors.current

    val context = LocalContext.current
    val app = context.applicationContext as QuickInkApp
    val categoryRepo = remember(app) { TagRepository(app.database.tagDao()) }
    val categories by remember(userId, categoryRepo) {
        categoryRepo.observe(userId)
    }.collectAsState(initial = emptyList())

    val isFailed = state is ScanFlowController.State.Failed
    val isRecognizing = state is ScanFlowController.State.Recognizing

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
            if (categories.isNotEmpty() && !isFailed) {
                CategoryButtonsGrid(
                    categories       = categories.map { it.name },
                    selectedCategory = selectedCategory,
                    onSelect         = { controller.setCategory(it) },
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
 * Two-column grid of bigger category buttons. Replaces the previous
 * compact chip row — the picker is now the primary affordance on
 * this screen, so it gets full-width buttons with serif headings
 * instead of small pills.
 */
@Composable
private fun CategoryButtonsGrid(
    categories: List<String>,
    selectedCategory: String?,
    onSelect: (String?) -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current

    Column(verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3)) {
        Text(
            text  = "CATEGORY",
            style = type.eyebrow,
            color = colors.muted,
        )

        // Manual two-column rows because we sit inside a verticalScroll
        // (LazyVerticalGrid + verticalScroll don't compose). Pairs the
        // categories into rows of 2.
        categories.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3)) {
                pair.forEach { name ->
                    val selected = name == selectedCategory
                    Box(modifier = Modifier.weight(1f)) {
                        CategoryButton(
                            name     = name,
                            selected = selected,
                            onClick  = {
                                // Tap-to-toggle: tapping the active
                                // button clears the selection.
                                onSelect(if (selected) null else name)
                            },
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
private fun CategoryButton(name: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    val bg = if (selected) colors.accent else colors.surface
    val fg = if (selected) colors.textOnAccent else colors.ink
    val borderColor = if (selected) colors.accent else colors.border
    // Compact button — was minHeight 64dp / padding s3 / type.heading,
    // which made the picker feel like a hero grid. Shrunk to a tap-
    // friendly 44dp tall with cardTitle so the page reads as a scan
    // review with categories beneath, not a category-picker hero.
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
        Text(
            text     = name,
            style    = type.cardTitle,
            color    = fg,
            textAlign = TextAlign.Center,
            maxLines  = 2,
        )
    }
}

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
