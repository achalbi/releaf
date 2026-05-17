/*
 * PeoplePickerSheet.kt
 *
 * Bottom sheet for attaching / detaching user-defined people from a
 * single capture. Mirror of LocationPickerSheet — Save diffs the
 * selection against the on-disk attachment set and calls
 * [CapturePersonDao.attachPerson] / [detachPerson].
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
import androidx.compose.material.icons.filled.Person
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
fun PeoplePickerSheet(
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

    val allPeople by remember(userId) {
        app.database.personDao().observeActive(userId)
    }.collectAsState(initial = emptyList())

    val originalIds by remember(captureId) {
        app.database.capturePersonDao().observePersonIdsForCapture(captureId)
    }.collectAsState(initial = emptyList())

    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var seeded by remember { mutableStateOf(false) }
    LaunchedEffect(originalIds) {
        if (!seeded && originalIds.isNotEmpty()) {
            selectedIds = originalIds.toSet()
            seeded = true
        }
    }

    // Editor dialog state — "+ Add new person" opens it in create
    // mode. The new row arrives via the `allPeople` Flow; we
    // pre-select it so the picker stays in a "newly created → about
    // to attach" state.
    var editorOpen by remember { mutableStateOf(false) }

    val countDelta = selectedIds.size

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        containerColor   = colors.surface,
    ) {
        Column(modifier = Modifier.padding(horizontal = QuickInkSpacing.s4, vertical = QuickInkSpacing.s2)) {
            Text(
                text  = "Add people",
                style = type.editorial.copy(fontSize = 19.sp, fontWeight = FontWeight.SemiBold),
                color = colors.ink,
            )
            Text(
                text  = "Mark who this document is about. People are seeded with Me.",
                style = type.meta,
                color = colors.muted,
                modifier = Modifier.padding(top = 4.dp, bottom = QuickInkSpacing.s3),
            )

            // ─── "+ Add new person" button ────────────────────
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
                    text  = "Add new person",
                    style = type.label.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                    color = colors.accent,
                )
            }

            Spacer(Modifier.height(QuickInkSpacing.s3))

            HorizontalDivider(color = colors.borderSoft)

            // ─── All-people list ──────────────────────────────
            Text(
                text  = "ALL PEOPLE",
                style = type.label.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp),
                color = colors.muted,
                modifier = Modifier.padding(top = QuickInkSpacing.s3, bottom = 4.dp),
            )

            if (allPeople.isEmpty()) {
                Text(
                    text  = "No people yet. Add one above to start.",
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
                    items(allPeople, key = { it.id }) { person ->
                        val isSelected = person.id in selectedIds
                        AllPeopleRow(
                            name       = person.name,
                            isSelected = isSelected,
                            onToggle   = {
                                selectedIds = if (isSelected) selectedIds - person.id
                                              else selectedIds + person.id
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
                                val joinDao = app.database.capturePersonDao()
                                for (personId in toAttach) {
                                    joinDao.attachPerson(
                                        joinId    = Uuidv7.generate(),
                                        captureId = captureId,
                                        personId  = personId,
                                        source    = "manual",
                                        timestamp = now,
                                    )
                                }
                                for (personId in toDetach) {
                                    joinDao.detachPerson(captureId, personId, now)
                                }
                                onDismiss()
                            }
                        }
                        .padding(vertical = 13.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text  = "Save $countDelta ${if (countDelta == 1) "person" else "people"}",
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

    if (editorOpen) {
        PersonEditorDialog(
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

@Composable
private fun AllPeopleRow(
    name: String,
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
            imageVector        = Icons.Filled.Person,
            contentDescription = null,
            tint               = colors.accent.copy(alpha = 0.6f),
            modifier           = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text  = name,
            style = type.body.copy(fontWeight = FontWeight.Medium, fontSize = 13.5.sp),
            color = colors.ink,
        )
    }
}
