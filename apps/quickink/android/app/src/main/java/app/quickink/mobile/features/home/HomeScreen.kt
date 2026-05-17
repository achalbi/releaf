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

import app.quickink.mobile.features.settings.SettingsPreferences
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.CenterFocusWeak
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Park
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.net.Uri
import app.quickink.mobile.QuickInkApp
import app.quickink.mobile.R
import app.quickink.mobile.data.capture.CaptureEntity
import app.quickink.mobile.data.capture.displayTitle
import app.quickink.mobile.data.location.LocationEntity
import app.quickink.mobile.data.location.LocationRepository
import app.quickink.mobile.data.person.PersonEntity
import app.quickink.mobile.data.person.PersonRepository
import app.quickink.mobile.data.tag.TagEntity
import app.quickink.mobile.features.workspace.LocationEditorDialog
import app.quickink.mobile.features.workspace.PersonEditorDialog
import app.quickink.mobile.data.sync.QuickInkSyncScheduler
import app.quickink.mobile.data.sync.QuickInkSyncWorker
import app.quickink.mobile.features.scan.PaperSize
import app.quickink.mobile.features.scan.ScanFlowController
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkColors
import app.quickink.mobile.ui.theme.QuickInkFonts
import app.quickink.mobile.ui.theme.QuickInkRadius
import app.quickink.mobile.ui.theme.QuickInkSpacing
import app.quickink.mobile.ui.theme.quickInkLinedPaper
import app.releaf.mobile.data.notepad.NotepadEntry
import app.releaf.mobile.data.sync.SyncStateKeys
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalTime

