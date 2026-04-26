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
    /**
     * All phone numbers stored for this contact, de-duplicated and
     * in capture order. Empty when there's no phone at all. Callers
     * that only want the "primary" number use [phone] below.
     */
    val phones: List<String> = emptyList(),
    val email: String? = null,
    val organization: String? = null,
    val notes: String? = null,
    val source: DirectoryContactSource,
    /** How many places this contact appears (app only). 0 for device contacts. */
    val appOccurrences: Int = 0,
    /** Most recent update timestamp across the surfaces this contact appears in. */
    val updatedAt: Instant? = null,
) {
    /** First phone number, if any — kept as a convenience for the
     *  row-meta line and other single-phone displays. */
    val phone: String? get() = phones.firstOrNull()
}

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

/**
 * Collapse phone numbers that differ only by a country-code prefix
 * (e.g. "+91 98765 43210" vs "98765 43210"). The last ten digits are
 * the subscriber number for every major numbering plan we care about,
 * so we group by that suffix and keep the richer representation
 * (more digits → likely carries the country code).
 *
 * Preserves first-seen order for visually stable lists.
 */
internal fun dedupePhones(raw: List<String>): List<String> {
    val winners = linkedMapOf<String, String>()
    for (phone in raw) {
        val trimmed = phone.trim()
        if (trimmed.isEmpty()) continue
        val digits = trimmed.filter { it.isDigit() }
        if (digits.isEmpty()) continue
        val key = if (digits.length >= 10) digits.takeLast(10) else digits
        val existing = winners[key]
        if (existing == null) {
            winners[key] = trimmed
        } else {
            val existingDigits = existing.count { it.isDigit() }
            if (digits.length > existingDigits) winners[key] = trimmed
        }
    }
    return winners.values.toList()
}
