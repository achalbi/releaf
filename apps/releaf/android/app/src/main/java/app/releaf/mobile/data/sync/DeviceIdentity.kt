/*
 * DeviceIdentity.kt
 *
 * Stable per-install UUID. Written once to a plain SharedPreferences file
 * the first time anything asks for it, and reused forever after. Used by
 * the sync worker to stamp the Drive manifest with `device_id` so a
 * future multi-device reconciliation can tell "edited by phone" apart
 * from "edited by tablet."
 *
 * Not using Settings.Secure.ANDROID_ID: that value can be reset with a
 * factory wipe, is shared across all apps on-device (privacy), and on
 * some OEMs is zero for workspace users. A per-install UUID is enough
 * for our needs and doesn't leak anything cross-app.
 *
 * Not stored in EncryptedSharedPreferences because it isn't sensitive —
 * it's not a credential, just a rotation-stable identifier.
 */

package app.releaf.mobile.data.sync

import android.content.Context
import java.util.UUID

object DeviceIdentity {
    private const val PREFS_NAME    = "releaf_device"
    private const val KEY_DEVICE_ID = "device_id"

    fun get(context: Context): String {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(KEY_DEVICE_ID, null)?.let { return it }
        val fresh = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, fresh).apply()
        return fresh
    }
}