@Composable
fun HomeScreen(
    controller: ScanFlowController,
    userId: String,
    onOpenNotes: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSearch: (() -> Unit)? = null,
    onOpenEntry: ((String) -> Unit)? = null,
    onOpenScan: ((String) -> Unit)? = null,
    /// Routes to the new Profile editor (photo / phone / punchline).
    /// Picked from the avatar dropdown menu alongside "Sign out".
    /// Wired at QuickInkRoot.
    onOpenProfile: (() -> Unit)? = null,
    /// Pushes the standalone Calendar screen — panchanga + Indian
    /// holidays + moon phases + a coral dot per scan day. Reached via
    /// the small calendar icon in the home header. Wired at
    /// QuickInkRoot to `navController.navigate(Routes.CALENDAR)`.
    onOpenCalendar: (() -> Unit)? = null,
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
    /// User's location, threaded down from `DaylightLocationStore`
    /// in `QuickInkRoot` so the `DaylightHero` card resolves sunrise
    /// and sunset against the user's actual position. Null for
    /// either falls back to Mysuru — the panchanga anchor.
    daylightLatitude: Double? = null,
    daylightLongitude: Double? = null,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    val context = LocalContext.current
    val app = context.applicationContext as QuickInkApp

    // Slide-in profile drawer triggered by the avatar tap — mirror
    // of Releaf's home drawer (apps/releaf/android/.../HomeScreen.kt).
    // Replaces the previous Material `DropdownMenu` so the avatar
    // action surface is visually consistent across the two sibling
    // apps.
    var showProfileDrawer by remember { mutableStateOf(false) }

    // Live recent-captures rail — surfaces the actual scanned-page
    // preview JPEGs, newest first. Tap on a thumb routes to
    // `ScanDetailScreen` (full preview + OCR-on-demand). Capped at
    // 30 rows by the DAO so the rail stays cheap as captures pile
    // up. Mirror of iOS's `CaptureListViewModel`-backed `recentRail`.
    val captureDao = remember(app) { app.database.captureDao() }
    val recentCaptures by remember(userId, captureDao) {
        captureDao.observeRecent(userId, limit = 30)
    }.collectAsState(initial = emptyList())

    // Lifetime page totals grouped by `paper_size` — drives the
    // sustainability hero, which weights each size bucket independently
    // (card +4, A4 +2, smaller +1). GROUP BY collapses unused buckets,
    // so a fresh-install library reads back as an empty list and the
    // hero renders its empty-state copy.
    val pagesBySizeRows by remember(userId, captureDao) {
        captureDao.observePagesBySize(userId)
    }.collectAsState(initial = emptyList())
    val pagesBySize: Map<String, Int> = remember(pagesBySizeRows) {
        pagesBySizeRows.associate { it.paperSize to it.pages }
    }

    // Per-capture primary-tag-name lookup. The pre-A.3c
    // `captures.category` field carried this on the row; post-drop
    // we derive it from `capture_tags` joined to `tags` and pick
    // the earliest-attached active tag per capture. Drives the
    // recent-rail title cascade (`Capture.displayTitle(
    // primaryTagName)`).
    val captureTagDao = remember(app) { app.database.captureTagDao() }
    val primaryTagRows by remember(userId, captureTagDao) {
        captureTagDao.observePrimaryTagNames(userId)
    }.collectAsState(initial = emptyList())
    val primaryTagByCapture: Map<String, String> = remember(primaryTagRows) {
        primaryTagRows.associate { it.captureId to it.tagName }
    }

    // Locations rail — user-defined places ("Home", "Work", …) the
    // user can attach to documents. Seeded with two defaults on
    // first launch via [LocationRepository.seedDefaultsIfEmpty] from
    // QuickInkRoot; this observation reflects the live list.
    val locationDao  = remember(app) { app.database.locationDao() }
    val locationRepo = remember(locationDao) { LocationRepository(locationDao) }
    val locations by remember(userId, locationDao) {
        locationRepo.observe(userId)
    }.collectAsState(initial = emptyList())
    val locationScope = rememberCoroutineScope()
    var showLocationsSheet by remember { mutableStateOf(false) }
    // Editor dialog state — `editorOpen` toggles the dialog,
    // `editorExisting` chooses create (null) vs edit (a row).
    var editorOpen by remember { mutableStateOf(false) }
    var editorExisting by remember { mutableStateOf<LocationEntity?>(null) }

    // People rail — mirror of the Locations rail. Seeded with "Me"
    // on first launch via [PersonRepository.seedDefaultsIfEmpty].
    val personDao  = remember(app) { app.database.personDao() }
    val personRepo = remember(personDao) { PersonRepository(personDao) }
    val people by remember(userId, personDao) {
        personRepo.observe(userId)
    }.collectAsState(initial = emptyList())
    var showPeopleSheet by remember { mutableStateOf(false) }
    var personEditorOpen by remember { mutableStateOf(false) }
    var personEditorExisting by remember { mutableStateOf<PersonEntity?>(null) }

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

    Box(modifier = Modifier.fillMaxSize().background(colors.bg)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    start  = QuickInkSpacing.s5,
                    end    = QuickInkSpacing.s5,
                    top    = statusBarTop + 6.dp,
                    // Reserve space behind nav bar (~80dp) plus a
                    // little extra (~40dp) so the sync pill at the
                    // end of the scroll content doesn't bump into
                    // the bar when scrolled to the end.
                    bottom = 140.dp,
                ),
        ) {
            // Live-updating system date/time strip at top-right —
            // small temporal anchor in the space where the OS
            // status bar would be (hidden app-wide) and where the
            // daylight bar would be (suppressed on Home). 60 s tick
            // is enough; only the minute digit moves below the hour
            // scale. Reuses `nowMs` (already maintained for the
            // sync-tap-ack window) plus a top-level minute ticker.
            var clockNowMs by remember { mutableStateOf(System.currentTimeMillis()) }
            LaunchedEffect(Unit) {
                while (true) {
                    delay(60_000L)
                    clockNowMs = System.currentTimeMillis()
                }
            }
            val clockNow = remember(clockNowMs) {
                java.time.ZonedDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(clockNowMs),
                    java.time.ZoneId.systemDefault(),
                )
            }
            val statusDate = remember(clockNow) { formatHomeStatusDate(clockNow) }
            val statusTime = remember(clockNow) { formatHomeStatusTime(clockNow) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                Text(
                    text       = statusDate,
                    fontFamily = QuickInkFonts.ui,
                    fontSize   = 12.sp,
                    color      = colors.muted,
                )
                // Sun glyph moved out — now sits next to the
                // "Good morning" greeting below. The status row
                // shows just time on the right.
                Text(
                    text       = statusTime,
                    fontFamily = QuickInkFonts.ui,
                    fontSize   = 12.sp,
                    color      = colors.muted,
                )
            }
            Spacer(Modifier.size(QuickInkSpacing.s2))
            HomeHeader(
                displayName     = displayName,
                profilePhotoUri = profilePhotoUri,
                onTapAvatar     = { showProfileDrawer = true },
                onTapCalendar   = { onOpenCalendar?.invoke() },
            )
            Spacer(Modifier.size(QuickInkSpacing.s4))
            // Daylight hero — slim card showing today's sunrise and
            // sunset times with a now-marker meter beneath. Sits
            // directly above the sustainability hero per the home
            // layout brief; both cards share an editorial, warm-
            // tinted register so the pair reads as one block of
            // ambient context. Times come from `sunTimesFor()` —
            // the same commons-suncalc calculator the Calendar's
            // Rahu Kala window uses, anchored at Mysuru. Mirror of
            // iOS's `DaylightHero()` call site in `HomeScreen.swift`.
            DaylightHero(
                latitude  = daylightLatitude,
                longitude = daylightLongitude,
            )
            Spacer(Modifier.size(QuickInkSpacing.s3))
            SustainabilityHero(pagesBySize = pagesBySize)
            // Mirror the displayed Tree-points value into a shared
            // SharedPreferences key so the next cold launch's
            // cinematic counter (`QuickInkLaunchAnimation`) ticks up
            // to the user's actual current balance instead of the
            // hardcoded preview default. The splash runs at
            // MainActivity onCreate, before any DAO flow can resolve,
            // so a cached pref is the only way to surface a real
            // number on the launch screen. Observed on the per-size
            // dict so a card-only scan (which doesn't change the
            // total page count proportionally to its 4× weight)
            // still flushes the cache.
            LaunchedEffect(pagesBySize) {
                val pts = computeTreeImpact(pagesBySize).totalPoints
                SettingsPreferences.writeCachedTreePoints(context, pts)
            }
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
                captures            = recentCaptures.take(6),
                primaryTagByCapture = primaryTagByCapture,
                onAllNotes          = onOpenNotes,
                onOpenScan          = onOpenScan,
            )
            Spacer(Modifier.size(QuickInkSpacing.s4))
            LocationsRail(
                locations  = locations,
                onAddTap   = { showLocationsSheet = true },
                onChipTap  = { showLocationsSheet = true },
            )
            Spacer(Modifier.size(QuickInkSpacing.s4))
            PeopleRail(
                people    = people,
                onAddTap  = { showPeopleSheet = true },
                onChipTap = { showPeopleSheet = true },
            )
            Spacer(Modifier.size(QuickInkSpacing.s4))
            RecentActivityPill(
                pendingReviewCount = pendingCount,
                pendingSyncCount   = localDirtyCount,
            )
        }

        // Bottom sheet — list / edit / delete locations. Opens from
        // the rail's "+" chip and from tapping an existing chip; the
        // sheet itself routes Add and row-tap into the shared
        // [LocationEditorDialog] (sibling layer below).
        if (showLocationsSheet) {
            LocationsManageSheet(
                locations = locations,
                onDismiss = { showLocationsSheet = false },
                onAdd     = {
                    editorExisting = null
                    editorOpen     = true
                },
                onEditRow = { loc ->
                    editorExisting = loc
                    editorOpen     = true
                },
                onDelete  = { id ->
                    locationScope.launch { locationRepo.softDelete(id) }
                },
            )
        }

        // Editor dialog — create or edit a single location row.
        // Owns its own DAO writes; we just supply the userId + the
        // row being edited (null for create).
        if (editorOpen) {
            LocationEditorDialog(
                userId   = userId,
                existing = editorExisting,
                onDismiss = { editorOpen = false },
                onSaved   = { editorOpen = false },
            )
        }

        // People manage sheet + editor — same shape as the Locations
        // pair above. Add opens the editor in create mode; tapping
        // a row opens it in edit mode.
        if (showPeopleSheet) {
            PeopleManageSheet(
                people    = people,
                onDismiss = { showPeopleSheet = false },
                onAdd     = {
                    personEditorExisting = null
                    personEditorOpen     = true
                },
                onEditRow = { person ->
                    personEditorExisting = person
                    personEditorOpen     = true
                },
                onDelete  = { id ->
                    locationScope.launch { personRepo.softDelete(id) }
                },
            )
        }

        if (personEditorOpen) {
            PersonEditorDialog(
                userId    = userId,
                existing  = personEditorExisting,
                onDismiss = { personEditorOpen = false },
                onSaved   = { personEditorOpen = false },
            )
        }

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
    onTapCalendar: () -> Unit,
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
    // True when the greeting reads "Good evening" — drives the moon
    // glyph (vs. sun) in the header Row. Same window as the `else`
    // branch above: hour >= 18 or hour < 5.
    val isEvening = remember {
        val h = LocalTime.now().hour
        h < 5 || h >= 18
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
            // Daylight cue sits before the greeting — sun during the
            // day, moon-with-stars after 18:00 (and before 05:00).
            // Replaces the glyph that used to live in the top status
            // row.
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector        = if (isEvening) Icons.Filled.NightsStay
                                          else Icons.Filled.WbSunny,
                    contentDescription = null,
                    tint               = if (isEvening) colors.inkSoft
                                          else QuickInkColors.LeafYellowDeep,
                    modifier           = Modifier.size(17.dp),
                )
                // Deep taupe (#5F5245) — the `InkSoft` light-mode
                // value, pinned as a fixed tone for the greeting in
                // both light and dark.
                Text(text = greeting, style = type.body, color = Color(0xFF5F5245))
            }
            Spacer(Modifier.size(QuickInkSpacing.s1))
            Text(
                text     = resolvedName,
                style    = type.display,
                color    = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // Top-right calendar tile — squared surface card with a
        // coral calendar glyph and a small "Calendar" label. Reads
        // as a destination card (vs the avatar's identity disc) and
        // matches the home mockup's tile-on-tile rhythm. Tap pushes
        // the standalone Calendar screen (panchanga + Indian holidays
        // + per-day capture dots).
        val calendarInteraction = remember { MutableInteractionSource() }
        val calendarShape = RoundedCornerShape(QuickInkRadius.md)
        Column(
            modifier = Modifier
                .size(60.dp)
                // Shadow renders before clip/background so it falls
                // outside the tile rather than getting masked away.
                // Soft 4dp lift to match the home mockup's raised
                // calendar card.
                .shadow(
                    elevation = 4.dp,
                    shape     = calendarShape,
                    clip      = false,
                )
                .clip(calendarShape)
                .background(colors.surface)
                .border(1.dp, colors.border, calendarShape)
                .clickable(
                    interactionSource = calendarInteraction,
                    indication        = null,
                    onClick           = onTapCalendar,
                )
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector        = Icons.Outlined.CalendarMonth,
                contentDescription = "Open calendar",
                tint               = colors.accent,
                modifier           = Modifier.size(24.dp),
            )
            Spacer(Modifier.size(2.dp))
            Text(
                text  = "Calendar",
                style = type.caption,
                color = colors.ink,
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
 *   1. Sheet engagement   — per-page reward, weighted by page size
 *                           (card +4, A4 +2, smaller +1) so a
 *                           bulk-print-saving card scan beats a
 *                           one-off small page.
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
 * Numbers are deliberately conservative; the goal is a directional
 * impact score, not a precise lifecycle assessment.
 *
 * Tapping the card opens [SustainabilityBreakdownSheet], which
 * surfaces the per-component math behind the displayed score.
 */
@Composable
private fun SustainabilityHero(pagesBySize: Map<String, Int>) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current

    val ecoDeep = QuickInkColors.LeafGreenDeep
    val ecoBg   = QuickInkColors.LeafGreenBase.copy(alpha = 0.18f)
    val ecoBorder = QuickInkColors.LeafGreenBase.copy(alpha = 0.40f)

    val impact = remember(pagesBySize) { computeTreeImpact(pagesBySize) }
    val totalPages = impact.pages
    var showBreakdown by remember { mutableStateOf(false) }

    val locale = java.util.Locale.ROOT
    val title    = "By going digital"
    val headline = when {
        totalPages == 0 -> "Start saving paper"
        totalPages == 1 -> "1 page saved"
        else            -> "$totalPages pages saved"
    }
    val treePointsValue = String.format(locale, "%,d", impact.totalPoints)
    val waterValue      = String.format(locale, "%,d L", impact.waterLiters)

    // Water-drop stat sits on a steady blue regardless of the user's
    // accent — same posture as `ecoDeep` for the Tree-points icon, so
    // both badges carry semantic colour (green = pages saved, blue =
    // water spared) rather than the warm coral that signals primary
    // app actions elsewhere.
    val waterDeep = Color(0xFF1F8FCB)
    val waterBg   = Color(0xFFE3F2FB)

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
            .padding(14.dp),
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
            Text(
                text       = headline,
                fontFamily = QuickInkFonts.ui,
                fontWeight = FontWeight.Thin,
                fontSize   = 12.sp,
                color      = colors.ink,
            )
            // Empty-state prompt only — once there's any saved page
            // the right-hand stat badges carry the secondary line.
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
            // Subtle divider between the headline copy and the
            // numeric stats — pulls the eye into the badge cluster
            // and breaks the card into "story" + "numbers" zones
            // without a heavier rule.
            Spacer(Modifier.size(QuickInkSpacing.s3))
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(56.dp)
                    .background(ecoBorder),
            )
            Spacer(Modifier.size(QuickInkSpacing.s3))
            SustainabilityStat(
                badgeBg   = ecoBg,
                badgeBorder = ecoBorder,
                iconTint  = ecoDeep,
                icon      = Icons.Filled.Park,
                value     = treePointsValue,
                caption   = "Tree pts",
                inkColor  = colors.ink,
                captionColor = colors.inkSoft,
                valueStyle   = type.heading,
                captionStyle = type.caption,
            )
            Spacer(Modifier.size(QuickInkSpacing.s3))
            SustainabilityStat(
                badgeBg   = waterBg,
                badgeBorder = waterDeep.copy(alpha = 0.40f),
                iconTint  = waterDeep,
                icon      = Icons.Filled.WaterDrop,
                value     = waterValue,
                caption   = "Water saved",
                inkColor  = colors.ink,
                captionColor = colors.inkSoft,
                valueStyle   = type.heading,
                captionStyle = type.caption,
            )
        }
    }

    if (showBreakdown) {
        SustainabilityBreakdownSheet(
            impact    = impact,
            onDismiss = { showBreakdown = false },
        )
    }
}

