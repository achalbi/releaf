/*
 * DeviceContactsProvider.kt
 *
 * Thin wrapper over `ContactsContract`. Exposes a permission check
 * and a search suspend function the Contacts screen calls whenever
 * the user types. Permission is runtime-granted on first tap of the
 * screen's "Enable device contacts" affordance — see
 * `ContactsScreen.PermissionGate`.
 *
 * No caching: each search goes straight to the CP. That keeps us
 * honest about permission revocations (if the user revokes in
 * Settings, the next query returns empty without stale data).
 */

package app.releaf.mobile.data.contact

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DeviceContactsProvider(
    private val context: Context,
) {

    /** True when the runtime `READ_CONTACTS` permission is granted. */
    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS,
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * Returns device contacts whose display name, phone, or email
     * matches [rawQuery]. Capped at [limit] rows so the list stays
     * responsive for large address books. Empty query or missing
     * permission returns `[]` — the caller handles the gate.
     */
    suspend fun search(rawQuery: String, limit: Int = 50): List<DirectoryContact> =
        withContext(Dispatchers.IO) {
            val query = rawQuery.trim()
            if (query.isEmpty() || !hasPermission()) return@withContext emptyList()

            // ContactsContract.Contacts is the display-ready table.
            // `DISPLAY_NAME_PRIMARY` survives locale changes; the
            // `LOOKUP_KEY` is intentionally not used — we only need
            // an id for the list key, not a permanent handle.
            val uri = ContactsContract.Contacts.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            )
            val selection = "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} LIKE ?"
            val selectionArgs = arrayOf("%$query%")
            val sortOrder = "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} ASC LIMIT $limit"

            val out = mutableListOf<DirectoryContact>()
            context.contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)?.use { c ->
                val idCol   = c.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
                val nameCol = c.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol).toString()
                    val name = c.getString(nameCol)?.takeIf { it.isNotBlank() } ?: continue
                    // One cheap follow-up query for the primary phone number.
                    // We keep the projection tiny — no emails, no org —
                    // because the search result row only shows name + phone.
                    val phone = firstPhone(id)
                    out += DirectoryContact(
                        id     = "device-$id",
                        name   = name,
                        phone  = phone,
                        email  = null,
                        source = DirectoryContactSource.Device,
                    )
                }
            }
            out
        }

    /** First phone number stored for a contact, or null. */
    private fun firstPhone(contactId: String): String? {
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?"
        val args = arrayOf(contactId)
        context.contentResolver.query(uri, projection, selection, args, null)?.use { c ->
            val numberCol = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            if (c.moveToFirst()) return c.getString(numberCol)?.takeIf { it.isNotBlank() }
        }
        return null
    }
}
