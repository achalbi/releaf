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
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Eco
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import coil.compose.SubcomposeAsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkColors
import app.quickink.mobile.ui.theme.QuickInkRadius
import app.quickink.mobile.ui.theme.QuickInkSpacing
import app.quickink.mobile.ui.theme.quickInkDotGridBackground
import app.quickink.mobile.ui.theme.quickInkLinedPaper
import app.releaf.mobile.data.notepad.NotepadEntry
import app.releaf.mobile.data.sync.SyncStateKeys
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlin.math.ln
import kotlin.math.roundToInt
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

    // Lifetime page total — drives the sustainability hero. Sums
    // `page_count` across every active capture in SQL so the figure
    // is accurate regardless of how many rows the recent rail has
    // loaded. Returns `null` for the empty-library case (SUM over zero
    // rows), which the hero maps to its first-capture copy.
    val totalPagesSaved by remember(userId, captureDao) {
        captureDao.observeTotalPageCount(userId)
    }.collectAsState(initial = 0)

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
            Spacer(Modifier.size(QuickInkSpacing.s4))
            SustainabilityHero(totalPages = totalPagesSaved ?: 0)
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

    // Avatar pill shares the centre Zap FAB's chrome (see
    // QuickInkBottomNavBar.BrandTab): same 56/4/64 dimensions,
    // same coral gradient brush, same canvas-coloured outer ring,
    // same ambient/contact drop shadow stack. Differs in two
    // intentional ways: no upward `lift` (the avatar isn't floating
    // above a bar surface), and the inner glyph is dialled down
    // from the FAB's 30dp Bolt to a 32dp Outlined.Face — the
    // FAB is the primary action; the avatar is a secondary
    // identity tap and reads quieter.
    val avatarInner     = 56.dp
    val avatarImageSize = 32.dp
    val avatarRing      = 4.dp
    val avatarOuter     = avatarInner + avatarRing * 2
    val coralGradient = Brush.verticalGradient(
        colors = listOf(colors.accent, colors.accentDeep),
    )

    Row(verticalAlignment = Alignment.Top) {
        // Top-left profile pill — a button, not a label. Mirrors
        // the centre Zap FAB's chrome so the two read as siblings:
        // user's space on the left, action space on the right.
        // Profile photo (when picked) and the fallback Face glyph
        // are capped at 22dp inside the coral disc. Tap slides the
        // profile drawer in from the leading edge — same pattern
        // as Releaf's home avatar → home drawer.
        // No ripple — the coral disc + canvas ring + drop shadow
        // already read as pressable, and the default rectangular
        // ripple paints in the four corners outside the circular
        // disc (since the layout box is 64dp square, not a circle).
        // Mirror of BrandTab's interactionSource pattern.
        val avatarInteraction = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .size(avatarOuter)
                .clickable(
                    interactionSource = avatarInteraction,
                    indication        = null,
                    onClick           = onTapAvatar,
                ),
            contentAlignment = Alignment.Center,
        ) {
            // Outer ring + shadow disc. The drawBehind block paints
            // the same two stacked drop shadows BrandTab uses
            // (ambient: wider/softer, contact: tighter/darker), then
            // a canvas-coloured ring sits on top — same `colors.bg`
            // tone the FAB uses, kept consistent for cross-element
            // coherence on the home screen.
            //
            // Critical: the parent Box deliberately does NOT
            // `.clip(CircleShape)`. The ambient shadow extends ~8dp
            // past the disc edge — clipping the parent kills it.
            Box(
                modifier = Modifier
                    .size(avatarOuter)
                    .drawBehind {
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        val r  = size.width / 2f

                        // Ambient — wider, softer.
                        val ambientR    = r + 8.dp.toPx()
                        val ambientStop = r / ambientR
                        drawCircle(
                            brush = Brush.radialGradient(
                                colorStops = arrayOf(
                                    ambientStop to Color.Black.copy(alpha = 0.06f),
                                    1f          to Color.Transparent,
                                ),
                                center = Offset(cx, cy + 3.dp.toPx()),
                                radius = ambientR,
                            ),
                            radius = ambientR,
                            center = Offset(cx, cy + 3.dp.toPx()),
                        )

                        // Contact — tighter, darker.
                        val contactR    = r + 3.dp.toPx()
                        val contactStop = r / contactR
                        drawCircle(
                            brush = Brush.radialGradient(
                                colorStops = arrayOf(
                                    contactStop to Color.Black.copy(alpha = 0.14f),
                                    1f          to Color.Transparent,
                                ),
                                center = Offset(cx, cy + 1.dp.toPx()),
                                radius = contactR,
                            ),
                            radius = contactR,
                            center = Offset(cx, cy + 1.dp.toPx()),
                        )
                    }
                    .background(colors.bg, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(avatarInner)
                        .clip(CircleShape)
                        .background(coralGradient),
                    contentAlignment = Alignment.Center,
                ) {
                    // Default fallback: a canvas-tone face glyph on
                    // the coral disc. Used directly when there's no
                    // profile photo AND as the loading/error slot
                    // for SubcomposeAsyncImage below — a stale URI
                    // pointing at a deleted file would otherwise
                    // leave the disc blank.
                    //
                    // Earlier passes rendered the user's first
                    // initial here (Canvas + TextMeasurer to dodge
                    // metric-vs-optical centering quirks), but the
                    // person glyph reads more universally and avoids
                    // the whole "what if the user's name starts
                    // with a descender / non-Latin / emoji" question.
                    val fallback: @Composable () -> Unit = {
                        Box(
                            modifier = Modifier.size(avatarImageSize),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector        = Icons.Outlined.Face,
                                contentDescription = "Open profile menu",
                                // Tinted to the canvas tone (`colors.bg`)
                                // so the glyph echoes the outer ring and
                                // reads as cream-on-coral rather than
                                // pure white-on-coral — softer, warmer,
                                // and ties the avatar's two cream-toned
                                // surfaces (ring + glyph) together.
                                tint               = colors.bg,
                                modifier           = Modifier.fillMaxSize(),
                            )
                        }
                    }

                    if (profilePhotoUri.isNotEmpty()) {
                        // SubcomposeAsyncImage (not AsyncImage) so a
                        // stale URI / missing file falls through to
                        // the Face icon fallback instead of leaving
                        // the coral disc empty. profilePhotoUri
                        // persists in SharedPreferences across
                        // reinstalls, so pointing at a vanished file
                        // is a real path.
                        SubcomposeAsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(Uri.parse(profilePhotoUri))
                                // Cache disabled because the user's
                                // photo is overwritten in-place at the
                                // same path — without this, a fresh
                                // pick would keep serving the stale
                                // bitmap. The rendered photo is small
                                // (32dp), so the perf cost is nil.
                                .memoryCachePolicy(CachePolicy.DISABLED)
                                .diskCachePolicy(CachePolicy.DISABLED)
                                .crossfade(true)
                                .build(),
                            contentDescription = "Open profile menu",
                            contentScale       = ContentScale.Crop,
                            modifier           = Modifier
                                .size(avatarImageSize)
                                .clip(CircleShape),
                            loading = { fallback() },
                            error   = { fallback() },
                        )
                    } else {
                        fallback()
                    }
                }
            }
        }
        Spacer(Modifier.size(QuickInkSpacing.s3))
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
        }
    }
}

