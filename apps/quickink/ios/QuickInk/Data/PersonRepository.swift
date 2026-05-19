/*
 * PersonRepository.swift
 *
 * GRDB-backed repository for the `people` table and the
 * `capture_people` join. Powers the Home-screen People section, the
 * person picker on scan detail, and the Drive-sync surface for both
 * tables.
 *
 * Mirror of Android's `PersonRepository.kt` + `CapturePersonDao.kt`.
 * Sync invariants mirror `LocationRepository` — see its header for
 * the details.
 */

import Foundation
import Combine
import GRDB
import ReleafCoreData

public struct PersonCount: Equatable, Sendable {
    public let personId: String
    public let docCount: Int
}

public final class PersonRepository: @unchecked Sendable {

    public static let defaultSeed: [String] = ["Me"]

    public static func isPredefined(_ name: String) -> Bool {
        defaultSeed.contains(name)
    }

    private let dbQueue: DatabaseQueue

    public init(database: QuickInkDatabase = .shared) {
        self.dbQueue = database.dbQueue
    }

    // MARK: - Reads (people)

    public func observe(userId: String) -> AnyPublisher<[PersonEntity], Error> {
        ValueObservation.tracking { db in
            try PersonEntity
                .filter(Column("user_id") == userId)
                .filter(Column("deleted_at") == nil)
                .order(Column("position").asc, Column("name").asc)
                .fetchAll(db)
        }
        .publisher(in: dbQueue)
        .eraseToAnyPublisher()
    }

    public func listActive(userId: String) async throws -> [PersonEntity] {
        try await dbQueue.read { db in
            try PersonEntity
                .filter(Column("user_id") == userId)
                .filter(Column("deleted_at") == nil)
                .order(Column("position").asc, Column("name").asc)
                .fetchAll(db)
        }
    }

    public func findById(_ id: String) async throws -> PersonEntity? {
        try await dbQueue.read { db in
            try PersonEntity.fetchOne(db, key: id)
        }
    }

    public func findByName(userId: String, name: String) async throws -> PersonEntity? {
        try await dbQueue.read { db in
            try PersonEntity
                .filter(Column("user_id") == userId)
                .filter(Column("name") == name)
                .filter(Column("deleted_at") == nil)
                .fetchOne(db)
        }
    }

    // MARK: - Writes (people)

    @discardableResult
    public func insert(
        userId: String,
        name: String,
        position: Int,
        id: String = Uuidv7.generate(),
        color: String? = nil,
        contactLookupKey: String? = nil,
        contactPhone: String? = nil,
        contactEmail: String? = nil,
        contactPhotoUri: String? = nil
    ) async throws -> Bool {
        let now = IsoClock.nowIso()
        let entity = PersonEntity(
            id:               id,
            userId:           userId,
            name:             name,
            position:         position,
            color:            color,
            contactLookupKey: contactLookupKey,
            contactPhone:     contactPhone,
            contactEmail:     contactEmail,
            contactPhotoUri:  contactPhotoUri,
            createdAt:        now,
            updatedAt:        now,
            dirty:            true
        )
        return try await dbQueue.write { db in
            do {
                try entity.insert(db)
                return true
            } catch let error as DatabaseError where error.resultCode == .SQLITE_CONSTRAINT {
                return false
            }
        }
    }

    public func findOrCreate(
        userId: String,
        name: String,
        contactLookupKey: String? = nil,
        contactPhone: String? = nil,
        contactEmail: String? = nil,
        contactPhotoUri: String? = nil
    ) async throws -> PersonEntity {
        if let existing = try await findByName(userId: userId, name: name) {
            return existing
        }
        let now = IsoClock.nowIso()
        let candidate = PersonEntity(
            id:               Uuidv7.generate(),
            userId:           userId,
            name:             name,
            position:         Int.max,
            color:            nil,
            contactLookupKey: contactLookupKey,
            contactPhone:     contactPhone,
            contactEmail:     contactEmail,
            contactPhotoUri:  contactPhotoUri,
            createdAt:        now,
            updatedAt:        now,
            dirty:            true
        )
        let inserted: Bool = try await dbQueue.write { db in
            do {
                try candidate.insert(db)
                return true
            } catch let error as DatabaseError where error.resultCode == .SQLITE_CONSTRAINT {
                return false
            }
        }
        if inserted { return candidate }
        guard let raced = try await findByName(userId: userId, name: name) else {
            throw QuickInkRepositoryError.raceLost("PersonRepository.findOrCreate")
        }
        return raced
    }

    public func rename(id: String, newName: String) async throws {
        let now = IsoClock.nowIso()
        try await dbQueue.write { db in
            try db.execute(sql: """
                UPDATE people
                SET name = ?, updated_at = ?, dirty = 1
                WHERE id = ?
                """, arguments: [newName, now, id])
        }
    }

