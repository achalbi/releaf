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
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.SystemClock
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.net.toFile
import app.releaf.mobile.data.common.AttachmentStorage
import app.releaf.mobile.data.common.SpeechTranscriber
import app.releaf.mobile.data.common.TranscribeResult
import app.releaf.mobile.data.common.Uuidv7
import app.releaf.mobile.data.notebook.Attachment
import app.releaf.mobile.data.notebook.Contact
import app.releaf.mobile.data.notebook.GeoLocation
import app.releaf.mobile.data.notebook.ScanCategory
import app.releaf.mobile.data.notebook.TodoItem
import app.releaf.mobile.ui.theme.AppColors
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
        Text(title, style = AppTypography.Eyebrow, color = AppColors.Coral)
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
            tint               = AppColors.Coral,
            modifier           = Modifier.size(20.dp),
        )
        Spacer(Modifier.size(AppSpacing.s3))
        Text(label, style = AppTypography.Body, color = AppColors.TextPrimary)
        Spacer(Modifier.weight(1f))
        Icon(
            imageVector        = Icons.Filled.Add,
            contentDescription = null,
            tint               = AppColors.Coral,
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
            color       = AppColors.Coral,
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
    onAdd: (name: String) -> Unit,
    onRemove: (id: String) -> Unit,
) {
    var isAdding by remember { mutableStateOf(false) }
    // Delete guard — tap × on a chip → open the alert. `onRemove`
    // fires only on explicit confirm.
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }

    SectionShell(title = "CONTACTS") {
        // Add affordance sits at the top of the section, matching the
        // Photos / Scans pattern so the "how do I add?" affordance is
        // always in the same place.
        if (isAdding) {
            InlineTextInput(
                placeholder = "Name",
                onSubmit    = { text ->
                    onAdd(text)
                    isAdding = false
                },
                onCancel    = { isAdding = false },
            )
        } else {
            AddAffordance(
                icon  = Icons.Filled.People,
                label = "Add contact",
                onClick = { isAdding = true },
            )
        }

        if (contacts.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2),
                verticalArrangement   = Arrangement.spacedBy(AppSpacing.s2),
            ) {
                contacts.forEach { c ->
                    ContactChip(c, onRemove = { pendingDeleteId = c.id })
                }
            }
        }
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

@Composable
private fun ContactChip(contact: Contact, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(AppSpacing.s3))
            .background(AppColors.Coral.copy(alpha = 0.15f))
            .padding(start = AppSpacing.s3, end = AppSpacing.s1, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text  = contact.name,
            style = AppTypography.Body,
            color = AppColors.TextPrimary,
        )
        Spacer(Modifier.size(AppSpacing.s1))
        DeleteButton(onClick = onRemove)
    }
}

// =========================== Todos ============================

@Composable
fun TodosSection(
    todos: List<TodoItem>,
    onAdd: (text: String) -> Unit,
    onToggle: (id: String) -> Unit,
    onRemove: (id: String) -> Unit,
) {
    var isAdding by remember { mutableStateOf(false) }
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }

    SectionShell(title = "TODOS") {
        // Add affordance (or inline input) on top.
        if (isAdding) {
            InlineTextInput(
                placeholder = "New todo",
                onSubmit    = { text ->
                    onAdd(text)
                    isAdding = false
                },
                onCancel    = { isAdding = false },
            )
        } else {
            AddAffordance(
                icon  = Icons.Filled.CheckBoxOutlineBlank,
                label = "Add todo",
                onClick = { isAdding = true },
            )
        }

        // Todo rows below. × tap routes through `pendingDeleteId` so
        // the actual remove is gated by the confirmation alert.
        todos.forEach { t ->
            TodoRow(
                todo     = t,
                onToggle = { onToggle(t.id) },
                onRemove = { pendingDeleteId = t.id },
            )
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
private fun TodoRow(todo: TodoItem, onToggle: () -> Unit, onRemove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector        = if (todo.done) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
            contentDescription = if (todo.done) "Mark not done" else "Mark done",
            tint               = AppColors.Coral,
            modifier           = Modifier
                .size(22.dp)
                .clickable(onClick = onToggle),
        )
        Spacer(Modifier.size(AppSpacing.s3))
        Text(
            text     = todo.text,
            style    = AppTypography.Body.copy(
                textDecoration = if (todo.done) TextDecoration.LineThrough else null,
            ),
            color    = if (todo.done) AppColors.TextTertiary else AppColors.TextPrimary,
            modifier = Modifier.weight(1f),
        )
        DeleteButton(onClick = onRemove)
    }
}

