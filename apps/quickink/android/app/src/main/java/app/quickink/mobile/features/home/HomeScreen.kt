/*
 * HomeScreen.kt
 *
 * QuickInk's home dashboard. Per the mockup spec:
 *   - Greeting header
 *   - Search bar (pill with soft-border tone)
 *   - Sync status pill
 *   - Horizontal "Recent" rail of note thumbnails (handwritten Caveat
 *     preview on lined paper)
 *   - 2-column category grid
 *   - Bottom nav with a Zap (⚡) FAB in the middle
 *
 * Architecturally this replaces the Slice-3 camera-first single-CTA
 * Home. The scanner is now reachable explicitly via the FAB rather
 * than auto-launching on first appear.
 *
 * Mirror of iOS `HomeScreen.swift`.
 */

package app.quickink.mobile.features.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.net.Uri
import app.quickink.mobile.QuickInkApp
import app.quickink.mobile.R
import app.quickink.mobile.data.capture.CaptureEntity
import app.quickink.mobile.data.capture.displayTitle
import app.quickink.mobile.data.category.CategoryEntity
import app.quickink.mobile.features.nav.NavTab
import app.quickink.mobile.features.nav.QuickInkBottomNavBar
import app.quickink.mobile.data.sync.QuickInkSyncScheduler
import app.quickink.mobile.data.sync.QuickInkSyncWorker
import app.quickink.mobile.features.scan.QuickCaptureScreen
import app.quickink.mobile.features.scan.ScanFlowController
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkRadius
import app.quickink.mobile.ui.theme.QuickInkSpacing
import app.quickink.mobile.ui.theme.quickInkDotGridBackground
import app.quickink.mobile.ui.theme.quickInkLinedPaper
import app.releaf.mobile.data.notepad.NotepadEntry
import app.releaf.mobile.data.sync.SyncStateKeys
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.delay
import java.time.LocalTime

