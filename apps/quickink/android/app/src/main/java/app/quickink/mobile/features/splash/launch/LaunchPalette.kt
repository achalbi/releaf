/*
 * LaunchPalette.kt
 *
 * Color palettes for the cinematic launch animation. Three variants
 * — Dawn, Morning Mist, Golden Hour — handed off in the React
 * prototype (`design_handoff_quickink_launch/source/scene.jsx`,
 * `PALETTES` constant). Each palette is a flat data class of every
 * color the scene's 14 layers reference, by name.
 *
 * Why a flat data class (not nested by layer): the React source
 * treats the palette as a single big bag (`c.skyTop`, `c.fatherShirt`,
 * `c.waterDrop`, …). Mirroring that shape keeps the per-layer Compose
 * code reading like a 1:1 port of the JSX, which makes cross-platform
 * parity audits trivial — search for a token name, compare iOS vs
 * Android vs the prototype line by line.
 *
 * Counterpart: iOS `LaunchPalette.swift`. Hex values must match
 * exactly so the cinematic looks identical across platforms.
 */

package app.quickink.mobile.features.splash.launch

import androidx.compose.ui.graphics.Color

/**
 * All colors a single launch-animation palette exposes. Field names
 * mirror the keys in the JSX `PALETTES.*` records.
 */
internal data class LaunchPalette(
    // Sky + sun
    val skyTop:        Color,
    val skyMid:        Color,
    val skyHorizon:    Color,
    val skyBase:       Color,
    val sun:           Color,
    val sunGlow:       Color,
    val rays:          Color,
    val haze:          Color,
    val cloud:         Color,

    // Mountains + ground
    val mountainBack:  Color,
    val mountainMid:   Color,
    val mountainFront: Color,
    val mountainHaze:  Color,
    val ground:        Color,
    val grassDark:     Color,
    val grassLight:    Color,
    val soil:          Color,
    val soilWet:       Color,

    // Stumps
    val stump:     Color,
    val stumpTop:  Color,
    val stumpRing: Color,

    // Family — skin / hair
    val skin:      Color,
    val skinDark:  Color,
    val skinShade: Color,
    val hairDark:  Color,
    val hairBrown: Color,
    val hairLight: Color,

    // Family — clothing
    val fatherShirt:   Color,
    val fatherPants:   Color,
    val motherShirt:   Color,
    val motherPants:   Color,
    val motherSkirt:   Color,
    val daughterShirt: Color,
    val daughterSkirt: Color,
    val sonShirt:      Color,
    val sonPants:      Color,

    // Accents — gold, generic
    val gold:     Color,
    val goldDark: Color,
    val accent1:  Color,
    val accent2:  Color,

    // Watering can
    val watercan:      Color,
    val watercanShade: Color,
    val watercanHi:    Color,

    // Tree
    val leaf:      Color,
    val leafDark:  Color,
    val leafLight: Color,
    val leafHi:    Color,
    val bark:      Color,
    val barkLight: Color,

    // Water + atmosphere
    val waterDrop:   Color,
    val waterStreak: Color,
    val pollen:      Color,
    val bird:        Color,

    // UI badges + logo + feed
    val badgeBg:     Color,
    val badgeText:   Color,
    val badgeAccent: Color,
    val logoTagline: Color,
    val feedBg:      Color,
    val feedAccent:  Color,
)

/** Build a [Color] from `0xRRGGBB` with full opacity. */
private fun hex(value: Int): Color = Color(0xFF000000.toInt() or value)

/** Build a [Color] from rgba bytes + a `[0,1]` alpha. */
private fun rgba(r: Int, g: Int, b: Int, a: Double): Color =
    Color(red = r / 255f, green = g / 255f, blue = b / 255f, alpha = a.toFloat())

/**
 * Three palettes from the prototype. `Dawn` is the default — the
 * shipping splash uses it unconditionally, with `Mist` and `Sunset`
 * available for future seasonal A/B or marketing renders.
 */
