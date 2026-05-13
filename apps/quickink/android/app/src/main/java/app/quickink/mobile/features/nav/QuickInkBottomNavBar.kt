/*
 * QuickInkBottomNavBar.kt
 *
 * Floating editorial card bottom navigation — five tabs on an opaque
 * warm surface with a hairline border and a single soft shadow,
 * hovering over the canvas with a lifted coral ⚡ Zap FAB in the
 * centre. Modelled directly after Releaf's `BottomNav.kt` so the two
 * apps share the same UX vocabulary (clean cream card, no glass blur,
 * radial-gradient FAB shadow); QuickInk keeps its brand identity via
 * the bolt icon and the custom `IconNote` / `IconSearch` assets.
 *
 *   ┌─────────────────────────────────────────┐
 *   │  Home   Library   ⚡   Search  Settings │
 *   └─────────────────────────────────────────┘
 *
 * Surface:
 *   - Shape  : RoundedCornerShape(QuickInkRadius.lg)
 *   - Fill   : colors.surface (opaque — was a haze/glass blur stack
 *              with two-layer ink shadows; replaced to match Releaf's
 *              cleaner card aesthetic).
 *   - Border : 1dp colors.border hairline (flat, was a white→border
 *              gradient brush).
 *   - Shadow : single Modifier.shadow(8.dp), default Material colours.
 *
 * Active tab: coral-soft rounded-rectangle pill behind icon+label,
 *             coral tint. No drop shadow, no border — flat chip.
 * Inactive  : ink tint, no background.
 *
 * FAB centre: bg-coloured ring (4dp wide) around a coral-gradient
 *             disc. Two stacked radial-gradient shadows drawn via
 *             `drawBehind` — ambient (wider, softer, offset y+3) +
 *             contact (tighter, darker, offset y+1) — for a real
 *             penumbra falloff that Compose's `Modifier.shadow`
 *             couldn't produce on the warm surface. Lifted -16dp.
 *
 * Mirror of iOS [QuickInkBottomNavBar.swift]; UX rationale tracked in
 * Releaf's BottomNav.kt comments which served as the source.
 */

package app.quickink.mobile.features.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.quickink.mobile.R
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkRadius
import app.quickink.mobile.ui.theme.QuickInkSpacing

/**
 * Top-level destinations that own a tab in the bottom nav. Used by
 * [QuickInkBottomNavBar] to paint the active cell. Scan is *not* a
 * tab — it's a transient action launched from the FAB.
 */
/**
 * Top-level tab destinations + a `None` sentinel for sub-screens
 * (e.g. ScanDetail) that host the bar but aren't themselves a
 * destination — passing [NavTab.None] paints no active cell.
 */
enum class NavTab { Home, Workspace, Search, Settings, None }

/**
 * The reserved space the bottom nav occupies on screens that own a
 * scroll surface. Padding callers should add at the bottom of their
 * scroll content so the last item isn't hidden behind the floating
 * bar. ~140dp covers the bar (~80) + the ⚡ FAB lift (~16) + breathing
 * room.
 */
val QuickInkBottomNavReservedHeight = 140.dp

@Composable
fun QuickInkBottomNavBar(
    activeTab: NavTab,
    onHome: () -> Unit,
    onWorkspace: () -> Unit,
    onScan: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalQuickInkColors.current
    val navShape = RoundedCornerShape(QuickInkRadius.lg)

    // Outer padding = the "float" — the canvas shows through on the
    // sides and below. s4 (16dp) horizontal widens the bar so 5 cells
    // with text labels fit without crowding; s6 (24dp) bottom lifts it
    // clear of the system nav handle. Mirrors Releaf BottomNav.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start  = QuickInkSpacing.s4,
                end    = QuickInkSpacing.s4,
                bottom = QuickInkSpacing.s6,
            ),
    ) {
        // Card surface — drawn BEHIND the tabs via matchParentSize so
        // it takes the Row's bounds. Kept as a separate sibling (not a
        // modifier on the Row) so the Row itself is unclipped; the
        // lifted centre Zap FAB can overflow upward past the card's
        // top edge.
        Box(
            modifier = Modifier
                .matchParentSize()
                .shadow(elevation = 8.dp, shape = navShape)
                .background(colors.surface, navShape)
                .border(1.dp, colors.border, navShape),
        )

        Row(
            // s0 horizontal so each cell gets the full bar width; s1
            // vertical so the selected pill doesn't kiss the bar edges.
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = 0.dp,
                    vertical   = QuickInkSpacing.s1,
                ),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RegularTab(
                icon       = Icons.Outlined.Home,
                label      = "Home",
                isSelected = activeTab == NavTab.Home,
                modifier   = Modifier.weight(1f),
                onClick    = onHome,
            )
            RegularTabAsset(
                drawableId = R.drawable.ic_note,
                label      = "Workspace",
                isSelected = activeTab == NavTab.Workspace,
                modifier   = Modifier.weight(1f),
                onClick    = onWorkspace,
            )
            BrandTab(
                modifier = Modifier.weight(1f),
                onClick  = onScan,
            )
            RegularTabAsset(
                drawableId = R.drawable.ic_search,
                label      = "Search",
                isSelected = activeTab == NavTab.Search,
                modifier   = Modifier.weight(1f),
                onClick    = onSearch,
            )
            RegularTab(
                icon       = Icons.Outlined.Settings,
                label      = "Settings",
                isSelected = activeTab == NavTab.Settings,
                modifier   = Modifier.weight(1f),
                onClick    = onSettings,
            )
        }
    }
}

