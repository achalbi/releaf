/*
 * FolderDetailScreen.kt
 *
 * Workspace v1 Screen 2 — captures inside a folder with a tag-
 * strip filter on top. Reached by tapping a folder on the
 * Workspace home; back arrow returns. Tap a docrow to open the
 * scan detail; long-press to open a move/tag action sheet (Phase
 * C.1 follow-up — not in this commit).
 *
 * Composition (top → bottom):
 *   - Folder bar  — back arrow + folder glyph + name + item count
 *   - Search bar  — in-folder search; routes to the global search
 *                   with the folder pre-filtered (Phase C.2 hook).
 *   - Tag strip   — horizontal chips: "All tags" + tags that appear
 *                   on at least one capture in this folder. Single-
 *                   select; tapping a chip filters; "All tags" or
 *                   tapping the active chip clears.
 *   - Result hint — "Filtered to #tag · N docs" eyebrow when a tag
 *                   is selected; "N docs" when not.
 *   - Doc rows    — thumbnail + name + tag chips + meta (page
 *                   count + date).
 *
 * Mirror of `FolderDetailScreen.swift` (iOS — lands in the iOS
 * Phase C pass).
 */

@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package app.quickink.mobile.features.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.quickink.mobile.QuickInkApp
import app.quickink.mobile.data.capture.CaptureEntity
import app.quickink.mobile.data.folder.FolderEntity
import app.quickink.mobile.data.tag.TagEntity
import app.quickink.mobile.features.nav.QuickInkBottomNavReservedHeight
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkRadius
import app.quickink.mobile.ui.theme.QuickInkSpacing
import coil.compose.AsyncImage
import coil.request.ImageRequest

@Composable
fun FolderDetailScreen(
    folderId: String,
    userId: String,
    onBack: () -> Unit,
    onOpenCapture: (CaptureEntity) -> Unit,
    onOpenSearch: () -> Unit,
) {
    val colors  = LocalQuickInkColors.current
    val type    = LocalQuickInkTypography.current
    val context = LocalContext.current
    val app     = remember(context) { context.applicationContext as QuickInkApp }

    val folder by produceState<FolderEntity?>(initialValue = null, key1 = folderId) {
        value = app.database.folderDao().findById(folderId)
    }

    // Active tag filter (null = "All tags"). Tapping the same chip
    // twice clears.
    var selectedTagId by remember { mutableStateOf<String?>(null) }

    // Captures in the folder, optionally narrowed by the selected
    // tag. Two flows because Room can't express an optional join
    // cleanly in a single @Query.
    val allCaptures by remember(folderId) {
        app.database.captureDao().observeByFolder(folderId)
    }.collectAsState(initial = emptyList())

    val taggedCaptures by remember(folderId, selectedTagId) {
        val tagId = selectedTagId
        if (tagId == null) kotlinx.coroutines.flow.flowOf(emptyList())
        else app.database.captureDao().observeByFolderAndTag(folderId, tagId)
    }.collectAsState(initial = emptyList())

    val captures = if (selectedTagId == null) allCaptures else taggedCaptures

    // Tag chips that would actually narrow the result — only the
    // tags that touch at least one capture in this folder.
    val tagIdsInFolder by remember(folderId) {
        app.database.captureTagDao().observeTagIdsInFolder(folderId)
    }.collectAsState(initial = emptyList())

    val allTags by remember(userId) {
        app.database.tagDao().observeActive(userId)
    }.collectAsState(initial = emptyList())

    val tagStrip = remember(tagIdsInFolder, allTags) {
        val byId = allTags.associateBy { it.id }
        tagIdsInFolder.mapNotNull { byId[it] }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top    = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
                              + QuickInkSpacing.s2,
                    bottom = QuickInkBottomNavReservedHeight,
                ),
        ) {
            FolderBar(
                folder       = folder,
                captureCount = captures.size,
                onBack       = onBack,
            )

            Spacer(Modifier.height(QuickInkSpacing.s2))

            InFolderSearchBar(onClick = onOpenSearch)

            if (tagStrip.isNotEmpty()) {
                Spacer(Modifier.height(QuickInkSpacing.s3))
                TagFilterStrip(
                    tags          = tagStrip,
                    selectedTagId = selectedTagId,
                    onPick        = { tagId ->
                        selectedTagId = if (selectedTagId == tagId) null else tagId
                    },
                )
            }

            Spacer(Modifier.height(QuickInkSpacing.s2))

            ResultEyebrow(
                count          = captures.size,
                selectedTag    = tagStrip.firstOrNull { it.id == selectedTagId },
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = QuickInkSpacing.s4),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                items(captures, key = { it.id }) { capture ->
                    DocRow(
                        capture = capture,
                        userId  = userId,
                        onClick = { onOpenCapture(capture) },
                    )
                }
                if (captures.isEmpty()) {
                    item {
                        Text(
                            text  = if (selectedTagId == null) "No captures in this folder yet."
                                    else "No captures match this tag in this folder.",
                            style = type.meta,
                            color = colors.muted,
                            modifier = Modifier.padding(vertical = QuickInkSpacing.s4),
                        )
                    }
                }
            }
        }
    }
}