@Composable
fun HomeScreen(
    controller: ScanFlowController,
    userId: String,
    onOpenNotes: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSearch: (() -> Unit)? = null,
    onTapCategory: ((String) -> Unit)? = null,
    onOpenEntry: ((String) -> Unit)? = null,
    onOpenScan: ((String) -> Unit)? = null,
    /// Routes to the new Profile editor (photo / phone / punchline).
    /// Picked from the avatar dropdown menu alongside "Sign out".
    /// Wired at QuickInkRoot.
    onOpenProfile: (() -> Unit)? = null,
    /// Avatar dropdown's Sign out action. Wired at QuickInkRoot to
    /// `authStore.signOut()` so the avatar menu can drop the user
    /// straight to the SignIn gate without a Settings detour.
    onSignOut: (() -> Unit)? = null,
    /// Resolved display name shown on the greeting. Already
    /// reconciled at the parent (Settings override > Google session
    /// displayName > null). Null falls through to "QuickInk".
    displayName: String? = null,
    /// Signed-in account email, threaded through from the parent
    /// (`QuickInkRoot`'s AuthStore session). Surfaced under the name
    /// in the profile drawer's banner header. Empty string when
    /// signed out — banner hides the email row.
    email: String = "",
    /// `file://` URI of the user's profile photo, when set. Empty
    /// string falls back to the initial / glyph avatar. Reconciled
    /// at the parent so a Profile-screen edit re-renders the home
    /// avatar without a SharedPreferences observer.
    profilePhotoUri: String = "",
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    val context = LocalContext.current
    val app = context.applicationContext as QuickInkApp

    // Tap on the Zap FAB now shows QuickCaptureScreen (the dark
    // mode-picker surface) rather than launching the system
    // scanner directly. QuickCapture's Zap shutter then triggers
    // the system scanner internally and dismisses itself on
    // completion.
    var showQuickCapture by remember { mutableStateOf(false) }

    // Slide-in profile drawer triggered by the avatar tap — mirror
    // of Releaf's home drawer (apps/releaf/android/.../HomeScreen.kt).
    // Replaces the previous Material `DropdownMenu` so the avatar
    // action surface is visually consistent across the two sibling
    // apps.
    var showProfileDrawer by remember { mutableStateOf(false) }

    if (showQuickCapture) {
        QuickCaptureScreen(
            controller = controller,
            onDismiss  = { showQuickCapture = false },
        )
        return
    }

    // Live recent-captures rail — surfaces the actual scanned-page
    // preview JPEGs, newest first. Tap on a thumb routes to
    // `ScanDetailScreen` (full preview + OCR-on-demand). Capped at
    // 30 rows by the DAO so the rail stays cheap as captures pile
    // up. Mirror of iOS's `CaptureListViewModel`-backed `recentRail`.
    val captureDao = remember(app) { app.database.captureDao() }
    val recentCaptures by remember(userId, captureDao) {
        captureDao.observeRecent(userId, limit = 30)
    }.collectAsState(initial = emptyList())

    // Live category list — every active category, newest-first.
    // Mirrors iOS's CategoryListViewModel observation. Sort happens
    // in the grid composable (kept close to render so changes to
    // `created_at` reorder without an extra remember layer).
    val categoryDao = remember(app) { app.database.categoryDao() }
    val categories by remember(userId, categoryDao) {
        categoryDao.observeActive(userId)
    }.collectAsState(initial = emptyList())

    // System status-bar inset — without this, the greeting crowds the
    // notch / clock area on edge-to-edge devices (target SDK 35+
    // enforces edge-to-edge). Computed at composition time and added
    // to the scroll content's top padding so the bottom nav bar
    // (also inside this Box) is not shifted by it.
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    // Live sync state — same source the Settings → Sync section
    // reads. Both keys land via `SyncStateDao.upsert` from the
    // sync worker, so a fresh pass updates the pill in real time.
    val syncStateDao = remember(app) { app.database.syncStateDao() }
    val pendingRow by syncStateDao
        .observe(SyncStateKeys.PENDING_COUNT)
        .collectAsState(initial = null)
    val pendingCount = pendingRow?.value?.toIntOrNull() ?: 0
    // Locally-dirty rows that haven't been pushed to Drive yet —
    // refreshed by `QuickInkApp`'s 60-second foreground ticker and
    // zeroed by the worker on a successful push. Drives the
    // [PendingSyncPill] right under the greeting; the existing
    // bottom-of-page sync pill stays for "Last synced" context.
    val localDirtyRow by syncStateDao
        .observe(SyncStateKeys.LOCAL_DIRTY_COUNT)
        .collectAsState(initial = null)
    val localDirtyCount = localDirtyRow?.value?.toIntOrNull() ?: 0

    // Same WorkManager signal Settings uses for "Sync now" progress.
    // The tap-ack timestamp bridges the brief enqueue-to-running
    // window so the home pill flips immediately when tapped.
    val workManager = remember(context) { WorkManager.getInstance(context) }
    val syncWorkInfos by remember(workManager) {
        workManager.getWorkInfosForUniqueWorkFlow(QuickInkSyncWorker.ONESHOT_WORK_NAME)
    }.collectAsState(initial = emptyList())
    val runningSyncInfo = syncWorkInfos.firstOrNull {
        it.state == WorkInfo.State.RUNNING
    }
    var syncTapAckUntilMs by remember { mutableStateOf(0L) }
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(syncTapAckUntilMs) {
        while (System.currentTimeMillis() < syncTapAckUntilMs) {
            nowMs = System.currentTimeMillis()
            delay(250L)
        }
        nowMs = System.currentTimeMillis()
    }
    val isSyncTapAckActive = nowMs < syncTapAckUntilMs
    val isHomeSyncInFlight = runningSyncInfo != null || isSyncTapAckActive
    val homeSyncProgressPercent = runningSyncInfo
        ?.progress
        ?.getInt(QuickInkSyncWorker.SYNC_PROGRESS_PERCENT_KEY, 0)
        ?.coerceIn(0, 100)

    Box(modifier = Modifier.fillMaxSize().quickInkDotGridBackground()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    start  = QuickInkSpacing.s5,
                    end    = QuickInkSpacing.s5,
                    top    = statusBarTop + QuickInkSpacing.s6,
                    // Reserve space behind nav bar (~80dp) plus a
                    // little extra (~40dp) so the sync pill at the
                    // end of the scroll content doesn't bump into
                    // the bar when scrolled to the end.
                    bottom = 140.dp,
                ),
        ) {
            HomeHeader(
                displayName     = displayName,
                profilePhotoUri = profilePhotoUri,
                onTapAvatar     = { showProfileDrawer = true },
            )
            // "N pending" pill — one tap kicks the upload-only sync
            // (REPLACE policy via `requestUserSync`). Visible only
            // when there's actual local work to push, so the home
            // surface stays clean for the common up-to-date state.
            //
            // Gated on `localDirtyCount > 0` ALONE — never on a bare
            // `isHomeSyncInFlight`. The post-token-refresh auto-sync
            // in `QuickInkApp` (and the 15-min periodic) flip an
            // in-flight worker on a fresh install with zero scans;
            // showing "Backing up to Drive — 0 items pending" in
            // that state is misleading. The worker still updates
            // `LAST_FULL_SYNC_AT` silently so the bottom-of-page
            // sync timestamp keeps pace.
            //
            // While count > 0 and a sync is running, the pill's
            // own [syncing] flag flips it into the "Backing up…"
            // / progress-bar mode, so the user-tap path still
            // gets visible feedback.
            if (localDirtyCount > 0) {
                Spacer(Modifier.size(QuickInkSpacing.s4))
                PendingSyncPill(
                    count           = localDirtyCount,
                    syncing         = isHomeSyncInFlight,
                    progressPercent = homeSyncProgressPercent,
                    onTap           = {
                        QuickInkSyncScheduler.requestUserSync(context)
                        syncTapAckUntilMs = System.currentTimeMillis() + 6_000L
                    },
                )
            }
            Spacer(Modifier.size(QuickInkSpacing.s5))
            RecentRail(
                captures    = recentCaptures.take(6),
                onAllNotes  = onOpenNotes,
                onOpenScan  = onOpenScan,
            )
            Spacer(Modifier.size(QuickInkSpacing.s5))
            CategoryGrid(
                categories    = categories,
                captures      = recentCaptures,
                onTapCategory = onTapCategory,
            )
            Spacer(Modifier.size(QuickInkSpacing.s4))
            RecentActivityPill(
                pendingReviewCount = pendingCount,
                pendingSyncCount   = localDirtyCount,
            )
        }

        QuickInkBottomNavBar(
            activeTab  = NavTab.Home,
            onHome     = { /* current */ },
            onLibrary  = onOpenNotes,
            onScan     = { showQuickCapture = true },
            onSearch   = { onOpenSearch?.invoke() },
            onSettings = onOpenSettings,
            modifier   = Modifier.align(Alignment.BottomCenter),
        )

        // Slide-in profile drawer — sibling layer above the home
        // content + bottom nav. Renders edge-to-edge so the banner
        // bleeds behind the status bar and the footer bleeds behind
        // the navigation bar (insets handled inside the overlay).
        AnimatedVisibility(
            visible = showProfileDrawer,
            enter   = slideInHorizontally(initialOffsetX = { -it }) + fadeIn(),
            exit    = slideOutHorizontally(targetOffsetX = { -it }) + fadeOut(),
        ) {
            ProfileDrawerOverlay(
                displayName     = (displayName?.trim().orEmpty()).ifEmpty { "QuickInk" },
                email           = email,
                profilePhotoUri = profilePhotoUri,
                onClose         = { showProfileDrawer = false },
                onOpenProfile   = {
                    showProfileDrawer = false
                    (onOpenProfile ?: onOpenSettings)()
                },
                // Library + Search routes: same destinations the
                // bottom-nav Library / Search tabs go to. Close the
                // drawer first so the caller's nav doesn't fight
                // with a half-dismissed sheet animation.
                onOpenLibrary   = {
                    showProfileDrawer = false
                    onOpenNotes()
                },
                onOpenSearch    = {
                    showProfileDrawer = false
                    onOpenSearch?.invoke()
                },
                onOpenSettings  = {
                    showProfileDrawer = false
                    onOpenSettings()
                },
                onSignOut       = {
                    showProfileDrawer = false
                    onSignOut?.invoke()
                },
            )
        }
    }
}

