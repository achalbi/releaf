/*
 * RemindersScreen.kt
 *
 * The main Reminders surface. Layout (top → bottom):
 *
 *   - Compact header with back + "Reminders" title.
 *   - Permission banner when POST_NOTIFICATIONS or exact-alarm is
 *     not yet granted — tapping jumps to the right system settings
 *     page.
 *   - Scrollable list:
 *       • Hero "Up next" card — the soonest upcoming reminder,
 *         with a live countdown ("In 42 min") and two inline
 *         actions: Snooze 15m and Mark done. Lets the user clear
 *         the most common case without opening the full editor.
 *       • Time-rail grouped sections — Today / Tomorrow / This
 *         week / Later / Past. Each row shows a mono left-rail
 *         time, the title, perspective-tagged chip(s) (parsed
 *         from @tag in the title, coloured with the perspective's
 *         icon), and a recurrence chip for repeating reminders.
 *   - Bottom quick-capture — natural-language input ("call mom at
 *     7pm tomorrow"), a mic icon for voice, and a "+" icon that
 *     opens the full editor for complex edits.
 */

package app.releaf.mobile.features.reminder

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import app.releaf.mobile.data.common.SpeechTranscriber
import app.releaf.mobile.data.common.TranscribeResult
import app.releaf.mobile.data.reminder.ReminderParser
import app.releaf.mobile.data.perspective.PerspectiveEntity
import app.releaf.mobile.data.perspective.extractContext
import app.releaf.mobile.data.perspective.stripContext
import app.releaf.mobile.data.reminder.ReminderEntity
import app.releaf.mobile.ui.theme.AppAccent
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppRadius
import app.releaf.mobile.ui.theme.AppSpacing
import app.releaf.mobile.ui.theme.AppTypography
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun RemindersScreen(
    onBack: () -> Unit,
    onComposeNew: () -> Unit,
    onOpenReminder: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RemindersListViewModel = viewModel(factory = RemindersListViewModel.Factory),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    // Ticks every 30s so the countdown + "in N min" labels stay
    // current without manual refresh. Cheap — each tick just
    // re-reads System.currentTimeMillis and triggers a targeted
    // recomposition via derived state reads.
    val nowMs by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(30_000L)
        }
    }

    val postNotifLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { /* Result is observed via the banner check below. */ },
    )
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasPostNotifPermission(context)
        ) {
            postNotifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            // Lift the whole column above the soft keyboard — the
            // quick-capture bar is the last child, so without this
            // the IME covers the lower half of the input pill.
            .imePadding(),
    ) {
        ReminderHeader(onBack = onBack, onComposeNew = onComposeNew)
        PermissionBanner(context = context)

        val isEmpty = state.upcoming.isEmpty() && state.past.isEmpty()
        if (isEmpty) {
            EmptyState(modifier = Modifier.weight(1f))
        } else {
            val next = state.upcoming.firstOrNull()?.takeIf { it.remindAt > nowMs }
            val laterUpcoming = if (next != null)
                state.upcoming.drop(1)
            else state.upcoming

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(
                    start  = AppSpacing.s4,
                    end    = AppSpacing.s4,
                    top    = AppSpacing.s2,
                    bottom = AppSpacing.s4,
                ),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.s2),
            ) {
                if (next != null) {
                    item(key = "hero-${next.id}") {
                        HeroNextUp(
                            reminder    = next,
                            nowMs       = nowMs,
                            perspectives = state.perspectives,
                            onSnooze    = { viewModel.snooze(next.id, 15) },
                            onMarkDone  = { viewModel.markCompleted(next.id) },
                            onOpen      = { onOpenReminder(next.id) },
                        )
                        Spacer(Modifier.height(AppSpacing.s2))
                    }
                }

                val grouped = groupByBucket(laterUpcoming, nowMs)
                for ((bucket, rows) in grouped) {
                    if (rows.isEmpty()) continue
                    item(key = "hdr-${bucket.name}") {
                        SectionHeader(
                            label = bucket.label,
                            count = rows.size,
                        )
                    }
                    items(rows, key = { "row-${it.id}" }) { row ->
                        TimeRailRow(
                            reminder     = row,
                            nowMs        = nowMs,
                            perspectives = state.perspectives,
                            showDate     = bucket != ReminderBucket.Today,
                            onTap        = { onOpenReminder(row.id) },
                            onToggle     = { viewModel.markCompleted(row.id) },
                        )
                    }
                }

                if (state.past.isNotEmpty()) {
                    item(key = "hdr-past") {
                        SectionHeader(label = "PAST", count = state.past.size, muted = true)
                    }
                    items(state.past, key = { "past-${it.id}" }) { row ->
                        TimeRailRow(
                            reminder     = row,
                            nowMs        = nowMs,
                            perspectives = state.perspectives,
                            showDate     = true,
                            pastStyle    = true,
                            onTap        = { onOpenReminder(row.id) },
                            onToggle     = {
                                if (row.completedAt != null) {
                                    viewModel.markActive(row.id)
                                } else {
                                    viewModel.markCompleted(row.id)
                                }
                            },
                        )
                    }
                }
            }
        }

        QuickCaptureBar(
            onCommit     = { text -> viewModel.quickCreate(text) },
            onOpenEditor = onComposeNew,
        )
    }
}