// MARK: - Sustainability hero

/**
 * Hero card under the greeting that frames QuickInk as a paper-saving
 * tool. Shows the user's lifetime digitised page count and translates
 * it into a composite "Tree points" impact score plus water spared.
 * Static leaf-green palette (independent of the user's accent picker)
 * so the eco message reads the same regardless of whether they've
 * picked Coral or Leaf Yellow for everything else.
 *
 * Tree-points model — we deliberately do *not* show a flat
 * `pages / 8333` tree count any more. A single divisor flatters
 * casual users (every scan rounds to "0.00 trees") and undersells
 * power users (one tree feels small even though the lifecycle impact
 * is huge). Instead we blend five independent paper-LCA factors into
 * one integer score:
 *
 *   1. Sheet engagement   — flat per-page reward; each capture has to
 *                           feel like it moved the needle.
 *   2. Tree-equivalent    — pages → fractional mature pine using the
 *                           conventional 8,333 sheets/tree, then
 *                           de-rated by the typical ~17% pulp yield
 *                           (only ~1/6 of a tree's biomass actually
 *                           becomes office paper). Heavy weight so a
 *                           whole-tree milestone reads as a real jump.
 *   3. Water spared       — ~10 L of process water per A4 sheet.
 *   4. CO₂ avoided        — ~4.6 g CO₂e per sheet, cradle-to-grave.
 *   5. Energy spared      — ~50 Wh per sheet (mill + transport).
 *
 * A logarithmic engagement boost is layered on top so the curve still
 * rewards sustained use without being purely linear — each order of
 * magnitude of pages adds a fixed bump rather than the score creeping
 * up at a constant per-page rate. Numbers are deliberately
 * conservative; the goal is a directional impact score, not a
 * precise lifecycle assessment.
 *
 * Tapping the card opens [SustainabilityBreakdownSheet], which
 * surfaces the per-component math behind the displayed score.
 */
