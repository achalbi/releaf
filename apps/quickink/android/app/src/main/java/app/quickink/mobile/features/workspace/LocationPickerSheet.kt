/*
 * LocationPickerSheet.kt
 *
 * Bottom sheet for attaching / detaching user-defined locations
 * from a single capture. Mirror of TagPickerSheet — same structure,
 * minus the AI-suggested strip (locations aren't auto-inferred from
 * OCR) and the tag-name normalization (location names are proper
 * nouns like "Home", "Office NYC" — keep mixed case).
 *
 * UX flow:
 *   1. Sheet opens with the currently-attached locations preselected.
 *   2. The user can type a new name and tap Done to add it
 *      (find-or-create via [LocationRepository.findOrCreate]).
 *   3. The "All locations" list shows every active location; tapping
 *      a row toggles its check state.
 *   4. Save commits the diff against the original attachment set via
 *      [CaptureLocationDao.attachLocation] / [detachLocation]. Cancel
 *      discards.
 */

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.quickink.mobile.features.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.quickink.mobile.QuickInkApp
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkRadius
import app.quickink.mobile.ui.theme.QuickInkSpacing
import app.releaf.mobile.data.common.IsoClock
import app.releaf.mobile.data.common.Uuidv7
import kotlinx.coroutines.launch

