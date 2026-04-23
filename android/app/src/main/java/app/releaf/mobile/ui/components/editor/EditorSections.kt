/*
 * EditorSections.kt
 *
 * Reusable feature-section composables for content-editor surfaces —
 * contacts, todos, locations, photos, and document scans. Originally
 * built for the notebook-page editor, now shared with the notepad editor
 * so both surfaces carry identical affordances.
 *
 * Each section is a self-contained `@Composable` that owns its own
 * system integrations (permission requests, IntentSender launches, etc.)
 * and calls back into the caller through a single per-section callback
 * surface. Layout is uniform: every section renders an eyebrow title
 * over a content card. If there are no items yet, the card shows an
 * empty affordance (e.g. "+ Add contact"); once items exist, they appear
 * above that same add affordance. That keeps the editor feeling
 * consistent as the user builds up a page or entry.
 */

package app.releaf.mobile.ui.components.editor

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.ContactsContract
import android.util.Patterns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.net.toFile
import app.releaf.mobile.data.common.AttachmentStorage
import app.releaf.mobile.data.common.CombineToPdfResult
import app.releaf.mobile.data.common.PhotosToPdf
import app.releaf.mobile.data.common.SpeechTranscriber
import app.releaf.mobile.data.common.TranscribeResult
import app.releaf.mobile.data.common.WaveformSamples
import app.releaf.mobile.data.common.Uuidv7
import app.releaf.mobile.data.notebook.Attachment
import app.releaf.mobile.data.notebook.Contact
import app.releaf.mobile.data.notebook.GeoLocation
import app.releaf.mobile.data.notebook.ScanCategory
import app.releaf.mobile.data.notebook.TodoItem
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppAccent
import app.releaf.mobile.ui.theme.AppRadius
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography
import coil.compose.AsyncImage
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// ============================ Shell ============================

/**
 * Uniform section shell. Eyebrow title on top, `content` slot below
 * rendered inside a subtle bordered card. Used by every page-editor
 * section for a consistent rhythm down the screen.
 */
@Composable
private fun SectionShell(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s2),
    ) {
        Text(title, style = AppTypography.Eyebrow, color = AppAccent.primary)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(AppSpacing.s3))
                .background(AppColors.CardSolid)
                .border(
                    width = 1.dp,
                    color = AppColors.BorderDefault,
                    shape = RoundedCornerShape(AppSpacing.s3),
                )
                .padding(AppSpacing.s3),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.s3),
        ) {
            content()
        }
    }
}

/** Tappable "+ add X" row used inside every section. */
@Composable
private fun AddAffordance(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppSpacing.s2))
            .clickable(onClick = onClick)
            .padding(vertical = AppSpacing.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector        = icon,
            contentDescription = null,
            tint               = AppAccent.primary,
            modifier           = Modifier.size(20.dp),
        )
        Spacer(Modifier.size(AppSpacing.s3))
        Text(label, style = AppTypography.Body, color = AppColors.TextPrimary)
        Spacer(Modifier.weight(1f))
        Icon(
            imageVector        = Icons.Filled.Add,
            contentDescription = null,
            tint               = AppAccent.primary,
            modifier           = Modifier.size(20.dp),
        )
    }
}

/**
 * Non-interactive row shown in place of an [AddAffordance] while an async
 * section action is running (e.g. ML Kit's scanner module download before
 * the first scan). Small coral spinner + muted label — no click handler,
 * so users can't re-fire the in-flight task.
 */
@Composable
private fun LoadingRow(label: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AppSpacing.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            color       = AppAccent.primary,
            strokeWidth = 2.dp,
            modifier    = Modifier.size(18.dp),
        )
        Spacer(Modifier.size(AppSpacing.s3))
        Text(label, style = AppTypography.Body, color = AppColors.TextSecondary)
    }
}

/** Small round × button next to a chip / row. */
@Composable
private fun DeleteButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector        = Icons.Filled.Close,
            contentDescription = "Remove",
            tint               = AppColors.TextTertiary,
            modifier           = Modifier.size(16.dp),
        )
    }
}

// ========================== Contacts ==========================

@Composable
fun ContactsSection(
    contacts: List<Contact>,
    /** Full-shape add signature — mirrors the Figma capture form plus
     *  the landline field. The trailing fields are optional; only
     *  `name` is required for the sheet's Save affordance to enable.
     *  Callers wire this to their VM's `addContact(...)` directly. */
    onAdd: (
        name: String,
        phone: String?,
        landline: String?,
        email: String?,
        title: String?,
        organization: String?,
        location: String?,
        website: String?,
    ) -> Unit,
    /** In-place edit: same shape as [onAdd] but keyed by id. Callers
     *  wire this to their VM's `updateContact(...)`. */
    onEdit: (
        id: String,
        name: String,
        phone: String?,
        landline: String?,
        email: String?,
        title: String?,
        organization: String?,
        location: String?,
        website: String?,
    ) -> Unit,
    onRemove: (id: String) -> Unit,
) {
    var isAdding by remember { mutableStateOf(false) }
    // Tap a card → open the contact-actions sheet (Call / Edit / Add-to-
    // Contacts / Email / Delete). Delete from the sheet still routes
    // through the confirmation alert below so the destructive action
    // stays gated.
    var sheetForId by remember { mutableStateOf<String?>(null) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }

    SectionShell(title = "CONTACTS") {
        // Add affordance sits at the top of the section, matching the
        // Photos / Scans pattern so the "how do I add?" affordance is
        // always in the same place.
        AddAffordance(
            icon  = Icons.Filled.People,
            label = "Add contact",
            onClick = { isAdding = true },
        )

        if (contacts.isNotEmpty()) {
            // Full-width cards stacked vertically — phone + email have
            // their own rows with leading icons, so the three captured
            // fields are each legible without truncating.
            Column(
                verticalArrangement = Arrangement.spacedBy(AppSpacing.s2),
            ) {
                contacts.forEach { c ->
                    ContactCard(
                        contact = c,
                        onClick = { sheetForId = c.id },
                    )
                }
            }
        }
    }

    if (isAdding) {
        ContactEditorSheet(
            initial   = null,
            onSubmit  = { name, phone, landline, email, title, organization, location, website ->
                onAdd(name, phone, landline, email, title, organization, location, website)
                isAdding = false
            },
            onDismiss = { isAdding = false },
        )
    }

    val editingContact = editingId?.let { id -> contacts.firstOrNull { it.id == id } }
    if (editingContact != null) {
        ContactEditorSheet(
            initial   = editingContact,
            onSubmit  = { name, phone, landline, email, title, organization, location, website ->
                onEdit(editingContact.id, name, phone, landline, email, title, organization, location, website)
                editingId = null
            },
            onDismiss = { editingId = null },
        )
    }

    val sheetContact = sheetForId?.let { id -> contacts.firstOrNull { it.id == id } }
    if (sheetContact != null) {
        ContactActionsSheet(
            contact  = sheetContact,
            onEdit   = {
                // Close actions sheet, then switch to the edit sheet on
                // the next frame so dismiss + show don't animate over
                // each other.
                editingId = sheetContact.id
                sheetForId = null
            },
            onDelete = {
                // Close the sheet first, then gate the delete behind
                // the existing confirmation alert. Two-step is
                // intentional — the sheet dismiss animation would
                // otherwise race the alert show.
                pendingDeleteId = sheetContact.id
                sheetForId = null
            },
            onDismiss = { sheetForId = null },
        )
    }

    pendingDeleteId?.let { id ->
        val name = contacts.firstOrNull { it.id == id }?.name
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title            = { Text("Remove this contact?") },
            text             = {
                Text(
                    if (name != null) {
                        "\u201C$name\u201D will be removed from this entry. " +
                            "The contact isn't deleted anywhere else."
                    } else {
                        "The contact will be removed from this entry."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onRemove(id)
                    pendingDeleteId = null
                }) {
                    Text("Remove", color = AppColors.Danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) {
                    Text("Cancel", color = AppColors.TextSecondary)
                }
            },
        )
    }
}

/**
 * Unified add/edit sheet for a contact. Passing `initial = null` opens
 * it in "Add contact" mode with empty fields; passing a non-null
 * [Contact] pre-fills every value and swaps the header + commit copy
 * to "Save" on an existing row.
 *
 * Validation (inline, gated on Save):
 *  - Name: trimmed non-empty (required).
 *  - Mobile: optional, but if present must contain exactly 10 digits.
 *  - Landline: same rule as mobile; 10 local digits (e.g. "011 2345
 *    6789" for a Delhi landline) when present.
 *  - Email: optional, but if present must match
 *    `Patterns.EMAIL_ADDRESS`.
 *
 * Invalid fields show an inline supporting-text error and the Save
 * affordance is greyed until every issue clears. On commit, both
 * number fields get the default `+91` dial-code prepended unless the
 * user typed their own `+`-prefixed country code — same behavior as
 * before the landline split.
 *
 * Rendered as a `ModalBottomSheet` to match the other editor sheets;
 * the content scrolls because eight fields + IME padding overflow a
 * short phone screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContactEditorSheet(
    initial: Contact?,
    onSubmit: (
        name: String,
        phone: String?,
        landline: String?,
        email: String?,
        title: String?,
        organization: String?,
        location: String?,
        website: String?,
    ) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    // Pre-fill from `initial` in edit mode. Strip the `+91 ` dial-code
    // prefix so the displayed value matches what the field's `prefix`
    // slot already shows — otherwise users see "+91 +91 98765..." on
    // re-open. Rows that were stored with a non-`+91` country code
    // (e.g. `+1 555...`) or as free-form legacy strings are shown
    // as-captured so we don't silently truncate something the user
    // typed by hand.
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var phone by remember { mutableStateOf(initial?.phone?.stripDefaultDialCode().orEmpty()) }
    var landline by remember { mutableStateOf(initial?.landline?.stripDefaultDialCode().orEmpty()) }
    var email by remember { mutableStateOf(initial?.email.orEmpty()) }
    var title by remember { mutableStateOf(initial?.title.orEmpty()) }
    var organization by remember { mutableStateOf(initial?.organization.orEmpty()) }
    var location by remember { mutableStateOf(initial?.location.orEmpty()) }
    var website by remember { mutableStateOf(initial?.website.orEmpty()) }
    val nameFocus = remember { FocusRequester() }
    // Pop the keyboard straight away in add mode. In edit mode the
    // user probably wants to tap a specific field themselves, so don't
    // steal focus.
    LaunchedEffect(Unit) { if (initial == null) nameFocus.requestFocus() }

    val phoneError    = validatePhoneDigits(phone)
    val landlineError = validatePhoneDigits(landline)
    val emailError    = validateEmail(email)
    val canSubmit = name.trim().isNotEmpty() &&
        phoneError == null &&
        landlineError == null &&
        emailError == null

    val commit: () -> Unit = {
        if (canSubmit) {
            onSubmit(
                name.trim(),
                phone.trim().ifEmpty { null }?.let(::normalizeDialNumber),
                landline.trim().ifEmpty { null }?.let(::normalizeDialNumber),
                email.trim().ifEmpty { null },
                title.trim().ifEmpty { null },
                organization.trim().ifEmpty { null },
                location.trim().ifEmpty { null },
                website.trim().ifEmpty { null },
            )
        }
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = AppAccent.primary,
        cursorColor        = AppAccent.primary,
        focusedLabelColor  = AppAccent.primary,
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        containerColor   = AppColors.Canvas,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(
                    start  = AppSpacing.s4,
                    end    = AppSpacing.s4,
                    top    = AppSpacing.s2,
                    bottom = AppSpacing.s4,
                ),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.s3),
        ) {
            // Title + Save header. Save lives in the top-right like
            // NotesEditorSheet's "Done", so the commit affordance is
            // always in the same place across sheets.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (initial == null) "Add contact" else "Edit contact",
                    style = AppTypography.SectionTitle,
                    color = AppColors.TextPrimary,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "Save",
                    style    = AppTypography.Button,
                    color    = if (canSubmit) AppAccent.primary else AppColors.TextTertiary,
                    modifier = Modifier.clickable(
                        enabled = canSubmit,
                        onClick = commit,
                    ),
                )
            }

            OutlinedTextField(
                value           = name,
                onValueChange   = { name = it },
                label           = { Text("Name") },
                singleLine      = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction      = ImeAction.Next,
                ),
                colors   = fieldColors,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(nameFocus),
            )
            // Mobile sits immediately after Name. The `+91` prefix is
            // displayed as a read-only affordance (not part of the input
            // buffer) so users just tap in the local 10-digit number
            // and the dial-code is added at commit time. Users who need
            // a different country code can type a leading `+`
            // themselves — normalizeDialNumber leaves those untouched.
            OutlinedTextField(
                value           = phone,
                onValueChange   = { phone = it },
                label           = { Text("Mobile") },
                placeholder     = { Text("10-digit number") },
                prefix          = { DialCodePrefix() },
                singleLine      = true,
                isError         = phoneError != null,
                supportingText  = phoneError?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Phone,
                    imeAction    = ImeAction.Next,
                ),
                colors   = fieldColors,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value           = landline,
                onValueChange   = { landline = it },
                label           = { Text("Landline") },
                placeholder     = { Text("10-digit number with STD code") },
                prefix          = { DialCodePrefix() },
                singleLine      = true,
                isError         = landlineError != null,
                supportingText  = landlineError?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Phone,
                    imeAction    = ImeAction.Next,
                ),
                colors   = fieldColors,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value           = title,
                onValueChange   = { title = it },
                label           = { Text("Title / Designation") },
                placeholder     = { Text("Optional") },
                singleLine      = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction      = ImeAction.Next,
                ),
                colors   = fieldColors,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value           = organization,
                onValueChange   = { organization = it },
                label           = { Text("Organization") },
                placeholder     = { Text("Optional") },
                singleLine      = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction      = ImeAction.Next,
                ),
                colors   = fieldColors,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value           = email,
                onValueChange   = { email = it },
                label           = { Text("Email") },
                placeholder     = { Text("name@example.com") },
                singleLine      = true,
                isError         = emailError != null,
                supportingText  = emailError?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction    = ImeAction.Next,
                ),
                colors   = fieldColors,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value           = location,
                onValueChange   = { location = it },
                label           = { Text("Location") },
                placeholder     = { Text("Optional, e.g. San Francisco, CA") },
                singleLine      = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction      = ImeAction.Next,
                ),
                colors   = fieldColors,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value           = website,
                onValueChange   = { website = it },
                label           = { Text("Website") },
                placeholder     = { Text("Optional") },
                singleLine      = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction    = ImeAction.Done,
                ),
                // Done on the last field commits — saves a tap vs.
                // reaching for the Save header.
                keyboardActions = KeyboardActions(onDone = { commit() }),
                colors          = fieldColors,
                modifier        = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun DialCodePrefix() {
    Text(
        text  = "+91 ",
        style = AppTypography.Body,
        color = AppColors.TextSecondary,
    )
}

/**
 * Default-dial-code stripper for pre-filling the edit sheet. Numbers
 * stored with the `+91 ` prefix we add on commit are shown without it
 * (the sheet's read-only prefix slot covers that). Anything else —
 * hand-typed international numbers (`+1 555…`) or free-form legacy
 * strings — passes through as-captured so we don't silently mangle
 * data.
 */
