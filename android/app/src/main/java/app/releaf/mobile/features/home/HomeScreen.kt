/*
 * HomeScreen.kt
 *
 * Signed-in Home. Keeps the existing greeting / onboarding / tasks
 * / reminders / theme picker, and appends two Room-backed summary
 * cards (Notebook + Notepad) at the end — a compact dashboard view
 * of what the user has actually captured. The mid-screen raw
 * notebook list from the classic design is gone; the Notebook
 * summary card covers that affordance.
 */

package app.releaf.mobile.features.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.releaf.mobile.auth.GoogleAuthSession
import app.releaf.mobile.features.contacts.HomeContactsCard
import app.releaf.mobile.features.onboarding.OnboardingQuickGuideCard
import app.releaf.mobile.features.reminder.HomeRemindersCard
import app.releaf.mobile.features.tasks.HomeTasksCard
import app.releaf.mobile.ui.theme.AppAccent
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography

@Composable
fun HomeScreen(
    session: GoogleAuthSession,
    onOpenNotebook: (String) -> Unit,
    onOpenNotebooksTab: () -> Unit,
    onOpenNotepadTab: () -> Unit,
    onOpenNotepadEntry: (String) -> Unit,
    onOpenTasks: () -> Unit,
    onOpenReminders: () -> Unit,
    onOpenContacts: () -> Unit,
    onOpenActivityLog: () -> Unit,
    onSignOut: () -> Unit,
    onShowOnboarding: () -> Unit,
    onOpenDrawer: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeDashboardViewModel = viewModel(factory = HomeDashboardViewModel.factory(session)),
    shelvesViewModel: ShelvesViewModel = viewModel(factory = ShelvesViewModel.factory(session)),
) {
    val state by viewModel.state.collectAsState()
    val shelvesState by shelvesViewModel.state.collectAsState()
    val scroll = rememberScrollState()

    Box(modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(AppSpacing.s4),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.s6),
        ) {
            Header(
                session       = session,
                onOpenDrawer  = onOpenDrawer,
            )

            // Animation stays on top
            TreesSavedHero(counts = state.captureCounts)

            // Stats chip row hidden — the full cards below cover the
            // same actions with more context.
            // HomeActionChipsRow(
            //     onOpenTasks     = onOpenTasks,
            //     onOpenReminders = onOpenReminders,
            //     onOpenContacts  = onOpenContacts,
            // )

            OnboardingQuickGuideCard(onShowIntro = onShowOnboarding)
            HomeTasksCard(onOpenTasks = onOpenTasks)
            HomeRemindersCard(onOpenReminders = onOpenReminders)
            HomeContactsCard(onOpenContacts = onOpenContacts)

            // New: combined library card + quick-capture pills + dummy timeline
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s4)) {
                if (state.isLoading) {
                    Box(
                        Modifier.fillMaxWidth().padding(vertical = AppSpacing.s6),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = AppAccent.primary)
                    }
                } else {
                    when (val s = shelvesState) {
                        ShelvesUiState.Loading -> {
                            Box(
                                Modifier.fillMaxWidth().padding(vertical = AppSpacing.s4),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(color = AppAccent.primary)
                            }
                        }
                        is ShelvesUiState.Loaded -> {
                            HomeLibrarySection(
                                notebooks            = s.notebooks,
                                totalNotepadEntries  = state.totalNotepadEntries,
                                totalNotepadCaptures = state.totalNotepadCaptures,
                                openNotepadTodos     = state.openNotepadTodos,
                                todayNotepadCount    = state.todayNotepadCount,
                                onOpenNotebooks      = onOpenNotebooksTab,
                                onOpenNotepad        = onOpenNotepadTab,
                                openTodos            = s.openTodos.size,
                            )
                        }
                    }
                    // Highlight card hidden per design feedback —
                    // Trees Saved hero + library card already cover the
                    // per-mode totals. Quick-capture routing moves to
                    // the center-tab FAB.
                    // HomeQuickCaptureSection(
                    //     onCapture = { _ -> onOpenNotepadTab() },
                    //     counts    = mapOf(
                    //         QuickCaptureMode.Notes    to (state.captureCounts.notes + state.totalNotepadEntries),
                    //         QuickCaptureMode.Photos   to (state.captureCounts.photos + state.totalNotepadPhotos),
                    //         QuickCaptureMode.Scans    to (state.captureCounts.scans  + state.totalNotepadScans),
                    //         QuickCaptureMode.Voice    to (state.captureCounts.voice  + state.totalNotepadVoice),
                    //         QuickCaptureMode.Todos    to state.openNotepadTodos,
                    //         QuickCaptureMode.Location to state.totalNotepadLocations,
                    //     ),
                    // )
                    HomeTimelineCard(onSeeAll = onOpenActivityLog)
                }
            }

            Spacer(Modifier.height(AppSpacing.s10))
        }
    }
}