    public func setContactLink(
        id: String,
        lookupKey: String?,
        phone: String?,
        email: String?,
        photoUri: String?
    ) async throws {
        let now = IsoClock.nowIso()
        try await dbQueue.write { db in
            try db.execute(sql: """
                UPDATE people
                SET contact_lookup_key = ?,
                    contact_phone      = ?,
                    contact_email      = ?,
                    contact_photo_uri  = ?,
                    updated_at         = ?,
                    dirty              = 1
                WHERE id = ?
                """, arguments: [lookupKey, phone, email, photoUri, now, id])
        }
    }

    public func setColor(id: String, color: String?) async throws {
        let now = IsoClock.nowIso()
        try await dbQueue.write { db in
            try db.execute(sql: """
                UPDATE people
                SET color = ?, updated_at = ?, dirty = 1
                WHERE id = ?
                """, arguments: [color, now, id])
        }
    }

    public func reorder(ids: [String]) async throws {
        let now = IsoClock.nowIso()
        try await dbQueue.write { db in
            for (index, id) in ids.enumerated() {
                try db.execute(sql: """
                    UPDATE people
                    SET position = ?, updated_at = ?, dirty = 1
                    WHERE id = ?
                    """, arguments: [index, now, id])
            }
        }
    }

    public func softDelete(id: String) async throws {
        let now = IsoClock.nowIso()
        try await dbQueue.write { db in
            try db.execute(sql: """
                UPDATE people
                SET deleted_at = ?, updated_at = ?, dirty = 1
                WHERE id = ?
                """, arguments: [now, now, id])
            try db.execute(sql: """
                UPDATE capture_people
                SET deleted_at = ?, updated_at = ?, dirty = 1
                WHERE person_id = ? AND deleted_at IS NULL
                """, arguments: [now, now, id])
        }
    }

    public func seedDefaultsIfEmpty(userId: String) async throws {
        let existing = try await listActive(userId: userId)
        guard existing.isEmpty else { return }
        for (index, name) in Self.defaultSeed.enumerated() {
            _ = try await insert(userId: userId, name: name, position: index)
        }
    }

    // MARK: - Join (capture_people)

    public func observePersonIds(captureId: String) -> AnyPublisher<[String], Error> {
        ValueObservation.tracking { db in
            try String.fetchAll(db, sql: """
                SELECT person_id FROM capture_people
                WHERE capture_id = ? AND deleted_at IS NULL
                ORDER BY created_at ASC
                """, arguments: [captureId])
        }
        .publisher(in: dbQueue)
        .eraseToAnyPublisher()
    }

    public func listPersonIds(captureId: String) async throws -> [String] {
        try await dbQueue.read { db in
            try String.fetchAll(db, sql: """
                SELECT person_id FROM capture_people
                WHERE capture_id = ? AND deleted_at IS NULL
                ORDER BY created_at ASC
                """, arguments: [captureId])
        }
    }

    public func observePersonCounts(userId: String) -> AnyPublisher<[PersonCount], Error> {
        ValueObservation.tracking { db in
            try Row.fetchAll(db, sql: """
                SELECT capture_people.person_id AS person_id,
                       COUNT(*) AS doc_count
                FROM capture_people
                JOIN captures ON captures.id = capture_people.capture_id
                WHERE capture_people.deleted_at IS NULL
                  AND captures.deleted_at IS NULL
                  AND captures.user_id = ?
                GROUP BY capture_people.person_id
                """, arguments: [userId]).map { row in
                    PersonCount(
                        personId: row["person_id"],
                        docCount: row["doc_count"]
                    )
                }
        }
        .publisher(in: dbQueue)
        .eraseToAnyPublisher()
    }

    public func attachPerson(
        captureId: String,
        personId: String,
        source: String = "manual",
        joinId: String = Uuidv7.generate()
    ) async throws {
        let now = IsoClock.nowIso()
        try await dbQueue.write { db in
            let existing = try Row.fetchOne(db, sql: """
                SELECT id, deleted_at FROM capture_people
                WHERE capture_id = ? AND person_id = ?
                ORDER BY (deleted_at IS NULL) DESC, updated_at DESC
                LIMIT 1
                """, arguments: [captureId, personId])
            if existing == nil {
                let entity = CapturePersonEntity(
                    id:        joinId,
                    captureId: captureId,
                    personId:  personId,
                    source:    source,
                    createdAt: now,
                    updatedAt: now,
                    dirty:     true
                )
                try entity.insert(db)
                return
            }
            let existingDeleted: String? = existing!["deleted_at"]
            guard existingDeleted != nil else { return }
            let existingId: String = existing!["id"]
            try db.execute(sql: """
                UPDATE capture_people
                SET deleted_at = NULL, source = ?, updated_at = ?, dirty = 1
                WHERE id = ?
                """, arguments: [source, now, existingId])
        }
    }

    public func detachPerson(captureId: String, personId: String) async throws {
        let now = IsoClock.nowIso()
        try await dbQueue.write { db in
            try db.execute(sql: """
                UPDATE capture_people
                SET deleted_at = ?, updated_at = ?, dirty = 1
                WHERE capture_id = ? AND person_id = ? AND deleted_at IS NULL
                """, arguments: [now, now, captureId, personId])
        }
    }
}
