/*
 * QuickInkTheme.swift
 *
 * QuickInk's local theme overlay — the warm, editorial Claude-style
 * palette specified in the mockup brief (off-white #FAF7F2 canvas,
 * coral #D97757 accent, Cormorant Garamond serif headings, Caveat
 * handwritten previews).
 *
 * Why this lives here, not in ReleafCore: the shared design system
 * is owned by Releaf and used by multiple apps; bending its tokens
 * to QuickInk's editorial direction would either fight Releaf's
 * needs or force a multi-theme indirection across every shared
 * surface. Instead, QuickInk introduces its own concrete tokens
 * here and screens read from `QuickInkColors` / `QuickInkTypography`
 * etc. directly. ReleafCore tokens stay available for any surface
 * that reaches into shared components (NotepadEditorViewModel etc.).
 *
 * Token shape mirrors ReleafCore's surface area (canvas, surface,
 * border, accent, ink, etc.) so the mental model carries over;
 * values come from the mockup tokens table:
 *
 *   bg          #FAF7F2  app background
 *   surface     #FFFFFF  cards
 *   border      #EDE4D2  dividers, card borders
 *   borderSoft  #F0E9DD  pill backgrounds, search bar fill
 *   accent      #D97757  coral — CTAs, active state, FAB
 *   accentSoft  #F5EDE0  category tag backgrounds
 *   ink         #2C2826  primary text
 *   inkSoft     #5C4A38  secondary text
 *   muted       #A8A29E  tertiary text, inactive nav
 *   paper1/2/3  #E8DCC4 / #F0E4D7 / #EADFCF  note thumbnail bg
 *
 * Fonts:
 *   - All serif headings (onboarding + app): Cormorant Garamond
 *     (high-contrast didone, editorial showroom feel — used by
 *     onboarding heroes, page titles, section headings, italic
 *     taglines, and the home greeting name)
 *   - Body & UI: Inter (replaces system sans so iOS and Android
 *     render identically — SF Pro vs Roboto drifted)
 *   - Handwritten previews: Caveat
 *
 * Single-serif system. Heading weights step up to Bold/SemiBold so
 * Cormorant carries the visual heft Fraunces previously gave the
 * app screens — see weight notes on each `QuickInkText` token.
 *
 * Custom font bundling: Cormorant Garamond, Inter, and Caveat are
 * all Google Fonts (OFL). To bundle:
 *   1. Drop .ttf/.otf files into QuickInk/DesignSystem/Fonts/.
 *      Inter ships at the 18pt optical (`Inter_18pt-Regular.ttf` →
 *      PS: Inter18pt-Regular).
 *   2. Package.swift already declares `.process("DesignSystem/Fonts")`,
 *      so new files are picked up automatically.
 *   3. Registration at launch happens in `QuickInkFont.registerAll()`
 *      and is file-name agnostic (iterates everything in the bundle),
 *      so no code change is needed when new files arrive.
 *
 * Until a given .ttf is bundled, `Font.custom(...)` falls back to the
 * closest system design. Layout + weight hierarchy stay correct;
 * only the visual character is degraded.
 */

import SwiftUI
import CoreText
#if canImport(UIKit)
import UIKit
#endif

// MARK: - Colors

