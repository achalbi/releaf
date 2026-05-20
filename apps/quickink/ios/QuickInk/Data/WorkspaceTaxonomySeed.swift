/*
 * WorkspaceTaxonomySeed.swift
 *
 * Phase 2 of the Workspace tab refresh
 * (`design/WORKSPACE_TAB_HANDOFF.md`):
 *
 *   - `workspaceFolderSeeds`  — the 12 spec'd folders with stable
 *                               IDs (`inbox`, `archive`, `finance`, …),
 *                               their tier (1 Workflow / 2 Life
 *                               domains / 3 Creative & output), type
 *                               (Inbox / Archive / Project / Reference)
 *                               and descriptive copy.
 *   - `workspaceTagSeeds`     — the 32 spec'd tags with stable IDs
 *                               (`tag-status-active`, `tag-people-mom`,
 *                               …) and bucket assignment.
 *   - `seedWorkspaceTaxonomyIfNeeded(userId:)` — idempotent first-
 *                               launch seeder. Stable IDs + the
 *                               (user_id, name, is_seeded) partial
 *                               UNIQUE on folders / the (user_id,
 *                               name) partial UNIQUE on tags keep
 *                               re-runs safe.
 *
 * Both platforms mirror the same set; the canonical source is
 * `shared/design-system/seeds/workspace_seed.json`. Keep this file
 * in sync with the JSON on every edit — there's no runtime parse
 * (avoids a launch-time Bundle hit) so the diff happens at code
 * review.
 *
 * Mirror of `WorkspaceTaxonomySeed.kt` (Android).
 */

import Foundation
import GRDB
import ReleafCoreData

// MARK: - Folder seed

public struct WorkspaceFolderSeed: Sendable {
    public let id: String
    public let name: String
    public let tier: Int
    public let type: String
    public let color: String
    public let desc: String
    public let isSystemManaged: Bool

    public init(id: String, name: String, tier: Int, type: String, color: String, desc: String, isSystemManaged: Bool = false) {
        self.id              = id
        self.name            = name
        self.tier            = tier
        self.type            = type
        self.color           = color
        self.desc            = desc
        self.isSystemManaged = isSystemManaged
    }
}

/// 12 folders, three tiers — mirror of `workspace_seed.json` §folders.
public let workspaceFolderSeeds: [WorkspaceFolderSeed] = [
    WorkspaceFolderSeed(id: "inbox",     name: "Inbox",     tier: 1, type: "Inbox",     color: "#6366F1", desc: "Capture zone — everything lands here first", isSystemManaged: true),
    WorkspaceFolderSeed(id: "archive",   name: "Archive",   tier: 1, type: "Archive",   color: "#6366F1", desc: "Completed or dormant, kept for reference"),

    WorkspaceFolderSeed(id: "finance",   name: "Finance",   tier: 2, type: "Reference", color: "#2C2826", desc: "bills, taxes, subscriptions"),
    WorkspaceFolderSeed(id: "medical",   name: "Medical",   tier: 2, type: "Reference", color: "#2C2826", desc: "appointments, scripts, results"),
    WorkspaceFolderSeed(id: "family",    name: "Family",    tier: 2, type: "Reference", color: "#2C2826", desc: "people, relationships, home"),
    WorkspaceFolderSeed(id: "travel",    name: "Travel",    tier: 2, type: "Project",   color: "#2C2826", desc: "trips, bookings, places"),
    WorkspaceFolderSeed(id: "events",    name: "Events",    tier: 2, type: "Project",   color: "#2C2826", desc: "weddings, parties, conferences"),
    WorkspaceFolderSeed(id: "legal",     name: "Legal",     tier: 2, type: "Reference", color: "#2C2826", desc: "contracts, IDs, records"),
    WorkspaceFolderSeed(id: "lifestyle", name: "Lifestyle", tier: 2, type: "Reference", color: "#2C2826", desc: "fitness, food, hobbies, style"),

    WorkspaceFolderSeed(id: "learning",  name: "Learning",  tier: 3, type: "Reference", color: "#10B981", desc: "notes from books, podcasts"),
    WorkspaceFolderSeed(id: "projects",  name: "Projects",  tier: 3, type: "Project",   color: "#10B981", desc: "committed efforts w/ an end"),
    WorkspaceFolderSeed(id: "ideas",     name: "Ideas",     tier: 3, type: "Reference", color: "#10B981", desc: "sparks, someday/maybe"),
]

// MARK: - Tag seed

public struct WorkspaceTagSeed: Sendable {
    public let id: String
    public let name: String
    public let bucket: String

    public init(id: String, name: String, bucket: String) {
        self.id     = id
        self.name   = name
        self.bucket = bucket
    }
}

