/*
 * QuickInkTypography.kt
 *
 * QuickInk's editorial type system. The mockup specifies:
 *   - Headings: Cormorant Garamond (serif, sometimes italic)
 *   - Body editorial: Cormorant Garamond
 *   - UI labels: system sans
 *   - Handwritten previews: Caveat
 *
 * Cormorant Garamond and Caveat are Google Fonts (OFL). This file
 * defines `QuickInkFonts` using `FontFamily(Font(...))` references
 * to runtime-downloadable Google Fonts via Compose's
 * `androidx-ui-text-google-fonts` artifact.
 *
 * Until the Google Fonts dependency is wired up (one-line addition
 * to libs.versions.toml + app/build.gradle.kts — see file footer),
 * `QuickInkFonts.serif` falls back to `FontFamily.Serif` and
 * `QuickInkFonts.handwritten` falls back to `FontFamily.Cursive`.
 * Layout and weight hierarchy stay correct in the meantime.
 *
 * Mirror of iOS `QuickInkText` styles in `QuickInkTheme.swift`.
 */

package app.quickink.mobile.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Font families QuickInk uses. To switch from system fallbacks to
 * the real Google Fonts at runtime:
 *
 *   1. In `libs.versions.toml` add:
 *        compose-ui-text-google-fonts = { group = "androidx.compose.ui",
 *            name = "ui-text-google-fonts", version.ref = "compose-ui" }
 *   2. In `apps/quickink/android/app/build.gradle.kts` add:
 *        implementation(libs.compose.ui.text.google.fonts)
 *   3. Add a Google Fonts provider config to
 *        `app/src/main/res/values/font_certs.xml` (cert hashes ship
 *        with the dependency — see Compose docs).
 *   4. Replace the `FontFamily.Serif` / `FontFamily.Cursive` values
 *      below with `FontFamily(Font(GoogleFont("Cormorant Garamond"),
 *      provider))` etc.
 *
 * Until step 1-4 lands, the system fallbacks below are in effect.
 */
object QuickInkFonts {
    val serif: FontFamily       = FontFamily.Serif
    val handwritten: FontFamily = FontFamily.Cursive
    val ui: FontFamily          = FontFamily.Default
}

/**
 * Pre-baked text styles matching the mockup hierarchy. Use these
 * instead of constructing `TextStyle` calls inline — a brand pass
 * tweak lands in one place.
 *
 * Mirror of iOS `QuickInkText` enum.
 */
object QuickInkTextStyle {
    /** Onboarding hero / page hero — large serif, light weight. */
    val Display: TextStyle = TextStyle(
        fontFamily = QuickInkFonts.serif,
        fontSize   = 40.sp,
        fontWeight = FontWeight.Light,
        lineHeight = 48.sp,
    )

    /**
     * Onboarding hero title — sized to match the JSX mockup
     * (`text-[30px] leading-[1.15]`). Smaller than [Display] so the
     * two-line tagline doesn't crowd the illustration on a 390-wide
     * phone frame. Mirror of iOS `QuickInkText.onboardingTitle`.
     */
    val OnboardingTitle: TextStyle = TextStyle(
        fontFamily = QuickInkFonts.serif,
        fontSize   = 30.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 35.sp,
    )

    /** Standard page title (Settings, Library, etc.). */
    val PageTitle: TextStyle = TextStyle(
        fontFamily = QuickInkFonts.serif,
        fontSize   = 28.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 34.sp,
    )

    /** Section heading — smaller than PageTitle. */
    val Heading: TextStyle = TextStyle(
        fontFamily = QuickInkFonts.serif,
        fontSize   = 20.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 26.sp,
    )

    /** Eyebrow — uppercase + tracked, used above grouped content. */
    val Eyebrow: TextStyle = TextStyle(
        fontFamily = QuickInkFonts.ui,
        fontSize   = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.2.sp,
        lineHeight = 14.sp,
    )

    /** Body editorial copy. */
    val Body: TextStyle = TextStyle(
        fontFamily = QuickInkFonts.serif,
        fontSize   = 16.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 24.sp,
    )

    /** Italic accent body — taglines, smart suggestions. */
    val BodyItalic: TextStyle = TextStyle(
        fontFamily = QuickInkFonts.serif,
        fontSize   = 16.sp,
        fontWeight = FontWeight.Normal,
        fontStyle  = FontStyle.Italic,
        lineHeight = 24.sp,
    )

    /** Caveat handwritten — note thumbnail previews. */
    val Handwritten: TextStyle = TextStyle(
        fontFamily = QuickInkFonts.handwritten,
        fontSize   = 20.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 26.sp,
    )

    /** UI label — chip text, nav labels, small CTAs. */
    val Label: TextStyle = TextStyle(
        fontFamily = QuickInkFonts.ui,
        fontSize   = 14.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 20.sp,
    )

    /**
     * CTA label rendered in the editorial serif family. Used by
     * the onboarding "Continue" / "Continue with Google" buttons so
     * the action matches the hero typography from the mock rather
     * than dropping into SF Pro / system sans for the button text.
     */
    val CtaSerif: TextStyle = TextStyle(
        fontFamily = QuickInkFonts.serif,
        fontSize   = 18.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 22.sp,
    )

    /** Meta — timestamps, sync status, helper copy. */
    val Meta: TextStyle = TextStyle(
        fontFamily = QuickInkFonts.ui,
        fontSize   = 12.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 16.sp,
    )

    /** Caption — confidence badges, page counters. */
    val Caption: TextStyle = TextStyle(
        fontFamily = QuickInkFonts.ui,
        fontSize   = 10.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 14.sp,
    )
}
