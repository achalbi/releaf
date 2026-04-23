/*
 * AppTheme.kt
 * Compose Material3 theme wrapper + shared canvas / card.
 *
 * Two user preferences gate the theme:
 *   - `ThemeMode`      (System / Light / Dark)
 *   - `AccentPaletteId` (Coral / Green / Yellow / Dry)
 *
 * Dark-mode is implemented by overriding `LocalConfiguration.uiMode` —
 * every `AppColors.*` role getter reads `isSystemInDarkTheme()` which
 * routes through that local, so all dark/light swaps propagate with
 * zero call-site changes. Accent palette swapping rides on the
 * `LocalAccent` CompositionLocal — call sites read via `AppAccent.*`.
 */

package app.releaf.mobile.ui.theme

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Role tokens are @Composable getters (dark-mode-aware), so the scheme
// has to be built inside a @Composable. Cheap — only runs on theme setup.
@Composable
private fun releafColorScheme(
    isDark: Boolean,
    accent: AccentPalette,
) = if (isDark) {
    darkColorScheme(
        primary      = accent.primary,
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
} else {
    lightColorScheme(
        primary      = accent.primary,
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
}

/**
 * Top-level theme wrapper. Reads the user's UI prefs once at the top
 * of the tree, rewrites `LocalConfiguration.uiMode` to force dark or
 * light when the user picked an override, and provides the active
 * accent palette via [LocalAccent].
 */
@Composable
fun ReleafTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { UiPreferences.get(context) }
    val state by prefs.state.collectAsState()

    // Resolve dark/light against the user's preference. "System" falls
    // back to the device's current uiMode so auto-dark still works.
    val systemDark = (LocalConfiguration.current.uiMode and
        Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    val effectiveDark = when (state.themeMode) {
        ThemeMode.Light  -> false
        ThemeMode.Dark   -> true
        ThemeMode.System -> systemDark
    }

    // Clone + rewrite Configuration.uiMode so every composable reading
    // `isSystemInDarkTheme()` — including every role getter on
    // AppColors — picks up the override transparently.
    val base = LocalConfiguration.current
    val overrideConfig = remember(base, effectiveDark) {
        Configuration(base).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (effectiveDark) Configuration.UI_MODE_NIGHT_YES
                else               Configuration.UI_MODE_NIGHT_NO
        }
    }
    val accent = AccentPalettes.forId(state.paletteId)

    CompositionLocalProvider(
        LocalConfiguration provides overrideConfig,
        LocalAccent        provides accent,
    ) {
        MaterialTheme(
            colorScheme = releafColorScheme(isDark = effectiveDark, accent = accent),
            content     = content,
        )
    }
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
