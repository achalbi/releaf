/*
 * QuickInkTypography.kt
 *
 * QuickInk's editorial type system:
 *
 *   - All serif headings (onboarding + app): Cormorant Garamond
 *     (high-contrast didone, editorial showroom feel — used by
 *     onboarding heroes, page titles, section headings, italic
 *     taglines, and the home greeting name)
 *   - Body & UI: system sans (Roboto on Android via
 *     `FontFamily.SansSerif`; iOS pairs to SF Pro). Inter was
 *     bundled here for cross-platform identical rendering, but the
 *     simpler system-sans pairing matches the Releaf sibling app
 *     and drops ~200KB of bundled fonts.
 *   - Handwritten previews: Caveat
 *
 * Single-serif system. Heading weight steps up to Bold/SemiBold so
 * Cormorant carries the visual heft Fraunces previously gave the
 * app screens — see weight notes on each style below.
 *
 * Required font resources (drop into `app/src/main/res/font/`):
 *
 *   cormorant_garamond_*  (Light/Regular/Medium/SemiBold/Bold + italics)
 *   caveat_medium
 *
 * Mirror of iOS `QuickInkText` styles in `QuickInkTheme.swift`.
 */

package app.quickink.mobile.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.quickink.mobile.R

/**
 * Font families QuickInk uses.
 *
 * - `serif` → Cormorant Garamond, bundled. The single editorial
 *   serif — onboarding heroes, page titles, section headings,
 *   italic taglines, and the home greeting name all resolve here.
 * - `ui` → `FontFamily.SansSerif` (system sans, Roboto on Android).
 *   Inter was bundled here for cross-platform parity with iOS but
 *   the simpler system-sans pairing matches the Releaf sibling app
 *   and drops ~200KB of bundled fonts. Existing call sites still
 *   reference `QuickInkFonts.ui` so no ripple edits are needed.
 * - `handwritten` → Caveat (Medium only).
 *
 * Android resource naming requires lowercase + underscores, so the
 * on-disk filenames differ from Google Fonts' shipped PascalCase
 * names. All weight/style variants the families ship with are
 * registered, so Compose serves any `(FontWeight, FontStyle)` pair
 * the call site asks for without needing to interpolate.
 */
object QuickInkFonts {
    /**
     * Cormorant Garamond — the only serif in the system. Used by
     * every serif token: `Display`, `PageTitle`, `Heading`,
     * `BodyItalic`, `OnboardingTitle`, `OnboardingBody`, `CtaSerif`.
     */
    val serif: FontFamily = FontFamily(
        // Upright
        Font(R.font.cormorant_garamond_light,    FontWeight.Light),
        Font(R.font.cormorant_garamond_regular,  FontWeight.Normal),
        Font(R.font.cormorant_garamond_medium,   FontWeight.Medium),
        Font(R.font.cormorant_garamond_semibold, FontWeight.SemiBold),
        Font(R.font.cormorant_garamond_bold,     FontWeight.Bold),
        // Italic
        Font(R.font.cormorant_garamond_light_italic,    FontWeight.Light,    FontStyle.Italic),
        Font(R.font.cormorant_garamond_italic,          FontWeight.Normal,   FontStyle.Italic),
        Font(R.font.cormorant_garamond_medium_italic,   FontWeight.Medium,   FontStyle.Italic),
        Font(R.font.cormorant_garamond_semibold_italic, FontWeight.SemiBold, FontStyle.Italic),
        Font(R.font.cormorant_garamond_bold_italic,     FontWeight.Bold,     FontStyle.Italic),
    )

    /**
     * Caveat for handwritten preview snippets (Library cards' OCR
     * thumbnails, NoteEditor handwritten title affordances). Only
     * the Medium weight is bundled — that's what the iOS side
     * uses, and the call sites all consume `QuickInkTextStyle.Handwritten`
     * at a single 20sp / Medium variant. If a thinner or bolder
     * stroke is needed later, drop the additional weights into
     * `res/font/caveat_*` and register them here the same way.
     */
    val handwritten: FontFamily = FontFamily(
        Font(R.font.caveat_medium, FontWeight.Medium),
    )

    /**
     * UI sans for body, labels, chips, nav, captions. Aliased to
     * `FontFamily.SansSerif` (Roboto on Android) so the call sites
     * that reference `QuickInkFonts.ui` don't need to change.
     */
    val ui: FontFamily = FontFamily.SansSerif
}

/**
 * Pre-baked text styles matching the mockup hierarchy. Use these
 * instead of constructing `TextStyle` calls inline — a brand pass
 * tweak lands in one place.
 *
 * Family contract:
 *   - All serif tokens (`Display`, `PageTitle`, `Heading`,
 *     `BodyItalic`, `OnboardingTitle`, `OnboardingBody`, `CtaSerif`)
 *     → Cormorant Garamond via [QuickInkFonts.serif].
 *   - `Body` → system sans via [QuickInkFonts.ui]. App screens use
 *     it directly; onboarding screens explicitly reach for
 *     [OnboardingBody] instead.
 *   - UI tokens (`Eyebrow`, `Label`, `CardTitle`, `Meta`, `Caption`)
 *     → system sans via [QuickInkFonts.ui].
 *
 * Mirror of iOS `QuickInkText` enum.
 */
