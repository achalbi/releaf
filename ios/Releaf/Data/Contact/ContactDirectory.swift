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
    public let phone: String?
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
        phone: String? = nil,
        email: String? = nil,
        organization: String? = nil,
        notes: String? = nil,
        source: DirectoryContactSource,
        appOccurrences: Int = 0,
        updatedAt: Date? = nil
    ) {
        self.id = id
        self.name = name
        self.phone = phone
        self.email = email
        self.organization = organization
        self.notes = notes
        self.source = source
        self.appOccurrences = appOccurrences
        self.updatedAt = updatedAt
    }
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
