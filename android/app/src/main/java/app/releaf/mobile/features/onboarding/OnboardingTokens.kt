/*
 * OnboardingTokens.kt
 *
 * Literal colour/typography tokens extracted from
 * docs/onboarding/source/onboarding.css. The wizard intentionally
 * uses fixed coral values (rather than the themeable [AppAccent]) so
 * the first-run experience has a consistent brand identity regardless
 * of the active accent palette.
 */

package app.releaf.mobile.features.onboarding

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

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
    val Headline = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.ExtraBold,
        fontSize   = 23.sp,
        lineHeight = 29.sp,
    )
    val Body = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize   = 15.sp,
        lineHeight = 24.sp,
    )
    val Badge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize   = 11.sp,
        letterSpacing = 0.6.sp,
    )
    val Button = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize   = 14.sp,
    )
    val Skip = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize   = 12.sp,
    )
    val CalendarHeader = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize   = 11.sp,
        letterSpacing = 0.5.sp,
    )
    val CalendarNumber = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.ExtraBold,
        fontSize   = 38.sp,
        lineHeight = 38.sp,
    )
    val CtaLabel = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize   = 13.sp,
        lineHeight = 18.sp,
    )
    val ScanPill = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize   = 10.sp,
    )
    val TodoItem = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize   = 13.sp,
    )
    val PhotoBadge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize   = 12.sp,
    )
}