object QuickInkTextStyle {
    /**
     * App-tier large display serif — used for the Home greeting
     * name ("Achal B I") and the Profile screen header. Cormorant
     * Garamond Bold at 40sp. Audited via grep for
     * `QuickInkTextStyle.Display`: only home + profile.
     */
    val Display: TextStyle = TextStyle(
        fontFamily = QuickInkFonts.serif,
        fontSize   = 40.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 48.sp,
    )

    /**
     * Onboarding hero title — sized to match the JSX mockup
     * (`text-[30px] leading-[1.15]`). Smaller than [Display] so the
     * two-line tagline doesn't crowd the illustration on a 390-wide
     * phone frame. Cormorant Garamond.
     */
    val OnboardingTitle: TextStyle = TextStyle(
        fontFamily = QuickInkFonts.serif,
        fontSize   = 30.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 35.sp,
    )

    /**
     * Onboarding body — Cormorant Garamond medium. Used by the
     * onboarding scaffold's tagline + SignInScreen's lead copy
     * where the editorial showroom feel matters more than density.
     * App screens use [Body] (system sans) instead.
     */
    val OnboardingBody: TextStyle = TextStyle(
        fontFamily = QuickInkFonts.serif,
        fontSize   = 16.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 24.sp,
    )

    /**
     * App page title (Settings, Library, Detail, etc.) — Cormorant
     * Garamond SemiBold. Bumped from Medium when Fraunces was
     * dropped: Cormorant runs lighter than Fraunces at the same
     * weight, so SemiBold is needed to match the Fraunces-Medium
     * visual heft on small mobile displays.
     */
    val PageTitle: TextStyle = TextStyle(
        fontFamily = QuickInkFonts.serif,
        fontSize   = 28.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 34.sp,
    )

    /**
     * App section heading — smaller than PageTitle. Cormorant
     * Garamond Bold. Bumped from SemiBold when Fraunces was dropped
     * for the same reason as [PageTitle] — Cormorant Bold is the
     * weight that carries against pale tile backgrounds.
     */
    val Heading: TextStyle = TextStyle(
        fontFamily = QuickInkFonts.serif,
        fontSize   = 20.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 26.sp,
    )

    /** Eyebrow — uppercase + tracked, used above grouped content. System sans semibold. */
    val Eyebrow: TextStyle = TextStyle(
        fontFamily = QuickInkFonts.ui,
        fontSize   = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.2.sp,
        lineHeight = 14.sp,
    )

    /**
     * App body — system sans medium (Roboto on Android). Reading
     * copy on app screens reads as "tool" (sans) while editorial
     * moments stay on the serif via [BodyItalic] / [Heading] /
     * [PageTitle]. Onboarding screens use [OnboardingBody] for the
     * Cormorant feel.
     */
    val Body: TextStyle = TextStyle(
        fontFamily = QuickInkFonts.ui,
        fontSize   = 16.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 24.sp,
    )

    /** Italic accent body — taglines, smart suggestions. Cormorant italic medium. */
    val BodyItalic: TextStyle = TextStyle(
        fontFamily = QuickInkFonts.serif,
        fontSize   = 16.sp,
        fontWeight = FontWeight.Medium,
        fontStyle  = FontStyle.Italic,
        lineHeight = 24.sp,
    )

    /**
     * Caveat handwritten — note thumbnail previews.
     *
     * Weight is `Medium` because that's the only Caveat variant
     * bundled (matches iOS, which uses `Caveat-Medium.ttf`). Asking
     * Compose for `FontWeight.Normal` here would force it to
     * synthesize a thinner stroke from the Medium glyphs — looks
     * faded and inconsistent with the iOS rendering. Pinning to
     * Medium keeps the call site honest about what's actually
     * available in the bundled file.
     */
    val Handwritten: TextStyle = TextStyle(
        fontFamily = QuickInkFonts.handwritten,
        fontSize   = 20.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 26.sp,
    )

    /**
     * Card title — system sans at body scale (14sp SemiBold), used
     * for note / scan thumbnail titles. Sans (not the serif) so the
     * home recent rail matches the library cards' UI-sans treatment
     * — note titles are functional (scannable in dense grids) and
     * the editorial serif felt precious for a file list. Library
     * cards still override with `heading.copy(...)` because they
     * sit at a different size; home rail uses this token directly.
     */
    val CardTitle: TextStyle = TextStyle(
        fontFamily = QuickInkFonts.ui,
        fontSize   = 14.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 18.sp,
    )

    /** UI label — chip text, nav labels, small CTAs. System sans semibold. */
    val Label: TextStyle = TextStyle(
        fontFamily = QuickInkFonts.ui,
        fontSize   = 14.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 20.sp,
    )

    /**
     * CTA label rendered in the onboarding serif family. Used by
     * the onboarding "Continue" / "Continue with Google" buttons so
     * the action matches the Cormorant hero typography from the
     * mock rather than dropping into UI sans for the button text.
     * Stays on [QuickInkFonts.serif] (Cormorant) — it's onboarding-
     * only.
     */
    val CtaSerif: TextStyle = TextStyle(
        fontFamily = QuickInkFonts.serif,
        fontSize   = 18.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 22.sp,
    )

    /** Meta — timestamps, sync status, helper copy. System sans medium. */
    val Meta: TextStyle = TextStyle(
        fontFamily = QuickInkFonts.ui,
        fontSize   = 12.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 16.sp,
    )

    /** Caption — confidence badges, page counters. System sans medium. */
    val Caption: TextStyle = TextStyle(
        fontFamily = QuickInkFonts.ui,
        fontSize   = 10.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 14.sp,
    )
}
