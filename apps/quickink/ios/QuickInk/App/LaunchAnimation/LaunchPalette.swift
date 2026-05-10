/*
 * LaunchPalette.swift
 *
 * Color palettes for the cinematic launch animation. Three variants
 * — Dawn, Morning Mist, Golden Hour — handed off in the React
 * prototype (`design_handoff_quickink_launch/source/scene.jsx`,
 * `PALETTES` constant). Each palette is a flat struct of every
 * color the scene's 14 layers reference, by name.
 *
 * Why a flat struct (not nested by layer): the React source treats
 * the palette as a single big bag (`c.skyTop`, `c.fatherShirt`,
 * `c.waterDrop`, …). Mirroring that shape keeps the per-layer
 * SwiftUI code reading like a 1:1 port of the JSX, which makes
 * cross-platform parity audits trivial — search for a token name,
 * compare iOS vs Android vs the prototype line by line.
 *
 * Counterpart: Android `LaunchPalette.kt`. Hex values must match
 * exactly so the cinematic looks identical across platforms.
 */

import SwiftUI

/// All colors a single launch-animation palette exposes. Field names
/// mirror the keys in the JSX `PALETTES.*` records. Optional values
/// (`gold` etc.) are surfaced as non-optional with the prototype's
/// fallback baked in — see `LaunchPalettes` below for the actual hex.
struct LaunchPalette {
    // Sky + sun
    let skyTop: Color
    let skyMid: Color
    let skyHorizon: Color
    let skyBase: Color
    let sun: Color
    let sunGlow: Color
    let rays: Color
    let haze: Color
    let cloud: Color

    // Mountains + ground
    let mountainBack: Color
    let mountainMid: Color
    let mountainFront: Color
    let mountainHaze: Color
    let ground: Color
    let grassDark: Color
    let grassLight: Color
    let soil: Color
    let soilWet: Color

    // Stumps
    let stump: Color
    let stumpTop: Color
    let stumpRing: Color

    // Family — skin / hair
    let skin: Color
    let skinDark: Color
    let skinShade: Color
    let hairDark: Color
    let hairBrown: Color
    let hairLight: Color

    // Family — clothing
    let fatherShirt: Color
    let fatherPants: Color
    let motherShirt: Color
    let motherPants: Color
    let motherSkirt: Color
    let daughterShirt: Color
    let daughterSkirt: Color
    let sonShirt: Color
    let sonPants: Color

    // Accents — gold, generic
    let gold: Color
    let goldDark: Color
    let accent1: Color
    let accent2: Color

    // Watering can
    let watercan: Color
    let watercanShade: Color
    let watercanHi: Color

    // Tree
    let leaf: Color
    let leafDark: Color
    let leafLight: Color
    let leafHi: Color
    let bark: Color
    let barkLight: Color

    // Water + atmosphere
    let waterDrop: Color
    let waterStreak: Color
    let pollen: Color
    let bird: Color

    // UI badges + logo + feed
    let badgeBg: Color
    let badgeText: Color
    let badgeAccent: Color
    let logoTagline: Color
    let feedBg: Color
    let feedAccent: Color
}

/// Three palettes from the prototype. `dawn` is the default — the
/// host (`LaunchAnimationView`) picks it unconditionally for the
/// shipping cinematic, with `mist` and `sunset` available for any
/// future seasonal A/B or marketing renders.
enum LaunchPalettes {