// ================================================================= Header

@Composable
private fun ReminderHeader(onBack: () -> Unit, onComposeNew: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start  = AppSpacing.s3,
                end    = AppSpacing.s3,
                top    = AppSpacing.s3,
                bottom = AppSpacing.s2,
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
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text  = "REMINDERS",
                style = AppTypography.Eyebrow,
                color = AppAccent.primary,
            )
            Text(
                text     = "Up next",
                style    = AppTypography.EditorialTitle,
                color    = AppColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(AppColors.InputBg)
                .clickable(onClick = onComposeNew),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector        = Icons.Filled.Add,
                contentDescription = "New reminder",
                tint               = AppColors.TextPrimary,
                modifier           = Modifier.size(18.dp),
            )
        }
    }
}

// ================================================================= Hero

@Composable
private fun HeroNextUp(
    reminder: ReminderEntity,
    nowMs: Long,
    perspectives: List<PerspectiveEntity>,
    onSnooze: () -> Unit,
    onMarkDone: () -> Unit,
    onOpen: () -> Unit,
) {
    val stripped = stripContext(reminder.title).ifBlank { reminder.title }
    // Prefer the explicit FK; fall back to @tag extraction so rows
    // that pre-date the perspective_id column still tag correctly.
    val fkMatch = reminder.perspectiveId?.let { id -> perspectives.firstOrNull { it.id == id } }
    val tag = fkMatch?.name ?: extractContext(reminder.title)
    val match = fkMatch ?: tag?.let { name -> perspectives.firstOrNull { it.name == name } }
    val countdown = countdownLabel(reminder.remindAt - nowMs)
    val whenLabel = formatAbsolute(reminder.remindAt)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.lg))
            .background(AppColors.CardSolid)
            .border(1.dp, AppColors.BorderDefault, RoundedCornerShape(AppRadius.lg))
            .clickable(onClick = onOpen)
            .padding(AppSpacing.s4),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.s3),
    ) {
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.s1),
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(AppAccent.primary),
            )
            Spacer(Modifier.width(AppSpacing.s1))
            Chip(
                text = countdown,
                fg   = AppAccent.primary,
                bg   = AppAccent.soft,
            )
            if (match != null || tag != null) {
                TagChip(perspective = match, fallbackName = tag)
            }
            if (reminder.recursEveryDays != null) {
                RecurrenceChip(days = reminder.recursEveryDays)
            }
        }
        Column {
            Text(
                text  = stripped,
                style = AppTypography.SectionTitle,
                color = AppColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            reminder.note?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(AppSpacing.s1))
                Text(
                    text  = it,
                    style = AppTypography.Meta,
                    color = AppColors.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(AppSpacing.s2))
            Text(
                text  = whenLabel,
                style = AppTypography.Meta.copy(fontWeight = FontWeight.SemiBold),
                color = AppAccent.primary,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2)) {
            ActionPill(
                text    = "Snooze 15m",
                fg      = AppColors.OnAccent,
                bg      = AppAccent.primary,
                onClick = onSnooze,
                modifier = Modifier.weight(1f),
            )
            ActionPill(
                text    = "Mark done",
                fg      = AppColors.TextPrimary,
                bg      = Color.Transparent,
                bordered = true,
                onClick = onMarkDone,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// ================================================================= Rows

@Composable
private fun SectionHeader(label: String, count: Int, muted: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AppSpacing.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text  = label,
            style = AppTypography.Eyebrow,
            color = if (muted) AppColors.TextTertiary else AppAccent.primary,
        )
        Spacer(Modifier.width(AppSpacing.s2))
        Chip(
            text = count.toString(),
            fg   = if (muted) AppColors.TextTertiary else AppAccent.primary,
            bg   = if (muted) AppColors.InputBg else AppAccent.soft,
        )
        Spacer(Modifier.weight(1f))
        Box(
            modifier = Modifier
                .weight(4f)
                .height(1.dp)
                .background(AppColors.BorderDefault),
        )
    }
}

