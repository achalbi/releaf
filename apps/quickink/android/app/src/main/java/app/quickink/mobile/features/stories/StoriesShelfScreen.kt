/*
 * StoriesShelfScreen.kt
 *
 * The Stories tab — §7.1 of design/stories-mockup-v3.html. Mirror of
 * iOS `StoriesShelfScreen.swift`; see that file's header for the
 * ASCII layout.
 *
 * Phase 1 surface: reads from [StoriesShelfViewModel], renders the
 * page title, search bar (non-functional placeholder for Phase 1),
 * suggestion hero card (only when a suggestion exists — Phase 5
 * fills the cache), empty-state hero (when both rows + suggestion
 * are absent), the "Your stories" list, and a coral "+" FAB whose
 * tap target is stubbed (Phase 2 wires the creation flow).
 */

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.quickink.mobile.features.stories

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import app.quickink.mobile.data.story.StoryEntity
import app.quickink.mobile.data.story.StoryShelfRow
import app.quickink.mobile.features.nav.QuickInkBottomNavReservedHeight
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkRadius
import app.quickink.mobile.ui.theme.QuickInkSpacing
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun StoriesShelfScreen(
    userId: String,
    /** Phase 2 navigation hook — opens the story in the editor.
     *  Called both when the user taps a shelf card (passes its id)
     *  AND when the "+" FAB lands after creating a fresh draft. */
    onOpenStory: (String) -> Unit = { _ -> },
    /** Phase 5 — push the suggestion preview screen. */
    onOpenSuggestionPreview: (String) -> Unit = { _ -> },
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current

    val vm: StoriesShelfViewModel = viewModel(
        factory = StoriesShelfViewModel.factory(userId),
    )
    val rows by vm.rows.collectAsState()
    val suggestion by vm.suggestion.collectAsState()

    var searchDraft by remember { mutableStateOf("") }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start  = QuickInkSpacing.s4,
                end    = QuickInkSpacing.s4,
                top    = QuickInkSpacing.s2,
                bottom = QuickInkBottomNavReservedHeight,
            ),
            verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s4),
        ) {
            item("title") {
                Text(
                    text  = "Stories",
                    style = type.display,
                    color = colors.ink,
                )
            }

            item("search") {
                SearchBar(
                    value          = searchDraft,
                    onValueChange  = { searchDraft = it },
                )
            }

            if (suggestion != null) {
                item("hero") {
                    SuggestionHero(
                        reason     = suggestion!!.reason,
                        onDismiss  = { vm.dismissSuggestion() },
                        onPreview  = { onOpenSuggestionPreview(suggestion!!.id) },
                    )
                }
            } else if (rows.isEmpty()) {
                item("hero-empty") { EmptyStateHero() }
            }

            if (rows.isNotEmpty()) {
                item("section-header") {
                    Text(
                        text  = "Your stories",
                        style = type.editorial,
                        color = colors.ink,
                        modifier = Modifier.padding(top = QuickInkSpacing.s1),
                    )
                }
                items(rows, key = { it.story.id }) { row ->
                    Box(
                        modifier = Modifier.clickable { onOpenStory(row.story.id) },
                    ) {
                        StoryShelfCard(row = row)
                    }
                }
            }
        }

        Fab(
            onClick = {
                scope.launch {
                    val id = vm.createDraft()
                    if (id != null) onOpenStory(id)
                }
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = QuickInkSpacing.s4)
                .padding(bottom = QuickInkBottomNavReservedHeight - 32.dp),
        )
    }
}

// MARK: - Search bar

@Composable
private fun SearchBar(value: String, onValueChange: (String) -> Unit) {
    val colors = LocalQuickInkColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(QuickInkRadius.md))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.md))
            .padding(horizontal = QuickInkSpacing.s3, vertical = QuickInkSpacing.s2 + 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector        = Icons.Filled.Search,
            contentDescription = null,
            tint               = colors.inkSoft,
            modifier           = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(QuickInkSpacing.s2))
        Box(modifier = Modifier.fillMaxWidth()) {
            if (value.isEmpty()) {
                Text(
                    text  = "Search your stories",
                    color = colors.inkSoft,
                    fontSize = 14.sp,
                )
            }
            BasicTextField(
                value            = value,
                onValueChange    = onValueChange,
                singleLine       = true,
                textStyle        = androidx.compose.ui.text.TextStyle(
                    color    = colors.ink,
                    fontSize = 14.sp,
                ),
                cursorBrush      = androidx.compose.ui.graphics.SolidColor(colors.accent),
                keyboardOptions  = KeyboardOptions.Default,
                modifier         = Modifier.fillMaxWidth(),
            )
        }
    }
}

// MARK: - Suggestion hero (Phase 5 fills this; v3 ships with it always
// off and the empty-state hero in its place)

@Composable
private fun SuggestionHero(
    reason: String,
    onDismiss: () -> Unit,
    onPreview: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(QuickInkRadius.lg))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.lg))
            .padding(QuickInkSpacing.s3),
        verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
    ) {
        Text(
            text       = "SUGGESTED · TODAY",
            color      = colors.accentDeep,
            fontSize   = 10.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.5.sp,
        )
        PreviewStrip()
        Text(
            text       = reason,
            style      = type.editorial,
            color      = colors.ink,
            maxLines   = 2,
            overflow   = TextOverflow.Ellipsis,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = QuickInkSpacing.s1),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text  = "Not interested",
                style = type.bodyItalic,
                color = colors.muted,
                fontSize = 11.sp,
                modifier = Modifier.clickable(onClick = onDismiss),
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text       = "Open preview →",
                color      = colors.accent,
                fontSize   = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable(onClick = onPreview),
            )
        }
    }
}

