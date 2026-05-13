/*
 * WorkspaceDeviceId.swift
 *
 * Stable per-install identifier surfaced into the Workspace v1
 * Continue card signal (captures.last_opened_device). Reserved for
 * the future cross-device handoff UX ("continue on iPhone, 2h
 * ago") that surfaces which device produced the most recent
 * last_opened_at.
 *
 * Implementation: stored in UserDefaults under a fixed key; lazily
 * minted with a UUIDv4 if missing. Stable across app launches;
 * resets only on a reinstall (UserDefaults survives app updates).
 *
 * Mirror of Android's `DeviceIdentity.get(context:)`. They emit
 * different ids — that's fine for v1 (the field is opaque to the
 * UI today).
 */

import Foundation

public enum WorkspaceDeviceId {
    private static let key = "quickink.workspace.device-id"

    /// Stable install id for the Continue card's device-of-origin
    /// stamp. Returns the cached value or mints a new one and
    /// persists it.
    public static var current: String {
        let defaults = UserDefaults.standard
        if let existing = defaults.string(forKey: key), !existing.isEmpty {
            return existing
        }
        let fresh = UUID().uuidString
        defaults.set(fresh, forKey: key)
        return fresh
    }
}
