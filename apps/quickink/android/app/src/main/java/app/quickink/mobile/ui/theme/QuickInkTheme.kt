/*
 * QuickInkTheme.kt
 *
 * Theme wrapper that configures Material3's `MaterialTheme` with
 * the QuickInk palette + typography, and exposes a
 * `LocalQuickInkColors` / `LocalQuickInkTypography` CompositionLocal
 * pair so screens can read tokens without round-tripping through
 * Material's mapping (which loses the Cormorant Garamond /
 * AccentSoft / Paper tones that don't have direct Material slots).
 *
 * Use:
 *
 *     setContent {
 *         QuickInkTheme {
 *             QuickInkRoot()
 *         }
 *     }
 *
 * Inside any composable under it:
 *
 *     val colors = LocalQuickInkColors.current
 *     val type   = LocalQuickInkTypography.current
 *     Text(text = "...", style = type.PageTitle, color = colors.Ink)
 *
 * Mirror of iOS `QuickInkTheme.swift` — same token names, same
 * fallbacks, same shape.
 */

package app.quickink.mobile.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color

// MARK: - Light/dark palette holder

@Immutable
data class QuickInkColorScheme(
    val bg: Color,
    val surface: Color,
    val border: Color,
    val borderSoft: Color,
    val accent: Color,
    val accentSoft: Color,
    val accentDeep: Color,
    val ink: Color,
    val inkSoft: Color,
    val muted: Color,
    val textOnAccent: Color,
    val paper1: Color,
    val paper2: Color,
    val paper3: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
) {
    /** Rotate paper tones by a stable seed (e.g., note ID hash). */
    fun paper(seed: Int): Color = when (((seed % 3) + 3) % 3) {
        0    -> paper1
        1    -> paper2
        else -> paper3
    }
}

private val LightQuickInkColors = QuickInkColorScheme(
    bg           = QuickInkColors.Bg,
    surface      = QuickInkColors.Surface,
    border       = QuickInkColors.Border,
    borderSoft   = QuickInkColors.BorderSoft,
    accent       = QuickInkColors.Accent,
    accentSoft   = QuickInkColors.AccentSoft,
    accentDeep   = QuickInkColors.AccentDeep,
    ink          = QuickInkColors.Ink,
    inkSoft      = QuickInkColors.InkSoft,
    muted        = QuickInkColors.Muted,
    textOnAccent = QuickInkColors.TextOnAccent,
    paper1       = QuickInkColors.Paper1,
    paper2       = QuickInkColors.Paper2,
    paper3       = QuickInkColors.Paper3,
    success      = QuickInkColors.Success,
    warning      = QuickInkColors.Warning,
    danger       = QuickInkColors.Danger,
)

/**
 * Dark scheme — coral identity preserved, ink/bg swap to deep
 * stone tones. Tweaked when the dark-mode brand pass lands; for
 * now this is the conservative starting point.
 */
private val DarkQuickInkColors = QuickInkColorScheme(
    bg           = Color(0xFF1C1917),
    surface      = Color(0xFF292524),
    border       = Color(0xFF3D3733),
    borderSoft   = Color(0xFF35302C),
    accent       = QuickInkColors.Accent,
    accentSoft   = Color(0xFF3A2A20),
    accentDeep   = QuickInkColors.AccentDeep,
    ink          = Color(0xFFF5EFE6),
    inkSoft      = Color(0xFFC9BDA8),
    muted        = Color(0xFF8C857F),
    textOnAccent = QuickInkColors.TextOnAccent,
    paper1       = Color(0xFF3F362A),
    paper2       = Color(0xFF42392C),
    paper3       = Color(0xFF3C3528),
    success      = QuickInkColors.Success,
    warning      = QuickInkColors.Warning,
    danger       = QuickInkColors.Danger,
)

// MARK: - Typography holder

@Immutable
data class QuickInkTypographyScheme(
    val display: androidx.compose.ui.text.TextStyle = QuickInkTextStyle.Display,
    val onboardingTitle: androidx.compose.ui.text.TextStyle = QuickInkTextStyle.OnboardingTitle,
    /** Cormorant body — onboarding showroom feel. App screens use [body]. */
    val onboardingBody: androidx.compose.ui.text.TextStyle = QuickInkTextStyle.OnboardingBody,
    val pageTitle: androidx.compose.ui.text.TextStyle = QuickInkTextStyle.PageTitle,
    val heading: androidx.compose.ui.text.TextStyle = QuickInkTextStyle.Heading,
    val eyebrow: androidx.compose.ui.text.TextStyle = QuickInkTextStyle.Eyebrow,
    /** Inter body — app screens. Onboarding uses [onboardingBody]. */
    val body: androidx.compose.ui.text.TextStyle = QuickInkTextStyle.Body,
    val bodyItalic: androidx.compose.ui.text.TextStyle = QuickInkTextStyle.BodyItalic,
    val handwritten: androidx.compose.ui.text.TextStyle = QuickInkTextStyle.Handwritten,
    val cardTitle: androidx.compose.ui.text.TextStyle = QuickInkTextStyle.CardTitle,
    val label: androidx.compose.ui.text.TextStyle = QuickInkTextStyle.Label,
    val ctaSerif: androidx.compose.ui.text.TextStyle = QuickInkTextStyle.CtaSerif,
    val meta: androidx.compose.ui.text.TextStyle = QuickInkTextStyle.Meta,
    val caption: androidx.compose.ui.text.TextStyle = QuickInkTextStyle.Caption,
)

// MARK: - Composition locals