private fun String.stripDefaultDialCode(): String =
    if (startsWith("+91 ")) removePrefix("+91 ")
    else if (startsWith("+91")) removePrefix("+91")
    else this

/** Apply the `+91` default-dial-code contract on commit: a `+`-prefixed
 *  value passes through untouched (the user picked their own country
 *  code); a plain local number gets `+91` prepended. */
private fun normalizeDialNumber(raw: String): String =
    if (raw.startsWith("+")) raw else "+91 $raw"

/**
 * Validation for the mobile and landline fields: the raw entry must
 * contain exactly 10 digits once non-digit separators (spaces, dashes,
 * parens) are stripped. `null` means "valid or empty"; a non-null
 * string is the supporting-text error shown under the field.
 *
 * `+`-prefixed entries bypass the 10-digit rule so users can type any
 * E.164 number — we can't reliably assert a length for every country
 * code, and we still pass the raw text through on commit.
 */
private fun validatePhoneDigits(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    if (trimmed.startsWith("+")) return null
    val digitCount = trimmed.count { it.isDigit() }
    return if (digitCount == 10) null else "Enter a 10-digit number"
}

/** Email validator — uses the platform's `EMAIL_ADDRESS` pattern so the
 *  rules match what Android's autofill expects. Empty string passes
 *  (the field is optional). */
private fun validateEmail(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    return if (Patterns.EMAIL_ADDRESS.matcher(trimmed).matches()) null
    else "Enter a valid email address"
}

/**
 * Full-width contact card. Styled after the published Figma design
 * (cream panel, circular coral avatar with initials, stacked detail
 * rows). Matches the design-system card tokens — `cardSolid` fill +
 * `borderDefault` hairline stroke + `md` (12dp) radius.
 *
 *   [SC]  Sarah Chen
 *         ✉  sarah@releaf.app
 *         ☎  +1 (555) 123-4567
 *
 *  The whole row is tap-target — click opens the `ContactActionsSheet`
 *  with Call / Add-to-Contacts / Send-Email / Delete. No inline delete
 *  button any more; destructive actions live in the sheet to keep the
 *  card glanceable.
 */
@Composable
private fun ContactCard(contact: Contact, onClick: () -> Unit) {
    // Has any of the icon-prefixed detail fields? Drives whether we
    // render the lower block at all — a name-only contact is just the
    // avatar + header row.
    val hasDetails =
        contact.email != null ||
        contact.phone != null ||
        contact.landline != null ||
        contact.location != null ||
        contact.website != null
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.md))
            .background(AppColors.CardSolid)
            .border(
                width = 1.dp,
                color = AppColors.BorderDefault,
                shape = RoundedCornerShape(AppRadius.md),
            )
            .clickable(onClick = onClick)
            .padding(AppSpacing.s4),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s3),
    ) {
        // Header: circular avatar + stacked name / title / organization.
        // Matches the Figma card's first row — avatar flush-left, name
        // as the primary headline with optional title + org lines
        // immediately below it (same horizontal column).
        Row(verticalAlignment = Alignment.CenterVertically) {
            ContactAvatar(name = contact.name)
            Spacer(Modifier.size(AppSpacing.s3))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text     = contact.name,
                    style    = AppTypography.SectionTitle,
                    color    = AppColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                contact.title?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text     = it,
                        style    = AppTypography.Body,
                        color    = AppColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                contact.organization?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text     = it,
                        style    = AppTypography.Meta,
                        color    = AppColors.TextTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // Details list. Order matches the Figma reference: email →
        // phone → location → website. Each row omits itself if the
        // field is null / blank, so a name-only contact shows just
        // the header above.
        if (hasDetails) {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s2)) {
                contact.email?.let {
                    ContactDetailRow(icon = Icons.Filled.Email, text = it)
                }
                contact.phone?.let {
                    // Distinct icons on the mobile vs. landline rows so
                    // users can tell them apart at a glance when both
                    // are present on the card.
                    ContactDetailRow(icon = Icons.Filled.PhoneAndroid, text = it)
                }
                contact.landline?.let {
                    ContactDetailRow(icon = Icons.Filled.Phone, text = it)
                }
                contact.location?.let {
                    ContactDetailRow(icon = Icons.Filled.LocationOn, text = it)
                }
                contact.website?.let {
                    ContactDetailRow(icon = Icons.Filled.Language, text = it)
                }
            }
        }
    }
}

/** Circular avatar with one-or-two-character initials, filled with
 *  `coralDeep` and white on-accent text. Matches the "SC" swatch in
 *  the Figma reference. */
@Composable
private fun ContactAvatar(name: String) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(AppAccent.deep),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text  = initialsFor(name),
            style = AppTypography.Button,
            color = AppColors.OnAccent,
        )
    }
}

/** Two-letter initials for the avatar. One-word name → first two
 *  chars; multi-word → first char of first + last. Always upper-case.
 *  Falls back to "?" for blank strings so the avatar never renders
 *  empty. */
private fun initialsFor(name: String): String {
    val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when {
        words.isEmpty()    -> "?"
        words.size == 1    -> words[0].take(2).uppercase()
        else               -> "${words.first().first()}${words.last().first()}".uppercase()
    }
}

@Composable
private fun ContactDetailRow(icon: ImageVector, text: String) {
    Row(
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2),
    ) {
        Icon(
            imageVector        = icon,
            contentDescription = null,
            tint               = AppColors.TextSecondary,
            modifier           = Modifier.size(16.dp),
        )
        Text(
            text     = text,
            style    = AppTypography.Body,
            color    = AppColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Bottom-sheet menu for a tapped contact card — Call / Add-to-
 *  contacts / Send-email / Delete. Rows for Call and Send-email only
 *  render when the contact actually has that field populated.
 *
 *  Each row dispatches the appropriate Android intent and dismisses
 *  the sheet. Delete is handled by the caller so the existing
 *  confirmation alert can fire. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContactActionsSheet(
    contact: Contact,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val context = LocalContext.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        containerColor   = AppColors.Canvas,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start  = AppSpacing.s4,
                    end    = AppSpacing.s4,
                    top    = AppSpacing.s2,
                    bottom = AppSpacing.s4,
                ),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.s1),
        ) {
            // Header — mirrors the sheet pattern used by the capture
            // form so sheets across the editor feel consistent.
            Row(
                modifier            = Modifier.fillMaxWidth().padding(bottom = AppSpacing.s2),
                verticalAlignment   = Alignment.CenterVertically,
            ) {
                ContactAvatar(name = contact.name)
                Spacer(Modifier.size(AppSpacing.s3))
                Text(
                    text     = contact.name,
                    style    = AppTypography.SectionTitle,
                    color    = AppColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            contact.phone?.takeIf { it.isNotBlank() }?.let { phone ->
                ActionRow(
                    icon    = Icons.Filled.PhoneAndroid,
                    label   = "Call $phone",
                    onClick = {
                        launchPhoneDialer(context, phone)
                        onDismiss()
                    },
                )
            }
            contact.landline?.takeIf { it.isNotBlank() }?.let { landline ->
                ActionRow(
                    icon    = Icons.Filled.Phone,
                    label   = "Call $landline",
                    onClick = {
                        launchPhoneDialer(context, landline)
                        onDismiss()
                    },
                )
            }
            contact.email?.takeIf { it.isNotBlank() }?.let { email ->
                ActionRow(
                    icon    = Icons.Filled.Email,
                    label   = "Send email",
                    onClick = {
                        launchEmailClient(context, email)
                        onDismiss()
                    },
                )
            }
            contact.website?.takeIf { it.isNotBlank() }?.let { website ->
                ActionRow(
                    icon    = Icons.Filled.Language,
                    label   = "Open website",
                    onClick = {
                        launchWebsite(context, website)
                        onDismiss()
                    },
                )
            }
            ActionRow(
                icon    = Icons.Filled.Edit,
                label   = "Edit",
                onClick = onEdit,
            )
            ActionRow(
                icon    = Icons.Filled.PersonAdd,
                label   = "Add to contacts",
                onClick = {
                    launchAddToContacts(context, contact)
                    onDismiss()
                },
            )
            ActionRow(
                icon    = Icons.Filled.DeleteOutline,
                label   = "Delete",
                tint    = AppColors.Danger,
                onClick = onDelete,
            )
        }
    }
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    label: String,
    tint: Color = AppColors.TextPrimary,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.sm))
            .clickable(onClick = onClick)
            .padding(vertical = AppSpacing.s3, horizontal = AppSpacing.s2),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s3),
    ) {
        Icon(
            imageVector        = icon,
            contentDescription = null,
            tint               = tint,
            modifier           = Modifier.size(20.dp),
        )
        Text(
            text  = label,
            style = AppTypography.Body,
            color = tint,
        )
    }
}

// ---------- Contact-action intents ----------
//
// Launched from `ContactActionsSheet`. All three are best-effort —
// we surface a Toast rather than a crash if the user has no handler
// for the target scheme (e.g. no phone-dial app on a Wi-Fi-only
// tablet). Android's chooser takes care of ambiguous resolutions
// (multiple email clients, etc.) so no extra disambiguation UX is
// needed here.

private fun launchPhoneDialer(context: Context, phone: String) {
    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(phone)}"))
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No dialer app available", Toast.LENGTH_SHORT).show()
    }
}

private fun launchEmailClient(context: Context, email: String) {
    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${Uri.encode(email)}"))
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No email app available", Toast.LENGTH_SHORT).show()
    }
}

/** Open the contact's URL in the user's default browser. Auto-prefixes
 *  `https://` when the user stored a scheme-less value (e.g.
 *  `sarahchen.design`) so the intent doesn't get rejected. */
private fun launchWebsite(context: Context, url: String) {
    val normalised = if (url.startsWith("http://") || url.startsWith("https://")) {
        url
    } else {
        "https://$url"
    }
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(normalised))
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No browser available", Toast.LENGTH_SHORT).show()
    }
}

/** Drops into the OS Contacts app's "new contact" form pre-filled
 *  with whatever fields we have. The user confirms the save there —
 *  we don't write to the system contacts DB ourselves.
 *
 *  `title` + `organization` map to ContactsContract's JOB_TITLE and
 *  COMPANY extras. `location` goes in POSTAL — it's free-form, but
 *  the Contacts app just drops it into the Address field for the user
 *  to clean up. `website` has no matching Insert extra in the stable
 *  SDK, so it's omitted from the handoff (user can paste manually). */
private fun launchAddToContacts(context: Context, contact: Contact) {
    val intent = Intent(Intent.ACTION_INSERT).apply {
        type = ContactsContract.Contacts.CONTENT_TYPE
        putExtra(ContactsContract.Intents.Insert.NAME, contact.name)
        contact.phone?.takeIf { it.isNotBlank() }?.let {
            // Primary number → MOBILE type so the Contacts app tags it
            // correctly after the user confirms the insert.
            putExtra(ContactsContract.Intents.Insert.PHONE, it)
            putExtra(
                ContactsContract.Intents.Insert.PHONE_TYPE,
                ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE,
            )
        }
        contact.landline?.takeIf { it.isNotBlank() }?.let {
            // Secondary number slot. Intents.Insert exposes two extra
            // phone channels; use the first (SECONDARY_PHONE) and tag
            // it as a WORK line — the Contacts app lets the user
            // re-label on confirm if the implied type doesn't fit.
            putExtra(ContactsContract.Intents.Insert.SECONDARY_PHONE, it)
            putExtra(
                ContactsContract.Intents.Insert.SECONDARY_PHONE_TYPE,
                ContactsContract.CommonDataKinds.Phone.TYPE_WORK,
            )
        }
        contact.email?.takeIf { it.isNotBlank() }?.let {
            putExtra(ContactsContract.Intents.Insert.EMAIL, it)
        }
        contact.title?.takeIf { it.isNotBlank() }?.let {
            putExtra(ContactsContract.Intents.Insert.JOB_TITLE, it)
        }
        contact.organization?.takeIf { it.isNotBlank() }?.let {
            putExtra(ContactsContract.Intents.Insert.COMPANY, it)
        }
        contact.location?.takeIf { it.isNotBlank() }?.let {
            putExtra(ContactsContract.Intents.Insert.POSTAL, it)
        }
    }
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No contacts app available", Toast.LENGTH_SHORT).show()
    }
}

// =========================== Todos ============================

