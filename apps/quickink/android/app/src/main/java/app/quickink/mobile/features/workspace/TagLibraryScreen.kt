/*
 * TagLibraryScreen.kt
 *
 * Workspace v1 Screen 4 — tag library. Reached from the "Browse
 * all" link on Workspace home (Phase D wires it). Shows:
 *
 *   - Header   — back arrow + "Tags" + meta (N tags · across M docs)
 *   - Search   — filter tag list by name substring (live).
 *   - Intersect builder — multi-select chips of currently-picked
 *                          tags + an "add tag" prompt; reflects
 *                          "N matching documents" live.
 *   - Most used grid (2-col) — every active tag with doc count.
 *
 * AI-suggested strip from the design ships in Phase E.
 *
 * Mirror of `TagLibraryScreen.swift` (iOS Phase D pass).
 */

@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package app.quickink.mobile.features.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.quickink.mobile.QuickInkApp
import app.quickink.mobile.data.capturetag.TagCount
import app.quickink.mobile.data.tag.TagEntity
import app.quickink.mobile.data.smartcollection.RuleClause
import app.quickink.mobile.data.smartcollection.SmartCollectionEntity
import app.quickink.mobile.data.smartcollection.SmartCollectionRule
import app.quickink.mobile.data.tag.TagRepository
import app.releaf.mobile.data.common.IsoClock
import app.releaf.mobile.data.common.Uuidv7
import kotlinx.coroutines.launch
import app.quickink.mobile.features.nav.QuickInkBottomNavReservedHeight
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkRadius
import app.quickink.mobile.ui.theme.QuickInkSpacing
import kotlinx.coroutines.flow.flowOf