// MARK: - Header

@Composable
private fun HomeHeader(
    displayName: String?,
    profilePhotoUri: String,
    onTapAvatar: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    val context = LocalContext.current
    val greeting = remember {
        when (LocalTime.now().hour) {
            in 5..11  -> "Good morning"
            in 12..17 -> "Good afternoon"
            else      -> "Good evening"
        }
    }
    val resolvedName = (displayName?.trim().orEmpty()).ifEmpty { "QuickInk" }
    val initial = displayName?.trim()?.firstOrNull()?.uppercase()

    Row(verticalAlignment = Alignment.Top) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = greeting, style = type.body, color = colors.muted)
            Spacer(Modifier.size(QuickInkSpacing.s1))
            Text(
                text     = resolvedName,
                style    = type.display,
                color    = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.size(QuickInkSpacing.s1))
            Text(
                text  = "Let's get things organized 👋",
                style = type.body,
                color = colors.muted,
            )
        }
        // Top-right profile pill — renders the user's profile photo
        // (when picked), the user's initial (when we have a name),
        // or a default person glyph. Tap slides the profile drawer
        // in from the leading edge — same pattern as Releaf's home
        // avatar → home drawer.
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(colors.accentSoft)
                .border(2.dp, colors.accent, CircleShape)
                .clickable(onClick = onTapAvatar),
            contentAlignment = Alignment.Center,
        ) {
            if (profilePhotoUri.isNotEmpty()) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(Uri.parse(profilePhotoUri))
                        // Cache disabled because the user's photo
                        // is overwritten in-place at the same path
                        // — without this, a fresh pick would keep
                        // serving the stale bitmap. The avatar is
                        // small (44dp), so the perf cost is nil.
                        .memoryCachePolicy(CachePolicy.DISABLED)
                        .diskCachePolicy(CachePolicy.DISABLED)
                        .crossfade(true)
                        .build(),
                    contentDescription = "Open profile menu",
                    contentScale       = ContentScale.Crop,
                    modifier           = Modifier.fillMaxSize().clip(CircleShape),
                )
            } else if (initial != null) {
                Text(
                    text  = initial,
                    style = type.heading,
                    color = colors.accent,
                )
            } else {
                Icon(
                    imageVector       = Icons.Filled.AccountCircle,
                    contentDescription = "Open profile menu",
                    tint              = colors.accent,
                    modifier          = Modifier.size(28.dp),
                )
            }
        }
    }
}