@Composable
fun TodosSection(
    todos: List<TodoItem>,
    onAdd: (text: String) -> Unit,
    onToggle: (id: String) -> Unit,
    onRemove: (id: String) -> Unit,
    onUpdatePriority: (id: String, priority: Int) -> Unit = { _, _ -> },
    onReorder: (newList: List<TodoItem>) -> Unit = {},
) {
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    val done = todos.count { it.done }
    val total = todos.size
    val percent = if (total == 0) 0 else (done * 100) / total

    // Drag-to-reorder state. `draggingId` pins the floating row to the
    // user's finger; `dragDy` is the accumulated vertical pointer
    // delta. Swap threshold is half the approximate row height —
    // rows aren't perfectly uniform but they're close enough that a
    // 28dp threshold reads as a crisp "slot change" rather than a
    // sluggish drag.
    val density = LocalDensity.current
    val rowStepPx = with(density) { 56.dp.toPx() }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragDy by remember { mutableStateOf(0f) }
    val latestTodos by rememberUpdatedState(todos)
    val latestReorder by rememberUpdatedState(onReorder)

    SectionShell(title = "TODOS") {
        // Progress header + bar. Shown only when there's at least one
        // todo so a fresh section doesn't sit half-empty.
        if (total > 0) {
            TodoProgressBar(done = done, total = total, percent = percent)
        }

        // Add-new input pinned at the top. Different visual shape from
        // the other sections' InlineTextInput — this one's the primary
        // CTA of the whole section, so it gets the filled coral + chip.
        TodoAddRow(onSubmit = onAdd)

        todos.forEach { t ->
            val isDragging = draggingId == t.id
            val yOffset = if (isDragging) dragDy else 0f
            val dragHandle = Modifier.pointerInput(t.id) {
                detectDragGestures(
                    onDragStart = {
                        draggingId = t.id
                        dragDy = 0f
                    },
                    onDragEnd = {
                        draggingId = null
                        dragDy = 0f
                    },
                    onDragCancel = {
                        draggingId = null
                        dragDy = 0f
                    },
                ) { change, dragAmount ->
                    change.consume()
                    dragDy += dragAmount.y
                    // Swap with a neighbour once we cross half the row
                    // height. Re-anchoring dragDy keeps the motion
                    // continuous across the swap.
                    val threshold = rowStepPx / 2f
                    val currentIdx = latestTodos.indexOfFirst { it.id == t.id }
                    if (currentIdx < 0) return@detectDragGestures
                    if (dragDy > threshold && currentIdx < latestTodos.size - 1) {
                        val newList = latestTodos.toMutableList().apply {
                            add(currentIdx + 1, removeAt(currentIdx))
                        }
                        latestReorder(newList)
                        dragDy -= rowStepPx
                    } else if (dragDy < -threshold && currentIdx > 0) {
                        val newList = latestTodos.toMutableList().apply {
                            add(currentIdx - 1, removeAt(currentIdx))
                        }
                        latestReorder(newList)
                        dragDy += rowStepPx
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        translationY = yOffset
                        // Float the dragging row above its siblings
                        // so rounded avatars / icons don't clip.
                        shadowElevation = if (isDragging) 8f else 0f
                    }
                    .zIndex(if (isDragging) 1f else 0f),
            ) {
                TodoRow(
                    todo               = t,
                    onToggle           = { onToggle(t.id) },
                    onRemove           = { pendingDeleteId = t.id },
                    onSetPriority      = { level -> onUpdatePriority(t.id, level) },
                    dragHandleModifier = dragHandle,
                )
            }
        }
    }

    pendingDeleteId?.let { id ->
        val snippet = todos.firstOrNull { it.id == id }?.text
            ?.take(60)
            ?.let { if (it.length == 60) "$it…" else it }
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title            = { Text("Delete this todo?") },
            text             = {
                Text(
                    if (snippet != null) "\u201C$snippet\u201D will be removed."
                    else "The todo will be removed.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onRemove(id)
                    pendingDeleteId = null
                }) {
                    Text("Delete", color = AppColors.Danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) {
                    Text("Cancel", color = AppColors.TextSecondary)
                }
            },
        )
    }
}

@Composable
private fun TodoRow(
    todo: TodoItem,
    onToggle: () -> Unit,
    onRemove: () -> Unit,
    onSetPriority: (level: Int) -> Unit,
    dragHandleModifier: Modifier = Modifier,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AppSpacing.s1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 6-dot grip — tap-and-drag vertically to reorder. Wrapped in
        // a 40dp square so the gesture has room to catch.
        Box(
            modifier = Modifier
                .size(40.dp)
                .then(dragHandleModifier),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector        = Icons.Filled.DragIndicator,
                contentDescription = "Reorder",
                tint               = AppColors.TextTertiary,
                modifier           = Modifier.size(20.dp),
            )
        }
        TodoCheckbox(done = todo.done, onClick = onToggle)
        Spacer(Modifier.size(AppSpacing.s3))
        Text(
            text     = todo.text,
            style    = AppTypography.Body.copy(
                textDecoration = if (todo.done) TextDecoration.LineThrough else null,
            ),
            color    = if (todo.done) AppColors.TextTertiary else AppColors.TextPrimary,
            modifier = Modifier.weight(1f),
        )
        PriorityDots(level = todo.priority, onSet = onSetPriority)
        Spacer(Modifier.size(AppSpacing.s2))
        // 40dp tap target so the delete hit-box is comfortable. Icon
        // itself stays at 20dp but the clickable Box is larger.
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector        = Icons.Filled.DeleteOutline,
                contentDescription = "Delete todo",
                tint               = AppColors.TextTertiary,
                modifier           = Modifier.size(20.dp),
            )
        }
    }
}

/** Rounded-square checkbox — coral-filled with a white check when
 *  `done`, hollow with a thin border + faint cream fill when not. */
@Composable
private fun TodoCheckbox(done: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(AppSpacing.s1)
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(shape)
            .background(if (done) AppAccent.primary else AppColors.Subtle)
            .border(
                width = 1.dp,
                color = if (done) AppAccent.primary else AppColors.BorderDefault,
                shape = shape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (done) {
            Icon(
                imageVector        = Icons.Filled.Check,
                contentDescription = "Done",
                tint               = AppColors.OnAccent,
                modifier           = Modifier.size(16.dp),
            )
        }
    }
}

/** Three-dot priority picker. Left = high, middle = medium, right = low.
 *  Tapping a dot sets that level; tapping the currently-active dot clears
 *  priority back to none. Colors are the project's traffic-light trio:
 *  high = #C65A3E (coral deep), medium = #E8B923 (yellow), low = #5B8C52 (green). */
@Composable
private fun PriorityDots(level: Int, onSet: (Int) -> Unit) {
    val high   = Color(0xFFC65A3E)
    val medium = Color(0xFFE8B923)
    val low    = Color(0xFF5B8C52)

    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment     = Alignment.CenterVertically,
    ) {
        PriorityDot(active = level == 3, color = high,   onClick = {
            onSet(if (level == 3) 0 else 3)
        })
        PriorityDot(active = level == 2, color = medium, onClick = {
            onSet(if (level == 2) 0 else 2)
        })
        PriorityDot(active = level == 1, color = low,    onClick = {
            onSet(if (level == 1) 0 else 1)
        })
    }
}

@Composable
private fun PriorityDot(active: Boolean, color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(14.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(if (active) 8.dp else 6.dp)
                .clip(CircleShape)
                .background(if (active) color else AppColors.BorderDefault),
        )
    }
}

/** Progress header: "N of M completed" (left) / "NN%" (right) + a
 *  filled coral bar underneath. Uses a plain `drawBehind` on a Box so
 *  the bar sits flush with the row's corner rounding. */
@Composable
private fun TodoProgressBar(done: Int, total: Int, percent: Int) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s2),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text  = "$done of $total completed",
                style = AppTypography.Meta,
                color = AppColors.TextSecondary,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text  = "${percent}%",
                style = AppTypography.Meta,
                color = AppColors.TextSecondary,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(AppRadius.pill))
                .background(AppColors.Subtle),
        ) {
            // Fraction as layout weight gives us a simple progress
            // bar without pulling in `LinearProgressIndicator` (which
            // carries a fixed height + its own padding).
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction = percent / 100f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(AppRadius.pill))
                    .background(AppAccent.primary),
            )
        }
    }
}

/** Top-of-section "Add a new task…" input. Large rounded rect with
 *  the text field on the left and a filled coral + button on the
 *  right. IME Done and the + button both commit. */
@Composable
private fun TodoAddRow(onSubmit: (String) -> Unit) {
    var value by remember { mutableStateOf("") }
    val commit: () -> Unit = {
        val trimmed = value.trim()
        if (trimmed.isNotEmpty()) {
            onSubmit(trimmed)
            value = ""
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AppSpacing.s1),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2),
    ) {
        // No pill container — just a plain underline-less text field
        // with the placeholder floating at the caret. Matches the
        // plaintext feel of the rest of the editor.
        Box(
            modifier         = Modifier.weight(1f),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (value.isEmpty()) {
                Text(
                    text  = "Add a new todo…",
                    style = AppTypography.Body,
                    color = AppColors.TextTertiary,
                )
            }
            BasicTextField(
                value         = value,
                onValueChange = { value = it },
                singleLine    = true,
                textStyle     = AppTypography.Body.copy(color = AppColors.TextPrimary),
                cursorBrush   = SolidColor(AppAccent.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { commit() }),
                modifier      = Modifier.fillMaxWidth(),
            )
        }
        // Filled coral + button — stays as a solid rounded chip on the
        // trailing edge; dims to 40% while the field is blank.
        val enabled = value.isNotBlank()
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(AppRadius.md))
                .background(
                    if (enabled) AppAccent.primary
                    else AppAccent.primary.copy(alpha = 0.4f),
                )
                .clickable(enabled = enabled, onClick = commit),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector        = Icons.Filled.Add,
                contentDescription = "Add todo",
                tint               = AppColors.OnAccent,
                modifier           = Modifier.size(18.dp),
            )
        }
    }
}

// ========================= Location ==========================

@Composable
fun LocationSection(
    locations: List<GeoLocation>,
    /** Adds a row with the given coords + address and returns the
     *  row's new uuidv7. The id is handed to [onUpdateCoords] later
     *  when the precise GPS fix arrives. */
    onAdd: (lat: Double, lng: Double, address: String?) -> String,
    /** Patches the coordinates on a previously-added row — the
     *  "refine in background" half of the two-stage capture flow. */
    onUpdateCoords: (id: String, lat: Double, lng: Double) -> Unit,
    onRemove: (id: String) -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity

    // Two-stage capture:
    //   1. Fast — `client.lastLocation` returns the most recent cached
    //      fix in ~10ms when any app has used location recently. We
    //      reverse-geocode that, add the row, and the user sees an
    //      address immediately.
    //   2. Refine (background) — `getCurrentLocation` fetches a fresh
    //      fix (1-10s on GPS cold start). When it arrives we patch
    //      the row's coordinates via `onUpdateCoords`; the address
    //      label stays as whatever the fast-fix geocoded to, which
    //      is almost always the same street / POI.
    //
    // If `lastLocation` returns null (no app has used GPS recently),
    // we fall back to the slow path only — the row still gets added,
    // just after the user's waited for a live fix.
    val fetchLocation: () -> Unit = fetch@{
        if (activity == null) return@fetch
        val client = LocationServices.getFusedLocationProviderClient(activity)

        client.lastLocation.addOnSuccessListener { cached ->
            if (cached != null) {
                resolveAddress(context, cached.latitude, cached.longitude) { address ->
                    val newId = onAdd(cached.latitude, cached.longitude, address)
                    refineLocationCoords(client, newId, onUpdateCoords)
                }
            } else {
                // No cached location — run the slow path only.
                fetchPreciseAndAdd(context, client, onAdd)
            }
        }.addOnFailureListener {
            // `lastLocation` rarely fails (it's a local read) but if
            // it does, fall through to the slow path rather than
            // leaving the user with no feedback.
            fetchPreciseAndAdd(context, client, onAdd)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            results[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            fetchLocation()
        } else {
            Toast.makeText(context, "Location permission is needed to capture GPS.", Toast.LENGTH_SHORT).show()
        }
    }

    val onUseCurrent: () -> Unit = {
        val hasFine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (hasFine || hasCoarse) {
            fetchLocation()
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                )
            )
        }
    }

    var pendingDeleteId by remember { mutableStateOf<String?>(null) }

    SectionShell(title = "LOCATION") {
        // Affordance on top.
        AddAffordance(
            icon    = Icons.Filled.LocationOn,
            label   = "Use current location",
            onClick = onUseCurrent,
        )

        // Saved location rows below. × routes through the delete gate.
        locations.forEach { loc ->
            LocationRow(loc, onRemove = { pendingDeleteId = loc.id })
        }
    }

    pendingDeleteId?.let { id ->
        val target = locations.firstOrNull { it.id == id }
        // Only surface the address in the confirmation copy — never
        // raw coordinates. Drops to the generic copy when there's no
        // resolved address (same rationale as LocationRow's
        // placeholder above).
        val primary = target?.address?.takeIf { it.isNotBlank() }
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title            = { Text("Remove this location?") },
            text             = {
                Text(
                    if (primary != null) "$primary will be removed from this entry."
                    else "The location will be removed from this entry.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onRemove(id)
                    pendingDeleteId = null
                }) {
                    Text("Remove", color = AppColors.Danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) {
                    Text("Cancel", color = AppColors.TextSecondary)
                }
            },
        )
    }
}

@Composable
private fun LocationRow(location: GeoLocation, onRemove: () -> Unit) {
    val context = LocalContext.current
    // Coordinates are persisted on the row (and drive the tap-to-Maps
    // intent below) but we never put raw lat/lng in the UI — the row
    // shows the reverse-geocoded address, or a generic placeholder
    // when the geocoder had no match for that point.
    val primary = location.address?.takeIf { it.isNotBlank() } ?: "Saved location"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { openLocationInMaps(context, location) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector        = Icons.Filled.LocationOn,
            contentDescription = null,
            tint               = AppAccent.primary,
            modifier           = Modifier.size(18.dp),
        )
        Spacer(Modifier.size(AppSpacing.s3))
        Text(
            text     = primary,
            style    = AppTypography.Body,
            color    = AppColors.TextPrimary,
            modifier = Modifier.weight(1f),
        )
        // Delete button consumes its own click via its internal
        // `clickable`, so tapping the × won't also fire the row-level
        // "open in Maps" handler above.
        DeleteButton(onClick = onRemove)
    }
}

/**
 * Launch the saved location in a map app. Tries a `geo:` intent first
 * — Android's chooser resolves that to whatever map app the user has
 * set as default (Google Maps on most phones, but also respects
 * Maps.me / OsmAnd / etc. if they're installed). Falls back to the
 * Google Maps web URL in a browser if nothing handles `geo:`.
 *
 * The `q=lat,lng(label)` query string pins a marker at the exact
 * coordinates with the reverse-geocoded address as the label, so the
 * user opens into a recognisable pin rather than a bare lat/lng dot.
 */
