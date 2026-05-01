/*
 * NotepadRoute.swift
 *
 * Navigation route for the Notepad tab's NavigationStack. Separating the
 * route into its own type keeps `.navigationDestination(for:)` readable
 * and gives us a single place to add future routes (e.g. share sheets,
 * search-result deep links).
 */

import Foundation
import ReleafDesignSystem

/// Route to the single-entry editor. `entryId` is either a UUIDv7 or the
/// sentinel `NotepadEditorViewModel.newEntryId` for a brand-new draft.
/// `initialMode` lets a caller deep-link to a specific feature section
/// (Photos, Scans, Voice, etc.) — used by the Recents new-entry picker
/// so tapping "Photo" lands the user inside the Photos section.
public struct NotepadEditorRoute: Hashable, Sendable {
    public let entryId: String
    public let initialMode: CaptureMode?
    public init(entryId: String, initialMode: CaptureMode? = nil) {
        self.entryId = entryId
        self.initialMode = initialMode
    }
}
