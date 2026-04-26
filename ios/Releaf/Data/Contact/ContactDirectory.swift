/*
 * ContactDirectory.swift
 *
 * Domain types for the Contacts screen. A `DirectoryContact` is a
 * single entry in the unified address book — it may originate from
 * the app (a `NotepadContact` on a notepad entry or page) or from
 * the device address book via `DeviceContactsProvider`.
 */

import Foundation

public enum DirectoryContactSource: Equatable, Sendable {
    case app
    case device
}

public struct DirectoryContact: Identifiable, Equatable, Sendable {
    public let id: String
    public let name: String
    /// All phone numbers stored for this contact, de-duplicated and
    /// in capture order. Empty when there's no phone at all. Single-
    /// phone displays use the `phone` computed accessor below.
    public let phones: [String]
    public let email: String?
    public let organization: String?
    public let notes: String?
    public let source: DirectoryContactSource
    /// How many places this contact appears (app only). 0 for device.
    public let appOccurrences: Int
    public let updatedAt: Date?

    public init(
        id: String,
        name: String,
        phones: [String] = [],
        email: String? = nil,
        organization: String? = nil,
        notes: String? = nil,
        source: DirectoryContactSource,
        appOccurrences: Int = 0,
        updatedAt: Date? = nil
    ) {
        self.id = id
        self.name = name
        self.phones = phones
        self.email = email
        self.organization = organization
        self.notes = notes
        self.source = source
        self.appOccurrences = appOccurrences
        self.updatedAt = updatedAt
    }

    /// First phone, for single-phone UI paths. `phones` is the source
    /// of truth.
    public var phone: String? { phones.first }
}

/// Signature used to collapse duplicate app contacts into one row.
internal func identitySignature(
    name: String,
    phone: String?,
    email: String?
) -> String {
    var out = name.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    out.append("|")
    out.append((phone ?? "").trimmingCharacters(in: .whitespacesAndNewlines).lowercased())
    out.append("|")
    out.append((email ?? "").trimmingCharacters(in: .whitespacesAndNewlines).lowercased())
    return out
}

/// Collapse phone numbers that differ only by a country-code prefix
/// (e.g. "+91 98765 43210" vs "98765 43210"). The last ten digits
/// are the subscriber number for every major numbering plan we care
/// about, so we group by that suffix and keep the richer
/// representation (more digits → likely carries the country code).
///
/// Preserves first-seen order for visually stable lists.
internal func dedupePhones(_ raw: [String]) -> [String] {
    var order: [String] = []
    var winners: [String: String] = [:]
    for phone in raw {
        let trimmed = phone.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { continue }
        let digits = trimmed.filter { $0.isNumber }
        if digits.isEmpty { continue }
        let key: String
        if digits.count >= 10 {
            key = String(digits.suffix(10))
        } else {
            key = digits
        }
        if let existing = winners[key] {
            let existingDigits = existing.filter { $0.isNumber }.count
            if digits.count > existingDigits {
                winners[key] = trimmed
            }
        } else {
            winners[key] = trimmed
            order.append(key)
        }
    }
    return order.compactMap { winners[$0] }
}
