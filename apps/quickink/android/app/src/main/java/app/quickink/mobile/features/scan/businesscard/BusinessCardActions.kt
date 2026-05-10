/*
 * BusinessCardActions.kt
 *
 * Glue code between [BusinessCardExtractor] and the system contact
 * intent. Loads OCR blocks for a capture, runs extraction, and
 * launches `Intent(ContactsContract.Intents.Insert.ACTION)` with the
 * (possibly user-edited) fields filled in.
 *
 * Lives next to AddContactReviewSheet so the call sites in
 * ScanDetailScreen don't have to know about JSON deserialisation,
 * intent extras, or the empty-state "form opens blank" fallback.
 */

package app.quickink.mobile.features.scan.businesscard

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.ContactsContract
import android.util.Log
import android.widget.Toast
import app.quickink.mobile.data.ocr.OcrResultDao
import app.releaf.shared.scan.OcrBlock
import app.releaf.shared.scan.businesscard.BusinessCardExtractor
import app.releaf.shared.scan.businesscard.ExtractedContact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer

/**
 * Load every OCR block for [captureId] (every page, in page order)
 * and run the business-card extractor. Empty input → returns
 * [ExtractedContact.empty]; the review sheet still opens with empty
 * fields so the user can fill in by hand.
 */
suspend fun runBusinessCardExtraction(
    captureId: String,
    ocrDao: OcrResultDao,
): ExtractedContact = withContext(Dispatchers.Default) {
    val rows = ocrDao.observeForCapture(captureId).first()
    if (rows.isEmpty()) return@withContext ExtractedContact.empty

    val allBlocks = mutableListOf<OcrBlock>()
    for (row in rows) {
        try {
            // Each row's `blocks_json` is the per-page block list as
            // emitted by the OCR engine (List<OcrBlock> JSON).
            // Decoder mirrors the encoder on the scan-flow side.
            val pageBlocks = json.decodeFromString(
                ListSerializer(OcrBlock.serializer()),
                row.blocksJson,
            )
            allBlocks.addAll(pageBlocks)
        } catch (e: Exception) {
            // Best-effort — a malformed row shouldn't fail the
            // whole extraction. Log and move on; the rest of the
            // pages still contribute.
            Log.w(TAG, "Couldn't decode blocks_json for ${row.id}: ${e.message}")
        }
    }
    BusinessCardExtractor.extract(allBlocks)
}

/**
 * Fire the system contact-create intent with the supplied fields.
 * Empty fields are simply omitted — Android shows them as empty
 * inputs the user can fill or skip.
 *
 * Multiple phones / emails / websites are packed into the
 * `ContactsContract.Intents.Insert.DATA` extras list so the contact
 * form shows multiple corresponding entries (the inline `PHONE` /
 * `EMAIL` extras only carry the first). Catches launch exceptions
 * with a Toast — the tap is never a silent no-op.
 */
fun launchAddContactIntent(
    context: Context,
    edited: EditableContact,
) {
    val phones   = splitCsv(edited.phones)
    val emails   = splitCsv(edited.emails)
    val websites = splitCsv(edited.websites)

    val intent = Intent(ContactsContract.Intents.Insert.ACTION).apply {
        type = ContactsContract.RawContacts.CONTENT_TYPE

        val displayName = listOf(edited.name, edited.designation)
            .filter { it.isNotBlank() }
            .joinToString(", ")
            .ifBlank { edited.name }
        if (displayName.isNotBlank()) {
            putExtra(ContactsContract.Intents.Insert.NAME, edited.name.ifBlank { displayName })
        }
        if (edited.designation.isNotBlank()) {
            putExtra(ContactsContract.Intents.Insert.JOB_TITLE, edited.designation)
        }
        if (edited.company.isNotBlank()) {
            putExtra(ContactsContract.Intents.Insert.COMPANY, edited.company)
        }
        if (edited.address.isNotBlank()) {
            putExtra(ContactsContract.Intents.Insert.POSTAL, edited.address)
            putExtra(
                ContactsContract.Intents.Insert.POSTAL_TYPE,
                ContactsContract.CommonDataKinds.StructuredPostal.TYPE_WORK,
            )
        }
        if (phones.isNotEmpty()) {
            putExtra(ContactsContract.Intents.Insert.PHONE, phones.first())
            putExtra(
                ContactsContract.Intents.Insert.PHONE_TYPE,
                ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE,
            )
        }
        if (emails.isNotEmpty()) {
            putExtra(ContactsContract.Intents.Insert.EMAIL, emails.first())
            putExtra(
                ContactsContract.Intents.Insert.EMAIL_TYPE,
                ContactsContract.CommonDataKinds.Email.TYPE_WORK,
            )
        }

        // Pack any extras (additional phones/emails/websites) into
        // the DATA ArrayList so the form shows multiple inputs.
        val extras = ArrayList<ContentValues>()
        for (extra in phones.drop(1)) {
            extras += ContentValues().apply {
                put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                put(ContactsContract.CommonDataKinds.Phone.NUMBER, extra)
                put(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
            }
        }
        for (extra in emails.drop(1)) {
            extras += ContentValues().apply {
                put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                put(ContactsContract.CommonDataKinds.Email.ADDRESS, extra)
                put(ContactsContract.CommonDataKinds.Email.TYPE, ContactsContract.CommonDataKinds.Email.TYPE_WORK)
            }
        }
        for (site in websites) {
            extras += ContentValues().apply {
                put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE)
                put(ContactsContract.CommonDataKinds.Website.URL, site)
                put(ContactsContract.CommonDataKinds.Website.TYPE, ContactsContract.CommonDataKinds.Website.TYPE_WORK)
            }
        }
        if (extras.isNotEmpty()) {
            putParcelableArrayListExtra(ContactsContract.Intents.Insert.DATA, extras)
        }
    }

    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't launch contact intent: ${e.message}")
        Toast.makeText(context, "Couldn't open the contact form", Toast.LENGTH_SHORT).show()
    }
}

private val json = Json { ignoreUnknownKeys = true }

private const val TAG = "QuickInkBusinessCard"

private fun splitCsv(s: String): List<String> =
    s.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
