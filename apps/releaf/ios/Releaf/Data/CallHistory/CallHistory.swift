/*
 * CallHistory.swift
 *
 * Domain model + GRDB record for the local Call History log.
 * Rows are written when the user taps a phone number in Contacts,
 * and updated in place by `CallObserver` as CXCallObserver reports
 * the call connecting / ending.
 *
 * Local-only today — not sync'd to Drive.
 */

import Foundation
import GRDB

public enum CallHistorySource: String, Codable, Sendable {
    case app
    case device
}

public struct CallHistoryRecord: Identifiable, Equatable, Sendable {
    public let id: String
    public let userId: String
    public let contactName: String
    public let phoneNumber: String
    public let source: CallHistorySource
    public let startedAt: Date
    public let connectedAt: Date?
    public let endedAt: Date?
    /// Cached duration — `endedAt - connectedAt` in whole seconds.
    /// Nil when either endpoint was never observed.
    public let durationSeconds: Int64?

    /// True once CXCallObserver reported `hasEnded` after a prior
    /// `hasConnected`.
    public var isComplete: Bool { endedAt != nil && connectedAt != nil }

    /// True for dials that ended before ever connecting (the user
    /// cancelled, the call rang out, the remote side declined).
    public var wasMissedOrCancelled: Bool { endedAt != nil && connectedAt == nil }
}

// MARK: - GRDB row

struct CallHistoryRow: Codable, FetchableRecord, PersistableRecord, Equatable {
    static let databaseTableName = "call_history"

    var id: String
    var userId: String
    var contactName: String
    var phoneNumber: String
    var source: String
    var startedAt: String
    var connectedAt: String?
    var endedAt: String?
    var durationSeconds: Int64?

    enum CodingKeys: String, CodingKey {
        case id
        case userId          = "user_id"
        case contactName     = "contact_name"
        case phoneNumber     = "phone_number"
        case source
        case startedAt       = "started_at"
        case connectedAt     = "connected_at"
        case endedAt         = "ended_at"
        case durationSeconds = "duration_seconds"
    }

    func toRecord() -> CallHistoryRecord {
        CallHistoryRecord(
            id:              id,
            userId:          userId,
            contactName:     contactName,
            phoneNumber:     phoneNumber,
            source:          CallHistorySource(rawValue: source) ?? .app,
            startedAt:       parseISO(startedAt) ?? Date(),
            connectedAt:     connectedAt.flatMap(parseISO),
            endedAt:         endedAt.flatMap(parseISO),
            durationSeconds: durationSeconds
        )
    }
}

private func parseISO(_ string: String) -> Date? {
    let f = ISO8601DateFormatter()
    f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    if let d = f.date(from: string) { return d }
    f.formatOptions = [.withInternetDateTime]
    return f.date(from: string)
}

private func formatISO(_ date: Date) -> String {
    let f = ISO8601DateFormatter()
    f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    return f.string(from: date)
}

// MARK: - Repository

public final class CallHistoryRepository: @unchecked Sendable {

    private let dbQueue: DatabaseQueue

    public init(database: ReleafDatabase = .shared) {
        self.dbQueue = database.dbQueue
    }

    /// Observe call history for a user, newest first. Re-emits on
    /// every write to `call_history`.
    public func observeAll(userId: String) -> AsyncThrowingStream<[CallHistoryRecord], Error> {
        let observation = ValueObservation.tracking { db -> [CallHistoryRecord] in
            try CallHistoryRow
                .filter(sql: "user_id = ?", arguments: [userId])
                .order(sql: "started_at DESC")
                .fetchAll(db)
                .map { $0.toRecord() }
        }
        return bridge(observation.values(in: dbQueue))
    }

    /// Insert a fresh "started" row. Returns the new id so the
    /// caller can hand it to `CallObserver.attach`.
    @discardableResult
    public func recordStarted(
        userId: String,
        contactName: String,
        phoneNumber: String,
        source: CallHistorySource
    ) async throws -> String {
        let id = UUID().uuidString
        let row = CallHistoryRow(
            id:              id,
            userId:          userId,
            contactName:     contactName.isEmpty ? phoneNumber : contactName,
            phoneNumber:     phoneNumber,
            source:          source.rawValue,
            startedAt:       formatISO(Date()),
            connectedAt:     nil,
            endedAt:         nil,
            durationSeconds: nil
        )
        try await dbQueue.write { db in
            try row.insert(db)
        }
        return id
    }

    /// Back-fill `connected_at` once CXCallObserver reports
    /// `hasConnected`.
    public func recordConnected(id: String) async throws {
        try await dbQueue.write { db in
            guard var row = try CallHistoryRow.fetchOne(db, key: id) else { return }
            if row.connectedAt != nil { return }
            row.connectedAt = formatISO(Date())
            try row.update(db)
        }
    }

    /// Back-fill `ended_at` + compute the cached duration. If we
    /// never saw a connect, we still record the ended timestamp
    /// so the row stops being "in progress" — duration stays nil
    /// so the UI can surface "missed / cancelled".
    public func recordEnded(id: String) async throws {
        try await dbQueue.write { db in
            guard var row = try CallHistoryRow.fetchOne(db, key: id) else { return }
            if row.endedAt != nil { return }
            let endedAt = Date()
            let connectedAt = row.connectedAt.flatMap(parseISO)
            row.endedAt = formatISO(endedAt)
            if let connectedAt {
                row.durationSeconds = max(0, Int64(endedAt.timeIntervalSince(connectedAt)))
            }
            try row.update(db)
        }
    }

    public func delete(id: String) async throws {
        _ = try await dbQueue.write { db in
            try CallHistoryRow.deleteOne(db, key: id)
        }
    }

    public func deleteAll(userId: String) async throws {
        _ = try await dbQueue.write { db in
            try CallHistoryRow
                .filter(sql: "user_id = ?", arguments: [userId])
                .deleteAll(db)
        }
    }
}

// MARK: - AsyncSequence bridge

private func bridge<S: AsyncSequence & Sendable>(_ sequence: S) -> AsyncThrowingStream<S.Element, Error>
where S.Element: Sendable {
    AsyncThrowingStream { continuation in
        let task = Task {
            do {
                for try await value in sequence {
                    continuation.yield(value)
                }
                continuation.finish()
            } catch {
                continuation.finish(throwing: error)
            }
        }
        continuation.onTermination = { _ in task.cancel() }
    }
}
