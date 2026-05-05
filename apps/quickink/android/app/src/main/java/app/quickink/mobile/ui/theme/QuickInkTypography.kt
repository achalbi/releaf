/*
 * QuickInkTypography.kt
 *
 * QuickInk's editorial type system (post type-system pass — see
 * TYPE_SYSTEM in repo root):
 *
 *   - Onboarding headings: Cormorant Garamond (high-contrast didone,
 *     reads "showroom"; reserved for the welcome flow only)
 *   - App headings & editorial italic: Fraunces (warmer modern
 *     serif, optically tuned for mobile display sizes — Library,
 *     Settings titles, card titles, italic taglines)
 *   - Body & UI: Inter (replaces system sans so iOS and Android
 *     render identically)
 *   - Handwritten previews: Caveat
 *
 * Two-serif rule: Cormorant and Fraunces never appear on the same
 * screen. The handoff happens once when the user finishes onboarding
 * and lands on Home. Onboarding screens use `serif` via `Display`,
 * `OnboardingTitle`, `OnboardingBody`, and `CtaSerif`. Every other
 * screen uses `appSerif` — wired up via the rerouted styles
 * (`PageTitle`, `Heading`, `CardTitle`, `BodyItalic`).
 *
 * Required font resources (drop into `app/src/main/res/font/`).
 * Source files come from Google Fonts' newer multi-axis build,
 * which names static instances like `Fraunces_72pt-Regular.ttf`.
 * scripts/install-fonts.sh extracts the 72pt-optical Fraunces
 * variants and 18pt-optical Inter variants and renames them to
 * the short Android resource names below. (Android resource
 * naming requires lowercase + underscore, no hyphens or
 * camelcase. Keeping the resource name flat means existing R.font.*
 * call sites don't change when we swap optical sizes or weight
 * pins — encode those decisions in the install script's file
 * picker instead.)
 *
 *   cormorant_garamond_*       (already present)
 *   caveat_medium              (already present)
 *   fraunces_regular.ttf       (= Fraunces_72pt-Regular)
 *   fraunces_medium.ttf        (= Fraunces_72pt-SemiBold) *
 *   fraunces_italic.ttf        (= Fraunces_72pt-Italic)
 *   fraunces_medium_italic.ttf (= Fraunces_72pt-SemiBoldItalic) *
 *   inter_regular.ttf          (= Inter_18pt-Regular)
 *   inter_medium.ttf           (= Inter_18pt-Medium)
 *
 * * The "medium" Android resource ID is fed by the SemiBold static
 *   instance because Fraunces 72pt skips Medium in its static set
 *   (Light → Regular → SemiBold → Bold). SemiBold (CSS weight 600)
 *   is the spec call for headings anyway, so this is intentional.
 *   Compose resolves FontWeight.Medium against R.font.fraunces_medium
 *   correctly — the file's internal weight class doesn't matter,
 *   the resource ID + Compose's FontWeight tag do.
 *
 * Fraunces 72pt is the display-optical — chosen for the boutique
 * magazine feel (high contrast, airy hairlines). If small-size
 * text reads thin during QA, the right fix is the variable font
 * with opsz pinned per text level — not overweighting the static
 * instance.
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
 * - `serif` → Cormorant Garamond (onboarding only).
 * - `appSerif` → Fraunces (app screens). Bundles only Regular +
 *   Medium (and italics) to keep the font payload small. Any heavier
 *   request from a call site lands on Medium thanks to Compose's
 *   own family resolution + the explicit registrations below.
 * - `ui` → Inter, replacing the previous `FontFamily.Default` so iOS
 *   and Android render identically. Only Regular and Medium are
 *   bundled — that's everything the type system uses.
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
     * Cormorant Garamond — RESERVED for onboarding screens. Used
     * by `Display`, `OnboardingTitle`, `OnboardingBody`, `CtaSerif`.
     * App screens reach for `appSerif` instead.
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
     * Fraunces — the app screens' editorial serif. Used by every
     * QuickInkTextStyle app token (`PageTitle`, `Heading`,
     * `CardTitle`, `BodyItalic`). 72pt-optical static instances
     * picked for the magazine display feel; we bundle Regular +
     * SemiBold (plus their italics) — that's enough for the type
     * system. Heavier weight requests (Bold/Heavy/Black) fall back
     * to SemiBold because the 72pt static set skips Medium and
     * SemiBold is the spec call (CSS weight 600) for headings.
     */
    val appSerif: FontFamily = FontFamily(
        // Fraunces 72pt static instances. Files in res/font/ are:
        //   fraunces_regular.ttf       — content: Fraunces_72pt-Regular
        //   fraunces_medium.ttf        — content: Fraunces_72pt-SemiBold
        //   fraunces_italic.ttf        — content: Fraunces_72pt-Italic
        //   fraunces_medium_italic.ttf — content: Fraunces_72pt-SemiBoldItalic
        // (See scripts/install-fonts.sh — fraunces_medium is fed by
        // SemiBold because Fraunces 72pt skips Medium in its static
        // set; Compose's FontWeight.Medium → R.font.fraunces_medium
        // binding does the right thing regardless of the file's
        // internal weight class.)
        Font(R.font.fraunces_regular,       FontWeight.Normal),
        Font(R.font.fraunces_medium,        FontWeight.Medium),
        Font(R.font.fraunces_italic,        FontWeight.Normal, FontStyle.Italic),
        Font(R.font.fraunces_medium_italic, FontWeight.Medium, FontStyle.Italic),
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
     * Inter — UI sans for body, labels, chips, nav, captions.
     * Replaces what was previously `FontFamily.Default` so the
     * brand carries consistently across iOS and Android. Bundled
     * weights are Regular and Medium — the only two the type
     * system uses.
     */
    val ui: FontFamily = FontFamily(
        Font(R.font.inter_regular, FontWeight.Normal),
        Font(R.font.inter_medium,  FontWeight.Medium),
    )
}

