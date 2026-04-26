/*
 * ContactDirectoryRepository.swift
 *
 * GRDB-backed aggregator for all app contacts. Combines the
 * `notepad_entries.contacts` and `pages.contacts` JSON columns into
 * a deduplicated, signature-indexed `[DirectoryContact]` stream.
 */

import Foundation
import GRDB

public final class ContactDirectoryRepository: @unchecked Sendable {

    private let dbQueue: DatabaseQueue

    public init(database: ReleafDatabase = .shared) {
        self.dbQueue = database.dbQueue
    }

    /// Stream of aggregated app contacts for a user. Re-emits on
    /// any write to either `notepad_entries` or `pages`.
    public func observeAll(userId: String) -> AsyncThrowingStream<[DirectoryContact], Error> {
        let observation = ValueObservation.tracking { db -> [DirectoryContact] in
            let notepad = try NotepadEntry
                .filter(sql: "user_id = ? AND deleted_at IS NULL", arguments: [userId])
                .fetchAll(db)
            let pages = try PageEntity
                .filter(sql: "deleted_at IS NULL")
                .fetchAll(db)
            return Self.aggregate(notepad: notepad, pages: pages)
        }
        return bridge(observation.values(in: dbQueue))
    }

    // MARK: - Aggregation

    private static func aggregate(
        notepad: [NotepadEntry],
        pages: [PageEntity]
    ) -> [DirectoryContact] {
        var bucket: [String: DirectoryBuilder] = [:]

        for entry in notepad {
            let contacts = entry.contacts.parseContacts()
            let updated = parseISO(entry.updatedAt)
            for c in contacts {
                record(contact: c, updated: updated, bucket: &bucket)
            }
        }
        for page in pages {
            let contacts = page.contacts.parseContacts()
            let updated = parseISO(page.updatedAt)
            for c in contacts {
                record(contact: c, updated: updated, bucket: &bucket)
            }
        }

        return bucket.values
            .map { $0.build() }
            .sorted { lhs, rhs in
                // Alphabetical by name; blank/"Unnamed" rows last.
                if lhs.name.isEmpty != rhs.name.isEmpty { return !lhs.name.isEmpty }
                return lhs.name.localizedCaseInsensitiveCompare(rhs.name) == .orderedAscending
            }
    }

    private static func record(
        contact c: NotepadContact,
        updated: Date?,
        bucket: inout [String: DirectoryBuilder]
    ) {
        let phone = c.phone?.trimmingCharacters(in: .whitespacesAndNewlines).nonEmpty
        let email = c.email?.trimmingCharacters(in: .whitespacesAndNewlines).nonEmpty
        let org   = c.organization?.trimmingCharacters(in: .whitespacesAndNewlines).nonEmpty
        let signature = identitySignature(
            name: c.name,
            phone: phone,
            email: email
        )
        if var builder = bucket[signature] {
            builder.occurrences += 1
            if let phone { builder.addPhone(phone) }
            if builder.email == nil, let email { builder.email = email }
            if builder.organization == nil, let org { builder.organization = org }
            if let updated, builder.updatedAt == nil || updated > builder.updatedAt! {
                builder.updatedAt = updated
            }
            bucket[signature] = builder
        } else {
            var builder = DirectoryBuilder(
                signature:    signature,
                name:         c.name.trimmingCharacters(in: .whitespacesAndNewlines),
                email:        email,
                organization: org,
                notes:        c.role?.trimmingCharacters(in: .whitespacesAndNewlines).nonEmpty
            )
            if let phone { builder.addPhone(phone) }
            builder.occurrences = 1
            builder.updatedAt = updated
            bucket[signature] = builder
        }
    }

    // `fileprivate` so the implicit memberwise init is callable from
    // the enclosing static `record(...)` method below. With `private`,
    // the init is locked to the struct body itself and the call site
    // gets "initializer is inaccessible due to 'private'".
    //
    // The `phones` storage is also `fileprivate` (rather than the
    // tighter `private`) — a `private` property would drag the synth
    // memberwise init back down to `private` regardless of the struct
    // declaration's level.
    fileprivate struct DirectoryBuilder {
        let signature: String
        let name: String
        var email: String?
        var organization: String?
        let notes: String?
        var occurrences: Int = 0
        var updatedAt: Date? = nil

        fileprivate var phones: [String] = []

        mutating func addPhone(_ raw: String) {
            let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmed.isEmpty else { return }
            phones.append(trimmed)
        }

        func build() -> DirectoryContact {
            DirectoryContact(
                id:             signature,
                name:           name.isEmpty ? "Unnamed" : name,
                // Collapse entries that differ only by a country-code
                // prefix (e.g. "+91 …" vs "…").
                phones:         dedupePhones(phones),
                email:          email,
                organization:   organization,
                notes:          notes,
                source:         .app,
                appOccurrences: occurrences,
                updatedAt:      updatedAt
            )
        }
    }

    private static func parseISO(_ s: String) -> Date? {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let d = f.date(from: s) { return d }
        f.formatOptions = [.withInternetDateTime]
        return f.date(from: s)
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

private extension String {
    var nonEmpty: String? {
        trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : self
    }
}