// MARK: - Pending-sync pill

/**
 * Small accent pill rendered between the greeting and the recent
 * rail when there are local rows that haven't been pushed to
 * Drive yet. Tap kicks `requestUserSync`. The count is sourced
 * from `sync_state[LOCAL_DIRTY_COUNT]`, refreshed every 60 seconds
 * by `QuickInkApp`'s foreground pending-push ticker (which also
 * auto-kicks the sync when count > 0 — the pill is the visible
 * surface of that mechanism).
 */
@Composable
private fun PendingSyncPill(
    count: Int,
    syncing: Boolean,
    progressPercent: Int?,
    onTap: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    val boundedPercent = progressPercent
        ?.coerceIn(0, 100)
        ?.takeIf { syncing && it > 0 }
    val barFraction = ((boundedPercent ?: 3).coerceIn(3, 100)) / 100f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(QuickInkRadius.pill))
            .background(colors.accentSoft)
            .border(1.dp, colors.accent.copy(alpha = 0.55f), RoundedCornerShape(QuickInkRadius.pill))
            .let { base ->
                if (syncing) base else base.clickable(onClick = onTap)
            }
            .padding(horizontal = QuickInkSpacing.s4, vertical = QuickInkSpacing.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(colors.accent),
            contentAlignment = Alignment.Center,
        ) {
            if (syncing) {
                CircularProgressIndicator(
                    modifier    = Modifier.size(16.dp),
                    color       = colors.textOnAccent,
                    strokeWidth = 2.dp,
                )
            } else {
                Text(
                    text  = if (count > 99) "99+" else count.toString(),
                    style = type.label,
                    color = colors.textOnAccent,
                )
            }
        }
        Spacer(Modifier.size(QuickInkSpacing.s3))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text  = if (syncing) {
                    "Backing up to Drive"
                } else if (count == 1) {
                    "1 item pending"
                } else {
                    "$count items pending"
                },
                style = type.body,
                color = colors.ink,
            )
            Text(
                text  = if (syncing) {
                    boundedPercent?.let { "$it% complete" } ?: "Syncing now…"
                } else {
                    "Tap to back up to Drive now"
                },
                style = type.meta,
                color = colors.inkSoft,
            )
            if (syncing) {
                Spacer(Modifier.size(QuickInkSpacing.s2))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(QuickInkRadius.pill))
                        .background(colors.borderSoft),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(barFraction)
                            .height(4.dp)
                            .clip(RoundedCornerShape(QuickInkRadius.pill))
                            .background(colors.accent),
                    )
                }
            }
        }
        if (!syncing) {
            Text(
                text  = "Sync →",
                style = type.label,
                color = colors.accent,
            )
        } else if (boundedPercent != null) {
            Text(
                text  = "$boundedPercent%",
                style = type.label,
                color = colors.accent,
            )
        }
    }
}