@Composable
private fun SustainabilityHero(totalPages: Int) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current

    val ecoDeep = QuickInkColors.LeafGreenDeep
    val ecoBg   = QuickInkColors.LeafGreenBase.copy(alpha = 0.18f)
    val ecoBorder = QuickInkColors.LeafGreenBase.copy(alpha = 0.40f)

    val impact = remember(totalPages) { computeTreeImpact(totalPages) }
    var showBreakdown by remember { mutableStateOf(false) }

    // Refined-warm pass: points + water are their own right-aligned
    // data column to the right of the headline. We always render two
    // numeric labels (points, water) so the rhythm stays consistent —
    // no more "first tree on the way" prose case, the integer score
    // does the same job at a glance and reads as a real metric.
    val ecoPointsLabel =
        String.format(java.util.Locale.ROOT, "%,d Tree pts", impact.totalPoints)
    val title    = "By going digital"
    val headline = when {
        totalPages == 0 -> "Start saving paper"
        totalPages == 1 -> "1 page saved"
        else            -> "$totalPages pages saved"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(QuickInkRadius.lg))
            .background(ecoBg)
            .border(1.dp, ecoBorder, RoundedCornerShape(QuickInkRadius.lg))
            // Tap to open the score-breakdown sheet. Always tappable
            // — the breakdown also serves as the explainer for the
            // empty-state ("here's how the score will work once you
            // start scanning").
            .clickable { showBreakdown = true }
            .padding(QuickInkSpacing.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(ecoDeep),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector        = Icons.Filled.Eco,
                contentDescription = null,
                tint               = colors.textOnAccent,
                modifier           = Modifier.size(26.dp),
            )
        }
        Spacer(Modifier.size(QuickInkSpacing.s3))
        Column(modifier = Modifier.weight(1f)) {
            // Title now sits on the leaf-green deep tone (was a flat
            // muted gray) so the whole card reads as a coherent green
            // family rather than gray-on-green.
            Text(text = title, style = type.meta, color = ecoDeep)
            // Sustainability Campaigns sit on the dedicated
            // [editorial] token (Roboto Serif Bold) per the type
            // spec — the eco card is the canonical campaign surface.
            // Sans [heading] would fight the editorial register here.
            Text(text = headline, style = type.editorial, color = colors.ink)
            // Empty-state prompt only — once there's any saved page
            // the right-hand stat column carries the secondary line.
            if (totalPages == 0) {
                Spacer(Modifier.size(QuickInkSpacing.s1))
                Text(
                    text  = "Tap the ⚡ to capture your first page",
                    style = type.caption,
                    color = ecoDeep,
                )
            }
        }
        if (totalPages > 0) {
            Spacer(Modifier.size(QuickInkSpacing.s2))
            Column(horizontalAlignment = Alignment.End) {
                Text(text = ecoPointsLabel, style = type.meta, color = ecoDeep)
                Text(text = "${impact.waterLiters} L water", style = type.meta, color = ecoDeep)
            }
        }
    }

    if (showBreakdown) {
        SustainabilityBreakdownSheet(
            impact    = impact,
            onDismiss = { showBreakdown = false },
        )
    }
}