@Composable
private fun TimeRailRow(
    reminder: ReminderEntity,
    nowMs: Long,
    perspectives: List<PerspectiveEntity>,
    showDate: Boolean,
    pastStyle: Boolean = false,
    onTap: () -> Unit,
    onToggle: () -> Unit,
) {
    val isDone = reminder.completedAt != null
    val stripped = stripContext(reminder.title).ifBlank { reminder.title }
    // Prefer the explicit FK; fall back to @tag extraction so rows
    // that pre-date the perspective_id column still tag correctly.
    val fkMatch = reminder.perspectiveId?.let { id -> perspectives.firstOrNull { it.id == id } }
    val tag = fkMatch?.name ?: extractContext(reminder.title)
    val match = fkMatch ?: tag?.let { name -> perspectives.firstOrNull { it.name == name } }
    val (timeLine1, timeLine2) = timeRailLabels(reminder.remindAt, showDate)

    val fadeAlpha = if (isDone) 0.55f else if (pastStyle) 0.75f else 1f

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.md))
            .background(AppColors.CardSolid)
            .border(1.dp, AppColors.BorderDefault, RoundedCornerShape(AppRadius.md))
            .clickable(onClick = onTap)
            .padding(horizontal = AppSpacing.s3, vertical = AppSpacing.s3),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s3),
    ) {
        Column(
            // 68dp is the sweet spot for mono "9:00 AM" at Button
            // size — narrower clipped "AM" on some phones, wider
            // starved the title column on narrow devices.
            modifier = Modifier.width(68.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text  = timeLine1,
                style = AppTypography.Button.copy(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = if (isDone) AppColors.TextTertiary else AppColors.TextPrimary,
                textDecoration = if (isDone) TextDecoration.LineThrough else null,
            )
            if (timeLine2.isNotEmpty()) {
                Text(
                    text  = timeLine2,
                    style = AppTypography.Tag.copy(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    ),
                    color = AppColors.TextTertiary,
                )
            }
        }
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(32.dp)
                .background(AppColors.BorderDefault),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text  = stripped,
                style = AppTypography.Body.copy(fontWeight = FontWeight.SemiBold),
                color = if (isDone) AppColors.TextTertiary else AppColors.TextPrimary,
                textDecoration = if (isDone) TextDecoration.LineThrough else null,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val hasChips = (match != null || tag != null) || reminder.recursEveryDays != null
            if (hasChips && !isDone) {
                Spacer(Modifier.height(AppSpacing.s1))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (match != null || tag != null) {
                        TagChip(perspective = match, fallbackName = tag)
                    }
                    if (reminder.recursEveryDays != null) {
                        RecurrenceChip(days = reminder.recursEveryDays)
                    }
                }
            }
        }
        Icon(
            imageVector        = if (isDone) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
            contentDescription = if (isDone) "Mark active" else "Mark done",
            tint               = if (isDone) AppAccent.primary else AppColors.TextTertiary,
            modifier           = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .clickable(onClick = onToggle),
        )
    }
}