private fun openLocationInMaps(context: Context, location: GeoLocation) {
    val label = location.address ?: "Saved location"
    val geoUri = Uri.parse(
        "geo:${location.lat},${location.lng}" +
            "?q=${location.lat},${location.lng}(${Uri.encode(label)})"
    )
    val intent = Intent(Intent.ACTION_VIEW, geoUri)
    try {
        context.startActivity(intent)
        return
    } catch (_: ActivityNotFoundException) {
        // No map app — fall through to web fallback below.
    }

    // Browser fallback — every device can load this URL.
    val webUri = Uri.parse(
        "https://www.google.com/maps/search/?api=1&query=${location.lat},${location.lng}"
    )
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, webUri))
    } catch (_: Exception) {
        Toast.makeText(
            context,
            "No app available to open this location.",
            Toast.LENGTH_SHORT,
        ).show()
    }
}

/**
 * Reverse-geocode to a human-readable address. Uses the async API on
 * Android 13+ (required; the sync one is deprecated and throttled), the
 * sync API otherwise. Any failure silently resolves to null — the
 * fallback is to show the generic "Saved location" placeholder, never
 * raw coordinates.
 */
private fun resolveAddress(
    context: android.content.Context,
    lat: Double,
    lng: Double,
    onResolved: (String?) -> Unit,
) {
    val geocoder = Geocoder(context, Locale.getDefault())
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            geocoder.getFromLocation(lat, lng, 1) { addresses ->
                onResolved(addresses.firstOrNull()?.getAddressLine(0))
            }
        } else {
            @Suppress("DEPRECATION")
            val addresses = geocoder.getFromLocation(lat, lng, 1)
            onResolved(addresses?.firstOrNull()?.getAddressLine(0))
        }
    } catch (_: Exception) {
        onResolved(null)
    }
}

/**
 * Slow-path location capture: run `getCurrentLocation`, reverse-geocode,
 * then add a single row with the precise coordinates. Used when the
 * fast-path `lastLocation` came back null (no recent fix cached on
 * the device), and also as the refine path — but in the refine case
 * the row is already on screen, so we go through `refineLocationCoords`
 * instead of this function.
 */
private fun fetchPreciseAndAdd(
    context: android.content.Context,
    client: com.google.android.gms.location.FusedLocationProviderClient,
    onAdd: (Double, Double, String?) -> String,
) {
    client.getCurrentLocation(
        Priority.PRIORITY_BALANCED_POWER_ACCURACY,
        CancellationTokenSource().token,
    ).addOnSuccessListener { location ->
        if (location == null) {
            Toast.makeText(
                context,
                "Couldn't read GPS — try again outdoors.",
                Toast.LENGTH_SHORT,
            ).show()
            return@addOnSuccessListener
        }
        resolveAddress(context, location.latitude, location.longitude) { address ->
            onAdd(location.latitude, location.longitude, address)
        }
    }.addOnFailureListener {
        Toast.makeText(context, "Location unavailable.", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Background refinement pass: kicks off a precise `getCurrentLocation`
 * fetch for a row that's already on screen (added from the cached
 * `lastLocation`), and patches in the accurate coordinates once the
 * fresh fix arrives. Silent on failure — the fast-path coords that
 * are already saved are usually within tens of meters anyway.
 */
private fun refineLocationCoords(
    client: com.google.android.gms.location.FusedLocationProviderClient,
    id: String,
    onUpdateCoords: (String, Double, Double) -> Unit,
) {
    client.getCurrentLocation(
        Priority.PRIORITY_BALANCED_POWER_ACCURACY,
        CancellationTokenSource().token,
    ).addOnSuccessListener { precise ->
        if (precise != null) {
            onUpdateCoords(id, precise.latitude, precise.longitude)
        }
    }
    // No failure handler — if the precise fetch errors out, the user
    // already has a usable row from the fast path.
}

// ========================== Photos ===========================

@Composable
fun PhotosSection(
    photos: List<Attachment>,
    onAdd: (uri: String) -> Unit,
    onRemove: (id: String) -> Unit,
    /** Called once the "Combine to PDF" flow has written a PDF into
     *  app storage. Callers route to `viewModel.addAttachment(TYPE_SCAN,
     *  pdfUri, previewUri)` so the combined document lands in the
     *  Scans section under the GENERAL category. */
    onCombineToPdf: (pdfUri: String, previewUri: String?) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    var showChooser by remember { mutableStateOf(false) }

    // Camera capture: we pre-create a destination file in our attachments
    // dir and hand the camera app a FileProvider URI pointing at it. On
    // success we read back the bytes at that file and store a file:// URI
    // so cleanup on remove is symmetric with scans.
    var pendingCameraFile by remember { mutableStateOf<File?>(null) }

    val takePicture = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val file = pendingCameraFile
        pendingCameraFile = null
        if (success && file != null && file.exists() && file.length() > 0L) {
            onAdd(Uri.fromFile(file).toString())
        } else {
            // Delete the zero-byte file the camera app may have created
            // before the user cancelled — keeps the attachments dir tidy.
            file?.takeIf { it.exists() && it.length() == 0L }?.delete()
        }
    }

    val pickMedia = rememberLauncherForActivityResult(
        // Multi-select photo picker. User can tap several images in
        // the sheet and we import each one — the camera path stays
        // single-shot since capturing is inherently one-at-a-time.
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris: List<Uri> ->
        for (uri in uris) {
            // Persist so the URI still resolves after app restart.
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            onAdd(uri.toString())
        }
    }

    val launchCamera: () -> Unit = {
        val file = File(
            AttachmentStorage.directory(context),
            "${Uuidv7.generate()}.jpg",
        )
        val uri = runCatching {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
        }.getOrNull()
        if (uri != null) {
            pendingCameraFile = file
            runCatching { takePicture.launch(uri) }.onFailure {
                pendingCameraFile = null
                file.delete()
                Toast.makeText(
                    context,
                    "No camera available on this device.",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    val launchGallery: () -> Unit = {
        pickMedia.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
        )
    }

    // Confirmation gate — tapping a tile's × opens the dialog; the
    // actual removal fires only if the user confirms.
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }

    // Which photo's fullscreen viewer is open. Null = closed. The
    // viewer pager owns navigation between photos once open, so we
    // only need to track the tapped entry index here.
    var viewerIndex by remember { mutableStateOf<Int?>(null) }

    // Multi-select state for the "Combine to PDF" flow. `selectMode`
    // gates the tile appearance (checkmark overlay vs close button);
    // `selectedIds` tracks which photos are picked. Reset on exit.
    var selectMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isCombining by remember { mutableStateOf(false) }
    val combineScope = rememberCoroutineScope()

    SectionShell(title = "PHOTOS") {
        // In select mode the add affordance steps aside for the
        // select-mode header. Same vertical real estate, clearer
        // which mode the user is in.
        if (!selectMode) {
            AddAffordance(
                icon    = Icons.Filled.PhotoCamera,
                label   = "Add photo",
                onClick = { showChooser = true },
            )
            if (photos.size >= 2) {
                // "Combine to PDF" is only useful with 2+ photos —
                // single-photo PDFs aren't worth the extra surface.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            selectMode = true
                            selectedIds = emptySet()
                        }
                        .padding(vertical = AppSpacing.s1),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector        = Icons.AutoMirrored.Filled.InsertDriveFile,
                        contentDescription = null,
                        tint               = AppAccent.primary,
                        modifier           = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(AppSpacing.s2))
                    Text(
                        text  = "Combine to PDF",
                        style = AppTypography.Button,
                        color = AppAccent.primary,
                    )
                }
            }
        } else {
            SelectModeBar(
                count       = selectedIds.size,
                isWorking   = isCombining,
                onCancel    = {
                    selectMode = false
                    selectedIds = emptySet()
                },
                onConfirm   = {
                    // Resolve the ordered URIs from the selected set so
                    // the PDF page order matches what the user saw in
                    // the grid, not insertion order of the Set.
                    val uris = photos
                        .filter { it.id in selectedIds }
                        .mapNotNull { runCatching { Uri.parse(it.uri) }.getOrNull() }
                    if (uris.isEmpty()) return@SelectModeBar
                    isCombining = true
                    combineScope.launch {
                        val result = PhotosToPdf.combine(context, uris)
                        isCombining = false
                        when (result) {
                            is CombineToPdfResult.Success -> {
                                onCombineToPdf(
                                    result.pdfUri.toString(),
                                    result.previewUri?.toString(),
                                )
                                selectMode = false
                                selectedIds = emptySet()
                                Toast.makeText(
                                    context,
                                    "Added to Scan documents",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                            is CombineToPdfResult.Failed -> {
                                Toast.makeText(
                                    context,
                                    "Couldn't combine photos: ${result.cause.message ?: "unknown error"}",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                    }
                },
            )
        }

        if (photos.isNotEmpty()) {
            AttachmentGrid(
                attachments     = photos,
                onRemoveRequest = { id -> pendingDeleteId = id },
                onTap           = { index -> viewerIndex = index },
                selectMode      = selectMode,
                selectedIds     = selectedIds,
                onToggleSelect  = { id ->
                    selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
                },
                placeholder     = {
                    Icon(Icons.Filled.PhotoCamera, null, tint = AppColors.TextTertiary)
                },
            )
        }
    }

    viewerIndex?.let { index ->
        PhotoViewerDialog(
            photos       = photos,
            initialIndex = index,
            onDismiss    = { viewerIndex = null },
        )
    }

    if (showChooser) {
        PhotoSourceChooser(
            onTakePhoto         = {
                showChooser = false
                launchCamera()
            },
            onChooseFromGallery = {
                showChooser = false
                launchGallery()
            },
            onDismiss           = { showChooser = false },
        )
    }

    pendingDeleteId?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title            = { Text("Delete this photo?") },
            text             = {
                Text(
                    "It'll be removed from this entry and the file in app " +
                        "storage is cleaned up.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onRemove(id)
                    pendingDeleteId = null
                }) {
                    Text("Delete", color = AppColors.Danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) {
                    Text("Cancel", color = AppColors.TextSecondary)
                }
            },
        )
    }
}

/**
 * Multi-select bar shown while the user is picking photos to combine
 * into a PDF. Exits the mode via Cancel; fires the combine pipeline
 * via "Create PDF (N)" when there's at least one selection. Disabled
 * look while the IO is in flight so the user doesn't double-fire.
 */
@Composable
private fun SelectModeBar(
    count: Int,
    isWorking: Boolean,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AppSpacing.s1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text  = if (count == 0) "Select photos to combine"
                    else if (count == 1) "1 selected"
                    else "$count selected",
            style = AppTypography.Button,
            color = AppColors.TextPrimary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text     = "Cancel",
            style    = AppTypography.Button,
            color    = AppColors.TextSecondary,
            modifier = Modifier
                .clickable(enabled = !isWorking, onClick = onCancel)
                .padding(horizontal = AppSpacing.s2, vertical = AppSpacing.s1),
        )
        Spacer(Modifier.size(AppSpacing.s2))
        val enabled = count >= 1 && !isWorking
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(AppRadius.md))
                .background(
                    if (enabled) AppAccent.primary
                    else AppAccent.primary.copy(alpha = 0.4f),
                )
                .clickable(enabled = enabled, onClick = onConfirm)
                .padding(horizontal = AppSpacing.s3, vertical = AppSpacing.s2),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text  = if (isWorking) "Creating…" else "Create PDF",
                style = AppTypography.Button,
                color = AppColors.OnAccent,
            )
        }
    }
}

