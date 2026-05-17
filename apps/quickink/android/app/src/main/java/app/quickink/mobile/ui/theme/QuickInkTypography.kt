/*
 * QuickInkTypography.kt
 *
 * QuickInk's editorial type system, sourced from the typography
 * token JSON (see `design/` token export). Three bundled families:
 *
 *   - Lora               → editorial serif. Display, onboarding hero,
 *                          sustainability headlines, empty-state
 *                          callouts, serif CTAs.
 *   - Plus Jakarta Sans  → product-UI sans. Body, labels, eyebrows,
 *                          metadata, captions, section headings,
 *                          page titles, card titles.
 *   - Caveat             → handwritten OCR previews (Medium only).
 *
 * All three are bundled under `res/font/` — no downloadable-fonts
 * roundtrip, editorial type paints on first frame.
 *
 * Mirror of iOS `QuickInkText` styles in `QuickInkTheme.swift`.
 * The iOS side has not been updated to Lora / Plus Jakarta Sans yet;
 * mirror will drift until that lands.
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
 * Font families QuickInk uses. All three families are bundled in
 * `res/font/` so first-frame rendering is deterministic.
 */
object QuickInkFonts {
    /**
     * Editorial serif — Lora. Powers [QuickInkTextStyle.Display],
     * [QuickInkTextStyle.OnboardingTitle], [QuickInkTextStyle.Editorial],
     * [QuickInkTextStyle.OnboardingBody], [QuickInkTextStyle.BodyItalic],
     * and [QuickInkTextStyle.CtaSerif].
     *
     * Bundled weights: Regular, Medium, SemiBold, Bold + Regular and
     * Medium italics. Lora has no Light variant — anywhere the old
     * Cormorant Light read elegantly at 28sp, [Display] now uses
     * Regular at 26sp to match optical density.
     */
    val serif: FontFamily = FontFamily(
        Font(R.font.lora_regular,       FontWeight.Normal),
        Font(R.font.lora_medium,        FontWeight.Medium),
        Font(R.font.lora_semibold,      FontWeight.SemiBold),
        Font(R.font.lora_bold,          FontWeight.Bold),
        Font(R.font.lora_italic,        FontWeight.Normal, FontStyle.Italic),
        Font(R.font.lora_medium_italic, FontWeight.Medium, FontStyle.Italic),
    )

    /**
     * UI sans — Plus Jakarta Sans. Powers every sans token: [QuickInkTextStyle.Body],
     * [QuickInkTextStyle.Caption], [QuickInkTextStyle.Meta], [QuickInkTextStyle.Label],
     * [QuickInkTextStyle.Eyebrow], [QuickInkTextStyle.PageTitle], [QuickInkTextStyle.Heading],
     * [QuickInkTextStyle.CardTitle].
     *
     * Bundled instead of pulled via downloadable Google Fonts (as
     * Inter was) so there's no first-launch network roundtrip and no
     * brief flash of fallback sans before the real face loads.
     */
    val ui: FontFamily = FontFamily(
        Font(R.font.plus_jakarta_sans_regular,  FontWeight.Normal),
        Font(R.font.plus_jakarta_sans_medium,   FontWeight.Medium),
        Font(R.font.plus_jakarta_sans_semibold, FontWeight.SemiBold),
        Font(R.font.plus_jakarta_sans_bold,     FontWeight.Bold),
    )

    /**
     * Caveat for handwritten preview snippets (Library cards' OCR
     * thumbnails, NoteEditor handwritten-title affordances). Only the
     * Medium weight is bundled — matches iOS (`Caveat-Medium.ttf`),
     * and every call site consumes [QuickInkTextStyle.Handwritten] at
     * a single 20sp / Medium variant. Asking for Normal would force
     * Compose to synthesize a thinner stroke from Medium glyphs and
     * look faded.
     */
    val handwritten: FontFamily = FontFamily(
        Font(R.font.caveat_medium, FontWeight.Medium),
    )
}

