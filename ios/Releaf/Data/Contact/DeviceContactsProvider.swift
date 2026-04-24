/*
 * DeviceContactsProvider.swift
 *
 * Thin wrapper over `CNContactStore`. Exposes a permission check
 * and a search function the Contacts screen calls whenever the
 * user types. Permission is requested on first tap of the
 * "Enable device contacts" affordance.
 *
 * No caching: each query hits CNContactStore directly so the list
 * reflects the latest address book state (and permission changes)
 * without stale reads.
 */

import Foundation
import Contacts

public final class DeviceContactsProvider: @unchecked Sendable {

    private let store = CNContactStore()

    public init() {}

    /// Current authorization status for the Contacts entity. The
    /// UI uses this to decide between rendering the search results
    /// or the "Enable device contacts" CTA.
    public func authorizationStatus() -> CNAuthorizationStatus {
        CNContactStore.authorizationStatus(for: .contacts)
    }

    public var hasPermission: Bool {
        let status = authorizationStatus()
        // `.authorized` is the legacy granted state; `.limited` on
        // iOS 18+ also allows read access to the chosen subset.
        if #available(iOS 18.0, *) {
            return status == .authorized || status == .limited
        }
        return status == .authorized
    }

    /// Request permission from the OS. Completion fires on the
    /// main actor.
    public func requestPermission() async -> Bool {
        do {
            return try await store.requestAccess(for: .contacts)
        } catch {
            return false
        }
    }

    /// Search the device address book for contacts whose name,
    /// phone, or email matches [rawQuery]. Capped at [limit].
    public func search(rawQuery: String, limit: Int = 50) async -> [DirectoryContact] {
        let query = rawQuery.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty, hasPermission else { return [] }

        let keys: [CNKeyDescriptor] = [
            CNContactFormatter.descriptorForRequiredKeys(for: .fullName),
            CNContactPhoneNumbersKey as CNKeyDescriptor,
            CNContactEmailAddressesKey as CNKeyDescriptor,
            CNContactOrganizationNameKey as CNKeyDescriptor,
        ]
        let predicate = CNContact.predicateForContacts(matchingName: query)

        return await withCheckedContinuation { cont in
            DispatchQueue.global(qos: .userInitiated).async { [store] in
                do {
                    let results = try store.unifiedContacts(matching: predicate, keysToFetch: keys)
                    var out: [DirectoryContact] = []
                    out.reserveCapacity(min(limit, results.count))
                    for contact in results.prefix(limit) {
                        let name = CNContactFormatter.string(from: contact, style: .fullName)
                            ?? [contact.givenName, contact.familyName]
                                .filter { !$0.isEmpty }
                                .joined(separator: " ")
                        guard !name.isEmpty else { continue }
                        out.append(
                            DirectoryContact(
                                id:           "device-\(contact.identifier)",
                                name:         name,
                                phone:        contact.phoneNumbers.first?.value.stringValue,
                                email:        contact.emailAddresses.first?.value as String?,
                                organization: contact.organizationName.isEmpty ? nil : contact.organizationName,
                                source:       .device
                            )
                        )
                    }
                    cont.resume(returning: out)
                } catch {
                    cont.resume(returning: [])
                }
            }
        }
    }
}