/**
 * One stat column inside the sustainability card. Renders as a
 * small circular icon badge stacked above a bold value and a muted
 * caption. Centred on the badge so two adjacent stats sit on the
 * same vertical axis even when the numeric values have different
 * widths.
 */
@Composable
private fun SustainabilityStat(
    badgeBg: Color,
    badgeBorder: Color,
    iconTint: Color,
    icon: ImageVector,
    value: String,
    caption: String,
    inkColor: Color,
    captionColor: Color,
    valueStyle: androidx.compose.ui.text.TextStyle,
    captionStyle: androidx.compose.ui.text.TextStyle,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(badgeBg)
                .border(1.dp, badgeBorder, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector        = icon,
                contentDescription = null,
                tint               = iconTint,
                modifier           = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.size(QuickInkSpacing.s1))
        Text(text = value,   style = valueStyle,   color = inkColor)
        Text(text = caption, style = captionStyle, color = captionColor)
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
    val cardPages: Int,
    val a4Pages: Int,
    val a5Pages: Int,
    val letterPages: Int,
    val customPages: Int,
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
    val totalPoints: Int,
)

/**
 * Build a [TreeImpact] from a per-size page count breakdown. The
 * `Sheet engagement` factor weights each bucket independently:
 *   - card        → +0.4 pts/page (bonus for digitising what's
 *                                  normally printed in bulk)
 *   - a4 / letter → +0.2 pts/page (≈ same physical paper area)
 *   - a5          → +0.1 pts/page (half the paper of A4)
 *   - custom      → +0.2 pts/page (treated as A4-equivalent for
 *                                  scoring; we don't know the true
 *                                  size by definition)
 *
 * The other four factors scale with total lifetime pages — they
 * capture pulp / water / CO₂ / energy per sheet, which is roughly
 * size-agnostic at the precision the score is meant to convey.
 *
 * Legacy `"small"` raw values (from the v13 schema's reserved slot)
 * are folded into the `a5` bucket via [PaperSize.fromRaw] before
 * lookup, so no historical pages get dropped.
 */