/**
 * Pre-baked text styles matching the typography token JSON. Use
 * these instead of constructing `TextStyle` calls inline so a brand
 * pass tweak lands in one place.
 *
 * Family contract:
 *   - Serif (Lora): [Display], [OnboardingTitle], [Editorial],
 *     [OnboardingBody], [BodyItalic], [CtaSerif].
 *   - Sans (Plus Jakarta Sans): [Body], [Caption], [Meta], [Label],
 *     [Eyebrow], [PageTitle], [Heading], [CardTitle].
 *   - Handwritten (Caveat): [Handwritten] only.
 *
 * Token names map directly except for three sans aliases kept for
 * call-site stability: [Label] = `UiLabel`, [Eyebrow] = `UiLabelCaps`,
 * [Meta] = `Metadata`. [PageTitle], [Heading], and [CardTitle] are
 * structural — no direct token entry, but used widely enough that
 * removing them would mean rewriting most screens.
 *
 * Mirror of iOS `QuickInkText` enum.
 */
object QuickInkTextStyle {

    // ─── Serif (Lora) ─────────────────────────────────────────────

    /**
     * Home greeting ("Achal B I"). Was Cormorant Light 28sp — now
     * Lora Regular 26sp for matching optical density (Lora has no
     * Light variant).
     */
    val Display: TextStyle = TextStyle(
        fontFamily    = QuickInkFonts.serif,
        fontWeight    = FontWeight.Normal,
        fontSize      = 26.sp,
        lineHeight    = 30.sp,
        letterSpacing = (-0.5).sp,
    )

    /** Onboarding hero title. Lora Medium 20 / 26 / -0.3. */
    val OnboardingTitle: TextStyle = TextStyle(
        fontFamily    = QuickInkFonts.serif,
        fontWeight    = FontWeight.Medium,
        fontSize      = 20.sp,
        lineHeight    = 26.sp,
        letterSpacing = (-0.3).sp,
    )

    /**
     * Sustainability / campaign headlines, smart-collection titles.
     * Lora Medium 15 / 22.
     */
    val Editorial: TextStyle = TextStyle(
        fontFamily = QuickInkFonts.serif,
        fontWeight = FontWeight.Medium,
        fontSize   = 15.sp,
        lineHeight = 22.sp,
    )

    /**
     * Onboarding tagline / SignIn lead copy. Regular (was Medium)
     * reads better in long editorial copy. App screens use [Body]
     * (sans) for non-editorial body copy.
     */
    val OnboardingBody: TextStyle = TextStyle(
        fontFamily = QuickInkFonts.serif,
        fontWeight = FontWeight.Normal,
        fontSize   = 16.sp,
        lineHeight = 24.sp,
    )

    /**
     * Empty states ("No notes yet…"), smart-collection rule grammar,
     * AI suggestion chips. Lora Regular Italic 16 / 24.
     */
    val BodyItalic: TextStyle = TextStyle(
        fontFamily = QuickInkFonts.serif,
        fontWeight = FontWeight.Normal,
        fontStyle  = FontStyle.Italic,
        fontSize   = 16.sp,
        lineHeight = 24.sp,
    )

    /**
     * Primary CTA on onboarding & sheet "Save / Continue" actions —
     * the only place serif meets a filled button. Lora SemiBold 14 /
     * 18 / +0.2 tracking.
     */
    val CtaSerif: TextStyle = TextStyle(
        fontFamily    = QuickInkFonts.serif,
        fontWeight    = FontWeight.SemiBold,
        fontSize      = 14.sp,
        lineHeight    = 18.sp,
        letterSpacing = 0.2.sp,
    )

    // ─── Sans (Plus Jakarta Sans) ─────────────────────────────────

    /**
     * Default UI body, doc list titles, modal body copy. Plus Jakarta
     * Sans Regular 14 / 20.
     *
     * Token rev: was 16sp Medium; tokens spec Regular 14sp for tighter
     * list density. Touches every screen — review for visual regressions
     * on first pass.
     */
    val Body: TextStyle = TextStyle(
        fontFamily = QuickInkFonts.ui,
        fontWeight = FontWeight.Normal,
        fontSize   = 14.sp,
        lineHeight = 20.sp,
    )