// ================================================================= Chips

@Composable
private fun Chip(text: String, fg: Color, bg: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(AppRadius.pill))
            .background(bg)
            .padding(horizontal = AppSpacing.s2, vertical = 2.dp),
    ) {
        Text(
            text  = text,
            style = AppTypography.Tag.copy(fontWeight = FontWeight.SemiBold),
            color = fg,
        )
    }
}

@Composable
private fun TagChip(perspective: PerspectiveEntity?, fallbackName: String?) {
    val name = perspective?.name ?: fallbackName ?: return
    val icon = iconForKey(perspective?.iconKey ?: "label")
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(AppRadius.pill))
            .background(AppAccent.soft)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector        = icon,
            contentDescription = null,
            tint               = AppAccent.primary,
            modifier           = Modifier.size(10.dp),
        )
        Spacer(Modifier.width(3.dp))
        Text(
            text  = "@$name",
            style = AppTypography.Tag.copy(fontWeight = FontWeight.SemiBold),
            color = AppAccent.primary,
        )
    }
}

@Composable
private fun RecurrenceChip(days: Int) {
    val label = when (days) {
        1    -> "Daily"
        7    -> "Weekly"
        14   -> "Every 2 wks"
        30   -> "Monthly"
        else -> "Every ${days}d"
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(AppRadius.pill))
            .background(AppColors.InfoSoft)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector        = Icons.Filled.Refresh,
            contentDescription = null,
            tint               = AppColors.Info,
            modifier           = Modifier.size(10.dp),
        )
        Spacer(Modifier.width(3.dp))
        Text(
            text  = label,
            style = AppTypography.Tag.copy(fontWeight = FontWeight.SemiBold),
            color = AppColors.Info,
        )
    }
}

@Composable
private fun ActionPill(
    text: String,
    fg: Color,
    bg: Color,
    onClick: () -> Unit,
    bordered: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(AppRadius.pill))
            .background(bg)
            .then(
                if (bordered) Modifier.border(1.dp, AppColors.BorderDefault, RoundedCornerShape(AppRadius.pill))
                else Modifier,
            )
            .clickable(onClick = onClick)
            .padding(vertical = AppSpacing.s2),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text  = text,
            style = AppTypography.Button,
            color = fg,
        )
    }
}

// ================================================================= Quick-capture