private fun computeTreeImpact(pagesBySize: Map<String, Int>): TreeImpact {
    // Re-bucket by PaperSize.fromRaw so legacy "small" rows fold into
    // .A5 and unknown values fall through to .A4 rather than getting
    // silently dropped on a typo. Collisions sum.
    val byEnum: Map<PaperSize, Int> = pagesBySize
        .entries
        .groupingBy { PaperSize.fromRaw(it.key) }
        .fold(0) { acc, entry -> acc + entry.value }

    val cardPages   = byEnum[PaperSize.Card]   ?: 0
    val a4Pages     = byEnum[PaperSize.A4]     ?: 0
    val a5Pages     = byEnum[PaperSize.A5]     ?: 0
    val letterPages = byEnum[PaperSize.Letter] ?: 0
    val customPages = byEnum[PaperSize.Custom] ?: 0
    val totalPages  = cardPages + a4Pages + a5Pages + letterPages + customPages

    val sheets       = totalPages.toDouble()
    val pulpYield    = 0.17                          // tree biomass → paper
    val treeFraction = (sheets / 8333.0) * pulpYield
    val waterLiters  = totalPages * 10               // kept as Int for the L label
    val co2Grams     = sheets * 4.6
    val energyWh     = sheets * 50.0

    // Size-weighted sheet engagement. Card +0.4 / A4 +0.2 / A5 +0.1 /
    // Letter +0.2 / Custom +0.2. Letter ≈ A4 area, so same weight.
    // Custom defaults to A4-equivalent — we don't know its true
    // size by definition.
    val pSheets = cardPages   * 0.4 +
                  a4Pages     * 0.2 +
                  a5Pages     * 0.1 +
                  letterPages * 0.2 +
                  customPages * 0.2
    val pTrees  = treeFraction * 1_200.0
    val pWater  = waterLiters  * 0.06
    val pCarbon = co2Grams     * 0.12
    val pEnergy = energyWh     * 0.04

    val total = (pSheets + pTrees + pWater + pCarbon + pEnergy)
        .roundToInt()
        .coerceAtLeast(0)

    return TreeImpact(
        pages        = totalPages,
        cardPages    = cardPages,
        a4Pages      = a4Pages,
        a5Pages      = a5Pages,
        letterPages  = letterPages,
        customPages  = customPages,
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
        totalPoints  = total,
    )
}

