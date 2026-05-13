/*
 * LegacyCategoriesPrefixCleanup.kt
 *
 * Workspace v1 follow-up to Phase A.2. The Drive payload prefix for
 * tag rows moved from `categories/{id}.json` to `tags/{id}.json` in
 * Phase A.3b — new writes have been landing under `tags/` since.
 * After the brief's two-week soak window, this one-shot cleanup
 * pass walks the user's `Thoughtbasics/QuickInk/categories/` folder
 * and trashes every leaf file (and the empty folder itself).
 *
 * Mirror of `LegacyCategoriesPrefixCleanup.swift` in QuickInk's
 * iOS target.
 *
 * Why a per-user, sync-side cleanup rather than a server-side
 * sweep: QuickInk has no server. Each user's data lives in their
 * own Drive; only their installed client has the OAuth token to
 * walk it. Running this once per signed-in install keeps the
 * cleanup user-scoped without any new infra.
 *
 * Posture:
 *   - SharedPreferences-guarded — runs exactly once per (install,
 *     user) tuple, even if the worker re-fires while a partial
 *     pass is in flight.
 *   - Best-effort — a transient network / auth failure leaves the
 *     flag unset, so the next successful sync retries.
 *   - Bounded — `listChildren` on a single folder is a single API
 *     call; per-file trash is one call each. Worst case: ~50 tag
 *     files for a power user. Drive's per-user quota absorbs that
 *     comfortably alongside the rest of the sync pass.
 *
 * The companion `tags/` writes have been canonical since A.3b; the
 * canonical SQL `v4_workspace.sql` documents the staged rename.
 */

package app.quickink.mobile.data.sync

import android.content.Context
import android.util.Log
import app.releaf.mobile.data.drive.DriveClient
import app.releaf.mobile.data.drive.DriveError

object LegacyCategoriesPrefixCleanup {

    private const val TAG = "QuickInkCleanup"
    private const val PREFS = "quickink_migrations"
    private const val ROOT_FOLDER = "Thoughtbasics/QuickInk"
    private const val LEGACY_FOLDER = "categories"

    private fun flagKey(userId: String): String =
        "quickink.workspace.categories-cleanup-v1:$userId"

    /**
     * Run the cleanup pass. Idempotent — short-circuits when the
     * flag is set. Returns the number of files trashed (or `0` on
     * the no-op / not-found / already-clean paths).
     *
     * Throws [DriveError.Unauthenticated] / [DriveError.RateLimited]
     * so the caller's worker can route the failure through its
     * normal retry / sign-out path; every other error is swallowed
     * and reported as `0` since the cleanup is fire-and-forget.
     */
    suspend fun runIfNeeded(
        context: Context,
        driveClient: DriveClient,
        accessToken: String,
        userId: String,
    ): Int {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = flagKey(userId)
        if (prefs.getBoolean(key, false)) return 0

        return try {
            // Walk the path manually so a missing root / missing
            // legacy folder is a clean exit (not an error).
            val root = driveClient.ensureRootFolder(ROOT_FOLDER, accessToken)
            val legacy = driveClient.listChildren(root.id, accessToken)
                .firstOrNull { it.name == LEGACY_FOLDER && it.isFolder }
            if (legacy == null) {
                Log.i(TAG, "no legacy `categories/` folder for user=${userId.take(8)}…, marking done")
                prefs.edit().putBoolean(key, true).apply()
                return 0
            }
            val files = driveClient.listChildren(legacy.id, accessToken)
            var trashed = 0
            for (f in files) {
                try {
                    driveClient.trash(f.id, accessToken)
                    trashed += 1
                } catch (e: DriveError.Unauthenticated) {
                    throw e
                } catch (e: DriveError.RateLimited) {
                    throw e
                } catch (e: DriveError) {
                    Log.w(TAG, "trash($f.id) failed: $e — continuing")
                }
            }
            // Empty folder itself goes too. Best-effort.
            try { driveClient.trash(legacy.id, accessToken) }
            catch (e: DriveError.Unauthenticated) { throw e }
            catch (e: DriveError.RateLimited)    { throw e }
            catch (_: DriveError) { /* leave the folder if Drive refuses */ }

            prefs.edit().putBoolean(key, true).apply()
            Log.i(TAG, "trashed $trashed legacy category files for user=${userId.take(8)}…")
            trashed
        } catch (e: DriveError.Unauthenticated) {
            throw e
        } catch (e: DriveError.RateLimited) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "cleanup pass failed: $e — will retry on next sync")
            0
        }
    }
}