internal object LaunchPalettes {
    val Dawn = LaunchPalette(
        skyTop        = hex(0xfce4c4),
        skyMid        = hex(0xf8d2a3),
        skyHorizon    = hex(0xe8e3b6),
        skyBase       = hex(0xdfe9c8),
        sun           = hex(0xfff4c8),
        sunGlow       = rgba(253, 204, 126, 0.55),
        rays          = rgba(255, 230, 180, 0.18),
        haze          = rgba(255, 220, 170, 0.40),
        cloud         = rgba(255, 244, 220, 0.70),
        mountainBack  = hex(0x7a8a6c),
        mountainMid   = hex(0x4a6048),
        mountainFront = hex(0x2c402a),
        mountainHaze  = rgba(232, 227, 182, 0.55),
        ground        = hex(0x1f2c1c),
        grassDark     = hex(0x2d4a26),
        grassLight    = hex(0x4f7c43),
        soil          = hex(0x3a2a1c),
        soilWet       = hex(0x1f140a),
        stump         = hex(0x5a3a22),
        stumpTop      = hex(0xa87850),
        stumpRing     = hex(0x7a5538),
        skin          = hex(0xe8b890),
        skinDark      = hex(0xc89870),
        skinShade     = hex(0xb88860),
        hairDark      = hex(0x0e0604),
        hairBrown     = hex(0x1a0c06),
        hairLight     = hex(0x3a1c0c),
        fatherShirt   = hex(0xe89020),
        fatherPants   = hex(0xf5ecd2),
        motherShirt   = hex(0xd63a7a),
        motherPants   = hex(0x7a1838),
        motherSkirt   = hex(0xe63070),
        daughterShirt = hex(0xe84080),
        daughterSkirt = hex(0xf4a020),
        sonShirt      = hex(0x2080a0),
        sonPants      = hex(0xf0e8d8),
        gold          = hex(0xf0c850),
        goldDark      = hex(0xb48830),
        accent1       = hex(0x5b18a8),
        accent2       = hex(0x1a8a5a),
        watercan      = hex(0x5a7a4a),
        watercanShade = hex(0x3a5232),
        watercanHi    = hex(0x7a9a5a),
        leaf          = hex(0x3a7d44),
        leafDark      = hex(0x28552f),
        leafLight     = hex(0x6db35a),
        leafHi        = hex(0x9ad982),
        bark          = hex(0x5a3a22),
        barkLight     = hex(0x7a5538),
        waterDrop     = rgba(180, 220, 255, 0.95),
        waterStreak   = rgba(200, 230, 255, 0.70),
        pollen        = rgba(255, 240, 180, 0.85),
        bird          = hex(0x0a1410),
        badgeBg       = rgba(14, 31, 21, 0.92),
        badgeText     = hex(0xf0fde4),
        badgeAccent   = hex(0x9ade7a),
        logoTagline   = rgba(14, 31, 21, 0.65),
        feedBg        = hex(0xf6f5ef),
        feedAccent    = hex(0x1a3a26),
    )

    val Mist = LaunchPalette(
        skyTop        = hex(0xcad8df),
        skyMid        = hex(0xd6e1d8),
        skyHorizon    = hex(0xdde6cf),
        skyBase       = hex(0xe6ecd6),
        sun           = Color.White,
        sunGlow       = rgba(255, 255, 255, 0.55),
        rays          = rgba(255, 255, 255, 0.22),
        haze          = rgba(220, 230, 220, 0.55),
        cloud         = rgba(255, 255, 255, 0.70),
        mountainBack  = hex(0x8a9c91),
        mountainMid   = hex(0x5d7466),
        mountainFront = hex(0x384c3e),
        mountainHaze  = rgba(220, 225, 207, 0.60),
        ground        = hex(0x1f2c1c),
        grassDark     = hex(0x2c4226),
        grassLight    = hex(0x4f7c44),
        soil          = hex(0x2e2018),
        soilWet       = hex(0x15100a),
        stump         = hex(0x5a4030),
        stumpTop      = hex(0xa8866a),
        stumpRing     = hex(0x7a5e44),
        skin          = hex(0xf0c8a0),
        skinDark      = hex(0xd4a880),
        skinShade     = hex(0xc09870),
        hairDark      = hex(0x0e0604),
        hairBrown     = hex(0x1a0c06),
        hairLight     = hex(0x3a1c0c),
        fatherShirt   = hex(0xe0c060),
        fatherPants   = hex(0xf0e6cc),
        motherShirt   = hex(0x1a8a5a),
        motherPants   = hex(0x0e4a30),
        motherSkirt   = hex(0x1aa468),
        daughterShirt = hex(0xd63070),
        daughterSkirt = hex(0x5b18a8),
        sonShirt      = hex(0xe84028),
        sonPants      = hex(0xf5ecd2),
        gold          = hex(0xe8c060),
        goldDark      = hex(0xa08030),
        accent1       = hex(0xd63070),
        accent2       = hex(0x1a8a5a),
        watercan      = hex(0x7a8c75),
        watercanShade = hex(0x4d5b48),
        watercanHi    = hex(0xa3b59c),
        leaf          = hex(0x3a7d44),
        leafDark      = hex(0x2c5e35),
        leafLight     = hex(0x6db35a),
        leafHi        = hex(0x9ad982),
        bark          = hex(0x5a3a22),
        barkLight     = hex(0x7a5538),
        waterDrop     = rgba(180, 220, 255, 0.95),
        waterStreak   = rgba(200, 230, 255, 0.70),
        pollen        = rgba(255, 250, 220, 0.85),
        bird          = hex(0x16221b),
        badgeBg       = rgba(14, 31, 21, 0.92),
        badgeText     = hex(0xf0fde4),
        badgeAccent   = hex(0x9ade7a),
        logoTagline   = rgba(14, 31, 21, 0.62),
        feedBg        = hex(0xf4f6ef),
        feedAccent    = hex(0x1a3a26),
    )

