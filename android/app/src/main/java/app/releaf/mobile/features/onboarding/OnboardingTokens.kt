/*
 * OnboardingTokens.kt
 *
 * Literal colour/typography tokens extracted from
 * docs/onboarding/source/onboarding.css. The wizard intentionally
 * uses fixed coral values (rather than the themeable [AppAccent]) so
 * the first-run experience has a consistent brand identity regardless
 * of the active accent palette.
 *
 * Typography roles are composable getters so they pick up the user's
 * global font-weight preference via [LocalFontWeight] — the wizard
 * lives behind the same setting as the rest of the app.
 */

package app.releaf.mobile.features.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import app.releaf.mobile.ui.theme.LocalFontWeight

internal object OnboardTokens {
    // Colours (from onboarding.css)
    val Coral          = Color(0xFFFF5F4E)
    val CoralFaded     = Color(0xFFFFB8B2)
    val CoralSoftBg    = Color(0xFFFFF5F4)
    val CoralSoftBorder= Color(0xFFFFD5D0)
    val ModalBg        = Color(0xFFFDF8F4)
    val CardBg         = Color(0xFFFFFFFF)
    val TextPrimary    = Color(0xFF1B1B1D)
    val TextBody       = Color(0xFF4A4845)
    val TextMuted      = Color(0xFF7A7670)
    val TextSubtle     = Color(0xFF9E9990)
    val BorderRest     = Color(0xFFE2DBD4)
    val LineFill       = Color(0xFFEDE8E3)
    val GhostHoverBg   = Color(0xFFF0ECE8)
    val TodoTaskBg     = Color(0xFFFFF0E8)
    val TodoTaskFg     = Color(0xFFE06020)
    val ScanDoneBg     = Color(0xFFFFD5D1)
    val ScanDoneFg     = Color(0xFFC0392B)
    val PhotoFrameBg   = Color(0xFFF0ECE8)
    val PhotoFrameBorder = Color(0xFFE0D8D0)

    // Gradient stops for the Releaf app icon.
    val IconGradientStart = Color(0xFF123524)
    val IconGradientEnd   = Color(0xFF3F7D58)
    val IconSurface       = Color(0xFFF7F5EF)
    val IconLine          = Color(0xFF2F5D50)
    val IconDotFill       = Color(0xFFD98324)

    // Typography
    val Headline: TextStyle
        @Composable @ReadOnlyComposable
        get() = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = LocalFontWeight.current,
            fontSize   = 23.sp,
            lineHeight = 29.sp,
        )
    val Body: TextStyle
        @Composable @ReadOnlyComposable
        get() = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = LocalFontWeight.current,
            fontSize   = 15.sp,
            lineHeight = 24.sp,
        )
    val Badge: TextStyle
        @Composable @ReadOnlyComposable
        get() = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = LocalFontWeight.current,
            fontSize   = 11.sp,
            letterSpacing = 0.6.sp,
        )
    val Button: TextStyle
        @Composable @ReadOnlyComposable
        get() = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = LocalFontWeight.current,
            fontSize   = 14.sp,
        )
    val Skip: TextStyle
        @Composable @ReadOnlyComposable
        get() = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = LocalFontWeight.current,
            fontSize   = 12.sp,
        )
    val CalendarHeader: TextStyle
        @Composable @ReadOnlyComposable
        get() = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = LocalFontWeight.current,
            fontSize   = 11.sp,
            letterSpacing = 0.5.sp,
        )
    val CalendarNumber: TextStyle
        @Composable @ReadOnlyComposable
        get() = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = LocalFontWeight.current,
            fontSize   = 38.sp,
            lineHeight = 38.sp,
        )
    val CtaLabel: TextStyle
        @Composable @ReadOnlyComposable
        get() = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = LocalFontWeight.current,
            fontSize   = 13.sp,
            lineHeight = 18.sp,
        )
    val ScanPill: TextStyle
        @Composable @ReadOnlyComposable
        get() = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = LocalFontWeight.current,
            fontSize   = 10.sp,
        )
    val TodoItem: TextStyle
        @Composable @ReadOnlyComposable
        get() = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = LocalFontWeight.current,
            fontSize   = 13.sp,
        )
    val PhotoBadge: TextStyle
        @Composable @ReadOnlyComposable
        get() = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = LocalFontWeight.current,
            fontSize   = 12.sp,
        )
}