/**
 * Bottom-sheet chooser surfaced when the user taps "+ Add photo". Two
 * options: system camera (via ActivityResultContracts.TakePicture) or
 * the Photo Picker (PickVisualMedia). Keeps the editor neutral about
 * which path a given photo came from — both end up on the attachment
 * list the same way.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhotoSourceChooser(
    onTakePhoto: () -> Unit,
    onChooseFromGallery: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        containerColor   = AppColors.CardSolid,
    ) {
        Column(modifier = Modifier.padding(bottom = AppSpacing.s4)) {
            ChooserRow(
                icon    = Icons.Filled.PhotoCamera,
                label   = "Take photo",
                onClick = onTakePhoto,
            )
            ChooserRow(
                icon    = Icons.Filled.PhotoLibrary,
                label   = "Choose from gallery",
                onClick = onChooseFromGallery,
            )
        }
    }
}

@Composable
private fun ChooserRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = AppSpacing.s4, vertical = AppSpacing.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector        = icon,
            contentDescription = null,
            tint               = AppAccent.primary,
            modifier           = Modifier.size(24.dp),
        )
        Spacer(Modifier.size(AppSpacing.s3))
        Text(label, style = AppTypography.Body, color = AppColors.TextPrimary)
    }
}

// =========================== Scans ===========================

@Composable
fun ScansSection(
    scans: List<Attachment>,
    onAdd: (primaryUri: String, previewUri: String?, pageUrisForOcr: List<Uri>) -> Unit,
    onRemove: (id: String) -> Unit,
    /** Fires when the user taps "Import this page to notes" in the
     *  in-house PDF viewer. The URI points at a JPG the viewer
     *  already wrote into `AttachmentStorage.directory()`; callers
     *  hand it to `viewModel.addSubPageFromImage(uri)`. */
    onImportPageToNotes: (pageImageUri: String) -> Unit = {},
    /** Fires when the user saves the Edit-scan dialog. Title is
     *  null-to-clear (falls back to OCR-derived); categoryId is
     *  `ScanCategory.name` or null to fall back to the derived
     *  classification. */
    onEditScan: (id: String, title: String?, categoryId: String?) -> Unit = { _, _, _ -> },
) {
    val context = LocalContext.current
    val activity = context as? Activity

    val options = remember {
        GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setResultFormats(
                GmsDocumentScannerOptions.RESULT_FORMAT_JPEG,
                GmsDocumentScannerOptions.RESULT_FORMAT_PDF,
            )
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
    }
    val scannerClient = remember { GmsDocumentScanning.getClient(options) }

    // True while we're waiting for `getStartScanIntent` to resolve. On first
    // use the ML Kit scanner module gets downloaded (~30MB) and this task
    // can sit for 10–30s; showing a loading row keeps the user from
    // double-tapping or assuming the app hung.
    var isLaunching by remember { mutableStateOf(false) }
    // When non-null, opens the extracted-text dialog for that attachment.
    var viewTextFor by remember { mutableStateOf<Attachment?>(null) }
    // When non-null, opens the in-house PDF viewer for that scan —
    // the user can flip through pages and import one into the notes
    // pager as a drawable background.
    var viewerFor by remember { mutableStateOf<Attachment?>(null) }
    // When non-null, opens the "Edit scan" dialog for that attachment.
    var editTargetFor by remember { mutableStateOf<Attachment?>(null) }
    // Active filter chip — null means "All". GENERAL is a real category
    // (catch-all for unmatched first words), so we model "show everything"
    // as a separate null state rather than overloading GENERAL.
    var filter by remember { mutableStateOf<ScanCategory?>(null) }

    val scanLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scanResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            val cachedPdf  = scanResult?.pdf?.uri
            val pageUris   = scanResult?.pages?.map { it.imageUri } ?: emptyList()
            val cachedJpeg = pageUris.firstOrNull()

            // ML Kit hands back content:// URIs pointing into its own cache
            // directory. Those stop resolving once the cache rotates, so we
            // copy both attachment artifacts (PDF + preview JPEG) into our
            // own filesDir and store the resulting file:// URIs on the
            // attachment. Cleanup on remove is then straightforward —
            // delete the files we own (VM.removeAttachment handles it).
            //
            // The page content:// URIs are NOT copied — they're handed
            // straight to the VM, which fires Text Recognition v2 off
            // the Compose tree. The scanner's result URIs stay readable
            // for the process lifetime, so a 1–3s-per-page inference run
            // sits well within their window.
            val localPdf  = cachedPdf?.let  { AttachmentStorage.copyIntoStorage(context, it, "pdf") }
            val localJpeg = cachedJpeg?.let { AttachmentStorage.copyIntoStorage(context, it, "jpg") }

            // Prefer the PDF as the primary asset, fall back to the JPEG
            // when the scanner only returned pages.
            val primary = localPdf ?: localJpeg
            if (primary == null) {
                // Nothing came back OR both copies failed. Rare, but worth
                // surfacing so the user doesn't think the tap did nothing.
                Toast.makeText(
                    context,
                    "Couldn't save scan. Try again.",
                    Toast.LENGTH_SHORT,
                ).show()
            } else {
                onAdd(primary.toString(), localJpeg?.toString(), pageUris)
            }
        }
    }

    val onLaunch: () -> Unit = launch@{
        val act = activity ?: return@launch
        if (isLaunching) return@launch
        isLaunching = true
        scannerClient.getStartScanIntent(act)
            .addOnSuccessListener { sender ->
                // Clear loading before launching the scanner Activity so
                // that when the user dismisses it the loading row isn't
                // stuck on.
                isLaunching = false
                scanLauncher.launch(IntentSenderRequest.Builder(sender).build())
            }
            .addOnFailureListener {
                isLaunching = false
                Toast.makeText(
                    context,
                    "Document scanner unavailable on this device.",
                    Toast.LENGTH_SHORT,
                ).show()
            }
    }

    // Delete guard — tap × on a row → open the alert. Actual remove
    // fires only on confirm.
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }

    // Classify once up-front; each row reuses its category for the chip
    // and the filter visibility test.
    val categorized = remember(scans) {
        scans.map { att ->
            // Prefer the user's override when set (matches `ScanCategory.name`),
            // otherwise derive from the OCR first-word heuristic.
            val override = att.categoryId
                ?.let { runCatching { ScanCategory.valueOf(it) }.getOrNull() }
            att to (override ?: ScanCategory.fromFirstWord(att.recognizedText))
        }
    }
    val visible = if (filter == null) categorized
                  else categorized.filter { it.second == filter }

    SectionShell(title = "SCAN DOCUMENTS") {
        // Affordance (or loading state while the scanner module
        // downloads) sits at the top of the section, so users always
        // know where to start a new scan even when the list is full.
        if (isLaunching) {
            LoadingRow(label = "Preparing scanner…")
        } else {
            AddAffordance(
                icon    = Icons.Filled.DocumentScanner,
                label   = "Scan document",
                onClick = onLaunch,
            )
        }

        if (scans.isNotEmpty()) {
            ScanFilterDropdown(
                selected = filter,
                onSelect = { filter = it },
            )

            if (visible.isEmpty()) {
                // User picked a filter that has no matches yet — show a
                // muted empty line rather than collapsing the section,
                // so the chip selection still looks responsive.
                Text(
                    text     = "No ${filter?.label.orEmpty()} scans yet.",
                    style    = AppTypography.Meta,
                    color    = AppColors.TextTertiary,
                    modifier = Modifier.padding(vertical = AppSpacing.s2),
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s2)) {
                    visible.forEach { (att, category) ->
                        ScanRow(
                            att         = att,
                            category    = category,
                            // Prefer the in-house PDF viewer for
                            // file:// PDFs — lets the user pick a
                            // page to annotate in notes. Falls back
                            // to the system-intent open for image-only
                            // scans (no PDF to paginate through).
                            onOpen      = {
                                val uriStr = att.uri
                                if (uriStr.endsWith(".pdf", ignoreCase = true)) {
                                    viewerFor = att
                                } else {
                                    openScan(context, att)
                                }
                            },
                            onRemove    = { pendingDeleteId = att.id },
                            onShare     = { shareScan(context, att) },
                            onEdit      = { editTargetFor = att },
                            onViewText  = if (!att.recognizedText.isNullOrBlank()) {
                                { viewTextFor = att }
                            } else null,
                        )
                    }
                }
            }
        }
    }

    pendingDeleteId?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title            = { Text("Delete this scan?") },
            text             = {
                Text(
                    "It'll be removed from this entry. The underlying " +
                        "scan files in app storage are cleaned up too.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onRemove(id)
                    pendingDeleteId = null
                }) {
                    Text("Delete", color = AppColors.Danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) {
                    Text("Cancel", color = AppColors.TextSecondary)
                }
            },
        )
    }

    viewerFor?.let { att ->
        PdfPageViewerDialog(
            pdfUri    = att.uri,
            onImport  = { pageImageUri ->
                onImportPageToNotes(pageImageUri)
                viewerFor = null
            },
            onDismiss = { viewerFor = null },
        )
    }

    editTargetFor?.let { att ->
        // Display the same category the list chip is showing, which
        // is the override when set and the derived value otherwise.
        val derived = ScanCategory.fromFirstWord(att.recognizedText)
        val override = att.categoryId
            ?.let { runCatching { ScanCategory.valueOf(it) }.getOrNull() }
        EditScanDialog(
            attachment      = att,
            currentCategory = override ?: derived,
            onSave          = { id, title, categoryId ->
                onEditScan(id, title, categoryId)
                editTargetFor = null
            },
            onDismiss       = { editTargetFor = null },
        )
    }

    viewTextFor?.let { att ->
        val recognized = att.recognizedText.orEmpty()
        AlertDialog(
            onDismissRequest = { viewTextFor = null },
            title            = { Text("Extracted text") },
            text             = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        text  = recognized,
                        style = AppTypography.Body,
                        color = AppColors.TextPrimary,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(
                        Context.CLIPBOARD_SERVICE,
                    ) as? android.content.ClipboardManager
                    clipboard?.setPrimaryClip(
                        android.content.ClipData.newPlainText("Scan text", recognized),
                    )
                    // Android 13+ surfaces a system toast automatically when
                    // clipboard copy happens, so we only toast on older
                    // versions to avoid a double-notification.
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                    }
                    viewTextFor = null
                }) {
                    Text("Copy", color = AppAccent.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewTextFor = null }) {
                    Text("Close", color = AppColors.TextSecondary)
                }
            },
        )
    }
}

/**
 * Compact dropdown above the scan list. The trigger shows the active
 * filter ("All" or a category name) with a chevron; tapping opens a
 * single-column menu with "All" + all seven [ScanCategory] entries.
 * Dropdown anchors to the trigger, so Material handles placement /
 * dismissal for us. Preferred over a horizontal chip row because eight
 * filter options end up wider than a phone in most locales.
 */
@Composable
private fun ScanFilterDropdown(
    selected: ScanCategory?,
    onSelect: (ScanCategory?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = selected?.label ?: "All"

    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(AppRadius.pill))
                .background(AppColors.Subtle)
                .clickable { expanded = true }
                .padding(
                    start = AppSpacing.s3,
                    end = AppSpacing.s2,
                    top = 6.dp,
                    bottom = 6.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text  = "Filter: ",
                style = AppTypography.Meta,
                color = AppColors.TextTertiary,
            )
            Text(
                text  = label,
                style = AppTypography.Button,
                color = AppColors.TextPrimary,
            )
            Spacer(Modifier.size(AppSpacing.s1))
            Icon(
                imageVector        = Icons.Filled.ExpandMore,
                contentDescription = null,
                tint               = AppColors.TextSecondary,
                modifier           = Modifier.size(18.dp),
            )
        }

        DropdownMenu(
            expanded         = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text    = {
                    Text(
                        "All",
                        style = AppTypography.Body,
                        color = if (selected == null) AppAccent.primary else AppColors.TextPrimary,
                    )
                },
                onClick = {
                    onSelect(null)
                    expanded = false
                },
            )
            ScanCategory.entries.forEach { cat ->
                val isActive = selected == cat
                DropdownMenuItem(
                    text    = {
                        Text(
                            cat.label,
                            style = AppTypography.Body,
                            color = if (isActive) AppAccent.primary else AppColors.TextPrimary,
                        )
                    },
                    onClick = {
                        onSelect(cat)
                        expanded = false
                    },
                )
            }
        }
    }
}

/**
 * List row for a scan attachment. Tappable surface opens the underlying
 * document in an external viewer via [openScan]; the category chip and
 * captured-at timestamp sit below the title, and a small "Aa" action on
 * the right surfaces the extracted-text dialog when OCR produced
 * anything. Uses a small thumbnail (48dp) rather than the larger grid
 * tile — one row per doc keeps the section readable as scans accumulate.
 */
@Composable
private fun ScanRow(
    att: Attachment,
    category: ScanCategory,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
    onShare: () -> Unit,
    onEdit: () -> Unit,
    onViewText: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.md))
            .background(AppColors.CardSolid)
            .border(1.dp, AppColors.BorderDefault, RoundedCornerShape(AppRadius.md))
            .clickable(onClick = onOpen)
            .padding(AppSpacing.s3),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s3),
    ) {
        // Thumbnail on the left — preview JPEG if we have one, otherwise
        // a file icon on a subtle swatch.
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(AppSpacing.s2))
                .background(AppColors.Subtle),
            contentAlignment = Alignment.Center,
        ) {
            val modelUri = att.previewUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
            if (modelUri != null) {
                AsyncImage(
                    model              = modelUri,
                    contentDescription = null,
                    contentScale       = ContentScale.Crop,
                    modifier           = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector        = Icons.AutoMirrored.Filled.InsertDriveFile,
                    contentDescription = null,
                    tint               = AppColors.TextTertiary,
                    modifier           = Modifier.size(22.dp),
                )
            }
        }

        // Title + meta row.
        Column(
            modifier            = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text     = scanTitle(att),
                style    = AppTypography.Body,
                color    = AppColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2),
            ) {
                CategoryChip(category = category)
                Text(
                    text  = formatRecordedAt(att.capturedAt),
                    style = AppTypography.Meta,
                    color = AppColors.TextTertiary,
                )
            }
        }

        // Single overflow button — keeps the row airy and scales to
        // more actions (export, rename, copy, …) without crowding the
        // trailing edge with ever-smaller icons. Row tap remains
        // "open the document"; the menu carries the secondary verbs.
        ScanRowOverflowMenu(
            hasRecognizedText = onViewText != null,
            onViewText        = onViewText ?: {},
            onShare           = onShare,
            onEdit            = onEdit,
            onRemove          = onRemove,
        )
    }
}

/**
 * IconButton + DropdownMenu pair for the scan row's overflow actions.
 * View-text is hidden when OCR didn't produce anything usable so the
 * menu doesn't carry a dead item. Delete uses the Danger color to
 * read as destructive.
 */
@Composable
private fun ScanRowOverflowMenu(
    hasRecognizedText: Boolean,
    onViewText: () -> Unit,
    onShare: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .clickable { expanded = true },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector        = Icons.Filled.MoreVert,
                contentDescription = "More actions",
                tint               = AppColors.TextSecondary,
                modifier           = Modifier.size(20.dp),
            )
        }
        DropdownMenu(
            expanded         = expanded,
            onDismissRequest = { expanded = false },
            modifier         = Modifier.background(AppColors.CardSolid),
        ) {
            if (hasRecognizedText) {
                DropdownMenuItem(
                    text        = { Text("View extracted text") },
                    leadingIcon = {
                        Icon(
                            imageVector        = Icons.Filled.Subtitles,
                            contentDescription = null,
                            tint               = AppAccent.primary,
                        )
                    },
                    onClick = {
                        expanded = false
                        onViewText()
                    },
                )
            }
            DropdownMenuItem(
                text        = { Text("Share") },
                leadingIcon = {
                    Icon(
                        imageVector        = Icons.Filled.Share,
                        contentDescription = null,
                        tint               = AppAccent.primary,
                    )
                },
                onClick = {
                    expanded = false
                    onShare()
                },
            )
            DropdownMenuItem(
                text        = { Text("Edit") },
                leadingIcon = {
                    Icon(
                        imageVector        = Icons.Filled.Edit,
                        contentDescription = null,
                        tint               = AppAccent.primary,
                    )
                },
                onClick = {
                    expanded = false
                    onEdit()
                },
            )
            DropdownMenuItem(
                text        = { Text("Delete", color = AppColors.Danger) },
                leadingIcon = {
                    Icon(
                        imageVector        = Icons.Filled.DeleteOutline,
                        contentDescription = null,
                        tint               = AppColors.Danger,
                    )
                },
                onClick = {
                    expanded = false
                    onRemove()
                },
            )
        }
    }
}

/**
 * Category pill shown on each scan row. Uses the warm neutral soft
 * swatch so it doesn't compete with the coral accent used elsewhere —
 * category is informational, not an action.
 */
@Composable
private fun CategoryChip(category: ScanCategory) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(AppRadius.pill))
            .background(AppColors.NeutralSoft)
            .padding(horizontal = AppSpacing.s2, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text  = category.label,
            style = AppTypography.Tag,
            color = AppColors.Neutral,
        )
    }
}

/**
 * Human-readable title for a scan row. Prefers the first non-blank line
 * of the recognized text, stripping a leading category-defining word
 * ("Todo", "Project", etc.) when that's all the first line contains —
 * so a scan whose first line is just "Project" falls through to line 2,
 * which is usually the actual subject. Returns a "Scan" fallback when
 * OCR produced nothing usable.
 */
