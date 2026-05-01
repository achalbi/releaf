/*
 * ShelfTheme.kt
 * Maps a Notebook's `colorToken` + `iconKey` onto the hero-card
 * visuals used by the variant-1 shelves / chapters / page screens.
 *
 * Kept alongside other shared components so both the hero-card and
 * its drill-in surfaces pull from the same palette registry.
 */

package app.releaf.mobile.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.outlined.LocalFlorist
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

data class ShelfPalette(
    val background: Color,
    val onBackground: Color,
    val onBackgroundMuted: Color,
    val accentSoft: Color,
)

object ShelfTheme {
    fun palette(token: String?): ShelfPalette = when (token?.lowercase()) {
        "green" -> ShelfPalette(
            background = Color(0xFF7AA874),
            onBackground = Color(0xFFF5EEDF),
            onBackgroundMuted = Color(0xC7F5EEDF),
            accentSoft = Color(0xFFDCE7CF),
        )
        "info", "purple" -> ShelfPalette(
            background = Color(0xFF8E86DB),
            onBackground = Color(0xFFF5EEDF),
            onBackgroundMuted = Color(0xC7F5EEDF),
            accentSoft = Color(0xFFE1DEF4),
        )
        "dry" -> ShelfPalette(
            background = Color(0xFFB8956A),
            onBackground = Color(0xFF241D17),
            onBackgroundMuted = Color(0xA8241D17),
            accentSoft = Color(0xFFE8D8BE),
        )
        "yellow" -> ShelfPalette(
            background = Color(0xFFF4C430),
            onBackground = Color(0xFF241D17),
            onBackgroundMuted = Color(0xA8241D17),
            accentSoft = Color(0xFFFBE9A6),
        )
        "coral" -> ShelfPalette(
            background = Color(0xFFE07856),
            onBackground = Color(0xFFF5EEDF),
            onBackgroundMuted = Color(0xC7F5EEDF),
            accentSoft = Color(0xFFFCD7C7),
        )
        else -> ShelfPalette(
            background = Color(0xFF7AA874),
            onBackground = Color(0xFFF5EEDF),
            onBackgroundMuted = Color(0xC7F5EEDF),
            accentSoft = Color(0xFFDCE7CF),
        )
    }

    fun icon(iconKey: String?): ImageVector = when (iconKey?.lowercase()) {
        "plant" -> Icons.Outlined.LocalFlorist
        "chart" -> Icons.Filled.BarChart
        "sun"   -> Icons.Filled.WbSunny
        "book"  -> Icons.AutoMirrored.Outlined.MenuBook
        else    -> Icons.Outlined.LocalFlorist
    }
}
