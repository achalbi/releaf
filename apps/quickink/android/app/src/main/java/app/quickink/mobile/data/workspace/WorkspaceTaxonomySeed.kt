/*
 * WorkspaceTaxonomySeed.kt
 *
 * Phase 2 of the Workspace tab refresh
 * (`design/WORKSPACE_TAB_HANDOFF.md`):
 *
 *   - [WorkspaceFolderSeed] / [workspaceFolderSeeds] — the 12 spec'd
 *     folders with stable IDs (`inbox`, `archive`, `finance`, …),
 *     their tier (1 Workflow / 2 Life domains / 3 Creative & output)
 *     and type (Inbox / Archive / Project / Reference).
 *   - [WorkspaceTagSeed] / [workspaceTagSeeds] — the 32 spec'd tags
 *     with stable IDs and bucket assignment.
 *   - [seedWorkspaceTaxonomyIfNeeded] — idempotent first-launch
 *     seeder. Stable IDs + the (user_id, name, is_seeded) UNIQUE on
 *     folders / the (user_id, name) UNIQUE on tags keep re-runs
 *     safe.
 *
 * Both platforms mirror the same set; the canonical source is
 * `shared/design-system/seeds/workspace_seed.json`. Keep this file
 * in sync with the JSON on every edit — there's no runtime parse
 * (avoids a launch-time asset hit) so the diff happens at code
 * review.
 *
 * Mirror of `WorkspaceTaxonomySeed.swift` (iOS).
 */

package app.quickink.mobile.data.workspace

import android.database.sqlite.SQLiteConstraintException
import app.quickink.mobile.data.folder.FolderDao
import app.quickink.mobile.data.folder.FolderEntity
import app.quickink.mobile.data.tag.TagDao
import app.quickink.mobile.data.tag.TagEntity
import app.releaf.mobile.data.common.IsoClock

// ─── Folder seed ──────────────────────────────────────────────────

data class WorkspaceFolderSeed(
    val id: String,
    val name: String,
    val tier: Int,
    val type: String,
    val color: String,
    val desc: String,
    val isSystemManaged: Boolean = false,
)

/** 12 folders, three tiers — mirror of `workspace_seed.json` §folders. */
val workspaceFolderSeeds: List<WorkspaceFolderSeed> = listOf(
    WorkspaceFolderSeed("inbox",     "Inbox",     1, "Inbox",     "#6366F1", "Capture zone — everything lands here first", isSystemManaged = true),
    WorkspaceFolderSeed("archive",   "Archive",   1, "Archive",   "#6366F1", "Completed or dormant, kept for reference"),

    WorkspaceFolderSeed("finance",   "Finance",   2, "Reference", "#2C2826", "bills, taxes, subscriptions"),
    WorkspaceFolderSeed("medical",   "Medical",   2, "Reference", "#2C2826", "appointments, scripts, results"),
    WorkspaceFolderSeed("family",    "Family",    2, "Reference", "#2C2826", "people, relationships, home"),
    WorkspaceFolderSeed("travel",    "Travel",    2, "Project",   "#2C2826", "trips, bookings, places"),
    WorkspaceFolderSeed("events",    "Events",    2, "Project",   "#2C2826", "weddings, parties, conferences"),
    WorkspaceFolderSeed("legal",     "Legal",     2, "Reference", "#2C2826", "contracts, IDs, records"),
    WorkspaceFolderSeed("lifestyle", "Lifestyle", 2, "Reference", "#2C2826", "fitness, food, hobbies, style"),

    WorkspaceFolderSeed("learning",  "Learning",  3, "Reference", "#10B981", "notes from books, podcasts"),
    WorkspaceFolderSeed("projects",  "Projects",  3, "Project",   "#10B981", "committed efforts w/ an end"),
    WorkspaceFolderSeed("ideas",     "Ideas",     3, "Reference", "#10B981", "sparks, someday/maybe"),
)

// ─── Tag seed ─────────────────────────────────────────────────────

data class WorkspaceTagSeed(
    val id: String,
    val name: String,
    val bucket: String,
)