@Composable
fun LocationPickerSheet(
    captureId: String,
    userId: String,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val colors  = LocalQuickInkColors.current
    val type    = LocalQuickInkTypography.current
    val context = LocalContext.current
    val app     = remember(context) { context.applicationContext as QuickInkApp }
    val scope   = rememberCoroutineScope()

    val allLocations by remember(userId) {
        app.database.locationDao().observeActive(userId)
    }.collectAsState(initial = emptyList())

    // Editor dialog state — "+ Add new location" opens it in create
    // mode; the editor handles name + Use-current / Search-address
    // commit. The new row appears in `allLocations` via the Flow.
    var editorOpen by remember { mutableStateOf(false) }

    val originalIds by remember(captureId) {
        app.database.captureLocationDao().observeLocationIdsForCapture(captureId)
    }.collectAsState(initial = emptyList())

    // Working set — initialised from the on-disk attachments the
    // first time they arrive. After that the user's toggles win
    // until Save / Cancel. `seeded` guards the one-time copy.
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var seeded by remember { mutableStateOf(false) }
    LaunchedEffect(originalIds) {
        if (!seeded && originalIds.isNotEmpty()) {
            selectedIds = originalIds.toSet()
            seeded = true
        }
    }

    val countDelta = selectedIds.size

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        containerColor   = colors.surface,
    ) {
        Column(modifier = Modifier.padding(horizontal = QuickInkSpacing.s4, vertical = QuickInkSpacing.s2)) {
            Text(
                text  = "Add locations",
                style = type.editorial.copy(fontSize = 19.sp, fontWeight = FontWeight.SemiBold),
                color = colors.ink,
            )
            Text(
                text  = "Mark where this document was captured. Locations are seeded with Home and Work.",
                style = type.meta,
                color = colors.muted,
                modifier = Modifier.padding(top = 4.dp, bottom = QuickInkSpacing.s3),
            )

            // ─── "+ Add new location" button ─────────────────
            // Opens the editor dialog where the user can set a name +
            // optionally pin "current location" or a searched address.
            // The new row drops into `allLocations` through the Flow.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(QuickInkRadius.md))
                    .background(colors.accentSoft.copy(alpha = 0.5f))
                    .border(1.dp, colors.accent.copy(alpha = 0.4f), RoundedCornerShape(QuickInkRadius.md))
                    .clickable { editorOpen = true }
                    .padding(horizontal = QuickInkSpacing.s3, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector        = Icons.Filled.Add,
                    contentDescription = null,
                    tint               = colors.accent,
                    modifier           = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text  = "Add new location",
                    style = type.label.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                    color = colors.accent,
                )
            }

            Spacer(Modifier.height(QuickInkSpacing.s3))

            HorizontalDivider(color = colors.borderSoft)

            // ─── All-locations list ───────────────────────────
            Text(
                text  = "ALL LOCATIONS",
                style = type.label.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp),
                color = colors.muted,
                modifier = Modifier.padding(top = QuickInkSpacing.s3, bottom = 4.dp),
            )

            if (allLocations.isEmpty()) {
                Text(
                    text  = "No locations yet. Type one above to start.",
                    style = type.meta,
                    color = colors.muted,
                    modifier = Modifier.padding(vertical = QuickInkSpacing.s2),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                ) {
                    items(allLocations, key = { it.id }) { loc ->
                        val isSelected = loc.id in selectedIds
                        AllLocationsRow(
                            name       = loc.name,
                            address    = loc.address,
                            isSelected = isSelected,
                            onToggle   = {
                                selectedIds = if (isSelected) selectedIds - loc.id
                                              else selectedIds + loc.id
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(QuickInkSpacing.s2))
            HorizontalDivider(color = colors.borderSoft)

            // ─── Footer CTAs ──────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = QuickInkSpacing.s3, bottom = QuickInkSpacing.s2),
                horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(QuickInkRadius.md))
                        .background(colors.ink)
                        .clickable {
                            val finalSelected = selectedIds
                            val toDetach = originalIds.toSet() - finalSelected
                            val toAttach = finalSelected - originalIds.toSet()
                            scope.launch {
                                val now = IsoClock.nowIso()
                                val joinDao = app.database.captureLocationDao()
                                for (locationId in toAttach) {
                                    joinDao.attachLocation(
                                        joinId     = Uuidv7.generate(),
                                        captureId  = captureId,
                                        locationId = locationId,
                                        source     = "manual",
                                        timestamp  = now,
                                    )
                                }
                                for (locationId in toDetach) {
                                    joinDao.detachLocation(captureId, locationId, now)
                                }
                                onDismiss()
                            }
                        }
                        .padding(vertical = 13.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text  = "Save $countDelta ${if (countDelta == 1) "location" else "locations"}",
                        style = type.body.copy(fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp),
                        color = Color.White,
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(QuickInkRadius.md))
                        .background(colors.borderSoft)
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 18.dp, vertical = 13.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text  = "Cancel",
                        style = type.body.copy(fontWeight = FontWeight.Medium, fontSize = 13.5.sp),
                        color = colors.ink,
                    )
                }
            }
        }
    }

    // Editor dialog — sibling layer above the picker sheet. Opens
    // when the user taps "+ Add new location". On save the new row
    // arrives via the `allLocations` Flow; we also pre-select it so
    // the picker stays in a "newly created → about to attach" state.
    if (editorOpen) {
        LocationEditorDialog(
            userId    = userId,
            existing  = null,
            onDismiss = { editorOpen = false },
            onSaved   = { saved ->
                selectedIds = selectedIds + saved.id
                editorOpen  = false
            },
        )
    }
}

// ─── Components ──────────────────────────────────────────────

@Composable
private fun AllLocationsRow(
    name: String,
    address: String?,
    isSelected: Boolean,
    onToggle: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(
                    color = if (isSelected) colors.accent else Color.Transparent,
                    shape = RoundedCornerShape(5.dp),
                )
                .border(
                    width = if (isSelected) 0.dp else 1.5.dp,
                    color = if (isSelected) colors.accent else colors.border,
                    shape = RoundedCornerShape(5.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (isSelected) {
                Icon(
                    imageVector        = Icons.Outlined.Check,
                    contentDescription = null,
                    tint               = Color.White,
                    modifier           = Modifier.size(12.dp),
                )
            }
        }
        Spacer(Modifier.width(QuickInkSpacing.s2))
        Icon(
            imageVector        = Icons.Filled.LocationOn,
            contentDescription = null,
            tint               = colors.accent.copy(alpha = 0.6f),
            modifier           = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(4.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text  = name,
                style = type.body.copy(fontWeight = FontWeight.Medium, fontSize = 13.5.sp),
                color = colors.ink,
            )
            if (!address.isNullOrBlank()) {
                Text(
                    text     = address,
                    style    = type.caption.copy(fontSize = 10.5.sp),
                    color    = colors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