// MARK: - Search bar

@Composable
private fun SearchBar(onClick: () -> Unit) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(QuickInkRadius.pill))
            .background(colors.borderSoft)
            .clickable(onClick = onClick)
            .padding(horizontal = QuickInkSpacing.s4, vertical = QuickInkSpacing.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter            = painterResource(id = R.drawable.ic_search),
            contentDescription = null,
            tint               = colors.muted,
            modifier           = Modifier.size(18.dp),
        )
        Spacer(Modifier.size(QuickInkSpacing.s2))
        Text(
            text  = "Search notes & OCR text",
            style = type.body,
            color = colors.muted,
        )
    }
}

// MARK: - Recent activity pill

/**
 * Info pill at the bottom of the home scroll. Surfaces a short
 * status sentence — pending review counts, pending sync counts, or
 * "all caught up" — without offering a tap action. The actionable
 * sync surface is [PendingSyncPill] right under the greeting.
 */
@Composable
private fun RecentActivityPill(
    pendingReviewCount: Int,
    pendingSyncCount: Int,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current

    val subtitle = when {
        pendingReviewCount > 0 ->
            "You have $pendingReviewCount scan${if (pendingReviewCount == 1) "" else "s"} pending review"
        pendingSyncCount > 0   ->
            "$pendingSyncCount item${if (pendingSyncCount == 1) "" else "s"} pending sync"
        else                   -> "You're all caught up"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(QuickInkRadius.pill))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.pill))
            .padding(horizontal = QuickInkSpacing.s4, vertical = QuickInkSpacing.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(colors.accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector        = Icons.Filled.Schedule,
                contentDescription = null,
                tint               = colors.accent,
                modifier           = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.size(QuickInkSpacing.s3))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "Recent Activity", style = type.cardTitle, color = colors.ink)
            Text(text = subtitle, style = type.caption, color = colors.muted)
        }
    }
}

// MARK: - Recent rail