    /**
     * Folder meta ("47 items · 3 new"), date stamps, page counts,
     * tag counts. PJS Regular 12 / 16. Token name: `Metadata`; kept
     * as [Meta] for call-site stability.
     */
    val Meta: TextStyle = TextStyle(
        fontFamily = QuickInkFonts.ui,
        fontWeight = FontWeight.Normal,
        fontSize   = 12.sp,
        lineHeight = 16.sp,
    )

    /**
     * Badge pills ("OCR done", "Shared", "Map"), thumbnail tags.
     * PJS Medium 10 / 16.
     */
    val Caption: TextStyle = TextStyle(
        fontFamily = QuickInkFonts.ui,
        fontWeight = FontWeight.Medium,
        fontSize   = 10.sp,
        lineHeight = 16.sp,
    )

    /**
     * Section eyebrows ("Folders", "Smart collections"), folder names
     * in list, toolbar chip text, nav labels. PJS SemiBold 14 / 20.
     * Token name: `UiLabel`; kept as [Label] for call-site stability.
     *
     * Token rev: was Medium; tokens promote to SemiBold so labels
     * sit a clear notch above [Body].
     */
    val Label: TextStyle = TextStyle(
        fontFamily = QuickInkFonts.ui,
        fontWeight = FontWeight.SemiBold,
        fontSize   = 14.sp,
        lineHeight = 20.sp,
    )

    /**
     * Uppercase eyebrow above grouped content — "CONTINUE",
     * "AUTO-CURATED RULE", tab-bar labels. PJS SemiBold 11 / 16 /
     * +1.4 tracking. Token name: `UiLabelCaps`; kept as [Eyebrow] for
     * call-site stability.
     *
     * Compose's `TextStyle` has no `textCase` field, so this style
     * does not force uppercase — callers must `.uppercase()` the
     * string when they want caps. (Existing call sites already do.)
     */
    val Eyebrow: TextStyle = TextStyle(
        fontFamily    = QuickInkFonts.ui,
        fontWeight    = FontWeight.SemiBold,
        fontSize      = 11.sp,
        lineHeight    = 16.sp,
        letterSpacing = 1.4.sp,
    )

    // ─── Sans — structural (no direct token entry) ────────────────

    /**
     * App page title (Settings, Library, Detail, etc.). PJS SemiBold
     * 20 / 26 / -0.2. Not in the token JSON but used widely; kept
     * structurally between [OnboardingTitle] (serif hero) and
     * [Heading] (sans 16) so app pages read functional rather than
     * editorial.
     */
    val PageTitle: TextStyle = TextStyle(
        fontFamily    = QuickInkFonts.ui,
        fontSize      = 20.sp,
        fontWeight    = FontWeight.SemiBold,
        lineHeight    = 26.sp,
        letterSpacing = (-0.2).sp,
    )

    /**
     * App section heading ("Recents", "Quick categories", etc.). PJS
     * SemiBold 16 / 22 / -0.1. Not in the token JSON. Sustainability-
     * tier editorial moments use [Editorial] (serif) instead.
     */
    val Heading: TextStyle = TextStyle(
        fontFamily    = QuickInkFonts.ui,
        fontSize      = 16.sp,
        fontWeight    = FontWeight.SemiBold,
        lineHeight    = 22.sp,
        letterSpacing = (-0.1).sp,
    )

    /**
     * Card title — note/scan thumbnail titles in home recent rail
     * and library grid. PJS SemiBold 14 / 18. Not in the token JSON.
     * Briefly tried serif per a "Notebook Titles → New York Medium"
     * spec row, but at 14sp serif read too literary for a file-name
     * list.
     */
    val CardTitle: TextStyle = TextStyle(
        fontFamily = QuickInkFonts.ui,
        fontSize   = 14.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 18.sp,
    )

    // ─── Handwritten (Caveat) ─────────────────────────────────────

    /**
     * Caveat handwritten — note-thumbnail previews and the editor's
     * handwritten-title affordance. Pinned to Medium (only weight
     * bundled, matches iOS).
     */
    val Handwritten: TextStyle = TextStyle(
        fontFamily = QuickInkFonts.handwritten,
        fontSize   = 20.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 26.sp,
    )
}
