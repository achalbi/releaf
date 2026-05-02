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
 * Fonts (mockup brief):
 *   - Headings: Cormorant Garamond (serif, sometimes italic)
 *   - Body editorial: Cormorant Garamond
 *   - UI labels: system sans
 *   - Handwritten previews: Caveat
 *
 * Custom font bundling: Cormorant Garamond and Caveat are both
 * Google Fonts (OFL). To bundle:
 *   1. Drop .ttf/.otf files into QuickInk/DesignSystem/Fonts/
 *   2. In Package.swift, add `resources: [.process("DesignSystem/Fonts")]`
 *      under the QuickInkFeatures target.
 *   3. Register them at app launch via `QuickInkFont.registerAll()`.
 *
 * Until fonts are bundled, `QuickInkFont.serif(...)` and
 * `QuickInkFont.handwritten(...)` fall back to the closest system
 * design (`.serif` and `.serif italic` respectively). The visual
 * is degraded but the layout and weight hierarchy remain correct.
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

    public static let bg           = dynamic(light: 0xFAF7F2, dark: 0x1C1917)
    public static let surface      = dynamic(light: 0xFFFFFF, dark: 0x292524)
    public static let border       = dynamic(light: 0xEDE4D2, dark: 0x3D3733)
    public static let borderSoft   = dynamic(light: 0xF0E9DD, dark: 0x35302C)
    public static let accent       = dynamic(light: 0xD97757, dark: 0xD97757)
    public static let accentSoft   = dynamic(light: 0xF5EDE0, dark: 0x3A2A20)
    public static let accentDeep   = dynamic(light: 0xB85F42, dark: 0xB85F42)
    public static let ink          = dynamic(light: 0x2C2826, dark: 0xF5EFE6)
    public static let inkSoft      = dynamic(light: 0x5C4A38, dark: 0xC9BDA8)
    public static let muted        = dynamic(light: 0xA8A29E, dark: 0x8C857F)
    public static let textOnAccent = Color(hex: 0xFFFFFF)

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
/// `serif(...)` produces Cormorant Garamond when bundled, falling
/// back to SwiftUI's `.serif` design otherwise. `handwritten(...)`
/// targets Caveat with an italic-serif fallback. `ui(...)` returns
/// the system sans for chips, nav labels, and status pills.
public enum QuickInkFont {

    /// Custom font names. Match the .ttf PostScript names — once
    /// fonts are bundled in `DesignSystem/Fonts/`, these resolve;
    /// before, `Font.custom(...)` falls through to the system
    /// fallback when called with an unknown family.
    public enum Family {
        public static let serif       = "Cormorant Garamond"
        public static let serifItalic = "Cormorant Garamond Italic"
        public static let handwritten = "Caveat"
    }

    /// Cormorant Garamond at the given size + weight. Falls back
    /// to system serif design if the family isn't bundled.
    public static func serif(_ size: CGFloat, weight: Font.Weight = .regular, italic: Bool = false) -> Font {
        let name = italic ? Family.serifItalic : Family.serif
        // `Font.custom(_:size:)` returns the system fallback if the
        // family isn't registered — calling `.weight` on it still
        // works and produces a system fallback at the right weight.
        return Font.custom(name, size: size).weight(weight)
    }

    /// Caveat handwritten font for note previews. Falls back to
    /// system serif italic if the family isn't bundled.
    public static func handwritten(_ size: CGFloat) -> Font {
        return Font.custom(Family.handwritten, size: size)
    }

    /// System sans for UI labels (chips, nav, status pills).
    public static func ui(_ size: CGFloat, weight: Font.Weight = .regular) -> Font {
        return Font.system(size: size, weight: weight, design: .default)
    }

    /// Register all bundled QuickInk font files. Call once at app
    /// launch from the Xcode app target's `@main App` init or
    /// equivalent; idempotent if files aren't present (logs and
    /// no-ops). Iterates over the package's resource bundle.
    public static func registerAll() {
        #if SWIFT_PACKAGE
        let bundle = Bundle.module
        #else
        let bundle = Bundle.main
        #endif
        let exts = ["ttf", "otf"]
        for ext in exts {
            guard let urls = bundle.urls(forResourcesWithExtension: ext, subdirectory: "Fonts") else { continue }
            for url in urls {
                CTFontManagerRegisterFontsForURL(url as CFURL, .process, nil)
            }
        }
    }
}

/// Pre-baked text styles matching the mockup hierarchy. Use these
/// instead of constructing `QuickInkFont.serif(...)` calls inline,
/// so a brand pass tweak lands in one place.
public enum QuickInkText {
    /// Onboarding hero / page hero — large serif, light weight.
    public static let display    = QuickInkFont.serif(40, weight: .light)

    /// Standard page title (Settings, Library, etc.) — serif, regular weight.
    public static let pageTitle  = QuickInkFont.serif(28, weight: .regular)

    /// Section eyebrow above grouped content (used uppercase + tracked).
    public static let eyebrow    = QuickInkFont.ui(11, weight: .semibold)

    /// Section heading (smaller than pageTitle).
    public static let heading    = QuickInkFont.serif(20, weight: .medium)

    /// Body editorial copy (Cormorant Garamond regular).
    public static let body       = QuickInkFont.serif(16, weight: .regular)

    /// Italic accent body (taglines, smart suggestions).
    public static let bodyItalic = QuickInkFont.serif(16, weight: .regular, italic: true)

    /// Caveat handwritten — used inside note thumbnails.
    public static let handwritten = QuickInkFont.handwritten(20)

    /// UI label (chip text, nav labels, button text on small CTAs).
    public static let label      = QuickInkFont.ui(14, weight: .medium)

    /// Meta — timestamps, sync status, helper copy.
    public static let meta       = QuickInkFont.ui(12, weight: .regular)

    /// Caption — smallest readable size. Used in confidence badges,
    /// page counters, etc.
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
