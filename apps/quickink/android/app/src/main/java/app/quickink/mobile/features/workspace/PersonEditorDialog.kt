/*
 * PersonEditorDialog.kt
 *
 * Shared dialog used by both the Home-screen Manage sheet and the
 * scan-detail People picker sheet to create or edit a single
 * `people` row. Supports an optional contact link — tapping "Link
 * contact" launches the system contact picker; on result we cache
 * the contact's display name, phone, email, and photo URI so future
 * renders don't hit the ContactsContract provider.
 */

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.quickink.mobile.features.workspace

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.outlined.ContactPage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.quickink.mobile.QuickInkApp
import app.quickink.mobile.data.person.PersonEntity
import app.quickink.mobile.data.person.PersonRepository
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkRadius
import app.quickink.mobile.ui.theme.QuickInkSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PersonEditorDialog(
    userId: String,
    existing: PersonEntity?,
    onDismiss: () -> Unit,
    onSaved: (PersonEntity) -> Unit,
) {
    val context = LocalContext.current
    val colors  = LocalQuickInkColors.current
    val type    = LocalQuickInkTypography.current
    val app     = remember(context) { context.applicationContext as QuickInkApp }
    val scope   = rememberCoroutineScope()
    val repo    = remember(app) { PersonRepository(app.database.personDao()) }

    var name by remember(existing?.id) { mutableStateOf(existing?.name.orEmpty()) }
    var contactLookupKey by remember(existing?.id) { mutableStateOf(existing?.contactLookupKey) }
    var contactPhone     by remember(existing?.id) { mutableStateOf(existing?.contactPhone) }
    var contactEmail     by remember(existing?.id) { mutableStateOf(existing?.contactEmail) }
    var contactPhotoUri  by remember(existing?.id) { mutableStateOf(existing?.contactPhotoUri) }
    var statusMessage    by remember { mutableStateOf<String?>(null) }
    var pendingPickAfterPermission by remember { mutableStateOf(false) }

    // System contact picker — returns a content:// URI in the form
    // `content://com.android.contacts/contacts/<id>` (or lookup form
    // depending on API). We resolve it once and cache snapshots.
    val contactPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data?.data ?: return@rememberLauncherForActivityResult
        scope.launch {
            val resolved = withContext(Dispatchers.IO) {
                readContact(context.contentResolver, data)
            }
            if (resolved == null) {
                statusMessage = "Couldn't read that contact."
                return@launch
            }
            contactLookupKey = resolved.lookupKey
            contactPhone     = resolved.phone
            contactEmail     = resolved.email
            contactPhotoUri  = resolved.photoUri
            // Pre-fill the name field if the user hasn't typed yet.
            if (name.isBlank() && !resolved.displayName.isNullOrBlank()) {
                name = resolved.displayName
            }
            statusMessage = null
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted && pendingPickAfterPermission) {
            pendingPickAfterPermission = false
            contactPickerLauncher.launch(buildContactPickerIntent())
        } else if (!granted) {
            pendingPickAfterPermission = false
            statusMessage = "Contacts permission denied."
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = colors.surface,
        title = {
            Text(
                text  = if (existing == null) "New person" else "Edit person",
                style = type.heading,
                color = colors.ink,
            )
        },
        text = {
            Column(
                modifier            = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3),
            ) {
                OutlinedTextField(
                    value         = name,
                    onValueChange = { name = it },
                    label         = { Text("Name") },
                    placeholder   = { Text("Me, Mom, Dr. Rao…", color = colors.muted) },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth(),
                    colors        = TextFieldDefaults.colors(
                        focusedContainerColor   = colors.surface,
                        unfocusedContainerColor = colors.surface,
                        focusedIndicatorColor   = colors.accent,
                        unfocusedIndicatorColor = colors.border,
                        cursorColor             = colors.accent,
                    ),
                )

                val hasLink = contactLookupKey != null ||
                              !contactPhone.isNullOrBlank() ||
                              !contactEmail.isNullOrBlank()
                if (hasLink) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(QuickInkRadius.md))
                            .background(colors.accentSoft.copy(alpha = 0.45f))
                            .padding(QuickInkSpacing.s3),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector        = Icons.Outlined.ContactPage,
                                contentDescription = null,
                                tint               = colors.accent,
                                modifier           = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(QuickInkSpacing.s2))
                            Text(
                                text     = "Linked to device contact",
                                style    = type.label.copy(
                                    fontSize   = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                ),
                                color    = colors.accent,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text     = "Unlink",
                                style    = type.label.copy(
                                    fontSize      = 10.5.sp,
                                    fontWeight    = FontWeight.SemiBold,
                                    letterSpacing = 1.sp,
                                ),
                                color    = colors.muted,
                                modifier = Modifier.clickable {
                                    contactLookupKey = null
                                    contactPhone     = null
                                    contactEmail     = null
                                    contactPhotoUri  = null
                                },
                            )
                        }
                        contactPhone?.takeIf { it.isNotBlank() }?.let {
                            ContactDetailRow(icon = Icons.Filled.Phone, text = it)
                        }
                        contactEmail?.takeIf { it.isNotBlank() }?.let {
                            ContactDetailRow(icon = Icons.Filled.Email, text = it)
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(QuickInkRadius.md))
                        .background(colors.borderSoft)
                        .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.md))
                        .clickable {
                            val granted = ContextCompat.checkSelfPermission(
                                context, Manifest.permission.READ_CONTACTS,
                            ) == PackageManager.PERMISSION_GRANTED
                            if (granted) {
                                contactPickerLauncher.launch(buildContactPickerIntent())
                            } else {
                                pendingPickAfterPermission = true
                                permissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                            }
                        }
                        .padding(horizontal = QuickInkSpacing.s3, vertical = QuickInkSpacing.s2),
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector        = Icons.Outlined.ContactPage,
                        contentDescription = null,
                        tint               = colors.inkSoft,
                        modifier           = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(QuickInkSpacing.s2))
                    Text(
                        text  = if (hasLink) "Pick a different contact" else "Link a contact",
                        style = type.label.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                        color = colors.ink,
                    )
                }

                statusMessage?.let { msg ->
                    Text(
                        text  = msg,
                        style = type.meta,
                        color = colors.muted,
                    )
                }
            }
        },
        confirmButton = {
            val enabled = name.trim().isNotEmpty()
            Text(
                text     = "Save",
                style    = type.label.copy(
                    fontSize   = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                color    = if (enabled) colors.accent else colors.muted,
                modifier = Modifier
                    .clickable(enabled = enabled) {
                        val trimmed = name.trim()
                        if (trimmed.isEmpty()) return@clickable
                        scope.launch {
                            val saved: PersonEntity = if (existing == null) {
                                repo.findOrCreate(
                                    userId           = userId,
                                    name             = trimmed,
                                    contactLookupKey = contactLookupKey,
                                    contactPhone     = contactPhone,
                                    contactEmail     = contactEmail,
                                    contactPhotoUri  = contactPhotoUri,
                                )
                            } else {
                                if (trimmed != existing.name) {
                                    repo.rename(existing.id, trimmed)
                                }
                                if (contactLookupKey != existing.contactLookupKey ||
                                    contactPhone     != existing.contactPhone ||
                                    contactEmail     != existing.contactEmail ||
                                    contactPhotoUri  != existing.contactPhotoUri
                                ) {
                                    repo.setContactLink(
                                        id        = existing.id,
                                        lookupKey = contactLookupKey,
                                        phone     = contactPhone,
                                        email     = contactEmail,
                                        photoUri  = contactPhotoUri,
                                    )
                                }
                                existing.copy(
                                    name             = trimmed,
                                    contactLookupKey = contactLookupKey,
                                    contactPhone     = contactPhone,
                                    contactEmail     = contactEmail,
                                    contactPhotoUri  = contactPhotoUri,
                                )
                            }
                            onSaved(saved)
                            onDismiss()
                        }
                    }
                    .padding(QuickInkSpacing.s2),
            )
        },
        dismissButton = {
            Text(
                text     = "Cancel",
                style    = type.label.copy(fontSize = 13.sp),
                color    = colors.ink,
                modifier = Modifier
                    .clickable(onClick = onDismiss)
                    .padding(QuickInkSpacing.s2),
            )
        },
    )
}