/// QuickInk's color palette. Each token is a *dynamic* color that
/// resolves at runtime based on the current `userInterfaceStyle`,
/// so every screen that reads `QuickInkColors.bg` automatically
/// adapts when the user switches between light and dark mode —
/// no screen edits required.
///
/// Light values come from the mockup brief (warm cream/coral
/// editorial). Dark values keep the coral identity but swap the
/// canvas, surface, border, and ink tones to deep stone. Coral
/// itself is preserved across modes — the brand mark stays
/// identifiable.
public enum QuickInkColors {
    // Light + dark token table:
    // | Token        | Light      | Dark       |
    // |--------------|------------|------------|
    // | bg           | #FAF7F2    | #1C1917    |
    // | surface      | #FFFFFF    | #292524    |
    // | border       | #EDE4D2    | #3D3733    |
    // | borderSoft   | #F0E9DD    | #35302C    |
    // | accent       | #D97757    | #D97757    |  (preserved)
    // | accentSoft   | #F5EDE0    | #3A2A20    |
    // | accentDeep   | #B85F42    | #B85F42    |  (preserved)
    // | ink          | #2C2826    | #F5EFE6    |
    // | inkSoft      | #5C4A38    | #C9BDA8    |
    // | muted        | #A8A29E    | #8C857F    |
    // | textOnAccent | #FFFFFF    | #FFFFFF    |  (white in both)
    // | paper1/2/3   | #E8DCC4 …  | #3F362A …  |  (deep paper tones)
    // | success      | #6B8E5A    | #6B8E5A    |  (preserved)
    // | warning      | #C97A2C    | #C97A2C    |
    // | danger       | #B54B3F    | #B54B3F    |

    // Brand-pass refresh:
    //   - Canvas / Text Primary / Text Secondary are now FIXED across
    //     the whole picker — only the accent rotates. Hex values
    //     come from the design table (see file header).
    //   - Accent defaults to Coral. The QuickInkTheme view modifier
    //     swaps it at runtime by reading SettingsState.primaryColor
    //     and SettingsState.themeMode (light → deep variant, dark →
    //     base variant).
    public static let bg           = dynamic(light: 0xF5EEDF, dark: 0x1C1917)
    public static let surface      = dynamic(light: 0xFFFFFF, dark: 0x292524)
    public static let border       = dynamic(light: 0xEDE4D2, dark: 0x3D3733)
    public static let borderSoft   = dynamic(light: 0xF0E9DD, dark: 0x35302C)
    public static let accent       = dynamic(light: 0xC65A3E, dark: 0xE07856)
    public static let accentSoft   = dynamic(light: 0xF5EDE0, dark: 0x3A2A20)
    public static let accentDeep   = dynamic(light: 0xC65A3E, dark: 0xC65A3E)
    public static let ink          = dynamic(light: 0x463C31, dark: 0xF5EFE6)
    public static let inkSoft      = dynamic(light: 0x5F5245, dark: 0xC9BDA8)
    public static let muted        = dynamic(light: 0xA8A29E, dark: 0x8C857F)
    public static let textOnAccent = Color(hex: 0xFFFFFF)

    // Hue family variants — exposed so the per-screen theme overlay
    // (see PrimaryColor + applyPrimaryColor below) can resolve
    // accent / accentDeep from the user's pick.
    public static let coralBase       = Color(hex: 0xE07856)
    public static let coralDeep       = Color(hex: 0xC65A3E)
    public static let leafGreenBase   = Color(hex: 0x7AA874)
    public static let leafGreenDeep   = Color(hex: 0x5B8C52)
    public static let leafYellowBase  = Color(hex: 0xF4C430)
    public static let leafYellowDeep  = Color(hex: 0xE8B923)
    public static let leafDryBase     = Color(hex: 0xB8956A)
    public static let leafDryDeep     = Color(hex: 0x8B7355)

    public static let paper1       = dynamic(light: 0xE8DCC4, dark: 0x3F362A)
    public static let paper2       = dynamic(light: 0xF0E4D7, dark: 0x42392C)
    public static let paper3       = dynamic(light: 0xEADFCF, dark: 0x3C3528)

    public static let success      = Color(hex: 0x6B8E5A)
    public static let warning      = Color(hex: 0xC97A2C)
    public static let danger       = Color(hex: 0xB54B3F)

    /// Rotate through paper tones for note thumbnails so a wall
    /// of cards doesn't look monotonous. Keyed by note ID hash so
    /// each note gets a stable tone across sessions.
    public static func paper(for seed: Int) -> Color {
        switch ((seed % 3) + 3) % 3 {
        case 0:  return paper1
        case 1:  return paper2
        default: return paper3
        }
    }