@Composable
private fun EmptyStateHero() {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(QuickInkRadius.lg))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.lg))
            .padding(QuickInkSpacing.s4),
        verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
    ) {
        Text(
            text       = "YOUR STORIES",
            color      = colors.accentDeep,
            fontSize   = 10.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.5.sp,
        )
        Text(
            text  = "Curate a moment.",
            style = type.editorial,
            color = colors.ink,
        )
        Text(
            text  = "Pick a few scans, photos, or notes and assemble them into a story you can share.",
            style = type.bodyItalic,
            color = colors.inkSoft,
        )
    }
}

@Composable
private fun PreviewStrip() {
    val colors = LocalQuickInkColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(70.dp)
            .clip(RoundedCornerShape(8.dp)),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .weight(2f)
                .fillMaxSize()
                .clip(RoundedCornerShape(4.dp))
                .background(colors.paper1),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize()
                .clip(RoundedCornerShape(4.dp))
                .background(colors.paper3),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize()
                .clip(RoundedCornerShape(4.dp))
                .background(colors.accent.copy(alpha = 0.55f)),
        )
    }
}

// MARK: - Story shelf card

@Composable
private fun StoryShelfCard(row: StoryShelfRow) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(QuickInkRadius.md + 2.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.md + 2.dp))
            .padding(QuickInkSpacing.s2 + 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3),
    ) {
        Cover(story = row.story)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text       = row.story.title,
                style      = type.editorial,
                color      = colors.ink,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
            )
            Text(
                text  = metaLine(row),
                color = colors.inkSoft,
                fontSize = 11.sp,
            )
            SharePill(story = row.story)
        }
    }
}

@Composable
private fun Cover(story: StoryEntity) {
    val colors = LocalQuickInkColors.current
    val fill = when (story.coverStyle) {
        StoryEntity.CoverStyle.GRADIENT.raw    -> colors.accent.copy(alpha = 0.7f)
        StoryEntity.CoverStyle.TYPOGRAPHIC.raw -> colors.paper3
        else                                   -> colors.paper1
    }
    val captionTint = if (story.coverStyle == StoryEntity.CoverStyle.GRADIENT.raw)
        colors.textOnAccent else colors.ink
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(fill),
        contentAlignment = Alignment.BottomStart,
    ) {
        Text(
            text       = coverCaption(story.title),
            color      = captionTint,
            fontSize   = 7.5.sp,
            fontWeight = FontWeight.Medium,
            modifier   = Modifier.padding(horizontal = 5.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun SharePill(story: StoryEntity) {
    val colors = LocalQuickInkColors.current
    val (text, bg, fg) = when {
        story.shareMode == StoryEntity.ShareMode.PUBLIC_LINK.raw ->
            Triple("Public link", colors.accentSoft, colors.accent)
        story.shareMode == StoryEntity.ShareMode.EXPORTED.raw ||
            story.shareMode == StoryEntity.ShareMode.IN_APP.raw ->
            Triple("Shared", colors.accentSoft, colors.accent)
        story.status == StoryEntity.Status.DRAFT.raw ->
            Triple("Draft", colors.borderSoft, colors.inkSoft)
        else -> return
    }
    Box(
        modifier = Modifier
            .padding(top = 4.dp)
            .clip(RoundedCornerShape(QuickInkRadius.pill))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text       = text,
            color      = fg,
            fontSize   = 10.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

// MARK: - FAB

@Composable
private fun Fab(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalQuickInkColors.current
    val gradient = Brush.verticalGradient(
        colors = listOf(colors.accent, colors.accentDeep),
    )
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(56.dp)
            .shadow(elevation = 12.dp, shape = CircleShape, clip = false)
            .clip(CircleShape)
            .background(gradient)
            .clickable(
                interactionSource = interactionSource,
                indication        = null,
                onClick           = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector        = Icons.Filled.Add,
            contentDescription = "Create a new story",
            tint               = colors.textOnAccent,
            modifier           = Modifier.size(24.dp),
        )
    }
}

// MARK: - Helpers

private fun coverCaption(title: String): String {
    val first = title.split(' ').firstOrNull()?.takeIf { it.isNotEmpty() } ?: title
    return first.take(8)
}

private fun metaLine(row: StoryShelfRow): String {
    val countPart = when (row.itemCount) {
        0    -> "no items yet"
        1    -> "1 item"
        else -> "${row.itemCount} items"
    }
    val monthYear = row.latestItemAt?.let { parseMonthYear(it) }
    return if (monthYear == null) countPart else "$countPart · $monthYear"
}

private val MONTH_YEAR_FMT = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH)

/**
 * Parse the schema's ISO-8601 timestamps into "Apr 2026". The
 * canonical writer (`IsoClock.nowIso`) emits fractional seconds; we
 * accept either shape via `OffsetDateTime.parse`, which handles
 * both.
 */
private fun parseMonthYear(iso: String): String? = runCatching {
    OffsetDateTime.parse(iso).format(MONTH_YEAR_FMT)
}.getOrNull()
