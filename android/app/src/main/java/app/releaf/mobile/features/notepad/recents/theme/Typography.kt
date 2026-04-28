package app.releaf.mobile.features.notepad.recents.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Typography tokens for Releaf Notepad.
 *
 * Two families:
 *  - FontFamily.Default for sans-serif body, labels, and meta text
 *  - FontFamily.Serif for the H1, theme names, and stat numbers
 *
 * If the host app ships a custom serif (e.g. EB Garamond), swap it here.
 */
object Type {

    // Brand label "RELEAF · NOTEPAD" — micro-ish but slightly larger
    // for the brand strip. Brief allows only Regular/Medium weights.
    val BrandLabel = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 1.6.sp,
    )

    // H1 "Recent garden"
    val H1 = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Normal,
        fontSize = 34.sp,
    )

    // Section labels: "TODAY", "THIS WEEK", "EARLIER IN APRIL"
    // — kept around for backwards-compat; new section labels use
    // [MicroWide] which matches the brief's microWide slot.
    val SectionLabel = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 1.4.sp,
    )

    // Brief: micro 10/medium/kerned 1.3 — uppercase eyebrows
    val MicroLabel = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        letterSpacing = 1.3.sp,
    )

    // Brief: microWide 9/medium/kerned 1.5 — date stamps, ALL CAPS
    // section labels, "EARLIER IN APRIL" eyebrows.
    val MicroWide = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 9.sp,
        letterSpacing = 1.5.sp,
    )

    // Stat numbers ("12", "22", etc.) — serif at 18sp/Medium so the
    // numerals carry the same editorial feel as the H1, theme
    // names, and earlier-card titles. Pairs with the [MicroLabel]
    // sans eyebrow underneath.
    val StatNumber = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp,
    )

    // Theme name in hero ("jatamansi")
    val ThemeName = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Normal,
        fontSize = 24.sp,
    )

    // Pill/chip text
    val Chip = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
    )

    // Brief's body = 14 / regular.
    val Body = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
    )

    // Brief's bodySmall = 13 / regular.
    val BodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
    )

    // Brief's caption = 12 / regular.
    val Caption = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
    )

    // Heaviest body weight available per brief (Medium, never SemiBold).
    val BodyBold = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
    )

    // Card title in inset / earlier grid
    val CardTitle = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
    )

    // CTA "Open page X →" / "Add a page →" — Medium, not SemiBold,
    // per the brief's two-weights rule.
    val Cta = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
    )
}
