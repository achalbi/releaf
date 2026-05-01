package app.releaf.mobile.features.notepad.recents.theme

import androidx.compose.ui.graphics.Color

// Brand color tokens for Releaf Notepad
// Cream/parchment surfaces + green accents, with amber for imported/new-entry slots.

val BgCanvas = Color(0xFFF4EDDC)
val BgSurface = Color(0xFFFBF5E2)
val BgSurfaceMuted = Color(0xFFEFE7CD)
val BgChip = Color(0xFFDDEACD)
val BgFeatured = Color(0xFFDDEACD)

val Green900 = Color(0xFF2C4520)
val Green800 = Color(0xFF3F5C2C) // primary
val Green600 = Color(0xFF5B7A3F)
val Green400 = Color(0xFF7AA055)
val Green200 = Color(0xFFC0DD97)
val Green100 = Color(0xFFDDEACD)

val Cream300 = Color(0xFFEDE3CC)

val TextPrimary = Color(0xFF1F1E18)
val TextSecondary = Color(0xFF5C5C50)
val TextMuted = Color(0xFF6B6B5E)
val TextOnDark = Color(0xFFF4EDDC)
val TextOnDarkMuted = Color(0xFFDDEACD)
val TextOnDarkSubtle = Color(0xFFC0DD97)
val TextGreen = Color(0xFF3F5C2C)
val TextGreenMuted = Color(0xFF6B8A4F)

val AccentImport = Color(0xFFBA7517)
val AccentImportBg = Color(0xFFF0E0B8)

// Translucent overlays for use on dark green surfaces
val OnDark10 = Color(0xFFF4EDDC).copy(alpha = 0.10f)
val OnDark14 = Color(0xFFF4EDDC).copy(alpha = 0.14f)
val OnDark16 = Color(0xFFF4EDDC).copy(alpha = 0.16f)
val OnDark25 = Color(0xFFC0DD97).copy(alpha = 0.25f)
val OnDark30 = Color(0xFFF4EDDC).copy(alpha = 0.30f)

// Amber tint used for the new-entry pill background, dashed borders, etc.
val AmberPillBg = Color(0xFFF0E0B8).copy(alpha = 0.22f)

val BorderFaint = Color(0xFF3F5C2C).copy(alpha = 0.10f)
val BorderDashed = Color(0xFF3F5C2C).copy(alpha = 0.20f)
val BorderDivider = Color(0xFF3F5C2C).copy(alpha = 0.20f)