// ---------- Regular tab (Material vector icons) ----------

@Composable
private fun RegularTab(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current
    val tint   = if (isSelected) colors.accent     else colors.ink
    val bg     = if (isSelected) colors.accentSoft else Color.Transparent

    // `indication = null` suppresses the default rectangular ripple —
    // the only affordance is the coral-soft chip swap on selection.
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier.clickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .clip(RoundedCornerShape(QuickInkRadius.md))
                .background(bg)
                .padding(
                    horizontal = QuickInkSpacing.s2,
                    vertical   = QuickInkSpacing.s2,
                ),
        ) {
            Icon(
                imageVector        = icon,
                contentDescription = label,
                tint               = tint,
                modifier           = Modifier.size(20.dp),
            )
            Text(
                text     = label,
                // Slightly tighter than the global caption token so
                // the longest label ("Workspace", 9 chars) clears
                // the `weight(1f)` slot on stock devices without
                // wrapping to two lines. The other four labels (4–8
                // chars) read identically at this size.
                style    = type.caption.copy(fontSize = 10.5.sp),
                color    = tint,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/**
 * Asset-backed regular tab — same shape as [RegularTab] but renders a
 * QuickInk vector drawable from `res/drawable/ic_*.xml`. Used for
 * Library / Search where we have brand-specific icons.
 */
@Composable
private fun RegularTabAsset(
    drawableId: Int,
    label: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current
    val tint   = if (isSelected) colors.accent     else colors.ink
    val bg     = if (isSelected) colors.accentSoft else Color.Transparent

    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier.clickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .clip(RoundedCornerShape(QuickInkRadius.md))
                .background(bg)
                .padding(
                    horizontal = QuickInkSpacing.s2,
                    vertical   = QuickInkSpacing.s2,
                ),
        ) {
            Icon(
                painter            = painterResource(id = drawableId),
                contentDescription = label,
                tint               = tint,
                modifier           = Modifier.size(20.dp),
            )
            Text(
                text     = label,
                // Slightly tighter than the global caption token so
                // the longest label ("Workspace", 9 chars) clears
                // the `weight(1f)` slot on stock devices without
                // wrapping to two lines. The other four labels (4–8
                // chars) read identically at this size.
                style    = type.caption.copy(fontSize = 10.5.sp),
                color    = tint,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

// ---------- Brand (centre Zap FAB) ----------

@Composable
private fun BrandTab(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = LocalQuickInkColors.current

    // Outer canvas-coloured ring sits OUTSIDE the coral disc so the
    // button reads as cleanly punched out of the bar surface / page
    // canvas. Without it the coral edge can blur into the surface
    // where they touch. Total visible diameter = inner + 2 × ring.
    val innerDiameter = 56.dp
    val ringWidth     = 4.dp
    val outerDiameter = innerDiameter + ringWidth * 2
    val lift          = 16.dp

    // Coral → CoralDeep vertical gradient: lighter at top, deeper at
    // bottom — reads as a subtle 3D lift under ambient light.
    val coralGradient = Brush.verticalGradient(
        colors = listOf(colors.accent, colors.accentDeep),
    )

    // No ripple — the circular shadow + gradient already read as
    // pressable.
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .height(outerDiameter)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        // Two stacked drop shadows — outer ring carries the bigger
        // neutral drop shadow (lifts the whole disc off the bar
        // surface); the contact halo right at the disc edge gives the
        // shadow a hard contact line.
        //
        // Default Compose shadow uses M3 ambient/spot colours which
        // are nearly invisible on the warm surface, so we draw the
        // shadow ourselves with two radial gradients — one wider
        // softer (ambient), one tighter darker (contact). Falloff is
        // a real penumbra, not a solid ring.
        Box(
            modifier = Modifier
                .offset(y = -lift)
                .size(outerDiameter)
                .drawBehind {
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val r  = size.width / 2f

                    // Ambient — wider, softer, offset down 3dp.
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

                    // Contact — tighter, darker, smaller offset.
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
                    .size(innerDiameter)
                    .background(coralGradient, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector        = Icons.Outlined.Bolt,
                    contentDescription = "Scan",
                    tint               = colors.textOnAccent,
                    modifier           = Modifier.size(30.dp),
                )
            }
        }
    }
}
