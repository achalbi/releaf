/*
 * QuickInkTypography.kt
 *
 * QuickInk's editorial type system, sourced from the typography spec:
 *
 *   | Element                  | Font                | Style       |
 *   | ------------------------ | ------------------- | ----------- |
 *   | App Name                 | New York Large Bold | Hero        |
 *   | Notebook Titles          | New York Medium     | Elegant     |
 *   | Editor Body              | SF Pro Text         | Clean       |
 *   | Toolbar                  | SF Pro Medium       | Compact     |
 *   | Empty States             | New York Italic     | Emotional   |
 *   | AI Summaries             | SF Pro              | Structured  |
 *   | Sustainability Campaigns | New York Bold       | Editorial   |
 *
 * "New York" + "SF Pro" are Apple system fonts. The Android side
 * uses the closest visual analogs delivered via Compose's
 * downloadable Google Fonts:
 *
 *   - Cormorant Garamond → New York stand-in. Bundled in
 *     `res/font/cormorant_garamond_*.ttf`. Editorial face with a
 *     warm, slightly hand-cut character; renders the App Name,
 *     sustainability campaigns, empty states, and onboarding hero.
 *   - Inter → SF Pro stand-in. The standard product-UI sans, with
 *     proportions tuned for screen rendering. Replaces the previous
 *     `FontFamily.SansSerif` (Roboto) for a tighter, more refined
 *     read across body, labels, section headings.
 *
 * Caveat (handwritten) stays bundled — only used for note-thumbnail
 * OCR snippets and the editor's handwritten-title affordance.
 *
 * Loading: Cormorant Garamond is bundled so editorial type paints on
 * first frame. Compose downloads the Inter weights it needs the first
 * time the app paints; subsequent launches resolve from disk cache.
 * While the Inter download is in flight the system fallback
 * (FontFamily.SansSerif) renders, so the UI never blocks. Cert hashes
 * for the Play Services font provider live in `res/values/font_certs.xml`.
 *
 * Mirror of iOS `QuickInkText` styles in `QuickInkTheme.swift`.
 */

package app.quickink.mobile.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font as GoogleFontFont
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.sp
import app.quickink.mobile.R

/**
 * Provider for Compose downloadable Google Fonts. Routes through
 * Google Play services' on-device font provider, verified against
 * the cert pins in `R.array.com_google_android_gms_fonts_certs`
 * (sourced verbatim from the official Compose samples — see
 * `res/values/font_certs.xml`).
 *
 * Single shared instance — every `GoogleFontFont(...)` call in
 * [QuickInkFonts] points back here so the device caches the
 * provider and dedup-resolves repeated weight requests.
 */
private val googleFontsProvider: GoogleFont.Provider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage   = "com.google.android.gms",
    certificates      = R.array.com_google_android_gms_fonts_certs,
)

/** Inter — Android's SF Pro stand-in (modern product-UI sans). */
private val interFont = GoogleFont("Inter")

/**
 * Font families QuickInk uses.
 *
 * - `serif` → Cormorant Garamond, bundled in `res/font/`. Used for
 *   App Name (`Display`), Sustainability Campaigns (`Editorial`),
 *   Empty States (`BodyItalic`), and onboarding hero copy. Bundled
 *   rather than downloaded so the editorial type renders without a
 *   first-launch Google Fonts roundtrip.
 * - `ui` → Inter via downloadable Google Fonts. App body, labels,
 *   section headings, card titles, captions, AI summaries.
 * - `handwritten` → Caveat (Medium only). Bundled.
 *
 * Spec deviation: the original spec mapped Notebook Titles
 * (CardTitle) to New York Medium. On screen the serif Medium 14sp
 * read as too literary for what is functionally a file-name list,
 * so CardTitle is sans now. App Name, Sustainability Campaigns,
 * Empty States, and onboarding still follow the spec.
 */
object QuickInkFonts {
    /**
     * Editorial serif — Cormorant Garamond bundled in
     * `res/font/cormorant_garamond_*.ttf`. Powers App Name
     * (`Display`), Sustainability Campaigns (`Editorial`), Empty
     * States (`BodyItalic`), and the onboarding hero. Five upright
     * weights (Light → Bold) plus Normal and Medium italics — covers
     * every active token plus headroom. Light + Light Italic +
     * SemiBold Italic + Bold Italic are also on disk if a future
     * style needs them.
     */
    val serif: FontFamily = FontFamily(
        Font(R.font.cormorant_garamond_light,         FontWeight.Light),
        Font(R.font.cormorant_garamond_regular,       FontWeight.Normal),
        Font(R.font.cormorant_garamond_medium,        FontWeight.Medium),
        Font(R.font.cormorant_garamond_semibold,      FontWeight.SemiBold),
        Font(R.font.cormorant_garamond_bold,          FontWeight.Bold),
        Font(R.font.cormorant_garamond_italic,        FontWeight.Normal, FontStyle.Italic),
        Font(R.font.cormorant_garamond_medium_italic, FontWeight.Medium, FontStyle.Italic),
    )

