/*
 * SmartCollectionScreen.kt
 *
 * Workspace v1 Screen 3 — a rule-based saved view (e.g. "Needs
 * review"). The header renders the rule as readable chips so the
 * user understands why each capture is in the list; the doc list
 * below is identical to the folder-detail layout, just driven by
 * the rule evaluator instead of a single folder_id filter.
 *
 * Editor UI is out of scope for v1 — Phase C.3 lands the VIEW
 * only. Seeded collections show a "Default" badge in the header
 * meta.
 *
 * Mirror of `SmartCollectionScreen.swift` (iOS Phase C pass).
 */

@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import app.quickink.mobile.data.smartcollection.RuleClause
import app.quickink.mobile.data.smartcollection.SmartCollectionEntity
import app.quickink.mobile.data.smartcollection.SmartCollectionRepository
import app.quickink.mobile.data.smartcollection.SmartCollectionRule
import app.quickink.mobile.features.nav.QuickInkBottomNavReservedHeight
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkRadius
import app.quickink.mobile.ui.theme.QuickInkSpacing

@Composable
fun SmartCollectionScreen(
    collectionId: String,
    userId: String,
    onBack: () -> Unit,
    onOpenCapture: (CaptureEntity) -> Unit,
    onOpenSearch: () -> Unit,
) {
    val colors  = LocalQuickInkColors.current
    val context = LocalContext.current
    val app     = remember(context) { context.applicationContext as QuickInkApp }

    val repo = remember(app) {
        SmartCollectionRepository(
            smartCollectionDao = app.database.smartCollectionDao(),
            captureDao         = app.database.captureDao(),
            captureTagDao      = app.database.captureTagDao(),
            tagDao             = app.database.tagDao(),
        )
    }

    val collection by produceState<SmartCollectionEntity?>(initialValue = null, key1 = collectionId) {
        value = repo.findById(collectionId)
    }

    val captures: List<CaptureEntity> = collection?.let {
        produceState(initialValue = emptyList<CaptureEntity>(), key1 = collectionId) {
            repo.observeMatchingCaptures(userId, it).collect { matched -> value = matched }
        }.value
    } ?: emptyList()

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
            SmartCollectionBar(collection = collection, onBack = onBack)

            collection?.let { coll ->
                Spacer(Modifier.height(QuickInkSpacing.s2))
                RuleHero(collection = coll, matchedCount = captures.size, userId = userId)
            }

            Spacer(Modifier.height(QuickInkSpacing.s2))

            DocsHeader(count = captures.size)

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = QuickInkSpacing.s4),
            ) {
                items(captures, key = { it.id }) { capture ->
                    SmartCollectionDocRow(
                        capture = capture,
                        userId  = userId,
                        onClick = { onOpenCapture(capture) },
                    )
                }
                if (captures.isEmpty() && collection != null) {
                    item {
                        Text(
                            text  = "No captures match this rule yet.",
                            style = LocalQuickInkTypography.current.meta,
                            color = colors.muted,
                            modifier = Modifier.padding(vertical = QuickInkSpacing.s4),
                        )
                    }
                }
            }
        }
    }
}

// ─── Header bar ──────────────────────────────────────────────