private fun scanTitle(att: Attachment): String {
    // User-set title wins over the OCR-derived one — only fall back to
    // derivation when the override is blank / never-set.
    val overridden = att.title?.trim()
    if (!overridden.isNullOrBlank()) return overridden
    val text = att.recognizedText
    if (text.isNullOrBlank()) return "Scan"
    val lines = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .toList()
    if (lines.isEmpty()) return "Scan"

    val firstLine = lines[0]
    val firstWord = firstLine.substringBefore(' ')
        .trim { !it.isLetterOrDigit() }
        .lowercase()
    val leadingCategoryOnly = ScanCategory.entries.any { cat ->
        cat != ScanCategory.GENERAL && cat.matchers.any { it == firstWord }
    } && firstLine.substringAfter(' ', "").isBlank()

    return if (leadingCategoryOnly) lines.getOrElse(1) { firstLine } else firstLine
}

/**
 * Hand the scan's primary URI to an external viewer via
 * `Intent.ACTION_VIEW`. Our scans live as `file://` URIs under
 * `filesDir/releaf/attachments/`, which [FileProvider] exposes via the
 * `${packageName}.fileprovider` authority — the receiving app gets a
 * short-lived content:// grant. MIME is inferred from the extension on
 * the stored URI (PDF or JPEG today); falls back to a wildcard so an
 * unexpected extension still has a chance of resolving.
 */
private fun openScan(context: Context, attachment: Attachment) {
    val parsed = runCatching { Uri.parse(attachment.uri) }.getOrNull()
    if (parsed == null) {
        Toast.makeText(context, "Scan file missing.", Toast.LENGTH_SHORT).show()
        return
    }

    val viewUri: Uri? = when (parsed.scheme) {
        "file" -> {
            val file = runCatching { parsed.toFile() }.getOrNull()
            if (file == null || !file.exists()) null
            else runCatching {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                )
            }.getOrNull()
        }
        else -> parsed
    }
    if (viewUri == null) {
        Toast.makeText(context, "Scan file missing.", Toast.LENGTH_SHORT).show()
        return
    }

    val lower = attachment.uri.lowercase()
    val mime = when {
        lower.endsWith(".pdf")                             -> "application/pdf"
        lower.endsWith(".jpg") || lower.endsWith(".jpeg")  -> "image/jpeg"
        lower.endsWith(".png")                             -> "image/png"
        else                                               -> "*/*"
    }

    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(viewUri, mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, "Open scan"))
    }.onFailure {
        Toast.makeText(
            context,
            "No app available to open this scan.",
            Toast.LENGTH_SHORT,
        ).show()
    }
}

/**
 * Share the scan via the system share sheet (`Intent.ACTION_SEND`).
 * Same FileProvider contract as [openScan] — our `file://` URIs need
 * to be translated into a FileProvider `content://` URI before they
 * can leave the app, otherwise the receiving app hits
 * `FileUriExposedException`. MIME is inferred from the extension;
 * unknown types fall back to `application/octet-stream` so transport
 * apps (Drive, email, etc.) still show up in the chooser.
 */
private fun shareScan(context: Context, attachment: Attachment) {
    val parsed = runCatching { Uri.parse(attachment.uri) }.getOrNull()
    if (parsed == null) {
        Toast.makeText(context, "Scan file missing.", Toast.LENGTH_SHORT).show()
        return
    }

    val shareUri: Uri? = when (parsed.scheme) {
        "file" -> {
            val file = runCatching { parsed.toFile() }.getOrNull()
            if (file == null || !file.exists()) null
            else runCatching {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                )
            }.getOrNull()
        }
        else -> parsed
    }
    if (shareUri == null) {
        Toast.makeText(context, "Scan file missing.", Toast.LENGTH_SHORT).show()
        return
    }

    val lower = attachment.uri.lowercase()
    val mime = when {
        lower.endsWith(".pdf")                             -> "application/pdf"
        lower.endsWith(".jpg") || lower.endsWith(".jpeg")  -> "image/jpeg"
        lower.endsWith(".png")                             -> "image/png"
        else                                               -> "application/octet-stream"
    }

    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, shareUri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, "Share scan"))
    }.onFailure {
        Toast.makeText(
            context,
            "No app available to share this scan.",
            Toast.LENGTH_SHORT,
        ).show()
    }
}

// ========================== Voice ===========================

/**
 * Voice-note section. Header carries the eyebrow title with item count
 * plus a Record pill (flips to Stop while the mic is hot); captured
 * notes render as cards below with a large coral play button, a
 * waveform cursor, current / total timestamps, and download + delete
 * affordances.
 *
 * Record flow is inline (no modal): tap the Record pill → we request
 * the mic permission if needed → MediaRecorder spins up and a compact
 * red-dot indicator appears under the header. Tap Stop (the header
 * pill flipped) to commit the clip as an Attachment with `type = "voice"`
 * and the measured `durationMs`, or navigate away to abort — the
 * DisposableEffect tears the recorder down and deletes the partial file.
 *
 * Playback lives on each card and uses MediaPlayer. Cards own their own
 * player so they don't race each other; tapping play on one while
 * another is playing lets both play until completion — acceptable for
 * v1 since voice notes are short and the list rarely grows past a
 * couple of items.
 */
@Composable
fun VoiceSection(
    notes: List<Attachment>,
    onAdd: (uri: String, durationMs: Long) -> Unit,
    /** Fires once recognition finishes (success or retry). Keyed by uri
     *  so the viewmodel can patch the already-persisted attachment
     *  without the section tracking the newly-assigned id across the
     *  async hop. `source` is the engine id —
     *  [SpeechTranscriber.BACKEND_MLKIT] or
     *  [SpeechTranscriber.BACKEND_SHERPA] — used internally to pick
     *  the opposite engine on retry. */
    onTranscribed: (uri: String, transcript: String?, source: String?) -> Unit,
    /** "Add to notes" button on a transcribed voice-note card. The
     *  screen owns the `RichTextState` the editor body is bound to,
     *  so it handles the actual append. Default no-op keeps callers
     *  that don't wire a body-editor up working. */
    onAddTranscriptToNotes: (transcript: String) -> Unit = {},
    onRemove: (id: String) -> Unit,
) {
    val context = LocalContext.current

    // Active recording bundle. Non-null ⇔ mic is hot. `startedAtMs` is
    // monotonic elapsedRealtime so the elapsed-counter doesn't drift when
    // the device clock jumps.
    var recorderBundle by remember { mutableStateOf<RecorderBundle?>(null) }
    var elapsedMs by remember { mutableLongStateOf(0L) }
    val isRecording = recorderBundle != null

    // Coroutine scope for the file-based sherpa-onnx transcription.
    // Fired on-demand from the "Transcribe" button on each voice-note
    // card rather than automatically post-stop — keeps the ~112MB
    // first-use model download off the record path and lets the user
    // skip transcription for clips they don't care about.
    val transcribeScope = rememberCoroutineScope()

    // Delete guard — identical pattern to the other sections.
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }

    // Chevron expand state — toggles the per-card details panel (recorded
    // timestamp + duration + file size) above the playback row. Shared
    // across every card in the section so the chevron controls the whole
    // list at once.
    var isExpanded by remember { mutableStateOf(false) }

    // URIs whose transcription is in flight. Added only when `stop()`
    // reports the callback will fire asynchronously — prevents a 0-ms
    // "Transcribing…" flash on devices where recognition is unavailable
    // and the stop callback fires synchronously inside the same Compose
    // event tick. The card uses this set to pick "Transcribing…" over
    // the final transcript or the unavailable placeholder.
    var pendingTranscription by remember { mutableStateOf(setOf<String>()) }

    // URIs where a transcription attempt completed (success OR failure),
    // mapped to a human-readable reason when the attempt produced no
    // text. The map presence is the "attempted" signal; the value is
    // the reason string shown on the card (e.g. "Microphone busy"). A
    // null reason never lands here — when recognition succeeds the
    // transcript goes onto the attachment itself, not into this map.
    var attemptedTranscription by remember { mutableStateOf(mapOf<String, String>()) }

    // Tick while the recorder is hot. Bound to `recorderBundle` so
    // starting/stopping cleanly cancels the loop.
    LaunchedEffect(recorderBundle) {
        val b = recorderBundle ?: return@LaunchedEffect
        while (isActive && recorderBundle != null) {
            elapsedMs = SystemClock.elapsedRealtime() - b.startedAtMs
            delay(100)
        }
    }

    val stopRecording: () -> Unit = stop@{
        val b = recorderBundle ?: return@stop
        val finalMs = SystemClock.elapsedRealtime() - b.startedAtMs
        // Always tear the recorder down — any stop() exception (happens
        // when stop() fires < ~500ms after start()) means the file is
        // unusable, so we drop it.
        val stopOk = runCatching { b.recorder.stop() }.isSuccess
        runCatching { b.recorder.reset() }
        runCatching { b.recorder.release() }
        recorderBundle = null
        elapsedMs = 0L
        val isValid = stopOk && finalMs >= MIN_RECORDING_MS &&
            b.outputFile.exists() && b.outputFile.length() > 0L
        if (isValid) {
            val uri = Uri.fromFile(b.outputFile).toString()
            // Persist immediately. Transcription is on-demand now —
            // the card shows a "Transcribe" button that the user taps
            // to run sherpa-onnx against the saved .m4a; no background
            // work fires here. Keeps cold-start battery / CPU cost off
            // the default path (first-use model download is ~112MB)
            // and lets the user opt out entirely for clips they don't
            // care about transcribing.
            onAdd(uri, finalMs)
        } else {
            b.outputFile.delete()
            if (stopOk && finalMs < MIN_RECORDING_MS) {
                Toast.makeText(
                    context,
                    "Recording too short — try again.",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    // Process-death / nav-away safety net: tear down the recorder and
    // throw away any partial file so we don't leak mic or storage. The
    // transcription coroutine lives on `transcribeScope` which is
    // scoped to this composable — it cancels automatically on dispose,
    // so nothing explicit is needed for the sherpa-onnx work here.
    DisposableEffect(Unit) {
        onDispose {
            recorderBundle?.let { b ->
                runCatching { b.recorder.stop() }
                runCatching { b.recorder.reset() }
                runCatching { b.recorder.release() }
                b.outputFile.delete()
            }
        }
    }

    val startRecording: () -> Unit = start@{
        val file = File(
            AttachmentStorage.directory(context),
            "${Uuidv7.generate()}.m4a",
        )
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        try {
            // Transcription is file-based (sherpa-onnx, post-stop) so
            // this path no longer tries to share the mic with a
            // recognizer. `VOICE_RECOGNITION` is still the right source
            // — it's tuned for speech capture and keeps .m4a compact.
            rec.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioEncodingBitRate(64_000)
            // 16kHz matches whisper's native input rate — letting us
            // decode the saved .m4a straight to PCM and feed it to the
            // recognizer without a resampling pass. Also keeps file
            // sizes in check (voice notes are mono speech; 16kHz is the
            // telephony standard and sounds essentially identical to
            // 44.1kHz for this use case).
            rec.setAudioSamplingRate(16_000)
            rec.setOutputFile(file.absolutePath)
            rec.prepare()
            rec.start()
            recorderBundle = RecorderBundle(rec, file, SystemClock.elapsedRealtime())
        } catch (e: Exception) {
            runCatching { rec.release() }
            file.delete()
            Toast.makeText(
                context,
                "Couldn't start recording. Check the mic permission.",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startRecording()
        } else {
            Toast.makeText(
                context,
                "Microphone permission is needed to record voice notes.",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    val onTapRecord: () -> Unit = {
        val hasPerm = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (hasPerm) startRecording()
        else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    // User-initiated transcription. Clears any prior failure reason so
    // the row flips back to a "transcribing…" state (instead of
    // showing stale "unavailable" text alongside the spinner).
    //
    // `preferredBackend` forces a specific engine when the user taps
    // the "try the other engine" retry pill — passing null keeps the
    // default priority (Whisper-via-sherpa first, Gemini Nano as
    // fallback). Both engines are deterministic on the same audio, so
    // re-running with the same engine always produces the same text
    // — swapping is the only way to get a genuinely different result
    // when the first take was inaccurate. On devices without AICore
    // (most emulators, non-Pixel/Samsung hardware), forcing `mlkit`
    // will fail; the row surfaces the reason inline so the user
    // doesn't misread "Try again" as a silent no-op.
    val transcribe: (uri: String, preferredBackend: String?) -> Unit = { uri, preferredBackend ->
        pendingTranscription = pendingTranscription + uri
        attemptedTranscription = attemptedTranscription - uri
        transcribeScope.launch {
            val result = SpeechTranscriber.transcribe(context, uri, preferredBackend)
            pendingTranscription = pendingTranscription - uri
            when (result) {
                is TranscribeResult.Success ->
                    onTranscribed(uri, result.text, result.source)
                is TranscribeResult.Failure ->
                    attemptedTranscription = attemptedTranscription +
                        (uri to result.reason)
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s3),
    ) {
        VoiceSectionHeader(
            count = notes.size,
            isRecording = isRecording,
            isExpanded = isExpanded,
            onToggleExpand = { isExpanded = !isExpanded },
            onRecordClick = if (isRecording) stopRecording else onTapRecord,
        )

        if (isRecording) {
            RecordingIndicator(elapsedMs = elapsedMs)
        }

        notes.forEach { att ->
            VoiceNoteCard(
                attachment = att,
                expanded = isExpanded,
                isTranscribing = att.uri in pendingTranscription,
                unavailableReason = attemptedTranscription[att.uri],
                // Initial transcribe: no preferred backend → default
                // priority (Whisper first, ML Kit fallback).
                onTranscribe = { transcribe(att.uri, null) },
                // Retry: deliberately pick the *opposite* engine from
                // whatever produced the current transcript. Both are
                // deterministic so re-running the same one is pointless.
                onRetranscribe = {
                    val next = when (att.transcriptSource) {
                        SpeechTranscriber.BACKEND_MLKIT -> SpeechTranscriber.BACKEND_SHERPA
                        SpeechTranscriber.BACKEND_SHERPA -> SpeechTranscriber.BACKEND_MLKIT
                        else -> null
                    }
                    transcribe(att.uri, next)
                },
                onAddToNotes = onAddTranscriptToNotes,
                onRemove = { pendingDeleteId = att.id },
            )
        }
    }

    pendingDeleteId?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title            = { Text("Delete this voice note?") },
            text             = {
                Text(
                    "The clip will be removed from this entry and the file " +
                        "in app storage is cleaned up.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onRemove(id)
                    pendingDeleteId = null
                }) {
                    Text("Delete", color = AppColors.Danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) {
                    Text("Cancel", color = AppColors.TextSecondary)
                }
            },
        )
    }
}

/** Section header: mic glyph + eyebrow title with count on the lead,
 *  chevron + Record/Stop pill on the trailing edge. Not wrapped in
 *  `SectionShell` because the shell doesn't expose a trailing-action
 *  slot — the voice section is the only one that needs one today. */
@Composable
private fun VoiceSectionHeader(
    count: Int,
    isRecording: Boolean,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onRecordClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Mic,
            contentDescription = null,
            tint = AppAccent.primary,
            modifier = Modifier.size(12.dp),
        )
        Spacer(Modifier.size(AppSpacing.s1))
        Text(
            text = if (count > 0) "VOICE NOTES · $count" else "VOICE NOTES",
            style = AppTypography.Eyebrow,
            color = AppAccent.primary,
        )
        Spacer(Modifier.weight(1f))
        HeaderChevronButton(isExpanded = isExpanded, onClick = onToggleExpand)
        Spacer(Modifier.size(AppSpacing.s2))
        RecordPill(isRecording = isRecording, onClick = onRecordClick)
    }
}

/** Circular chevron button on the header. Rotates from right (collapsed)
 *  to down (expanded) on tap and tints coral-soft when open, so the
 *  state change reads at a glance alongside the details panel on each
 *  card. */
@Composable
private fun HeaderChevronButton(isExpanded: Boolean, onClick: () -> Unit) {
    val rotation by animateFloatAsState(
        targetValue = if (isExpanded) 90f else 0f,
        label = "chevronRotation",
    )
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(if (isExpanded) AppAccent.soft else AppColors.CardSolid)
            .border(1.dp, AppColors.BorderDefault, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = if (isExpanded) "Hide voice note details" else "Show voice note details",
            tint = if (isExpanded) AppAccent.primary else AppColors.TextSecondary,
            modifier = Modifier
                .size(18.dp)
                .rotate(rotation),
        )
    }
}

/** Record/Stop pill in the section header. Flips label + icon based on
 *  `isRecording` so the user has a single consistent control for both
 *  entering and exiting the recording state. */
@Composable
private fun RecordPill(isRecording: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(AppRadius.pill))
            .background(if (isRecording) AppAccent.soft else AppColors.CardSolid)
            .border(
                width = 1.dp,
                color = AppColors.BorderDefault,
                shape = RoundedCornerShape(AppRadius.pill),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = AppSpacing.s3, vertical = AppSpacing.s1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (isRecording) Icons.Filled.Stop else Icons.Filled.Mic,
            contentDescription = null,
            tint = AppAccent.primary,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.size(AppSpacing.s1))
        Text(
            text = if (isRecording) "Stop" else "Record",
            style = AppTypography.Button,
            color = AppAccent.primary,
        )
    }
}

/**
 * Compact red-dot indicator shown under the header while MediaRecorder
 * is hot. The Stop control lives on the header pill, so this row is
 * purely status — a subtle card with a pulsing-style red dot and live
 * mm:ss counter.
 */
@Composable
private fun RecordingIndicator(elapsedMs: Long) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.md))
            .background(AppColors.CardSolid)
            .border(1.dp, AppColors.BorderDefault, RoundedCornerShape(AppRadius.md))
            .padding(horizontal = AppSpacing.s3, vertical = AppSpacing.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(AppColors.Danger),
        )
        Spacer(Modifier.size(AppSpacing.s2))
        Text(
            text = "Recording · ${formatDurationMs(elapsedMs)}",
            style = AppTypography.Body,
            color = AppColors.TextPrimary,
        )
    }
}

