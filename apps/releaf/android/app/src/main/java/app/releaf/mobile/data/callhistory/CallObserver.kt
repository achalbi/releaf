/*
 * CallObserver.kt
 *
 * Wraps the platform Telephony callback so the Contacts screen can
 * capture duration for calls it just initiated. The OS reports
 * three states: IDLE → OFFHOOK → IDLE; we treat the first OFFHOOK
 * after a dial as "connected" and the following IDLE as "ended".
 *
 * Attribution is best-effort: the callback is global (it fires for
 * any call on the device), so we bind the next OFFHOOK/IDLE
 * sequence to whatever history id the caller parked via [attach].
 * Good enough for the "tap dial → have conversation → return"
 * path that's 99% of usage.
 *
 * Permission: [Manifest.permission.READ_PHONE_STATE] (runtime
 * prompt on API 23+). When permission is missing we silently skip
 * observation — the history row still exists with a `started_at`
 * timestamp, just without connect/end events.
 */

// PhoneStateListener is deprecated in API 31+ in favour of
// TelephonyCallback. We intentionally keep both paths: the new
// API on API 31+, the legacy listener on API 30 and below. File-
// level suppression so the import + field declaration don't emit
// the usual deprecation warning — the inline @Suppress at the
// fallback usage already documents the choice.
@file:Suppress("DEPRECATION")

package app.releaf.mobile.data.callhistory

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

class CallObserver(
    private val context: Context,
    private val repository: CallHistoryRepository,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val executor = Executors.newSingleThreadExecutor()

    /** Id of the most recently started call waiting for OFFHOOK. */
    private var pendingId: String? = null
    /** Id of the active call (OFFHOOK already observed). */
    private var activeId: String? = null
    /** Watchdog that clears [pendingId] if no OFFHOOK arrives. */
    private var pendingTimeoutJob: Job? = null

    private var registered: Boolean = false
    private var callbackApi31: Any? = null
    private var callbackLegacy: PhoneStateListener? = null

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE,
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * Register a telephony listener (if permission is granted) and
     * park `callId` as the target of the next OFFHOOK → IDLE
     * sequence. No-op and returns `false` when permission is
     * missing; the caller keeps the bare `started_at` row.
     */
    fun attach(callId: String): Boolean {
        if (!hasPermission()) return false
        ensureRegistered()
        pendingId = callId
        pendingTimeoutJob?.cancel()
        pendingTimeoutJob = scope.launch {
            // If the OS never reports OFFHOOK within 3 minutes the
            // user almost certainly never placed the call (permission
            // sheet, dialer crash, etc.). Drop the binding so a
            // much-later unrelated call doesn't inherit this id.
            delay(3 * 60 * 1000L)
            if (pendingId == callId) {
                pendingId = null
                // Mark the row as ended (no connect, no duration) so
                // the history screen stops showing it as pending.
                scope.launch { repository.recordEnded(callId) }
            }
        }
        return true
    }

    private fun ensureRegistered() {
        if (registered) return
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    handleState(state)
                }
            }
            try {
                tm.registerTelephonyCallback(executor, cb)
                callbackApi31 = cb
                registered = true
            } catch (_: SecurityException) {
                // Permission revoked between check and register.
            }
        } else {
            @Suppress("DEPRECATION")
            val legacy = object : PhoneStateListener() {
                @Deprecated("Legacy PhoneStateListener kept for API < 31.")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    handleState(state)
                }
            }
            try {
                @Suppress("DEPRECATION")
                tm.listen(legacy, PhoneStateListener.LISTEN_CALL_STATE)
                callbackLegacy = legacy
                registered = true
            } catch (_: SecurityException) {
            }
        }
    }

    private fun handleState(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                val id = pendingId ?: return
                pendingId = null
                activeId = id
                pendingTimeoutJob?.cancel()
                pendingTimeoutJob = null
                scope.launch { repository.recordConnected(id) }
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                val pending = pendingId
                val active = activeId
                // A connected call ending.
                if (active != null) {
                    activeId = null
                    scope.launch { repository.recordEnded(active) }
                }
                // A dialed call that never connected (ringout / cancel).
                if (pending != null && active == null) {
                    pendingId = null
                    pendingTimeoutJob?.cancel()
                    pendingTimeoutJob = null
                    scope.launch { repository.recordEnded(pending) }
                }
            }
            TelephonyManager.CALL_STATE_RINGING -> {
                // Incoming call — ignore.
            }
        }
    }

    /**
     * Unregister the telephony listener. Typically called from the
     * Application's lifecycle teardown or at process exit; not
     * strictly required since the Executor + listener live for the
     * process lifetime.
     */
    fun shutdown() {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (callbackApi31 as? TelephonyCallback)?.let { tm?.unregisterTelephonyCallback(it) }
        } else {
            @Suppress("DEPRECATION")
            callbackLegacy?.let { tm?.listen(it, PhoneStateListener.LISTEN_NONE) }
        }
        callbackApi31 = null
        callbackLegacy = null
        registered = false
    }
}
