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
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import app.quickink.mobile.data.category.CategoryEntity
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
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime

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

    // Haze state — links the scrolling content (`Modifier.haze`) to
    // the bottom nav bar (`Modifier.hazeChild` inside BottomNavBar).
    // Haze captures pixels from the source on every frame the source
    // is visible, runs them through a `RenderEffect` blur (API 32+;
    // tint-only fallback on lower APIs), and composes the result
    // beneath the child's content. Hoisted here so the same instance
    // is shared across the source ↔ child pair.
    val hazeState = remember { HazeState() }

    // Live sync state — same source the Settings → Sync section
    // reads. Both keys land via `SyncStateDao.upsert` from the
    // sync worker, so a fresh pass updates the pill in real time.
    val syncStateDao = remember(app) { app.database.syncStateDao() }
    val lastSyncRow by syncStateDao
        .observe(SyncStateKeys.LAST_FULL_SYNC_AT)
        .collectAsState(initial = null)
    val pendingRow by syncStateDao
        .observe(SyncStateKeys.PENDING_COUNT)
        .collectAsState(initial = null)
    val pendingCount = pendingRow?.value?.toIntOrNull() ?: 0
    val syncPillState: SyncPillState = if (pendingCount > 0) {
        SyncPillState.Pending(pendingCount)
    } else {
        SyncPillState.Synced(relativeSyncTimestamp(lastSyncRow?.value))
    }

    Box(modifier = Modifier.fillMaxSize().quickInkDotGridBackground()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(hazeState)
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
            // Sync pill at the bottom of the scroll content —
            // scrolls with the page, no floating over content,
            // centered horizontally.
            Spacer(Modifier.size(QuickInkSpacing.s5))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                SyncStatusPill(state = syncPillState)
            }
        }

        BottomNavBar(
            onHome     = { /* current */ },
            onLibrary  = onOpenNotes,
            onScan     = { showQuickCapture = true },
            onSearch   = { onOpenSearch?.invoke() },
            onSettings = onOpenSettings,
            hazeState  = hazeState,
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

/**
 * Turn an ISO-8601 timestamp into "moments ago" / "5m ago" /
 * "2h ago" / "yesterday" / "3d ago" / "Apr 28". Null / unparsable
 * returns null so the pill renders "Not yet synced" instead of a
 * misleading bare date.
 */
private fun relativeSyncTimestamp(iso: String?): String? {
    if (iso.isNullOrBlank()) return null
    val instant = try {
        java.time.Instant.parse(iso)
    } catch (_: Exception) {
        return null
    }
    val seconds = java.time.Duration.between(instant, java.time.Instant.now())
        .seconds
        .coerceAtLeast(0L)
    return when {
        seconds < 60        -> "moments ago"
        seconds < 3600      -> "${seconds / 60}m ago"
        seconds < 86_400    -> "${seconds / 3600}h ago"
        seconds < 172_800   -> "yesterday"
        seconds < 604_800   -> "${seconds / 86_400}d ago"
        else                -> instant.atZone(java.time.ZoneId.systemDefault())
            .toLocalDate()
            .format(java.time.format.DateTimeFormatter.ofPattern("MMM d"))
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

// MARK: - Sync status pill

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
                text  = "RECENT",
                style = type.eyebrow,
                color = colors.muted,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text     = "All scans →",
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
    val title = capture.category?.takeIf { it.isNotEmpty() } ?: "Scan"
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
            text     = title,
            style    = type.label,
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
        Text(text = "CATEGORIES", style = type.eyebrow, color = colors.muted)
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
                        name         = cat.name,
                        icon         = iconForCategory(cat.name),
                        count        = stats.count,
                        recencyBadge = stats.recencyBadge,
                        onTap        = { onTapCategory?.invoke(cat.name) },
                        modifier     = Modifier.weight(1f),
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
 * loaded (`recentCaptures`, capped at 30 by the DAO). Returns the
 * match count plus an optional "Today" / "Yesterday" recency hint
 * for the tile's top-right badge.
 *
 * At 30 captures the count saturates — fine for the home tile
 * (UI still reads "30 scans"); a dedicated GROUP BY query would lift
 * that ceiling but isn't worth the extra observation while the
 * typical user library stays well under 30.
 */
private data class CategoryStats(val count: Int, val recencyBadge: String?)

private fun categoryStats(name: String, captures: List<CaptureEntity>): CategoryStats {
    val needle = name.lowercase()
    val matching = captures.filter { (it.category ?: "").lowercase() == needle }
    val today = LocalDate.now()
    val yesterday = today.minusDays(1)

    var hasToday = false
    var hasYesterday = false
    for (capture in matching) {
        val date = try {
            OffsetDateTime.parse(capture.createdAt).toLocalDate()
        } catch (_: Exception) {
            null
        } ?: continue
        when (date) {
            today -> {
                hasToday = true
                break // Today wins; no point scanning further.
            }
            yesterday -> hasYesterday = true
            else -> { /* older — ignored for the badge */ }
        }
    }
    val badge = when {
        hasToday     -> "Today"
        hasYesterday -> "Yesterday"
        else         -> null
    }
    return CategoryStats(count = matching.size, recencyBadge = badge)
}

@Composable
private fun CategoryTile(
    name: String,
    icon: ImageVector,
    count: Int,
    recencyBadge: String?,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(QuickInkRadius.md))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.md))
            .clickable(onClick = onTap)
            .padding(QuickInkSpacing.s4),
    ) {
        // Icon + recency badge laid out as a Row so the badge can
        // sit top-right while the icon stays top-left. Spacer pushes
        // the badge to the trailing edge.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(colors.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector        = icon,
                    contentDescription = null,
                    tint               = colors.accent,
                    modifier           = Modifier.size(16.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            if (recencyBadge != null) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(QuickInkRadius.sm))
                        .background(colors.accentSoft)
                        .padding(horizontal = QuickInkSpacing.s2, vertical = 4.dp),
                ) {
                    Text(
                        text  = recencyBadge.uppercase(java.util.Locale.getDefault()),
                        style = type.caption,
                        color = colors.accent,
                    )
                }
            }
        }
        Spacer(Modifier.size(QuickInkSpacing.s3))
        Text(text = name, style = type.heading, color = colors.ink)
        Text(
            text  = if (count == 0) "No scans yet" else "$count scan${if (count == 1) "" else "s"}",
            style = type.caption,
            color = colors.muted,
        )
    }
}

