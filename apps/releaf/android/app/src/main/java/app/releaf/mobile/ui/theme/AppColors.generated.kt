// GENERATED — DO NOT EDIT.
// Run `node design-system/scripts/generate-tokens.mjs` to regenerate.
//
// Source: design-system/design-tokens.json

package app.releaf.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

object AppColors {

    // neutral — 11 stops
    val Neutral50 = Color(0xFFFAF6F0)
    val Neutral100 = Color(0xFFEFE7DA)
    val Neutral200 = Color(0xFFE0D2BA)
    val Neutral300 = Color(0xFFCEBB9D)
    val Neutral400 = Color(0xFFA99A84)
    val Neutral500 = Color(0xFF8A7C6D)
    val Neutral600 = Color(0xFF5F5245)
    val Neutral700 = Color(0xFF463C31)
    val Neutral800 = Color(0xFF332B22)
    val Neutral900 = Color(0xFF241D17)
    val Neutral950 = Color(0xFF120E0A)

    // coral — 4 stops
    val Coral50 = Color(0xFFFEF4EF)
    val Coral100 = Color(0xFFFCEAE0)
    val Coral500 = Color(0xFFE07856)
    val Coral700 = Color(0xFFC65A3E)

    // success — 4 stops
    val Success50 = Color(0xFFF1F7F4)
    val Success100 = Color(0xFFE3F1E8)
    val Success600 = Color(0xFF4C9A6A)
    val Success700 = Color(0xFF36754F)

    // info — 4 stops
    val Info50 = Color(0xFFEFF5FB)
    val Info100 = Color(0xFFE1ECF8)
    val Info600 = Color(0xFF2E6FB5)
    val Info700 = Color(0xFF23548B)

    // warning — 4 stops
    val Warning50 = Color(0xFFFDF7E5)
    val Warning100 = Color(0xFFFBEECD)
    val Warning600 = Color(0xFFA87418)
    val Warning700 = Color(0xFF7F5711)

    // danger — 4 stops
    val Danger50 = Color(0xFFFDEEE9)
    val Danger100 = Color(0xFFF6D1C9)
    val Danger600 = Color(0xFFC8432E)
    val Danger700 = Color(0xFF983224)

    // Leaf theme variants (flat — no dark variant yet)

    // coral theme — primary / deep / bgSoft (10%) / borderSoft (30%)
    val ThemeCoralPrimary = Color(0xFFE07856)
    val ThemeCoralDeep = Color(0xFFC65A3E)
    val ThemeCoralBgSoft = Color(0x1AE07856)
    val ThemeCoralBorderSoft = Color(0x4DE07856)

    // green theme — primary / deep / bgSoft (10%) / borderSoft (30%)
    val ThemeGreenPrimary = Color(0xFF7AA874)
    val ThemeGreenDeep = Color(0xFF5B8C52)
    val ThemeGreenBgSoft = Color(0x1A7AA874)
    val ThemeGreenBorderSoft = Color(0x4D7AA874)

    // yellow theme — primary / deep / bgSoft (10%) / borderSoft (30%)
    val ThemeYellowPrimary = Color(0xFFF4C430)
    val ThemeYellowDeep = Color(0xFFE8B923)
    val ThemeYellowBgSoft = Color(0x1AF4C430)
    val ThemeYellowBorderSoft = Color(0x4DF4C430)

    // dry theme — primary / deep / bgSoft (10%) / borderSoft (30%)
    val ThemeDryPrimary = Color(0xFFB8956A)
    val ThemeDryDeep = Color(0xFF8B7355)
    val ThemeDryBgSoft = Color(0x1AB8956A)
    val ThemeDryBorderSoft = Color(0x4DB8956A)

    // Roles (theme-aware — resolve per recomposition via isSystemInDarkTheme())

