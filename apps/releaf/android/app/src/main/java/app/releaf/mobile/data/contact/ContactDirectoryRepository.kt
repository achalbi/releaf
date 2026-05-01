/*
 * ContactDirectoryRepository.kt
 *
 * Unified address book pulled from the two places the app persists
 * contacts today: `notepad_entries.contacts` and `pages.contacts`.
 * Both are JSON arrays of `Contact` rows; the repository parses
 * each, joins them, dedupes by `(name, phone, email)` signature,
 * and emits a `Flow<List<DirectoryContact>>` the Contacts screen
 * can render without caring about the storage shape.
 *
 * Device contacts live behind a separate path (search-only, opt-in
 * permission) and are handled by `DeviceContactsProvider`.
 */

package app.releaf.mobile.data.contact

import app.releaf.mobile.data.notebook.Contact as AppContact
import app.releaf.mobile.data.notebook.PageDao
import app.releaf.mobile.data.notebook.PageEntity
import app.releaf.mobile.data.notebook.parseContacts
import app.releaf.mobile.data.notepad.NotepadDao
import app.releaf.mobile.data.notepad.NotepadEntry
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class ContactDirectoryRepository(
    private val notepadDao: NotepadDao,
    private val pageDao: PageDao,
) {

    /**
     * Observe all app contacts for a user. `userId` scopes the
     * notepad half; page contacts aren't user-scoped today so they
     * always flow through.
     */
    fun observeAll(userId: String): Flow<List<DirectoryContact>> =
        combine(
            notepadDao.observeActive(userId),
            pageDao.observeAllActive(),
        ) { entries, pages ->
            aggregate(entries = entries, pages = pages)
        }

    private fun aggregate(
        entries: List<NotepadEntry>,
        pages: List<PageEntity>,
    ): List<DirectoryContact> {
        // Key-by-signature so two pages referencing the same person
        // collapse to one row. Preserve the most-recent `updatedAt`
        // so the list can sort by recency.
        val bucket = mutableMapOf<String, DirectoryBuilder>()

        entries.forEach { entry ->
            val contacts = runCatching { entry.contacts.parseContacts() }
                .getOrDefault(emptyList())
            val updated = parseIsoOrNull(entry.updatedAt)
            contacts.forEach { record(it, updated, bucket) }
        }
        pages.forEach { page ->
            val contacts = runCatching { page.contacts.parseContacts() }
                .getOrDefault(emptyList())
            val updated = parseIsoOrNull(page.updatedAt)
            contacts.forEach { record(it, updated, bucket) }
        }

        return bucket.values
            .map { it.build() }
            // Alphabetical by name, with blank-name rows last. The
            // screen layer can re-sort (recency, etc.) over this.
            .sortedWith(
                compareBy<DirectoryContact> { it.name.isBlank() }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name.ifBlank { "~" } }
            )
    }

    private fun record(
        c: AppContact,
        updated: Instant?,
        bucket: MutableMap<String, DirectoryBuilder>,
    ) {
        // A single Android Contact can carry both a mobile phone and
        // a landline. Treat them as distinct entries in the phones
        // list so both show up in the picker.
        val contactPhones = listOfNotNull(
            c.phone?.trim()?.ifEmpty { null },
            c.landline?.trim()?.ifEmpty { null },
        )
        val signature = identitySignature(
            name  = c.name,
            phone = contactPhones.firstOrNull(),
            email = c.email,
        )
        val builder = bucket.getOrPut(signature) {
            DirectoryBuilder(
                signature    = signature,
                name         = c.name.trim(),
                email        = c.email?.trim()?.ifEmpty { null },
                organization = c.organization?.trim()?.ifEmpty { null },
                notes        = null,
            )
        }
        builder.occurrences += 1
        for (phone in contactPhones) builder.addPhone(phone)
        if (updated != null && (builder.updatedAt == null || updated > builder.updatedAt!!)) {
            builder.updatedAt = updated
        }
    }

    private class DirectoryBuilder(
        val signature: String,
        val name: String,
        val email: String?,
        val organization: String?,
        val notes: String?,
    ) {
        var occurrences: Int = 0
        var updatedAt: Instant? = null
        private val phones: MutableList<String> = mutableListOf()

        fun addPhone(raw: String) {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return
            phones += trimmed
        }

        fun build(): DirectoryContact = DirectoryContact(
            id             = signature,
            name           = name.ifBlank { "Unnamed" },
            // Final pass dedupes numbers that differ only by a
            // country-code prefix (e.g. "+91 …" vs "…").
            phones         = dedupePhones(phones),
            email          = email,
            organization   = organization,
            notes          = notes,
            source         = DirectoryContactSource.App,
            appOccurrences = occurrences,
            updatedAt      = updatedAt,
        )
    }

    private fun parseIsoOrNull(iso: String): Instant? =
        runCatching { Instant.parse(iso) }.getOrNull()
}