// ─── Folder bar ───────────────────────────────────────────────

@Composable
private fun FolderBar(
    folder: FolderEntity?,
    captureCount: Int,
    onBack: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = QuickInkSpacing.s2, vertical = QuickInkSpacing.s1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector        = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = "Back",
                tint               = colors.ink,
                modifier           = Modifier.size(20.dp),
            )
        }

        Spacer(Modifier.width(QuickInkSpacing.s2))

        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(parseFolderColor(folder?.color)),
        )

        Spacer(Modifier.width(QuickInkSpacing.s2))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text  = folder?.name ?: "…",
                style = type.editorial.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text  = "$captureCount ${if (captureCount == 1) "item" else "items"}",
                style = type.meta.copy(fontSize = 11.sp),
                color = colors.muted,
            )
        }
    }
}

// ─── Search bar (in-folder) ──────────────────────────────────

@Composable
private fun InFolderSearchBar(onClick: () -> Unit) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current
    val shape  = RoundedCornerShape(QuickInkRadius.md)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = QuickInkSpacing.s4)
            .clip(shape)
            .background(colors.surface, shape)
            .border(1.dp, colors.border, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = QuickInkSpacing.s3, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Search,
            contentDescription = null,
            tint = colors.muted,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(QuickInkSpacing.s2))
        Text(
            text     = "Search in folder",
            style    = type.body.copy(fontSize = 13.sp),
            color    = colors.muted,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.Outlined.Tune,
            contentDescription = null,
            tint = colors.inkSoft,
            modifier = Modifier.size(16.dp),
        )
    }
}

// ─── Tag filter strip ────────────────────────────────────────

@Composable
private fun TagFilterStrip(
    tags: List<TagEntity>,
    selectedTagId: String?,
    onPick: (String?) -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current
    val shape  = RoundedCornerShape(999.dp)

    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = QuickInkSpacing.s4),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            // "All tags" pill — always present, active when selectedTagId is null.
            val isActive = selectedTagId == null
            Row(
                modifier = Modifier
                    .clip(shape)
                    .background(if (isActive) colors.ink else colors.surface, shape)
                    .border(1.dp, if (isActive) colors.ink else colors.border, shape)
                    .clickable(onClick = { onPick(null) })
                    .padding(horizontal = 11.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Tag,
                    contentDescription = null,
                    tint = if (isActive) Color.White else colors.accent,
                    modifier = Modifier.size(13.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text  = "All tags",
                    style = type.label.copy(fontSize = 11.5.sp),
                    color = if (isActive) Color.White else colors.inkSoft,
                )
            }
        }
        items(tags, key = { it.id }) { tag ->
            val isActive = selectedTagId == tag.id
            Row(
                modifier = Modifier
                    .clip(shape)
                    .background(if (isActive) colors.ink else colors.surface, shape)
                    .border(1.dp, if (isActive) colors.ink else colors.border, shape)
                    .clickable(onClick = { onPick(tag.id) })
                    .padding(horizontal = 11.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text  = "#",
                    style = type.label.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                    color = if (isActive) Color.White else colors.accent,
                )
                Text(
                    text  = tag.name,
                    style = type.label.copy(fontSize = 11.5.sp),
                    color = if (isActive) Color.White else colors.inkSoft,
                    modifier = Modifier.padding(start = 2.dp),
                )
            }
        }
    }
}

