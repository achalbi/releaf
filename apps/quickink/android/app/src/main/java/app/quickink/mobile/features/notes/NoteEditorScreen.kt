/*
 * NoteEditorScreen.kt
 *
 * QuickInk's Note Detail — upgraded from the slim title+body editor
 * to the mockup's tabbed view:
 *
 *   - Tab 1 (Page): lined-paper page rendering with coral left
 *     margin; handwritten Caveat preview of the note body. When
 *     a real captured image is wired through, the image overlays
 *     the paper rule.
 *
 *   - Tab 2 (OCR Text): clean editorial serif rendering of the
 *     transcript with a confidence badge, copy-to-clipboard button,
 *     and a "smart suggestions" footer hook (currently inert).
 *
 *   - Floating action bar (Re-tag / Export / Delete).
 *
 * Architecturally still wraps `NoteEditorController` — controller
 * fields `title` and `notes` remain the data source.
 *
 * Mirror of iOS `NoteEditorScreen.swift`.
 */

package app.quickink.mobile.features.notes

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.quickink.mobile.QuickInkApp
import app.quickink.mobile.data.sync.QuickInkSyncScheduler
import app.quickink.mobile.features.settings.SettingsPreferences
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkRadius
import app.quickink.mobile.ui.theme.QuickInkSpacing
import app.quickink.mobile.ui.theme.quickInkDotGridBackground
import app.quickink.mobile.ui.theme.quickInkLinedPaper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class DetailTab { Page, OCR }

@Composable
fun NoteEditorScreen(
    entryId: String,
    userId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as QuickInkApp
    val scope = rememberCoroutineScope()
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current

    val controller = remember(entryId) {
        NoteEditorController(
            entryId   = entryId,
            userId    = userId,
            dao       = app.database.notepadDao(),
            scope     = scope,
            // Sync is user-initiated only — no auto-kick on note
            // edits. User taps Settings → "Sync now" to push.
            onMutated = { /* intentional no-op */ },
        )
    }

    LaunchedEffect(controller) {
        controller.bootstrap()
    }

    var activeTab by remember { mutableStateOf(DetailTab.Page) }
    var copyToastVisible by remember { mutableStateOf(false) }
    var showExportSheet by remember { mutableStateOf(false) }
    val searchablePdfEnabled = remember {
        SettingsPreferences(context).searchablePdfExportEnabled
    }

    Box(modifier = Modifier.fillMaxSize().quickInkDotGridBackground()) {
        Column(modifier = Modifier.fillMaxSize()) {
            DetailTopBar(
                title    = controller.title,
                date     = controller.entryDate,
                onBack   = {
                    if (controller.canSave) controller.save()
                    onBack()
                },
            )

            TabSwitcher(
                active   = activeTab,
                onSelect = { activeTab = it },
                modifier = Modifier
                    .padding(horizontal = QuickInkSpacing.s5)
                    .padding(top = QuickInkSpacing.s3, bottom = QuickInkSpacing.s2),
            )

            if (controller.isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = colors.accent)
                }
            } else {
                when (activeTab) {
                    DetailTab.Page -> PageTab(controller = controller)
                    DetailTab.OCR  -> OcrTab(
                        controller = controller,
                        onCopy     = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("OCR", controller.notes))
                            copyToastVisible = true
                            scope.launch {
                                delay(1600)
                                copyToastVisible = false
                            }
                        },
                    )
                }
            }
        }

        FloatingActionBar(
            showDelete = controller.entry != null,
            onReTag    = { /* T13 */ },
            onExport   = { showExportSheet = true },
            onDelete   = { controller.delete(onDeleted = onBack) },
            modifier   = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = QuickInkSpacing.s5)
                .padding(bottom = QuickInkSpacing.s5),
        )

        if (showExportSheet) {
            ExportSheet(
                searchablePdfEnabled = searchablePdfEnabled,
                onSelect = { format ->
                    showExportSheet = false
                    // Format-specific export pipeline lands in a
                    // follow-up. Currently a no-op — the picker
                    // surfaces intent without generating the file.
                    @Suppress("UNUSED_EXPRESSION") format
                },
                onDismiss = { showExportSheet = false },
            )
        }

        AnimatedVisibility(
            visible = copyToastVisible,
            enter   = fadeIn() + slideInVertically(initialOffsetY  = { it / 2 }),
            exit    = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 100.dp),
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(QuickInkRadius.pill))
                    .background(colors.ink)
                    .padding(horizontal = QuickInkSpacing.s4, vertical = QuickInkSpacing.s2),
            ) {
                Text(
                    text  = "Copied to clipboard",
                    style = type.label,
                    color = colors.textOnAccent,
                )
            }
        }
    }
}

