/*
 * CaptureMode.swift
 *
 * The 7 capture flavors Releaf supports. Lives in the design system
 * because it's intrinsically a UI concept — the tab bar, quick-capture
 * sheet, and page-detail sections all render off it.
 *
 * Shape mirrors the Inkcreate mobile DS (title / subtitle / systemIcon).
 * `label` / `systemImage` are kept as back-compat aliases for older
 * call sites that haven't been migrated yet.
 */

import SwiftUI

public enum CaptureMode: String, CaseIterable, Identifiable, Sendable {
    case overview
    case photos
    case voice
    case todo
    case scans
    case contacts
    case location

    public var id: String { rawValue }

    public var title: String {
        switch self {
        case .overview: return "Overview"
        case .photos:   return "Photos"
        case .voice:    return "Voice note"
        case .todo:     return "To-do"
        case .scans:    return "Scan document"
        case .contacts: return "Contact"
        case .location: return "Location"
        }
    }

    public var subtitle: String {
        switch self {
        case .overview: return "All sections at a glance"
        case .photos:   return "Camera or upload"
        case .voice:    return "Record audio"
        case .todo:     return "Quick checklist item"
        case .scans:    return "Capture a document page"
        case .contacts: return "Phone, email, website"
        case .location: return "Tag current GPS"
        }
    }

    public var systemIcon: String {
        switch self {
        case .overview: return "square.grid.2x2"
        case .photos:   return "camera"
        case .voice:    return "mic"
        case .todo:     return "checklist"
        case .scans:    return "doc.viewfinder"
        case .contacts: return "person.crop.circle"
        case .location: return "mappin.and.ellipse"
        }
    }

    // MARK: - Back-compat aliases
    //
    // Older screens (pre-DS port) used `.label` / `.systemImage`. Kept as
    // thin shims so nothing breaks mid-port; remove once all call sites
    // are on the new API.

    public var label: String { title }
    public var systemImage: String { systemIcon }
}
