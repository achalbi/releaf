/*
 * WorkspaceHomeScreen.kt
 *
 * Workspace v1 home (Screen 1 from the design brief). The canonical
 * landing surface for the bottom-nav Workspace tab — post-GA it is
 * always the route (the legacy rollout flag has been retired).
 *
 * Composition (top → bottom):
 *   - Header row     — "Workspace" title + folder count subtitle +
 *                      bell + avatar.
 *   - Search bar     — pill that routes to the existing SearchScreen.
 *   - Continue card  — dark hero with the most-recently-opened
 *                      capture; null state when nothing's been
 *                      opened yet.
 *   - Folders list   — active folders, color-coded, with item-count
 *                      meta. Tap → folder detail (Phase B.1).
 *   - Tag cloud      — top tags by capture count.
 *   - Bottom nav     — Workspace tab active.
 *
 * Deferred to later phases:
 *   - Smart collections strip (Phase C)
 *   - AI bar (Phase E — out of v1)
 *   - "New folder" affordance (Phase B.1 — folder CRUD)
 *   - Tag library "Browse all" link (Phase D)
 *
 * Mirror of `WorkspaceHomeScreen.swift` (lands in the iOS Phase B
 * pass).
 */

@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package app.quickink.mobile.features.workspace

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.quickink.mobile.QuickInkApp
import app.quickink.mobile.data.capture.CaptureEntity
import app.quickink.mobile.data.capturetag.TagCount
import app.quickink.mobile.data.folder.FolderEntity
import app.quickink.mobile.data.folder.FolderRepository
import app.quickink.mobile.data.smartcollection.RuleClause
import app.quickink.mobile.data.smartcollection.SmartCollectionEntity
import app.quickink.mobile.data.smartcollection.SmartCollectionRule
import app.quickink.mobile.data.smartcollection.SmartCollectionRuleInput
import app.releaf.mobile.data.common.IsoClock
import app.releaf.mobile.data.common.Uuidv7
import app.quickink.mobile.data.tag.TagEntity
import app.quickink.mobile.features.nav.NavTab
import app.quickink.mobile.features.nav.QuickInkBottomNavBar
import app.quickink.mobile.features.nav.QuickInkBottomNavReservedHeight
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkRadius
import app.quickink.mobile.ui.theme.QuickInkSpacing
import kotlinx.coroutines.launch