@Composable
fun TagLibraryScreen(
    userId: String,
    onBack: () -> Unit,
    onOpenTag: (TagEntity) -> Unit,
    onOpenSearch: () -> Unit,
) {
    val colors  = LocalQuickInkColors.current
    val type    = LocalQuickInkTypography.current
    val context = LocalContext.current
    val app     = remember(context) { context.applicationContext as QuickInkApp }

    val tags by remember(userId) {
        app.database.tagDao().observeActive(userId)
    }.collectAsState(initial = emptyList())

    val tagCounts by remember(userId) {
        app.database.captureTagDao().observeTagCounts(userId)
    }.collectAsState(initial = emptyList())

    val countById = remember(tagCounts) { tagCounts.associate { it.tagId to it.docCount } }

    var query     by remember { mutableStateOf("") }
    var picked    by remember { mutableStateOf<List<TagEntity>>(emptyList()) }

    // Tag CRUD modal state (Phase D.1 follow-up).
    var actionsForTag         by remember { mutableStateOf<TagEntity?>(null) }
    var renameTarget          by remember { mutableStateOf<TagEntity?>(null) }
    var deleteTarget          by remember { mutableStateOf<TagEntity?>(null) }
    var showCreateDialog      by remember { mutableStateOf(false) }
    // Save-as-collection prompt — non-empty list of tags becomes
    // a SmartCollection with an AND-of-tag-is clauses.
    var saveAsCollectionPicked by remember { mutableStateOf<List<TagEntity>>(emptyList()) }

    val scope = rememberCoroutineScope()
    val tagRepo = remember(app) {
        TagRepository(
            tagDao     = app.database.tagDao(),
            captureDao = app.database.captureDao(),
        )
    }
    val captureTagDao = remember(app) { app.database.captureTagDao() }

    val filtered = remember(tags, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) tags
        else tags.filter { it.name.contains(q, ignoreCase = true) }
    }

    val totalActiveCaptureCount = remember(tagCounts) {
        // Imperfect — counts the SUM of attachments not distinct
        // captures. For the header meta this is close enough; the
        // exact figure requires another DAO query.
        tagCounts.sumOf { it.docCount }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    top    = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
                              + QuickInkSpacing.s2,
                    bottom = QuickInkBottomNavReservedHeight,
                ),
        ) {
            TagLibraryHeader(
                tagCount     = tags.size,
                captureCount = totalActiveCaptureCount,
                onBack       = onBack,
                onNewTag     = { showCreateDialog = true },
            )

            Spacer(Modifier.height(QuickInkSpacing.s2))

            TagSearchBar(
                query    = query,
                onChange = { query = it },
            )

            Spacer(Modifier.height(QuickInkSpacing.s3))

            IntersectBuilderCard(
                userId   = userId,
                picked   = picked,
                allTags  = tags,
                onAdd    = { tag ->
                    if (picked.none { it.id == tag.id }) {
                        picked = picked + tag
                    }
                },
                onRemove = { tag ->
                    picked = picked.filter { it.id != tag.id }
                },
                onSaveAsCollection = { saveAsCollectionPicked = picked },
                onViewAll = {
                    // Single-tag drill uses the existing per-tag
                    // route; multi-tag intersect view is Phase D
                    // follow-up. Land on the first picked tag for now.
                    picked.firstOrNull()?.let(onOpenTag)
                },
            )

            Spacer(Modifier.height(QuickInkSpacing.s4))

            MostUsedHeader()

            TagGrid(
                tags           = filtered,
                countById      = countById,
                onOpenTag      = onOpenTag,
                onLongPressTag = { tag -> actionsForTag = tag },
            )

            Spacer(Modifier.height(QuickInkSpacing.s6))
        }
    }

    // ─── Tag CRUD modals ────────────────────────────────────────

    actionsForTag?.let { tag ->
        TagActionSheet(
            tag         = tag,
            onDismiss   = { actionsForTag = null },
            onRename    = {
                actionsForTag = null
                renameTarget = tag
            },
            onDelete    = {
                actionsForTag = null
                deleteTarget = tag
            },
        )
    }

    renameTarget?.let { tag ->
        TagRenameDialog(
            initialName = tag.name,
            onDismiss   = { renameTarget = null },
            onSubmit    = { newName ->
                scope.launch {
                    tagRepo.renameAndPropagate(
                        id      = tag.id,
                        oldName = tag.name,
                        newName = newName,
                        userId  = userId,
                    )
                    renameTarget = null
                }
            },
        )
    }

    deleteTarget?.let { tag ->
        val attachedCount = countById[tag.id] ?: 0
        TagDeleteConfirmDialog(
            tag          = tag,
            captureCount = attachedCount,
            onDismiss    = { deleteTarget = null },
            onConfirm    = {
                scope.launch {
                    val now = IsoClock.nowIso()
                    captureTagDao.softDeleteByTagId(tag.id, now)
                    tagRepo.softDelete(tag.id)
                    deleteTarget = null
                }
            },
        )
    }

    if (showCreateDialog) {
        TagRenameDialog(
            initialName = "",
            title       = "New tag",
            onDismiss   = { showCreateDialog = false },
            onSubmit    = { newName ->
                scope.launch {
                    val normalized = normalizeTagName(newName)
                    if (normalized.isNotEmpty()) {
                        tagRepo.findOrCreate(userId, normalized)
                    }
                    showCreateDialog = false
                }
            },
        )
    }

    if (saveAsCollectionPicked.isNotEmpty()) {
        val tagNames = saveAsCollectionPicked.joinToString(" + ") { "#${it.name}" }
        TagRenameDialog(
            initialName = tagNames,
            title       = "Save as smart collection",
            onDismiss   = { saveAsCollectionPicked = emptyList() },
            onSubmit    = { name ->
                val tagsForRule = saveAsCollectionPicked
                scope.launch {
                    val now = IsoClock.nowIso()
                    val clauses: List<RuleClause> = tagsForRule.map {
                        RuleClause.TagIs(it.id)
                    }
                    val ruleJson = SmartCollectionRule.encode(clauses)
                    val existing = app.database.smartCollectionDao().listActive(userId)
                    val nextPos = (existing.maxOfOrNull { it.position } ?: -1) + 1
                    app.database.smartCollectionDao().insert(
                        SmartCollectionEntity(
                            id        = Uuidv7.generate(),
                            userId    = userId,
                            name      = name.trim().ifEmpty { tagNames },
                            icon      = null,
                            color     = null,
                            ruleJson  = ruleJson,
                            position  = nextPos,
                            isSeeded  = false,
                            createdAt = now,
                            updatedAt = now,
                            dirty     = true,
                            deletedAt = null,
                        ),
                    )
                    saveAsCollectionPicked = emptyList()
                    picked = emptyList()
                }
            },
        )
    }
}