// MARK: - Top bar

@Composable
private fun DetailTopBar(
    title: String,
    date: String,
    onBack: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = QuickInkSpacing.s2, vertical = QuickInkSpacing.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector       = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint              = colors.ink,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text     = title.ifEmpty { "Untitled" },
                style    = type.label,
                color    = if (title.isEmpty()) colors.muted else colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(text = date, style = type.caption, color = colors.muted)
        }
        // Page navigator pill — placeholder showing 1/1.
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(QuickInkRadius.pill))
                .background(colors.borderSoft)
                .padding(horizontal = QuickInkSpacing.s3, vertical = 4.dp),
        ) {
            Text(text = "1 / 1", style = type.caption, color = colors.inkSoft)
        }
        Spacer(Modifier.size(QuickInkSpacing.s3))
    }
}

// MARK: - Tab switcher

@Composable
private fun TabSwitcher(
    active: DetailTab,
    onSelect: (DetailTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalQuickInkColors.current

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(QuickInkRadius.pill))
            .background(colors.borderSoft)
            .padding(4.dp),
    ) {
        TabPill(label = "Page",     isActive = active == DetailTab.Page) { onSelect(DetailTab.Page) }
        Spacer(Modifier.size(4.dp))
        TabPill(label = "OCR Text", isActive = active == DetailTab.OCR)  { onSelect(DetailTab.OCR) }
    }
}

@Composable
private fun TabPill(label: String, isActive: Boolean, onClick: () -> Unit) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(QuickInkRadius.pill))
            .background(if (isActive) colors.surface else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = QuickInkSpacing.s4, vertical = QuickInkSpacing.s2),
    ) {
        Text(
            text  = label,
            style = type.label,
            color = if (isActive) colors.ink else colors.inkSoft,
        )
    }
}

// MARK: - Page tab

@Composable
private fun PageTab(controller: NoteEditorController) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(QuickInkSpacing.s5)
            .padding(bottom = 100.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 480.dp)
                .shadow(8.dp, RoundedCornerShape(QuickInkRadius.lg))
                .clip(RoundedCornerShape(QuickInkRadius.lg))
                .background(colors.surface)
                .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.lg)),
        ) {
            // Lined paper rule lines.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .quickInkLinedPaper(seed = controller.entryDate.hashCode(), lineSpacing = 22.dp, lineOpacity = 0.10f),
            )
            // Coral left margin line.
            Box(
                modifier = Modifier
                    .padding(start = 36.dp, top = 12.dp, bottom = 12.dp)
                    .width(1.5.dp)
                    .fillMaxSize()
                    .background(colors.accent.copy(alpha = 0.6f)),
            )
            // Content rendered in handwritten Caveat.
            Column(
                modifier = Modifier
                    .padding(start = 52.dp, end = QuickInkSpacing.s5, top = QuickInkSpacing.s5, bottom = QuickInkSpacing.s5),
                verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3),
            ) {
                if (controller.title.isNotEmpty()) {
                    Text(
                        text  = controller.title,
                        style = type.handwritten.copy(fontSize = 28.sp),
                        color = colors.ink,
                    )
                }
                Text(
                    text = if (controller.notes.isEmpty()) "Tap OCR Text to add transcript content." else controller.notes,
                    style = type.handwritten.copy(fontSize = 20.sp),
                    color = colors.ink.copy(alpha = 0.85f),
                )
            }
        }
    }
}

// MARK: - OCR tab