// MARK: - Sustainability breakdown sheet

/**
 * Walk the view-tree ancestry looking for a [DialogWindowProvider].
 * Material3 `ModalBottomSheet` (1.3.x) renders inside a private
 * `ModalBottomSheetDialogLayout` (an `AbstractComposeView`) that
 * implements this interface and exposes the dialog's window. Some
 * Compose versions put the provider on the AbstractComposeView
 * itself, others on a parent — so we walk the chain rather than
 * casting `view.parent` directly. Returns `null` when called
 * outside a Compose dialog (e.g., a preview), in which case the
 * caller no-ops.
 */
private fun findDialogWindow(start: View): Window? {
    var node: Any? = start
    while (node != null) {
        if (node is DialogWindowProvider) return node.window
        node = (node as? View)?.parent
    }
    return null
}

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

    // Hide the system status bar for the duration of this composition
    // so the modal reads as a clean full-bleed surface rather than
    // sitting under the system clock / battery strip.
    //
    // Critical: ModalBottomSheet (Material3 1.3.x) renders inside a
    // private `ComponentDialog` which has its OWN window — separate
    // from the host Activity's. System-bar visibility is determined
    // by the topmost focused window, which is the dialog when the
    // modal is open. Applying immersive flags to the Activity's
    // window does nothing here; we have to reach the dialog's
    // window and apply on its `WindowInsetsController`.
    //
    // Two layers, both on the dialog window:
    //   1. Modern AndroidX `WindowInsetsControllerCompat.hide(
    //      statusBars())` — works on stock Android / Pixel / most
    //      OEMs.
    //   2. Legacy `WindowManager.LayoutParams.FLAG_FULLSCREEN` —
    //      MIUI / HyperOS and some ColorOS builds silently ignore
    //      the modern API and only honor the older window flag.
    //
    // [findDialogWindow] walks the view-tree ancestry looking for
    // `DialogWindowProvider` — Material3's
    // `ModalBottomSheetDialogLayout` implements that interface,
    // exposing the dialog's window. Returns `null` outside a Compose
    // dialog (e.g., a preview), in which case the effect no-ops.
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = findDialogWindow(view)
        if (window != null) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = WindowInsetsControllerCompat(window, view)
            controller.hide(WindowInsetsCompat.Type.statusBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }
        onDispose {
            // No explicit show() — the dialog window is being torn
            // down anyway, and the activity window's status bar
            // visibility is unaffected since we never touched it.
            window?.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }
    }

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
                        text  = "Five LCA factors. Per-page weight scales with page size.",
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
            // Per-size breakdown of pages captured. Each row only
            // renders when its bucket is non-empty so a brand-new
            // library doesn't read as three "0 pages" lines. Falls
            // back to a single "Sheets engaged" row when no bucket
            // has any pages yet — preserves the previous empty-state
            // copy.
            if (impact.pages == 0) {
                BreakdownRow(
                    label   = "Sheets engaged",
                    value   = "0 pages",
                    caption = "Lifetime captures across all notebooks",
                )
            } else {
                if (impact.cardPages > 0) {
                    BreakdownRow(
                        label   = "Business cards",
                        value   = String.format(locale, "%,d cards", impact.cardPages),
                        caption = "Each card saves a bulk print run",
                    )
                }
                if (impact.a4Pages > 0) {
                    BreakdownRow(
                        label   = "A4 documents",
                        value   = String.format(locale, "%,d pages", impact.a4Pages),
                        caption = "Standard A4 captures",
                    )
                }
                if (impact.a5Pages > 0) {
                    BreakdownRow(
                        label   = "A5 documents",
                        value   = String.format(locale, "%,d pages", impact.a5Pages),
                        caption = "Half the paper of A4",
                    )
                }
                if (impact.letterPages > 0) {
                    BreakdownRow(
                        label   = "Letter documents",
                        value   = String.format(locale, "%,d pages", impact.letterPages),
                        caption = "US Letter ≈ A4 area",
                    )
                }
                if (impact.customPages > 0) {
                    BreakdownRow(
                        label   = "Other documents",
                        value   = String.format(locale, "%,d pages", impact.customPages),
                        caption = "Non-standard sizes, scored as A4",
                    )
                }
            }
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
                caption = "+0.4 / +0.2 / +0.1 pts per page (card / A4·Letter·Custom / A5)",
            )
            BreakdownRow(
                label   = "Tree milestone",
                value   = pointsLabel(locale, impact.pTrees),
                caption = "+1,200 pts per tree-equivalent saved",
            )
            BreakdownRow(
                label   = "Water",
                value   = pointsLabel(locale, impact.pWater),
                caption = "+6 pts per 100 L",
            )
            BreakdownRow(
                label   = "CO₂",
                value   = pointsLabel(locale, impact.pCarbon),
                caption = "+12 pts per 100 g avoided",
            )
            BreakdownRow(
                label   = "Energy",
                value   = pointsLabel(locale, impact.pEnergy),
                caption = "+4 pts per 100 Wh",
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

// region — Home status date/time strip

/// Locked to US English so the abbreviated weekday + AM/PM tokens
/// match the design spec regardless of device locale.
private val HomeStatusDateFormatter =
    java.time.format.DateTimeFormatter.ofPattern("EEE, d MMM yyyy", java.util.Locale.US)
private val HomeStatusTimeFormatter =
    java.time.format.DateTimeFormatter.ofPattern("hh:mm a", java.util.Locale.US)

/// "Sat, 16 May 2026" — left side of the home status strip.
private fun formatHomeStatusDate(now: java.time.ZonedDateTime): String =
    now.format(HomeStatusDateFormatter)

/// "07:20 AM" — right side of the home status strip. Uppercase per
/// the design spec; java.time's `a` token is already uppercase on
/// US locale, so no further casing is required.
private fun formatHomeStatusTime(now: java.time.ZonedDateTime): String =
    now.format(HomeStatusTimeFormatter)

// endregion

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
        else                   -> "You're all caught up!"
    }
    val isCaughtUp = pendingReviewCount == 0 && pendingSyncCount == 0

    // Steady leaf-green for the "all caught up" affirmation badge.
    // Independent of the user's accent picker so the checkmark
    // always reads as a confirmation cue rather than a coral CTA.
    val checkDeep = QuickInkColors.LeafGreenDeep
    val checkBg   = QuickInkColors.LeafGreenBase.copy(alpha = 0.22f)
    val checkBorder = QuickInkColors.LeafGreenBase.copy(alpha = 0.55f)

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
        if (isCaughtUp) {
            // Affirmation badge — green disc + check glyph on the
            // trailing edge so "all caught up" reads visually, not
            // just via the subtitle copy.
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(checkBg)
                    .border(1.dp, checkBorder, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector        = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint               = checkDeep,
                    modifier           = Modifier.size(18.dp),
                )
            }
        }
    }
}