@Composable
fun WorkspaceHomeScreen(
    userId: String,
    onOpenSearch: () -> Unit,
    onOpenFolder: (FolderEntity) -> Unit,
    onOpenContinue: (CaptureEntity) -> Unit,
    onOpenProfile: () -> Unit,
    onOpenTag: (TagEntity) -> Unit,
    onOpenSmartCollection: (SmartCollectionEntity) -> Unit,
    onBrowseTags: () -> Unit,
    onHome: () -> Unit,
    onScan: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors  = LocalQuickInkColors.current
    val context = LocalContext.current
    val app     = remember(context) { context.applicationContext as QuickInkApp }
    val scope   = rememberCoroutineScope()

    // Hide the system status bar while Workspace is on screen. The
    // modern `WindowInsetsControllerCompat.hide(statusBars())` set
    // in `MainActivity.onCreate` doesn't take on some OEM skins
    // (MIUI / HyperOS), so the user reported the bar still rendering
    // here. Layer the legacy `FLAG_FULLSCREEN` on the activity
    // window only for the duration of this composition — those ROMs
    // still honor the older flag. Cleared on dispose so leaving
    // Workspace restores the activity's baseline state for screens
    // (Home, Settings, …) that want the bar's space reserved.
    val activity = context.findActivity()
    DisposableEffect(activity) {
        val window = activity?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }
    }

    val folderRepo = remember(app) {
        FolderRepository(
            folderDao  = app.database.folderDao(),
            captureDao = app.database.captureDao(),
        )
    }

    // Folder CRUD modal state. `editorTarget` is non-null while the
    // create / rename / recolor dialog is up; `actionsForFolder` is
    // the long-pressed folder showing the action sheet; the delete
    // confirmation pops independently.
    var editorTarget       by remember { mutableStateOf<FolderEditorTarget?>(null) }
    var actionsForFolder   by remember { mutableStateOf<FolderEntity?>(null) }
    var confirmDeleteFolder by remember { mutableStateOf<FolderEntity?>(null) }
    var showSmartEditor    by remember { mutableStateOf(false) }
    var confirmDeleteCollection by remember {
        mutableStateOf<SmartCollectionEntity?>(null)
    }
    var actionsForCollection by remember {
        mutableStateOf<SmartCollectionEntity?>(null)
    }
    var editCollection by remember {
        mutableStateOf<SmartCollectionEntity?>(null)
    }

    // Per-tab observers. `userId` keys the flow rebuild so a sign-out
    // / sign-in doesn't leak state across users.
    val folders by produceState(
        initialValue = emptyList<FolderEntity>(),
        key1         = userId,
    ) {
        app.database.folderDao()
            .observeActive(userId)
            .collect { value = it }
    }

    val tags by produceState(
        initialValue = emptyList<TagEntity>(),
        key1         = userId,
    ) {
        app.database.tagDao()
            .observeActive(userId)
            .collect { value = it }
    }

    val smartCollections by produceState(
        initialValue = emptyList<SmartCollectionEntity>(),
        key1         = userId,
    ) {
        app.database.smartCollectionDao()
            .observeActive(userId)
            .collect { value = it }
    }

    val tagCounts by produceState(
        initialValue = emptyList<TagCount>(),
        key1         = userId,
    ) {
        app.database.captureTagDao()
            .observeTagCounts(userId)
            .collect { value = it }
    }

    val continueCandidate by produceState<CaptureEntity?>(
        initialValue = null,
        key1         = userId,
    ) {
        app.database.captureDao()
            .observeContinueCandidate(userId)
            .collect { value = it }
    }

    val folderCaptureCounts by produceState(
        initialValue = emptyMap<String, Int>(),
        key1         = userId,
    ) {
        // Fetched eagerly. Phase B.1 swaps for a JOIN-driven Flow
        // so badges update live as captures move between folders.
        val counts = app.database.captureDao().countByFolder(userId)
        value = counts.associate { it.folderId to it.count }
    }

    // "N new" badge — captures created in the last 7 days. ISO
    // string compare on `created_at` is chronologically correct
    // because the timestamp is always stored in the same format.
    val newSinceIso = remember(userId) {
        val instant = java.time.Instant.now().minus(7, java.time.temporal.ChronoUnit.DAYS)
        instant.toString()
    }
    val folderNewCounts by produceState(
        initialValue = emptyMap<String, Int>(),
        key1         = userId,
        key2         = newSinceIso,
    ) {
        app.database.captureDao()
            .observeNewCountByFolder(userId, newSinceIso)
            .collect { rows ->
                value = rows.associate { it.folderId to it.count }
            }
    }

    Box(
        modifier = modifier
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
            WorkspaceHeader(
                folderCount = folders.size,
                onOpenProfile = onOpenProfile,
            )

            Spacer(Modifier.height(QuickInkSpacing.s3))

            WorkspaceSearchBar(onClick = onOpenSearch)

            Spacer(Modifier.height(QuickInkSpacing.s3))

            continueCandidate?.let { capture ->
                ContinueCard(
                    capture = capture,
                    onClick = { onOpenContinue(capture) },
                )
                Spacer(Modifier.height(QuickInkSpacing.s4))
            }

            SmartCollectionsStrip(
                collections        = smartCollections,
                onOpen             = onOpenSmartCollection,
                onLongPress        = { sc -> actionsForCollection = sc },
                onNewCollection    = { showSmartEditor = true },
            )
            Spacer(Modifier.height(QuickInkSpacing.s4))

            FoldersSection(
                folders             = folders,
                folderCaptureCounts = folderCaptureCounts,
                folderNewCounts     = folderNewCounts,
                onOpenFolder        = onOpenFolder,
                onLongPressFolder   = { folder -> actionsForFolder = folder },
                onNewFolder         = { editorTarget = FolderEditorTarget.Create },
            )

            Spacer(Modifier.height(QuickInkSpacing.s4))

            TagsSection(
                tags = tags,
                tagCounts = tagCounts,
                onOpenTag = onOpenTag,
                onBrowseAll = onBrowseTags,
            )

            Spacer(Modifier.height(QuickInkSpacing.s6))
        }

        QuickInkBottomNavBar(
            activeTab  = NavTab.Workspace,
            onHome     = onHome,
            onWorkspace = { /* current tab — no-op */ },
            onScan     = onScan,
            onSearch   = onOpenSearch,
            onSettings = onSettings,
            modifier   = Modifier.align(Alignment.BottomCenter),
        )
    }

    // ─── Folder CRUD modals ──────────────────────────────────────

    editorTarget?.let { target ->
        val initialName  = if (target is FolderEditorTarget.Edit) target.folder.name  else ""
        val initialColor = if (target is FolderEditorTarget.Edit) target.folder.color
                           else WorkspaceFolderPalette.first()
        val mode = when (target) {
            FolderEditorTarget.Create     -> FolderEditorMode.Create
            is FolderEditorTarget.Edit    -> target.mode
        }
        FolderEditorDialog(
            mode         = mode,
            initialName  = initialName,
            initialColor = initialColor,
            onDismiss    = { editorTarget = null },
            onSubmit     = { newName, newColor ->
                scope.launch {
                    when (target) {
                        FolderEditorTarget.Create -> {
                            folderRepo.create(
                                userId   = userId,
                                name     = newName,
                                color    = newColor,
                                position = folders.size,
                            )
                        }
                        is FolderEditorTarget.Edit -> {
                            if (target.mode != FolderEditorMode.Recolor &&
                                newName != target.folder.name
                            ) {
                                folderRepo.rename(target.folder.id, newName)
                            }
                            if (target.mode != FolderEditorMode.Rename &&
                                !newColor.equals(target.folder.color, ignoreCase = true)
                            ) {
                                folderRepo.setColor(target.folder.id, newColor)
                            }
                        }
                    }
                    editorTarget = null
                }
            },
        )
    }

    actionsForFolder?.let { folder ->
        FolderActionSheet(
            folder        = folder,
            onDismiss     = { actionsForFolder = null },
            onRename      = {
                actionsForFolder = null
                editorTarget = FolderEditorTarget.Edit(folder, FolderEditorMode.Rename)
            },
            onChangeColor = {
                actionsForFolder = null
                editorTarget = FolderEditorTarget.Edit(folder, FolderEditorMode.Recolor)
            },
            onDelete      = {
                actionsForFolder = null
                confirmDeleteFolder = folder
            },
        )
    }

    confirmDeleteFolder?.let { folder ->
        FolderDeleteConfirmDialog(
            folder       = folder,
            captureCount = folderCaptureCounts[folder.id] ?: 0,
            onDismiss    = { confirmDeleteFolder = null },
            onConfirm    = {
                scope.launch {
                    folderRepo.softDelete(userId, folder.id)
                    confirmDeleteFolder = null
                }
            },
        )
    }

    actionsForCollection?.let { collection ->
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = { actionsForCollection = null },
            containerColor = colors.surface,
        ) {
            Column(modifier = Modifier.padding(
                horizontal = QuickInkSpacing.s4,
                vertical = QuickInkSpacing.s2,
            )) {
                Row(
                    modifier = Modifier.padding(vertical = QuickInkSpacing.s2),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = Icons.Outlined.AutoAwesome,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(QuickInkSpacing.s2))
                    Text(
                        text  = collection.name,
                        style = LocalQuickInkTypography.current.body.copy(
                            fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                        ),
                        color = colors.ink,
                    )
                }
                androidx.compose.material3.HorizontalDivider(color = colors.borderSoft)
                Text(
                    text  = "Edit",
                    style = LocalQuickInkTypography.current.body.copy(fontSize = 15.sp),
                    color = colors.ink,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            editCollection = collection
                            actionsForCollection = null
                        }
                        .padding(vertical = 14.dp),
                )
                Text(
                    text  = "Delete",
                    style = LocalQuickInkTypography.current.body.copy(fontSize = 15.sp),
                    color = colors.danger,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            confirmDeleteCollection = collection
                            actionsForCollection = null
                        }
                        .padding(vertical = 14.dp),
                )
                Spacer(Modifier.height(QuickInkSpacing.s2))
            }
        }
    }

    editCollection?.let { collection ->
        val initialInput = remember(collection.id) {
            SmartCollectionRuleInput.fromClauses(
                SmartCollectionRule.decode(collection.ruleJson),
            )
        }
        SmartCollectionEditorDialog(
            folders      = folders,
            tags         = tags,
            initialName  = collection.name,
            initialInput = initialInput,
            initialIcon  = collection.icon,
            initialColor = collection.color,
            isEdit       = true,
            onDismiss    = { editCollection = null },
            onSubmit     = { name, ruleInput, icon, color ->
                val target = collection
                scope.launch {
                    val now = IsoClock.nowIso()
                    val newClauses = ruleInput.toClauses()
                    if (newClauses.isEmpty()) {
                        editCollection = null
                        return@launch
                    }
                    val ruleJson = SmartCollectionRule.encode(newClauses)
                    val dao = app.database.smartCollectionDao()
                    if (name.isNotEmpty() && name != target.name) {
                        dao.rename(target.id, name, now)
                    }
                    dao.setRule(target.id, ruleJson, now)
                    if (icon != target.icon || color != target.color) {
                        dao.setAppearance(target.id, icon, color, now)
                    }
                    editCollection = null
                }
            },
        )
    }

    confirmDeleteCollection?.let { collection ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDeleteCollection = null },
            title = {
                Text(
                    text  = "Delete \"${collection.name}\"?",
                    style = LocalQuickInkTypography.current.body.copy(
                        fontWeight = FontWeight.SemiBold, fontSize = 17.sp,
                    ),
                    color = colors.ink,
                )
            },
            text = {
                Text(
                    text  = "The rule is removed. Captures aren't deleted — " +
                            "the collection is just a saved view.",
                    style = LocalQuickInkTypography.current.meta,
                    color = colors.inkSoft,
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    val target = collection
                    scope.launch {
                        app.database.smartCollectionDao()
                            .softDelete(target.id, IsoClock.nowIso())
                        confirmDeleteCollection = null
                    }
                }) {
                    Text("Delete", color = colors.danger)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = {
                    confirmDeleteCollection = null
                }) {
                    Text("Cancel", color = colors.ink)
                }
            },
            containerColor = colors.surface,
        )
    }

    if (showSmartEditor) {
        SmartCollectionEditorDialog(
            folders   = folders,
            tags      = tags,
            onDismiss = { showSmartEditor = false },
            onSubmit  = { name, ruleInput, icon, color ->
                scope.launch {
                    val now = IsoClock.nowIso()
                    val clauses = ruleInput.toClauses()
                    if (clauses.isEmpty()) {
                        showSmartEditor = false
                        return@launch
                    }
                    val ruleJson = SmartCollectionRule.encode(clauses)
                    val existing = app.database.smartCollectionDao().listActive(userId)
                    val nextPos = (existing.maxOfOrNull { it.position } ?: -1) + 1
                    app.database.smartCollectionDao().insert(
                        SmartCollectionEntity(
                            id        = Uuidv7.generate(),
                            userId    = userId,
                            name      = name.ifEmpty { "Untitled collection" },
                            icon      = icon,
                            color     = color,
                            ruleJson  = ruleJson,
                            position  = nextPos,
                            isSeeded  = false,
                            createdAt = now,
                            updatedAt = now,
                            dirty     = true,
                            deletedAt = null,
                        ),
                    )
                    showSmartEditor = false
                }
            },
        )
    }
}