@Composable
private fun OcrTab(controller: NoteEditorController, onCopy: () -> Unit) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = QuickInkSpacing.s5)
            .padding(top = QuickInkSpacing.s3, bottom = 100.dp),
        verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s4),
    ) {
        // Confidence + copy header.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(QuickInkRadius.pill))
                    .background(colors.borderSoft)
                    .padding(horizontal = QuickInkSpacing.s3, vertical = QuickInkSpacing.s1),
                horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s1),
            ) {
                Box(
                    modifier = Modifier.size(6.dp).clip(CircleShape).background(colors.success),
                )
                Text(text = "98% confidence", style = type.caption, color = colors.inkSoft)
            }

            Spacer(Modifier.weight(1f))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(QuickInkRadius.pill))
                    .background(colors.accentSoft)
                    .clickable(onClick = onCopy)
                    .padding(horizontal = QuickInkSpacing.s3, vertical = QuickInkSpacing.s1),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector       = Icons.Filled.ContentCopy,
                    contentDescription = null,
                    tint              = colors.accent,
                    modifier          = Modifier.size(13.dp),
                )
                Text(text = "Copy", style = type.label, color = colors.accent)
            }
        }

        // Title.
        BasicTextField(
            value         = controller.title,
            onValueChange = { controller.title = it },
            textStyle     = type.pageTitle.copy(color = colors.ink),
            cursorBrush   = SolidColor(colors.accent),
            singleLine    = true,
            modifier      = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                if (controller.title.isEmpty()) {
                    Text(text = "Title", style = type.pageTitle, color = colors.muted)
                }
                inner()
            },
        )

        // Body / OCR transcript.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(QuickInkRadius.md))
                .background(colors.surface)
                .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.md))
                .padding(QuickInkSpacing.s4),
        ) {
            BasicTextField(
                value         = controller.notes,
                onValueChange = { controller.notes = it },
                textStyle     = type.body.copy(color = colors.ink),
                cursorBrush   = SolidColor(colors.accent),
                modifier      = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 240.dp),
                decorationBox = { inner ->
                    if (controller.notes.isEmpty()) {
                        Text(text = "Start typing…", style = type.body, color = colors.muted)
                    }
                    inner()
                },
            )
        }

        // Smart suggestions footer placeholder.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(QuickInkRadius.md))
                .background(colors.accentSoft)
                .padding(QuickInkSpacing.s4),
            verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
        ) {
            Text(text = "SMART SUGGESTIONS", style = type.eyebrow, color = colors.muted)
            Text(
                text  = "Coming soon — extracted to-dos, dates, and contacts from this page.",
                style = type.bodyItalic,
                color = colors.inkSoft,
            )
        }
    }
}

// MARK: - Floating action bar

@Composable
private fun FloatingActionBar(
    showDelete: Boolean,
    onReTag: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalQuickInkColors.current
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(QuickInkRadius.pill))
            .shadow(12.dp, RoundedCornerShape(QuickInkRadius.pill))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.pill))
            .padding(QuickInkSpacing.s2),
        horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
        verticalAlignment     = Alignment.CenterVertically,
    ) {
        ActionButton(icon = Icons.Filled.LocalOffer, label = "Re-tag", onClick = onReTag)
        ActionButton(icon = Icons.Filled.IosShare,   label = "Export", onClick = onExport)
        if (showDelete) {
            ActionButton(icon = Icons.Filled.Delete, label = "Delete", tint = colors.danger, onClick = onDelete)
        }
    }
}

@Composable
private fun ActionButton(
    icon: ImageVector,
    label: String,
    tint: Color = LocalQuickInkColors.current.ink,
    onClick: () -> Unit,
) {
    val type = LocalQuickInkTypography.current
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(QuickInkRadius.pill))
            .clickable(onClick = onClick)
            .padding(horizontal = QuickInkSpacing.s3, vertical = QuickInkSpacing.s2),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector       = icon,
            contentDescription = null,
            tint              = tint,
            modifier          = Modifier.size(14.dp),
        )
        Text(text = label, style = type.label, color = tint)
    }
}