// ─── Header ──────────────────────────────────────────────────

@Composable
private fun TagLibraryHeader(
    tagCount: Int,
    captureCount: Int,
    onBack: () -> Unit,
    onNewTag: () -> Unit,
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
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text  = "Tags",
                style = type.editorial.copy(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
                color = colors.ink,
            )
            Text(
                text  = "$tagCount tags · $captureCount attachments",
                style = type.meta.copy(fontSize = 11.sp),
                color = colors.muted,
            )
        }
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .clickable(onClick = onNewTag),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector        = Icons.Outlined.Add,
                contentDescription = "New tag",
                tint               = colors.ink,
                modifier           = Modifier.size(20.dp),
            )
        }
    }
}

// ─── Tag search bar ──────────────────────────────────────────

@Composable
private fun TagSearchBar(
    query: String,
    onChange: (String) -> Unit,
) {
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
            .padding(horizontal = QuickInkSpacing.s3, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Search,
            contentDescription = null,
            tint = colors.muted,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(QuickInkSpacing.s2))
        BasicTextField(
            value         = query,
            onValueChange = onChange,
            singleLine    = true,
            textStyle     = type.body.copy(fontSize = 13.sp, color = colors.ink),
            cursorBrush   = SolidColor(colors.accent),
            modifier      = Modifier.weight(1f),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(
                            text  = "Search tags…",
                            style = type.body.copy(fontSize = 13.sp),
                            color = colors.muted,
                        )
                    }
                    inner()
                }
            },
        )
    }
}

// ─── Intersect builder ───────────────────────────────────────

@Composable
private fun IntersectBuilderCard(
    userId: String,
    picked: List<TagEntity>,
    allTags: List<TagEntity>,
    onAdd: (TagEntity) -> Unit,
    onRemove: (TagEntity) -> Unit,
    onViewAll: () -> Unit,
    onSaveAsCollection: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current
    val context = LocalContext.current
    val app    = remember(context) { context.applicationContext as QuickInkApp }
    val shape  = RoundedCornerShape(QuickInkRadius.md)

    var showAddSheet by remember { mutableStateOf(false) }

    // Live intersect count — only run a real query when ≥1 tag
    // is selected. Empty list → 0 to keep the read cheap.
    val intersectCount by remember(picked) {
        if (picked.isEmpty()) flowOf(0)
        else app.database.captureTagDao().observeIntersectCount(
            userId    = userId,
            tagIds    = picked.map { it.id },
            tagCount  = picked.size,
        )
    }.collectAsState(initial = 0)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = QuickInkSpacing.s4)
            .clip(shape)
            .background(colors.surface, shape)
            .border(1.dp, colors.border, shape)
            .padding(horizontal = QuickInkSpacing.s3, vertical = QuickInkSpacing.s2),
    ) {
        Text(
            text  = "Combine tags — show docs that have all of:",
            style = type.meta.copy(fontStyle = FontStyle.Italic, fontSize = 11.sp),
            color = colors.muted,
        )
        Spacer(Modifier.height(QuickInkSpacing.s2))

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            picked.forEachIndexed { index, tag ->
                if (index > 0) {
                    Text(
                        text  = "+",
                        style = type.label.copy(fontSize = 11.sp),
                        color = colors.muted,
                    )
                }
                PickedTagChip(tag = tag, onRemove = { onRemove(tag) })
            }
            if (picked.isNotEmpty()) {
                Text(
                    text  = "+",
                    style = type.label.copy(fontSize = 11.sp),
                    color = colors.muted,
                )
            }
            AddTagChip(onClick = { showAddSheet = true })
        }

        Spacer(Modifier.height(QuickInkSpacing.s2))
        HorizontalDivider(color = colors.borderSoft)
        Spacer(Modifier.height(QuickInkSpacing.s2))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text  = if (picked.isEmpty()) "Pick a tag to combine."
                        else "$intersectCount matching documents",
                style = type.body.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                color = colors.ink,
            )
            if (picked.isNotEmpty() && intersectCount > 0) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text  = "SAVE",
                        style = type.label.copy(fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp),
                        color = colors.accent,
                        modifier = Modifier.clickable(onClick = onSaveAsCollection),
                    )
                    Text(
                        text  = "VIEW",
                        style = type.label.copy(fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp),
                        color = colors.accent,
                        modifier = Modifier.clickable(onClick = onViewAll),
                    )
                }
            }
        }
    }

    if (showAddSheet) {
        // Lightweight selection sheet — reuses the same modal
        // pattern as the tag picker but only emits an `add` event.
        TagSelectSheet(
            tags       = allTags.filter { picked.none { p -> p.id == it.id } },
            onPick     = { tag ->
                onAdd(tag)
                showAddSheet = false
            },
            onDismiss  = { showAddSheet = false },
        )
    }
}

