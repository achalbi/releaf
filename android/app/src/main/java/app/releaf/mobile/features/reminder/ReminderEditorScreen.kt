/*
 * ReminderEditorScreen.kt
 *
 * Create / edit form for a single reminder. Title + note + a combined
 * date-and-time picker, with Save in the top bar and a Delete icon
 * that only shows on the edit path.
 *
 * The VM owns everything (field values, pre-fill, commit). This screen
 * is presentation + picker orchestration.
 */

package app.releaf.mobile.features.reminder

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.viewmodel.compose.viewModel
import app.releaf.mobile.ui.theme.AppAccent
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppRadius
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderEditorScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ReminderEditorViewModel = viewModel(factory = ReminderEditorViewModel.Factory),
) {
    val state by viewModel.state.collectAsState()

    // Once the VM stamps `saved`, pop back. Keyed on the flag so
    // toggling it from false→true fires once.
    LaunchedEffect(state.saved) { if (state.saved) onBack() }

    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    val isEdit = state.id != null

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        EditorTopBar(
            title      = if (isEdit) "Edit reminder" else "New reminder",
            canSave    = state.canSave,
            showDelete = isEdit,
            onBack     = onBack,
            onSave     = viewModel::save,
            onDelete   = viewModel::delete,
        )

        if (state.isLoading) {
            Box(
                Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = AppAccent.primary)
            }
            return@Column
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AppSpacing.s4, vertical = AppSpacing.s3),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.s3),
        ) {
            val fieldColors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AppAccent.primary,
                cursorColor        = AppAccent.primary,
                focusedLabelColor  = AppAccent.primary,
            )

            OutlinedTextField(
                value           = state.title,
                onValueChange   = viewModel::updateTitle,
                label           = { Text("Title") },
                singleLine      = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction      = ImeAction.Next,
                ),
                colors   = fieldColors,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value           = state.note,
                onValueChange   = viewModel::updateNote,
                label           = { Text("Note (optional)") },
                minLines        = 3,
                maxLines        = 6,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                ),
                colors   = fieldColors,
                modifier = Modifier.fillMaxWidth(),
            )

            DateTimePickerRow(
                remindAt      = state.remindAt,
                onPickDate    = { showDatePicker = true },
                onPickTime    = { showTimePicker = true },
            )

            RecurrencePickerRow(
                selectedDays = state.recursEveryDays,
                onSelect     = viewModel::updateRecursEveryDays,
            )

            PerspectivePickerRow(
                perspectives = state.perspectives,
                selectedId   = state.perspectiveId,
                onSelect     = viewModel::updatePerspective,
            )
        }
    }

    if (showDatePicker) {
        DatePickerSheet(
            initial      = state.remindAt,
            onConfirm    = { newEpochMillis ->
                showDatePicker = false
                // Compose only the date part; preserve the existing
                // time-of-day so the user doesn't have to re-pick.
                viewModel.updateRemindAt(mergeDate(state.remindAt, newEpochMillis))
            },
            onDismiss    = { showDatePicker = false },
        )
    }
    if (showTimePicker) {
        TimePickerSheet(
            initial      = state.remindAt,
            onConfirm    = { hour, minute ->
                showTimePicker = false
                viewModel.updateRemindAt(mergeTime(state.remindAt, hour, minute))
            },
            onDismiss    = { showTimePicker = false },
        )
    }
}

/* ---------- pieces ---------- */

@Composable
private fun EditorTopBar(
    title: String,
    canSave: Boolean,
    showDelete: Boolean,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start  = AppSpacing.s4,
                end    = AppSpacing.s4,
                top    = AppSpacing.s3,
                bottom = AppSpacing.s3,
            ),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s3),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector        = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint               = AppColors.TextPrimary,
            )
        }
        Text(
            text     = title,
            style    = AppTypography.SectionTitle,
            color    = AppColors.TextPrimary,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (showDelete) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onDelete),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector        = Icons.Filled.DeleteOutline,
                    contentDescription = "Delete",
                    tint               = AppColors.Danger,
                )
            }
        }
        Text(
            text  = "Save",
            style = AppTypography.Button,
            color = if (canSave) AppAccent.primary else AppColors.TextTertiary,
            modifier = Modifier.clickable(enabled = canSave, onClick = onSave),
        )
    }
}