// ================================================================== Header

@Composable
private fun Header(session: GoogleAuthSession, onOpenDrawer: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s3),
    ) {
        AvatarCircle(
            initial  = initialLetter(session.displayName),
            onClick  = onOpenDrawer,
        )
        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s2)) {
            Text("RELEAF", style = AppTypography.Eyebrow, color = AppAccent.primary)
            Text(
                greeting(session),
                style = AppTypography.EditorialTitle,
                color = AppColors.TextPrimary,
            )
        }
    }
}

// ================================================================== Avatar + drawer

/**
 * Avatar styled to mirror the bottom-nav brand button — an outer
 * canvas-coloured ring with a soft drop shadow halo around it, and
 * the user's initial sitting on a coral-gradient inner disc. Clicks
 * open the home drawer.
 *
 * Visual ratios match the brand button:
 *   - 4dp canvas ring around a 36dp coral disc → 44dp outer
 *   - Same `Brush.radialGradient` halo (8% black, 1dp y-offset,
 *     3dp falloff outside the ring)
 *   - Same coral → coral-deep vertical gradient for the inner disc
 */
@Composable
private fun AvatarCircle(
    initial: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val innerDiameter = 36.dp
    val ringWidth     = 4.dp
    val outerDiameter = innerDiameter + ringWidth * 2

    val coralGradient = Brush.verticalGradient(
        colors = listOf(AppAccent.primary, AppAccent.deep),
    )

    Box(
        modifier = modifier
            .size(outerDiameter)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // Outer canvas ring with hand-drawn radial-gradient halo —
        // same recipe as the bottom-nav brand button so the two
        // surfaces feel like siblings.
        Box(
            modifier = Modifier
                .size(outerDiameter)
                .drawBehind {
                    val cx        = size.width / 2f
                    val cy        = size.height / 2f
                    val r         = size.width / 2f
                    val haloOuter = r + 3.dp.toPx()
                    val edgeStop  = r / haloOuter
                    drawCircle(
                        brush = Brush.radialGradient(
                            colorStops = arrayOf(
                                edgeStop to Color.Black.copy(alpha = 0.08f),
                                1f       to Color.Transparent,
                            ),
                            center = Offset(cx, cy + 1.dp.toPx()),
                            radius = haloOuter,
                        ),
                        radius = haloOuter,
                        center = Offset(cx, cy + 1.dp.toPx()),
                    )
                }
                .background(AppColors.Canvas, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(innerDiameter)
                    .background(coralGradient, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text  = initial,
                    color = AppColors.OnAccent,
                    fontSize = 16.sp,
                )
            }
        }
    }
}

// ================================================================== Drawer (Canopy header · B)
//
// "B · Canopy header" — forest banner at top, cream menu list with
// colored leaf glyphs and per-row status metadata, dashed green stem
// dividers, earth-brown footer with grass blades. Mirrors the same
// treatment on iOS (HomeScreen.swift).