    /** App background — warm cream (light, matches Releaf Branding template) / warm dark (neutral900, dark) */
    val Canvas: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) Color(0xFF241D17) else Color(0xFFF5EEDF)

    /** Card / input background @ 90% opacity — cream in light, neutral800 in dark */
    val Card: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) Color(0xE6332B22) else Color(0xE6FFFAF4)

    /** Solid card fill for opaque contexts */
    val CardSolid: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) Color(0xFF332B22) else Color(0xFFFFFAF4)

    /** Subtle alt surface — neutral100 in light, neutral700 in dark */
    val Subtle: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) Color(0xFF463C31) else Color(0xFFEFE7DA)

    /** Slightly darker cream than canvas — used on disabled buttons and subtle fills. Matches Releaf Branding --muted token. */
    val Muted: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) Color(0xFF332B22) else Color(0xFFEBE4D3)

    /** Input field fill — 5% opacity of the primary text color so the field reads as a soft tan well on the cream canvas. Matches Releaf Branding --input-background. */
    val InputBg: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) Color(0x0DFAF6F0) else Color(0x0D463C31)

    /** Body + headings — neutral700 in light, neutral100 in dark */
    val TextPrimary: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) Color(0xFFEFE7DA) else Color(0xFF463C31)

    /** Muted text, meta labels — neutral600 in light, neutral300 in dark */
    val TextSecondary: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) Color(0xFFCEBB9D) else Color(0xFF5F5245)

    /** Very muted, placeholder — neutral500 in light, neutral400 in dark */
    val TextTertiary: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) Color(0xFFA99A84) else Color(0xFF8A7C6D)

    /** Text on coral / action pill — cream in both themes (matches Releaf Branding template; warmer than pure white on the coral fill) */
    val OnAccent: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) Color(0xFFF5EEDF) else Color(0xFFF5EEDF)

    /** Card/input border @ ~12% alpha — warm brown on light, neutral50 on dark */
    val BorderDefault: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) Color(0x1FFAF6F0) else Color(0x1F503E2D)

    /** Stronger border @ ~24% alpha */
    val BorderStrong: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) Color(0x3DFAF6F0) else Color(0x3D503E2D)

    /** Primary accent — coral stays coral across themes (matches Releaf Branding template) */
    val Coral: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) Color(0xFFE07856) else Color(0xFFE07856)

    /** Coral tint — pale apricot in light, dark coral wash in dark */
    val CoralSoft: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) Color(0xFF3A2118) else Color(0xFFFCEAE0)

    /** Pressed coral — darker in light, lighter in dark (pressed direction inverts). Light hex matches Releaf Branding template. */
    val CoralDeep: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) Color(0xFFFA9975) else Color(0xFFC65A3E)

    /** Coral outline button border + text */
    val CoralOutline: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) Color(0xFFEF8B66) else Color(0xFFE07856)

    /** Install / sync-success primary action */
    val Green: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) Color(0xFF7FC19A) else Color(0xFF1E5943)

    /** Green accent soft tint */
    val GreenSoft: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) Color(0xFF20352B) else Color(0xFFD9EDE2)

    /** Success eyebrow, sync labels */
    val GreenText: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) Color(0xFF7FC19A) else Color(0xFF1E5943)

    /** Active status text */
    val Success: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) Color(0xFF7FC19A) else Color(0xFF4C9A6A)

    /** Active pill background */
    val SuccessSoft: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) Color(0xFF1B2E23) else Color(0xFFE3F1E8)

    /** Count-pill text */
    val Info: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) Color(0xFF7FA7D4) else Color(0xFF2E6FB5)

    /** Count-pill background */
    val InfoSoft: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) Color(0xFF17283D) else Color(0xFFE1ECF8)

    /** Date-based tag text */
    val Warning: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) Color(0xFFD9A45C) else Color(0xFFA87418)

    /** Date-based tag background */
    val WarningSoft: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) Color(0xFF2E2516) else Color(0xFFFBEECD)

    /** Archived / neutral tag text */
    val Neutral: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) Color(0xFFCEBB9D) else Color(0xFF5F5245)

    /** Archived / neutral tag background */
    val NeutralSoft: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) Color(0xFF332B22) else Color(0xFFEFE7DA)

    /** Destructive + photo count badge */
    val Danger: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) Color(0xFFE87058) else Color(0xFFC8432E)

    /** Primary pill button background — inverts direction in dark */
    val ActionPrimary: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) Color(0xFFFAF6F0) else Color(0xFF1A1A1A)

    val ActionPrimaryPressed: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) Color(0xFFFFFFFF) else Color(0xFF000000)

    /** Text on primary pill — white on black (light), neutral900 on cream (dark) */
    val OnPrimary: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) Color(0xFF241D17) else Color(0xFFFFFFFF)

    /** App-wide dot-grid tint — warm brown @ 35% in light; neutral50 @ ~5% in dark (texture fades back) */
    val DotGrid: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) Color(0x0DFAF6F0) else Color(0x59503E2D)

    // Aliases — older call sites use different names for the same role.

    /** Alias of [OnAccent] */
    val TextOnAccent: Color
        @Composable @ReadOnlyComposable
        get() = OnAccent
}