@Composable
private fun RecentRail(
    captures: List<CaptureEntity>,
    onAllNotes: () -> Unit,
    onOpenScan: ((String) -> Unit)?,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text  = "Recent Scans",
                style = type.heading,
                color = colors.ink,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text     = "View all >",
                style    = type.meta,
                color    = colors.accent,
                modifier = Modifier.clickable(onClick = onAllNotes),
            )
        }

        Spacer(Modifier.size(QuickInkSpacing.s3))

        if (captures.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(QuickInkRadius.md))
                    .background(colors.surface)
                    .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.md))
                    .padding(QuickInkSpacing.s4),
            ) {
                Column {
                    Text(
                        text  = "No scans yet.",
                        style = type.body,
                        color = colors.ink,
                    )
                    Spacer(Modifier.size(QuickInkSpacing.s1))
                    Text(
                        text  = "Tap the ⚡ to capture your first page.",
                        style = type.meta,
                        color = colors.muted,
                    )
                }
            }
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3)) {
                itemsIndexed(captures) { _, capture ->
                    RecentScanThumb(
                        capture = capture,
                        onTap   = { onOpenScan?.invoke(capture.id) },
                    )
                }
            }
        }
    }
}

/**
 * Recent-scan thumbnail. Renders the actual preview JPEG produced
 * by ML Kit's document scanner (`captures.preview_uri`). Falls back
 * to a paper-toned placeholder when the file is missing or unreadable.
 * Mirror of iOS's `RecentScanThumb`.
 */
@Composable
private fun RecentScanThumb(capture: CaptureEntity, onTap: () -> Unit) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    val context = LocalContext.current
    val title = capture.displayTitle()
    val displayDate = friendlyMonthDay(capture.createdAt)

    Column(
        modifier = Modifier
            .width(140.dp)
            .clickable(onClick = onTap),
    ) {
        Box(
            modifier = Modifier
                .size(width = 140.dp, height = 120.dp)
                .clip(RoundedCornerShape(QuickInkRadius.md))
                .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.md)),
        ) {
            val previewUri = capture.previewUri
            if (previewUri.isNullOrBlank()) {
                Box(
                    modifier         = Modifier.fillMaxSize().background(colors.borderSoft),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector       = Icons.Filled.Description,
                        contentDescription = null,
                        tint              = colors.muted,
                        modifier          = Modifier.size(28.dp),
                    )
                }
            } else {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(Uri.parse(previewUri))
                        .crossfade(true)
                        .build(),
                    contentDescription = title,
                    contentScale       = ContentScale.Crop,
                    modifier           = Modifier.fillMaxSize(),
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(QuickInkSpacing.s2)
                    .background(
                        color = if (capture.source == "import") colors.accent else colors.surface.copy(alpha = 0.9f),
                        shape = RoundedCornerShape(QuickInkRadius.sm),
                    )
                    .padding(horizontal = QuickInkSpacing.s2, vertical = 2.dp),
            ) {
                Text(
                    text  = if (capture.source == "import") "Import" else "Scan",
                    style = type.caption,
                    color = if (capture.source == "import") colors.textOnAccent else colors.ink.copy(alpha = 0.7f),
                )
            }

            if (capture.pageCount > 1) {
                Text(
                    text  = "${capture.pageCount} pages",
                    style = type.caption,
                    color = colors.textOnAccent,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(QuickInkSpacing.s2)
                        .background(
                            color = colors.ink.copy(alpha = 0.55f),
                            shape = RoundedCornerShape(QuickInkRadius.sm),
                        )
                        .padding(horizontal = QuickInkSpacing.s2, vertical = 2.dp),
                )
            }
        }

        Spacer(Modifier.size(QuickInkSpacing.s2))

        Text(
            // Title Case — matches the library grid/list/search
            // normalisation. Per-word: split on whitespace, lower
            // then titlecase first char.
            text     = title
                .split(' ')
                .joinToString(" ") { word ->
                    word.lowercase().replaceFirstChar(Char::titlecaseChar)
                },
            style    = type.cardTitle,
            color    = colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text  = displayDate,
            style = type.caption,
            color = colors.muted,
        )
    }
}

/**
 * `2026-05-02T14:30:00.000Z` → `May 2`. Falls back to the input's
 * date prefix when parsing fails — mirrors iOS's `friendlyDate`.
 */
private fun friendlyMonthDay(iso: String): String =
    try {
        val instant = java.time.Instant.parse(iso)
        val date    = instant.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        date.format(java.time.format.DateTimeFormatter.ofPattern("MMM d"))
    } catch (_: Exception) {
        iso.take(10)
    }

