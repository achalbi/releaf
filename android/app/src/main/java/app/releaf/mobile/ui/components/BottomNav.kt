/*
 * BottomNav.kt
 *
 * Floating editorial card bottom navigation — five tabs on an opaque warm
 * cream surface with a hairline border and soft shadow, hovering over the
 * canvas with a lifted coral leaf in the center.
 *
 * Layout:
 *   [ Home ] [ Notebook ] [ 🌿 Leaf ] [ Notepad ] [ Settings ]
 *
 * Surface:
 *   - Shape  : rounded card @ AppRadius.nav (16dp).
 *   - Fill   : AppColors.CardSolid (opaque warm cream — matches the Card DS).
 *   - Border : 1dp AppColors.BorderDefault hairline.
 *   - Shadow : 8dp elevation, clipped to shape.
 *
 * Active tab: coral-soft rounded rectangle behind icon, coral tint.
 * Inactive  : textPrimary icon, no background.
 * Center    : coral-filled circle, white leaf icon, offset upward ~10dp.
 * Margins   : 16dp horizontal, 4dp bottom — the nav floats, does not span.
 *
 * NOTE: core Material icons don't include a leaf; Icons.Filled.Spa is used
 * as a visual stand-in. Swap for a vector asset once imported.
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
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.automirrored.filled.EventNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppAccent
import app.releaf.mobile.ui.theme.AppRadius
import app.releaf.mobile.ui.theme.AppSpacing

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
            BottomNavItem("home",     "Home",     Icons.Filled.Home),
            BottomNavItem("notebook", "Notebook", Icons.Filled.Book),
            BottomNavItem("leaf",     "",         Icons.Filled.Spa, BottomNavKind.Brand),
            BottomNavItem("notepad",  "Notepad",  Icons.AutoMirrored.Filled.EventNote),
            BottomNavItem("settings", "Settings", Icons.Filled.Settings),
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

    // Outer padding = the "float" — the dot-grid canvas shows through on the
    // sides and below. Horizontal s8 keeps the bar visibly narrower than the
    // screen; bottom s6 lifts it clear of the system nav handle.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = AppSpacing.s6,
                end = AppSpacing.s6,
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = AppSpacing.s1,
                    vertical = AppSpacing.s1,
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
    val tint = if (isSelected) AppAccent.primary     else AppColors.TextPrimary
    val bg   = if (isSelected) AppAccent.soft else Color.Transparent

    // Icon-only cell. Whole cell is the tap target (Box with weight(1f)); the
    // visible chip is a 24dp icon with coralSoft rounded-rect background when
    // selected. Title survives for contentDescription / TalkBack only.
    //
    // `indication = null` suppresses the default rectangular ripple — the
    // only affordance is the coralSoft chip swap on selection.
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
        Icon(
            imageVector = item.icon,
            contentDescription = item.title,
            tint = tint,
            modifier = Modifier
                .clip(RoundedCornerShape(AppRadius.md))
                .background(bg)
                .padding(
                    horizontal = AppSpacing.s2,
                    vertical = AppSpacing.s2,
                )
                .size(24.dp),
        )
    }
}

// ---------- Brand (center leaf) tab ----------

@Composable
private fun BrandTab(
    item: BottomNavItem,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val diameter = 56.dp
    val lift = 16.dp

    // Coral → CoralDeep vertical gradient: lighter at top, deeper at bottom —
    // reads as a subtle 3D lift under ambient light.
    val coralGradient = Brush.verticalGradient(
        colors = listOf(AppAccent.primary, AppAccent.deep),
    )

    // No ripple — the circular shadow + gradient already read as pressable.
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .height(diameter)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .offset(y = -lift)
                .size(diameter)
                .shadow(
                    elevation = 12.dp,
                    shape = CircleShape,
                    ambientColor = AppAccent.primary,
                    spotColor = AppAccent.primary,
                )
                .background(coralGradient, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = "Releaf",
                tint = AppColors.TextOnAccent,
                modifier = Modifier.size(24.dp),
            )
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
        )
    }
}
