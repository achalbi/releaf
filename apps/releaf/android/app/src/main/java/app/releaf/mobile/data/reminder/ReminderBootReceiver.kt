/*
 * ReminderBootReceiver.kt
 *
 * Re-registers every pending reminder's alarm after device boot.
 * Android clears the AlarmManager queue on shutdown, so without this
 * receiver any reminder whose target time is in the future would
 * silently never fire after a reboot.
 */

package app.releaf.mobile.data.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.releaf.mobile.ReleafApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ReminderBootReceiver : BroadcastReceiver() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }
        val app = context.applicationContext as? ReleafApp ?: return
        val pending = goAsync()
        scope.launch {
            try {
                app.reminderRepository.rescheduleAll()
            } finally {
                pending.finish()
            }
        }
    }
}