    /// Light/dark dynamic-color helper. Uses
    /// `UIColor(dynamicProvider:)` on iOS so every reference
    /// updates automatically when the system or user toggles
    /// dark mode. Falls back to the light value on platforms
    /// without UIKit (won't fire in practice — QuickInk is iOS-
    /// only — but keeps `swift build` from failing on macOS).
    private static func dynamic(light: UInt32, dark: UInt32) -> Color {
        #if canImport(UIKit)
        return Color(uiColor: UIColor { trait in
            trait.userInterfaceStyle == .dark ? UIColor(rgb: dark) : UIColor(rgb: light)
        })
        #else
        return Color(hex: light)
        #endif
    }
}

/// The four hue families the user can pick from in Settings →
/// Appearance. Each family carries its base + deep variant; the
/// theme layer picks one based on the active mode.
public enum PrimaryColor: String, CaseIterable {
    case coral
    case leafGreen
    case leafYellow
    case leafDry

    public var displayName: String {
        switch self {
        case .coral:      return "Coral"
        case .leafGreen:  return "Leaf Green"
        case .leafYellow: return "Leaf Yellow"
        case .leafDry:    return "Leaf Dry"
        }
    }

    public var base: Color {
        switch self {
        case .coral:      return QuickInkColors.coralBase
        case .leafGreen:  return QuickInkColors.leafGreenBase
        case .leafYellow: return QuickInkColors.leafYellowBase
        case .leafDry:    return QuickInkColors.leafDryBase
        }
    }

    public var deep: Color {
        switch self {
        case .coral:      return QuickInkColors.coralDeep
        case .leafGreen:  return QuickInkColors.leafGreenDeep
        case .leafYellow: return QuickInkColors.leafYellowDeep
        case .leafDry:    return QuickInkColors.leafDryDeep
        }
    }
}

/// User-pickable theme override. `system` follows the OS setting;
/// `light` / `dark` force the corresponding mode regardless of OS.
public enum ThemeMode: String, CaseIterable {
    case system
    case light
    case dark

    public var displayName: String {
        switch self {
        case .system: return "System"
        case .light:  return "Light"
        case .dark:   return "Dark"
        }
    }

    /// Map to SwiftUI's `ColorScheme?` — `nil` lets the OS decide.
    public var colorScheme: ColorScheme? {
        switch self {
        case .system: return nil
        case .light:  return .light
        case .dark:   return .dark
        }
    }
}

#if canImport(UIKit)
private extension UIColor {
    /// Hex helper mirroring `Color(hex:)`. Only used inside the
    /// dynamic-color provider above.
    convenience init(rgb: UInt32, alpha: CGFloat = 1.0) {
        let r = CGFloat((rgb >> 16) & 0xFF) / 255.0
        let g = CGFloat((rgb >>  8) & 0xFF) / 255.0
        let b = CGFloat( rgb        & 0xFF) / 255.0
        self.init(red: r, green: g, blue: b, alpha: alpha)
    }
}
#endif

// MARK: - Typography

/// Font helpers for QuickInk's editorial type system.
///
/// Two font families resolve here:
///
///   - `serif(...)` → Cormorant Garamond. The single editorial
///     serif — used by onboarding heroes, page titles, section
///     headings, italic taglines, and the home greeting name.
///     Heading-tier call sites use `.bold` / `.semibold` so the
///     glyphs carry visual weight at small mobile rendering sizes.
///   - `ui(...)` → Inter. Replaces what was previously
///     `Font.system(...)` so iOS and Android render identically and
///     the brand carries across platforms. Used for body, labels,
///     chips, nav, captions.
///
/// Both resolve via `Font.custom(...)` with a PostScript-name
/// selector that maps a `Font.Weight` (and italic flag) to a
/// specific bundled `.ttf`. SwiftUI's `Font.custom(...).weight(...)`
/// modifier sometimes synthesizes a faux-bold rather than picking
/// the matching face — selecting the file directly is the
/// deterministic approach.
///
/// `handwritten(...)` targets Caveat (Medium only).
public enum QuickInkFont {