/**
 * Voice-note card. Large coral play button, a waveform tracking the
 * current playback position, current/total timestamps beneath it, and
 * a download + delete stack on the trailing edge. Owns its own
 * MediaPlayer so state is local to the card — and releases it on
 * disposal so we don't leak audio sessions across navigation.
 */
@Composable
private fun VoiceNoteCard(
    attachment: Attachment,
    expanded: Boolean,
    isTranscribing: Boolean,
    /** Non-null when a transcription attempt completed and produced no
     *  text. Value is the human-readable reason we surface on the card
     *  (e.g. "Audio file missing"). Null means "never attempted" —
     *  the row shows a prominent "Transcribe" button instead. */
    unavailableReason: String?,
    onTranscribe: () -> Unit,
    /** Retry with the *other* engine — shown once a transcript exists
     *  (or failed) so the user can swap engines when accuracy is off.
     *  Re-running with the same engine would be a no-op since both
     *  are deterministic on the same audio. */
    onRetranscribe: () -> Unit,
    /** Drop the current transcript into the editor body. Only shown
     *  when the card has a transcript. Plumbed up through
     *  `VoiceSection` → the screen, which appends to the
     *  `RichTextState` the editor body is bound to. */
    onAddToNotes: (transcript: String) -> Unit,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    var player by remember(attachment.id) { mutableStateOf<MediaPlayer?>(null) }
    var isPlaying by remember(attachment.id) { mutableStateOf(false) }
    var positionMs by remember(attachment.id) { mutableLongStateOf(0L) }

    val totalMs = (attachment.durationMs ?: 0L).coerceAtLeast(1L)

    // File size is resolved off the disk once per card (and again if the
    // URI changes). Cheap — a single File.length() — but there's no point
    // doing it on every recomposition while the waveform cursor ticks.
    val fileSizeLabel = remember(attachment.uri) { resolveFileSizeLabel(context, attachment.uri) }

    // Real amplitude samples for the waveform. Decoded off the main
    // thread and cached by `WaveformSamples`; null until the first
    // decode completes, at which point `Waveform` swaps over from its
    // hash-seeded fallback. A row of uniformly tiny bars after decode =
    // the clip is silent, which is the diagnostic we want users to see
    // when transcription says "No speech detected".
    var amplitudes by remember(attachment.uri) { mutableStateOf<FloatArray?>(null) }
    LaunchedEffect(attachment.uri) {
        amplitudes = WaveformSamples.extract(attachment.uri, barCount = 40)
    }

    DisposableEffect(attachment.id) {
        onDispose {
            runCatching { player?.stop() }
            runCatching { player?.release() }
            player = null
        }
    }

    // Poll MediaPlayer position while playing so the waveform cursor +
    // current-time label track the clip. 100ms ≈ 10 fps — imperceptibly
    // smooth for a 40-bar waveform and cheap enough that we don't worry
    // about the loop.
    LaunchedEffect(isPlaying, player) {
        val mp = player ?: return@LaunchedEffect
        while (isActive && isPlaying) {
            positionMs = runCatching { mp.currentPosition.toLong() }.getOrDefault(0L)
            delay(100)
        }
    }

    val togglePlay: () -> Unit = toggle@{
        val existing = player
        if (existing == null) {
            // First tap — build the player lazily so we don't pay prepare
            // cost for rows the user never plays.
            val mp = MediaPlayer()
            try {
                mp.setDataSource(context, Uri.parse(attachment.uri))
                mp.setOnCompletionListener {
                    isPlaying = false
                    positionMs = 0L
                    runCatching { mp.seekTo(0) }
                }
                mp.prepare()
                mp.start()
            } catch (_: Exception) {
                runCatching { mp.release() }
                Toast.makeText(
                    context,
                    "Couldn't play this voice note.",
                    Toast.LENGTH_SHORT,
                ).show()
                return@toggle
            }
            player = mp
            isPlaying = true
        } else if (isPlaying) {
            runCatching { existing.pause() }
            isPlaying = false
        } else {
            runCatching { existing.start() }
            isPlaying = true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.md))
            .background(AppColors.CardSolid)
            .border(1.dp, AppColors.BorderDefault, RoundedCornerShape(AppRadius.md))
            .padding(AppSpacing.s3),
    ) {
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            VoiceNoteDetails(
                capturedAt = attachment.capturedAt,
                durationMs = attachment.durationMs ?: 0L,
                fileSizeLabel = fileSizeLabel,
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(AppAccent.primary)
                    .clickable(onClick = togglePlay),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = AppColors.OnAccent,
                    modifier = Modifier.size(26.dp),
                )
            }

            Spacer(Modifier.size(AppSpacing.s3))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.s1),
            ) {
                Waveform(
                    seed = attachment.id,
                    progress = (positionMs.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f),
                    playedColor = AppAccent.primary,
                    unplayedColor = AppColors.TextTertiary,
                    amplitudes = amplitudes,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(28.dp),
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = formatDurationMs(positionMs),
                        style = AppTypography.Meta,
                        color = AppColors.TextSecondary,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = formatDurationMs(attachment.durationMs ?: 0L),
                        style = AppTypography.Meta,
                        color = AppColors.TextSecondary,
                    )
                }
            }

            Spacer(Modifier.size(AppSpacing.s3))

            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s1)) {
                CardIconButton(
                    icon = Icons.Filled.FileDownload,
                    contentDescription = "Download",
                    onClick = { shareVoiceNote(context, attachment) },
                )
                CardIconButton(
                    icon = Icons.Filled.Delete,
                    contentDescription = "Delete",
                    onClick = onRemove,
                )
            }
        }

        // Transcript strip — always visible below the playback row.
        // Four states:
        //   idle / never attempted → "Transcribe" pill button
        //   pending                → spinner + "Transcribing…"
        //   success                → text + "Add to notes" + "Try again"
        //   failed                 → reason + "Retry" pill
        TranscriptRow(
            transcript = attachment.transcript,
            transcriptSource = attachment.transcriptSource,
            isPending = isTranscribing,
            unavailableReason = unavailableReason,
            onTranscribe = onTranscribe,
            onRetranscribe = onRetranscribe,
            onAddToNotes = onAddToNotes,
        )
    }
}

/** Inline transcript strip on the voice-note card. Always rendered so
 *  the user has a consistent affordance for transcription on every
 *  voice note — tapping "Transcribe" fires the file-based sherpa-onnx
 *  pipeline and this same strip flips through "Transcribing…" →
 *  transcript (or a retry affordance on failure). */
@Composable
private fun TranscriptRow(
    transcript: String?,
    transcriptSource: String?,
    isPending: Boolean,
    unavailableReason: String?,
    onTranscribe: () -> Unit,
    onRetranscribe: () -> Unit,
    /** Takes the current transcript text and hands it off to the
     *  screen-level append-to-editor-body callback. Only wired when
     *  `transcript` is non-empty (the button only renders then). */
    onAddToNotes: (String) -> Unit,
) {
    val hasTranscript = !transcript.isNullOrBlank()
    val hasReason = unavailableReason != null
    // Local to the row so every voice-note card carries its own
    // viewer-sheet state — opening one transcript doesn't affect
    // another's row above or below it.
    var showFullTranscript by remember { mutableStateOf(false) }

    val eyebrow = when {
        hasTranscript -> "TRANSCRIPT"
        isPending     -> "TRANSCRIBING"
        hasReason     -> "TRANSCRIPT UNAVAILABLE"
        else          -> "TRANSCRIPT"
    }

    Spacer(Modifier.size(AppSpacing.s3))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.sm))
            .background(AppColors.Subtle)
            .padding(AppSpacing.s3),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s2),
    ) {
        Text(
            text = eyebrow,
            style = AppTypography.Eyebrow,
            color = AppColors.TextTertiary,
        )
        when {
            hasTranscript -> {
                // Clamp to two lines with an ellipsis; tapping anywhere
                // on the preview opens the full-text viewer sheet. The
                // whole Text is clickable (not just the "…") because
                // Compose doesn't expose the ellipsis region as a
                // separate hit target, and surfacing the full text on
                // any tap is friendlier than a narrow hit zone anyway.
                Text(
                    text = transcript!!,
                    style = AppTypography.Body,
                    color = AppColors.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showFullTranscript = true },
                )
                // Two actions for a transcribed note:
                //   Add to notes — primary; drops the text into the
                //                  editor body so the user can keep
                //                  writing around it.
                //   Try again    — always swaps engines when we know
                //                  the source (both recognizers are
                //                  deterministic, rerunning the same
                //                  engine is pointless). Legacy
                //                  transcripts without a source fall
                //                  back to the default-priority
                //                  transcribe.
                val canSwap = transcriptSource == SpeechTranscriber.BACKEND_MLKIT ||
                    transcriptSource == SpeechTranscriber.BACKEND_SHERPA
                Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2)) {
                    TranscriptActionPill(
                        label = "Add to notes",
                        icon = Icons.AutoMirrored.Filled.NoteAdd,
                        onClick = { onAddToNotes(transcript) },
                    )
                    TranscriptActionPill(
                        label = "Try again",
                        icon = Icons.Filled.Subtitles,
                        onClick = if (canSwap) onRetranscribe else onTranscribe,
                    )
                }
            }
            isPending -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    color = AppAccent.primary,
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.size(AppSpacing.s2))
                Text(
                    text = "Running on-device recognition…",
                    style = AppTypography.Body,
                    color = AppColors.TextSecondary,
                )
            }
            hasReason -> {
                Text(
                    text = unavailableReason!!,
                    style = AppTypography.Body,
                    color = AppColors.TextSecondary,
                )
                TranscriptActionPill(
                    label = "Retry",
                    icon = Icons.Filled.Subtitles,
                    onClick = onTranscribe,
                )
            }
            else -> TranscriptActionPill(
                label = "Transcribe voice note",
                icon = Icons.Filled.Subtitles,
                onClick = onTranscribe,
            )
        }
    }

    if (showFullTranscript && hasTranscript) {
        TranscriptViewerSheet(
            transcript       = transcript!!,
            transcriptSource = transcriptSource,
            onDismiss        = { showFullTranscript = false },
        )
    }
}

