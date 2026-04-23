/*
 * KeychainTokenStore.swift
 *
 * Keychain-backed storage for the Google auth session. Replaces the
 * UserDefaults placeholder in `AuthStore.swift` (flagged as a TODO at
 * the top of that file). Keychain items survive app uninstall/reinstall
 * unless we explicitly wipe them, which we do on sign-out.
 *
 * Service identifier: "app.releaf.mobile.auth" — namespaced to our
 * bundle id so tests / debug builds don't collide with other apps.
 *
 * The store persists the full `GoogleAuthSession` as a Codable blob
 * under a single account key. It's serialized via JSONEncoder — the
 * token itself isn't meant to be human-readable and we don't checksum
 * this blob for sync, so canonical JSON isn't needed here.
 */

import Foundation
import Security

public enum KeychainTokenStore {

    private static let service = "app.releaf.mobile.auth"
    private static let account = "google-session"

    public static func save(_ session: GoogleAuthSession) throws {
        let data = try JSONEncoder().encode(StoredSession(session))
        let baseQuery: [CFString: Any] = [
            kSecClass:       kSecClassGenericPassword,
            kSecAttrService: service,
            kSecAttrAccount: account,
        ]
        // Delete any existing item so SecItemAdd has a clean slot.
        SecItemDelete(baseQuery as CFDictionary)

        var addQuery = baseQuery
        addQuery[kSecValueData] = data
        // Accessible whenever the device has been unlocked once —
        // sync runs in the background after a cold launch.
        addQuery[kSecAttrAccessible] = kSecAttrAccessibleAfterFirstUnlock
        let status = SecItemAdd(addQuery as CFDictionary, nil)
        guard status == errSecSuccess else {
            throw KeychainError.osError(status)
        }
    }

    public static func load() -> GoogleAuthSession? {
        let query: [CFString: Any] = [
            kSecClass:        kSecClassGenericPassword,
            kSecAttrService:  service,
            kSecAttrAccount:  account,
            kSecReturnData:   true,
            kSecMatchLimit:   kSecMatchLimitOne,
        ]
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        guard status == errSecSuccess, let data = item as? Data else {
            return nil
        }
        return (try? JSONDecoder().decode(StoredSession.self, from: data))?.toDomain()
    }

    public static func clear() {
        let query: [CFString: Any] = [
            kSecClass:       kSecClassGenericPassword,
            kSecAttrService: service,
            kSecAttrAccount: account,
        ]
        SecItemDelete(query as CFDictionary)
    }

    public enum KeychainError: Error {
        case osError(OSStatus)
    }

    /// Private Codable companion — lets us JSON-encode `Date`
    /// deterministically without needing GoogleAuthSession to pick a
    /// strategy itself.
    private struct StoredSession: Codable {
        let userId: String
        let email: String
        let displayName: String?
        let accessToken: String
        let refreshToken: String?
        let expiresAt: Date

        init(_ s: GoogleAuthSession) {
            userId = s.userId
            email = s.email
            displayName = s.displayName
            accessToken = s.accessToken
            refreshToken = s.refreshToken
            expiresAt = s.expiresAt
        }

        func toDomain() -> GoogleAuthSession {
            GoogleAuthSession(
                userId: userId,
                email: email,
                displayName: displayName,
                accessToken: accessToken,
                refreshToken: refreshToken,
                expiresAt: expiresAt
            )
        }
    }
}
