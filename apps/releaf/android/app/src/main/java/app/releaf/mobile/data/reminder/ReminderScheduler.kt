/*
 * ReminderScheduler.kt
 *
 * Thin façade over AlarmManager. Exposes `schedule(id, remindAt)` and
 * `cancel(id)` so the repository doesn't have to think about
 * PendingIntent flags or exact-alarm permission gating.
 *
 * Exact vs. inexact: reminders are user-facing time-anchored events
 * (e.g. "pay rent at 9am on the 1st"), which is exactly the contract
 * API 31+ enforces `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM` for. We
 * request `USE_EXACT_ALARM` in the manifest (granted at install on API
 * 33+, auto-denied on OEMs that don't allow it — fine, we silently
 * downgrade to the inexact scheduler in that case).
 */

package app.releaf.mobile.data.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

object ReminderScheduler {
    /** Extra carrying the reminder id on the fired intent. */
    const val EXTRA_REMINDER_ID = "reminderId"

    /**
     * Schedule an exact wake-up alarm that delivers a broadcast to
     * [ReminderAlarmReceiver] at [remindAtMs]. Safe to call repeatedly
     * for the same id — AlarmManager replaces an existing alarm that
     * matches the PendingIntent.
     */
    fun schedule(context: Context, id: String, remindAtMs: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // create=true always yields a non-null PendingIntent — the
        // nullability comes from FLAG_NO_CREATE lookups on the cancel
        // path. Guard with !! so the platform APIs (non-null) type-check.
        val pending = pendingIntentFor(context, id, create = true)!!
        // Exact wake-up on API 31+ requires the USE_EXACT_ALARM or
        // SCHEDULE_EXACT_ALARM permission. Downgrade to the inexact
        // scheduler when the OS says we can't have it — better a few-
        // minute-late fire than a silent miss.
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (canExact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, remindAtMs, pending)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, remindAtMs, pending)
        }
    }

    /** Cancel any alarm previously scheduled for [id]. Idempotent. */
    fun cancel(context: Context, id: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pending = pendingIntentFor(context, id, create = false) ?: return
        am.cancel(pending)
        pending.cancel()
    }

    /**
     * Build (or look up) the PendingIntent that targets
     * [ReminderAlarmReceiver] for [id]. Stable request-code derived
     * from the id so re-scheduling the same reminder replaces the
     * existing alarm rather than stacking a second one.
     *
     * [create]=false uses FLAG_NO_CREATE, returning `null` if no PI
     * already exists — we want that path on cancel so we don't
     * resurrect an intent we're about to throw away.
     */
    private fun pendingIntentFor(context: Context, id: String, create: Boolean): PendingIntent? {
        val intent = Intent(context, ReminderAlarmReceiver::class.java).apply {
            action = ReminderAlarmReceiver.ACTION_FIRE
            putExtra(EXTRA_REMINDER_ID, id)
            // A unique data URI forces Android to treat PendingIntents
            // for different reminder ids as distinct — otherwise
            // PendingIntent.getBroadcast() would return the same PI for
            // every reminder and we'd only ever see one alarm fire.
            setData(android.net.Uri.parse("releaf://reminder/$id"))
        }
        val flags = (if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE) or
            PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, id.hashCode(), intent, flags)
    }
}