/**
 * Horizontally-scrollable row of perspective chips — None at the
 * left, then every saved perspective. Selected chip fills with the
 * accent palette.
 */
@Composable
private fun PerspectivePickerRow(
    perspectives: List<app.releaf.mobile.data.perspective.PerspectiveEntity>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s1)) {
        Text(
            text  = "PERSPECTIVE",
            style = AppTypography.Eyebrow,
            color = AppColors.TextSecondary,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2),
        ) {
            val noneActive = selectedId == null
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(AppRadius.pill))
                    .background(if (noneActive) AppAccent.primary else AppColors.CardSolid)
                    .border(
                        width = 1.dp,
                        color = if (noneActive) AppAccent.primary else AppColors.BorderDefault,
                        shape = RoundedCornerShape(AppRadius.pill),
                    )
                    .clickable { onSelect(null) }
                    .padding(horizontal = AppSpacing.s3, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text  = "None",
                    style = AppTypography.Meta,
                    color = if (noneActive) AppColors.OnAccent else AppColors.TextPrimary,
                )
            }
            perspectives.forEach { p ->
                val active = p.id == selectedId
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(AppRadius.pill))
                        .background(if (active) AppAccent.primary else AppColors.CardSolid)
                        .border(
                            width = 1.dp,
                            color = if (active) AppAccent.primary else AppColors.BorderDefault,
                            shape = RoundedCornerShape(AppRadius.pill),
                        )
                        .clickable { onSelect(p.id) }
                        .padding(horizontal = AppSpacing.s3, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text  = "@${p.name}",
                        style = AppTypography.Meta.copy(fontWeight = FontWeight.SemiBold),
                        color = if (active) AppColors.OnAccent else AppColors.TextPrimary,
                    )
                }
            }
        }
    }
}

/**
 * Preset chips for the recurrence interval. Tapping any chip writes
 * the days-value via [onSelect] — null for one-shot, a positive Int
 * otherwise. The active chip fills with the accent palette so the
 * current selection is obvious at a glance.
 */
@Composable
private fun RecurrencePickerRow(
    selectedDays: Int?,
    onSelect: (Int?) -> Unit,
) {
    val presets = listOf(
        null to "None",
        1    to "Daily",
        7    to "Weekly",
        14   to "2 weeks",
        30   to "Monthly",
    )
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.s1)) {
        Text(
            text  = "REPEATS",
            style = AppTypography.Eyebrow,
            color = AppColors.TextSecondary,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2),
        ) {
            presets.forEach { (days, label) ->
                val active = days == selectedDays
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(AppRadius.pill))
                        .background(if (active) AppAccent.primary else AppColors.CardSolid)
                        .border(
                            width = 1.dp,
                            color = if (active) AppAccent.primary else AppColors.BorderDefault,
                            shape = RoundedCornerShape(AppRadius.pill),
                        )
                        .clickable { onSelect(days) }
                        .padding(horizontal = AppSpacing.s3, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text  = label,
                        style = AppTypography.Meta,
                        color = if (active) AppColors.OnAccent else AppColors.TextPrimary,
                    )
                }
            }
        }
    }
}