private val CanopyBg       = Color(0xFF1E5943)
// Tree silhouette palette — mirrors TreesSavedHero.TreeGlyph so the
// drawer's forest banner reads as the same "world" as the hero card.
private val DrawerTreeTop    = Color(0xFF7AA874) // lightest canopy tier
private val DrawerTreeMid    = Color(0xFF5B8C52)
private val DrawerTreeBottom = Color(0xFF3E6B3B) // darkest canopy tier
private val DrawerTrunk      = Color(0xFF3E2A18)
private val CanopyCream    = Color(0xFFFFF8EE)
private val CanopyStem     = Color(0xFF7AA874)
private val CanopyEarth    = Color(0xFF8B7355)
private val CanopyGrass    = Color(0xFF6FA064)

// Per-row leaf glyph colors — picked to echo the Trees Saved hero
// palette so the drawer reads as the same little world.
private val LeafTimeline   = Color(0xFFE77850) // coral "golden hour"
private val LeafLibrary    = Color(0xFF7AA874) // leaf green
private val LeafNotepad    = Color(0xFFF5C4B3) // light coral
private val LeafTasks      = Color(0xFFF4C430) // leaf dark yellow
private val LeafReminders  = Color(0xFFB8956A) // leaf dry
private val LeafContacts   = Color(0xFF3E6B3B) // leaf deep-green
private val LeafSettings   = Color(0xFFF2C94C) // leaf yellow

@Composable
internal fun HomeDrawerContent(
    session: GoogleAuthSession,
    librarySubtitle: String,
    notepadSubtitle: String,
    tasksSubtitle: String,
    remindersSubtitle: String,
    contactsSubtitle: String,
    onClose: () -> Unit,
    onOpenTimeline: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenNotepad: () -> Unit,
    onOpenTasks: () -> Unit,
    onOpenReminders: () -> Unit,
    onOpenContacts: () -> Unit,
    onOpenSettings: () -> Unit,
    onSignOut: () -> Unit,
) {
    ModalDrawerSheet(
        drawerContainerColor = CanopyCream,
        windowInsets = WindowInsets(0, 0, 0, 0),
    ) {
        Column(modifier = Modifier.fillMaxHeight()) {
            CanopyHeader(session = session)

            Column(modifier = Modifier.weight(1f)) {
                Spacer(Modifier.height(AppSpacing.s3))

                DrawerItem(
                    leafColor = LeafTimeline,
                    label     = "Timeline",
                    meta      = "recent activity",
                    onClick   = onOpenTimeline,
                )
                DashedStemSeparator()
                DrawerItem(
                    leafColor = LeafLibrary,
                    label     = "Library",
                    meta      = librarySubtitle,
                    onClick   = onOpenLibrary,
                )
                DashedStemSeparator()
                DrawerItem(
                    leafColor = LeafNotepad,
                    label     = "Notepad",
                    meta      = notepadSubtitle,
                    onClick   = onOpenNotepad,
                )
                DashedStemSeparator()
                DrawerItem(
                    leafColor = LeafTasks,
                    label     = "Tasks",
                    meta      = tasksSubtitle,
                    onClick   = onOpenTasks,
                )
                DashedStemSeparator()
                DrawerItem(
                    leafColor = LeafReminders,
                    label     = "Reminders",
                    meta      = remindersSubtitle,
                    onClick   = onOpenReminders,
                )
                DashedStemSeparator()
                DrawerItem(
                    leafColor = LeafContacts,
                    label     = "Contacts",
                    meta      = contactsSubtitle,
                    onClick   = onOpenContacts,
                )
                DashedStemSeparator()
                DrawerItem(
                    leafColor = LeafSettings,
                    label     = "Settings",
                    meta      = "theme · sync · account",
                    onClick   = onOpenSettings,
                )
            }

            EarthFooter(onSignOut = onSignOut)
        }
    }
}

// ---------- Canopy header (forest banner) ----------

