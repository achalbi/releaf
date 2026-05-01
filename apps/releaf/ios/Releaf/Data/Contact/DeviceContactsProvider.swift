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
// Apple's Contacts framework hasn't been audited for Sendable in
// Swift 6 — `CNContactStore` / `CNKeyDescriptor` / `NSPredicate`
// surface a forest of "non-Sendable capture" diagnostics inside
// otherwise correct code. `@preconcurrency` defers those to runtime,
// matching how every Apple-platform Sendable migration has handled
// pre-Swift-6 frameworks until they get their own annotations.
@preconcurrency import Contacts

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
        // `.limited` is iOS-only — Contacts on macOS doesn't expose
        // a partial-access tier, so the macOS path falls through to
        // the plain `.authorized` check.
        #if os(iOS)
        if #available(iOS 18.0, *) {
            return status == .authorized || status == .limited
        }
        #endif
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
        // NSPredicate isn't `Sendable` (Foundation hasn't annotated it),
        // and `@preconcurrency import` only silences diagnostics for
        // the named module — Foundation is auto-imported. Wrap the
        // predicate in an `@unchecked Sendable` box so the @Sendable
        // DispatchQueue closure can capture it. Predicates are
        // immutable after construction, so the unchecked promise is
        // honest.
        let predicate = UncheckedSendable(CNContact.predicateForContacts(matchingName: query))

        return await withCheckedContinuation { cont in
            DispatchQueue.global(qos: .userInitiated).async { [store] in
                do {
                    let results = try store.unifiedContacts(matching: predicate.value, keysToFetch: keys)
                    var out: [DirectoryContact] = []
                    out.reserveCapacity(min(limit, results.count))
                    for contact in results.prefix(limit) {
                        let name = CNContactFormatter.string(from: contact, style: .fullName)
                            ?? [contact.givenName, contact.familyName]
                                .filter { !$0.isEmpty }
                                .joined(separator: " ")
                        guard !name.isEmpty else { continue }
                        // Pull every phone number linked to the
                        // contact (mobile, home, work, iPhone, etc.),
                        // then collapse entries that differ only by a
                        // country-code prefix (e.g. "+91 …" vs "…").
                        var rawPhones: [String] = []
                        for entry in contact.phoneNumbers {
                            let raw = entry.value.stringValue.trimmingCharacters(in: .whitespacesAndNewlines)
                            if raw.isEmpty { continue }
                            rawPhones.append(raw)
                        }
                        let phones = dedupePhones(rawPhones)
                        out.append(
                            DirectoryContact(
                                id:           "device-\(contact.identifier)",
                                name:         name,
                                phones:       phones,
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

/// `@unchecked Sendable` wrapper for cross-actor capture of values
/// whose types haven't been annotated `Sendable` yet (NSPredicate,
/// CNKeyDescriptor, etc.). Use only for values that are immutable
/// after construction — that's the implicit promise.
private struct UncheckedSendable<T>: @unchecked Sendable {
    let value: T
    init(_ value: T) { self.value = value }
}
