/*
 * LegacyCategoriesPrefixCleanup.swift
 *
 * Workspace v1 follow-up to Phase A.2. The Drive payload prefix for
 * tag rows moved from `categories/{id}.json` to `tags/{id}.json` in
 * Phase A.3b — new writes have been landing under `tags/` since.
 * After the brief's two-week soak window, this one-shot cleanup
 * pass walks the user's `Thoughtbasics/QuickInk/categories/` folder
 * and trashes every leaf file (and the empty folder itself).
 *
 * Mirror of `LegacyCategoriesPrefixCleanup.kt` in QuickInk's
 * Android target.
 *
 * Posture:
 *   - UserDefaults-guarded — runs exactly once per (install, user)
 *     tuple, even if the sync re-fires while a partial pass is in
 *     flight.
 *   - Best-effort — a transient network / auth failure leaves the
 *     flag unset, so the next successful sync retries.
 *   - Bounded — `listChildren` on a single folder is a single API
 *     call; per-file trash is one call each. Worst case: ~50 tag
 *     files for a power user.
 */

import Foundation
import ReleafCoreDrive
import ReleafCoreSync

enum LegacyCategoriesPrefixCleanup {

    private static let prefsKeyPrefix = "quickink.workspace.categories-cleanup-v1."
    private static let rootFolderName = "Thoughtbasics/QuickInk"
    private static let legacyFolderName = "categories"

    private static func flagKey(for userId: String) -> String {
        prefsKeyPrefix + userId
    }

    /// Run the cleanup pass. Idempotent — short-circuits when the
    /// flag is set. Returns the number of files trashed (or `0` on
    /// the no-op / not-found / already-clean paths).
    ///
    /// Rethrows `DriveError.unauthenticated` so the caller can
    /// route the failure through its normal retry / sign-out path;
    /// every other error is swallowed and reported as `0` since
    /// the cleanup is fire-and-forget.
    @discardableResult
    static func runIfNeeded(
        driveClient: DriveClient,
        accessToken: String,
        userId: String
    ) async throws -> Int {
        let defaults = UserDefaults.standard
        let key = flagKey(for: userId)
        if defaults.bool(forKey: key) { return 0 }

        do {
            // Walk manually so a missing root / missing legacy folder
            // is a clean exit, not an error.
            let root = try await driveClient.ensureRootFolder(
                named: rootFolderName,
                accessToken: accessToken
            )
            let children = try await driveClient.listChildren(
                of: root.id,
                accessToken: accessToken
            )
            guard let legacy = children.first(where: {
                $0.name == legacyFolderName && $0.isFolder
            }) else {
                print("[QuickInkCleanup] no legacy `categories/` folder for user=\(userId.prefix(8))…, marking done")
                defaults.set(true, forKey: key)
                return 0
            }
            let files = try await driveClient.listChildren(
                of: legacy.id,
                accessToken: accessToken
            )
            var trashed = 0
            for f in files {
                do {
                    try await driveClient.trash(fileId: f.id, accessToken: accessToken)
                    trashed += 1
                } catch DriveError.unauthenticated {
                    throw DriveError.unauthenticated
                } catch {
                    print("[QuickInkCleanup] trash(\(f.id)) failed: \(error) — continuing")
                }
            }
            // Empty folder itself goes too. Best-effort.
            do {
                try await driveClient.trash(fileId: legacy.id, accessToken: accessToken)
            } catch DriveError.unauthenticated { throw DriveError.unauthenticated }
            catch { /* leave the folder if Drive refuses */ }

            defaults.set(true, forKey: key)
            print("[QuickInkCleanup] trashed \(trashed) legacy category files for user=\(userId.prefix(8))…")
            return trashed
        } catch DriveError.unauthenticated {
            throw DriveError.unauthenticated
        } catch {
            print("[QuickInkCleanup] cleanup pass failed: \(error) — will retry on next sync")
            return 0
        }
    }
}