@Composable
private fun CanopyHeader(session: GoogleAuthSession) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(CanopyBg),
    ) {
        // Tree silhouette row — 4 stacked 3-tier pines drawn behind
        // the avatar/name row. Each pine mirrors the TreeGlyph used in
        // TreesSavedHero, so the banner lines up visually with the
        // Home-tab hero card. Canvas picks up the status-bar padding
        // too so the forest reaches the very top of the screen.
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(175.dp),
        ) {
            val w = size.width
            val h = size.height
            // (cx fraction of width, tree height fraction of header)
            val trees = listOf(
                0.12f to 0.52f,
                0.34f to 0.56f,
                0.60f to 0.62f,
                0.84f to 0.78f,
            )
            trees.forEach { (cxFrac, heightFrac) ->
                val treeH = h * heightFrac
                val treeW = treeH * 0.8f
                val left  = w * cxFrac - treeW / 2f
                val top   = h * 0.96f - treeH   // baseline near footer
                // Local-to-canvas coordinate helpers
                fun px(fx: Float) = left + treeW * fx
                fun py(fy: Float) = top + treeH * fy

                val topCanopy = Path().apply {
                    moveTo(px(0.50f), py(0.04f))
                    lineTo(px(0.25f), py(0.44f))
                    lineTo(px(0.75f), py(0.44f))
                    close()
                }
                drawPath(topCanopy, DrawerTreeTop)

                val midCanopy = Path().apply {
                    moveTo(px(0.50f), py(0.28f))
                    lineTo(px(0.15f), py(0.68f))
                    lineTo(px(0.85f), py(0.68f))
                    close()
                }
                drawPath(midCanopy, DrawerTreeMid)

                val botCanopy = Path().apply {
                    moveTo(px(0.50f), py(0.44f))
                    lineTo(px(0.06f), py(0.88f))
                    lineTo(px(0.94f), py(0.88f))
                    close()
                }
                drawPath(botCanopy, DrawerTreeBottom)

                drawRect(
                    color = DrawerTrunk,
                    topLeft = Offset(px(0.44f), py(0.88f)),
                    size = Size(treeW * 0.12f, treeH * 0.12f),
                )
            }
        }

        // Avatar + name overlay — top-aligned, pushed below the
        // status bar via statusBarsPadding with a little extra top
        // breathing room so the row doesn't crowd the very top edge.
        // Trees fill the rest of the canopy area behind the row.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = AppSpacing.s5)
                .padding(top = AppSpacing.s4),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.s3),
        ) {
            // Avatar — yellow leaf gradient (light top → deep bottom)
            // with trunk-brown initial so the text reads against yellow.
            val yellowLeafGradient = Brush.verticalGradient(
                colors = listOf(
                    Color(0xFFF9DB7F), // light leaf yellow
                    Color(0xFFC89B1A), // deep leaf yellow
                ),
            )
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(yellowLeafGradient),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = initialLetter(session.displayName),
                    color = DrawerTrunk,
                    fontSize = 18.sp,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text  = session.displayName?.ifBlank { "Guest" } ?: "Guest",
                    style = AppTypography.SectionTitle,
                    color = CanopyCream,
                )
                if (session.email.isNotBlank()) {
                    Text(
                        text  = session.email,
                        style = AppTypography.Meta,
                        color = CanopyCream.copy(alpha = 0.80f),
                    )
                }
            }
        }
    }
}

// ---------- Drawer item (leaf glyph + label + metadata) ----------

@Composable
private fun DrawerItem(
    leafColor: Color,
    label: String,
    meta: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = AppSpacing.s5, vertical = AppSpacing.s3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s3),
    ) {
        LeafGlyph(color = leafColor, size = 26.dp)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = AppTypography.Body, color = AppColors.TextPrimary)
            Text(meta, style = AppTypography.Tag, color = AppColors.TextSecondary)
        }
    }
}