// MARK: - Recent rail

@Composable
private fun RecentRail(
    captures: List<CaptureEntity>,
    primaryTagByCapture: Map<String, String>,
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
                        capture        = capture,
                        primaryTagName = primaryTagByCapture[capture.id],
                        onTap          = { onOpenScan?.invoke(capture.id) },
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
private fun RecentScanThumb(
    capture: CaptureEntity,
    primaryTagName: String?,
    onTap: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    val context = LocalContext.current
    val title = capture.displayTitle(primaryTagName)
    val displayDate = friendlyMonthDay(capture.createdAt)
    val cardShape  = RoundedCornerShape(QuickInkRadius.md)
    // Image fills the card's full width and top corners — flat at
    // the bottom so the title row below sits flush against it.
    val imageShape = RoundedCornerShape(
        topStart    = QuickInkRadius.md,
        topEnd      = QuickInkRadius.md,
        bottomStart = 0.dp,
        bottomEnd   = 0.dp,
    )
    val isImport   = capture.source == "import"
    val isPhoto    = capture.source == "photo"

    Column(
        modifier = Modifier
            .width(168.dp)
            .clip(cardShape)
            // Paper tone — same warm cream the scan-detail per-page
            // thumbnail strip sits on. Reads as a "paper note" surface
            // rather than the cooler pure-white `colors.surface`.
            .background(colors.paper2)
            .border(1.dp, colors.border, cardShape)
            .clickable(onClick = onTap),
    ) {
        // Image area — full bleed to the card edges horizontally and
        // at the top; flat bottom so the title row sits flush against
        // it. Badges align to this Box's corners.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(132.dp)
                .clip(imageShape)
                .background(colors.borderSoft),
        ) {
            val previewUri = capture.previewUri
            if (previewUri.isNullOrBlank()) {
                Box(
                    modifier         = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector        = Icons.Filled.Description,
                        contentDescription = null,
                        tint               = colors.muted,
                        modifier           = Modifier.size(28.dp),
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

            // Source badge — three-way: "scan" (document scanner —
            // neutral chip, default), "import" (gallery-picked photos
            // — coral accent), "photo" (in-app camera shot — neutral
            // chip with a distinct "Photo" label so the Library lists
            // distinguish a one-shot photo from a multi-page document
            // scan). Photo and Scan share the neutral chrome on
            // purpose; only the label disambiguates.
            val label = when {
                isImport -> "Import"
                isPhoto  -> "Photo"
                else     -> "Scan"
            }
            val chipBg = if (isImport) colors.accent else colors.surface.copy(alpha = 0.92f)
            val chipFg = if (isImport) colors.textOnAccent else colors.ink.copy(alpha = 0.75f)
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(QuickInkSpacing.s2)
                    .background(
                        color = chipBg,
                        shape = RoundedCornerShape(4.dp),
                    )
                    .padding(horizontal = QuickInkSpacing.s2, vertical = 3.dp),
            ) {
                Icon(
                    imageVector        = Icons.Filled.CenterFocusWeak,
                    contentDescription = null,
                    tint               = chipFg,
                    modifier           = Modifier.size(12.dp),
                )
                Text(
                    text  = label,
                    style = type.caption,
                    color = chipFg,
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
                            color = colors.ink.copy(alpha = 0.65f),
                            shape = RoundedCornerShape(4.dp),
                        )
                        .padding(horizontal = QuickInkSpacing.s2, vertical = 3.dp),
                )
            }
        }

        // Footer row — title + date on the left, three-dot menu on
        // the right. Menu is currently visual only; tapping the card
        // surface routes to scan detail.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    // Title Case — matches the library grid/list/
                    // search normalisation.
                    text     = title
                        .split(' ')
                        .joinToString(" ") { word ->
                            word.lowercase().replaceFirstChar(Char::titlecaseChar)
                        },
                    style    = type.cardTitle.copy(
                        fontSize   = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color    = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.size(2.dp))
                Text(
                    text  = displayDate,
                    style = type.caption,
                    color = colors.muted,
                )
            }
            Icon(
                imageVector        = Icons.Filled.MoreVert,
                contentDescription = "More",
                tint               = colors.muted,
                modifier           = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * `2026-05-02T14:30:00.000Z` → `May 2, 2026`. Falls back to the
 * input's date prefix when parsing fails — mirrors iOS's
 * `friendlyDate`.
 */
private fun friendlyMonthDay(iso: String): String =
    try {
        val instant = java.time.Instant.parse(iso)
        val date    = instant.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        date.format(java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy"))
    } catch (_: Exception) {
        iso.take(10)
    }

// The legacy "Quick categories" grid was removed from the Home
// scroll surface — tag browsing lives in the Workspace tab's tag
// cloud now. Helpers (CategoryGrid / CategoryTile / categoryStats
// / iconForCategory / CategoryStats) and the `onTapCategory`
// callback on `HomeScreen` come along with it.

// MARK: - Locations rail

/**
 * Horizontal chip rail of user-defined locations ("Home", "Work",
 * etc.) with a trailing "+" chip that opens the create / manage
 * sheet. Seeded with two defaults on first launch
 * (LocationRepository.seedDefaultsIfEmpty in QuickInkRoot).
 */
@Composable
private fun LocationsRail(
    locations: List<LocationEntity>,
    onAddTap: () -> Unit,
    onChipTap: (LocationEntity) -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current

    Column {
        Row(
            modifier              = Modifier.fillMaxWidth(),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text  = "Locations",
                style = type.label.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
                color = colors.ink,
            )
            Text(
                text     = "MANAGE",
                style    = type.label.copy(
                    letterSpacing = 1.2.sp,
                    fontSize      = 10.5.sp,
                    fontWeight    = FontWeight.SemiBold,
                ),
                color    = colors.accent,
                modifier = Modifier.clickable(onClick = onAddTap),
            )
        }
        Spacer(Modifier.size(QuickInkSpacing.s2))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
        ) {
            items(locations, key = { it.id }) { loc ->
                LocationChip(location = loc, onClick = { onChipTap(loc) })
            }
            item {
                AddLocationChip(onClick = onAddTap)
            }
        }
    }
}

@Composable
private fun LocationChip(
    location: LocationEntity,
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
            .padding(horizontal = 11.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector        = Icons.Filled.LocationOn,
            contentDescription = null,
            tint               = colors.accent,
            modifier           = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text  = location.name,
            style = type.label.copy(fontSize = 12.sp),
            color = colors.inkSoft,
        )
    }
}

