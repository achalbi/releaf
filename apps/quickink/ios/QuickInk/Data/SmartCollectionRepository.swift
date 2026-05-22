/*
 * SmartCollectionRepository.swift
 *
 * iOS rule evaluator + seed mirror of Android
 * `SmartCollectionRepository.kt`. In-memory filter: corpus-size-
 * appropriate at v1, with a clear path to a typed raw-SQL builder
 * once the workspace grows.
 *
 * Seeded "Today" is date-only; "Needs review" depends on the
 * `#needs-review` tag landing via TagRepository.defaultSeed first.
 */

import Foundation
import Combine
import GRDB
import ReleafCoreData

public final class SmartCollectionRepository: @unchecked Sendable {

    public static let seedToday = "Today"
    public static let seedNeedsReview = "Needs review"
    private static let seedBlue = "#3A78AE"

    private let dbQueue: DatabaseQueue
    private let tagRepository: TagRepository

    public init(database: QuickInkDatabase = .shared) {
        self.dbQueue = database.dbQueue
        self.tagRepository = TagRepository(database: database)
    }

    // MARK: - Reads

    public func observeActive(userId: String) -> AnyPublisher<[SmartCollectionEntity], Error> {
        ValueObservation.tracking { [userId] db in
            try SmartCollectionEntity
                .filter(Column("user_id") == userId)
                .filter(Column("deleted_at") == nil)
                .order(Column("position").asc, Column("name").asc)
                .fetchAll(db)
        }
        .publisher(in: dbQueue)
        .eraseToAnyPublisher()
    }

    public func findById(_ id: String) async throws -> SmartCollectionEntity? {
        try await dbQueue.read { db in
            try SmartCollectionEntity.filter(Column("id") == id).fetchOne(db)
        }
    }

    public func observeMatchingCaptures(
        userId: String,
        collection: SmartCollectionEntity
    ) -> AnyPublisher<[CaptureSummary], Error> {
        let clauses = SmartCollectionRule.decode(collection.ruleJson)
        guard !clauses.isEmpty else {
            return Just([CaptureSummary]())
                .setFailureType(to: Error.self)
                .eraseToAnyPublisher()
        }
        return ValueObservation.tracking { [userId, clauses] db -> [CaptureSummary] in
            let captures = try CaptureSummary.fetchAll(db, sql: """
                SELECT id, title, preview_uri, pdf_uri, page_count, created_at, source,
                       latitude, longitude, locality, sub_locality, address,
                       folder_id, last_opened_at, last_opened_page, last_opened_device
                FROM captures
                WHERE user_id = ? AND deleted_at IS NULL
                ORDER BY created_at DESC
                """, arguments: [userId])
            return captures.filter { cap in
                clauses.allSatisfy { clause in
                    Self.matches(clause: clause, capture: cap, db: db)
                }
            }
        }
        .publisher(in: dbQueue)
        .eraseToAnyPublisher()
    }

    private static func matches(clause: RuleClause, capture: CaptureSummary, db: Database) -> Bool {
        switch clause {
        case .folderIs(let id):
            return capture.folderId == id
        case .tagIs(let tagId):
            let n = (try? Int.fetchOne(db, sql: """
                SELECT COUNT(*) FROM capture_tags
                WHERE capture_id = ? AND tag_id = ? AND deleted_at IS NULL
                """, arguments: [capture.id, tagId])) ?? 0
            return n > 0
        case .tagIsNot(let tagId):
            let n = (try? Int.fetchOne(db, sql: """
                SELECT COUNT(*) FROM capture_tags
                WHERE capture_id = ? AND tag_id = ? AND deleted_at IS NULL
                """, arguments: [capture.id, tagId])) ?? 0
            return n == 0
        case .dateRange(let field, let preset):
            let ts: String
            switch field {
            case "created_at":     ts = capture.createdAt
            case "last_opened_at": guard let t = capture.lastOpenedAt else { return false }; ts = t
            default: return false
            }
            return inDatePreset(timestamp: ts, preset: preset)
        case .sourceIs(let value):
            return capture.source == value
        case .hasHandwriting, .hasSignature, .hasOcrText:
            // OCR-derived placeholders; flagged on but column not
            // emitted yet — return false until Phase E.
            return false
        }
    }

    private static func inDatePreset(timestamp: String, preset: String) -> Bool {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        guard let date = formatter.date(from: timestamp) ??
                ISO8601DateFormatter().date(from: timestamp) else {
            return false
        }
        let cal = Calendar.current
        let now = Date()
        switch preset {
        case "today":
            return cal.isDateInToday(date)
        case "yesterday":
            return cal.isDateInYesterday(date)
        case "this_week":
            return cal.isDate(date, equalTo: now, toGranularity: .weekOfYear)
        case "this_month":
            return cal.isDate(date, equalTo: now, toGranularity: .month)
        case "last_30_days":
            guard let cutoff = cal.date(byAdding: .day, value: -30, to: now) else { return false }
            return date >= cutoff
        case "this_quarter":
            let q = (cal.component(.month, from: date) - 1) / 3
            let qNow = (cal.component(.month, from: now) - 1) / 3
            return q == qNow && cal.isDate(date, equalTo: now, toGranularity: .year)
        default:
            return false
        }
    }

    // MARK: - Seed

    public func seedDefaultsIfNeeded(userId: String) async throws {
        let existing = try await dbQueue.read { db in
            try SmartCollectionEntity
                .filter(Column("user_id") == userId)
                .filter(Column("is_seeded") == true)
                .filter(Column("deleted_at") == nil)
                .fetchAll(db)
        }
        let existingNames = Set(existing.map(\.name))
        let now = IsoClock.nowIso()

        if !existingNames.contains(Self.seedToday) {
            let row = SmartCollectionEntity(
                id:        Uuidv7.generate(),
                userId:    userId,
                name:      Self.seedToday,
                icon:      "star",
                color:     Self.seedBlue,
                ruleJson:  SmartCollectionRule.encode([.dateRange(field: "created_at", preset: "today")]),
                position:  0,
                isSeeded:  true,
                createdAt: now,
                updatedAt: now,
                dirty:     true,
            )
            try await dbQueue.write { db in
                do { try row.insert(db) }
                catch let e as DatabaseError where e.resultCode == .SQLITE_CONSTRAINT {
                    // Race lost — another seed pass won.
                }
            }
        }

        if var needsReview = existing.first(where: { $0.name == Self.seedNeedsReview }),
           needsReview.position != 1 {
            needsReview.position = 1
            needsReview.updatedAt = now
            needsReview.dirty = true
            try await dbQueue.write { db in
                try needsReview.update(db)
            }
        }

        if existingNames.contains(Self.seedNeedsReview) {
            return
        }
        guard let needsReviewTag = try await tagRepository.findByName(userId: userId, name: "needs-review") else {
            return
        }
        let row = SmartCollectionEntity(
            id:        Uuidv7.generate(),
            userId:    userId,
            name:      Self.seedNeedsReview,
            icon:      "eye",
            color:     "#E8AE17",
            ruleJson:  SmartCollectionRule.encode([.tagIs(tagId: needsReviewTag.id)]),
            position:  1,
            isSeeded:  true,
            createdAt: now,
            updatedAt: now,
            dirty:     true,
        )
        try await dbQueue.write { db in
            do { try row.insert(db) }
            catch let e as DatabaseError where e.resultCode == .SQLITE_CONSTRAINT {
                // Race lost — another seed pass won.
            }
        }
    }
}
