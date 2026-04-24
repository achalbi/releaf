/*
 * ContactDirectory.kt
 *
 * Domain types for the Contacts screen. A `DirectoryContact` is a
 * single entry in the unified address book — it may originate from
 * the app (a `Contact` JSON row on a notepad entry or page) or from
 * the device's phone contacts (via `DeviceContactsProvider`).
 *
 * The repository deduplicates app contacts by their
 * "identity signature" — a lowercased tuple of `(name, phone, email)`
 * — and counts how many times the contact shows up across surfaces.
 */

package app.releaf.mobile.data.contact

import java.time.Instant

enum class DirectoryContactSource {
    App,      // Captured inside a notepad entry or page.
    Device,   // From the OS address book (ContactsContract).
}

data class DirectoryContact(
    /**
     * Stable identifier. For app contacts, the signature hash; for
     * device contacts, the OS `contacts._id`. Not intended to survive
     * a full rebuild of the directory (it's UI-scope).
     */
    val id: String,
    val name: String,
    val phone: String? = null,
    val email: String? = null,
    val organization: String? = null,
    val notes: String? = null,
    val source: DirectoryContactSource,
    /** How many places this contact appears (app only). 0 for device contacts. */
    val appOccurrences: Int = 0,
    /** Most recent update timestamp across the surfaces this contact appears in. */
    val updatedAt: Instant? = null,
)

/** Signature used to collapse duplicate app contacts into one row. */
internal fun identitySignature(
    name: String,
    phone: String?,
    email: String?,
): String = buildString {
    append(name.trim().lowercase())
    append('|')
    append(phone?.trim()?.lowercase().orEmpty())
    append('|')
    append(email?.trim()?.lowercase().orEmpty())
}