// ========================= Location ==========================

@Composable
fun LocationSection(
    locations: List<GeoLocation>,
    onAdd: (lat: Double, lng: Double, address: String?) -> Unit,
    onRemove: (id: String) -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity

    // Permission + fetch pipeline. The success/failure paths feed the VM
    // via `onAdd` on the main thread.
    val fetchLocation: () -> Unit = fetch@{
        if (activity == null) return@fetch
        val client = LocationServices.getFusedLocationProviderClient(activity)
        client.getCurrentLocation(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            CancellationTokenSource().token,
        ).addOnSuccessListener { location ->
            if (location == null) {
                Toast.makeText(context, "Couldn't read GPS — try again outdoors.", Toast.LENGTH_SHORT).show()
                return@addOnSuccessListener
            }
            resolveAddress(context, location.latitude, location.longitude) { address ->
                onAdd(location.latitude, location.longitude, address)
            }
        }.addOnFailureListener {
            Toast.makeText(context, "Location unavailable.", Toast.LENGTH_SHORT).show()
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
        val primary = target?.address
            ?: target?.let { "%.5f, %.5f".format(Locale.US, it.lat, it.lng) }
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
    val primary = location.address ?: "%.5f, %.5f".format(Locale.US, location.lat, location.lng)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector        = Icons.Filled.LocationOn,
            contentDescription = null,
            tint               = AppColors.Coral,
            modifier           = Modifier.size(18.dp),
        )
        Spacer(Modifier.size(AppSpacing.s3))
        Text(
            text     = primary,
            style    = AppTypography.Body,
            color    = AppColors.TextPrimary,
            modifier = Modifier.weight(1f),
        )
        DeleteButton(onClick = onRemove)
    }
}