/** Discriminates between the create flow and an edit-in-place flow
 *  for the [FolderEditorDialog]. Internal to this screen file. */
private sealed interface FolderEditorTarget {
    object Create : FolderEditorTarget
    data class Edit(
        val folder: FolderEntity,
        val mode: FolderEditorMode,
    ) : FolderEditorTarget
}

// ─── Header ──────────────────────────────────────────────────────

@Composable
private fun WorkspaceHeader(
    folderCount: Int,
    onOpenProfile: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = QuickInkSpacing.s4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Workspace",
                style = type.display.copy(fontSize = 30.sp, fontWeight = FontWeight.Medium),
                color = colors.ink,
            )
            Text(
                text = "$folderCount ${if (folderCount == 1) "folder" else "folders"}",
                style = type.meta,
                color = colors.muted,
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable(onClick = { /* notifications — out of scope for B.0 */ }),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Notifications,
                    contentDescription = "Notifications",
                    tint = colors.ink,
                    modifier = Modifier.size(19.dp),
                )
            }
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(colors.accent)
                    .clickable(onClick = onOpenProfile),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text  = "A",
                    color = Color.White,
                    style = type.label.copy(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
                )
            }
        }
    }
}

// ─── Search ──────────────────────────────────────────────────────

@Composable
private fun WorkspaceSearchBar(onClick: () -> Unit) {
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
            text     = "Search documents, tags…",
            style    = type.body.copy(fontSize = 13.sp),
            color    = colors.muted,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(18.dp)
                .background(colors.border),
        )
        Spacer(Modifier.width(QuickInkSpacing.s2))
        Icon(
            imageVector = Icons.Outlined.Tune,
            contentDescription = null,
            tint = colors.inkSoft,
            modifier = Modifier.size(16.dp),
        )
    }
}