@Composable
private fun QuickCaptureBar(
    onCommit: (String) -> Unit,
    onOpenEditor: () -> Unit,
) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    var text             by remember { mutableStateOf("") }
    var recordingBundle  by remember { mutableStateOf<VoiceCaptureBundle?>(null) }
    var isTranscribing   by remember { mutableStateOf(false) }
    val isRecording = recordingBundle != null

    // One-shot RECORD_AUDIO permission flow. On grant we kick the
    // recorder straight off — matches "hold to talk" expectations
    // without a second tap. On deny, a Toast explains the blocker.
    val micPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            if (granted) {
                recordingBundle = startVoiceCapture(context)
                if (recordingBundle == null) {
                    Toast.makeText(
                        context,
                        "Couldn't start the microphone",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            } else {
                Toast.makeText(
                    context,
                    "Microphone permission is required for voice capture",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        },
    )

    // Tear down the recorder if the screen is disposed (navigation,
    // process death) so we don't leak the mic or the temp file.
    DisposableEffect(Unit) {
        onDispose {
            recordingBundle?.let { b ->
                runCatching { b.recorder.stop() }
                runCatching { b.recorder.reset() }
                runCatching { b.recorder.release() }
                b.outputFile.delete()
            }
        }
    }

    val toggleMic: () -> Unit = {
        val active = recordingBundle
        when {
            active != null -> {
                // Stop + transcribe.
                val stopOk = runCatching { active.recorder.stop() }.isSuccess
                runCatching { active.recorder.reset() }
                runCatching { active.recorder.release() }
                recordingBundle = null
                val file = active.outputFile
                if (stopOk && file.exists() && file.length() > 0L) {
                    isTranscribing = true
                    scope.launch {
                        val result = SpeechTranscriber.transcribe(
                            context = context,
                            fileUri = Uri.fromFile(file).toString(),
                        )
                        isTranscribing = false
                        file.delete()
                        when (result) {
                            is TranscribeResult.Success -> {
                                // Drop a leading "remind me to …" from
                                // the transcript — users naturally say
                                // it, but echoing it back into the
                                // input field is annoying.
                                val spoken = ReminderParser
                                    .stripRemindMePrefix(result.text.trim())
                                    .trim()
                                if (spoken.isNotEmpty()) {
                                    text = if (text.isBlank()) spoken
                                           else "$text $spoken".trim()
                                }
                            }
                            is TranscribeResult.Failure -> {
                                Toast.makeText(
                                    context,
                                    result.reason,
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    }
                } else {
                    file.delete()
                    Toast.makeText(
                        context,
                        "Recording too short — try again",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
            isTranscribing -> {
                // No-op while the last clip is still transcribing —
                // avoids starting a second recording mid-transcribe.
            }
            else -> {
                // Starting a fresh recording — wipe the field so the
                // incoming transcript replaces what was there, rather
                // than appending on top of a previous capture / typed
                // partial.
                text = ""
                val granted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED
                if (granted) {
                    recordingBundle = startVoiceCapture(context)
                    if (recordingBundle == null) {
                        Toast.makeText(
                            context,
                            "Couldn't start the microphone",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                } else {
                    micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }
    }

    val commit = commit@{
        val value = text.trim()
        if (value.isEmpty()) return@commit
        onCommit(value)
        text = ""
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = AppSpacing.s4,
                vertical   = AppSpacing.s3,
            )
            .clip(RoundedCornerShape(AppRadius.pill))
            .background(AppColors.CardSolid)
            .border(1.dp, AppColors.BorderDefault, RoundedCornerShape(AppRadius.pill))
            .padding(horizontal = AppSpacing.s3, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // "+" button commits the text just like the keyboard's ↵.
        // Disabled + muted when the field is empty / locked during
        // recording so a mis-tap can't fire an empty commit. The
        // full-editor entry lives on the header's "+ New" button
        // so this spot can be a pure commit affordance.
        val canCommit = text.isNotBlank() && !isRecording && !isTranscribing
        val plusBg   = if (canCommit) AppAccent.primary else AppAccent.soft
        val plusTint = if (canCommit) AppColors.OnAccent else AppAccent.primary
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(plusBg)
                .clickable(
                    enabled      = canCommit,
                    onClickLabel = "Add reminder",
                ) { commit() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector        = Icons.Filled.Add,
                contentDescription = "Add reminder",
                tint               = plusTint,
                modifier           = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.width(AppSpacing.s2))
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            textStyle = AppTypography.Body.copy(color = AppColors.TextPrimary),
            cursorBrush = SolidColor(AppAccent.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commit() }),
            modifier = Modifier.weight(1f),
            enabled  = !isRecording && !isTranscribing,
            decorationBox = { inner ->
                if (text.isEmpty()) {
                    val hint = when {
                        isRecording    -> "Listening…"
                        isTranscribing -> "Transcribing…"
                        else           -> "Remind me to… try \"call mom at 7pm tomorrow\""
                    }
                    Text(
                        text  = hint,
                        style = AppTypography.Body,
                        color = AppColors.TextTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                inner()
            },
        )
        // Mic button — tap to start, tap again to stop + transcribe.
        // Background flips to danger red while recording, muted
        // during transcription so repeated taps don't stack jobs.
        val micBg = when {
            isRecording    -> AppColors.Danger
            isTranscribing -> AppColors.Muted
            else           -> Color.Transparent
        }
        val micTint = when {
            isRecording    -> AppColors.OnAccent
            isTranscribing -> AppColors.TextTertiary
            else           -> AppAccent.primary
        }
        val micIcon = if (isRecording) Icons.Filled.Stop else Icons.Filled.Mic
        val micLabel = when {
            isRecording    -> "Stop recording"
            isTranscribing -> "Transcribing"
            else           -> "Start voice capture"
        }
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(micBg)
                .clickable(
                    enabled = !isTranscribing,
                    onClickLabel = micLabel,
                ) { toggleMic() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector        = micIcon,
                contentDescription = micLabel,
                tint               = micTint,
                modifier           = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * Handle pair returned by [startVoiceCapture]. The caller keeps it
 * in composable state so the mic toggle can stop + release the
 * recorder and forward the finalised file to [SpeechTranscriber].
 */
private data class VoiceCaptureBundle(
    val recorder: MediaRecorder,
    val outputFile: File,
)

/**
 * Start a `MediaRecorder` capturing AAC-in-MP4 to a temp file in
 * the app's cache dir. Returns null if the recorder can't be
 * configured or prepared — typical causes are a busy mic (another
 * app holding it) or a platform that rejected our codec combo.
 *
 * The file is intentionally written to `cacheDir`, not the
 * attachments directory, because the reminder flow doesn't keep
 * the audio — once the transcript is back we delete the file.
 */
private fun startVoiceCapture(context: Context): VoiceCaptureBundle? {
    val file = File(context.cacheDir, "reminder_capture_${System.currentTimeMillis()}.m4a")
    val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        MediaRecorder(context)
    } else {
        @Suppress("DEPRECATION") MediaRecorder()
    }
    return try {
        rec.setAudioSource(MediaRecorder.AudioSource.MIC)
        rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        // 16 kHz matches what SpeechTranscriber downstreams consume
        // (Whisper + ML Kit both resample internally but feeding the
        // native rate avoids the first-pass resample).
        rec.setAudioSamplingRate(16_000)
        rec.setOutputFile(file.absolutePath)
        rec.prepare()
        rec.start()
        VoiceCaptureBundle(recorder = rec, outputFile = file)
    } catch (t: Throwable) {
        runCatching { rec.release() }
        file.delete()
        null
    }
}

// ================================================================= Empty + banner

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(AppSpacing.s6),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(AppAccent.soft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector        = Icons.Filled.Notifications,
                contentDescription = null,
                tint               = AppAccent.primary,
                modifier           = Modifier.size(36.dp),
            )
        }
        Spacer(Modifier.height(AppSpacing.s3))
        Text(
            "No reminders yet",
            style = AppTypography.SectionTitle,
            color = AppColors.TextPrimary,
        )
        Spacer(Modifier.height(AppSpacing.s2))
        Text(
            "Type at the bottom: \"pay rent on the 1st at 9am\" or \"stand-up daily at 10\".",
            style = AppTypography.Body,
            color = AppColors.TextSecondary,
            modifier = Modifier.padding(horizontal = AppSpacing.s4),
        )
    }
}

@Composable
private fun PermissionBanner(context: Context) {
    val needsPostNotif = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        !hasPostNotifPermission(context)
    val needsExactAlarm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !canScheduleExact(context)
    if (!needsPostNotif && !needsExactAlarm) return

    val label = when {
        needsPostNotif  -> "Allow notifications to hear your reminders."
        needsExactAlarm -> "Grant exact-alarm permission so reminders fire on time."
        else            -> return
    }

    Row(
        modifier = Modifier
            .padding(horizontal = AppSpacing.s4, vertical = AppSpacing.s2)
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.md))
            .background(AppColors.Muted)
            .clickable {
                val intent = if (needsPostNotif) {
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    }
                } else {
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                }
                runCatching { context.startActivity(intent) }
            }
            .padding(AppSpacing.s3),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.s2),
    ) {
        Icon(
            imageVector        = Icons.Filled.NotificationsOff,
            contentDescription = null,
            tint               = AppColors.TextSecondary,
            modifier           = Modifier.size(18.dp),
        )
        Text(
            text     = label,
            style    = AppTypography.Meta,
            color    = AppColors.TextSecondary,
            modifier = Modifier.weight(1f),
        )
    }
}

// ================================================================= Helpers

private enum class ReminderBucket(val label: String) {
    Today("TODAY"),
    Tomorrow("TOMORROW"),
    ThisWeek("THIS WEEK"),
    Later("LATER"),
}

private fun groupByBucket(
    rows: List<ReminderEntity>,
    nowMs: Long,
): List<Pair<ReminderBucket, List<ReminderEntity>>> {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val weekEnd = today.plusDays(7)
    val buckets = linkedMapOf<ReminderBucket, MutableList<ReminderEntity>>(
        ReminderBucket.Today    to mutableListOf(),
        ReminderBucket.Tomorrow to mutableListOf(),
        ReminderBucket.ThisWeek to mutableListOf(),
        ReminderBucket.Later    to mutableListOf(),
    )
    rows.forEach { row ->
        val date = Instant.ofEpochMilli(row.remindAt).atZone(zone).toLocalDate()
        val bucket = when {
            date == today              -> ReminderBucket.Today
            date == today.plusDays(1)  -> ReminderBucket.Tomorrow
            date.isBefore(weekEnd)     -> ReminderBucket.ThisWeek
            else                       -> ReminderBucket.Later
        }
        buckets.getValue(bucket).add(row)
    }
    return buckets.toList()
}

private val timeFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
// Second line for non-Today rows keeps AM/PM so the time is never
// ambiguous ("9:00 AM" vs "9:00 PM"). "a" → AM / PM on the default
// locale; adequate for en_*. We keep the space in front so the two
// parts stay legible on narrow phones.
private val hourFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
private val shortDayMonthFmt = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())

/**
 * Two-line label for the time rail. Line 1 is the dominant read;
 * line 2 is the date when [showDate] is true (Tomorrow / This week /
 * Later / Past bucket), omitted for Today.
 */
private fun timeRailLabels(remindAt: Long, showDate: Boolean): Pair<String, String> {
    val dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(remindAt), ZoneId.systemDefault())
    val time = dt.format(timeFmt)
    return if (!showDate) {
        time to ""
    } else {
        val day = dt.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).uppercase()
        day to dt.format(hourFmt)
    }
}

private fun formatAbsolute(remindAt: Long): String {
    val zone = ZoneId.systemDefault()
    val dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(remindAt), zone)
    val today = LocalDate.now(zone)
    return when (dt.toLocalDate()) {
        today              -> "Today · ${dt.format(timeFmt)}"
        today.plusDays(1)  -> "Tomorrow · ${dt.format(timeFmt)}"
        today.minusDays(1) -> "Yesterday · ${dt.format(timeFmt)}"
        else -> "${dt.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())}, " +
                "${dt.format(shortDayMonthFmt)} · ${dt.format(timeFmt)}"
    }
}

/**
 * Friendly countdown — positive deltas render as "In 42 min" / "In
 * 2 hrs" / "In 3 days"; a reminder that already fired just shows
 * "Now" so the hero doesn't jump past it before the receiver marks
 * it fired.
 */
private fun countdownLabel(deltaMs: Long): String {
    if (deltaMs <= 60_000L) return "Now"
    val minutes = deltaMs / 60_000L
    return when {
        minutes < 60         -> "In ${minutes} min"
        minutes < 60 * 24    -> "In ${minutes / 60} hr" + (if (minutes / 60 != 1L) "s" else "")
        else                 -> "In ${minutes / (60 * 24)} day" + (if (minutes / (60 * 24) != 1L) "s" else "")
    }
}

/** Same mapping the Perspectives view uses for tile icons. */
private fun iconForKey(iconKey: String): ImageVector = when (iconKey) {
    "home"          -> Icons.Filled.Home
    "work"          -> Icons.Filled.Work
    "shopping_cart" -> Icons.Filled.ShoppingCart
    "book"          -> Icons.Filled.Book
    "flight"        -> Icons.Filled.Flight
    "favorite"      -> Icons.Filled.Favorite
    else            -> Icons.AutoMirrored.Filled.Label
}

private fun hasPostNotifPermission(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED
}

private fun canScheduleExact(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
    val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    return am.canScheduleExactAlarms()
}

// Shush unused-import warnings — DayOfWeek is used transitively via
// the getDisplayName extension calls above.
@Suppress("unused")
private val _keepDayOfWeekImport: DayOfWeek = DayOfWeek.MONDAY
