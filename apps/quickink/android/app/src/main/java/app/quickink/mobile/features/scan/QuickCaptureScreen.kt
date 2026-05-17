/*
 * QuickCaptureScreen.kt
 *
 * Top-level capture surface. Owns the cross-surface chrome —
 * close button, the Document/Business-Card mode pill — and
 * dispatches the live area below the top bar to one of three
 * child surfaces:
 *
 *   [DocumentCaptureSurface]      → the existing ML Kit
 *                                   document scanner flow
 *                                   (Single/Multi-page
 *                                   pill + page-mock + shutter
 *                                   that launches Google's
 *                                   system scanner).
 *   [BusinessCardCaptureSurface]  → an in-app CameraX preview
 *                                   with a card-shaped guide
 *                                   overlay and an OpenCV-
 *                                   backed detector that auto-
 *                                   captures on a stable quad.
 *   [PhotoCaptureSurface]         → a plain single-shot still
 *                                   camera. No pill slot —
 *                                   entered transiently via the
 *                                   bottom-nav ⚡ FAB's long-
 *                                   press (`initialMode =
 *                                   CaptureMode.Photo` on this
 *                                   screen) or via the Photo
 *                                   icon in the other two
 *                                   surfaces' shutter rows. The
 *                                   pill keeps highlighting the
 *                                   last pill-selected mode
 *                                   while the photo surface is
 *                                   up, so tapping a pill flips
 *                                   back with one tap.
 *
 * Why two surfaces instead of one shared camera session:
 * Document mode is hosted by Google's `GmsDocumentScanning`
 * activity, which owns its own camera in a separate process —
 * we can't share its session with an in-process CameraX preview.
 * The toggle picks the surface; mode-switch latency is dominated
 * by Compose's mount/unmount of one of the child composables,
 * which is well under the spec's 100 ms budget.
 *
 * The mode pill lives in the top bar (where the "Capture" title
 * used to sit). That's the canonical tabbed-camera position —
 * Instagram, Apple Camera, every modern OS camera app puts the
 * mode picker right there — and it saves vertical space against
 * the existing layout's per-surface chrome at the bottom.
 *
 * Mirror of iOS `QuickCaptureScreen.swift`.
 */

package app.quickink.mobile.features.scan

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import app.quickink.mobile.features.scan.cardcapture.BusinessCardCaptureSurface
import app.quickink.mobile.features.settings.SettingsPreferences
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkRadius
import app.quickink.mobile.ui.theme.QuickInkSpacing

