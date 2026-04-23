/*
 * ReleafLogoRow.kt
 * Horizontal brand mark — leaf + "Releaf" serif wordmark. Matches the
 * four sizes in the Releaf Branding spec's "App Logo Variations" (xs /
 * sm / md / lg). Leaf color flows from the active accent palette; the
 * wordmark flows from text-primary.
 */

package app.releaf.mobile.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.releaf.mobile.ui.theme.AppAccent
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography

enum class ReleafLogoSize(
    val leaf: Dp,
    val stroke: Dp,
    val wordmark: androidx.compose.ui.unit.TextUnit,
    val gap: Dp,
) {
    Xs(leaf = 14.dp, stroke = 1.5.dp, wordmark = 13.sp, gap = AppSpacing.s1),
    Sm(leaf = 20.dp, stroke = 2.dp,   wordmark = 17.sp, gap = AppSpacing.s2),
    Md(leaf = 32.dp, stroke = 2.5.dp, wordmark = 26.sp, gap = AppSpacing.s2),
    Lg(leaf = 52.dp, stroke = 3.dp,   wordmark = 42.sp, gap = AppSpacing.s3),
}

@Composable
fun ReleafLogoRow(
    modifier: Modifier = Modifier,
    size: ReleafLogoSize = ReleafLogoSize.Md,
    leafGradientStart: Color = AppAccent.primary,
    leafGradientEnd: Color = AppAccent.deep,
    wordmarkColor: Color = AppColors.TextPrimary,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(size.gap),
    ) {
        ReleafLogo(
            size = size.leaf,
            filled = true,
            fillGradientStart = leafGradientStart,
            fillGradientEnd = leafGradientEnd,
            strokeWidth = size.stroke,
        )
        Text(
            text = "Releaf",
            style = TextStyle(
                fontFamily = AppTypography.EditorialTitle.fontFamily ?: FontFamily.Serif,
                fontWeight = FontWeight.Medium,
                fontSize = size.wordmark,
            ),
            color = wordmarkColor,
        )
    }
}