/**
 * Full-text viewer for a voice-note transcript. Opened when the user
 * taps the clamped two-line preview on a voice-note card. Read-only
 * ModalBottomSheet with a scrollable body — long whisper transcripts
 * (multi-minute clips can run to several paragraphs) still fit, and
 * the engine attribution under the title tells the user which
 * recognizer produced the text they're reading.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TranscriptViewerSheet(
    transcript: String,
    transcriptSource: String?,
    onDismiss: () -> Unit,
) {
    // Bypass the half-height peek — the viewer is content-first and
    // should open as tall as it wants to be.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scrollState = rememberScrollState()

    val attribution = when (transcriptSource) {
        SpeechTranscriber.BACKEND_MLKIT  -> "Gemini Nano"
        SpeechTranscriber.BACKEND_SHERPA -> "Whisper"
        else -> null
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        containerColor   = AppColors.Canvas,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start  = AppSpacing.s4,
                    end    = AppSpacing.s4,
                    top    = AppSpacing.s2,
                    bottom = AppSpacing.s4,
                ),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.s3),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text  = "Transcript",
                        style = AppTypography.SectionTitle,
                        color = AppColors.TextPrimary,
                    )
                    if (attribution != null) {
                        Text(
                            text  = "via $attribution",
                            style = AppTypography.Meta,
                            color = AppColors.TextSecondary,
                        )
                    }
                }
                Text(
                    text     = "Done",
                    style    = AppTypography.Button,
                    color    = AppColors.Coral,
                    modifier = Modifier.clickable(onClick = onDismiss),
                )
            }
            // Scrolls when the transcript exceeds the sheet's natural
            // height. The sheet itself is still capped at the bottom-
            // sheet max (approximately screen height minus top inset),
            // so this is the inner scroll the user needs for long
            // transcripts.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState),
            ) {
                Text(
                    text  = transcript,
                    style = AppTypography.Body,
                    color = AppColors.TextPrimary,
                )
            }
        }
    }
}

/** Coral pill button used by the transcript row for every user-facing
 *  action — "Transcribe voice note", "Retry", "Try again", "Add to
 *  notes". Takes an `ImageVector` so each action keeps its own icon
 *  while the shape / colour / typography stay uniform. */
@Composable
private fun TranscriptActionPill(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(AppRadius.pill))
            .background(AppAccent.primary)
            .clickable(onClick = onClick)
            .padding(horizontal = AppSpacing.s3, vertical = AppSpacing.s1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = AppColors.OnAccent,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.size(AppSpacing.s1))
        Text(
            text = label,
            style = AppTypography.Button,
            color = AppColors.OnAccent,
        )
    }
}

/** Expanded-state details panel shown above the playback row. Leads with
 *  a coral-soft mic tile + human-readable "Recorded …" timestamp, then a
 *  meta row with a waveform-glyph + duration and storage-glyph + file
 *  size. Separated into its own composable so the animated expand/collapse
 *  path stays readable. Transcript doesn't live here any more — it got
 *  pulled out to `TranscriptRow` below the playback row so the user
 *  sees it without having to expand the card first. */
@Composable
private fun VoiceNoteDetails(
    capturedAt: String,
    durationMs: Long,
    fileSizeLabel: String?,
) {
    Column(modifier = Modifier.padding(bottom = AppSpacing.s3)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(AppRadius.sm))
                    .background(AppAccent.soft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Mic,
                    contentDescription = null,
                    tint = AppAccent.primary,
                    modifier = Modifier.size(16.dp),
                )
            }
            Spacer(Modifier.size(AppSpacing.s2))
            Text(
                text = "Recorded ${formatRecordedAt(capturedAt)}",
                style = AppTypography.Body,
                color = AppColors.TextPrimary,
            )
        }
        Spacer(Modifier.size(AppSpacing.s2))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.GraphicEq,
                contentDescription = null,
                tint = AppColors.TextTertiary,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.size(AppSpacing.s1))
            Text(
                text = formatDurationMs(durationMs),
                style = AppTypography.Meta,
                color = AppColors.TextSecondary,
            )
            if (fileSizeLabel != null) {
                Spacer(Modifier.size(AppSpacing.s3))
                Icon(
                    imageVector = Icons.Filled.Storage,
                    contentDescription = null,
                    tint = AppColors.TextTertiary,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.size(AppSpacing.s1))
                Text(
                    text = fileSizeLabel,
                    style = AppTypography.Meta,
                    color = AppColors.TextSecondary,
                )
            }
        }
    }
}

/** Small square icon button — subtle rounded-rect, used for the
 *  download / delete stack on the voice-note card. Intentionally muted
 *  so it doesn't compete with the coral play button. */
@Composable
private fun CardIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(AppRadius.sm))
            .background(AppColors.Subtle)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = AppColors.TextSecondary,
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * Decorative waveform — 40 vertical bars with heights stable per note
 * (hashed from the attachment id) so the shape doesn't jitter on
 * recomposition. Bars left of `progress` (0..1) render in `playedColor`,
 * the rest in `unplayedColor`, giving the user a cheap visual cursor
 * while the MediaPlayer advances.
 *
 * Bars derive from real amplitude samples when `amplitudes` is non-null
 * (pulled off the decoded PCM by `WaveformSamples` — see the extraction
 * pass in `VoiceNoteCard`). While the samples are still being computed
 * on first render, or if the file isn't decodable, we fall back to a
 * hash-seeded decorative shape so the card never renders empty.
 */
@Composable
private fun Waveform(
    seed: String,
    progress: Float,
    playedColor: Color,
    unplayedColor: Color,
    amplitudes: FloatArray? = null,
    modifier: Modifier = Modifier,
) {
    val barCount = 40
    val heights = remember(seed, amplitudes) {
        amplitudes?.takeIf { it.size == barCount } ?: run {
            val rng = java.util.Random(seed.hashCode().toLong())
            FloatArray(barCount) { 0.2f + rng.nextFloat() * 0.8f }
        }
    }
    Canvas(modifier = modifier) {
        val gap = 3f
        val barWidth = ((size.width - gap * (barCount - 1)) / barCount).coerceAtLeast(1f)
        val centerY = size.height / 2f
        val progressPx = size.width * progress
        var x = barWidth / 2f
        for (i in 0 until barCount) {
            val h = size.height * heights[i]
            val barColor = if (x <= progressPx) playedColor else unplayedColor
            drawLine(
                color = barColor,
                start = Offset(x, centerY - h / 2f),
                end = Offset(x, centerY + h / 2f),
                strokeWidth = barWidth,
                cap = StrokeCap.Round,
            )
            x += barWidth + gap
        }
    }
}

/** Fire a share sheet for the given voice-note attachment. The URI is
 *  stored as `file://` on disk; we remap to the FileProvider authority
 *  so the receiving app has read permission, then launch ACTION_SEND
 *  as an audio/mp4 intent. Matches the FileProvider pattern used by
 *  camera captures in the photos section. */
private fun shareVoiceNote(context: Context, attachment: Attachment) {
    val file = runCatching { File(Uri.parse(attachment.uri).path ?: "") }.getOrNull()
    if (file == null || !file.exists()) {
        Toast.makeText(context, "Voice note file missing.", Toast.LENGTH_SHORT).show()
        return
    }
    val shareUri = runCatching {
        FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
    }.getOrNull()
    if (shareUri == null) {
        Toast.makeText(context, "Couldn't prepare voice note for sharing.", Toast.LENGTH_SHORT).show()
        return
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "audio/mp4"
        putExtra(Intent.EXTRA_STREAM, shareUri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(Intent.createChooser(send, "Share voice note"))
    }.onFailure {
        Toast.makeText(context, "No app available to share this clip.", Toast.LENGTH_SHORT).show()
    }
}

/** mm:ss formatter — Locale.US so the minute/second separator stays `:`
 *  regardless of device locale (which otherwise can flip in some RTL
 *  contexts). Zero-padded minutes so the card's current / total
 *  timestamps stay a fixed width as the cursor advances. */
private fun formatDurationMs(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0L)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(Locale.US, minutes, seconds)
}

/** "Apr 21, 2026 at 10:59 PM"-style label for the expanded details row.
 *  Input is the ISO-8601 UTC string stored at capture time; we render in
 *  the device's local zone and locale. Falls back to the raw ISO string
 *  if parsing fails so nothing in the panel ever blanks out. */
private val recordedAtFormatter: DateTimeFormatter by lazy {
    DateTimeFormatter.ofPattern("MMM d, yyyy 'at' h:mm a", Locale.getDefault())
        .withZone(ZoneId.systemDefault())
}

private fun formatRecordedAt(iso: String): String =
    runCatching { recordedAtFormatter.format(Instant.parse(iso)) }.getOrDefault(iso)

/** Read the underlying file's byte size and format it via the platform
 *  formatter (`Formatter.formatShortFileSize`). Returns null if the URI
 *  doesn't point to a readable file — the details panel drops the field
 *  entirely rather than showing a confusing 0 B. */
private fun resolveFileSizeLabel(context: Context, uri: String): String? {
    val file = runCatching { File(Uri.parse(uri).path ?: "") }.getOrNull()
    if (file == null || !file.exists() || file.length() <= 0L) return null
    return android.text.format.Formatter.formatShortFileSize(context, file.length())
}

/** Bundle passed around while a recording is in flight. Carries the
 *  MediaRecorder along with the output file and monotonic start time so
 *  stop / cleanup paths have everything they need. */
private data class RecorderBundle(
    val recorder: MediaRecorder,
    val outputFile: File,
    val startedAtMs: Long,
)

/** Anything under this is almost certainly a misfire (double-tap). We
 *  drop the clip rather than persisting a near-silent fragment. */
private const val MIN_RECORDING_MS: Long = 500L

// ========================== Grid helper ==========================

/**
 * 3-column vertical grid used by Photos + Scans. Non-lazy — nests
 * inside the OverviewPane / editor ScrollView which owns outer
 * scrolling. Empty slots in the last row become transparent spacers
 * so the grid stays visually uniform. Delete intent goes through
 * `onRemoveRequest` so the section can gate the actual removal on
 * a confirmation dialog.
 */
@Composable
private fun AttachmentGrid(
    attachments: List<Attachment>,
    onRemoveRequest: (id: String) -> Unit,
    placeholder: @Composable () -> Unit,
    columns: Int = 3,
    onTap: (index: Int) -> Unit = {},
    /** When true, tiles render a checkmark overlay instead of the
     *  close (×) button; a tile tap toggles selection through
     *  [onToggleSelect] and [selectedIds] drives the selected look. */
    selectMode: Boolean = false,
    selectedIds: Set<String> = emptySet(),
    onToggleSelect: (id: String) -> Unit = {},
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s2),
    ) {
        attachments.chunked(columns).forEachIndexed { rowIndex, rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2),
            ) {
                rowItems.forEachIndexed { colIndex, att ->
                    val flatIndex = rowIndex * columns + colIndex
                    AttachmentTile(
                        att         = att,
                        onRemove    = { onRemoveRequest(att.id) },
                        onTap       = {
                            if (selectMode) onToggleSelect(att.id)
                            else            onTap(flatIndex)
                        },
                        selectMode  = selectMode,
                        selected    = att.id in selectedIds,
                        placeholder = placeholder,
                        modifier    = Modifier.weight(1f).aspectRatio(1f),
                    )
                }
                repeat(columns - rowItems.size) {
                    Spacer(Modifier.weight(1f).aspectRatio(1f))
                }
            }
        }
    }
}

@Composable
private fun AttachmentTile(
    att: Attachment,
    onRemove: () -> Unit,
    placeholder: @Composable () -> Unit,
    onTap: () -> Unit = {},
    selectMode: Boolean = false,
    selected: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(AppSpacing.s2))
            .background(AppColors.CardSolid)
            .border(
                width = if (selectMode && selected) 2.dp else 1.dp,
                color = if (selectMode && selected) AppAccent.primary
                        else                         AppColors.BorderDefault,
                shape = RoundedCornerShape(AppSpacing.s2),
            )
            // Whole tile is the tap target. In select mode the caller
            // routes taps through `onToggleSelect`; outside select mode
            // they fall through to the viewer.
            .clickable(onClick = onTap),
    ) {
        val renderable = att.type == Attachment.TYPE_PHOTO || att.previewUri != null
        val thumb = att.previewUri ?: att.uri
        val modelUri = runCatching { Uri.parse(thumb) }.getOrNull()
        if (renderable && modelUri != null) {
            AsyncImage(
                model              = modelUri,
                contentDescription = null,
                contentScale       = ContentScale.Crop,
                modifier           = Modifier.fillMaxSize(),
            )
        } else {
            Box(
                modifier         = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { placeholder() }
        }

        if (selectMode) {
            // Selection checkmark overlay — top-left so it doesn't
            // collide with existing trailing-edge UI. Unselected tiles
            // get a hollow circle so the tap affordance is obvious.
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) AppAccent.primary
                        else          Color.Black.copy(alpha = 0.45f),
                    )
                    .border(
                        width = 1.5.dp,
                        color = Color.White,
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Icon(
                        imageVector        = Icons.Filled.Check,
                        contentDescription = "Selected",
                        tint               = Color.White,
                        modifier           = Modifier.size(14.dp),
                    )
                }
            }
        } else {
            // Close button overlay — hidden in select mode because
            // remove doesn't apply to a multi-select flow.
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector        = Icons.Filled.Close,
                    contentDescription = "Remove",
                    tint               = Color.White,
                    modifier           = Modifier.size(14.dp),
                )
            }
        }
    }
}

// ========================== Inline input ==========================

/**
 * Simple single-line text field used by contacts + todos for "+ Add X"
 * expansions. IME Done commits, blank submission cancels. Styled like
 * the rest of the editor's body — no border, coral cursor.
 */
@Composable
private fun InlineTextInput(
    placeholder: String,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var value by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    // Pop the keyboard as soon as the field appears — the user already
    // tapped "+ Add X" to get here, so making them tap a second time to
    // focus the field feels like a bug.
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AppSpacing.s1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(
                    placeholder,
                    style = AppTypography.Body,
                    color = AppColors.TextTertiary,
                )
            }
            BasicTextField(
                value           = value,
                onValueChange   = { value = it },
                singleLine      = true,
                textStyle       = AppTypography.Body.copy(color = AppColors.TextPrimary),
                cursorBrush     = SolidColor(AppAccent.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    if (value.isBlank()) onCancel() else onSubmit(value)
                    value = ""
                }),
                modifier        = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
        }
        DeleteButton(onClick = {
            value = ""
            onCancel()
        })
    }
}