// MARK: - Sustainability impact model

/**
 * Snapshot of one user's lifetime impact, expressed both as raw LCA
 * outputs (sheets / trees-equivalent / water / CO₂ / energy) and as
 * the per-component point contributions that sum to [totalPoints].
 *
 * The hero card and the breakdown bottom sheet both read from this
 * struct so the displayed score and the per-row math can never drift
 * out of sync.
 */
private data class TreeImpact(
    val pages: Int,
    val pulpYield: Double,
    val treeFraction: Double,
    val waterLiters: Int,
    val co2Grams: Double,
    val energyWh: Double,
    val pSheets: Double,
    val pTrees: Double,
    val pWater: Double,
    val pCarbon: Double,
    val pEnergy: Double,
    val pStreak: Double,
    val totalPoints: Int,
)

/**
 * Build a [TreeImpact] for the given lifetime page count. See the
 * [SustainabilityHero] KDoc for the rationale behind each factor and
 * weight.
 */
private fun computeTreeImpact(totalPages: Int): TreeImpact {
    val sheets       = totalPages.toDouble()
    val pulpYield    = 0.17                          // tree biomass → paper
    val treeFraction = (sheets / 8333.0) * pulpYield
    val waterLiters  = totalPages * 10               // kept as Int for the L label
    val co2Grams     = sheets * 4.6
    val energyWh     = sheets * 50.0

    // Component weights are calibrated so a single tree-milestone
    // (~8,333 pages) lands in the low six figures, while a single
    // captured page still scores in the low hundreds — enough to feel
    // rewarding without making the empty-state-to-first-scan jump
    // feel cheap.
    val pSheets = sheets       * 7.5
    val pTrees  = treeFraction * 12_000.0
    val pWater  = waterLiters  * 0.6
    val pCarbon = co2Grams     * 1.2
    val pEnergy = energyWh     * 0.4
    val pStreak = if (sheets > 0) ln(sheets + 1.0) * 180.0 else 0.0

    val total = (pSheets + pTrees + pWater + pCarbon + pEnergy + pStreak)
        .roundToInt()
        .coerceAtLeast(0)

    return TreeImpact(
        pages        = totalPages,
        pulpYield    = pulpYield,
        treeFraction = treeFraction,
        waterLiters  = waterLiters,
        co2Grams     = co2Grams,
        energyWh     = energyWh,
        pSheets      = pSheets,
        pTrees       = pTrees,
        pWater       = pWater,
        pCarbon      = pCarbon,
        pEnergy      = pEnergy,
        pStreak      = pStreak,
        totalPoints  = total,
    )
}

// MARK: - Sustainability breakdown sheet