/**
 * Reverse-geocode to a human-readable address. Uses the async API on
 * Android 13+ (required; the sync one is deprecated and throttled), the
 * sync API otherwise. Any failure silently resolves to null — the
 * fallback is to show raw coordinates, not crash.
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

// ========================== Photos ===========================

@Composable
fun PhotosSection(
    photos: List<Attachment>,
    onAdd: (uri: String) -> Unit,
    onRemove: (id: String) -> Unit,
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

    SectionShell(title = "PHOTOS") {
        // Affordance sits at the top of the section so users always
        // know where to add, even with a full grid below.
        AddAffordance(
            icon    = Icons.Filled.PhotoCamera,
            label   = "Add photo",
            onClick = { showChooser = true },
        )

        if (photos.isNotEmpty()) {
            AttachmentGrid(
                attachments     = photos,
                onRemoveRequest = { id -> pendingDeleteId = id },
                placeholder     = {
                    Icon(Icons.Filled.PhotoCamera, null, tint = AppColors.TextTertiary)
                },
            )
        }
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
            tint               = AppColors.Coral,
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
        scans.map { it to ScanCategory.fromFirstWord(it.recognizedText) }
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
                            onOpen      = { openScan(context, att) },
                            onRemove    = { pendingDeleteId = att.id },
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
                    Text("Copy", color = AppColors.Coral)
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
                        color = if (selected == null) AppColors.Coral else AppColors.TextPrimary,
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
                            color = if (isActive) AppColors.Coral else AppColors.TextPrimary,
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

        // Optional "Aa" action — only present when OCR produced text.
        // Standalone button so the row's primary tap stays "open the
        // document" rather than "open the text dialog".
        if (onViewText != null) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onViewText),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text  = "Aa",
                    style = AppTypography.Button,
                    color = AppColors.Coral,
                )
            }
        }
        DeleteButton(onClick = onRemove)
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
    /** Fires once the recognizer flushes its final `onResults` after the
     *  recorder stops — typically 0.5-2s later. Keyed by uri (which
     *  embeds a uuidv7 and is unique) so the viewmodel can patch the
     *  already-persisted attachment without the section having to
     *  track the newly-assigned id across the async hop. Matches the
     *  iOS twin exactly. */
    onTranscribed: (uri: String, transcript: String?) -> Unit,
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
    // showing stale "unavailable" text alongside the spinner) and
    // fires the sherpa-onnx pipeline against the saved .m4a. Result routes
    // through `onTranscribed` on success, back into
    // `attemptedTranscription` on failure.
    val transcribe: (String) -> Unit = { uri ->
        pendingTranscription = pendingTranscription + uri
        attemptedTranscription = attemptedTranscription - uri
        transcribeScope.launch {
            val result = SpeechTranscriber.transcribe(context, uri)
            pendingTranscription = pendingTranscription - uri
            when (result) {
                is TranscribeResult.Success ->
                    onTranscribed(uri, result.text)
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
                onTranscribe = { transcribe(att.uri) },
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
            tint = AppColors.Coral,
            modifier = Modifier.size(12.dp),
        )
        Spacer(Modifier.size(AppSpacing.s1))
        Text(
            text = if (count > 0) "VOICE NOTES · $count" else "VOICE NOTES",
            style = AppTypography.Eyebrow,
            color = AppColors.Coral,
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
            .background(if (isExpanded) AppColors.CoralSoft else AppColors.CardSolid)
            .border(1.dp, AppColors.BorderDefault, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = if (isExpanded) "Hide voice note details" else "Show voice note details",
            tint = if (isExpanded) AppColors.Coral else AppColors.TextSecondary,
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
            .background(if (isRecording) AppColors.CoralSoft else AppColors.CardSolid)
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
            tint = AppColors.Coral,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.size(AppSpacing.s1))
        Text(
            text = if (isRecording) "Stop" else "Record",
            style = AppTypography.Button,
            color = AppColors.Coral,
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
                    .background(AppColors.Coral)
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
                    playedColor = AppColors.Coral,
                    unplayedColor = AppColors.TextTertiary,
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
        //   success                → "TRANSCRIPT" + body text
        //   failed                 → reason + "Retry" pill
        TranscriptRow(
            transcript = attachment.transcript,
            isPending = isTranscribing,
            unavailableReason = unavailableReason,
            onTranscribe = onTranscribe,
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
    isPending: Boolean,
    unavailableReason: String?,
    onTranscribe: () -> Unit,
) {
    val hasTranscript = !transcript.isNullOrBlank()
    val hasReason = unavailableReason != null

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
            hasTranscript -> Text(
                text = transcript!!,
                style = AppTypography.Body,
                color = AppColors.TextPrimary,
            )
            isPending -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    color = AppColors.Coral,
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
                TranscribeButton(label = "Retry", onClick = onTranscribe)
            }
            else -> TranscribeButton(label = "Transcribe voice note", onClick = onTranscribe)
        }
    }
}

/** Coral pill that kicks off (or retries) transcription. Kept as its
 *  own composable because the transcript row renders the same button
 *  in two different states ("Transcribe…" for untouched notes and
 *  "Retry" after a failure) and we want them visually identical. */
@Composable
private fun TranscribeButton(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(AppRadius.pill))
            .background(AppColors.Coral)
            .clickable(onClick = onClick)
            .padding(horizontal = AppSpacing.s3, vertical = AppSpacing.s1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Subtitles,
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
                    .background(AppColors.CoralSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Mic,
                    contentDescription = null,
                    tint = AppColors.Coral,
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
 * while the MediaPlayer advances. No real amplitude data yet — the
 * MediaRecorder doesn't preserve it and we don't want to re-decode the
 * file just for a sparkline.
 */
@Composable
private fun Waveform(
    seed: String,
    progress: Float,
    playedColor: Color,
    unplayedColor: Color,
    modifier: Modifier = Modifier,
) {
    val barCount = 40
    val heights = remember(seed) {
        val rng = java.util.Random(seed.hashCode().toLong())
        FloatArray(barCount) { 0.2f + rng.nextFloat() * 0.8f }
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
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s2),
    ) {
        attachments.chunked(columns).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2),
            ) {
                rowItems.forEach { att ->
                    AttachmentTile(
                        att         = att,
                        onRemove    = { onRemoveRequest(att.id) },
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
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(AppSpacing.s2))
            .background(AppColors.CardSolid)
            .border(
                width = 1.dp,
                color = AppColors.BorderDefault,
                shape = RoundedCornerShape(AppSpacing.s2),
            ),
    ) {
        // We have a renderable thumbnail whenever the attachment is a photo
        // or the scanner gave us a JPEG preview URI for the PDF. Anything
        // else falls through to the placeholder icon — don't trust URI
        // extensions; content:// URIs from ML Kit / MediaStore rarely
        // carry one.
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

        // Close button overlay — small background so it's legible on any thumb.
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
                cursorBrush     = SolidColor(AppColors.Coral),
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