// MARK: - Bottom nav with Zap FAB

/**
 * Floating glass-morphism bottom nav — frosted card hovering over
 * the canvas with the Zap FAB lifted in the center. Mirror of
 * iOS [HomeScreen.swift]'s `bottomNavBar`.
 *
 * The frosted-glass effect is provided by Haze: [hazeChild] captures
 * pixels from the [hazeState] source (the scrolling Column behind),
 * runs them through a `RenderEffect` blur (API 32+; tint-only
 * fallback below), and composes the result beneath this row. The
 * paired `Modifier.haze(hazeState)` lives on the source Column in
 * [HomeScreen]. [HazeMaterials.regular] supplies the blur radius +
 * warm-cream tint preset — `colors.surface` is the container colour
 * so the brand cream reads through the frost rather than system gray.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
private fun BottomNavBar(
    onHome: () -> Unit,
    onLibrary: () -> Unit,
    onScan: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
) {
    val colors = LocalQuickInkColors.current
    val barShape = RoundedCornerShape(QuickInkRadius.lg)

    // Bright-top-to-warm-bottom gradient stroke — emulates light
    // reflecting off a glass edge. Mirrors the iOS bar's
    // `LinearGradient` border.
    val borderBrush = Brush.verticalGradient(
        colors = listOf(
            Color.White.copy(alpha = 0.55f),
            colors.border.copy(alpha = 0.40f),
        ),
    )

    // Two-layer composition: a backdrop Box that owns the shadow +
    // surface + haze + border (all on a SINGLE composable so the
    // shadow has an opaque tint to cast from), and a content Row
    // that sits on top *unclipped* so the lifted Zap FAB can render
    // above the bar's top edge.
    //
    // Why one Box instead of multiple sibling shadow layers: Compose's
    // `Modifier.shadow` only renders when the composable has opaque
    // drawn content — an empty Box with just `.shadow()` produces no
    // visible shadow because the RenderNode has nothing to elevate.
    // The earlier two-Box version had an empty "border halo" sibling
    // that was effectively invisible for that reason. Folding the
    // shadow + tint together on one element fixes both the missing
    // halo and the washed-out border.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start  = QuickInkSpacing.s4,
                end    = QuickInkSpacing.s4,
                bottom = QuickInkSpacing.s3,
            ),
    ) {
        // Border-halo layer — a tight, very dark shadow right at the
        // bar's edge. Lives BEHIND the backdrop so its solid surface
        // tint is hidden by the backdrop's tint above; only its
        // shadow (which extends past the bar's bounds via
        // `clip = false`) survives in the visible region. This adds
        // the requested "darker border shadow" on top of the soft
        // diffuse drop the backdrop already casts.
        //
        // The opaque `.background(colors.surface, barShape)` is
        // load-bearing — Compose's `Modifier.shadow` renders nothing
        // when the RenderNode has no opaque content. Without the
        // tint this Box would cast no shadow at all.
        // Inner border halo — even tighter, hugs the border line. Same
        // opaque-tint trick to keep the shadow rendering. Stacks with
        // the spread halo + soft drop below, so the cumulative effect
        // is: dark stripe right at the edge → fading dark band → soft
        // diffuse falloff far out.
        Box(
            modifier = Modifier
                .matchParentSize()
                .shadow(
                    elevation     = 8.dp,
                    shape         = barShape,
                    clip          = false,
                    ambientColor  = colors.ink.copy(alpha = 1.00f),
                    spotColor     = colors.ink.copy(alpha = 1.00f),
                )
                .background(colors.surface, barShape),
        )

        // Spread halo — wider, still very dark.
        Box(
            modifier = Modifier
                .matchParentSize()
                .shadow(
                    elevation     = 22.dp,
                    shape         = barShape,
                    clip          = false,
                    ambientColor  = colors.ink.copy(alpha = 0.95f),
                    spotColor     = colors.ink.copy(alpha = 0.95f),
                )
                .background(colors.surface, barShape),
        )

        Box(
            modifier = Modifier
                .matchParentSize()
                // 1. Shadow first — soft diffuse drop, the bar's
                //    overall lift away from the canvas. `clip = false`
                //    lets the shadow extend past the bar's bounds.
                //    The tighter, darker border halo above provides
                //    the embossed-edge cue; this one provides depth.
                .shadow(
                    elevation     = 32.dp,
                    shape         = barShape,
                    clip          = false,
                    ambientColor  = colors.ink.copy(alpha = 0.75f),
                    spotColor     = colors.ink.copy(alpha = 0.75f),
                )
                // 2. Opaque-ish cream tint — gives the shadow a real
                //    surface to cast from AND warms the haze so the
                //    bar reads as QuickInk cream, not system gray.
                //    0.85 alpha is the sweet spot between "haze still
                //    shows the page scrolling underneath" and "border
                //    has enough contrast to read".
                .background(colors.surface.copy(alpha = 0.85f), barShape)
                // 3. Clip to the rounded shape so the haze and any
                //    overdraw stay inside the silhouette.
                .clip(barShape)
                // 4. Haze on top of the tint — the `.thin` preset adds
                //    just enough blur for the glass cue without
                //    swallowing the cream tint.
                .hazeEffect(
                    state = hazeState,
                    style = HazeMaterials.thin(containerColor = colors.surface),
                )
                // 5. Border last so it draws crisply over both the
                //    tint and the haze, against the now-opaque surface.
                .border(width = 1.dp, brush = borderBrush, shape = barShape),
        )

        // Content layer — the cells. Sits on top of the backdrop and
        // is NOT clipped, so the lifted Zap FAB at `offset(y = -16dp)`
        // can render above the bar's top edge.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = QuickInkSpacing.s1,
                    vertical   = QuickInkSpacing.s1,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavIcon(icon = Icons.Filled.Home,               label = "Home",     active = true,  onClick = onHome,     modifier = Modifier.weight(1f))
            NavIconAsset(drawableId = R.drawable.ic_note,   label = "Library",  active = false, onClick = onLibrary,  modifier = Modifier.weight(1f))
            ZapFab(onClick = onScan, modifier = Modifier.weight(1f))
            NavIconAsset(drawableId = R.drawable.ic_search, label = "Search",   active = false, onClick = onSearch,   modifier = Modifier.weight(1f))
            NavIcon(icon = Icons.Filled.Settings,           label = "Settings", active = false, onClick = onSettings, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun NavIcon(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    val tint = if (active) colors.accent else colors.ink
    val pillBg = if (active) colors.accentSoft else Color.Transparent

    // Outer Column owns the click + weighted cell so the whole cell
    // is tappable. Inner Column carries the active-pill background +
    // padding so the pill hugs icon+label, not the full cell. Same
    // posture as Releaf's BottomNav RegularTab.
    Column(
        modifier            = modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .background(color = pillBg, shape = RoundedCornerShape(QuickInkRadius.md))
                .padding(horizontal = QuickInkSpacing.s2, vertical = QuickInkSpacing.s2),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector        = icon,
                contentDescription = label,
                tint               = tint,
                modifier           = Modifier.size(20.dp),
            )
            Text(text = label, style = type.caption, color = tint)
        }
    }
}

/**
 * Asset-backed nav icon — same shape as [NavIcon] but renders a
 * QuickInk vector drawable from `res/drawable/ic_*.xml`. Used for
 * Library / Search where we have brand-specific icons; Home and
 * Settings still use Material symbols where there's no brand
 * equivalent in the icon set.
 */