/// 32 tags, 7 buckets — mirror of `workspace_seed.json` §buckets.
public let workspaceTagSeeds: [WorkspaceTagSeed] = [
    // Status (controlled)
    .init(id: "tag-status-active", name: "active", bucket: "status"),
    .init(id: "tag-status-todo",   name: "todo",   bucket: "status"),
    .init(id: "tag-status-later",  name: "later",  bucket: "status"),
    .init(id: "tag-status-done",   name: "done",   bucket: "status"),

    // People (prefixed `p/`)
    .init(id: "tag-people-mom",     name: "p/mom",     bucket: "people"),
    .init(id: "tag-people-manager", name: "p/manager", bucket: "people"),
    .init(id: "tag-people-sarah",   name: "p/sarah",   bucket: "people"),

    // Org & Place (prefixed `org/`, `place/`)
    .init(id: "tag-orgplace-aws",    name: "org/aws",      bucket: "orgplace"),
    .init(id: "tag-orgplace-clinic", name: "org/clinic",   bucket: "orgplace"),
    .init(id: "tag-orgplace-lisbon", name: "place/lisbon", bucket: "orgplace"),
    .init(id: "tag-orgplace-home",   name: "place/home",   bucket: "orgplace"),

    // Energy (controlled)
    .init(id: "tag-energy-focus",   name: "focus",   bucket: "energy"),
    .init(id: "tag-energy-shallow", name: "shallow", bucket: "energy"),
    .init(id: "tag-energy-errand",  name: "errand",  bucket: "energy"),
    .init(id: "tag-energy-call",    name: "call",    bucket: "energy"),

    // Time-sensitivity (controlled + exclusive)
    .init(id: "tag-time-today",     name: "today",     bucket: "time"),
    .init(id: "tag-time-thisweek",  name: "thisweek",  bucket: "time"),
    .init(id: "tag-time-thismonth", name: "thismonth", bucket: "time"),

    // Kind
    .init(id: "tag-kind-idea",      name: "idea",      bucket: "kind"),
    .init(id: "tag-kind-quote",     name: "quote",     bucket: "kind"),
    .init(id: "tag-kind-recipe",    name: "recipe",    bucket: "kind"),
    .init(id: "tag-kind-checklist", name: "checklist", bucket: "kind"),
    .init(id: "tag-kind-template",  name: "template",  bucket: "kind"),

    // Source (controlled + auto-applied)
    .init(id: "tag-source-scan",         name: "scan",         bucket: "source"),
    .init(id: "tag-source-voice",        name: "voice",        bucket: "source"),
    .init(id: "tag-source-handwritten",  name: "handwritten",  bucket: "source"),
    .init(id: "tag-source-web",          name: "web",          bucket: "source"),
    .init(id: "tag-source-email",        name: "email",        bucket: "source"),
    .init(id: "tag-source-book",         name: "book",         bucket: "source"),
    .init(id: "tag-source-podcast",      name: "podcast",      bucket: "source"),
    .init(id: "tag-source-article",      name: "article",      bucket: "source"),
    .init(id: "tag-source-conversation", name: "conversation", bucket: "source"),
]

// MARK: - Bucket lookup (for the write-boundary enforcement)

/// Look up the bucket metadata for a given bucket id. Used by the
/// write boundary in `CaptureTagRepository` to enforce
/// `controlled` / `exclusive` / `auto_applied`.
public func workspaceBucket(forId id: String) -> TagBucket? {
    workspaceTagBuckets.first { $0.id == id }
}

// MARK: - Seeder

/// Insert the 12 folders + 32 tags as seeded rows for `userId`,
/// idempotently. Safe to call on every launch — stable IDs + the
/// folders (user_id, name, is_seeded) partial UNIQUE and tags
/// (user_id, name) partial UNIQUE short-circuit subsequent runs.
///
/// User-created folders that happen to share a name with a seeded
/// folder coexist — the `is_seeded` column distinguishes them and
/// the UNIQUE index includes it. User-created tags that share a
/// name with a seeded tag soft-collide: the existing tag stays
/// (its bucket may be NULL, which is fine — the seeded tag with
/// the canonical bucket lands under a different ID).
public func seedWorkspaceTaxonomyIfNeeded(
    userId: String,
    database: QuickInkDatabase = .shared
) async throws {
    let dbQueue = database.dbQueue
    let now = IsoClock.nowIso()

    try await dbQueue.write { db in
        // ─── Folders ──────────────────────────────────────────
        for (index, seed) in workspaceFolderSeeds.enumerated() {
            do {
                try db.execute(sql: """
                    INSERT INTO folders (
                        id, user_id, name, color, position,
                        is_default, is_shared, type, tier, is_seeded,
                        created_at, updated_at, dirty
                    ) VALUES (?, ?, ?, ?, ?, 0, 0, ?, ?, 1, ?, ?, 1)
                    """, arguments: [
                        seed.id, userId, seed.name, seed.color, index,
                        seed.type, seed.tier, now, now,
                    ])
            } catch let error as DatabaseError where error.resultCode == .SQLITE_CONSTRAINT {
                // Already seeded (or hit the (user_id, name,
                // is_seeded) UNIQUE on a previous run that wrote the
                // same row). Idempotent — leave the row as-is.
                continue
            }
        }

        // ─── Tags ─────────────────────────────────────────────
        for (index, seed) in workspaceTagSeeds.enumerated() {
            do {
                try db.execute(sql: """
                    INSERT INTO tags (
                        id, user_id, name, position, color,
                        bucket, is_seeded,
                        created_at, updated_at, dirty
                    ) VALUES (?, ?, ?, ?, NULL, ?, 1, ?, ?, 1)
                    """, arguments: [
                        seed.id, userId, seed.name, index,
                        seed.bucket, now, now,
                    ])
            } catch let error as DatabaseError where error.resultCode == .SQLITE_CONSTRAINT {
                // User already has a non-seeded tag with this name
                // (e.g. `today`). Skip — leave their tag intact;
                // the spec's vocabulary will land under a different
                // ID if/when we ever need to resolve the collision.
                continue
            }
        }
    }
}