@Composable
private fun ContactDetailRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector        = icon,
            contentDescription = null,
            tint               = colors.accent.copy(alpha = 0.7f),
            modifier           = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(QuickInkSpacing.s2))
        Text(
            text  = text,
            style = type.meta.copy(fontSize = 12.sp),
            color = colors.ink,
        )
    }
}

private fun buildContactPickerIntent(): Intent =
    Intent(Intent.ACTION_PICK).apply {
        type = ContactsContract.Contacts.CONTENT_TYPE
    }

private data class ResolvedContact(
    val lookupKey: String,
    val displayName: String?,
    val phone: String?,
    val email: String?,
    val photoUri: String?,
)

/**
 * Resolve a contact-picker result URI into a flat snapshot the
 * editor can persist. Runs on Dispatchers.IO — the ContactsContract
 * ContentResolver hits an on-device SQLite DB which can be slow.
 *
 * Picks the primary phone + primary email (or the first available)
 * to keep the surface flat — the People row only renders one of each.
 */
private fun readContact(resolver: ContentResolver, uri: Uri): ResolvedContact? {
    // Step 1: read the contact row to get lookup_key, display name,
    // and photo URI.
    val contactProjection = arrayOf(
        ContactsContract.Contacts._ID,
        ContactsContract.Contacts.LOOKUP_KEY,
        ContactsContract.Contacts.DISPLAY_NAME,
        ContactsContract.Contacts.PHOTO_URI,
    )
    var contactId: Long?    = null
    var lookupKey: String?  = null
    var displayName: String? = null
    var photoUri: String?   = null
    resolver.query(uri, contactProjection, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            contactId   = cursor.getLong(0)
            lookupKey   = cursor.getString(1)
            displayName = cursor.getString(2)
            photoUri    = cursor.getString(3)
        }
    }
    val id  = contactId ?: return null
    val key = lookupKey ?: return null

    // Step 2: pull the primary phone (or first phone if none flagged).
    val phone = queryFirst(
        resolver,
        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
        projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.IS_PRIMARY,
        ),
        selection  = "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
        selectionArgs = arrayOf(id.toString()),
        primarySortOrder = "${ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY} DESC, " +
                           "${ContactsContract.CommonDataKinds.Phone.IS_PRIMARY} DESC",
        valueIndex = 0,
    )

    // Step 3: same for the primary email.
    val email = queryFirst(
        resolver,
        ContactsContract.CommonDataKinds.Email.CONTENT_URI,
        projection = arrayOf(
            ContactsContract.CommonDataKinds.Email.ADDRESS,
            ContactsContract.CommonDataKinds.Email.IS_SUPER_PRIMARY,
            ContactsContract.CommonDataKinds.Email.IS_PRIMARY,
        ),
        selection  = "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?",
        selectionArgs = arrayOf(id.toString()),
        primarySortOrder = "${ContactsContract.CommonDataKinds.Email.IS_SUPER_PRIMARY} DESC, " +
                           "${ContactsContract.CommonDataKinds.Email.IS_PRIMARY} DESC",
        valueIndex = 0,
    )

    return ResolvedContact(
        lookupKey   = key,
        displayName = displayName,
        phone       = phone,
        email       = email,
        photoUri    = photoUri,
    )
}

private fun queryFirst(
    resolver: ContentResolver,
    uri: Uri,
    projection: Array<String>,
    selection: String,
    selectionArgs: Array<String>,
    primarySortOrder: String,
    valueIndex: Int,
): String? {
    return runCatching {
        resolver.query(uri, projection, selection, selectionArgs, primarySortOrder)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(valueIndex) else null
            }
    }.getOrNull()
}
