/*
 * StorySuggestionPreviewScreen.kt
 *
 * Stories Phase 5 — mirror of iOS `StorySuggestionPreviewScreen.swift`.
 * See that file's header for the §7.2 mockup ASCII.
 */

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.quickink.mobile.features.stories

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.quickink.mobile.QuickInkApp
import app.quickink.mobile.data.story.StoryRepository
import app.quickink.mobile.data.storyitem.StoryItemEntity
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkRadius
import app.quickink.mobile.ui.theme.QuickInkSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun StorySuggestionPreviewScreen(
    suggestionId: String,
    userId: String,
    onBack: () -> Unit,
    onOpenStory: (String) -> Unit,
) {
    val colors  = LocalQuickInkColors.current
    val type    = LocalQuickInkTypography.current
    val context = LocalContext.current
    val app     = remember(context) { context.applicationContext as QuickInkApp }
    val scope   = rememberCoroutineScope()

    var suggestion by remember { mutableStateOf<StorySuggestion?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var creating   by remember { mutableStateOf(false) }
    var toast      by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(suggestionId) {
        val result = withContext(Dispatchers.Default) {
            StorySuggestionEngine.compute(
                userId     = userId,
                captureDao = app.database.captureDao(),
                dismissed  = emptySet(),
            )
        }
        if (result != null && result.id == suggestionId) suggestion = result
        else loadFailed = true
    }
    LaunchedEffect(toast) {
        if (toast != null) { delay(1_800); toast = null }
    }

    fun makeStory() {
        val s = suggestion ?: return
        creating = true
        scope.launch {
            val repo = StoryRepository(
                storyDao          = app.database.storyDao(),
                storyItemDao      = app.database.storyItemDao(),
                storyVoiceClipDao = app.database.storyVoiceClipDao(),
            )
            val title = deriveTitle(s)
            val story = repo.insertStory(
                userId   = userId,
                title    = title,
                subtitle = null,
            )
            if (story != null) {
                s.candidateRefs.forEachIndexed { idx, captureId ->
                    repo.insertItem(
                        storyId    = story.id,
                        position   = (idx + 1) * 1024,
                        kind       = StoryItemEntity.Kind.DOCUMENT,
                        refId      = captureId,
                        text       = null,
                        caption    = null,
                        occurredAt = null,
                        layout     = StoryItemEntity.Layout.FULL,
                    )
                }
                creating = false
                onOpenStory(story.id)
            } else {
                creating = false
                toast = "Couldn't create the story."
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(colors.bg)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start  = QuickInkSpacing.s4,
                end    = QuickInkSpacing.s4,
                top    = QuickInkSpacing.s2,
                bottom = 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3),
        ) {
            item("back") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable(onClick = onBack).padding(vertical = 4.dp),
                ) {
                    Icon(
                        imageVector        = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = null,
                        tint               = colors.inkSoft,
                        modifier           = Modifier.size(18.dp),
                    )
                    Text(
                        text       = "Stories",
                        color      = colors.inkSoft,
                        fontSize   = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            val s = suggestion
            when {
                s != null -> {
                    item("title") {
                        Text(deriveTitle(s),
                            fontSize = 22.sp, fontWeight = FontWeight.Medium,
                            color = colors.ink, style = type.display.copy(lineHeight = 26.sp))
                    }
                    item("reason") {
                        Text(
                            text     = s.reason,
                            style    = type.bodyItalic,
                            color    = colors.inkSoft,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(colors.borderSoft)
                                .padding(11.dp),
                        )
                    }
                    item("preview") { PreviewHero(suggestion = s, onSwapCover = { toast = "Swap cover ships in Phase 5.1." }) }
                    item("inside-label") {
                        Text("What's inside, in order:",
                            style = type.bodyItalic, color = colors.inkSoft, fontSize = 11.sp)
                    }
                    item("inside-strip") { ItemStrip(refs = s.candidateRefs) }
                }
                loadFailed -> {
                    item("missing") {
                        Text(
                            text  = "That suggestion is no longer available.",
                            style = type.bodyItalic,
                            color = colors.inkSoft,
                            modifier = Modifier.padding(top = QuickInkSpacing.s5),
                        )
                    }
                }
                else -> {
                    item("loading") {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(top = QuickInkSpacing.s5),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }

        // CTA row, pinned to bottom.
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = QuickInkSpacing.s4, vertical = QuickInkSpacing.s5),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CtaButton(
                label   = "Edit first",
                primary = false,
                enabled = suggestion != null && !creating,
                onClick = ::makeStory,
                modifier = Modifier.weight(1f),
            )
            CtaButton(
                label   = if (creating) "Creating…" else "Make story",
                primary = true,
                enabled = suggestion != null && !creating,
                onClick = ::makeStory,
                modifier = Modifier.weight(1f),
            )
        }

        if (toast != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 60.dp)
                    .clip(RoundedCornerShape(QuickInkRadius.pill))
                    .background(colors.surface)
                    .border(0.5.dp, colors.border, RoundedCornerShape(QuickInkRadius.pill))
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            ) {
                Text(toast!!, style = type.bodyItalic, color = colors.inkSoft, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun PreviewHero(suggestion: StorySuggestion, onSwapCover: () -> Unit) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current
    val title  = deriveTitle(suggestion)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(12.dp))
            .padding(QuickInkSpacing.s3 + 2.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text          = "WHAT IT'LL LOOK LIKE",
            color         = colors.accentDeep,
            fontSize      = 10.sp,
            fontWeight    = FontWeight.Medium,
            letterSpacing = 1.5.sp,
        )
        // Sample cover
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Brush.linearGradient(listOf(colors.paper1, colors.paper3))),
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(14.dp),
            ) {
                Text(
                    text          = "PREVIEW",
                    color         = colors.inkSoft,
                    fontSize      = 9.sp,
                    fontStyle     = FontStyle.Italic,
                    letterSpacing = 1.5.sp,
                )
                Text(text = title, fontSize = 20.sp, fontWeight = FontWeight.Medium, color = colors.ink,
                    style = type.display.copy(lineHeight = 22.sp))
                Text(text = "a curated story-in-the-making", style = type.handwritten,
                    fontSize = 14.sp, color = colors.inkSoft)
            }
        }
        // First-page card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(colors.bg)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(colors.paper1),
            )
            Spacer(modifier = Modifier.height(5.dp))
            Text(
                text     = "First capture, jet-lagged and curious.",
                style    = type.bodyItalic,
                color    = colors.inkSoft,
                fontSize = 11.sp,
            )
        }
        // Controls row
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = QuickInkSpacing.s2)
                .border(0.5.dp, colors.border),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text     = "${suggestion.candidateRefs.size} items · ~${maxOf(1, suggestion.candidateRefs.size / 3)} pages",
                style    = type.bodyItalic,
                color    = colors.inkSoft,
                fontSize = 11.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                text       = "Swap cover",
                color      = colors.accent,
                fontSize   = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier   = Modifier.clickable(onClick = onSwapCover),
            )
        }
    }
}

@Composable
private fun ItemStrip(refs: List<String>) {
    val colors = LocalQuickInkColors.current
    val palette = remember { listOf<Color.() -> Unit>() } // unused, placeholder
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        refs.forEachIndexed { idx, _ ->
            val fill = when (idx % 5) {
                0    -> colors.paper1
                1    -> colors.accent.copy(alpha = 0.55f)
                2    -> colors.surface
                3    -> colors.paper3
                else -> colors.paper2
            }
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(fill)
                    .border(1.dp, colors.border, RoundedCornerShape(6.dp)),
            )
        }
    }
}

@Composable
private fun CtaButton(
    label: String,
    primary: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalQuickInkColors.current
    val bg = when {
        !primary -> colors.surface
        enabled  -> colors.accent
        else     -> colors.accent.copy(alpha = 0.5f)
    }
    val fg = if (primary) colors.textOnAccent else colors.ink
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .let { if (!primary) it.border(1.dp, colors.border, RoundedCornerShape(12.dp)) else it }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = fg)
    }
}

private fun deriveTitle(s: StorySuggestion): String {
    val idx = s.reason.lastIndexOf(',')
    val date = if (idx >= 0) s.reason.substring(idx + 1).trim() else s.reason
    return "Captures, $date"
}