@Composable
private fun DateTimePickerRow(
    remindAt: Long,
    onPickDate: () -> Unit,
    onPickTime: () -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(remindAt), zone)
    val dateLabel = dt.format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault()))
    val timeLabel = dt.format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()))

    Row(
        modifier              = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s3),
    ) {
        PickerField(
            icon     = Icons.Filled.CalendarToday,
            label    = "DATE",
            value    = dateLabel,
            onClick  = onPickDate,
            modifier = Modifier.weight(1f),
        )
        PickerField(
            icon     = Icons.Filled.Schedule,
            label    = "TIME",
            value    = timeLabel,
            onClick  = onPickTime,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun PickerField(
    icon: ImageVector,
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(AppRadius.md))
            .background(AppColors.CardSolid)
            .border(
                width = 1.dp,
                color = AppColors.BorderDefault,
                shape = RoundedCornerShape(AppRadius.md),
            )
            .clickable(onClick = onClick)
            .padding(AppSpacing.s3),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s1),
    ) {
        Text(
            text  = label,
            style = AppTypography.Eyebrow,
            color = AppColors.TextSecondary,
        )
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2),
        ) {
            Icon(
                imageVector        = icon,
                contentDescription = null,
                tint               = AppAccent.primary,
                modifier           = Modifier.size(18.dp),
            )
            Text(
                text     = value,
                style    = AppTypography.Body,
                color    = AppColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePickerSheet(
    initial: Long,
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    // DatePickerState works in UTC epoch millis at start-of-day. Map
    // from the existing local-date to the matching UTC start-of-day so
    // the displayed date matches what the user sees on the row.
    val initialUtc = LocalDateTime.ofInstant(Instant.ofEpochMilli(initial), ZoneId.systemDefault())
        .toLocalDate()
        .atStartOfDay(ZoneOffset.UTC)
        .toInstant()
        .toEpochMilli()
    val pickerState = rememberDatePickerState(initialSelectedDateMillis = initialUtc)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                pickerState.selectedDateMillis?.let(onConfirm)
            }) { Text("OK", color = AppAccent.primary) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = AppColors.TextSecondary) }
        },
    ) {
        DatePicker(state = pickerState)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerSheet(
    initial: Long,
    onConfirm: (hour: Int, minute: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(initial), ZoneId.systemDefault())
    val pickerState = rememberTimePickerState(
        initialHour   = dt.hour,
        initialMinute = dt.minute,
        is24Hour      = false,
    )
    // TimePicker doesn't ship a dialog wrapper — render it inside a
    // minimal alert-style sheet so the user still gets an OK/Cancel
    // pair.
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                onConfirm(pickerState.hour, pickerState.minute)
            }) { Text("OK", color = AppAccent.primary) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = AppColors.TextSecondary) }
        },
        title = { Text("Pick a time", color = AppColors.TextPrimary) },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier            = Modifier.fillMaxWidth(),
            ) {
                Spacer(Modifier.height(AppSpacing.s2))
                TimePicker(state = pickerState)
            }
        },
        containerColor = AppColors.CardSolid,
    )
}

/* ---------- date/time merge helpers ---------- */

/** Replace only the date portion of [base] with the date represented
 *  by [newDateUtcMillis] (UTC midnight, as DatePickerState stores it),
 *  keeping the original time-of-day in the device's local zone. */
private fun mergeDate(base: Long, newDateUtcMillis: Long): Long {
    val zone = ZoneId.systemDefault()
    val baseTime: LocalTime = LocalDateTime
        .ofInstant(Instant.ofEpochMilli(base), zone)
        .toLocalTime()
    val newDate: LocalDate = Instant.ofEpochMilli(newDateUtcMillis)
        .atZone(ZoneOffset.UTC)
        .toLocalDate()
    return newDate.atTime(baseTime)
        .atZone(zone)
        .toInstant()
        .toEpochMilli()
}

/** Replace only the time-of-day portion of [base] with [hour]:[minute]
 *  in the device's local zone. */
private fun mergeTime(base: Long, hour: Int, minute: Int): Long {
    val zone = ZoneId.systemDefault()
    val baseDate: LocalDate = LocalDateTime
        .ofInstant(Instant.ofEpochMilli(base), zone)
        .toLocalDate()
    return baseDate.atTime(hour, minute)
        .atZone(zone)
        .toInstant()
        .toEpochMilli()
}