    /**
     * UI sans — Inter via downloadable Google Fonts. Product-UI
     * standard, with screen-tuned proportions that read tighter and
     * more refined than Roboto. Powers Editor Body, Toolbar, AI
     * Summaries, section headings, captions, and meta copy.
     */
    val ui: FontFamily = FontFamily(
        GoogleFontFont(interFont, googleFontsProvider, FontWeight.Normal),
        GoogleFontFont(interFont, googleFontsProvider, FontWeight.Medium),
        GoogleFontFont(interFont, googleFontsProvider, FontWeight.SemiBold),
        GoogleFontFont(interFont, googleFontsProvider, FontWeight.Bold),
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
}

/**
 * Pre-baked text styles matching the typography spec. Use these
 * instead of constructing `TextStyle` calls inline — a brand pass
 * tweak lands in one place.
 *
 * Family contract (mostly mapped to the spec table):
 *   - Editorial serif (Roboto Serif via [QuickInkFonts.serif]):
 *     [Display] (App Name), [Editorial] (Sustainability Campaigns),
 *     [BodyItalic] (Empty States), [OnboardingTitle],
 *     [OnboardingBody], [CtaSerif].
 *   - Sans (Inter via [QuickInkFonts.ui]): [Body] (Editor Body),
 *     [CardTitle] (was Notebook Titles per spec, moved to sans —
 *     see token doc), [Label] (Toolbar), [PageTitle], [Heading],
 *     [Eyebrow], [Meta],
 *     [Caption]. AI Summaries also resolve here via [Body].
 *   - Handwritten ([QuickInkFonts.handwritten]): [Handwritten] only.
 *
 * Mirror of iOS `QuickInkText` enum.
 */
object QuickInkTextStyle {
    /**
     * App Name — "Hero" tier. Roboto Serif Light at 28sp with
     * neutral tracking. Light weight reads elegant and unhurried
     * at this size, more wordmark than running text — Bold/SemiBold
     * felt heavy at 32-40sp. iOS mirror uses New York Light via
     * `Font.system(size: 28, weight: .light, design: .serif)`.
     * Audited via grep for `QuickInkTextStyle.Display`: only home +
     * profile.
     */
    val Display: TextStyle = TextStyle(
        fontFamily    = QuickInkFonts.serif,
        fontSize      = 28.sp,
        fontWeight    = FontWeight.Bold,
        lineHeight    = 34.sp,
        letterSpacing = 0.sp,
    )

    /**
     * Sustainability Campaigns — "Editorial" tier. Roboto Serif
     * Medium at 16sp. The eco-card headline ("8 pages saved"),
     * trees-saved milestones, and any future campaign moments
     * resolve here.
     * Distinct from [Heading] (sans) so productivity headings stay
     * clearly functional while editorial sustainability moments
     * carry the brand serif voice.
     */
    val Editorial: TextStyle = TextStyle(
        fontFamily    = QuickInkFonts.serif,
        fontSize      = 16.sp,
        fontWeight    = FontWeight.Medium,
        lineHeight    = 22.sp,
        letterSpacing = (-0.1).sp,
    )

    /**
     * Onboarding hero title — sized to match the JSX mockup
     * (`text-[30px] leading-[1.15]`). Smaller than [Display] so the
     * two-line tagline doesn't crowd the illustration on a 390-wide
     * phone frame. Editorial serif (Roboto Serif).
     */
    val OnboardingTitle: TextStyle = TextStyle(
        fontFamily = QuickInkFonts.serif,
        fontSize   = 22.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 28.sp,
    )

    /**
     * Onboarding body — editorial serif (Roboto Serif) Medium.
     * Used by the onboarding scaffold's tagline + SignInScreen's
     * lead copy where the editorial showroom feel matters more than
     * density. App screens use [Body] (Inter sans) instead.
     */
    val OnboardingBody: TextStyle = TextStyle(
        fontFamily = QuickInkFonts.serif,
        fontSize   = 16.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 24.sp,
    )