// ─── Eyebrow ─────────────────────────────────────────────────

@Composable
private fun ResultEyebrow(count: Int, selectedTag: TagEntity?) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = QuickInkSpacing.s4, vertical = QuickInkSpacing.s1),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        if (selectedTag == null) {
            Text(
                text  = "$count ${if (count == 1) "doc" else "docs"}",
                style = type.label.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
                color = colors.ink,
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text  = "Filtered to ",
                    style = type.body.copy(fontSize = 12.sp, fontStyle = FontStyle.Italic),
                    color = colors.muted,
                )
                Text(
                    text  = "#${selectedTag.name}",
                    style = type.body.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                    color = colors.accentDeep,
                )
                Text(
                    text  = " · $count docs",
                    style = type.body.copy(fontSize = 12.sp),
                    color = colors.muted,
                )
            }
        }
    }
}

// ─── Doc row ─────────────────────────────────────────────────

@Composable
private fun DocRow(
    capture: CaptureEntity,
    userId: String,
    onClick: () -> Unit,
) {
    val colors  = LocalQuickInkColors.current
    val type    = LocalQuickInkTypography.current
    val context = LocalContext.current
    val app     = remember(context) { context.applicationContext as QuickInkApp }

    // Per-row inline tag chips. Cheap because the join is indexed
    // and the row count is bounded by the folder's capture count.
    val attachedTagIds by remember(capture.id) {
        app.database.captureTagDao().observeTagIdsForCapture(capture.id)
    }.collectAsState(initial = emptyList())

    val allTags by remember(userId) {
        app.database.tagDao().observeActive(userId)
    }.collectAsState(initial = emptyList())

    val attachedTagNames = remember(attachedTagIds, allTags) {
        val byId = allTags.associateBy { it.id }
        attachedTagIds.mapNotNull { byId[it]?.name }
    }

    val title = capture.title?.takeIf { it.isNotBlank() }
        ?: "Untitled scan"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 13.dp),
        verticalAlignment = Alignment.Top,
    ) {
        DocRowThumbnail(previewUri = capture.previewUri)

        Spacer(Modifier.width(QuickInkSpacing.s3))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text  = title,
                style = type.body.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            if (attachedTagNames.isNotEmpty()) {
                Spacer(Modifier.height(5.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    attachedTagNames.take(3).forEach { name ->
                        DocRowTagChip(name = name)
                    }
                    if (attachedTagNames.size > 3) {
                        DocRowTagChip(name = "+${attachedTagNames.size - 3}")
                    }
                }
            }

            Spacer(Modifier.height(5.dp))
            Text(
                text  = buildString {
                    append(capture.pageCount)
                    append(if (capture.pageCount == 1) " page" else " pages")
                    append(" · ")
                    append(capture.createdAt.take(10))
                },
                style = type.meta.copy(fontSize = 11.5.sp),
                color = colors.muted,
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(colors.borderSoft),
    )
}

@Composable
private fun DocRowTagChip(name: String) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current
    val isExtra = name.startsWith("+")
    val bg = if (isExtra) colors.borderSoft else colors.accentSoft
    val fg = if (isExtra) colors.inkSoft    else colors.accentDeep
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg, RoundedCornerShape(4.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!isExtra) {
            Text(
                text  = "#",
                style = type.label.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                color = fg,
            )
        }
        Text(
            text  = name,
            style = type.label.copy(fontSize = 10.5.sp),
            color = fg,
            modifier = Modifier.padding(start = if (!isExtra) 1.dp else 0.dp),
        )
    }
}

/**
 * Shared 44×56 thumbnail tile for Workspace doc rows. Falls back
 * to a soft-border placeholder when the previewUri is missing or
 * fails to load — matches the lined-paper neutral tone the design
 * specs for OCR-only documents.
 */
@Composable
internal fun DocRowThumbnail(previewUri: String?) {
    val colors = LocalQuickInkColors.current
    val shape  = RoundedCornerShape(7.dp)
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .size(width = 44.dp, height = 56.dp)
            .clip(shape)
            .background(colors.borderSoft, shape),
    ) {
        val uri = previewUri?.takeIf { it.isNotBlank() }
        if (uri != null) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(Uri.parse(uri))
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
        }
    }
}
