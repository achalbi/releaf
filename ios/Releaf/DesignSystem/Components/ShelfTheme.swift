/*
 * ShelfTheme.swift
 * Maps a Notebook's `colorToken` + `iconKey` onto the hero-card
 * visuals used by the variant-1 shelves / chapters / page screens.
 *
 * Keeping this in the design system means the color palette + icon
 * registry stay in one place; the feature screens just read tokens.
 */

import SwiftUI

public struct ShelfPalette: Equatable, Sendable {
    public let background: Color
    public let onBackground: Color
    public let onBackgroundMuted: Color
    public let accentSoft: Color
}

public enum ShelfTheme {

    /// Resolve a palette for a given color token.
    public static func palette(for token: String?) -> ShelfPalette {
        switch token?.lowercased() {
        case "green":
            return ShelfPalette(
                background: Color(hex: 0x7AA874),
                onBackground: Color(hex: 0xF5EEDF),
                onBackgroundMuted: Color(hex: 0xF5EEDF, alpha: 0.78),
                accentSoft: Color(hex: 0xDCE7CF)
            )
        case "info", "purple":
            return ShelfPalette(
                background: Color(hex: 0x8E86DB),
                onBackground: Color(hex: 0xF5EEDF),
                onBackgroundMuted: Color(hex: 0xF5EEDF, alpha: 0.78),
                accentSoft: Color(hex: 0xE1DEF4)
            )
        case "dry":
            return ShelfPalette(
                background: Color(hex: 0xB8956A),
                onBackground: Color(hex: 0x241D17),
                onBackgroundMuted: Color(hex: 0x241D17, alpha: 0.66),
                accentSoft: Color(hex: 0xE8D8BE)
            )
        case "yellow":
            return ShelfPalette(
                background: Color(hex: 0xF4C430),
                onBackground: Color(hex: 0x241D17),
                onBackgroundMuted: Color(hex: 0x241D17, alpha: 0.66),
                accentSoft: Color(hex: 0xFBE9A6)
            )
        case "coral":
            return ShelfPalette(
                background: Color(hex: 0xE07856),
                onBackground: Color(hex: 0xF5EEDF),
                onBackgroundMuted: Color(hex: 0xF5EEDF, alpha: 0.78),
                accentSoft: Color(hex: 0xFCD7C7)
            )
        default:
            return ShelfPalette(
                background: Color(hex: 0x7AA874),
                onBackground: Color(hex: 0xF5EEDF),
                onBackgroundMuted: Color(hex: 0xF5EEDF, alpha: 0.78),
                accentSoft: Color(hex: 0xDCE7CF)
            )
        }
    }

    /// Pick an SF Symbol-ish glyph for the hero icon. We lean on SF
    /// Symbols that broadly match the Figma pictograms.
    public static func iconSystemName(for iconKey: String?) -> String {
        switch iconKey?.lowercased() {
        case "plant":  return "leaf"
        case "chart":  return "chart.bar.fill"
        case "sun":    return "sun.max"
        case "book":   return "book"
        case "coffee": return "cup.and.saucer"
        default:       return "leaf"
        }
    }
}