    /**
     * App page title (Settings, Library, Detail, etc.) — Inter
     * SemiBold at 20sp. Sans, not serif: the editorial serif is
     * reserved for App Name + sustainability campaigns + onboarding
     * hero, so app-screen page titles stay on the product-UI sans
     * for a confident functional read. Slightly negative letter-
     * spacing tightens the wordmark feel.
     */
    val PageTitle: TextStyle = TextStyle(
        fontFamily    = QuickInkFonts.ui,
        fontSize      = 20.sp,
        fontWeight    = FontWeight.SemiBold,
        lineHeight    = 26.sp,
        letterSpacing = (-0.2).sp,
    )

    /**
     * App section heading ("Recents", "Quick categories", etc.)
     * — Inter SemiBold at 16sp. Sans for the same reason as
     * [PageTitle]: section headings on app screens read as functional
     * UI rather than literary chapter titles. Sustainability-tier
     * editorial moments use [Editorial] instead.
     */
    val Heading: TextStyle = TextStyle(
        fontFamily    = QuickInkFonts.ui,
        fontSize      = 16.sp,
        fontWeight    = FontWeight.SemiBold,
        lineHeight    = 22.sp,
        letterSpacing = (-0.1).sp,
    )

    /** Eyebrow — uppercase + tracked, used above grouped content. Inter sans semibold. */
    val Eyebrow: TextStyle = TextStyle(
        fontFamily    = QuickInkFonts.ui,
        fontSize      = 11.sp,
        fontWeight    = FontWeight.SemiBold,
        letterSpacing = 1.2.sp,
        lineHeight    = 14.sp,
    )

    /**
     * Editor Body + AI Summaries — "Clean" / "Structured" tiers.
     * Inter Medium at 16sp. App-screen reading copy and Haiku-
     * generated summary blocks both resolve here. The editorial
     * serif is reserved for [Display] / [Editorial] / [CardTitle]
     * / [BodyItalic] taglines.
     */
    val Body: TextStyle = TextStyle(
        fontFamily = QuickInkFonts.ui,
        fontSize   = 16.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 24.sp,
    )

    /**
     * Empty States — "Emotional" tier. Roboto Serif italic Medium
     * at 16sp. Used by no-content prompts and SmartSuggestion-style
     * taglines where a literary, slightly hand-held tone reads
     * better than a clinical sans.
     */
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
     * Card title — Inter SemiBold at 14sp, used for note/scan
     * thumbnail titles in the home recent rail and the library
     * grid. Briefly switched to Roboto Serif Medium per the
     * "Notebook Titles → New York Medium" spec row, but on screen
     * the serif read as too literary at 14sp — too much editorial
     * weight on what is functionally a file-name list. Back on
     * sans for the productivity-app feel; SemiBold gives it a
     * notch more emphasis than [Label] (Medium) so titles still
     * sit clearly above their captions.
     */
    val CardTitle: TextStyle = TextStyle(
        fontFamily = QuickInkFonts.ui,
        fontSize   = 14.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 18.sp,
    )

    /**
     * Toolbar — "Compact" tier. Inter Medium at 14sp. Used by chip
     * text, nav labels, and small CTAs. Was SemiBold; dropped to
     * Medium per the spec's "SF Pro Medium" callout — toolbar
     * affordances should read as compact and unobtrusive, not as
     * shouting bold copy.
     */
    val Label: TextStyle = TextStyle(
        fontFamily = QuickInkFonts.ui,
        fontSize   = 14.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 20.sp,
    )

    /**
     * CTA label rendered in the editorial serif family. Used by
     * the onboarding "Continue" / "Continue with Google" buttons so
     * the action matches the serif hero typography rather than
     * dropping into UI sans for the button text. Stays on
     * [QuickInkFonts.serif] (Roboto Serif) — onboarding-only.
     */
    val CtaSerif: TextStyle = TextStyle(
        fontFamily = QuickInkFonts.serif,
        fontSize   = 14.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 18.sp,
    )

    /** Meta — timestamps, sync status, helper copy. Inter sans medium. */
    val Meta: TextStyle = TextStyle(
        fontFamily = QuickInkFonts.ui,
        fontSize   = 12.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 16.sp,
    )

    /** Caption — confidence badges, page counters. Inter sans medium. */
    val Caption: TextStyle = TextStyle(
        fontFamily = QuickInkFonts.ui,
        fontSize   = 10.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 14.sp,
    )
}