@Composable
private fun NavIconAsset(
    drawableId: Int,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    val tint = if (active) colors.accent else colors.ink
    val pillBg = if (active) colors.accentSoft else Color.Transparent

    Column(
        modifier            = modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .background(color = pillBg, shape = RoundedCornerShape(QuickInkRadius.md))
                .padding(horizontal = QuickInkSpacing.s2, vertical = QuickInkSpacing.s2),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                painter            = painterResource(id = drawableId),
                contentDescription = label,
                tint               = tint,
                modifier           = Modifier.size(20.dp),
            )
            Text(text = label, style = type.caption, color = tint)
        }
    }
}

/**
 * The signature ⚡ Zap FAB — coral disc with a top→bottom gradient,
 * lifted ~16dp above the bar's top edge so it reads as a hovering
 * brand mark. Counterpart to iOS's `zapFab`.
 */
@Composable
private fun ZapFab(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalQuickInkColors.current
    val gradient = Brush.verticalGradient(
        colors = listOf(colors.accent, colors.accentDeep),
    )
    Box(
        modifier         = modifier,
        contentAlignment = Alignment.Center,
    ) {
        // Canvas ring — bg-coloured disc 8dp larger than the FAB (4dp
        // ring on each side), with its own drop shadow. Punches through
        // the glass bar so the lifted brand mark sits on a canvas moat
        // instead of looking pasted onto the bar surface. Mirror of
        // iOS's three-layer `zapFab` ZStack.
        // Canvas ring border-shadow elevation bumped (16→26) and
        // darkened further (alpha 0.58→0.85) so the lift around the
        // FAB matches the bar's deeper border-halo treatment. The
        // ring still uses bg (canvas) fill so the FAB sits on a
        // moat punched through the glass bar.
        Box(
            modifier = Modifier
                .offset(y = (-16).dp)
                .size(64.dp)
                .shadow(
                    elevation    = 26.dp,
                    shape        = CircleShape,
                    clip         = false,
                    ambientColor = colors.ink.copy(alpha = 0.85f),
                    spotColor    = colors.ink.copy(alpha = 0.85f),
                )
                .clip(CircleShape)
                .background(colors.bg)
        )
        // Gradient FAB disc on top. Accent halo darkened (alpha
        // 0.55→0.68, elevation 20→24) — slightly more pronounced
        // coral glow under the brand mark.
        Box(
            modifier = Modifier
                .offset(y = (-16).dp)
                .size(56.dp)
                .shadow(
                    elevation    = 24.dp,
                    shape        = CircleShape,
                    clip         = false,
                    ambientColor = colors.accent.copy(alpha = 0.68f),
                    spotColor    = colors.accent.copy(alpha = 0.68f),
                )
                .clip(CircleShape)
                .background(brush = gradient, shape = CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector        = Icons.Filled.Bolt,
                contentDescription = "Scan",
                tint               = colors.textOnAccent,
                modifier           = Modifier.size(32.dp),
            )
        }
    }
}
