/*
 * SmartCollectionAppearance.swift
 *
 * Visual palette + icon mapping for the smart-collection card.
 * `SmartCollectionEntity.icon` is a free-form TEXT slug — the
 * brief's design system uses Tabler icon names like
 * `"ti-receipt"`, but the in-app surface ships SF Symbols, so
 * this file keeps a small SF-flavored palette and resolves slugs
 * to symbol names with a stable fallback.
 *
 * Mirror of `SmartCollectionAppearance.kt` (Android).
 *
 * Color authoring reuses [WorkspaceFolderPalette] so smart
 * collections and folders speak the same visual dialect.
 */

import Foundation

/// One icon choice in the editor + the slug stored on disk.
struct SmartCollectionIconOption: Hashable, Sendable {
    let slug: String
    let symbol: String
}

/// Editor's icon palette. The first entry is the implicit default
/// for new collections (and the fallback for any unknown slug a
/// cross-device payload might carry).
let SmartCollectionIconPalette: [SmartCollectionIconOption] = [
    SmartCollectionIconOption(slug: "sparkle",  symbol: "sparkles"),
    SmartCollectionIconOption(slug: "star",     symbol: "star"),
    SmartCollectionIconOption(slug: "receipt",  symbol: "doc.plaintext"),
    SmartCollectionIconOption(slug: "doc",      symbol: "doc.text"),
    SmartCollectionIconOption(slug: "folder",   symbol: "folder"),
    SmartCollectionIconOption(slug: "idea",     symbol: "lightbulb"),
    SmartCollectionIconOption(slug: "work",     symbol: "briefcase"),
    SmartCollectionIconOption(slug: "check",    symbol: "checkmark.circle"),
    SmartCollectionIconOption(slug: "bookmark", symbol: "bookmark"),
    SmartCollectionIconOption(slug: "flag",     symbol: "flag"),
]

/// Resolve a stored icon slug to an SF Symbol name. Returns the
/// palette's first symbol for nil / unknown values so a fresh
/// collection's card never renders an empty tile.
func iconSymbolForSlug(_ slug: String?) -> String {
    if let slug,
       let opt = SmartCollectionIconPalette.first(where: { $0.slug == slug }) {
        return opt.symbol
    }
    return SmartCollectionIconPalette.first?.symbol ?? "sparkles"
}
