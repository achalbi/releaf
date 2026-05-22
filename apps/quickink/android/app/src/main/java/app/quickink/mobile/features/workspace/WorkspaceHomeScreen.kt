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
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import app.quickink.mobile.data.capturelocation.LocationCount
import app.quickink.mobile.data.captureperson.PersonCount
import app.quickink.mobile.data.capturetag.TagCount
import app.quickink.mobile.data.folder.FolderEntity
import app.quickink.mobile.data.folder.FolderRepository
import app.quickink.mobile.data.location.LocationEntity
import app.quickink.mobile.data.person.PersonEntity
import app.quickink.mobile.data.smartcollection.RuleClause
import app.quickink.mobile.data.smartcollection.SmartCollectionEntity
import app.quickink.mobile.data.smartcollection.SmartCollectionRule
import app.quickink.mobile.data.smartcollection.SmartCollectionRuleInput
import app.quickink.mobile.data.workspace.workspaceFolderSeeds
import app.releaf.mobile.data.common.IsoClock
import app.releaf.mobile.data.common.Uuidv7
import app.quickink.mobile.data.tag.TagEntity
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
    onOpenLocation: (LocationEntity) -> Unit,
    onOpenPerson: (PersonEntity) -> Unit,
    onBrowseTags: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors  = LocalQuickInkColors.current
    val context = LocalContext.current
    val app     = remember(context) { context.applicationContext as QuickInkApp }
    val scope   = rememberCoroutineScope()

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

    // Locations + per-location attached-capture counts. Drives the
    // Locations section below the Tags cloud. Counts are computed
    // by [CaptureLocationDao.observeLocationCounts] which joins the
    // capture_locations rows against the user's active captures.
    val locations by produceState(
        initialValue = emptyList<LocationEntity>(),
        key1         = userId,
    ) {
        app.database.locationDao()
            .observeActive(userId)
            .collect { value = it }
    }

    val locationCounts by produceState(
        initialValue = emptyList<LocationCount>(),
        key1         = userId,
    ) {
        app.database.captureLocationDao()
            .observeLocationCounts(userId)
            .collect { value = it }
    }

    // Editor dialog state — null `existing` opens the dialog in
    // create mode; a non-null row opens it in edit mode.
    var locationEditorOpen by remember { mutableStateOf(false) }
    var locationEditorExisting by remember { mutableStateOf<LocationEntity?>(null) }

    // People observers + editor state — same shape as Locations.
    val people by produceState(
        initialValue = emptyList<PersonEntity>(),
        key1         = userId,
    ) {
        app.database.personDao()
            .observeActive(userId)
            .collect { value = it }
    }

    val personCounts by produceState(
        initialValue = emptyList<PersonCount>(),
        key1         = userId,
    ) {
        app.database.capturePersonDao()
            .observePersonCounts(userId)
            .collect { value = it }
    }

    var personEditorOpen by remember { mutableStateOf(false) }
    var personEditorExisting by remember { mutableStateOf<PersonEntity?>(null) }

    val recentlyOpened by produceState<List<CaptureEntity>>(
        initialValue = emptyList(),
        key1         = userId,
    ) {
        app.database.captureDao()
            .observeRecentlyOpened(userId, RECENTLY_OPENED_LIMIT)
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
    //
    // Phase 3 of the Workspace tab refresh dropped the "N new"
    // badge from FolderRow per the spec's row layout. The
    // `observeNewCountByFolder` query stays in `CaptureDao` so a
    // future polish pass can re-attach the indicator without
    // touching the data layer.

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

            val hero = recentlyOpened.firstOrNull()
            if (hero != null) {
                RecentsCarousel(
                    hero   = hero,
                    onOpen = onOpenContinue,
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
            WorkspaceSectionDivider()
            Spacer(Modifier.height(QuickInkSpacing.s4))

            FoldersSection(
                folders             = folders,
                folderCaptureCounts = folderCaptureCounts,
                onOpenFolder        = onOpenFolder,
                onLongPressFolder   = { folder -> actionsForFolder = folder },
                onNewFolder         = { editorTarget = FolderEditorTarget.Create },
            )

            Spacer(Modifier.height(QuickInkSpacing.s4))
            WorkspaceSectionDivider()
            Spacer(Modifier.height(QuickInkSpacing.s4))

            TagsSection(
                tags        = tags,
                onOpenTag   = onOpenTag,
                onBrowseAll = onBrowseTags,
            )

            Spacer(Modifier.height(QuickInkSpacing.s4))
            WorkspaceSectionDivider()
            Spacer(Modifier.height(QuickInkSpacing.s4))

            LocationsSection(
                locations      = locations,
                locationCounts = locationCounts,
                onOpenLocation = onOpenLocation,
                onNewLocation  = {
                    locationEditorExisting = null
                    locationEditorOpen     = true
                },
            )

            Spacer(Modifier.height(QuickInkSpacing.s4))
            WorkspaceSectionDivider()
            Spacer(Modifier.height(QuickInkSpacing.s4))

            PeopleSection(
                people       = people,
                personCounts = personCounts,
                onOpenPerson = onOpenPerson,
                onNewPerson  = {
                    personEditorExisting = null
                    personEditorOpen     = true
                },
            )

            Spacer(Modifier.height(QuickInkSpacing.s6))
        }

    }

    // Locations editor — create / edit a single row. Owns its own
    // DAO writes; we only feed it the userId + the row being edited
    // (null for create). The list refreshes through the observer
    // above when the editor commits.
    if (locationEditorOpen) {
        LocationEditorDialog(
            userId    = userId,
            existing  = locationEditorExisting,
            onDismiss = { locationEditorOpen = false },
            onSaved   = { locationEditorOpen = false },
        )
    }

    // People editor — same shape as the locations editor above.
    if (personEditorOpen) {
        PersonEditorDialog(
            userId    = userId,
            existing  = personEditorExisting,
            onDismiss = { personEditorOpen = false },
            onSaved   = { personEditorOpen = false },
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

// ─── Section divider ─────────────────────────────────────────────

@Composable
private fun WorkspaceSectionDivider() {
    val colors = LocalQuickInkColors.current
    androidx.compose.material3.HorizontalDivider(
        modifier  = Modifier.padding(horizontal = QuickInkSpacing.s4),
        thickness = 1.dp,
        color     = colors.border,
    )
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
    modifier: Modifier = Modifier,
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
        modifier = modifier
            .height(RecentsCarouselHeight)
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

// ─── Recently-opened strip ───────────────────────────────────

/** Workspace recents currently shows only the primary Continue hero. */
private const val RECENTLY_OPENED_LIMIT = 1

/**
 * Single horizontal row that keeps the Continue hero aligned with the
 * workspace sections while leaving room for future recents expansion.
 */
@Composable
private fun RecentsCarousel(
    hero: CaptureEntity,
    onOpen: (CaptureEntity) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = QuickInkSpacing.s4),
    ) {
        ContinueCard(
            capture  = hero,
            onClick  = { onOpen(hero) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Shared Continue hero height. Matches the card's natural height
 *  (thumb 70 + s3 padding × 2). */
private val RecentsCarouselHeight = 94.dp

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

/**
 * Folders section — three tier blocks (Workflow / Life domains /
 * Creative & output) for the 12 seeded folders, plus a `Custom`
 * tier block for any user-created folders that coexist with the
 * seed (Phase 2 scope call: "Keep both"). Phase 3 of
 * `design/WORKSPACE_TAB_HANDOFF.md`. The NEW FOLDER affordance
 * moved into the Custom tier header so the user-owned region owns
 * the CRUD entry point.
 */
@Composable
private fun FoldersSection(
    folders: List<FolderEntity>,
    folderCaptureCounts: Map<String, Int>,
    onOpenFolder: (FolderEntity) -> Unit,
    onLongPressFolder: (FolderEntity) -> Unit,
    onNewFolder: () -> Unit,
) {
    val grouped = remember(folders) { groupFoldersByTier(folders) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = QuickInkSpacing.s4),
    ) {
        if (folders.isEmpty()) {
            // Pre-seed placeholder: the LaunchedEffect-style seeder
            // runs on first sign-in and lands the 12 seeded folders
            // a beat later. While it's in flight the section reads
            // "Setting up your workspace…" so the user doesn't see
            // a bare Custom tier and wonder where the rest went.
            WorkspaceSettingUpPlaceholder()
        } else {
            TierBlock(
                tier              = FolderTier.Workflow,
                folders           = grouped.workflow,
                captureCounts     = folderCaptureCounts,
                onOpenFolder      = onOpenFolder,
                onLongPressFolder = onLongPressFolder,
                topSpacing        = 0.dp,
            )
            TierBlock(
                tier              = FolderTier.Life,
                folders           = grouped.life,
                captureCounts     = folderCaptureCounts,
                onOpenFolder      = onOpenFolder,
                onLongPressFolder = onLongPressFolder,
            )
            TierBlock(
                tier              = FolderTier.Creative,
                folders           = grouped.creative,
                captureCounts     = folderCaptureCounts,
                onOpenFolder      = onOpenFolder,
                onLongPressFolder = onLongPressFolder,
            )
            TierBlock(
                tier              = FolderTier.Custom,
                folders           = grouped.custom,
                captureCounts     = folderCaptureCounts,
                onOpenFolder      = onOpenFolder,
                onLongPressFolder = onLongPressFolder,
                onNewFolder       = onNewFolder,
                emptyState        = "No custom folders yet — tap NEW FOLDER to add one.",
            )
        }
    }
}

/**
 * Transient empty state for the folders + tag-vocabulary regions
 * while the first-launch seeder is in flight. Shows once per fresh
 * install; after the seeder lands (typically sub-second) the
 * tiered list takes over.
 */
@Composable
private fun WorkspaceSettingUpPlaceholder() {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current
    Row(
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier              = Modifier
            .padding(vertical = 12.dp)
            .semantics { contentDescription = "Setting up your workspace" },
    ) {
        androidx.compose.material3.CircularProgressIndicator(
            color       = colors.accent,
            strokeWidth = 2.dp,
            modifier    = Modifier.size(14.dp),
        )
        Text(
            text  = "Setting up your workspace…",
            style = type.meta.copy(fontStyle = FontStyle.Italic, fontSize = 12.5.sp),
            color = colors.muted,
        )
    }
}

/**
 * One tier of the folders section — header + (optional) NEW FOLDER
 * pill + folder rows. Seeded tiers without rows hide entirely.
 * The Custom tier always renders (it owns the create affordance
 * and shows its empty-state copy when the user has none).
 */
@Composable
private fun TierBlock(
    tier: FolderTier,
    folders: List<FolderEntity>,
    captureCounts: Map<String, Int>,
    onOpenFolder: (FolderEntity) -> Unit,
    onLongPressFolder: (FolderEntity) -> Unit,
    onNewFolder: (() -> Unit)? = null,
    emptyState: String? = null,
    topSpacing: androidx.compose.ui.unit.Dp = 18.dp,
) {
    if (folders.isEmpty() && onNewFolder == null) return

    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current

    Spacer(Modifier.height(topSpacing))
    Row(
        modifier              = Modifier.fillMaxWidth(),
        verticalAlignment     = Alignment.Bottom,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        TierHeader(tier = tier, modifier = Modifier.weight(1f))
        if (onNewFolder != null) {
            Text(
                text     = "NEW FOLDER",
                style    = type.label.copy(
                    letterSpacing = 1.2.sp,
                    fontSize      = 10.5.sp,
                    fontWeight    = FontWeight.SemiBold,
                ),
                color    = colors.accent,
                modifier = Modifier
                    .clickable(onClick = onNewFolder)
                    .padding(bottom = 6.dp, start = 8.dp),
            )
        }
    }

    if (folders.isEmpty() && emptyState != null) {
        Text(
            text     = emptyState,
            style    = type.meta.copy(fontStyle = FontStyle.Italic),
            color    = colors.muted,
            modifier = Modifier.padding(vertical = 12.dp),
        )
    } else {
        folders.forEachIndexed { index, folder ->
            val isLast = index == folders.lastIndex
            FolderRow(
                name             = folder.name,
                count            = captureCounts[folder.id] ?: 0,
                tier             = tier,
                onClick          = { onOpenFolder(folder) },
                modifier         = Modifier.combinedClickable(
                    onClick     = { onOpenFolder(folder) },
                    onLongClick = { onLongPressFolder(folder) },
                ),
                description      = descriptionForSeededFolder(folder),
                isSystemManaged  = isInboxFolder(folder),
                showBottomBorder = !isLast,
            )
        }
    }
}

private data class GroupedFolders(
    val workflow: List<FolderEntity>,
    val life:     List<FolderEntity>,
    val creative: List<FolderEntity>,
    val custom:   List<FolderEntity>,
)

private fun groupFoldersByTier(folders: List<FolderEntity>): GroupedFolders {
    val workflow = mutableListOf<FolderEntity>()
    val life     = mutableListOf<FolderEntity>()
    val creative = mutableListOf<FolderEntity>()
    val custom   = mutableListOf<FolderEntity>()
    folders.forEach { folder ->
        if (!folder.isSeeded) { custom.add(folder); return@forEach }
        when (folder.tier) {
            1 -> workflow.add(folder)
            2 -> life.add(folder)
            3 -> creative.add(folder)
            else -> custom.add(folder)
        }
    }
    return GroupedFolders(workflow, life, creative, custom)
}

/**
 * Inbox row carries the lock glyph; everything else doesn't.
 * Inbox is identified by the seeded stable ID — works even if
 * the user accidentally creates their own folder named "Inbox"
 * in the Custom tier.
 */
private fun isInboxFolder(folder: FolderEntity): Boolean =
    folder.isSeeded && folder.id == "inbox"

/**
 * One-line description for the row — pulled from the seeded
 * vocabulary for spec'd folders, null for user folders (their
 * names already telegraph intent).
 */
private fun descriptionForSeededFolder(folder: FolderEntity): String? {
    if (!folder.isSeeded) return null
    return workspaceFolderSeeds.firstOrNull { it.id == folder.id }?.desc
}

// ─── Tag vocabulary ──────────────────────────────────────────────

/**
 * Tag vocabulary section — seven [TagBucketBlock]s in spec order
 * (Status / People / Org & Place / Energy / Time / Kind / Source).
 * Replaces the legacy top-10-chip cloud per Phase 4 of
 * `design/WORKSPACE_TAB_HANDOFF.md`. Pills route via [onOpenTag]
 * to the existing tag-filtered list; the "BROWSE ALL TAGS" trail
 * at the bottom routes to TagLibraryScreen so the user can still
 * reach legacy unbucketed tags.
 */
@Composable
private fun TagsSection(
    tags: List<TagEntity>,
    onOpenTag: (TagEntity) -> Unit,
    onBrowseAll: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current

    val activeTags = remember(tags) { tags.filter { it.deletedAt == null } }
    val tagById    = remember(activeTags) { activeTags.associateBy { it.id } }
    val pillsByBucket = remember(activeTags) {
        activeTags
            .filter { !it.bucket.isNullOrEmpty() }
            .groupBy { it.bucket!! }
    }
    val seededCount = remember(activeTags) {
        activeTags.count { !it.bucket.isNullOrEmpty() }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = QuickInkSpacing.s4),
    ) {
        Row(
            modifier              = Modifier.fillMaxWidth(),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text  = "TAG VOCABULARY",
                style = type.eyebrow.copy(letterSpacing = 1.4.sp, fontSize = 11.sp),
                color = colors.ink,
            )
            Text(
                text  = "$seededCount tags · 7 buckets",
                style = type.meta.copy(fontSize = 11.sp, fontStyle = FontStyle.Italic),
                color = colors.muted,
            )
        }

        Spacer(Modifier.height(6.dp))

        if (seededCount == 0) {
            // Mirror of FoldersSection's pre-seed placeholder.
            // Bucketless legacy tags are still reachable via the
            // "BROWSE ALL TAGS" trail below; this state only
            // renders while the v25 destructive-rebuild tag seed
            // is in flight.
            WorkspaceSettingUpPlaceholder()
        } else {
            workspaceTagBuckets.forEachIndexed { index, bucket ->
                val rows = pillsByBucket[bucket.id].orEmpty()
                TagBucketBlock(
                    bucket           = bucket,
                    pills            = rows.map { TagPillSpec(id = it.id, label = it.name) },
                    onTapTag         = { spec -> tagById[spec.id]?.let(onOpenTag) },
                    onAddTag         = { /* TODO Phase 5 polish — open create-tag sheet pre-bound to bucket */ },
                    showBottomBorder = index != workspaceTagBuckets.lastIndex,
                    modifier         = Modifier.semantics {
                        contentDescription = "${bucket.name}, ${rows.size} tags"
                    },
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier          = Modifier
                .padding(top = 14.dp, bottom = 4.dp)
                .clickable(onClick = onBrowseAll),
        ) {
            Text(
                text  = "BROWSE ALL TAGS",
                style = type.eyebrow.copy(
                    letterSpacing = 1.2.sp,
                    fontSize      = 10.5.sp,
                    fontWeight    = FontWeight.SemiBold,
                ),
                color = colors.accent,
            )
            Icon(
                imageVector       = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint              = colors.accent,
                modifier          = Modifier
                    .padding(start = 4.dp)
                    .size(14.dp),
            )
        }
    }
}

// ─── Locations section ───────────────────────────────────────────

/**
 * Places section — one row per user-defined place ("Home",
 * "Work", custom search/GPS-pinned rows). Mirrors the FoldersSection
 * shape (header + list of rows) since each place has both a name
 * and an optional address that benefit from the wider row layout.
 *
 * Tapping a row routes back into [LocationEditorDialog] in edit
 * mode; "NEW PLACE" in the header opens it in create mode.
 * Counts are sourced from the `capture_locations` join, so a row's
 * "N items" badge reflects active attachments only.
 */
@Composable
private fun LocationsSection(
    locations: List<LocationEntity>,
    locationCounts: List<LocationCount>,
    onOpenLocation: (LocationEntity) -> Unit,
    onNewLocation: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current

    val countById = remember(locationCounts) {
        locationCounts.associate { it.locationId to it.docCount }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = QuickInkSpacing.s4),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text  = "Places",
            style = type.label.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
            color = colors.ink,
        )
        Text(
            text     = "NEW PLACE",
            style    = type.label.copy(
                letterSpacing = 1.2.sp,
                fontSize      = 10.5.sp,
                fontWeight    = FontWeight.SemiBold,
            ),
            color    = colors.accent,
            modifier = Modifier.clickable(onClick = onNewLocation),
        )
    }

    Spacer(Modifier.height(QuickInkSpacing.s2))

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = QuickInkSpacing.s4),
    ) {
        if (locations.isEmpty()) {
            Text(
                text     = "No places yet.",
                style    = type.meta,
                color    = colors.muted,
                modifier = Modifier.padding(vertical = QuickInkSpacing.s3),
            )
        } else {
            locations.forEach { loc ->
                LocationRow(
                    location     = loc,
                    captureCount = countById[loc.id] ?: 0,
                    onClick      = { onOpenLocation(loc) },
                )
            }
        }
    }
}

@Composable
private fun LocationRow(
    location: LocationEntity,
    captureCount: Int,
    onClick: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Location glyph in an accent-soft square — same footprint
        // as FolderRow's color chip so the rows align vertically
        // when stacked under the Folders section.
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(colors.accentSoft.copy(alpha = 0.7f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector        = Icons.Filled.LocationOn,
                contentDescription = null,
                tint               = colors.accent,
                modifier           = Modifier.size(14.dp),
            )
        }

        Spacer(Modifier.width(QuickInkSpacing.s3))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text  = location.name,
                style = type.body.copy(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
                color = colors.ink,
            )
            val addr = location.address
            if (!addr.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text     = addr,
                    style    = type.meta.copy(fontSize = 11.5.sp),
                    color    = colors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        DocCountBadge(count = captureCount)

        Spacer(Modifier.width(QuickInkSpacing.s2))

        Icon(
            imageVector        = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint               = colors.muted,
            modifier           = Modifier.size(18.dp),
        )
    }
}

/**
 * Right-aligned numeric badge for the row's attached-document count.
 * Mirror of the "N new" pill on FolderRow — small accent-soft pill
 * with the integer in semibold. Renders an empty-state "0" pill in
 * muted tone when nothing's attached.
 */
@Composable
private fun DocCountBadge(count: Int) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current
    val active = count > 0
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(
                if (active) colors.accentSoft else colors.borderSoft,
            )
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text  = count.toString(),
            style = type.label.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
            color = if (active) colors.accentDeep else colors.muted,
        )
        Spacer(Modifier.width(3.dp))
        Text(
            text  = if (count == 1) "doc" else "docs",
            style = type.label.copy(fontSize = 10.5.sp),
            color = if (active) colors.accentDeep else colors.muted,
        )
    }
}