@Composable
private fun SmartCollectionBar(
    collection: SmartCollectionEntity?,
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
                .clip(RoundedCornerShape(7.dp))
                .background(colors.accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.AutoAwesome,
                contentDescription = null,
                tint = colors.accentDeep,
                modifier = Modifier.size(14.dp),
            )
        }

        Spacer(Modifier.width(QuickInkSpacing.s2))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text  = "SMART COLLECTION",
                style = type.label.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp),
                color = colors.accentDeep,
            )
            Text(
                text  = collection?.name ?: "…",
                style = type.editorial.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ─── Rule hero ───────────────────────────────────────────────

@Composable
private fun RuleHero(
    collection: SmartCollectionEntity,
    matchedCount: Int,
    userId: String,
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current
    val context = LocalContext.current
    val app     = remember(context) { context.applicationContext as QuickInkApp }

    val rule = remember(collection.ruleJson) { SmartCollectionRule.decode(collection.ruleJson) }

    // Resolve tag / folder names so chips read naturally rather
    // than as raw ids. Read once; cheap because the corpus is small.
    val tagNamesById by produceState(initialValue = emptyMap<String, String>(), key1 = userId) {
        value = app.database.tagDao().listActive(userId).associate { it.id to it.name }
    }
    val folderNamesById by produceState(initialValue = emptyMap<String, String>(), key1 = userId) {
        value = app.database.folderDao().listActive(userId).associate { it.id to it.name }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = QuickInkSpacing.s4)
            .clip(RoundedCornerShape(QuickInkRadius.lg))
            .background(colors.accentSoft.copy(alpha = 0.4f))
            .border(1.dp, colors.accentSoft, RoundedCornerShape(QuickInkRadius.lg))
            .padding(QuickInkSpacing.s3),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.AutoAwesome,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text  = "AUTO-CURATED RULE",
                style = type.label.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp),
                color = colors.accentDeep,
            )
        }
        Spacer(Modifier.height(QuickInkSpacing.s2))

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            rule.forEachIndexed { index, clause ->
                if (index > 0) {
                    Text(
                        text  = "·",
                        style = type.body.copy(fontSize = 11.sp),
                        color = colors.muted,
                    )
                }
                RuleChip(
                    clause           = clause,
                    tagNamesById     = tagNamesById,
                    folderNamesById  = folderNamesById,
                )
            }
            if (rule.isEmpty()) {
                Text(
                    text  = "No clauses",
                    style = type.body.copy(fontSize = 12.sp, fontStyle = FontStyle.Italic),
                    color = colors.muted,
                )
            }
        }

        Spacer(Modifier.height(QuickInkSpacing.s3))
        HorizontalDivider(color = colors.accentSoft)
        Spacer(Modifier.height(QuickInkSpacing.s2))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text  = matchedCount.toString(),
                style = type.editorial.copy(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
                color = colors.ink,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text  = "DOCUMENTS",
                style = type.label.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp),
                color = colors.muted,
            )
        }
    }
}

@Composable
private fun RuleChip(
    clause: RuleClause,
    tagNamesById: Map<String, String>,
    folderNamesById: Map<String, String>,
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current
    val shape  = RoundedCornerShape(4.dp)

    val label = when (clause) {
        is RuleClause.FolderIs -> "in: ${folderNamesById[clause.folderId] ?: "folder"}"
        is RuleClause.TagIs    -> "#${tagNamesById[clause.tagId] ?: "tag"}"
        is RuleClause.TagIsNot -> "not #${tagNamesById[clause.tagId] ?: "tag"}"
        is RuleClause.DateRange -> when (clause.preset) {
            "this_week"     -> "this week"
            "this_month"    -> "this month"
            "last_30_days"  -> "last 30 days"
            "this_quarter"  -> "this quarter"
            else            -> clause.preset
        }
        is RuleClause.SourceIs  -> "source: ${clause.value}"
        is RuleClause.HasHandwriting -> if (clause.value) "handwritten" else "not handwritten"
        is RuleClause.HasSignature   -> if (clause.value) "has signature" else "no signature"
        is RuleClause.HasOcrText     -> if (clause.value) "has OCR text" else "no OCR text"
    }

    Box(
        modifier = Modifier
            .clip(shape)
            .background(colors.surface, shape)
            .border(1.dp, colors.accentSoft, shape)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Text(
            text  = label,
            style = type.label.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
            color = colors.accentDeep,
        )
    }
}

// ─── Doc rows ────────────────────────────────────────────────

@Composable
private fun DocsHeader(count: Int) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = QuickInkSpacing.s4, vertical = QuickInkSpacing.s1),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text  = "Documents",
            style = type.label.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
            color = colors.ink,
        )
        Text(
            text  = "$count match${if (count == 1) "" else "es"}",
            style = type.meta.copy(fontSize = 11.sp),
            color = colors.muted,
        )
    }
}

@Composable
private fun SmartCollectionDocRow(
    capture: CaptureEntity,
    userId: String,
    onClick: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current

    val title = capture.title?.takeIf { it.isNotBlank() }
        ?: "Untitled scan"

    Column(modifier = Modifier
        .fillMaxWidth()
        .clickable(onClick = onClick)
        .padding(vertical = 13.dp)) {
        Row(verticalAlignment = Alignment.Top) {
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
        HorizontalDivider(color = colors.borderSoft)
    }
}