    static let dawn = LaunchPalette(
        skyTop:        Color(launchHex: 0xfce4c4),
        skyMid:        Color(launchHex: 0xf8d2a3),
        skyHorizon:    Color(launchHex: 0xe8e3b6),
        skyBase:       Color(launchHex: 0xdfe9c8),
        sun:           Color(launchHex: 0xfff4c8),
        sunGlow:       Color(rgba: 253, 204, 126, 0.55),
        rays:          Color(rgba: 255, 230, 180, 0.18),
        haze:          Color(rgba: 255, 220, 170, 0.40),
        cloud:         Color(rgba: 255, 244, 220, 0.70),
        mountainBack:  Color(launchHex: 0x7a8a6c),
        mountainMid:   Color(launchHex: 0x4a6048),
        mountainFront: Color(launchHex: 0x2c402a),
        mountainHaze:  Color(rgba: 232, 227, 182, 0.55),
        ground:        Color(launchHex: 0x1f2c1c),
        grassDark:     Color(launchHex: 0x2d4a26),
        grassLight:    Color(launchHex: 0x4f7c43),
        soil:          Color(launchHex: 0x3a2a1c),
        soilWet:       Color(launchHex: 0x1f140a),
        stump:         Color(launchHex: 0x5a3a22),
        stumpTop:      Color(launchHex: 0xa87850),
        stumpRing:     Color(launchHex: 0x7a5538),
        skin:          Color(launchHex: 0xe8b890),
        skinDark:      Color(launchHex: 0xc89870),
        skinShade:     Color(launchHex: 0xb88860),
        hairDark:      Color(launchHex: 0x0e0604),
        hairBrown:     Color(launchHex: 0x1a0c06),
        hairLight:     Color(launchHex: 0x3a1c0c),
        fatherShirt:   Color(launchHex: 0xe89020),
        fatherPants:   Color(launchHex: 0xf5ecd2),
        motherShirt:   Color(launchHex: 0xd63a7a),
        motherPants:   Color(launchHex: 0x7a1838),
        motherSkirt:   Color(launchHex: 0xe63070),
        daughterShirt: Color(launchHex: 0xe84080),
        daughterSkirt: Color(launchHex: 0xf4a020),
        sonShirt:      Color(launchHex: 0x2080a0),
        sonPants:      Color(launchHex: 0xf0e8d8),
        gold:          Color(launchHex: 0xf0c850),
        goldDark:      Color(launchHex: 0xb48830),
        accent1:       Color(launchHex: 0x5b18a8),
        accent2:       Color(launchHex: 0x1a8a5a),
        watercan:      Color(launchHex: 0x5a7a4a),
        watercanShade: Color(launchHex: 0x3a5232),
        watercanHi:    Color(launchHex: 0x7a9a5a),
        leaf:          Color(launchHex: 0x3a7d44),
        leafDark:      Color(launchHex: 0x28552f),
        leafLight:     Color(launchHex: 0x6db35a),
        leafHi:        Color(launchHex: 0x9ad982),
        bark:          Color(launchHex: 0x5a3a22),
        barkLight:     Color(launchHex: 0x7a5538),
        waterDrop:     Color(rgba: 180, 220, 255, 0.95),
        waterStreak:   Color(rgba: 200, 230, 255, 0.70),
        pollen:        Color(rgba: 255, 240, 180, 0.85),
        bird:          Color(launchHex: 0x0a1410),
        badgeBg:       Color(rgba: 14, 31, 21, 0.92),
        badgeText:     Color(launchHex: 0xf0fde4),
        badgeAccent:   Color(launchHex: 0x9ade7a),
        logoTagline:   Color(rgba: 14, 31, 21, 0.65),
        feedBg:        Color(launchHex: 0xf6f5ef),
        feedAccent:    Color(launchHex: 0x1a3a26)
    )