@Composable
private fun LeafGlyph(color: Color, size: androidx.compose.ui.unit.Dp) {
    // Simple leaf: teardrop-ish path with a small midrib stroke.
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val leaf = Path().apply {
            // Start at stem tip (bottom-left), curve to top tip, back down.
            moveTo(w * 0.15f, h * 0.85f)
            cubicTo(
                w * 0.05f, h * 0.55f,
                w * 0.35f, h * 0.05f,
                w * 0.85f, h * 0.20f,
            )
            cubicTo(
                w * 0.70f, h * 0.55f,
                w * 0.50f, h * 0.95f,
                w * 0.15f, h * 0.85f,
            )
            close()
        }
        drawPath(leaf, color)
        // Midrib — slightly darker.
        drawLine(
            color = color.copy(alpha = 0.55f).let {
                Color(
                    red   = (it.red * 0.75f).coerceIn(0f, 1f),
                    green = (it.green * 0.75f).coerceIn(0f, 1f),
                    blue  = (it.blue * 0.75f).coerceIn(0f, 1f),
                    alpha = 0.8f,
                )
            },
            start = Offset(w * 0.15f, h * 0.85f),
            end   = Offset(w * 0.80f, h * 0.25f),
            strokeWidth = 1.2f,
            cap = StrokeCap.Round,
        )
    }
}

// ---------- Dashed stem separator ----------

@Composable
private fun DashedStemSeparator() {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.s5)
            .height(1.dp),
    ) {
        val y = size.height / 2f
        drawLine(
            color = CanopyStem.copy(alpha = 0.55f),
            start = Offset(0f, y),
            end   = Offset(size.width, y),
            strokeWidth = 1.2f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f), 0f),
        )
    }
}

// ---------- Earth-brown footer with grass blades ----------

@Composable
private fun EarthFooter(onSignOut: () -> Unit) {
    // Box sizes to its children — brown background bleeds all the
    // way to the bottom edge (behind the tab bar). The Row below
    // applies navigationBarsPadding so the sign-out row stays above
    // the tab bar while the earth continues beneath.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(CanopyEarth)
            .clickable(onClick = onSignOut),
    ) {
        // Grass blades poking up from the top edge of the footer.
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp),
        ) {
            val w = size.width
            val h = size.height
            val blades = 22
            for (i in 0 until blades) {
                val x = (i + 0.5f) / blades * w
                val bladeH = h * (0.55f + ((i % 3) * 0.15f))
                val path = Path().apply {
                    moveTo(x - 1.5f, h)
                    quadraticTo(
                        x - 0.5f, h - bladeH * 0.8f,
                        x, h - bladeH,
                    )
                    quadraticTo(
                        x + 0.5f, h - bladeH * 0.8f,
                        x + 1.5f, h,
                    )
                    close()
                }
                drawPath(
                    path = path,
                    color = if (i % 2 == 0) CanopyGrass else CanopyStem,
                )
            }
        }
        // Sign-out label — 80dp Row with navigationBarsPadding so the
        // label stays above the tab bar while the brown background
        // continues bleeding beneath.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .padding(horizontal = AppSpacing.s5)
                .padding(top = 12.dp)
                .navigationBarsPadding(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.s3),
        ) {
            // Stroked "→" chevron as a leaf-stem glyph
            Canvas(modifier = Modifier.size(20.dp)) {
                drawLine(
                    color = CanopyCream,
                    start = Offset(size.width * 0.2f, size.height * 0.5f),
                    end   = Offset(size.width * 0.85f, size.height * 0.5f),
                    strokeWidth = 2f,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = CanopyCream,
                    start = Offset(size.width * 0.55f, size.height * 0.25f),
                    end   = Offset(size.width * 0.85f, size.height * 0.5f),
                    strokeWidth = 2f,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = CanopyCream,
                    start = Offset(size.width * 0.55f, size.height * 0.75f),
                    end   = Offset(size.width * 0.85f, size.height * 0.5f),
                    strokeWidth = 2f,
                    cap = StrokeCap.Round,
                )
            }
            Text(
                text = "Sign out",
                style = AppTypography.Body,
                color = CanopyCream,
            )
        }
    }
}

private fun initialLetter(name: String?): String {
    val trimmed = name?.trim().orEmpty()
    if (trimmed.isEmpty()) return "?"
    return trimmed.first().uppercaseChar().toString()
}

private fun greeting(session: GoogleAuthSession): String {
    val name = session.displayName?.trim().orEmpty()
    return if (name.isNotEmpty()) "Hi, $name" else "Good morning"
}

