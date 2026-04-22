/*
 * AppTheme.kt
 * Compose Material3 theme wrapper + shared canvas / card.
 */

package app.releaf.mobile.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Role tokens are now @Composable getters (dark-mode-aware), so the scheme
// has to be built inside a @Composable. Cheap — only runs on theme setup.
@Composable
private fun releafColorScheme() = lightColorScheme(
    primary      = AppColors.Coral,
    onPrimary    = AppColors.OnAccent,
    secondary    = AppColors.ActionPrimary,
    onSecondary  = AppColors.OnPrimary,
    background   = AppColors.Canvas,
    onBackground = AppColors.TextPrimary,
    surface      = AppColors.CardSolid,
    onSurface    = AppColors.TextPrimary,
    error        = AppColors.Danger,
    outline      = AppColors.BorderDefault,
)

@Composable
fun ReleafTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = releafColorScheme(),
        content = content,
    )
}

/** Full-screen canvas background. Wrap top-level screens with this. */
@Composable
fun ReleafCanvas(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.Canvas)
            .dotGrid(),
        contentAlignment = Alignment.TopStart,
    ) {
        content()
    }
}

/**
 * Subtle dot-grid texture over the canvas. Specs come from design-tokens.json
 * (`pattern.dotGrid.*`). Single draw pass via `drawBehind` — no extra layer.
 *
 * `@Composable` because the default color reads the dark-mode-aware
 * `AppColors.DotGrid` getter, which requires a Composable context. Callers
 * already compose this into their UI — no call-site change needed.
 */
@Composable
fun Modifier.dotGrid(
    spacing: Dp = 24.dp,
    dotSize: Dp = 1.dp,
    color: Color = AppColors.DotGrid,
): Modifier = this.drawBehind {
    val spacingPx = spacing.toPx()
    val radiusPx = dotSize.toPx() / 2f
    var y = spacingPx / 2f
    while (y <= size.height) {
        var x = spacingPx / 2f
        while (x <= size.width) {
            drawCircle(color = color, radius = radiusPx, center = Offset(x, y))
            x += spacingPx
        }
        y += spacingPx
    }
}

/** Cream card with a hairline border. Matches iOS `Card`. */
@Composable
fun Card(
    modifier: Modifier = Modifier,
    padding: Dp = AppSpacing.s4,
    radius: Dp = AppRadius.md,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(radius))
            .background(AppColors.CardSolid)
            .border(1.dp, AppColors.BorderDefault, RoundedCornerShape(radius))
            .padding(padding),
    ) {
        content()
    }
}