@Composable
private fun PickedTagChip(tag: TagEntity, onRemove: () -> Unit) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current
    val shape  = RoundedCornerShape(999.dp)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(colors.ink, shape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text  = "#",
            style = type.label.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
            color = Color.White.copy(alpha = 0.7f),
        )
        Text(
            text  = tag.name,
            style = type.label.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Medium),
            color = Color.White,
            modifier = Modifier.padding(start = 1.dp),
        )
        Spacer(Modifier.width(4.dp))
        Icon(
            imageVector = Icons.Outlined.Close,
            contentDescription = "Remove tag",
            tint = Color.White,
            modifier = Modifier
                .size(11.dp)
                .clickable(onClick = onRemove),
        )
    }
}

@Composable
private fun AddTagChip(onClick: () -> Unit) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current
    val shape  = RoundedCornerShape(999.dp)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(colors.borderSoft, shape)
            .border(
                width = 1.dp,
                color = colors.muted.copy(alpha = 0.5f),
                shape = shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Add,
            contentDescription = null,
            tint = colors.inkSoft,
            modifier = Modifier.size(11.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text  = "add tag",
            style = type.label.copy(fontSize = 11.5.sp),
            color = colors.inkSoft,
        )
    }
}

@Composable
private fun TagSelectSheet(
    tags: List<TagEntity>,
    onPick: (TagEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor   = colors.surface,
    ) {
        Column(modifier = Modifier.padding(horizontal = QuickInkSpacing.s4, vertical = QuickInkSpacing.s2)) {
            Text(
                text  = "Pick a tag",
                style = type.body.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
                color = colors.ink,
                modifier = Modifier.padding(vertical = QuickInkSpacing.s1),
            )
            HorizontalDivider(color = colors.borderSoft)
            if (tags.isEmpty()) {
                Text(
                    text  = "All tags are already picked.",
                    style = type.meta,
                    color = colors.muted,
                    modifier = Modifier.padding(vertical = QuickInkSpacing.s3),
                )
            } else {
                tags.forEach { tag ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(tag) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text  = "#",
                            style = type.label.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold),
                            color = colors.accent,
                        )
                        Text(
                            text  = tag.name,
                            style = type.body.copy(fontSize = 13.5.sp, fontWeight = FontWeight.Medium),
                            color = colors.ink,
                            modifier = Modifier.padding(start = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

// ─── Most used grid ──────────────────────────────────────────

@Composable
private fun MostUsedHeader() {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = QuickInkSpacing.s4, vertical = QuickInkSpacing.s1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.LocalFireDepartment,
            contentDescription = null,
            tint = colors.accent,
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text  = "MOST USED",
            style = type.label.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp),
            color = colors.muted,
        )
    }
}

@Composable
private fun TagGrid(
    tags: List<TagEntity>,
    countById: Map<String, Int>,
    onOpenTag: (TagEntity) -> Unit,
    onLongPressTag: (TagEntity) -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current

    val ranked = remember(tags, countById) {
        tags.sortedByDescending { countById[it.id] ?: 0 }
    }

    // Hand-roll the 2-col layout via FlowRow rather than
    // LazyVerticalGrid — LazyVerticalGrid in a vertically-scrolling
    // parent needs explicit height bounds, and the tag list is
    // bounded anyway. FlowRow flows nicely.
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = QuickInkSpacing.s4),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        maxItemsInEachRow = 2,
    ) {
        if (ranked.isEmpty()) {
            Text(
                text  = "No tags yet.",
                style = type.meta,
                color = colors.muted,
            )
            return@FlowRow
        }
        ranked.forEach { tag ->
            TagCard(
                tag         = tag,
                count       = countById[tag.id] ?: 0,
                onClick     = { onOpenTag(tag) },
                onLongPress = { onLongPressTag(tag) },
                modifier    = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TagCard(
    tag: TagEntity,
    count: Int,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current
    val shape  = RoundedCornerShape(12.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(colors.surface, shape)
            .border(1.dp, colors.border, shape)
            .combinedClickable(
                onClick     = onClick,
                onLongClick = onLongPress,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text  = "#",
                style = type.body.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold),
                color = colors.accent,
            )
            Text(
                text  = tag.name,
                style = type.body.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                color = colors.ink,
                modifier = Modifier.padding(start = 1.dp),
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text  = "$count document${if (count == 1) "" else "s"}",
            style = type.meta.copy(fontSize = 11.sp),
            color = colors.muted,
        )
    }
}

// ─── Tag CRUD modals ─────────────────────────────────────────

@Composable
private fun TagActionSheet(
    tag: TagEntity,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState()
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current

    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        containerColor   = colors.surface,
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = QuickInkSpacing.s4,
                vertical   = QuickInkSpacing.s2,
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = QuickInkSpacing.s2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text  = "#",
                    style = type.body.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold),
                    color = colors.accent,
                )
                Text(
                    text  = tag.name,
                    style = type.body.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                    color = colors.ink,
                    modifier = Modifier.padding(start = 2.dp),
                )
            }
            androidx.compose.material3.HorizontalDivider(color = colors.borderSoft)

            Text(
                text  = "Rename",
                style = type.body.copy(fontSize = 15.sp),
                color = colors.ink,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onRename)
                    .padding(vertical = 14.dp),
            )
            Text(
                text  = "Delete tag",
                style = type.body.copy(fontSize = 15.sp),
                color = colors.danger,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onDelete)
                    .padding(vertical = 14.dp),
            )
            Spacer(Modifier.height(QuickInkSpacing.s2))
        }
    }
}