@Composable
private fun AddLocationChip(onClick: () -> Unit) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current
    val shape  = RoundedCornerShape(999.dp)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(colors.borderSoft, shape)
            .border(1.dp, colors.border, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector        = Icons.Filled.Add,
            contentDescription = "Add location",
            tint               = colors.inkSoft,
            modifier           = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text  = "Add",
            style = type.label.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
            color = colors.inkSoft,
        )
    }
}

/**
 * Bottom sheet for creating and removing locations. Currently the
 * surface where the user actually edits the list — the rail itself
 * routes both "+" and chip taps in here.
 *
 * Linking a location to a document lives on the capture-detail
 * screen (separate sheet); this sheet is the rail's CRUD entry point.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocationsManageSheet(
    locations: List<LocationEntity>,
    onDismiss: () -> Unit,
    onAdd: () -> Unit,
    onEditRow: (LocationEntity) -> Unit,
    onDelete: (String) -> Unit,
) {
    val colors     = LocalQuickInkColors.current
    val type       = LocalQuickInkTypography.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        containerColor   = colors.bg,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start  = QuickInkSpacing.s5,
                    end    = QuickInkSpacing.s5,
                    bottom = QuickInkSpacing.s5,
                ),
            verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3),
        ) {
            Text(
                text  = "Locations",
                style = type.heading,
                color = colors.ink,
            )
            Text(
                text  = "Places you scan from. Tap a row to edit its address; attach a location to a document from the document's detail screen.",
                style = type.meta,
                color = colors.inkSoft,
            )

            // Full-width "+ Add location" button routes to the editor
            // dialog. The editor handles name + Use-current /
            // Search-address commit; this sheet just opens it.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(QuickInkRadius.md))
                    .background(colors.accent)
                    .clickable(onClick = onAdd)
                    .padding(vertical = QuickInkSpacing.s3),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector        = Icons.Filled.Add,
                        contentDescription = null,
                        tint               = colors.textOnAccent,
                        modifier           = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text  = "Add location",
                        style = type.label.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.textOnAccent,
                    )
                }
            }

            if (locations.isEmpty()) {
                Text(
                    text  = "No locations yet. Add one to get started.",
                    style = type.meta,
                    color = colors.muted,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2)) {
                    locations.forEach { loc ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(QuickInkRadius.md))
                                .background(colors.surface)
                                .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.md))
                                .clickable { onEditRow(loc) }
                                .padding(
                                    horizontal = QuickInkSpacing.s3,
                                    vertical   = QuickInkSpacing.s2,
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector        = Icons.Filled.LocationOn,
                                contentDescription = null,
                                tint               = colors.accent,
                                modifier           = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(QuickInkSpacing.s2))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text  = loc.name,
                                    style = type.body,
                                    color = colors.ink,
                                )
                                val addr = loc.address
                                if (!addr.isNullOrBlank()) {
                                    Text(
                                        text     = addr,
                                        style    = type.caption.copy(fontSize = 11.sp),
                                        color    = colors.muted,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Icon(
                                imageVector        = Icons.Filled.Delete,
                                contentDescription = "Delete ${loc.name}",
                                tint               = colors.muted,
                                modifier           = Modifier
                                    .size(20.dp)
                                    .clickable { onDelete(loc.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

// MARK: - People rail

/**
 * Horizontal chip rail of user-defined people ("Me", "Mom", "Dr.
 * Rao", etc.) with a trailing "+" chip that opens the create /
 * manage sheet. Seeded with "Me" on first launch
 * (PersonRepository.seedDefaultsIfEmpty in QuickInkRoot). Mirror of
 * the LocationsRail above.
 */
