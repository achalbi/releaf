/*
 * AppTypography.kt
 * Type roles matching design-tokens.json. Every weight reads from
 * `LocalFontWeight.current`, so the user's font-weight setting in
 * Settings hot-swaps every styled `Text` on next recomposition.
 */

package app.releaf.mobile.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

object AppTypography {
    val Eyebrow: TextStyle
        @Composable @ReadOnlyComposable
        get() = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = LocalFontWeight.current,
            fontSize = 10.sp,
            letterSpacing = 0.08.em,
        )

    val Body: TextStyle
        @Composable @ReadOnlyComposable
        get() = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = LocalFontWeight.current,
            fontSize = 15.sp,
        )

    val Meta: TextStyle
        @Composable @ReadOnlyComposable
        get() = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = LocalFontWeight.current,
            fontSize = 13.sp,
        )

    val Button: TextStyle
        @Composable @ReadOnlyComposable
        get() = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = LocalFontWeight.current,
            fontSize = 13.sp,
        )

    val SectionTitle: TextStyle
        @Composable @ReadOnlyComposable
        get() = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = LocalFontWeight.current,
            fontSize = 20.sp,
        )

    val StatNumber: TextStyle
        @Composable @ReadOnlyComposable
        get() = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = LocalFontWeight.current,
            fontSize = 32.sp,
        )

    val EditorialTitle: TextStyle
        @Composable @ReadOnlyComposable
        get() = TextStyle(
            fontFamily = FontFamily.Serif,
            fontWeight = LocalFontWeight.current,
            fontSize = 26.sp,
        )

    val PageTitle: TextStyle
        @Composable @ReadOnlyComposable
        get() = TextStyle(
            fontFamily = FontFamily.Serif,
            fontWeight = LocalFontWeight.current,
            fontSize = 24.sp,
        )

    val Tag: TextStyle
        @Composable @ReadOnlyComposable
        get() = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = LocalFontWeight.current,
            fontSize = 11.sp,
        )

    // Held over from the era when notebook surfaces wanted a calmer
    // typographic colour than the rest of the app. Now identical to
    // their non-Light counterparts since the global weight collapsed
    // the per-role weight ladder. Kept to avoid breaking call sites.
    val SectionTitleLight: TextStyle
        @Composable @ReadOnlyComposable
        get() = SectionTitle

    val EditorialTitleLight: TextStyle
        @Composable @ReadOnlyComposable
        get() = EditorialTitle

    val PageTitleLight: TextStyle
        @Composable @ReadOnlyComposable
        get() = PageTitle
}