// MARK: - Category grid

/**
 * Map a category name to its tile icon. Default-seed names get
 * purpose-specific glyphs; user-created categories fall through to
 * a generic label icon. Matches the iOS `iconFor(_:)` switch.
 */
private fun iconForCategory(name: String): ImageVector =
    when (name.lowercase()) {
        "ideas"      -> Icons.Filled.Lightbulb
        "projects"   -> Icons.Filled.Folder
        "meetings"   -> Icons.Filled.Group
        "todo"       -> Icons.Filled.CheckCircle
        "study"      -> Icons.Filled.School
        "journal"    -> Icons.Filled.Book
        "brainstorm" -> Icons.Filled.Star
        else         -> Icons.AutoMirrored.Filled.Label
    }

@Composable
private fun CategoryGrid(
    categories: List<CategoryEntity>,
    captures: List<CaptureEntity>,
    onTapCategory: ((String) -> Unit)?,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current

    // Sort by the most recent capture in each category. ISO-8601
    // `created_at` strings sort lexicographically by timeline, so no
    // parse step needed. Categories with no captures in the loaded
    // window fall to the end (empty-string key sorts smallest in
    // descending order); among those, the DAO's
    // (position ASC, name ASC) ordering carries through because
    // `sortedByDescending` is stable.
    val sorted = remember(categories, captures) {
        val latestByName: Map<String, String> = captures
            .groupBy { (it.category ?: "").lowercase() }
            .mapValues { (_, list) -> list.maxOf { it.createdAt } }
        categories.sortedByDescending { latestByName[it.name.lowercase()] ?: "" }
    }

    Column {
        Text(text = "Quick Categories", style = type.heading, color = colors.ink)
        Spacer(Modifier.size(QuickInkSpacing.s3))
        // 2-column grid sized to the live category count. Number of
        // rows grows with the user's library; LazyVGrid still isn't
        // worth it for the typical handful.
        sorted.chunked(2).forEachIndexed { i, pair ->
            if (i > 0) Spacer(Modifier.size(QuickInkSpacing.s3))
            Row(horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3)) {
                pair.forEach { cat ->
                    val stats = remember(cat.name, captures) {
                        categoryStats(cat.name, captures)
                    }
                    CategoryTile(
                        name     = cat.name,
                        icon     = iconForCategory(cat.name),
                        count    = stats.count,
                        onTap    = { onTapCategory?.invoke(cat.name) },
                        modifier = Modifier.weight(1f),
                    )
                }
                // Pad the trailing row when the count is odd so the
                // last tile keeps its half-width footprint instead of
                // stretching across both columns.
                if (pair.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * Per-category aggregate computed from the captures we already have
 * loaded (`recentCaptures`, capped at 30 by the DAO).
 *
 * At 30 captures the count saturates — fine for the home tile
 * (UI still reads "30 scans"); a dedicated GROUP BY query would lift
 * that ceiling but isn't worth the extra observation while the
 * typical user library stays well under 30.
 */
private data class CategoryStats(val count: Int)

private fun categoryStats(name: String, captures: List<CaptureEntity>): CategoryStats {
    val needle = name.lowercase()
    val matching = captures.filter { (it.category ?: "").lowercase() == needle }
    return CategoryStats(count = matching.size)
}

@Composable
private fun CategoryTile(
    name: String,
    icon: ImageVector,
    count: Int,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(QuickInkRadius.md))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.md))
            .clickable(onClick = onTap)
            .padding(QuickInkSpacing.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(QuickInkRadius.sm))
                .background(colors.accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector        = icon,
                contentDescription = null,
                tint               = colors.accent,
                modifier           = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.size(QuickInkSpacing.s3))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text     = name,
                style    = type.heading,
                color    = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text  = if (count == 0) "No scans yet" else "$count scan${if (count == 1) "" else "s"}",
                style = type.caption,
                color = colors.muted,
            )
        }
        Icon(
            imageVector        = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint               = colors.muted,
            modifier           = Modifier.size(18.dp),
        )
    }
}