@Composable
private fun PeopleRail(
    people: List<PersonEntity>,
    onAddTap: () -> Unit,
    onChipTap: (PersonEntity) -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current

    Column {
        Row(
            modifier              = Modifier.fillMaxWidth(),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text  = "People",
                style = type.label.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
                color = colors.ink,
            )
            Text(
                text     = "MANAGE",
                style    = type.label.copy(
                    letterSpacing = 1.2.sp,
                    fontSize      = 10.5.sp,
                    fontWeight    = FontWeight.SemiBold,
                ),
                color    = colors.accent,
                modifier = Modifier.clickable(onClick = onAddTap),
            )
        }
        Spacer(Modifier.size(QuickInkSpacing.s2))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
        ) {
            items(people, key = { it.id }) { person ->
                PersonChip(person = person, onClick = { onChipTap(person) })
            }
            item {
                AddPersonChip(onClick = onAddTap)
            }
        }
    }
}

@Composable
private fun PersonChip(
    person: PersonEntity,
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
            .padding(horizontal = 11.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector        = Icons.Filled.Person,
            contentDescription = null,
            tint               = colors.accent,
            modifier           = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text  = person.name,
            style = type.label.copy(fontSize = 12.sp),
            color = colors.inkSoft,
        )
    }
}

@Composable
private fun AddPersonChip(onClick: () -> Unit) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current
    val shape  = RoundedCornerShape(999.dp)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(colors.borderSoft, shape)
            .border(1.dp, colors.border, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector        = Icons.Filled.Add,
            contentDescription = "Add person",
            tint               = colors.inkSoft,
            modifier           = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text  = "Add",
            style = type.label.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
            color = colors.inkSoft,
        )
    }
}

/**
 * Bottom sheet for creating, renaming, and removing people. Mirror
 * of [LocationsManageSheet]. Linking a person to a document lives
 * on the capture-detail picker.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeopleManageSheet(
    people: List<PersonEntity>,
    onDismiss: () -> Unit,
    onAdd: () -> Unit,
    onEditRow: (PersonEntity) -> Unit,
    onDelete: (String) -> Unit,
) {
    val colors     = LocalQuickInkColors.current
    val type       = LocalQuickInkTypography.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        containerColor   = colors.bg,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start  = QuickInkSpacing.s5,
                    end    = QuickInkSpacing.s5,
                    bottom = QuickInkSpacing.s5,
                ),
            verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3),
        ) {
            Text(
                text  = "People",
                style = type.heading,
                color = colors.ink,
            )
            Text(
                text  = "People you scan documents about. Tap a row to rename; attach a person to a document from the document's detail screen.",
                style = type.meta,
                color = colors.inkSoft,
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(QuickInkRadius.md))
                    .background(colors.accent)
                    .clickable(onClick = onAdd)
                    .padding(vertical = QuickInkSpacing.s3),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector        = Icons.Filled.Add,
                        contentDescription = null,
                        tint               = colors.textOnAccent,
                        modifier           = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text  = "Add person",
                        style = type.label.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.textOnAccent,
                    )
                }
            }

            if (people.isEmpty()) {
                Text(
                    text  = "No people yet. Add one to get started.",
                    style = type.meta,
                    color = colors.muted,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2)) {
                    people.forEach { person ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(QuickInkRadius.md))
                                .background(colors.surface)
                                .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.md))
                                .clickable { onEditRow(person) }
                                .padding(
                                    horizontal = QuickInkSpacing.s3,
                                    vertical   = QuickInkSpacing.s2,
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector        = Icons.Filled.Person,
                                contentDescription = null,
                                tint               = colors.accent,
                                modifier           = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(QuickInkSpacing.s2))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text  = person.name,
                                    style = type.body,
                                    color = colors.ink,
                                )
                                val sub = person.contactPhone?.takeIf { it.isNotBlank() }
                                    ?: person.contactEmail?.takeIf { it.isNotBlank() }
                                if (sub != null) {
                                    Text(
                                        text     = sub,
                                        style    = type.caption.copy(fontSize = 11.sp),
                                        color    = colors.muted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Icon(
                                imageVector        = Icons.Filled.Delete,
                                contentDescription = "Delete ${person.name}",
                                tint               = colors.muted,
                                modifier           = Modifier
                                    .size(20.dp)
                                    .clickable { onDelete(person.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}
