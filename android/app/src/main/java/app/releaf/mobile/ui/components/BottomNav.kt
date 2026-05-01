/*
 * BottomNav.kt
 *
 * Floating editorial card bottom navigation — five tabs on an opaque warm
 * cream surface with a hairline border and soft shadow, hovering over the
 * canvas with a lifted coral leaf in the center.
 *
 * Layout:
 *   [ Home ] [ Library ] [ 🌿 Leaf ] [ Notepad ] [ Settings ]
 *
 * Surface:
 *   - Shape  : rounded card @ AppRadius.nav (16dp).
 *   - Fill   : AppColors.CardSolid (opaque warm cream — matches the Card DS).
 *   - Border : 1dp AppColors.BorderDefault hairline.
 *   - Shadow : 8dp elevation, clipped to shape.
 *
 * Active tab: coral-soft rounded rectangle behind icon, coral tint.
 * Inactive  : textPrimary icon, no background.
 * Center    : coral-filled circle, brand Releaf leaf (`ReleafLogoSolid`)
 *             rendered on top, offset upward ~10dp.
 * Margins   : 16dp horizontal, 4dp bottom — the nav floats, does not span.
 *
 * Ported from Inkcreate mobile DS.
 */

package app.releaf.mobile.ui.components

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
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.automirrored.outlined.EventNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppAccent
import app.releaf.mobile.ui.theme.AppRadius
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography
import app.releaf.mobile.ui.theme.LocalFontWeight

// ---------- Item model ----------

enum class BottomNavKind { Regular, Brand }

data class BottomNavItem(
    val id: String,
    val title: String,
    val icon: ImageVector,
    val kind: BottomNavKind = BottomNavKind.Regular,
) {
    companion object {
        /** Default Releaf IA. */
        val defaults = listOf(
            // Outlined icons across the four regular tabs so the bar
            // reads as a uniform line-icon set — Material Filled glyphs
            // looked heavier than the prototype and the door cutout in
            // Filled.Home read as a notch rather than a clean outline.
            BottomNavItem("home",     "Home",     Icons.Outlined.Home),
            // Tab id stays "notebook" so existing nav routing keeps
            // working; label + icon reflect the Library rename. Custom
            // LibraryShelf glyph (3 upright spines + 1 tilted) matches
            // the Variant D prototype — Material's stock LibraryBooks
            // reads as stacked documents, which doesn't fit "library".
            BottomNavItem("notebook", "Library",  LibraryShelf),
            BottomNavItem("leaf",     "",         Icons.Filled.Spa, BottomNavKind.Brand),
            BottomNavItem("notepad",  "Notepad",  Icons.AutoMirrored.Outlined.EventNote),
            BottomNavItem("settings", "Settings", Icons.Outlined.Settings),
        )
    }
}

// ---------- BottomNav ----------

@Composable
fun BottomNav(
    items: List<BottomNavItem> = BottomNavItem.defaults,
    selectedId: String,
    onSelect: (String) -> Unit,
    onBrandTap: (() -> Unit)? = null,
) {
    val navShape = RoundedCornerShape(AppRadius.nav)

    // Outer padding = the "float" — the dot-grid canvas shows through
    // on the sides and below. Horizontal s4 (was s6) widens the bar so
    // 5 cells with text labels fit without crowding; bottom s6 still
    // lifts it clear of the system nav handle.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start  = AppSpacing.s4,
                end    = AppSpacing.s4,
                bottom = AppSpacing.s6,
            ),
    ) {
        // Card surface — drawn BEHIND the tabs via matchParentSize so it takes
        // the Row's bounds. Kept as a separate sibling (not a modifier on the
        // Row) so the Row itself is unclipped; the lifted center leaf button
        // can overflow upward past the card's top edge.
        Box(
            modifier = Modifier
                .matchParentSize()
                .shadow(elevation = 8.dp, shape = navShape)
                .background(AppColors.CardSolid, navShape)
                .border(1.dp, AppColors.BorderDefault, navShape),
        )

        Row(
            // Tightened from s1 → s0 horizontal so each cell gets the
            // full bar width to lay out its icon + label. Vertical s1
            // stays so the selected pill doesn't kiss the bar edges.
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = AppSpacing.s0,
                    vertical   = AppSpacing.s1,
                ),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEach { item ->
                when (item.kind) {
                    BottomNavKind.Regular -> RegularTab(
                        item = item,
                        isSelected = selectedId == item.id,
                        modifier = Modifier.weight(1f),
                        onClick = { onSelect(item.id) },
                    )
                    BottomNavKind.Brand -> BrandTab(
                        item = item,
                        isSelected = selectedId == item.id,
                        modifier = Modifier.weight(1f),
                        onClick = { onBrandTap?.invoke() ?: onSelect(item.id) },
                    )
                }
            }
        }
    }
}

// ---------- Regular tab ----------

