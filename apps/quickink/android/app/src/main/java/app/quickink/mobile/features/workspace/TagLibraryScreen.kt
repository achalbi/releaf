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
import app.quickink.mobile.features.nav.NavTab
import app.quickink.mobile.features.nav.QuickInkBottomNavBar
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
    onHome: () -> Unit,
    onWorkspace: () -> Unit,
    onScan: () -> Unit,
    onSettings: () -> Unit,
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
                tags        = filtered,
                countById   = countById,
                onOpenTag   = onOpenTag,
            )

            Spacer(Modifier.height(QuickInkSpacing.s6))
        }

        QuickInkBottomNavBar(
            activeTab   = NavTab.Workspace,
            onHome      = onHome,
            onWorkspace = onWorkspace,
            onScan      = onScan,
            onSearch    = onOpenSearch,
            onSettings  = onSettings,
            modifier    = Modifier.align(Alignment.BottomCenter),
        )
    }
}

// ─── Header ──────────────────────────────────────────────────

@Composable
private fun TagLibraryHeader(
    tagCount: Int,
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
                Text(
                    text  = "VIEW",
                    style = type.label.copy(fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp),
                    color = colors.accent,
                    modifier = Modifier.clickable(onClick = onViewAll),
                )
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
                tag       = tag,
                count     = countById[tag.id] ?: 0,
                onClick   = { onOpenTag(tag) },
                modifier  = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TagCard(
    tag: TagEntity,
    count: Int,
    onClick: () -> Unit,
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
            .clickable(onClick = onClick)
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