/** 32 tags, 7 buckets — mirror of `workspace_seed.json` §buckets. */
val workspaceTagSeeds: List<WorkspaceTagSeed> = listOf(
    // Status (controlled)
    WorkspaceTagSeed("tag-status-active", "active", "status"),
    WorkspaceTagSeed("tag-status-todo",   "todo",   "status"),
    WorkspaceTagSeed("tag-status-later",  "later",  "status"),
    WorkspaceTagSeed("tag-status-done",   "done",   "status"),

    // People (prefixed p/)
    WorkspaceTagSeed("tag-people-mom",     "p/mom",     "people"),
    WorkspaceTagSeed("tag-people-manager", "p/manager", "people"),
    WorkspaceTagSeed("tag-people-sarah",   "p/sarah",   "people"),

    // Org & Place (prefixed org/, place/)
    WorkspaceTagSeed("tag-orgplace-aws",    "org/aws",      "orgplace"),
    WorkspaceTagSeed("tag-orgplace-clinic", "org/clinic",   "orgplace"),
    WorkspaceTagSeed("tag-orgplace-lisbon", "place/lisbon", "orgplace"),
    WorkspaceTagSeed("tag-orgplace-home",   "place/home",   "orgplace"),

    // Energy (controlled)
    WorkspaceTagSeed("tag-energy-focus",   "focus",   "energy"),
    WorkspaceTagSeed("tag-energy-shallow", "shallow", "energy"),
    WorkspaceTagSeed("tag-energy-errand",  "errand",  "energy"),
    WorkspaceTagSeed("tag-energy-call",    "call",    "energy"),

    // Time-sensitivity (controlled + exclusive)
    WorkspaceTagSeed("tag-time-today",     "today",     "time"),
    WorkspaceTagSeed("tag-time-thisweek",  "thisweek",  "time"),
    WorkspaceTagSeed("tag-time-thismonth", "thismonth", "time"),

    // Kind
    WorkspaceTagSeed("tag-kind-idea",      "idea",      "kind"),
    WorkspaceTagSeed("tag-kind-quote",     "quote",     "kind"),
    WorkspaceTagSeed("tag-kind-recipe",    "recipe",    "kind"),
    WorkspaceTagSeed("tag-kind-checklist", "checklist", "kind"),
    WorkspaceTagSeed("tag-kind-template",  "template",  "kind"),

    // Source (controlled + auto-applied)
    WorkspaceTagSeed("tag-source-scan",         "scan",         "source"),
    WorkspaceTagSeed("tag-source-voice",        "voice",        "source"),
    WorkspaceTagSeed("tag-source-handwritten",  "handwritten",  "source"),
    WorkspaceTagSeed("tag-source-web",          "web",          "source"),
    WorkspaceTagSeed("tag-source-email",        "email",        "source"),
    WorkspaceTagSeed("tag-source-book",         "book",         "source"),
    WorkspaceTagSeed("tag-source-podcast",      "podcast",      "source"),
    WorkspaceTagSeed("tag-source-article",      "article",      "source"),
    WorkspaceTagSeed("tag-source-conversation", "conversation", "source"),
)

// ─── Seeder ───────────────────────────────────────────────────────

/**
 * Insert the 12 folders + 32 tags as seeded rows for [userId],
 * idempotently. Safe to call on every launch — stable IDs + the
 * folders (user_id, name, is_seeded) UNIQUE and tags (user_id, name)
 * partial UNIQUE short-circuit subsequent runs.
 *
 * User-created folders that happen to share a name with a seeded
 * folder coexist — the `is_seeded` column distinguishes them and
 * the UNIQUE index includes it. User-created tags that share a name
 * with a seeded tag soft-collide: the existing tag stays (its
 * bucket may be NULL, which is fine — the seeded tag with the
 * canonical bucket lands under a different ID).
 */
suspend fun seedWorkspaceTaxonomyIfNeeded(
    userId: String,
    folderDao: FolderDao,
    tagDao: TagDao,
) {
    val now = IsoClock.nowIso()

    // ─── Folders ──────────────────────────────────────────────
    workspaceFolderSeeds.forEachIndexed { index, seed ->
        try {
            folderDao.insert(
                FolderEntity(
                    id          = seed.id,
                    userId      = userId,
                    name        = seed.name,
                    color       = seed.color,
                    position    = index,
                    isDefault   = false,
                    isShared    = false,
                    type        = seed.type,
                    tier        = seed.tier,
                    isSeeded    = true,
                    createdAt   = now,
                    updatedAt   = now,
                    dirty       = true,
                    deletedAt   = null,
                ),
            )
        } catch (_: SQLiteConstraintException) {
            // Already seeded (or hit the (user_id, name, is_seeded)
            // UNIQUE on a previous run). Idempotent — leave the
            // existing row as-is.
        }
    }

    // ─── Tags ─────────────────────────────────────────────────
    workspaceTagSeeds.forEachIndexed { index, seed ->
        try {
            tagDao.insert(
                TagEntity(
                    id           = seed.id,
                    userId       = userId,
                    name         = seed.name,
                    position     = index,
                    color        = null,
                    bucket       = seed.bucket,
                    isSeeded     = true,
                    driveFileId  = null,
                    createdAt    = now,
                    updatedAt    = now,
                    dirty        = true,
                    deletedAt    = null,
                ),
            )
        } catch (_: SQLiteConstraintException) {
            // User already has a non-seeded tag with this name.
            // Skip — leave their tag intact.
        }
    }
}
