/*
 * ReminderAlarmReceiver.kt
 *
 * Broadcast receiver that wakes up when a scheduled reminder fires.
 * Two responsibilities: post a notification, and stamp the row with
 * `fired_at` so the list can split upcoming vs. past.
 *
 * Registered in the manifest (not the runtime — we need it to survive
 * the process being asleep).
 */

package app.releaf.mobile.data.reminder

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import app.releaf.mobile.MainActivity
import app.releaf.mobile.R
import app.releaf.mobile.ReleafApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ReminderAlarmReceiver : BroadcastReceiver() {

    // Receivers can't hold long-running coroutine scopes across
    // instances, but goAsync() gives us ~10s to finish work after
    // onReceive returns — more than enough for a DB write + a
    // notification post. Use a supervisor job so a failure on one
    // branch doesn't cancel the other.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val id = intent.getStringExtra(ReminderScheduler.EXTRA_REMINDER_ID) ?: return
        val app = context.applicationContext as? ReleafApp ?: return

        val pendingResult = goAsync()
        scope.launch {
            try {
                val row = app.reminderRepository.findById(id)
                if (row == null || row.deletedAt != null || row.completedAt != null) {
                    return@launch
                }
                postNotification(context, row)
                if (row.recursEveryDays != null && row.recursEveryDays > 0) {
                    // Recurring: bump to the next fire and re-arm.
                    // Don't stamp firedAt — we want the row to stay
                    // in the upcoming section.
                    app.reminderRepository.advanceRecurring(id)
                } else {
                    app.reminderRepository.markFired(id)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun postNotification(context: Context, row: ReminderEntity) {
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val tapPending = PendingIntent.getActivity(
            context,
            row.id.hashCode(),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(row.title)
            .setAutoCancel(true)
            .setContentIntent(tapPending)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)

        row.note?.takeIf { it.isNotBlank() }?.let { body ->
            builder.setContentText(body)
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(body))
        }

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Hashing the id to an Int keeps notifications-per-reminder
        // deterministic so re-posting replaces the previous card.
        nm.notify(row.id.hashCode(), builder.build())
    }

    companion object {
        const val ACTION_FIRE = "app.releaf.mobile.REMINDER_FIRE"

        /** Notification channel id. Channel is created in
         *  [ReleafApp.onCreate] so it exists by the time we try to
         *  notify. */
        const val CHANNEL_ID = "releaf.reminders"
    }
}
