/*
 * StoryShareSheet.kt
 *
 * Stories Phase 4 — the share sheet (§7.5 of the v3 mockup). Mirror
 * of iOS `StoryShareSheet.swift`; see that file's header for the
 * ASCII layout + per-action behaviour.
 */

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.quickink.mobile.features.stories

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import app.quickink.mobile.QuickInkApp
import app.quickink.mobile.features.settings.SettingsPreferences
import app.quickink.mobile.data.story.StoryEntity
import app.quickink.mobile.data.story.StoryRepository
import app.quickink.mobile.data.storyitem.StoryItemEntity
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkRadius
import app.quickink.mobile.ui.theme.QuickInkSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun StoryShareSheet(
    storyId: String,
    userId: String,
    onDismiss: () -> Unit,
) {
    val colors  = LocalQuickInkColors.current
    val type    = LocalQuickInkTypography.current
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current

    val vm: StoryEditorViewModel = viewModel(factory = StoryEditorViewModel.factory(storyId, userId))
    val story by vm.story.collectAsState()
    val items by vm.items.collectAsState()

    var rendering by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    var showingPublishConfirm by remember { mutableStateOf(false) }
    var showingUnpublishConfirm by remember { mutableStateOf(false) }
    var publishing by remember { mutableStateOf(false) }
    LaunchedEffect(toast) {
        if (toast != null) { delay(1_800); toast = null }
    }

    val app = remember(context) { context.applicationContext as QuickInkApp }
    val settingsPrefs = remember(context) { SettingsPreferences(context) }
    val publicLinksEnabled = remember(settingsPrefs) { settingsPrefs.experimentalPublicLinksEnabled }
    val repo = remember(app) {
        StoryRepository(
            storyDao          = app.database.storyDao(),
            storyItemDao      = app.database.storyItemDao(),
            storyVoiceClipDao = app.database.storyVoiceClipDao(),
        )
    }

    fun doPublish() {
        val s = story ?: run { toast = "Story not ready — try again."; return }
        publishing = true
        scope.launch {
            try {
                val result = StoryPublisher.publish(s, items)
                repo.markPublished(s.id, result.slug)
                publishing = false
                toast = "Link is live — anyone with it can read."
            } catch (e: Exception) {
                publishing = false
                toast = e.message ?: "Couldn't publish — please try again."
            }
        }
    }

    fun doUnpublish() {
        val s = story ?: return
        publishing = true
        scope.launch {
            try {
                StoryPublisher.unpublish(s)
                repo.markUnpublished(s.id)
                publishing = false
                toast = "Public link removed."
            } catch (e: Exception) {
                publishing = false
                toast = e.message ?: "Couldn't unpublish — please try again."
            }
        }
    }

    val sheetState = rememberModalBottomSheetState()
    val isPublicLinkActive = story?.shareMode == StoryEntity.ShareMode.PUBLIC_LINK.raw
        && !story?.shareSlug.isNullOrEmpty()
    val linkText = "quickink.app/s/${story?.shareSlug ?: "…"}"

    fun runExport(asPdf: Boolean) {
        val s = story ?: run { toast = "Story not ready — try again."; return }
        rendering = true
        scope.launch {
            val file = withContext(Dispatchers.IO) {
                val previewUris = loadPreviewUris(app, userId, items)
                runCatching {
                    if (asPdf) StoryPdfExporter.export(context, s, items, previewUris)
                    else        StoryImageExporter.export(context, s, items, previewUris)
                }.getOrNull()
            }
            rendering = false
            if (file == null) { toast = "Couldn't render — please try again."; return@launch }
            val uri = runCatching {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            }.getOrNull()
            if (uri == null) { toast = "Couldn't expose file for sharing."; return@launch }
            val mimeType = if (asPdf) "application/pdf" else "image/png"
            val intent = Intent(Intent.ACTION_SEND).apply {
                setType(mimeType)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = android.content.ClipData.newRawUri(null, uri)
            }
            try {
                context.startActivity(Intent.createChooser(intent, "Share story"))
            } catch (_: Exception) {
                toast = "Couldn't open the share sheet."
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        containerColor   = colors.surface,
    ) {
        Column(
            modifier = Modifier.padding(
                start  = QuickInkSpacing.s4,
                end    = QuickInkSpacing.s4,
                bottom = QuickInkSpacing.s6,
            ),
        ) {
            Text(
                text  = "Share ${story?.title ?: "story"}",
                style = type.editorial,
                color = colors.ink,
                modifier = Modifier.padding(bottom = QuickInkSpacing.s2),
            )

            // Preview card
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.bg)
                    .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                    .padding(QuickInkSpacing.s2 + 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3),
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp, 56.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(colors.paper1, colors.paper3)
                            )
                        ),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text       = "Public link preview",
                        fontSize   = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color      = colors.ink,
                    )
                    Text(
                        text     = "${items.size} items · cream cover with title overlay · ~${estimatedReadMinutes(items)} min read",
                        style    = type.bodyItalic,
                        color    = colors.inkSoft,
                        fontSize = 11.sp,
                        maxLines = 2,
                    )
                }
                Text(
                    text       = "Change",
                    color      = colors.accent,
                    fontSize   = 10.5.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(modifier = Modifier.height(QuickInkSpacing.s3))

            // Options grid (2×2)
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                verticalArrangement   = Arrangement.spacedBy(9.dp),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                modifier = Modifier.height(220.dp),
            ) {
                items(
                    items = listOf(
                        ShareOption(Icons.Filled.Description, "Save as PDF",  "Editorial layout, ready to print.",   false) { runExport(asPdf = true) },
                        ShareOption(Icons.Filled.Image,       "Save as image", "A tall PNG for chats or stories.",   false) { runExport(asPdf = false) },
                        ShareOption(Icons.Filled.IosShare,    "Share via app", "WhatsApp, Messages, Mail.",          false) { runExport(asPdf = true) },
                        ShareOption(Icons.Filled.Link,        "Public link",
                            if (isPublicLinkActive) "A page anyone can open." else "Generate a public page.",
                            isPublicLinkActive,
                        ) {
                            when {
                                isPublicLinkActive ->
                                    toast = "Public link already live — see the box below."
                                !publicLinksEnabled ->
                                    toast = "Turn on Experimental → Public link sharing in Settings."
                                else ->
                                    showingPublishConfirm = true
                            }
                        },
                    ),
                ) { opt ->
                    OptionCard(opt)
                }
            }
            Spacer(modifier = Modifier.height(QuickInkSpacing.s3))

            if (isPublicLinkActive) {
                LinkBox(
                    linkText = linkText,
                    onCopy   = {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(linkText))
                        toast = "Link copied"
                    },
                    onAddPasscode = { toast = "Passcode protection ships in v1.1." },
                    onStopSharing = { showingUnpublishConfirm = true },
                )
            }
            if (rendering || publishing) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = QuickInkSpacing.s2),
                ) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    Text(
                        text  = if (publishing) "Publishing…" else "Rendering…",
                        style = type.bodyItalic,
                        color = colors.inkSoft,
                    )
                }
            }
            if (toast != null) {
                Text(toast!!, style = type.bodyItalic, color = colors.inkSoft,
                    modifier = Modifier.padding(top = QuickInkSpacing.s2))
            }
        }
    }

    if (showingPublishConfirm) {
        AlertDialog(
            onDismissRequest = { showingPublishConfirm = false },
            title   = { Text("Publish this story?") },
            text    = { Text("Anyone with the link will be able to read this story. You can stop sharing at any time.") },
            confirmButton = {
                TextButton(onClick = {
                    showingPublishConfirm = false
                    doPublish()
                }) { Text("Publish") }
            },
            dismissButton = {
                TextButton(onClick = { showingPublishConfirm = false }) { Text("Cancel") }
            },
        )
    }

    if (showingUnpublishConfirm) {
        AlertDialog(
            onDismissRequest = { showingUnpublishConfirm = false },
            title   = { Text("Stop sharing this story?") },
            text    = { Text("The public link will go offline. People who saved the URL won't be able to open it anymore.") },
            confirmButton = {
                TextButton(onClick = {
                    showingUnpublishConfirm = false
                    doUnpublish()
                }) { Text("Stop sharing") }
            },
            dismissButton = {
                TextButton(onClick = { showingUnpublishConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

private data class ShareOption(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val active: Boolean,
    val onClick: () -> Unit,
)

@Composable
private fun OptionCard(opt: ShareOption) {
    val colors = LocalQuickInkColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.bg)
            .border(
                width = 1.dp,
                color = if (opt.active) colors.accent else colors.border,
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(onClick = opt.onClick)
            .padding(11.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (opt.active) colors.accent else colors.borderSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector        = opt.icon,
                contentDescription = null,
                tint               = if (opt.active) colors.textOnAccent else colors.inkSoft,
                modifier           = Modifier.size(14.dp),
            )
        }
        Text(
            text       = opt.title,
            fontSize   = 12.sp,
            fontWeight = FontWeight.Medium,
            color      = colors.ink,
        )
        Text(
            text     = opt.subtitle,
            fontSize = 10.5.sp,
            color    = colors.inkSoft,
            maxLines = 2,
        )
    }
}

@Composable
private fun LinkBox(
    linkText: String,
    onCopy: () -> Unit,
    onAddPasscode: () -> Unit,
    onStopSharing: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.borderSoft)
            .border(
                width = 1.dp,
                color = colors.accent,
                shape = RoundedCornerShape(10.dp),
            )
            .padding(QuickInkSpacing.s2 + 2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text     = linkText,
                fontSize = 11.sp,
                color    = colors.inkSoft,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            Text(
                text       = "Copy",
                color      = colors.accent,
                fontSize   = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier   = Modifier.clickable(onClick = onCopy),
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(colors.accent.copy(alpha = 0.4f)))
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text     = "require a passcode",
                style    = type.bodyItalic,
                color    = colors.inkSoft,
                fontSize = 10.5.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                text       = "+ add",
                color      = colors.accent,
                fontSize   = 10.5.sp,
                fontWeight = FontWeight.Medium,
                modifier   = Modifier.clickable(onClick = onAddPasscode),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            Text(
                text       = "Stop sharing",
                color      = colors.accent,
                fontSize   = 10.5.sp,
                fontWeight = FontWeight.Medium,
                modifier   = Modifier.clickable(onClick = onStopSharing),
            )
        }
    }
}

/** Look up `preview_uri` for every capture-backed item in the
 *  story so the exporters can embed real bitmaps. One DB read total,
 *  even for stories with many items. */
private suspend fun loadPreviewUris(
    app: app.quickink.mobile.QuickInkApp,
    userId: String,
    items: List<StoryItemEntity>,
): Map<String, String> {
    val refIds = items.mapNotNullTo(mutableSetOf()) { item ->
        when (item.kind) {
            StoryItemEntity.Kind.PHOTO.raw,
            StoryItemEntity.Kind.DOCUMENT.raw,
            StoryItemEntity.Kind.NOTE.raw -> item.refId
            else -> null
        }
    }
    if (refIds.isEmpty()) return emptyMap()
    val rows = app.database.captureDao().activeRows(userId)
    val out = mutableMapOf<String, String>()
    for (row in rows) {
        if (row.id in refIds && !row.previewUri.isNullOrEmpty()) {
            out[row.id] = row.previewUri
        }
    }
    return out
}

private fun estimatedReadMinutes(items: List<StoryItemEntity>): Int {
    val words = items.sumOf { (it.text ?: it.caption ?: "").split(Regex("\\s+")).filter { w -> w.isNotEmpty() }.size }
    val photoBonus = items.size / 8
    return maxOf(1, (words / 180) + photoBonus)
}