/**
 * Pre-baked text styles matching the mockup hierarchy. Use these
 * instead of constructing `TextStyle` calls inline — a brand pass
 * tweak lands in one place.
 *
 * Two-context contract (post type-system pass):
 *   - Onboarding tokens (`Display`, `OnboardingTitle`,
 *     `OnboardingBody`, `CtaSerif`) → resolve to Cormorant Garamond
 *     via [QuickInkFonts.serif].
 *   - App tokens (`PageTitle`, `Heading`, `CardTitle`, `BodyItalic`)
 *     → resolve to Fraunces via [QuickInkFonts.appSerif].
 *   - `Body` → resolves to Inter via [QuickInkFonts.ui]. App screens
 *     use it directly; onboarding screens explicitly reach for
 *     [OnboardingBody] instead.
 *   - UI tokens (`Eyebrow`, `Label`, `Meta`, `Caption`) → Inter
 *     for both contexts (UI sans is the same family everywhere).
 *
 * Mirror of iOS `QuickInkText` enum.
 */
object QuickInkTextStyle {
    /**
     * App-tier large display serif — used for the Home greeting
     * name ("Achal B I") and the Profile screen header. Fraunces
     * 72pt at 40sp rendering. Despite the legacy name, this is
     * NOT used in onboarding — the onboarding wordmark goes
     * through [OnboardingTitle] (Cormorant). Audited via grep
     * for `QuickInkTextStyle.Display`: only home + profile.
     */
    val Display: TextStyle = TextStyle(
        fontFamily = QuickInkFonts.appSerif,
        fontSize   = 40.sp,
        fontWeight = FontWeight.Normal,
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
     * App screens use [Body] (Inter) instead.
     */
    val OnboardingBody: TextStyle = TextStyle(
        fontFamily = QuickInkFonts.serif,
        fontSize   = 16.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 24.sp,
    )

    /**
     * App page title (Settings, Library, Detail, etc.) — Fraunces
     * medium. Switched from Cormorant in the type-system pass:
     * Fraunces is warmer and reads better at small mobile sizes.
     */
    val PageTitle: TextStyle = TextStyle(
        fontFamily = QuickInkFonts.appSerif,
        fontSize   = 28.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 34.sp,
    )

    /** App section heading — smaller than PageTitle. Fraunces semibold. */
    val Heading: TextStyle = TextStyle(
        fontFamily = QuickInkFonts.appSerif,
        fontSize   = 20.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 26.sp,
    )

    /** Eyebrow — uppercase + tracked, used above grouped content. Inter semibold. */
    val Eyebrow: TextStyle = TextStyle(
        fontFamily = QuickInkFonts.ui,
        fontSize   = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.2.sp,
        lineHeight = 14.sp,
    )

    /**
     * App body — Inter medium. Reading copy on app screens reads
     * as "tool" (sans) while editorial moments stay on the serif
     * via [BodyItalic] / [Heading] / [PageTitle]. Onboarding screens
     * use [OnboardingBody] for the Cormorant feel.
     */
    val Body: TextStyle = TextStyle(
        fontFamily = QuickInkFonts.ui,
        fontSize   = 16.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 24.sp,
    )

    /** Italic accent body — taglines, smart suggestions. Fraunces italic medium. */
    val BodyItalic: TextStyle = TextStyle(
        fontFamily = QuickInkFonts.appSerif,
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
     * Card title — Inter at body scale (14sp SemiBold), used for
     * note / scan thumbnail titles. Originally Fraunces, switched
     * to Inter so the home recent rail matches the library cards'
     * UI-sans treatment — note titles are functional (scannable in
     * dense grids) and the editorial serif felt precious for a
     * file list. Library cards still override with
     * `heading.copy(...)` because they sit at a different size;
     * home rail uses this token directly.
     */
    val CardTitle: TextStyle = TextStyle(
        fontFamily = QuickInkFonts.ui,
        fontSize   = 14.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 18.sp,
    )

    /** UI label — chip text, nav labels, small CTAs. Inter semibold. */
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

    /** Meta — timestamps, sync status, helper copy. Inter medium. */
    val Meta: TextStyle = TextStyle(
        fontFamily = QuickInkFonts.ui,
        fontSize   = 12.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 16.sp,
    )

    /** Caption — confidence badges, page counters. Inter medium. */
    val Caption: TextStyle = TextStyle(
        fontFamily = QuickInkFonts.ui,
        fontSize   = 10.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 14.sp,
    )
}