    /// Cormorant Garamond at the given size + weight, optionally
    /// italic. The single serif used across onboarding and app
    /// screens — see `QuickInkText` for the pre-baked tokens.
    public static func serif(_ size: CGFloat, weight: Font.Weight = .regular, italic: Bool = false) -> Font {
        return Font.custom(cormorantPostScriptName(weight: weight, italic: italic), size: size)
    }

    /// Caveat handwritten font for note previews. Only the Medium
    /// weight is bundled today; if other weights are needed later,
    /// drop the .ttf into `DesignSystem/Fonts/` and switch this to
    /// a weight-aware selector like `cormorantPostScriptName`.
    public static func handwritten(_ size: CGFloat) -> Font {
        return Font.custom("Caveat-Medium", size: size)
    }

    /// Map a SwiftUI `Font.Weight` + italic flag to the PostScript
    /// name of the matching bundled `.ttf`. Filenames in
    /// `DesignSystem/Fonts/` are PascalCase per Google Fonts'
    /// shipped naming; the PostScript names embedded in the files
    /// match. `Font.Weight` is a struct (not a true enum), hence
    /// the chain of `==` checks instead of a `switch`.
    private static func cormorantPostScriptName(weight: Font.Weight, italic: Bool) -> String {
        let weightSuffix: String
        if weight == .ultraLight || weight == .thin || weight == .light {
            weightSuffix = "Light"
        } else if weight == .medium {
            weightSuffix = "Medium"
        } else if weight == .semibold {
            weightSuffix = "SemiBold"
        } else if weight == .bold || weight == .heavy || weight == .black {
            weightSuffix = "Bold"
        } else {
            weightSuffix = "Regular"
        }
        if italic {
            // The italic regular variant ships as
            // `CormorantGaramond-Italic.ttf`, NOT `…-RegularItalic`.
            return weightSuffix == "Regular"
                ? "CormorantGaramond-Italic"
                : "CormorantGaramond-\(weightSuffix)Italic"
        }
        return "CormorantGaramond-\(weightSuffix)"
    }

    /// Map a SwiftUI `Font.Weight` to the matching bundled Inter
    /// file at the **18pt optical size** (Inter's smallest optical;
    /// the right pick for our 11–16pt UI rendering). Bundled
    /// weights are Regular and Medium — that's all the QuickInk
    /// type system uses. SemiBold/Bold/Heavy fall back to Medium,
    /// Light/Thin to Regular.
    ///
    /// PostScript name from Google Fonts' new build:
    /// `Inter_18pt-Regular.ttf` → `Inter18pt-Regular`.
    private static func interPostScriptName(weight: Font.Weight) -> String {
        if weight == .medium
            || weight == .semibold
            || weight == .bold
            || weight == .heavy
            || weight == .black
        {
            return "Inter18pt-Medium"
        }
        return "Inter18pt-Regular"
    }

    /// Inter for body, labels, chips, nav, captions. Replaces what
    /// was previously `Font.system(...)`. Kept as `ui(...)` (not
    /// `body(...)` etc.) so the rename ripples nowhere — every
    /// existing call site that asks for `QuickInkFont.ui(...)` now
    /// transparently gets Inter.
    public static func ui(_ size: CGFloat, weight: Font.Weight = .regular) -> Font {
        return Font.custom(interPostScriptName(weight: weight), size: size)
    }

