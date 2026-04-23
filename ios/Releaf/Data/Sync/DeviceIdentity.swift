/*
 * DeviceIdentity.swift
 *
 * Stable per-install UUID. Written once to UserDefaults the first
 * time anything asks for it, and reused forever after. Mirror of
 * Android's `DeviceIdentity.kt`.
 *
 * Used by the sync worker to stamp the Drive manifest with `device_id`
 * so a future multi-device reconciliation can tell "edited by phone"
 * apart from "edited by tablet". Not used as a credential — UserDefaults
 * is fine.
 */

import Foundation

public enum DeviceIdentity {
    private static let key = "releaf.device_id"

    public static func get(defaults: UserDefaults = .standard) -> String {
        if let existing = defaults.string(forKey: key) { return existing }
        let fresh = UUID().uuidString.lowercased()
        defaults.set(fresh, forKey: key)
        return fresh
    }
}