@Composable
private fun TagRenameDialog(
    initialName: String,
    title: String = "Rename tag",
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current
    var draft by remember(initialName) { mutableStateOf(initialName) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text  = title,
                style = type.body.copy(fontWeight = FontWeight.SemiBold, fontSize = 17.sp),
                color = colors.ink,
            )
        },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value         = draft,
                onValueChange = { draft = it },
                singleLine    = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.None,
                    imeAction      = androidx.compose.ui.text.input.ImeAction.Done,
                ),
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = colors.border,
                    unfocusedBorderColor = colors.border,
                ),
                shape = RoundedCornerShape(QuickInkRadius.md),
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = {
                    val trimmed = draft.trim()
                    if (trimmed.isEmpty()) return@TextButton
                    onSubmit(trimmed)
                },
            ) {
                Text("Save", color = colors.accent)
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("Cancel", color = colors.ink)
            }
        },
        containerColor = colors.surface,
    )
}

@Composable
private fun TagDeleteConfirmDialog(
    tag: TagEntity,
    captureCount: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text  = "Delete #${tag.name}?",
                style = type.body.copy(fontWeight = FontWeight.SemiBold, fontSize = 17.sp),
                color = colors.ink,
            )
        },
        text = {
            Text(
                text = when (captureCount) {
                    0    -> "The tag isn't attached to any captures. Deleting it can't be undone."
                    1    -> "1 capture will be untagged. The tag is removed from this and other devices."
                    else -> "$captureCount captures will be untagged. The tag is removed from this and other devices."
                },
                style = type.meta,
                color = colors.inkSoft,
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onConfirm) {
                Text("Delete", color = colors.danger)
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("Cancel", color = colors.ink)
            }
        },
        containerColor = colors.surface,
    )
}