    val Sunset = LaunchPalette(
        skyTop        = hex(0x3a2748),
        skyMid        = hex(0xcb5c4f),
        skyHorizon    = hex(0xf3a35c),
        skyBase       = hex(0xf7c082),
        sun           = hex(0xffd76a),
        sunGlow       = rgba(255, 154, 92, 0.70),
        rays          = rgba(255, 198, 130, 0.20),
        haze          = rgba(255, 134, 92, 0.30),
        cloud         = rgba(60, 30, 50, 0.45),
        mountainBack  = hex(0x321a30),
        mountainMid   = hex(0x1a0d20),
        mountainFront = hex(0x0a050c),
        mountainHaze  = rgba(243, 163, 92, 0.40),
        ground        = hex(0x0a050a),
        grassDark     = hex(0x15101a),
        grassLight    = hex(0x28202a),
        soil          = hex(0x0e070b),
        soilWet       = hex(0x050204),
        stump         = hex(0x1a0a06),
        stumpTop      = hex(0x3a2418),
        stumpRing     = hex(0x2a1810),
        skin          = hex(0xd8a878),
        skinDark      = hex(0xb88860),
        skinShade     = hex(0xa87850),
        hairDark      = hex(0x0a0402),
        hairBrown     = hex(0x1a0a06),
        hairLight     = hex(0x2a1408),
        fatherShirt   = hex(0xc46028),
        fatherPants   = hex(0x1a0a08),
        motherShirt   = hex(0xa01840),
        motherPants   = hex(0x3a0818),
        motherSkirt   = hex(0xc0285a),
        daughterShirt = hex(0xe8a020),
        daughterSkirt = hex(0x7a1838),
        sonShirt      = hex(0x1a4878),
        sonPants      = hex(0x1a0a08),
        gold          = hex(0xd8a040),
        goldDark      = hex(0x8a6020),
        accent1       = hex(0x7a1838),
        accent2       = hex(0xc46028),
        watercan      = hex(0x3a2218),
        watercanShade = hex(0x1a0e08),
        watercanHi    = hex(0x5a3828),
        leaf          = hex(0x5fa051),
        leafDark      = hex(0x3d6f37),
        leafLight     = hex(0x9bd47b),
        leafHi        = hex(0xcaf0a0),
        bark          = hex(0x3a2418),
        barkLight     = hex(0x5a3828),
        waterDrop     = rgba(255, 220, 180, 0.92),
        waterStreak   = rgba(255, 220, 180, 0.55),
        pollen        = rgba(255, 200, 130, 0.85),
        bird          = Color.Black,
        badgeBg       = rgba(20, 8, 16, 0.92),
        badgeText     = hex(0xffe9c8),
        badgeAccent   = hex(0xffb86b),
        logoTagline   = rgba(255, 245, 227, 0.70),
        feedBg        = hex(0x1a0e16),
        feedAccent    = hex(0xffb86b),
    )
}

/**
 * Heuristic — treat the feed background as "dark" if its luminance
 * is low enough that ink-dark text on it would fail contrast. Used
 * by the logo + feed transition to flip text colors when the palette
 * is `Sunset`. Mirrors the JSX `c.feedBg.startsWith('#1') || ...`
 * shortcut with a real (cheap) luma calc.
 */
internal val LaunchPalette.feedIsDark: Boolean
    get() {
        val r = feedBg.red
        val g = feedBg.green
        val b = feedBg.blue
        val lum = 0.299f * r + 0.587f * g + 0.114f * b
        return lum < 0.4f
    }
