/*
 * NotepadRoute.swift
 *
 * Navigation route for the Notepad tab's NavigationStack. Separating the
 * route into its own type keeps `.navigationDestination(for:)` readable
 * and gives us a single place to add future routes (e.g. share sheets,
 * search-result deep links).
 */

import Foundation

/// Route to the single-entry editor. `entryId` is either a UUIDv7 or the
/// sentinel `NotepadEditorViewModel.newEntryId` for a brand-new draft.
public struct NotepadEditorRoute: Hashable, Sendable {
    public let entryId: String
    public init(entryId: String) { self.entryId = entryId }
}