// ─── Continue card ───────────────────────────────────────────────

@Composable
private fun ContinueCard(
    capture: CaptureEntity,
    onClick: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current
    val shape  = RoundedCornerShape(18.dp)

    val title = capture.title?.takeIf { it.isNotBlank() }
        ?: "Untitled scan"
    val page  = capture.lastOpenedPage ?: 1
    val total = capture.pageCount.coerceAtLeast(1)
    val progressFraction = (page.toFloat() / total.toFloat()).coerceIn(0f, 1f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = QuickInkSpacing.s4)
            .clip(shape)
            .background(colors.ink, shape)
            .clickable(onClick = onClick)
            .padding(QuickInkSpacing.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Hero thumbnail — uses the preview JPEG when present;
        // falls back to the dark-mode cream rectangle when the
        // file isn't on disk (cross-device synced capture before
        // restorePending finishes downloading the binary).
        val previewUri = capture.previewUri?.takeIf { it.isNotBlank() }
        Box(
            modifier = Modifier
                .width(56.dp)
                .height(70.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(colors.bg),
        ) {
            if (previewUri != null) {
                coil.compose.AsyncImage(
                    model = coil.request.ImageRequest.Builder(LocalContext.current)
                        .data(android.net.Uri.parse(previewUri))
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    modifier = Modifier
                        .width(56.dp)
                        .height(70.dp),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                )
            }
        }

        Spacer(Modifier.width(QuickInkSpacing.s3))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text  = "CONTINUE",
                color = Color(0xFFCFCAC1),
                style = type.label.copy(letterSpacing = 1.2.sp, fontSize = 10.sp),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = title,
                color = Color(0xFFF5F0E5),
                style = type.editorial.copy(fontSize = 17.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Page $page of $total",
                color = Color(0xCCF5F0E5),
                style = type.meta.copy(fontSize = 11.5.sp),
            )
            Spacer(Modifier.height(QuickInkSpacing.s1))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color(0x33F5F0E5)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progressFraction)
                        .height(3.dp)
                        .background(colors.accent),
                )
            }
        }

        Spacer(Modifier.width(QuickInkSpacing.s2))

        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(colors.accent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Continue",
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

// ─── Smart collections strip ─────────────────────────────────

@Composable
private fun SmartCollectionsStrip(
    collections: List<SmartCollectionEntity>,
    onOpen: (SmartCollectionEntity) -> Unit,
    onLongPress: (SmartCollectionEntity) -> Unit,
    onNewCollection: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = QuickInkSpacing.s4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text  = "Smart collections",
            style = type.label.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
            color = colors.ink,
        )
        Text(
            text  = "+ NEW",
            style = type.label.copy(letterSpacing = 1.2.sp, fontSize = 10.5.sp,
                                    fontWeight = FontWeight.SemiBold),
            color = colors.accent,
            modifier = Modifier.clickable(onClick = onNewCollection),
        )
    }

    Spacer(Modifier.height(QuickInkSpacing.s2))

    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = QuickInkSpacing.s4),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(collections, key = { it.id }) { collection ->
            SmartCollectionCard(
                collection  = collection,
                onClick     = { onOpen(collection) },
                onLongPress = { onLongPress(collection) },
            )
        }
    }
}