// ─── People section ──────────────────────────────────────────────

/**
 * People section — one row per user-defined person. Mirror of the
 * LocationsSection above. Tap a row to edit; "NEW PERSON" in the
 * header opens the editor in create mode. Counts come from
 * `capture_people`, so the "N items" badge reflects active
 * attachments only.
 */
@Composable
private fun PeopleSection(
    people: List<PersonEntity>,
    personCounts: List<PersonCount>,
    onOpenPerson: (PersonEntity) -> Unit,
    onNewPerson: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current

    val countById = remember(personCounts) {
        personCounts.associate { it.personId to it.docCount }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = QuickInkSpacing.s4),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text  = "People",
            style = type.label.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
            color = colors.ink,
        )
        Text(
            text     = "NEW PERSON",
            style    = type.label.copy(
                letterSpacing = 1.2.sp,
                fontSize      = 10.5.sp,
                fontWeight    = FontWeight.SemiBold,
            ),
            color    = colors.accent,
            modifier = Modifier.clickable(onClick = onNewPerson),
        )
    }

    Spacer(Modifier.height(QuickInkSpacing.s2))

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = QuickInkSpacing.s4),
    ) {
        if (people.isEmpty()) {
            Text(
                text     = "No people yet.",
                style    = type.meta,
                color    = colors.muted,
                modifier = Modifier.padding(vertical = QuickInkSpacing.s3),
            )
        } else {
            people.forEach { person ->
                PersonRow(
                    person       = person,
                    captureCount = countById[person.id] ?: 0,
                    onClick      = { onOpenPerson(person) },
                )
            }
        }
    }
}

@Composable
private fun PersonRow(
    person: PersonEntity,
    captureCount: Int,
    onClick: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(colors.accentSoft.copy(alpha = 0.7f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector        = Icons.Filled.Person,
                contentDescription = null,
                tint               = colors.accent,
                modifier           = Modifier.size(14.dp),
            )
        }

        Spacer(Modifier.width(QuickInkSpacing.s3))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text  = person.name,
                style = type.body.copy(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
                color = colors.ink,
            )
            val sub = person.contactPhone?.takeIf { it.isNotBlank() }
                ?: person.contactEmail?.takeIf { it.isNotBlank() }
            if (sub != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text     = sub,
                    style    = type.meta.copy(fontSize = 11.5.sp),
                    color    = colors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        DocCountBadge(count = captureCount)

        Spacer(Modifier.width(QuickInkSpacing.s2))

        Icon(
            imageVector        = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint               = colors.muted,
            modifier           = Modifier.size(18.dp),
        )
    }
}