/**
 * Bottom sheet that opens when the user taps [SustainabilityHero].
 * Lays out the Tree-points calculation in two stacked sections:
 *
 *   1. **What we measured** — the raw lifecycle outputs (sheets,
 *      tree-equivalent, water, CO₂, energy) with the per-sheet
 *      conversion factor as a caption underneath each row.
 *   2. **How that scores** — the per-component point contributions
 *      with their weight rates as a caption, totalled at the bottom.
 *
 * The total at the top of the sheet is the same integer the hero card
 * shows; both reads come from the same [TreeImpact] snapshot so the
 * displayed score and the breakdown can never disagree.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SustainabilityBreakdownSheet(
    impact: TreeImpact,
    onDismiss: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val ecoDeep = QuickInkColors.LeafGreenDeep
    val ecoBg   = QuickInkColors.LeafGreenBase.copy(alpha = 0.18f)

    val locale = java.util.Locale.ROOT
    val totalLabel = String.format(locale, "%,d Tree pts", impact.totalPoints)
    val pagesLabel = when (impact.pages) {
        0    -> "No pages saved yet"
        1    -> "From 1 page saved"
        else -> String.format(locale, "From %,d pages saved", impact.pages)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        containerColor   = colors.bg,
        dragHandle = {
            Box(modifier = Modifier.padding(top = QuickInkSpacing.s2, bottom = QuickInkSpacing.s3)) {
                Box(
                    modifier = Modifier
                        .size(width = 40.dp, height = 4.dp)
                        .clip(RoundedCornerShape(QuickInkRadius.pill))
                        .background(colors.border),
                )
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(
                    start  = QuickInkSpacing.s5,
                    end    = QuickInkSpacing.s5,
                    bottom = QuickInkSpacing.s5,
                ),
            verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3),
        ) {
            // Header row — title + close affordance.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text  = "How your Tree score works",
                        style = type.heading,
                        color = colors.ink,
                    )
                    Text(
                        text  = "Five LCA factors plus an engagement boost.",
                        style = type.meta,
                        color = colors.inkSoft,
                    )
                }
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(colors.borderSoft)
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector        = Icons.Filled.Close,
                        contentDescription = "Close",
                        tint               = colors.inkSoft,
                        modifier           = Modifier.size(14.dp),
                    )
                }
            }

            // Hero total — mirrors the card so the user sees the same
            // number they tapped through.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(QuickInkRadius.lg))
                    .background(ecoBg)
                    .padding(QuickInkSpacing.s4),
            ) {
                Text(text = pagesLabel, style = type.meta, color = ecoDeep)
                Text(text = totalLabel, style = type.editorial, color = colors.ink)
            }

            // Section 1 — raw lifecycle outputs.
            BreakdownSectionHeader(label = "What we measured")
            BreakdownRow(
                label   = "Sheets engaged",
                value   = String.format(locale, "%,d pages", impact.pages),
                caption = "Lifetime captures across all notebooks",
            )
            BreakdownRow(
                label   = "Tree-equivalent",
                value   = String.format(locale, "%.4f trees", impact.treeFraction),
                caption = String.format(
                    locale,
                    "pages ÷ 8,333 × %.0f%% pulp yield",
                    impact.pulpYield * 100.0,
                ),
            )
            BreakdownRow(
                label   = "Water spared",
                value   = String.format(locale, "%,d L", impact.waterLiters),
                caption = "≈ 10 L of process water per A4 sheet",
            )
            BreakdownRow(
                label   = "CO₂ avoided",
                value   = formatGramsOrKg(impact.co2Grams),
                caption = "≈ 4.6 g CO₂e per sheet, cradle-to-grave",
            )
            BreakdownRow(
                label   = "Energy spared",
                value   = formatWhOrKWh(impact.energyWh),
                caption = "≈ 50 Wh per sheet (mill + transport)",
            )

            HorizontalDivider(color = colors.border, thickness = 1.dp)

            // Section 2 — point contributions.
            BreakdownSectionHeader(label = "How that scores")
            BreakdownRow(
                label   = "Sheet engagement",
                value   = pointsLabel(locale, impact.pSheets),
                caption = "+7.5 pts per page captured",
            )
            BreakdownRow(
                label   = "Tree milestone",
                value   = pointsLabel(locale, impact.pTrees),
                caption = "+12,000 pts per tree-equivalent saved",
            )
            BreakdownRow(
                label   = "Water",
                value   = pointsLabel(locale, impact.pWater),
                caption = "+0.6 pts per litre",
            )
            BreakdownRow(
                label   = "CO₂",
                value   = pointsLabel(locale, impact.pCarbon),
                caption = "+1.2 pts per gram avoided",
            )
            BreakdownRow(
                label   = "Energy",
                value   = pointsLabel(locale, impact.pEnergy),
                caption = "+0.4 pts per watt-hour",
            )
            BreakdownRow(
                label   = "Engagement boost",
                value   = pointsLabel(locale, impact.pStreak),
                caption = "ln(pages + 1) × 180 — rewards sustained use",
            )

            HorizontalDivider(color = colors.border, thickness = 1.dp)

            // Total row — matches the card.
            Row(
                modifier              = Modifier.fillMaxWidth(),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(text = "Total", style = type.label, color = colors.ink)
                Text(text = totalLabel, style = type.heading, color = ecoDeep)
            }

            Text(
                text  = "Numbers are deliberately conservative — directional " +
                        "impact, not a precise lifecycle assessment.",
                style = type.caption,
                color = colors.muted,
            )
            Spacer(Modifier.size(QuickInkSpacing.s2))
        }
    }
}

@Composable
private fun BreakdownSectionHeader(label: String) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    Text(
        text  = label.uppercase(java.util.Locale.ROOT),
        style = type.caption,
        color = colors.muted,
    )
}

@Composable
private fun BreakdownRow(label: String, value: String, caption: String) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    Row(
        modifier              = Modifier.fillMaxWidth(),
        verticalAlignment     = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label,   style = type.label,   color = colors.ink)
            Text(text = caption, style = type.caption, color = colors.muted)
        }
        Text(text = value, style = type.label, color = colors.ink)
    }
}

/** "+1,234 pts" — used for the per-component point contributions. */
private fun pointsLabel(locale: java.util.Locale, raw: Double): String =
    String.format(locale, "+%,d pts", raw.roundToInt().coerceAtLeast(0))

