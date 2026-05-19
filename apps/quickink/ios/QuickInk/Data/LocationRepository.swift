/*
 * LocationRepository.swift
 *
 * GRDB-backed repository for the `locations` table and the
 * `capture_locations` join. Powers the Home-screen Places section,
 * the location picker on scan detail, and the Drive-sync surface
 * for both tables.
 *
 * Mirror of Android's `LocationRepository.kt` + `CaptureLocationDao.kt`.
 * The Swift target collapses Room's DAO + repository split into one
 * type since GRDB doesn't have Room's annotation-driven DAOs.
 *
 * Sync invariants:
 *   - Every write sets `dirty = 1` and bumps `updated_at`.
 *   - `attachLocation` + `detachLocation` are idempotent + revive
 *     tombstoned join rows so the partial UNIQUE behavior matches
 *     Android byte-for-byte.
 *   - `markSynced` clears the dirty flag only if `updated_at`
 *     hasn't advanced since the upload started (CAS pattern).
 */

import Foundation
import Combine
import GRDB
import ReleafCoreData

public struct LocationCount: Equatable, Sendable {
    public let locationId: String
    public let docCount: Int
}

public final class LocationRepository: @unchecked Sendable {

    /// Default seed locations for `seedDefaultsIfEmpty`.
    public static let defaultSeed: [String] = ["Home", "Work"]

    public static func isPredefined(_ name: String) -> Bool {
        defaultSeed.contains(name)
    }

    private let dbQueue: DatabaseQueue

    public init(database: QuickInkDatabase = .shared) {
        self.dbQueue = database.dbQueue
    }

    // MARK: - Reads (locations)

    public func observe(userId: String) -> AnyPublisher<[LocationEntity], Error> {
        ValueObservation.tracking { db in
            try LocationEntity
                .filter(Column("user_id") == userId)
                .filter(Column("deleted_at") == nil)
                .order(Column("position").asc, Column("name").asc)
                .fetchAll(db)
        }
        .publisher(in: dbQueue)
        .eraseToAnyPublisher()
    }

    public func listActive(userId: String) async throws -> [LocationEntity] {
        try await dbQueue.read { db in
            try LocationEntity
                .filter(Column("user_id") == userId)
                .filter(Column("deleted_at") == nil)
                .order(Column("position").asc, Column("name").asc)
                .fetchAll(db)
        }
    }

    public func findById(_ id: String) async throws -> LocationEntity? {
        try await dbQueue.read { db in
            try LocationEntity.fetchOne(db, key: id)
        }
    }

    public func findByName(userId: String, name: String) async throws -> LocationEntity? {
        try await dbQueue.read { db in
            try LocationEntity
                .filter(Column("user_id") == userId)
                .filter(Column("name") == name)
                .filter(Column("deleted_at") == nil)
                .fetchOne(db)
        }
    }

    // MARK: - Writes (locations)