@Composable
private fun SmartCollectionCard(
    collection: SmartCollectionEntity,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current
    val shape  = RoundedCornerShape(12.dp)
    val tint   = collection.color?.let {
        runCatching { Color(android.graphics.Color.parseColor(it)) }
            .getOrDefault(colors.accent)
    } ?: colors.accent

    Column(
        modifier = Modifier
            .width(142.dp)
            .clip(shape)
            .background(colors.surface, shape)
            .border(1.dp, colors.border, shape)
            .combinedClickable(
                onClick     = onClick,
                onLongClick = onLongPress,
            )
            .padding(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(tint.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector        = iconVectorForSlug(collection.icon),
                contentDescription = null,
                tint               = tint,
                modifier           = Modifier.size(15.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text  = collection.name,
            style = type.body.copy(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold),
            color = colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text  = "Smart",
            style = type.meta.copy(fontSize = 11.sp),
            color = colors.muted,
        )
    }
}

// ─── Folders ─────────────────────────────────────────────────────

@Composable
private fun FoldersSection(
    folders: List<FolderEntity>,
    folderCaptureCounts: Map<String, Int>,
    folderNewCounts: Map<String, Int>,
    onOpenFolder: (FolderEntity) -> Unit,
    onLongPressFolder: (FolderEntity) -> Unit,
    onNewFolder: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = QuickInkSpacing.s4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text  = "Folders",
            style = type.label.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
            color = colors.ink,
        )
        Text(
            text     = "NEW FOLDER",
            style    = type.label.copy(letterSpacing = 1.2.sp, fontSize = 10.5.sp,
                                       fontWeight = FontWeight.SemiBold),
            color    = colors.accent,
            modifier = Modifier.clickable(onClick = onNewFolder),
        )
    }

    Spacer(Modifier.height(QuickInkSpacing.s2))

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = QuickInkSpacing.s4),
    ) {
        folders.forEach { folder ->
            FolderRow(
                folder       = folder,
                captureCount = folderCaptureCounts[folder.id] ?: 0,
                newCount     = folderNewCounts[folder.id] ?: 0,
                onClick      = { onOpenFolder(folder) },
                onLongPress  = { onLongPressFolder(folder) },
            )
        }
        if (folders.isEmpty()) {
            Text(
                text  = "No folders yet.",
                style = type.meta,
                color = colors.muted,
                modifier = Modifier.padding(vertical = QuickInkSpacing.s3),
            )
        }
    }
}