/** "812 g" under a kilo, "1.23 kg" once we cross the threshold. */
private fun formatGramsOrKg(grams: Double): String {
    val locale = java.util.Locale.ROOT
    return if (grams < 1_000.0) {
        String.format(locale, "%,d g", grams.roundToInt())
    } else {
        String.format(locale, "%.2f kg", grams / 1_000.0)
    }
}

/** "812 Wh" under a kilowatt-hour, "1.23 kWh" once we cross over. */
private fun formatWhOrKWh(wh: Double): String {
    val locale = java.util.Locale.ROOT
    return if (wh < 1_000.0) {
        String.format(locale, "%,d Wh", wh.roundToInt())
    } else {
        String.format(locale, "%.2f kWh", wh / 1_000.0)
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
            // Status pill text — sits on the sans Label token rather
            // than CardTitle (which is now serif Medium per the spec
            // and reserved for actual notebook/scan titles). "Recent
            // activity" is functional UI copy, not editorial.
            Text(text = "Recent activity", style = type.label, color = colors.ink)
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
            // Section heading uses the sans Heading token (with a
            // small upsize) rather than the serif Display token.
            // Productivity-app section headings should read as
            // functional, not editorial — the serif is reserved for
            // the home greeting name above.
            Text(
                text  = "Recents",
                style = type.heading,
                color = colors.ink,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text     = "View all →",
                style    = type.label.copy(fontSize = 13.sp),
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
        Text(
            text  = "Quick categories",
            style = type.heading,
            color = colors.ink,
        )
        Spacer(Modifier.size(QuickInkSpacing.s3))
        // 2-column grid sized to the live category count. Number of
        // rows grows with the user's library; LazyVGrid still isn't
        // worth it for the typical handful.
        sorted.chunked(2).forEachIndexed { i, pair ->
            if (i > 0) Spacer(Modifier.size(QuickInkSpacing.s2))
            Row(horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2)) {
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
                .size(32.dp)
                .clip(RoundedCornerShape(QuickInkRadius.sm))
                .background(colors.accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector        = icon,
                contentDescription = null,
                tint               = colors.accent,
                modifier           = Modifier.size(14.dp),
            )
        }
        Spacer(Modifier.size(QuickInkSpacing.s3))
        Column(modifier = Modifier.weight(1f)) {
            // Category tile name uses the Label token (Inter Medium
            // 14sp) rather than CardTitle (Inter SemiBold 14sp).
            // Category names are functional UI labels — Medium reads
            // lighter and more chip-like than SemiBold, which would
            // make the tiles fight the recent-rail thumbnails for
            // visual emphasis. (Originally also a width fix when
            // CardTitle was serif and "Meetings" truncated to
            // "Meetin..." — that's resolved now that both tokens
            // sit on Inter, but the weight distinction still earns
            // its keep.)
            Text(
                text     = name,
                style    = type.label,
                color    = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text  = if (count == 0) "No scans yet" else "$count scan${if (count == 1) "" else "s"}",
                style = type.caption,
                color = colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector        = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint               = colors.muted,
            modifier           = Modifier.size(16.dp),
        )
    }
}
