/*
 * StoryEditorScreen.kt
 *
 * Stories Phase 2 — the curated-narrative editor (§7.3 of the v3
 * mockup). Mirror of iOS `StoryEditorScreen.swift`. Lets the user
 * reorder items (via Move up / Move down on the ⋯ menu — drag-and-
 * drop ships in a follow-up), edit captions / inline text, change
 * layout, set a cover, and remove items. Auto-saves on every change
 * with a "Saved just now" toast at the top.
 */

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.quickink.mobile.features.stories

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.quickink.mobile.data.storyitem.StoryItemEntity
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkRadius
import app.quickink.mobile.ui.theme.QuickInkSpacing
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun StoryEditorScreen(
    storyId: String,
    userId: String,
    onBack: () -> Unit,
    /** Phase 3 — tap the bottom Preview button to push the reader. */
    onPreview: () -> Unit = {},
) {
    val colors  = LocalQuickInkColors.current
    val type    = LocalQuickInkTypography.current
    val vm: StoryEditorViewModel = viewModel(factory = StoryEditorViewModel.factory(storyId, userId))

    val story by vm.story.collectAsState()
    val items by vm.items.collectAsState()
    val savedJustNow by vm.savedJustNow.collectAsState()

    var titleDraft by remember { mutableStateOf("") }
    var subtitleDraft by remember { mutableStateOf("") }
    val itemTextDrafts = remember { mutableStateMapOf<String, String>() }
    val itemCaptionDrafts = remember { mutableStateMapOf<String, String>() }

    LaunchedEffect(story?.id) {
        story?.let {
            if (titleDraft.isEmpty()) titleDraft = it.title
            if (subtitleDraft.isEmpty()) subtitleDraft = it.subtitle.orEmpty()
        }
    }

    var addSheetPrecedingId by remember { mutableStateOf<String?>(null) }
    var showingAddSheet by remember { mutableStateOf(false) }
    var menuTargetId by remember { mutableStateOf<String?>(null) }
    var showingShareSheet by remember { mutableStateOf(false) }

    // Drag-reorder state. The reorderable lib tracks the visual
    // order; we hold a parallel `pendingOrder` so we can apply the
    // swap on `onMove` (synchronous, mid-drag) AND commit the final
    // ordering to the repo on `onDragStopped` (release). Nil means
    // no drag in progress — render `vm.items` order verbatim.
    var pendingOrder by remember { mutableStateOf<List<String>?>(null) }
    val displayedItems = remember(items, pendingOrder) {
        val order = pendingOrder ?: return@remember items
        val byId = items.associateBy { it.id }
        order.mapNotNull { byId[it] }
    }
    val lazyListState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val fromKey = from.key as? String ?: return@rememberReorderableLazyListState
        val toKey   = to.key   as? String ?: return@rememberReorderableLazyListState
        val current = pendingOrder ?: items.map { it.id }
        val fromIdx = current.indexOf(fromKey)
        val toIdx   = current.indexOf(toKey)
        if (fromIdx < 0 || toIdx < 0 || fromIdx == toIdx) return@rememberReorderableLazyListState
        pendingOrder = current.toMutableList().apply {
            add(toIdx, removeAt(fromIdx))
        }
    }

    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = QuickInkSpacing.s3, vertical = QuickInkSpacing.s2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val interactionSource = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .padding(QuickInkSpacing.s2)
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = onBack,
                        ),
                ) {
                    Icon(
                        imageVector        = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = "Back",
                        tint               = colors.ink,
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text     = "Preview · Share",
                    color    = colors.inkSoft,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(end = QuickInkSpacing.s3),
                )
            }

            // Cover strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surface)
                    .border(0.5.dp, colors.border)
                    .padding(horizontal = QuickInkSpacing.s4, vertical = QuickInkSpacing.s3),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3),
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.paper1),
                )
                Column(modifier = Modifier.weight(1f)) {
                    BasicTextField(
                        value = titleDraft,
                        onValueChange = {
                            titleDraft = it
                            vm.updateTitle(it)
                        },
                        textStyle = type.editorial.copy(color = colors.ink),
                        cursorBrush = SolidColor(colors.accent),
                        singleLine = true,
                        decorationBox = { inner ->
                            if (titleDraft.isEmpty()) Text("Title", style = type.editorial, color = colors.inkSoft)
                            inner()
                        },
                    )
                    BasicTextField(
                        value = subtitleDraft,
                        onValueChange = {
                            subtitleDraft = it
                            vm.updateSubtitle(it)
                        },
                        textStyle = type.bodyItalic.copy(color = colors.inkSoft, fontSize = 13.sp),
                        cursorBrush = SolidColor(colors.accent),
                        singleLine = true,
                        decorationBox = { inner ->
                            if (subtitleDraft.isEmpty()) Text("a quiet line of context", style = type.bodyItalic, color = colors.muted, fontSize = 13.sp)
                            inner()
                        },
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(QuickInkRadius.pill))
                        .border(1.dp, colors.accent.copy(alpha = 0.4f), RoundedCornerShape(QuickInkRadius.pill))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    Text(
                        text       = "Cover",
                        color      = colors.accent,
                        fontSize   = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            // Items list. Each story_item is wrapped in a
            // ReorderableItem with the +Add button rendered inside
            // the same composable so it travels with the item during
            // a drag. The "first" Add button (when empty) stays a
            // plain non-reorderable item.
            LazyColumn(
                state    = lazyListState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(
                    start  = QuickInkSpacing.s4,
                    end    = QuickInkSpacing.s4,
                    top    = QuickInkSpacing.s3,
                    bottom = QuickInkSpacing.s6,
                ),
                verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
            ) {
                if (displayedItems.isEmpty()) {
                    item("empty") {
                        Text(
                            text  = "Add the first item — a photo, a typed paragraph, or a voice clip.",
                            style = type.bodyItalic,
                            color = colors.inkSoft,
                            modifier = Modifier.padding(vertical = QuickInkSpacing.s4),
                        )
                    }
                    item("add-empty") {
                        AddSlotButton(onClick = {
                            addSheetPrecedingId = null
                            showingAddSheet     = true
                        })
                    }
                } else {
                    displayedItems.forEach { storyItem ->
                        item(storyItem.id) {
                            ReorderableItem(reorderState, key = storyItem.id) { isDragging ->
                                Column(
                                    modifier = Modifier.longPressDraggableHandle(
                                        onDragStopped = {
                                            pendingOrder?.let { order ->
                                                vm.commitReorder(order)
                                            }
                                            pendingOrder = null
                                        },
                                    ),
                                ) {
                                    EditorItemRow(
                                        storyItem     = storyItem,
                                        textDrafts    = itemTextDrafts,
                                        captionDrafts = itemCaptionDrafts,
                                        onTextChange  = { id, v ->
                                            itemTextDrafts[id] = v
                                            vm.updateItemText(id, v)
                                        },
                                        onCaptionChange = { id, v ->
                                            itemCaptionDrafts[id] = v
                                            vm.updateItemCaption(id, v)
                                        },
                                        onMenu = { menuTargetId = storyItem.id },
                                    )
                                    Spacer(modifier = Modifier.height(QuickInkSpacing.s2))
                                    AddSlotButton(onClick = {
                                        addSheetPrecedingId = storyItem.id
                                        showingAddSheet     = true
                                    })
                                }
                            }
                        }
                    }
                }
            }

            // Bottom action bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surface)
                    .border(0.5.dp, colors.border)
                    .padding(horizontal = QuickInkSpacing.s4, vertical = QuickInkSpacing.s3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(QuickInkRadius.pill))
                        .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.pill))
                        .clickable(onClick = onPreview)
                        .padding(horizontal = QuickInkSpacing.s4, vertical = QuickInkSpacing.s2 + 2.dp),
                ) {
                    Text("Preview", color = colors.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
                Spacer(modifier = Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(QuickInkRadius.pill))
                        .background(colors.accent)
                        .clickable { showingShareSheet = true }
                        .padding(horizontal = QuickInkSpacing.s5, vertical = QuickInkSpacing.s2 + 2.dp),
                ) {
                    Text("Share", color = colors.textOnAccent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // Saved toast
        AnimatedVisibility(
            visible = savedJustNow,
            enter   = fadeIn() + expandVertically(),
            exit    = fadeOut() + shrinkVertically(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = QuickInkSpacing.s5),
        ) {
            Box(
                modifier = Modifier
                    .shadow(elevation = 4.dp, shape = RoundedCornerShape(QuickInkRadius.pill))
                    .clip(RoundedCornerShape(QuickInkRadius.pill))
                    .background(colors.surface)
                    .border(0.5.dp, colors.border, RoundedCornerShape(QuickInkRadius.pill))
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            ) {
                Text(
                    text       = "Saved just now",
                    color      = colors.inkSoft,
                    fontSize   = 11.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }

    if (showingShareSheet) {
        StoryShareSheet(
            storyId   = storyId,
            userId    = userId,
            onDismiss = { showingShareSheet = false },
        )
    }

    if (showingAddSheet) {
        StoryAddSheet(
            precedingItemCaption = precedingCaption(items, addSheetPrecedingId),
            userId               = userId,
            onPickInlineKind     = { kind ->
                val pre = addSheetPrecedingId
                showingAddSheet = false
                scope.launch { vm.insertItem(pre, kind) }
            },
            onPickVoiceClip      = { uri, durationMs ->
                val pre = addSheetPrecedingId
                showingAddSheet = false
                scope.launch { vm.insertVoiceClipItem(pre, uri, durationMs) }
            },
            onPickCapture        = { captureId, kind ->
                val pre = addSheetPrecedingId
                showingAddSheet = false
                scope.launch { vm.insertCaptureItem(pre, captureId, kind) }
            },
            onDismiss            = { showingAddSheet = false },
        )
    }

    val menuItem = menuTargetId?.let { id -> items.firstOrNull { it.id == id } }
    if (menuItem != null) {
        StoryItemMenuSheet(
            item          = menuItem,
            isCoverItem   = story?.coverItemId == menuItem.id,
            onSetAsCover  = {
                vm.setCover(menuItem.id)
                menuTargetId = null
            },
            onLayoutChange = { layout -> vm.updateItemLayout(menuItem.id, layout) },
            onMoveUp       = {
                vm.moveItem(menuItem.id, -1)
                menuTargetId = null
            },
            onMoveDown     = {
                vm.moveItem(menuItem.id, +1)
                menuTargetId = null
            },
            onRemove       = {
                vm.removeItem(menuItem.id)
                menuTargetId = null
            },
            onDismiss      = { menuTargetId = null },
        )
    }
}

@Composable
private fun AddSlotButton(onClick: () -> Unit) {
    val colors = LocalQuickInkColors.current
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(QuickInkRadius.md))
            .border(
                width = 1.dp,
                color = colors.accent.copy(alpha = 0.3f),
                shape = RoundedCornerShape(QuickInkRadius.md),
            )
            .clickable(
                interactionSource = interactionSource,
                indication        = null,
                onClick           = onClick,
            )
            .padding(vertical = QuickInkSpacing.s2),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text       = "＋ Add",
            color      = colors.accent,
            fontSize   = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun EditorItemRow(
    storyItem: StoryItemEntity,
    textDrafts: MutableMap<String, String>,
    captionDrafts: MutableMap<String, String>,
    onTextChange: (String, String) -> Unit,
    onCaptionChange: (String, String) -> Unit,
    onMenu: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current

    when (storyItem.kind) {
        StoryItemEntity.Kind.TEXT_BLOCK.raw -> {
            CardSurface {
                Row(
                    modifier = Modifier.padding(QuickInkSpacing.s3),
                    verticalAlignment = Alignment.Top,
                ) {
                    val draft = textDrafts.getOrDefault(storyItem.id, storyItem.text.orEmpty())
                    BasicTextField(
                        value = draft,
                        onValueChange = { onTextChange(storyItem.id, it) },
                        textStyle = type.editorial.copy(color = colors.ink),
                        cursorBrush = SolidColor(colors.accent),
                        modifier = Modifier.weight(1f),
                        decorationBox = { inner ->
                            if (draft.isEmpty()) Text("Write a paragraph…", style = type.editorial, color = colors.inkSoft)
                            inner()
                        },
                    )
                    MenuButton(onMenu)
                }
            }
        }
        StoryItemEntity.Kind.HANDWRITTEN_NOTE.raw -> {
            CardSurface {
                Row(
                    modifier = Modifier.padding(QuickInkSpacing.s3),
                    verticalAlignment = Alignment.Top,
                ) {
                    val draft = textDrafts.getOrDefault(storyItem.id, storyItem.text.orEmpty())
                    BasicTextField(
                        value = draft,
                        onValueChange = { onTextChange(storyItem.id, it) },
                        textStyle = type.handwritten.copy(color = colors.ink),
                        cursorBrush = SolidColor(colors.accent),
                        modifier = Modifier.weight(1f),
                        decorationBox = { inner ->
                            if (draft.isEmpty()) Text("Handwritten note…", style = type.handwritten, color = colors.inkSoft)
                            inner()
                        },
                    )
                    MenuButton(onMenu)
                }
            }
        }
        StoryItemEntity.Kind.DATE_DIVIDER.raw -> {
            Row(
                modifier = Modifier.padding(vertical = QuickInkSpacing.s2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.weight(1f).height(0.5.dp).background(colors.border))
                Text(
                    text       = storyItem.text ?: "Date divider",
                    color      = colors.inkSoft,
                    fontSize   = 13.sp,
                    fontWeight = FontWeight.Medium,
                    style      = type.editorial,
                    modifier   = Modifier.padding(horizontal = QuickInkSpacing.s3),
                )
                Box(modifier = Modifier.weight(1f).height(0.5.dp).background(colors.border))
                MenuButton(onMenu)
            }
        }
        StoryItemEntity.Kind.PLACE_PIN.raw -> {
            CardSurface {
                Row(
                    modifier = Modifier.padding(QuickInkSpacing.s3),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.LocationOn,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(QuickInkSpacing.s2))
                    val draft = textDrafts.getOrDefault(storyItem.id, storyItem.text.orEmpty())
                    BasicTextField(
                        value = draft,
                        onValueChange = { onTextChange(storyItem.id, it) },
                        textStyle = type.editorial.copy(color = colors.ink),
                        cursorBrush = SolidColor(colors.accent),
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        decorationBox = { inner ->
                            if (draft.isEmpty()) Text("Place name", style = type.editorial, color = colors.inkSoft)
                            inner()
                        },
                    )
                    MenuButton(onMenu)
                }
            }
        }
        StoryItemEntity.Kind.VOICE_CLIP.raw -> {
            CardSurface {
                Row(
                    modifier = Modifier.padding(QuickInkSpacing.s2 + 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(colors.accentSoft),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Mic,
                            contentDescription = null,
                            tint = colors.accent,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(QuickInkSpacing.s3))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Voice clip", style = type.editorial, color = colors.ink)
                        Text("tap to play", style = type.bodyItalic, color = colors.inkSoft, fontSize = 12.sp)
                    }
                    MenuButton(onMenu)
                }
            }
        }
        else -> {
            CardSurface {
                Row(
                    modifier = Modifier.padding(QuickInkSpacing.s2 + 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.paper1),
                    )
                    Spacer(modifier = Modifier.width(QuickInkSpacing.s3))
                    val draft = captionDrafts.getOrDefault(storyItem.id, storyItem.caption.orEmpty())
                    BasicTextField(
                        value = draft,
                        onValueChange = { onCaptionChange(storyItem.id, it) },
                        textStyle = type.editorial.copy(color = colors.ink),
                        cursorBrush = SolidColor(colors.accent),
                        modifier = Modifier.weight(1f),
                        decorationBox = { inner ->
                            if (draft.isEmpty()) Text("Caption", style = type.editorial, color = colors.inkSoft)
                            inner()
                        },
                    )
                    MenuButton(onMenu)
                }
            }
        }
    }
}

@Composable
private fun CardSurface(content: @Composable () -> Unit) {
    val colors = LocalQuickInkColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(QuickInkRadius.md))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.md)),
    ) {
        content()
    }
}

@Composable
private fun MenuButton(onClick: () -> Unit) {
    val colors = LocalQuickInkColors.current
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .padding(QuickInkSpacing.s2)
            .clickable(
                interactionSource = interactionSource,
                indication        = null,
                onClick           = onClick,
            ),
    ) {
        Icon(
            imageVector        = Icons.Filled.MoreHoriz,
            contentDescription = "More",
            tint               = colors.inkSoft,
            modifier           = Modifier.size(18.dp),
        )
    }
}

private fun precedingCaption(items: List<StoryItemEntity>, precedingId: String?): String? {
    if (precedingId == null) return null
    val item = items.firstOrNull { it.id == precedingId } ?: return null
    return item.caption ?: item.text ?: item.kind.replace('_', ' ')
}
