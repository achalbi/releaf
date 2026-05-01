/*
 * QuickInkApp.kt
 *
 * Application-level singleton for QuickInk. Counterpart to Releaf's
 * `ReleafApp.kt`, but intentionally near-empty at scaffold time.
 *
 * What goes here as the MVP flow lands (per QUICKINK_PROPOSAL.md §6.4
 * + §4 Phase 3 step 14):
 *   - QuickInkDatabase wiring (forked v1_initial.sql per §3)
 *   - AuthStore + GoogleAuthClient instances (from :shared:auth)
 *   - DriveClient instance (OkHttp-backed, from :shared:drive)
 *   - SyncRepository + SyncScheduler (from :shared:sync); the
 *     `SyncDataSource` impl will live in this app target since
 *     it consumes QuickInk's local schema
 *   - The `runScope` CoroutineScope for the auth-state observer
 *
 * Mirror of the wiring already present in `ReleafApp.kt` — the
 * shapes are stable enough that QuickInk lifts the contract; the
 * only Releaf-specific bit (the panchanga / contact / call-history /
 * reminder / shelf / notebook repositories) doesn't apply here.
 */

package app.quickink.mobile

import android.app.Application

class QuickInkApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Process-scoped wiring lands here as features ship. See file
        // header for the queued list.
    }
}
