/*
 * TaskEntity.swift
 *
 * GRDB record for the `tasks` table. Mirror of Android's
 * `TaskEntity.kt` — snake_case column names match the shared SQL
 * schema (design-system/migrations/v1_initial.sql §5) so Drive sync
 * payloads round-trip byte-for-byte between platforms.
 *
 * Named `TaskRecord` rather than `Task` to avoid colliding with the
 * Swift concurrency `_Concurrency.Task` type. Every feature-layer
 * reference should say `TaskRecord`.
 */

import Foundation
import GRDB

public struct TaskRecord: Codable, FetchableRecord, PersistableRecord,
                          Identifiable, Equatable, Sendable {

    public static let databaseTableName = "tasks"

    // MARK: - Columns

    /// UUIDv7, sorted chronologically by construction.
    public var id: String

    /// Scoping column; all queries filter by the signed-in user.
    public var userId: String

    public var title: String

    /// Optional longer notes body.
    public var description: String?

    /// Local `YYYY-MM-DD` date the task is due. The SQL CHECK
    /// constraint gates the GLOB shape — keep that in mind when
    /// synthesising values outside of DatePicker.
    public var dueDate: String?

    /// GRDB stores Bool as INTEGER 0/1, matching the SQL shape.
    public var completed: Bool

    /// ISO-8601 UTC with ms; set at the moment the user ticks the box.
    public var completedAt: String?

    /// 0 = none, 1 = low, 2 = medium, 3 = high. Mirrors
    /// `NotepadTodo.priority` so a promoted todo keeps its level.
    public var priority: Int

    /// ISO-8601 UTC with ms. See IsoClock.
    public var createdAt: String

    public var updatedAt: String

    /// `true` = needs upload to Drive; cleared by the sync worker.
    public var dirty: Bool

    /// ISO-8601 UTC when soft-deleted; nil = active.
    public var deletedAt: String?

    // MARK: - Column mapping

    public enum CodingKeys: String, CodingKey {
        case id
        case userId       = "user_id"
        case title
        case description
        case dueDate      = "due_date"
        case completed
        case completedAt  = "completed_at"
        case priority
        case createdAt    = "created_at"
        case updatedAt    = "updated_at"
        case dirty
        case deletedAt    = "deleted_at"
    }

    // MARK: - Init

    public init(
        id: String,
        userId: String,
        title: String,
        description: String? = nil,
        dueDate: String? = nil,
        completed: Bool = false,
        completedAt: String? = nil,
        priority: Int = 0,
        createdAt: String,
        updatedAt: String,
        dirty: Bool = true,
        deletedAt: String? = nil
    ) {
        self.id = id
        self.userId = userId
        self.title = title
        self.description = description
        self.dueDate = dueDate
        self.completed = completed
        self.completedAt = completedAt
        self.priority = priority
        self.createdAt = createdAt
        self.updatedAt = updatedAt
        self.dirty = dirty
        self.deletedAt = deletedAt
    }
}