    static let mist = LaunchPalette(
        skyTop:        Color(launchHex: 0xcad8df),
        skyMid:        Color(launchHex: 0xd6e1d8),
        skyHorizon:    Color(launchHex: 0xdde6cf),
        skyBase:       Color(launchHex: 0xe6ecd6),
        sun:           Color.white,
        sunGlow:       Color(rgba: 255, 255, 255, 0.55),
        rays:          Color(rgba: 255, 255, 255, 0.22),
        haze:          Color(rgba: 220, 230, 220, 0.55),
        cloud:         Color(rgba: 255, 255, 255, 0.70),
        mountainBack:  Color(launchHex: 0x8a9c91),
        mountainMid:   Color(launchHex: 0x5d7466),
        mountainFront: Color(launchHex: 0x384c3e),
        mountainHaze:  Color(rgba: 220, 225, 207, 0.60),
        ground:        Color(launchHex: 0x1f2c1c),
        grassDark:     Color(launchHex: 0x2c4226),
        grassLight:    Color(launchHex: 0x4f7c44),
        soil:          Color(launchHex: 0x2e2018),
        soilWet:       Color(launchHex: 0x15100a),
        stump:         Color(launchHex: 0x5a4030),
        stumpTop:      Color(launchHex: 0xa8866a),
        stumpRing:     Color(launchHex: 0x7a5e44),
        skin:          Color(launchHex: 0xf0c8a0),
        skinDark:      Color(launchHex: 0xd4a880),
        skinShade:     Color(launchHex: 0xc09870),
        hairDark:      Color(launchHex: 0x0e0604),
        hairBrown:     Color(launchHex: 0x1a0c06),
        hairLight:     Color(launchHex: 0x3a1c0c),
        fatherShirt:   Color(launchHex: 0xe0c060),
        fatherPants:   Color(launchHex: 0xf0e6cc),
        motherShirt:   Color(launchHex: 0x1a8a5a),
        motherPants:   Color(launchHex: 0x0e4a30),
        motherSkirt:   Color(launchHex: 0x1aa468),
        daughterShirt: Color(launchHex: 0xd63070),
        daughterSkirt: Color(launchHex: 0x5b18a8),
        sonShirt:      Color(launchHex: 0xe84028),
        sonPants:      Color(launchHex: 0xf5ecd2),
        gold:          Color(launchHex: 0xe8c060),
        goldDark:      Color(launchHex: 0xa08030),
        accent1:       Color(launchHex: 0xd63070),
        accent2:       Color(launchHex: 0x1a8a5a),
        watercan:      Color(launchHex: 0x7a8c75),
        watercanShade: Color(launchHex: 0x4d5b48),
        watercanHi:    Color(launchHex: 0xa3b59c),
        leaf:          Color(launchHex: 0x3a7d44),
        leafDark:      Color(launchHex: 0x2c5e35),
        leafLight:     Color(launchHex: 0x6db35a),
        leafHi:        Color(launchHex: 0x9ad982),
        bark:          Color(launchHex: 0x5a3a22),
        barkLight:     Color(launchHex: 0x7a5538),
        waterDrop:     Color(rgba: 180, 220, 255, 0.95),
        waterStreak:   Color(rgba: 200, 230, 255, 0.70),
        pollen:        Color(rgba: 255, 250, 220, 0.85),
        bird:          Color(launchHex: 0x16221b),
        badgeBg:       Color(rgba: 14, 31, 21, 0.92),
        badgeText:     Color(launchHex: 0xf0fde4),
        badgeAccent:   Color(launchHex: 0x9ade7a),
        logoTagline:   Color(rgba: 14, 31, 21, 0.62),
        feedBg:        Color(launchHex: 0xf4f6ef),
        feedAccent:    Color(launchHex: 0x1a3a26)
    )