/**
 * Use `compositionLocalOf` (not `static`) so dark-mode flips
 * recompose readers automatically — mode is reactive in this
 * theme.
 */
val LocalQuickInkColors = compositionLocalOf<QuickInkColorScheme> {
    error("QuickInkTheme not present — wrap your composable in QuickInkTheme { ... }")
}

val LocalQuickInkTypography = staticCompositionLocalOf {
    QuickInkTypographyScheme()
}

val LocalQuickInkSpacing = staticCompositionLocalOf {
    QuickInkSpacing
}

val LocalQuickInkRadius = staticCompositionLocalOf {
    QuickInkRadius
}

// MARK: - Theme entry point

/**
 * QuickInk theme entry point. Pass a `useDarkTheme` override; the
 * default reads `isSystemInDarkTheme()` so the app follows the
 * system setting.
 *
 * Wraps `MaterialTheme` so any ReleafCore / Material3 components
 * QuickInk pulls in still pick up colors/typography automatically.
 * Material's mapping is best-effort — the QuickInk locals are the
 * authoritative source for screens.
 */
@Composable
fun QuickInkTheme(
    /// User-pickable theme override. `System` follows the OS; `Light`
    /// / `Dark` force the corresponding mode.
    themeMode: ThemeMode = ThemeMode.System,
    /// User-pickable primary color (Coral / Leaf Green / Leaf Yellow
    /// / Leaf Dry). The composed `accent` token resolves to the
    /// picked family's deep variant in light mode, base variant in
    /// dark mode (so contrast against the canvas stays readable
    /// either way).
    primaryColor: PrimaryColor = PrimaryColor.Coral,
    content: @Composable () -> Unit,
) {
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val useDarkTheme = when (themeMode) {
        ThemeMode.System -> systemDark
        ThemeMode.Light  -> false
        ThemeMode.Dark   -> true
    }
    // Resolve the picked primary against the effective mode and
    // graft it onto the base scheme. Light → deep variant; dark →
    // base variant. accentDeep stays as the deep variant in both
    // modes so hover / pressed states keep their emphasis.
    val resolvedAccent = if (useDarkTheme) primaryColor.base else primaryColor.deep
    val baseScheme    = if (useDarkTheme) DarkQuickInkColors else LightQuickInkColors
    val quickInkColors = baseScheme.copy(
        accent     = resolvedAccent,
        accentDeep = primaryColor.deep,
    )
    val typography = QuickInkTypographyScheme()

    val materialColorScheme = if (useDarkTheme) {
        darkColorScheme(
            primary       = quickInkColors.accent,
            onPrimary     = quickInkColors.textOnAccent,
            secondary     = quickInkColors.accentSoft,
            onSecondary   = quickInkColors.ink,
            background    = quickInkColors.bg,
            onBackground  = quickInkColors.ink,
            surface       = quickInkColors.surface,
            onSurface     = quickInkColors.ink,
            error         = quickInkColors.danger,
            onError       = quickInkColors.textOnAccent,
        )
    } else {
        lightColorScheme(
            primary       = quickInkColors.accent,
            onPrimary     = quickInkColors.textOnAccent,
            secondary     = quickInkColors.accentSoft,
            onSecondary   = quickInkColors.ink,
            background    = quickInkColors.bg,
            onBackground  = quickInkColors.ink,
            surface       = quickInkColors.surface,
            onSurface     = quickInkColors.ink,
            error         = quickInkColors.danger,
            onError       = quickInkColors.textOnAccent,
        )
    }

    val materialTypography = Typography(
        displayLarge   = typography.display,
        headlineLarge  = typography.pageTitle,
        headlineMedium = typography.heading,
        titleMedium    = typography.heading,
        bodyLarge      = typography.body,
        bodyMedium     = typography.body,
        bodySmall      = typography.meta,
        labelLarge     = typography.label,
        labelMedium    = typography.label,
        labelSmall     = typography.caption,
    )

    androidx.compose.runtime.CompositionLocalProvider(
        LocalQuickInkColors provides quickInkColors,
        LocalQuickInkTypography provides typography,
    ) {
        MaterialTheme(
            colorScheme = materialColorScheme,
            typography  = materialTypography,
            content     = content,
        )
    }
}

// MARK: - Decoration shortcuts (mirror of iOS view modifiers)

/**
 * Coral pill button surface — fills with accent, radius 999,
 * vertical padding tuned for one-line CTAs. Use as the outermost
 * background on Buttons that want the QuickInk CTA look.
 */
@Composable
fun Modifier.quickInkCTA(): Modifier {
    val colors = LocalQuickInkColors.current
    return this
        .clip(RoundedCornerShape(QuickInkRadius.pill))
        .background(colors.accent)
        .padding(vertical = QuickInkSpacing.s3)
}

/**
 * Card surface — white fill with border, rounded corners. Use as
 * the outermost background on grouped-content cards.
 */
@Composable
fun Modifier.quickInkCard(): Modifier {
    val colors = LocalQuickInkColors.current
    return this
        .clip(RoundedCornerShape(QuickInkRadius.md))
        .background(colors.surface)
}

/**
 * Pill chip surface — soft border background, rounded. Used for
 * filter chips, status pills, search bar.
 */
@Composable
fun Modifier.quickInkPill(): Modifier {
    val colors = LocalQuickInkColors.current
    return this
        .clip(RoundedCornerShape(QuickInkRadius.pill))
        .background(colors.borderSoft)
        .padding(horizontal = QuickInkSpacing.s3, vertical = QuickInkSpacing.s2)
}
