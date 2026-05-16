/*
 * LocationEditorDialog.kt
 *
 * Shared dialog used by both the Home-screen Manage sheet and the
 * scan-detail Locations picker sheet to create or edit a single
 * `locations` row. Exposes three affordances:
 *
 *   - Name input (required)
 *   - "Use current location" — gates on ACCESS_COARSE_LOCATION, runs
 *     [LocationService.captureCurrent] and fills the row's lat/lng +
 *     reverse-geocoded address.
 *   - "Search address" — opens an inline forward-geocode search;
 *     tapping a result fills the same three fields without touching
 *     GPS.
 *
 * The dialog returns the saved entity via [onSaved] so the caller
 * can do whatever (select it in the picker, scroll to it, etc.). It
 * owns the actual DAO writes — the caller just passes userId.
 */

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.quickink.mobile.features.workspace

import android.Manifest
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import app.quickink.mobile.QuickInkApp
import app.quickink.mobile.data.location.LocationEntity
import app.quickink.mobile.data.location.LocationRepository
import app.quickink.mobile.features.scan.LocationService
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkRadius
import app.quickink.mobile.ui.theme.QuickInkSpacing
import kotlinx.coroutines.launch

@Composable
fun LocationEditorDialog(
    userId: String,
    existing: LocationEntity?,
    onDismiss: () -> Unit,
    onSaved: (LocationEntity) -> Unit,
) {
    val context = LocalContext.current
    val colors  = LocalQuickInkColors.current
    val type    = LocalQuickInkTypography.current
    val app     = remember(context) { context.applicationContext as QuickInkApp }
    val scope   = rememberCoroutineScope()
    val repo    = remember(app) { LocationRepository(app.database.locationDao()) }

    var name      by remember(existing?.id) { mutableStateOf(existing?.name.orEmpty()) }
    var latitude  by remember(existing?.id) { mutableStateOf(existing?.latitude) }
    var longitude by remember(existing?.id) { mutableStateOf(existing?.longitude) }
    var address   by remember(existing?.id) { mutableStateOf(existing?.address) }

    var fetchingCurrent by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var pendingCurrentLocationAfterPermission by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { grantMap ->
        val granted = grantMap.values.any { it }
        if (granted && pendingCurrentLocationAfterPermission) {
            pendingCurrentLocationAfterPermission = false
            // Re-trigger the fetch after the system dialog resolves.
            fetchingCurrent = true
            scope.launch {
                val current = LocationService.captureCurrent(context)
                if (current != null) {
                    latitude  = current.latitude
                    longitude = current.longitude
                    address   = current.address
                        ?: listOfNotNull(current.subLocality, current.locality)
                            .joinToString(", ")
                            .takeIf { it.isNotEmpty() }
                    statusMessage = null
                } else {
                    statusMessage = "Couldn't read current location."
                }
                fetchingCurrent = false
            }
        } else if (!granted) {
            pendingCurrentLocationAfterPermission = false
            statusMessage = "Location permission denied."
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = colors.surface,
        title = {
            Text(
                text  = if (existing == null) "New location" else "Edit location",
                style = type.heading,
                color = colors.ink,
            )
        },
        text = {
            Column(
                modifier            = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3),
            ) {
                OutlinedTextField(
                    value         = name,
                    onValueChange = { name = it },
                    label         = { Text("Name") },
                    placeholder   = { Text("Home, Office, Cafe…", color = colors.muted) },
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

                if (address != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(QuickInkRadius.md))
                            .background(colors.accentSoft.copy(alpha = 0.45f))
                            .padding(QuickInkSpacing.s3),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(
                            imageVector        = Icons.Filled.LocationOn,
                            contentDescription = null,
                            tint               = colors.accent,
                            modifier           = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(QuickInkSpacing.s2))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text  = address ?: "",
                                style = type.body.copy(fontSize = 12.5.sp),
                                color = colors.ink,
                            )
                            val lat = latitude
                            val lng = longitude
                            if (lat != null && lng != null) {
                                Text(
                                    text  = String.format(
                                        java.util.Locale.US,
                                        "%.5f, %.5f",
                                        lat,
                                        lng,
                                    ),
                                    style = type.caption.copy(fontSize = 10.sp),
                                    color = colors.muted,
                                )
                            }
                        }
                        Text(
                            text     = "Clear",
                            style    = type.label.copy(
                                fontSize      = 10.5.sp,
                                fontWeight    = FontWeight.SemiBold,
                                letterSpacing = 1.sp,
                            ),
                            color    = colors.muted,
                            modifier = Modifier.clickable {
                                latitude  = null
                                longitude = null
                                address   = null
                            },
                        )
                    }
                }

                ActionPill(
                    icon     = Icons.Outlined.MyLocation,
                    label    = if (fetchingCurrent) "Finding…" else "Use current location",
                    loading  = fetchingCurrent,
                    enabled  = !fetchingCurrent,
                    modifier = Modifier.fillMaxWidth(),
                    onClick  = {
                        if (!LocationService.hasPermission(context)) {
                            pendingCurrentLocationAfterPermission = true
                            permissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_COARSE_LOCATION,
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                ),
                            )
                            return@ActionPill
                        }
                        fetchingCurrent = true
                        statusMessage = null
                        scope.launch {
                            val current = LocationService.captureCurrent(context)
                            if (current != null) {
                                latitude  = current.latitude
                                longitude = current.longitude
                                address   = current.address
                                    ?: listOfNotNull(current.subLocality, current.locality)
                                        .joinToString(", ")
                                        .takeIf { it.isNotEmpty() }
                            } else {
                                statusMessage = "Couldn't read current location."
                            }
                            fetchingCurrent = false
                        }
                    },
                )

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
            val enabled = name.trim().isNotEmpty() && !fetchingCurrent
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
                            val saved: LocationEntity = if (existing == null) {
                                repo.findOrCreate(
                                    userId    = userId,
                                    name      = trimmed,
                                    latitude  = latitude,
                                    longitude = longitude,
                                    address   = address,
                                )
                            } else {
                                if (trimmed != existing.name) {
                                    repo.rename(existing.id, trimmed)
                                }
                                if (latitude != existing.latitude ||
                                    longitude != existing.longitude ||
                                    address  != existing.address
                                ) {
                                    repo.setLocation(
                                        id        = existing.id,
                                        latitude  = latitude,
                                        longitude = longitude,
                                        address   = address,
                                    )
                                }
                                existing.copy(
                                    name      = trimmed,
                                    latitude  = latitude,
                                    longitude = longitude,
                                    address   = address,
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
private fun ActionPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current
    val shape  = RoundedCornerShape(QuickInkRadius.md)
    Row(
        modifier = modifier
            .clip(shape)
            .background(if (enabled) colors.borderSoft else colors.borderSoft.copy(alpha = 0.5f), shape)
            .border(1.dp, colors.border, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = QuickInkSpacing.s3, vertical = QuickInkSpacing.s2),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier   = Modifier.size(14.dp),
                strokeWidth = 1.5.dp,
                color      = colors.accent,
            )
        } else {
            Icon(
                imageVector        = icon,
                contentDescription = null,
                tint               = colors.inkSoft,
                modifier           = Modifier.size(14.dp),
            )
        }
        Spacer(Modifier.width(QuickInkSpacing.s2))
        Text(
            text  = label,
            style = type.label.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
            color = colors.ink,
        )
    }
}