    static let sunset = LaunchPalette(
        skyTop:        Color(launchHex: 0x3a2748),
        skyMid:        Color(launchHex: 0xcb5c4f),
        skyHorizon:    Color(launchHex: 0xf3a35c),
        skyBase:       Color(launchHex: 0xf7c082),
        sun:           Color(launchHex: 0xffd76a),
        sunGlow:       Color(rgba: 255, 154, 92, 0.70),
        rays:          Color(rgba: 255, 198, 130, 0.20),
        haze:          Color(rgba: 255, 134, 92, 0.30),
        cloud:         Color(rgba: 60, 30, 50, 0.45),
        mountainBack:  Color(launchHex: 0x321a30),
        mountainMid:   Color(launchHex: 0x1a0d20),
        mountainFront: Color(launchHex: 0x0a050c),
        mountainHaze:  Color(rgba: 243, 163, 92, 0.40),
        ground:        Color(launchHex: 0x0a050a),
        grassDark:     Color(launchHex: 0x15101a),
        grassLight:    Color(launchHex: 0x28202a),
        soil:          Color(launchHex: 0x0e070b),
        soilWet:       Color(launchHex: 0x050204),
        stump:         Color(launchHex: 0x1a0a06),
        stumpTop:      Color(launchHex: 0x3a2418),
        stumpRing:     Color(launchHex: 0x2a1810),
        skin:          Color(launchHex: 0xd8a878),
        skinDark:      Color(launchHex: 0xb88860),
        skinShade:     Color(launchHex: 0xa87850),
        hairDark:      Color(launchHex: 0x0a0402),
        hairBrown:     Color(launchHex: 0x1a0a06),
        hairLight:     Color(launchHex: 0x2a1408),
        fatherShirt:   Color(launchHex: 0xc46028),
        fatherPants:   Color(launchHex: 0x1a0a08),
        motherShirt:   Color(launchHex: 0xa01840),
        motherPants:   Color(launchHex: 0x3a0818),
        motherSkirt:   Color(launchHex: 0xc0285a),
        daughterShirt: Color(launchHex: 0xe8a020),
        daughterSkirt: Color(launchHex: 0x7a1838),
        sonShirt:      Color(launchHex: 0x1a4878),
        sonPants:      Color(launchHex: 0x1a0a08),
        gold:          Color(launchHex: 0xd8a040),
        goldDark:      Color(launchHex: 0x8a6020),
        accent1:       Color(launchHex: 0x7a1838),
        accent2:       Color(launchHex: 0xc46028),
        watercan:      Color(launchHex: 0x3a2218),
        watercanShade: Color(launchHex: 0x1a0e08),
        watercanHi:    Color(launchHex: 0x5a3828),
        leaf:          Color(launchHex: 0x5fa051),
        leafDark:      Color(launchHex: 0x3d6f37),
        leafLight:     Color(launchHex: 0x9bd47b),
        leafHi:        Color(launchHex: 0xcaf0a0),
        bark:          Color(launchHex: 0x3a2418),
        barkLight:     Color(launchHex: 0x5a3828),
        waterDrop:     Color(rgba: 255, 220, 180, 0.92),
        waterStreak:   Color(rgba: 255, 220, 180, 0.55),
        pollen:        Color(rgba: 255, 200, 130, 0.85),
        bird:          Color.black,
        badgeBg:       Color(rgba: 20, 8, 16, 0.92),
        badgeText:     Color(launchHex: 0xffe9c8),
        badgeAccent:   Color(launchHex: 0xffb86b),
        logoTagline:   Color(rgba: 255, 245, 227, 0.70),
        feedBg:        Color(launchHex: 0x1a0e16),
        feedAccent:    Color(launchHex: 0xffb86b)
    )
}

// MARK: - Color helpers

extension Color {
    /// Build a `Color` from a packed `0xRRGGBB` integer. Named
    /// `launchHex:` (rather than the more obvious `hex:`) to avoid
    /// colliding with `QuickInkColors`'s own `Color(hex:)` extension
    /// in the same module — duplicated initializers on a public
    /// extension would shadow each other.
    init(launchHex value: UInt32, opacity: Double = 1.0) {
        let r = Double((value >> 16) & 0xFF) / 255.0
        let g = Double((value >>  8) & 0xFF) / 255.0
        let b = Double( value        & 0xFF) / 255.0
        self.init(.sRGB, red: r, green: g, blue: b, opacity: opacity)
    }

    /// Build a `Color` from RGB byte channels + a `[0,1]` alpha.
    /// Mirrors the JSX prototype's `rgba(r, g, b, a)` literals so
    /// alpha tokens like `cloud: 'rgba(255, 244, 220, 0.7)'` carry
    /// across word-for-word.
    init(rgba r: Int, _ g: Int, _ b: Int, _ a: Double) {
        self.init(
            .sRGB,
            red:     Double(r) / 255.0,
            green:   Double(g) / 255.0,
            blue:    Double(b) / 255.0,
            opacity: a
        )
    }
}