    /// Insert a single location. Returns `false` if the (user_id, name)
    /// UNIQUE constraint already has this name — the existing row is
    /// left untouched.
    @discardableResult
    public func insert(
        userId: String,
        name: String,
        position: Int,
        id: String = Uuidv7.generate(),
        color: String? = nil,
        latitude: Double? = nil,
        longitude: Double? = nil,
        address: String? = nil
    ) async throws -> Bool {
        let now = IsoClock.nowIso()
        let entity = LocationEntity(
            id:          id,
            userId:      userId,
            name:        name,
            position:    position,
            color:       color,
            latitude:    latitude,
            longitude:   longitude,
            address:     address,
            createdAt:   now,
            updatedAt:   now,
            dirty:       true
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

    /// Find-or-create by name within a user's namespace.
    public func findOrCreate(
        userId: String,
        name: String,
        latitude: Double? = nil,
        longitude: Double? = nil,
        address: String? = nil
    ) async throws -> LocationEntity {
        if let existing = try await findByName(userId: userId, name: name) {
            return existing
        }
        let now = IsoClock.nowIso()
        let candidate = LocationEntity(
            id:          Uuidv7.generate(),
            userId:      userId,
            name:        name,
            position:    Int.max,
            color:       nil,
            latitude:    latitude,
            longitude:   longitude,
            address:     address,
            createdAt:   now,
            updatedAt:   now,
            dirty:       true
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
            throw QuickInkRepositoryError.raceLost("LocationRepository.findOrCreate")
        }
        return raced
    }

    public func rename(id: String, newName: String) async throws {
        let now = IsoClock.nowIso()
        try await dbQueue.write { db in
            try db.execute(sql: """
                UPDATE locations
                SET name = ?, updated_at = ?, dirty = 1
                WHERE id = ?
                """, arguments: [newName, now, id])
        }
    }

    public func setCoordinates(
        id: String,
        latitude: Double?,
        longitude: Double?,
        address: String?
    ) async throws {
        let now = IsoClock.nowIso()
        try await dbQueue.write { db in
            try db.execute(sql: """
                UPDATE locations
                SET latitude = ?, longitude = ?, address = ?,
                    updated_at = ?, dirty = 1
                WHERE id = ?
                """, arguments: [latitude, longitude, address, now, id])
        }
    }

    public func setColor(id: String, color: String?) async throws {
        let now = IsoClock.nowIso()
        try await dbQueue.write { db in
            try db.execute(sql: """
                UPDATE locations
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
                    UPDATE locations
                    SET position = ?, updated_at = ?, dirty = 1
                    WHERE id = ?
                    """, arguments: [index, now, id])
            }
        }
    }

    /// Soft-delete a location. Cascades to its `capture_locations`
    /// rows so the join's tombstones travel via sync alongside the
    /// parent row.
    public func softDelete(id: String) async throws {
        let now = IsoClock.nowIso()
        try await dbQueue.write { db in
            try db.execute(sql: """
                UPDATE locations
                SET deleted_at = ?, updated_at = ?, dirty = 1
                WHERE id = ?
                """, arguments: [now, now, id])
            try db.execute(sql: """
                UPDATE capture_locations
                SET deleted_at = ?, updated_at = ?, dirty = 1
                WHERE location_id = ? AND deleted_at IS NULL
                """, arguments: [now, now, id])
        }
    }

    /// Seed the default places if the user has no active rows. Safe to
    /// call on every launch — the (user_id, name) UNIQUE constraint
    /// makes partial seed runs idempotent.
    public func seedDefaultsIfEmpty(userId: String) async throws {
        let existing = try await listActive(userId: userId)
        guard existing.isEmpty else { return }
        for (index, name) in Self.defaultSeed.enumerated() {
            _ = try await insert(userId: userId, name: name, position: index)
        }
    }

    // MARK: - Join (capture_locations)

    /// Live list of location ids attached to a capture.
    public func observeLocationIds(captureId: String) -> AnyPublisher<[String], Error> {
        ValueObservation.tracking { db in
            try String.fetchAll(db, sql: """
                SELECT location_id FROM capture_locations
                WHERE capture_id = ? AND deleted_at IS NULL
                ORDER BY created_at ASC
                """, arguments: [captureId])
        }
        .publisher(in: dbQueue)
        .eraseToAnyPublisher()
    }

    public func listLocationIds(captureId: String) async throws -> [String] {
        try await dbQueue.read { db in
            try String.fetchAll(db, sql: """
                SELECT location_id FROM capture_locations
                WHERE capture_id = ? AND deleted_at IS NULL
                ORDER BY created_at ASC
                """, arguments: [captureId])
        }
    }

    /// Live capture counts per location, scoped to a user.
    public func observeLocationCounts(userId: String) -> AnyPublisher<[LocationCount], Error> {
        ValueObservation.tracking { db in
            try Row.fetchAll(db, sql: """
                SELECT capture_locations.location_id AS location_id,
                       COUNT(*) AS doc_count
                FROM capture_locations
                JOIN captures ON captures.id = capture_locations.capture_id
                WHERE capture_locations.deleted_at IS NULL
                  AND captures.deleted_at IS NULL
                  AND captures.user_id = ?
                GROUP BY capture_locations.location_id
                """, arguments: [userId]).map { row in
                    LocationCount(
                        locationId: row["location_id"],
                        docCount:   row["doc_count"]
                    )
                }
        }
        .publisher(in: dbQueue)
        .eraseToAnyPublisher()
    }

    /// Idempotent attach. Pre-existing active pair → no-op;
    /// tombstoned pair → revive in place (preserves the join id so
    /// the Drive payload filename doesn't churn).
    public func attachLocation(
        captureId: String,
        locationId: String,
        source: String = "manual",
        joinId: String = Uuidv7.generate()
    ) async throws {
        let now = IsoClock.nowIso()
        try await dbQueue.write { db in
            let existing = try Row.fetchOne(db, sql: """
                SELECT id, deleted_at FROM capture_locations
                WHERE capture_id = ? AND location_id = ?
                ORDER BY (deleted_at IS NULL) DESC, updated_at DESC
                LIMIT 1
                """, arguments: [captureId, locationId])
            if existing == nil {
                let entity = CaptureLocationEntity(
                    id:         joinId,
                    captureId:  captureId,
                    locationId: locationId,
                    source:     source,
                    createdAt:  now,
                    updatedAt:  now,
                    dirty:      true
                )
                try entity.insert(db)
                return
            }
            let existingDeleted: String? = existing!["deleted_at"]
            guard existingDeleted != nil else { return }
            let existingId: String = existing!["id"]
            try db.execute(sql: """
                UPDATE capture_locations
                SET deleted_at = NULL, source = ?, updated_at = ?, dirty = 1
                WHERE id = ?
                """, arguments: [source, now, existingId])
        }
    }

    public func detachLocation(captureId: String, locationId: String) async throws {
        let now = IsoClock.nowIso()
        try await dbQueue.write { db in
            try db.execute(sql: """
                UPDATE capture_locations
                SET deleted_at = ?, updated_at = ?, dirty = 1
                WHERE capture_id = ? AND location_id = ? AND deleted_at IS NULL
                """, arguments: [now, now, captureId, locationId])
        }
    }
}