@Composable
private fun RegularTab(
    item: BottomNavItem,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val tint = if (isSelected) AppAccent.primary  else AppColors.TextPrimary
    val bg   = if (isSelected) AppAccent.soft     else Color.Transparent

    // Vertical icon-over-label cell. When selected, both the icon and
    // label are wrapped in a coral-soft rounded-rectangle pill so the
    // tab reads as a single highlighted chip. Inactive tabs render flat
    // — same icon + label, no background, primary-text colour.
    //
    // `indication = null` suppresses the default rectangular ripple —
    // the only affordance is the coral-soft chip swap on selection.
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
            // Horizontal tightened from s3 → s2 so the selected pill
            // hugs the icon + label more closely; gives long-ish
            // labels like "Settings" / "Notebook" enough room without
            // stealing too much from neighbours. Vertical kept at s2
            // so the pill keeps its comfortable top/bottom breathing
            // room.
            modifier = Modifier
                .clip(RoundedCornerShape(AppRadius.md))
                .background(bg)
                .padding(
                    horizontal = AppSpacing.s2,
                    vertical   = AppSpacing.s2,
                ),
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = item.title,
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
            androidx.compose.material3.Text(
                text  = item.title,
                style = if (isSelected) {
                    AppTypography.Tag.copy(
                        fontWeight = app.releaf.mobile.ui.theme.LocalFontWeight.current,
                    )
                } else {
                    AppTypography.Tag.copy(
                        fontWeight = app.releaf.mobile.ui.theme.LocalFontWeight.current,
                    )
                },
                color = tint,
            )
        }
    }
}

// ---------- Brand (center leaf) tab ----------

@Composable
private fun BrandTab(
    item: BottomNavItem,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    // Outer canvas-coloured ring sits OUTSIDE the coral disc so the
    // button reads as cleanly punched out of the cream nav bar / page
    // canvas. Without it the coral edge can blur into the cream
    // surface where they touch. Total visible diameter = inner +
    // 2 × ring.
    //
    // When `isSelected` is true (Capture is the active top-level tab),
    // a thin coral outline is drawn at the very edge of the cream ring
    // so the FAB reads as the active tab without changing its lifted
    // disc identity. Matches docs/CAPTURE_TAB_PLAN.md Phase 3.
    val innerDiameter = 56.dp
    val ringWidth     = 4.dp
    val outerDiameter = innerDiameter + ringWidth * 2
    val lift          = 16.dp
    val activeRing    = 1.5.dp

    // Coral → CoralDeep vertical gradient: lighter at top, deeper at bottom —
    // reads as a subtle 3D lift under ambient light.
    val coralGradient = Brush.verticalGradient(
        colors = listOf(AppAccent.primary, AppAccent.deep),
    )

    // No ripple — the circular shadow + gradient already read as pressable.
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
        // Two stacked shadows — outer ring carries the bigger neutral
        // drop shadow (lifts the whole disc off the cream nav bar);
        // the inner coral disc gets its own coral-tinted shadow that
        // reads as a warm glow inside the canvas ring.
        //
        // Default Compose shadow uses the M3 ambient/spot colours which
        // are nearly invisible on the cream surface, so we override
        // both with explicit dark + warm tints.
        Box(
            modifier = Modifier
                .offset(y = -lift)
                .size(outerDiameter)
                // Layered drop shadow — a wider, lower-alpha ambient
                // layer with a small downward offset (suggests light
                // from above) plus a tighter contact halo right at
                // the disc edge. Both use radial gradients with the
                // colour fully concentrated at the disc edge then
                // fading to transparent, so the falloff reads as a
                // real shadow with penumbra rather than a solid ring.
                .drawBehind {
                    val cx        = size.width / 2f
                    val cy        = size.height / 2f
                    val r         = size.width / 2f

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
                .background(AppColors.Canvas, CircleShape)
                // Active-tab ring: thin coral outline at the outer
                // edge of the cream ring. Visible only when the Leaf
                // tab is currently selected.
                .let {
                    if (isSelected) {
                        it.border(activeRing, AppAccent.primary, CircleShape)
                    } else it
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(innerDiameter)
                    .background(coralGradient, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                // Brand mark — cream Releaf leaf on the coral disc.
                // Uses ReleafLogoSolid so the leaf silhouette matches the
                // app icon, splash, and logo lockup. `item.icon` is
                // ignored for the Brand kind; it stays on the data class
                // for consistency but isn't rendered here.
                ReleafLogoSolid(
                    size = 30.dp,
                    leafColor = AppColors.TextOnAccent,
                    veinColor = AppAccent.deep,
                    veinWidth = 1.5.dp,
                )
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF5EEE3, widthDp = 390)
@Composable
private fun BottomNavPreview() {
    var selected by remember { mutableStateOf("home") }
    Column {
        Box(Modifier.fillMaxWidth().height(560.dp))
        BottomNav(
            selectedId = selected,
            onSelect = { selected = it },
            // Treat the Leaf brand tap like any other tab in the
            // preview so we can see the active-ring on the FAB by
            // tapping it. Phase 2 wires this to a real navigation.
            onBrandTap = { selected = "leaf" },
        )
    }
}

/** Active-state preview: Leaf is the current tab — thin coral
 *  outline visible at the edge of the cream ring. */
@Preview(showBackground = true, backgroundColor = 0xFFF5EEE3, widthDp = 390)
@Composable
private fun BottomNavLeafActivePreview() {
    Column {
        Box(Modifier.fillMaxWidth().height(560.dp))
        BottomNav(
            selectedId = "leaf",
            onSelect = {},
            onBrandTap = {},
        )
    }
}
