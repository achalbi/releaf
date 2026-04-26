/*
 * ActivityItem.swift
 *
 * iOS mirror of `data/activity/ActivityItem.kt`.
 *
 * Phase-1 activity feed primitive — derived view, not a stored entity.
 * The feed is synthesized from the `updated_at` columns already on the
 * notepad / page / chapter / notebook tables; nothing new lands on
 * disk. When (and if) phase 2 lands a real `audit_events` table on
 * iOS, this type stays unchanged — only the source switches.
 *
 * Each row carries enough metadata for the timeline UI to render an
 * icon, label, time, and a tap target without re-querying. The
 * `entityId` keeps the row navigable.
 */

import Foundation

/// Which side of the app the row originated from. Drives the icon /
/// accent palette in the timeline UI. The `photo / scan / voice /
/// todo / contact / location` variants are sub-event captures (phase
/// 3.5) — the parent entity (NotepadEntry or Page) is referenced via
/// `entityId`, the captured item label sits in `title`.
public enum ActivityKind: String, Sendable {
    case notepadEntry
    case page
    case chapter
    case notebook
    case photo
    case scan
    case voice
    case todo
    case contact
    case location
}

/// Audit-log action ladder. Add new values — never remove existing
/// ones (the audit table, when it lands, will store the string name).
public enum ActivityAction: String, Sendable {
    case created
    case updated
    case deleted
    case restored
    case merged
    case moved
}

/// One row in the activity feed.
public struct ActivityItem: Identifiable, Equatable, Sendable {
    /// Stable identity for SwiftUI list keys; combines kind + entity id + action.
    public let id: String
    public let kind: ActivityKind
    public let action: ActivityAction
    /// The underlying notepad/page/chapter/notebook id (or, for
    /// sub-event captures, the parent entity id) — used for navigation.
    public let entityId: String
    /// ISO-8601 UTC with ms.
    public let timestamp: String
    /// Human label rendered in the row.
    public let title: String
    /// Breadcrumb-style hierarchy string for sub-event captures
    /// (e.g. "Releaf garden › Chapter 1 › Page A"); nil for entity-level events.
    public let context: String?

    public init(
        id: String,
        kind: ActivityKind,
        action: ActivityAction,
        entityId: String,
        timestamp: String,
        title: String,
        context: String? = nil
    ) {
        self.id = id
        self.kind = kind
        self.action = action
        self.entityId = entityId
        self.timestamp = timestamp
        self.title = title
        self.context = context
    }
}
