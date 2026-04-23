/*
 * AppTypography.kt
 * Type roles matching design-tokens.json.
 */

package app.releaf.mobile.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

object AppTypography {
    val Eyebrow = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 10.sp,
        letterSpacing = 0.08.em,
    )

    val Body = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
    )

    val Meta = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
    )

    val Button = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
    )

    val SectionTitle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
    )

    val StatNumber = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
    )

    val EditorialTitle = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Medium,
        fontSize = 26.sp,
    )

    val PageTitle = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
    )

    val Tag = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
    )

    // ---------------------------------------------------------------
    // "Light" variants — same family + size as the heavy roles, but
    // dropped a step in weight. Used by the notebook tab and its
    // drill-in surfaces (chapters / pages / page editor) to give that
    // half of the app a calmer, more list-driven typographic colour.
    // Other surfaces (notepad, settings, etc.) keep the heavy roles.
    // ---------------------------------------------------------------

    /** Bold → Medium. Use for list-row headlines and grouping cards. */
    val SectionTitleLight = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
    )

    /** Medium → Normal. Use for tab-level page headings. */
    val EditorialTitleLight = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Normal,
        fontSize = 26.sp,
    )

    /** SemiBold → Medium. Use for in-page editor titles. */
    val PageTitleLight = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Medium,
        fontSize = 24.sp,
    )
}