    /// Register all bundled QuickInk font files. Call once at app
    /// launch from the Xcode app target's `@main App` init or
    /// equivalent; idempotent if files aren't present (logs and
    /// no-ops). Iterates over the package's resource bundle.
    ///
    /// `subdirectory` is `nil` (not `"Fonts"`) because Package.swift
    /// declares the font directory with `.process(...)`, which
    /// FLATTENS the directory contents to the bundle root rather
    /// than preserving the `Fonts/` subfolder. Looking in `Fonts/`
    /// returned an empty list and silently skipped registration —
    /// SwiftUI then fell back to system serif for every
    /// `Font.custom("CormorantGaramond-...", size:)` call.
    ///
    /// In DEBUG builds, prints a compact summary of which TTFs
    /// registered (and which failed) so the "why is the screen in
    /// system serif?" debugging step is `Run → look at console`,
    /// not `dig through the Xcode bundle`.
    public static func registerAll() {
        #if SWIFT_PACKAGE
        let bundle = Bundle.module
        #else
        let bundle = Bundle.main
        #endif
        let exts = ["ttf", "otf"]
        var registered: [String] = []
        var failed: [String] = []
        for ext in exts {
            guard let urls = bundle.urls(forResourcesWithExtension: ext, subdirectory: nil) else { continue }
            for url in urls {
                var error: Unmanaged<CFError>?
                let ok = CTFontManagerRegisterFontsForURL(url as CFURL, .process, &error)
                if ok {
                    registered.append(url.lastPathComponent)
                } else if let cfError = error?.takeRetainedValue() {
                    // `takeRetainedValue()` consumes the +1 retain
                    // CTFontManager hands us so we don't leak.
                    // Already-registered (`kCTFontManagerError-
                    // AlreadyRegistered`) isn't a real failure — it
                    // happens during SwiftUI Previews hot reload
                    // and re-init paths — treat as success.
                    let code = (cfError as Error as NSError).code
                    if code == Int(CTFontManagerError.alreadyRegistered.rawValue) {
                        registered.append(url.lastPathComponent)
                    } else {
                        failed.append("\(url.lastPathComponent) (code=\(code))")
                    }
                } else {
                    failed.append(url.lastPathComponent)
                }
            }
        }
        #if DEBUG
        print("[QuickInkFont] registered \(registered.count) font file(s):")
        for f in registered.sorted() { print("  ✓ \(f)") }
        if !failed.isEmpty {
            print("[QuickInkFont] FAILED \(failed.count) font file(s):")
            for f in failed.sorted() { print("  ✗ \(f)") }
        }
        #endif
    }
}

/// Pre-baked text styles matching the mockup hierarchy. Use these
/// instead of constructing `QuickInkFont.serif(...)` calls inline,
/// so a brand pass tweak lands in one place.
///
/// Family contract:
///   - All serif tokens (`display`, `pageTitle`, `heading`,
///     `bodyItalic`, `onboardingTitle`, `onboardingBody`) →
///     Cormorant Garamond via `QuickInkFont.serif(...)`.
///   - `body` → Inter via `QuickInkFont.ui(...)`. App screens use
///     it directly; onboarding screens explicitly reach for
///     `onboardingBody` instead.
///   - UI tokens (`eyebrow`, `label`, `cardTitle`, `meta`,
///     `caption`) → Inter (UI sans).
///
/// Heading-tier weights step up to Bold/SemiBold so Cormorant
/// carries the visual heft Fraunces previously gave the small-
/// mobile rendering — see notes per token.
public enum QuickInkText {
    /// App-tier large display serif — used for the Home greeting
    /// name ("Achal B I") and the Profile screen header. Cormorant
    /// Garamond Bold at 40pt. Audited via grep for
    /// `QuickInkText.display`: only home + profile.
    public static let display    = QuickInkFont.serif(40, weight: .bold)

    /// Onboarding hero title — sized to match the JSX mockup
    /// (`text-[30px] leading-[1.15]`). Smaller than `display` so
    /// the two-line tagline doesn't crowd the illustration on a
    /// 390-wide phone frame. Cormorant Garamond.
    public static let onboardingTitle = QuickInkFont.serif(30, weight: .medium)

    /// Onboarding body — Cormorant Garamond medium. Used by the
    /// onboarding scaffold's tagline + SignInScreen's lead copy
    /// where the editorial showroom feel matters more than density.
    /// App screens use `body` (Inter) instead.
    public static let onboardingBody = QuickInkFont.serif(16, weight: .medium)

    /// App page title (Settings, Library, Detail, etc.) — Cormorant
    /// Garamond SemiBold. Bumped from Medium when Fraunces was
    /// dropped: Cormorant runs lighter than Fraunces at the same
    /// weight, so SemiBold is needed to match the Fraunces-Medium
    /// visual heft on small mobile displays.
    public static let pageTitle  = QuickInkFont.serif(28, weight: .semibold)