@Composable
private fun FolderRow(
    folder: FolderEntity,
    captureCount: Int,
    newCount: Int,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current
    val tint   = parseFolderColor(folder.color)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick     = onClick,
                onLongClick = onLongPress,
            )
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Folder glyph — a colored rounded square. Compose-side
        // SVG would be ideal but a tinted Box is faithful enough.
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(tint),
        )

        Spacer(Modifier.width(QuickInkSpacing.s3))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text  = folder.name,
                style = type.body.copy(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
                color = colors.ink,
            )
            Spacer(Modifier.height(2.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text  = "$captureCount ${if (captureCount == 1) "item" else "items"}",
                    style = type.meta.copy(fontSize = 11.5.sp),
                    color = colors.muted,
                )
                if (newCount > 0) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(colors.accentSoft, RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(colors.accent),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text  = "$newCount new",
                            style = type.label.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                            color = colors.accentDeep,
                        )
                    }
                }
            }
        }

        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = colors.muted,
            modifier = Modifier.size(18.dp),
        )
    }
}

// ─── Tag cloud ───────────────────────────────────────────────────

@Composable
private fun TagsSection(
    tags: List<TagEntity>,
    tagCounts: List<TagCount>,
    onOpenTag: (TagEntity) -> Unit,
    onBrowseAll: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current

    val countById = remember(tagCounts) { tagCounts.associate { it.tagId to it.docCount } }
    // Show top 10 tags by usage, or all when there are fewer.
    val ranked = remember(tags, countById) {
        tags
            .map { it to (countById[it.id] ?: 0) }
            .sortedByDescending { it.second }
            .take(10)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = QuickInkSpacing.s4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text  = "Tags",
            style = type.label.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
            color = colors.ink,
        )
        Text(
            text     = "BROWSE ALL",
            style    = type.label.copy(letterSpacing = 1.2.sp, fontSize = 10.5.sp,
                                       fontWeight = FontWeight.SemiBold),
            color    = colors.accent,
            modifier = Modifier.clickable(onClick = onBrowseAll),
        )
    }

    Spacer(Modifier.height(QuickInkSpacing.s2))

    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = QuickInkSpacing.s4),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (ranked.isEmpty()) {
            Text(
                text  = "No tags yet.",
                style = type.meta,
                color = colors.muted,
            )
            return@FlowRow
        }
        ranked.forEach { (tag, count) ->
            TagChip(tag = tag, count = count, onClick = { onOpenTag(tag) })
        }
    }
}

@Composable
private fun TagChip(
    tag: TagEntity,
    count: Int,
    onClick: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current
    val shape  = RoundedCornerShape(999.dp)

    Row(
        modifier = Modifier
            .clip(shape)
            .background(colors.surface, shape)
            .border(1.dp, colors.border, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text  = "#",
            style = type.label.copy(fontWeight = FontWeight.Bold, fontSize = 11.5.sp),
            color = colors.accent,
        )
        Text(
            text  = tag.name,
            style = type.label.copy(fontSize = 11.5.sp),
            color = colors.inkSoft,
            modifier = Modifier.padding(start = 2.dp),
        )
        if (count > 0) {
            Spacer(Modifier.width(5.dp))
            Text(
                text  = count.toString(),
                style = type.meta.copy(fontSize = 10.sp),
                color = colors.muted,
            )
        }
    }
}

/**
 * Walk the `ContextWrapper.baseContext` chain looking for the host
 * [Activity] so the Workspace screen can reach into the activity's
 * window and toggle `FLAG_FULLSCREEN`. Returns `null` if called
 * outside an Activity-hosted context (e.g., a Compose preview),
 * which the caller treats as a no-op.
 */
private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