@Composable
fun QuickCaptureScreen(
    controller: ScanFlowController,
    onDismiss: () -> Unit,
    /**
     * Optional override for the starting surface. `null` (the
     * default) reads `quickink.capture.last_mode` from
     * SettingsPreferences as before. Passing
     * [CaptureMode.Photo] lets the bottom-nav ⚡ FAB's long-press
     * jump straight into the photo surface without disturbing
     * the user's pill choice — the coordinator below uses a
     * no-op persist on the `.Photo` branch so the next tap on
     * the FAB still lands on the previously-selected pill mode
     * (Document or Business Card).
     */
    initialMode: CaptureMode? = null,
) {
    val context = LocalContext.current
    val prefs = remember { SettingsPreferences(context) }

    val persistedPillMode = remember { prefs.lastCaptureMode }
    val startingMode = remember(initialMode) { initialMode ?: persistedPillMode }

    // Persisted starting mode. Captured once at composition time
    // (`remember`) so flipping the pill during the session doesn't
    // immediately re-read disk — `coordinator.select(...)` writes
    // through on every change. Long-press → `.Photo` is transient:
    // it should NOT overwrite the user's last pill choice. Gate
    // the persist hook so only pill-eligible modes round-trip to
    // SettingsPreferences. If the user later flips the pill from
    // inside this transient surface, that pill-driven `select()`
    // will land here too and re-persist normally.
    val coordinator = remember {
        CaptureModeCoordinator(
            initial = startingMode,
            persist = { mode ->
                if (mode != CaptureMode.Photo) {
                    prefs.lastCaptureMode = mode
                }
            },
        )
    }

    // The most recent pill-selected mode (Document or Business
    // Card — never `.Photo`). Drives the highlighted option in
    // the top-bar pill. Diverges from `coordinator.mode` when
    // the user enters `.Photo` transiently via the FAB long-
    // press or the shutter-row Photo icon: the pill keeps
    // showing whatever pill choice the user last made, so on
    // the photo surface the user still sees Document /
    // Business Card highlighted and can flip back with one tap.
    // Matches spec test #5 ("top-bar pill should still read
    // 'Document'") and spec §11 ("the user can switch to
    // Document mode via the pill without re-granting").
    var lastPillMode by remember {
        mutableStateOf(
            if (persistedPillMode == CaptureMode.Photo) CaptureMode.Document
            else persistedPillMode
        )
    }

    // First render: log `capture_mode_selected` with the persisted
    // value so dashboards see a "user is in this mode" signal even
    // when they don't touch the toggle. Without this, only flips
    // get tracked and the steady-state distribution is invisible.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        CaptureAnalytics.modeSelected(coordinator.mode)
    }

    // Status-bar inset for edge-to-edge devices (target SDK 35+)
    // so the close button clears the notch.
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F0E0D)),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Top bar — close button, mode pill (replaces the
            // "Capture" title), right-slot spacer that keeps the
            // pill centered.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start  = QuickInkSpacing.s5,
                        end    = QuickInkSpacing.s5,
                        top    = statusBarTop + QuickInkSpacing.s6,
                        bottom = QuickInkSpacing.s4,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircleIconButton(icon = Icons.Filled.Close, onClick = onDismiss)
                Spacer(Modifier.weight(1f))
                // Top-bar control depends on which surface is up:
                //   - `.Document` / `.BusinessCard` → the two-wide
                //     pill, highlighting whichever mode is active.
                //   - `.Photo` → a static "Photo" chip in the same
                //     slot. Photo mode is a transient one-shot
                //     capture; the chip is a passive mode indicator
                //     (no tap, no CTA) so the user reads the top
                //     bar as "you're in Photo right now" without
                //     being prompted to flip back to scanning. The
                //     close button on the left and completing the
                //     capture remain the two ways out.
                if (coordinator.mode == CaptureMode.Photo) {
                    PhotoModeChip()
                } else {
                    ModeTogglePill(
                        current = lastPillMode,
                        onSelect = { mode ->
                            lastPillMode = mode
                            coordinator.select(mode)
                        },
                    )
                }
                Spacer(Modifier.weight(1f))
                // Matches the 36dp footprint the close button
                // claims on the left so the toggle stays
                // visually centered.
                Spacer(Modifier.size(36.dp))
            }

            // Surface dispatch — render exactly one of the three
            // surfaces; never two at once. Compose's `when`
            // already disposes the inactive branches' effects,
            // so flipping the toggle tears down the previous
            // detector + overlay (or the previous launcher
            // state, or the photo CameraX session) without any
            // explicit cleanup wiring here.
            when (coordinator.mode) {
                CaptureMode.Document -> DocumentCaptureSurface(
                    controller    = controller,
                    onDismiss     = onDismiss,
                    onSelectPhoto = { coordinator.select(CaptureMode.Photo) },
                    modifier      = Modifier.weight(1f),
                )
                CaptureMode.BusinessCard -> BusinessCardCaptureSurface(
                    controller    = controller,
                    onDismiss     = onDismiss,
                    onSelectPhoto = { coordinator.select(CaptureMode.Photo) },
                    modifier      = Modifier.weight(1f),
                )
                CaptureMode.Photo -> PhotoCaptureSurface(
                    controller = controller,
                    onDismiss  = onDismiss,
                    modifier   = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * Document/Business-Card segmented pill — two options, same
 * visual treatment as the existing page-mode pill (white-on-
 * translucent track, accent-colored active state) so the screen
 * doesn't mix two different pill styles. Width hugs its label
 * content; the parent Row centers it via the surrounding
 * `Spacer(weight=1)` pair.
 *
 * Active-pill background animates over 150 ms ease-out to match
 * the spec's "underline / pill background with a 150 ms ease-out
 * transition" callout — Compose's `animateColorAsState` handles
 * the actual cross-fade.
 */
/**
 * The pill is intentionally two-wide on a 393dp device — three
 * pills crowd the top bar against the close button and right-slot
 * spacer. `.Photo` is a transient surface (long-press FAB /
 * shutter-row icon), so it doesn't take a pill slot. If a third
 * pill ever ships, the Instagram-style ordering
 * (Document / Photo / Business Card) reduces accidental
 * Business-Card taps.
 */
private val pillModes: List<CaptureMode> = listOf(CaptureMode.Document, CaptureMode.BusinessCard)

@Composable
private fun ModeTogglePill(
    current: CaptureMode,
    onSelect: (CaptureMode) -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    val view = LocalView.current
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(QuickInkRadius.pill))
            .background(Color.White.copy(alpha = 0.10f))
            .padding(4.dp),
    ) {
        pillModes.forEachIndexed { i, m ->
            if (i > 0) Spacer(Modifier.size(4.dp))
            val active = (m == current)
            val bg by animateColorAsState(
                targetValue = if (active) colors.accent else Color.Transparent,
                animationSpec = tween(durationMillis = 150),
                label = "capture-mode-pill-bg",
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(QuickInkRadius.pill))
                    .background(bg)
                    .clickable {
                        if (!active) {
                            // Light haptic on mode flip — matches
                            // the spec callout. CONTEXT_CLICK is
                            // the closest stock constant to "light
                            // tap"; HapticFeedbackConstants doesn't
                            // expose a separate light/medium
                            // distinction below API 30, so this
                            // collapses cleanly across versions.
                            view.performHapticFeedback(
                                android.view.HapticFeedbackConstants.CONTEXT_CLICK,
                            )
                            onSelect(m)
                        }
                    }
                    .padding(horizontal = QuickInkSpacing.s4, vertical = QuickInkSpacing.s2),
            ) {
                Text(
                    text  = m.pillLabel,
                    style = type.label,
                    color = if (active) Color.White else Color.White.copy(alpha = 0.55f),
                )
            }
        }
    }
}

@Composable
internal fun CircleIconButton(icon: ImageVector, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.10f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector       = icon,
            contentDescription = null,
            tint              = Color.White.copy(alpha = 0.85f),
            modifier          = Modifier.size(18.dp),
        )
    }
}

/**
 * Passive mode indicator shown in the top bar while the photo
 * surface is mounted, in place of the two-wide Document /
 * Business Card pill. Text-only chip styled in the same
 * translucent-pill vocabulary the pill uses so it reads as
 * part of the top-bar control family. No tap, no action — the
 * chip's job is to label the current surface ("you're in Photo")
 * so the top bar isn't blank while the pill is suppressed.
 * Escape happens via the close button or completing the capture.
 */
@Composable
private fun PhotoModeChip() {
    val type = LocalQuickInkTypography.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(QuickInkRadius.pill))
            .background(Color.White.copy(alpha = 0.10f))
            .padding(horizontal = QuickInkSpacing.s4, vertical = QuickInkSpacing.s2),
    ) {
        Text(
            text  = "Photo",
            style = type.label,
            color = Color.White,
        )
    }
}