    /// Section eyebrow above grouped content (uppercase + tracked).
    /// Inter semibold.
    public static let eyebrow    = QuickInkFont.ui(11, weight: .semibold)

    /// App section heading (smaller than pageTitle). Cormorant
    /// Garamond Bold. Bumped from SemiBold when Fraunces was
    /// dropped — Cormorant Bold is the weight that carries against
    /// pale tile backgrounds.
    public static let heading    = QuickInkFont.serif(20, weight: .bold)

    /// App body — Inter medium. Reading copy on app screens reads
    /// as "tool" (sans) while editorial moments stay on the serif
    /// via `bodyItalic` / `heading` / `pageTitle`. Onboarding
    /// screens use `onboardingBody` for the Cormorant feel.
    public static let body       = QuickInkFont.ui(16, weight: .medium)

    /// Italic accent body — taglines, smart suggestions on app
    /// screens. Cormorant italic medium.
    public static let bodyItalic = QuickInkFont.serif(16, weight: .medium, italic: true)

    /// Caveat handwritten — used inside note thumbnails.
    public static let handwritten = QuickInkFont.handwritten(20)

    /// Card title — Inter at body scale (14pt SemiBold), used for
    /// note / scan thumbnail titles. Inter (not the serif) so the
    /// home recent rail matches the library cards' UI-sans
    /// treatment — note titles are functional (scannable in dense
    /// grids) and the editorial serif felt precious for a file
    /// list. Library cards still override with `heading.copy(...)`
    /// because they sit at a different size; home rail uses this
    /// token directly.
    public static let cardTitle  = QuickInkFont.ui(14, weight: .semibold)

    /// UI label (chip text, nav labels, button text on small CTAs).
    /// Inter semibold.
    public static let label      = QuickInkFont.ui(14, weight: .semibold)

    /// Meta — timestamps, sync status, helper copy. Inter medium.
    public static let meta       = QuickInkFont.ui(12, weight: .medium)

    /// Caption — smallest readable size. Used in confidence badges,
    /// page counters, etc. Inter medium.
    public static let caption    = QuickInkFont.ui(10, weight: .medium)
}

// MARK: - Spacing

/// 4-px-based spacing scale. Mirrors ReleafCore's `s1`–`s8` shape
/// so screens that read both can compose without surprise.
public enum QuickInkSpacing {
    public static let s1: CGFloat = 4
    public static let s2: CGFloat = 8
    public static let s3: CGFloat = 12
    public static let s4: CGFloat = 16
    public static let s5: CGFloat = 20
    public static let s6: CGFloat = 24
    public static let s7: CGFloat = 32
    public static let s8: CGFloat = 40
}

// MARK: - Radius

public enum QuickInkRadius {
    public static let sm: CGFloat = 8
    public static let md: CGFloat = 12
    public static let lg: CGFloat = 18
    public static let xl: CGFloat = 24
    /// Pill shape — used for CTAs, chips, filter selectors.
    public static let pill: CGFloat = 999
}

// MARK: - Letter spacing

public enum QuickInkLetterSpacing {
    /// Eyebrow / overline — 1.2 px tracking on top of the
    /// 11 pt label. Values in points; SwiftUI consumes them via
    /// the `.tracking()` modifier.
    public static let eyebrow: CGFloat = 1.2
    public static let body: CGFloat    = 0
}

// MARK: - Color hex helper

extension Color {
    /// Hex helper. Pass an integer literal like `0xFAF7F2`; alpha
    /// defaults to 1.0. Used by `QuickInkColors` to keep token
    /// definitions readable.
    init(hex: UInt32, alpha: Double = 1.0) {
        let r = Double((hex >> 16) & 0xFF) / 255.0
        let g = Double((hex >>  8) & 0xFF) / 255.0
        let b = Double( hex        & 0xFF) / 255.0
        self.init(.sRGB, red: r, green: g, blue: b, opacity: alpha)
    }
}

// MARK: - Common decoration shortcuts

public extension View {
    /// Coral pill button surface — fills with accent, radius 999,
    /// vertical padding tuned for one-line CTAs. Use as the
    /// outermost background on Buttons that want the QuickInk CTA
    /// look. Pair with `QuickInkText.label` text on white.
    func quickInkCTA() -> some View {
        self
            .frame(maxWidth: .infinity)
            .padding(.vertical, QuickInkSpacing.s3)
            .background(QuickInkColors.accent)
            .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.pill, style: .continuous))
    }

    /// Onboarding CTA surface — coral, rounded-rectangle (not a
    /// full pill), taller vertical padding, with a soft coral
    /// drop-shadow. Mirrors the JSX mockup's
    /// `rounded-2xl py-4 shadow-md` pattern. Pair with
    /// `QuickInkText.label` text + a trailing arrow icon for the
    /// canonical onboarding "Continue" button.
    func quickInkOnboardingCTA() -> some View {
        self
            .frame(maxWidth: .infinity)
            .padding(.vertical, QuickInkSpacing.s4)
            .background(QuickInkColors.accent)
            .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.xl, style: .continuous))
            .shadow(color: QuickInkColors.accent.opacity(0.30), radius: 12, x: 0, y: 6)
    }

    /// Card surface — white fill with border, rounded corners.
    /// Use as the outermost background on grouped-content cards.
    func quickInkCard() -> some View {
        self
            .background(QuickInkColors.surface)
            .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: QuickInkRadius.md, style: .continuous)
                    .stroke(QuickInkColors.border, lineWidth: 1)
            )
    }

    /// Pill chip surface — soft border background, rounded.
    /// Used for filter chips, status pills, search bar.
    func quickInkPill() -> some View {
        self
            .padding(.horizontal, QuickInkSpacing.s3)
            .padding(.vertical, QuickInkSpacing.s2)
            .background(QuickInkColors.borderSoft)
            .clipShape(RoundedRectangle(cornerRadius: QuickInkRadius.pill, style: .continuous))
    }
}

// MARK: - Lined paper background

/// Repeating-line gradient that fakes notebook ruling. Mirror of
/// the JSX mockup's `repeating-linear-gradient` thumbnail effect.
/// Renders 12 px-tall stripes with a faint 0.12-opacity ink line
/// on top of the given paper tone.
public struct QuickInkLinedPaper: View {
    public let tone: Color
    public let lineSpacing: CGFloat
    public let lineOpacity: Double

    public init(tone: Color = QuickInkColors.paper1, lineSpacing: CGFloat = 12, lineOpacity: Double = 0.12) {
        self.tone = tone
        self.lineSpacing = lineSpacing
        self.lineOpacity = lineOpacity
    }

    public var body: some View {
        GeometryReader { geo in
            ZStack {
                tone
                Canvas { ctx, size in
                    let lineColor = QuickInkColors.ink.opacity(lineOpacity)
                    var y: CGFloat = lineSpacing
                    while y < size.height {
                        var path = Path()
                        path.move(to: CGPoint(x: 0, y: y))
                        path.addLine(to: CGPoint(x: size.width, y: y))
                        ctx.stroke(path, with: .color(lineColor), lineWidth: 0.5)
                        y += lineSpacing
                    }
                }
                .frame(width: geo.size.width, height: geo.size.height)
            }
        }
    }
}

// MARK: - Phone frame helper (for previews)

#if DEBUG
/// Wraps a screen body in a 390×844 stone-bordered phone frame for
/// SwiftUI Previews — matches the prototype HTML's frame so
/// iterating in Xcode's preview canvas looks like the mockup.
public struct QuickInkPhoneFrame<Content: View>: View {
    public let content: () -> Content
    public init(@ViewBuilder content: @escaping () -> Content) {
        self.content = content
    }
    public var body: some View {
        content()
            .frame(width: 390, height: 844)
            .background(QuickInkColors.bg)
            .clipShape(RoundedRectangle(cornerRadius: 48, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 48, style: .continuous)
                    .stroke(Color(hex: 0x1C1917), lineWidth: 10)
            )
    }
}
#endif
