/*
 * ScanReviewScreen.kt
 *
 * Shown after the user finishes a scan. Layout (top → bottom):
 *
 *   1. Big category-button grid  — the user picks a category
 *      (or none) for the in-flight capture. Tap-to-toggle
 *      persists immediately via `controller.setCategory(name)`.
 *   2. Status indicator          — small progress / saved /
 *      failed badge. The hero used to be the progress UI; now
 *      it sits beneath the actionable affordances.
 *   3. Done button                — terminal-state-only.
 *
 * Mirror of iOS `ScanReviewScreen.swift`.
 */

package app.quickink.mobile.features.scan

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowOverflow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Add
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.quickink.mobile.QuickInkApp
import app.quickink.mobile.data.folder.FolderEntity
import app.quickink.mobile.data.folder.FolderRepository
import app.quickink.mobile.data.tag.TagEntity
import app.quickink.mobile.data.tag.TagRepository
import app.quickink.mobile.data.voicenote.VoiceNoteEntity
import app.quickink.mobile.data.voicenote.VoiceNoteRepository
import app.quickink.mobile.data.location.LocationEntity
import app.quickink.mobile.data.person.PersonEntity
import app.quickink.mobile.features.workspace.AutoTagSuggester
import app.quickink.mobile.features.workspace.LocationPickerSheet
import app.quickink.mobile.features.workspace.PeoplePickerSheet
import app.quickink.mobile.features.workspace.normalizeTagName
import app.releaf.mobile.data.common.IsoClock
import app.releaf.mobile.data.common.Uuidv7
import app.quickink.mobile.features.onboarding.OnboardingPrimaryButton
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkRadius
import app.quickink.mobile.ui.theme.QuickInkSpacing
import app.quickink.mobile.ui.theme.quickInkDotGridBackground
import app.releaf.mobile.ui.theme.AppColors
import app.releaf.mobile.ui.theme.AppSpacing
import kotlinx.coroutines.launch
import app.quickink.mobile.ui.theme.QuickInkFonts

@Composable
fun ScanReviewScreen(
    controller: ScanFlowController,
    userId: String,
    onBack: (() -> Unit)? = null,
) {
    val state by controller.state.collectAsState()
    val selectedFolderId by controller.selectedFolderId.collectAsState()
    val selectedPaperSize by controller.selectedPaperSize.collectAsState()
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current

    val context = LocalContext.current
    val app = context.applicationContext as QuickInkApp
    val categoryRepo = remember(app) { TagRepository(app.database.tagDao()) }
    val categories by remember(userId, categoryRepo) {
        categoryRepo.observe(userId)
    }.collectAsState(initial = emptyList())
    // Folder picker — the scan-review primary picker is folders
    // now (the previous "category" grid attached a tag; that
    // surface moved to the post-save tag picker + AI-suggested
    // chips above). Folder writes the capture's `folder_id`
    // through `ScanFlowController.setFolder`.
    val folderRepo = remember(app) { FolderRepository(folderDao = app.database.folderDao()) }
    val folders by remember(userId, folderRepo) {
        folderRepo.observe(userId)
    }.collectAsState(initial = emptyList())

    // People + Places — observe the user's full lists once, plus
    // the capture-scoped join sets, so the chip strips re-render the
    // moment the picker sheets commit. The pickers themselves write
    // the join rows; this screen only reads.
    val personDao         = remember(app) { app.database.personDao() }
    val locationDao       = remember(app) { app.database.locationDao() }
    val capturePersonDao  = remember(app) { app.database.capturePersonDao() }
    val captureLocationDao = remember(app) { app.database.captureLocationDao() }
    val allPeople by remember(userId, personDao) {
        personDao.observeActive(userId)
    }.collectAsState(initial = emptyList())
    val allLocations by remember(userId, locationDao) {
        locationDao.observeActive(userId)
    }.collectAsState(initial = emptyList())

    val isFailed = state is ScanFlowController.State.Failed
    val isRecognizing = state is ScanFlowController.State.Recognizing

    // Workspace v1 Phase E.2 — auto-tag suggestions on the scan
    // review surface (per brief §5). Capture row + OCR rows exist
    // by the time we land here because the controller creates them
    // as each page completes; we read by captureId from state.
    val captureId = when (val s = state) {
        is ScanFlowController.State.Recognizing -> s.captureId
        is ScanFlowController.State.Complete    -> s.captureId
        else                                    -> null
    }
    // Capture-scoped join sets — nil-safe via flowOf(emptyList()) so
    // the strip renders blank until a captureId lands. Re-keyed on
    // captureId so a fresh in-flight capture starts from a clean slate.
    val attachedPersonIds by remember(captureId, capturePersonDao) {
        if (captureId != null) capturePersonDao.observePersonIdsForCapture(captureId)
        else kotlinx.coroutines.flow.flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    val attachedLocationIds by remember(captureId, captureLocationDao) {
        if (captureId != null) captureLocationDao.observeLocationIdsForCapture(captureId)
        else kotlinx.coroutines.flow.flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    val attachedPeople: List<PersonEntity> = remember(attachedPersonIds, allPeople) {
        val byId = allPeople.associateBy { it.id }
        attachedPersonIds.mapNotNull { byId[it] }
    }
    val attachedLocations: List<LocationEntity> = remember(attachedLocationIds, allLocations) {
        val byId = allLocations.associateBy { it.id }
        attachedLocationIds.mapNotNull { byId[it] }
    }
    // Picker-sheet visibility. The sheets handle their own commit on
    // Done; we only flip the flag.
    var showPeoplePicker   by remember(captureId) { mutableStateOf(false) }
    var showLocationPicker by remember(captureId) { mutableStateOf(false) }
    // Locations the user has explicitly detached this session. Guards
    // the place auto-attach effect from re-attaching a place the user
    // just removed because they didn't want it on this scan.
    var dismissedLocationIds by remember(captureId) {
        mutableStateOf<Set<String>>(emptySet())
    }
    // Same guard for the "Me" auto-attach below.
    var dismissedPersonIds by remember(captureId) {
        mutableStateOf<Set<String>>(emptySet())
    }

    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // Every name the suggester has emitted for the in-flight
    // capture, in emit order. The visible "SUGGESTED FROM THIS
    // SCAN" strip is computed below as this list minus
    // `acceptedTagNames`, so unselecting a tag in the TAGS section
    // immediately puts it back without waiting for the suggester to
    // re-emit it (which can fail when other suggestions filled the
    // 4-slot budget). Reset on captureId change.
    val proposedNames = androidx.compose.runtime.remember(captureId) {
        androidx.compose.runtime.mutableStateOf<List<String>>(emptyList())
    }
    // Tags the user has accepted from the suggestions strip during
    // this review session, ordered by accept time. Backs the "TAGS"
    // section below the paper-size row.
    val acceptedTagNames = androidx.compose.runtime.remember(captureId) {
        androidx.compose.runtime.mutableStateOf<List<String>>(emptyList())
    }
    // Names the user has explicitly detached this session. Guards
    // auto-attach: a suggestion that matches an existing tag is
    // auto-attached only if the user hasn't already removed it. Set
    // is independent of `proposedNames` so a name that landed
    // unmatched on an early refresh (when `categories` was still
    // loading) can still auto-attach once the categories flow
    // catches up.
    val dismissedNames = androidx.compose.runtime.remember(captureId) {
        androidx.compose.runtime.mutableStateOf<Set<String>>(emptySet())
    }
    // Add-tag dialog state — opened from the TAGS section's "+ ADD
    // TAG" affordance. Resets the draft on dismiss/confirm.
    val showAddTagDialog = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }
    val newTagDraft = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf("")
    }
    // Voice notes for the in-flight capture. Re-running the suggester
    // when this list changes folds dictation transcripts into the
    // input alongside the first-page OCR text — clips the user
    // recorded on the pre-review pane drive the chip strip once
    // `setTranscription` lands.
    val voiceRepo = remember(app) { VoiceNoteRepository(app.database.voiceNoteDao()) }
    val voiceNotesFlow: kotlinx.coroutines.flow.Flow<List<VoiceNoteEntity>> =
        remember(captureId, voiceRepo) {
            if (captureId != null) voiceRepo.observeForCapture(captureId)
            else kotlinx.coroutines.flow.flowOf(emptyList())
        }
    val voiceNotes by voiceNotesFlow.collectAsState(
        initial = emptyList<VoiceNoteEntity>(),
    )

    // "Me" auto-attach — the seeded "Me" person is silently attached
    // to every new capture so the common case (a scan that's about the
    // user themselves) lands with one less tap. Guarded by
    // dismissedPersonIds so explicit detach wins. Re-keyed on
    // allPeople so a late-arriving people-list still triggers the
    // match (e.g. when the seed runs on first open and the flow lands
    // after this composable mounts).
    androidx.compose.runtime.LaunchedEffect(
        captureId, allPeople.size, attachedPersonIds,
    ) {
        val id = captureId ?: return@LaunchedEffect
        val me = allPeople.firstOrNull { it.name.equals("Me", ignoreCase = true) }
            ?: return@LaunchedEffect
        if (me.id in attachedPersonIds.toSet()) return@LaunchedEffect
        if (me.id in dismissedPersonIds) return@LaunchedEffect
        capturePersonDao.attachPerson(
            joinId    = Uuidv7.generate(),
            captureId = id,
            personId  = me.id,
            source    = "ai-suggested",
            timestamp = IsoClock.nowIso(),
        )
    }

    // Place auto-attach — when the capture has a GPS fix and any
    // existing place falls within 150m, attach it. Guarded by
    // dismissedLocationIds so the user's explicit detach this session
    // wins over the matcher. Re-keyed on allLocations so a place
    // created mid-flow still gets picked up.
    androidx.compose.runtime.LaunchedEffect(
        captureId, allLocations.size, attachedLocationIds,
    ) {
        val id = captureId ?: return@LaunchedEffect
        val capture = app.database.captureDao().findById(id) ?: return@LaunchedEffect
        val lat = capture.latitude ?: return@LaunchedEffect
        val lon = capture.longitude ?: return@LaunchedEffect
        val attachedSet = attachedLocationIds.toSet()
        val results = FloatArray(1)
        for (loc in allLocations) {
            val placeLat = loc.latitude ?: continue
            val placeLon = loc.longitude ?: continue
            if (loc.id in attachedSet) continue
            if (loc.id in dismissedLocationIds) continue
            android.location.Location.distanceBetween(
                lat, lon, placeLat, placeLon, results,
            )
            if (results[0] <= 150f) {
                captureLocationDao.attachLocation(
                    joinId     = Uuidv7.generate(),
                    captureId  = id,
                    locationId = loc.id,
                    source     = "ai-suggested",
                    timestamp  = IsoClock.nowIso(),
                )
            }
        }
    }

    androidx.compose.runtime.LaunchedEffect(captureId, state, categories, voiceNotes) {
        val id = captureId ?: return@LaunchedEffect
        // Wait for at least one page to finish OCR so suggestions
        // have something to fire on.
        val capture = app.database.captureDao().findById(id) ?: return@LaunchedEffect
        val ocrText = app.database.ocrResultDao().findFirstTextForCapture(id)
        // Pre-review voice notes contribute their transcript to the
        // suggester input so dictation drives the chips alongside
        // the OCR text. Concatenated with a newline so the keyword
        // fallback treats both pools as one bag of tokens.
        val voiceText: String = voiceNotes
            .mapNotNull { row -> row.transcription?.takeIf { it.isNotBlank() } }
            .joinToString(separator = "\n")
        val parts: List<String> = listOfNotNull(
            ocrText?.takeIf { it.isNotBlank() },
            voiceText.ifBlank { null },
        )
        val combinedText: String? = if (parts.isEmpty()) null
            else parts.joinToString(separator = "\n")
        val raw = AutoTagSuggester.suggest(
            ocrText           = combinedText,
            existingTagNames  = categories.map { it.name }.toSet(),
            currentlyAttached = acceptedTagNames.value.toSet(),
            captureDateIso    = capture.createdAt,
        )
        // Walk new suggester output:
        //   - Any name that matches an existing tag, isn't already
        //     attached, and the user hasn't explicitly detached this
        //     session → auto-attach silently so the user sees it in
        //     the TAGS section pre-selected. Gating on
        //     `dismissedNames` (rather than "have we seen this name
        //     before?") lets late-arriving categories trigger an
        //     auto-attach on a subsequent refresh: a suggestion that
        //     landed unmatched while `categories` was still loading
        //     can still be promoted later.
        //   - Add every emitted name to `proposedNames` so the
        //     derived strip surfaces it whenever it's not currently
        //     attached. This makes detach light up the chip in the
        //     strip immediately without depending on the suggester
        //     re-emitting it.
        val existingByName = categories.associateBy { it.name }
        val known = proposedNames.value.toSet()
        val updatedProposals = proposedNames.value.toMutableList()
        for (name in raw) {
            if (name !in known) updatedProposals += name
            val tag = existingByName[name]
            if (tag != null &&
                name !in acceptedTagNames.value &&
                name !in dismissedNames.value) {
                app.database.captureTagDao().attachTag(
                    joinId    = Uuidv7.generate(),
                    captureId = id,
                    tagId     = tag.id,
                    source    = "ai-suggested",
                    timestamp = IsoClock.nowIso(),
                )
                acceptedTagNames.value = acceptedTagNames.value + name
            }
        }
        if (updatedProposals.size != proposedNames.value.size) {
            proposedNames.value = updatedProposals
        }
    }

    // Derived strip — anything proposed that isn't currently
    // attached. Updates automatically when the user (de)selects.
    val suggestedTags = androidx.compose.runtime.remember {
        androidx.compose.runtime.derivedStateOf {
            val attached = acceptedTagNames.value.toSet()
            proposedNames.value.filter { it !in attached }
        }
    }

    // Lift the content past the system status bar + add visual
    // breathing room so the category buttons clear the notch on
    // edge-to-edge devices. Same pattern as Library / Settings.
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .quickInkDotGridBackground(),
    ) {
        if (onBack != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = QuickInkSpacing.s4,
                        end   = QuickInkSpacing.s4,
                        top   = statusBarTop + QuickInkSpacing.s4,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .clickable(onClick = onBack)
                        .padding(horizontal = QuickInkSpacing.s2, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector       = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back to voice note",
                        tint              = colors.accentDeep,
                        modifier          = Modifier.size(18.dp),
                    )
                    Text(
                        text  = "Back",
                        style = type.body.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                        color = colors.accentDeep,
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
            }
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(
                    start  = QuickInkSpacing.s5,
                    end    = QuickInkSpacing.s5,
                    top    = if (onBack != null) QuickInkSpacing.s3 else statusBarTop + QuickInkSpacing.s7,
                    bottom = QuickInkSpacing.s5,
                ),
            verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s5),
        ) {
            // Phase E.2 — AI-suggested chip strip above the
            // category grid. Renders only while we have a
            // captureId in flight and the suggester produced hits.
            val cid = captureId
            val suggestions = suggestedTags.value
            if (cid != null && suggestions.isNotEmpty() && !isFailed) {
                ScanReviewSuggestions(
                    names = suggestions,
                    onAccept = { name ->
                        scope.launch {
                            val tag = categoryRepo.findOrCreate(userId, name)
                            val now = IsoClock.nowIso()
                            app.database.captureTagDao().attachTag(
                                joinId    = Uuidv7.generate(),
                                captureId = cid,
                                tagId     = tag.id,
                                source    = "ai-suggested",
                                timestamp = now,
                            )
                            if (name !in acceptedTagNames.value) {
                                acceptedTagNames.value = acceptedTagNames.value + name
                            }
                            // Re-attach by deliberate user action
                            // clears any previous dismissal so future
                            // auto-attach runs (transcript landing,
                            // etc.) treat the name as freshly
                            // desired. Strip self-updates — it's a
                            // derived view over `proposedNames -
                            // acceptedTagNames`, so adding to
                            // acceptedTagNames is enough to make
                            // the chip move to the TAGS section.
                            dismissedNames.value = dismissedNames.value - name
                        }
                    },
                )
            }

            if (folders.isNotEmpty() && !isFailed) {
                FolderButtonsGrid(
                    folders          = folders,
                    selectedFolderId = selectedFolderId,
                    onSelect         = { controller.setFolder(it) },
                )
            }

            if (!isFailed) {
                PaperSizeChipRow(
                    selected = selectedPaperSize,
                    onSelect = { controller.setPaperSize(it) },
                )
            }

            if (!isFailed && cid != null) {
                PeopleSection(
                    all          = allPeople,
                    selectedIds  = attachedPersonIds.toSet(),
                    onAdd        = { showPeoplePicker = true },
                    onToggle     = { person ->
                        scope.launch {
                            if (person.id in attachedPersonIds) {
                                capturePersonDao.detachPerson(
                                    captureId = cid,
                                    personId  = person.id,
                                    timestamp = IsoClock.nowIso(),
                                )
                                // Remember the explicit dismissal so
                                // the "Me" auto-attach doesn't put the
                                // same row back this session.
                                dismissedPersonIds = dismissedPersonIds + person.id
                            } else {
                                capturePersonDao.attachPerson(
                                    joinId    = Uuidv7.generate(),
                                    captureId = cid,
                                    personId  = person.id,
                                    source    = "manual",
                                    timestamp = IsoClock.nowIso(),
                                )
                                dismissedPersonIds = dismissedPersonIds - person.id
                            }
                        }
                    },
                )
                PlacesSection(
                    all          = allLocations,
                    selectedIds  = attachedLocationIds.toSet(),
                    onAdd        = { showLocationPicker = true },
                    onToggle     = { loc ->
                        scope.launch {
                            if (loc.id in attachedLocationIds) {
                                captureLocationDao.detachLocation(
                                    captureId  = cid,
                                    locationId = loc.id,
                                    timestamp  = IsoClock.nowIso(),
                                )
                                // Remember the explicit dismissal so
                                // the auto-attach effect doesn't
                                // reattach the same place behind the
                                // user's back on the next refresh.
                                dismissedLocationIds = dismissedLocationIds + loc.id
                            } else {
                                captureLocationDao.attachLocation(
                                    joinId     = Uuidv7.generate(),
                                    captureId  = cid,
                                    locationId = loc.id,
                                    source     = "manual",
                                    timestamp  = IsoClock.nowIso(),
                                )
                                dismissedLocationIds = dismissedLocationIds - loc.id
                            }
                        }
                    },
                )
            }

            val attached = acceptedTagNames.value
            if (!isFailed && categories.isNotEmpty() && cid != null) {
                TagsSection(
                    tags          = categories,
                    selectedNames = attached.toSet(),
                    onToggle      = { name ->
                        scope.launch {
                            if (name in attached) {
                                val tag = categories.first { it.name == name }
                                app.database.captureTagDao().detachTag(
                                    captureId = cid,
                                    tagId     = tag.id,
                                    timestamp = IsoClock.nowIso(),
                                )
                                acceptedTagNames.value = attached.filter { it != name }
                                // Remember the explicit dismissal so
                                // subsequent suggester runs don't re-
                                // auto-attach behind the user's back.
                                dismissedNames.value = dismissedNames.value + name
                            } else {
                                val tag = categoryRepo.findOrCreate(userId, name)
                                val now = IsoClock.nowIso()
                                app.database.captureTagDao().attachTag(
                                    joinId    = Uuidv7.generate(),
                                    captureId = cid,
                                    tagId     = tag.id,
                                    source    = "manual",
                                    timestamp = now,
                                )
                                acceptedTagNames.value = attached + name
                                dismissedNames.value = dismissedNames.value - name
                            }
                        }
                    },
                    onAddTag      = { showAddTagDialog.value = true },
                )
            }

            StatusIndicator(state = state)
        }

        if (!isRecognizing) {
            OnboardingPrimaryButton(
                label   = "Done",
                onClick = { controller.dismiss() },
            )
            Spacer(Modifier.size(AppSpacing.s5))
        }
    }

    if (showPeoplePicker && captureId != null) {
        PeoplePickerSheet(
            captureId = captureId,
            userId    = userId,
            onDismiss = { showPeoplePicker = false },
        )
    }

    if (showLocationPicker && captureId != null) {
        LocationPickerSheet(
            captureId = captureId,
            userId    = userId,
            onDismiss = { showLocationPicker = false },
        )
    }

    if (showAddTagDialog.value && captureId != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {
                showAddTagDialog.value = false
                newTagDraft.value = ""
            },
            title = { Text("New tag") },
            text = {
                Column {
                    androidx.compose.material3.OutlinedTextField(
                        value         = newTagDraft.value,
                        onValueChange = { newTagDraft.value = it },
                        singleLine    = true,
                        label         = { Text("Tag name") },
                        placeholder   = { Text("e.g. meeting-notes") },
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        text  = "Lowercase, hyphens for spaces.",
                        style = type.meta.copy(fontSize = 11.sp),
                        color = colors.muted,
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    val raw = newTagDraft.value
                    showAddTagDialog.value = false
                    newTagDraft.value = ""
                    scope.launch {
                        val normalized = normalizeTagName(raw)
                        if (normalized.isEmpty()) return@launch
                        val tag = categoryRepo.findOrCreate(userId, normalized)
                        val now = IsoClock.nowIso()
                        app.database.captureTagDao().attachTag(
                            joinId    = Uuidv7.generate(),
                            captureId = captureId,
                            tagId     = tag.id,
                            source    = "manual",
                            timestamp = now,
                        )
                        if (normalized !in acceptedTagNames.value) {
                            acceptedTagNames.value = acceptedTagNames.value + normalized
                        }
                        dismissedNames.value = dismissedNames.value - normalized
                    }
                }) {
                    Text("Add")
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showAddTagDialog.value = false
                    newTagDraft.value = ""
                }) {
                    Text("Cancel")
                }
            },
        )
    }
}

/**
 * Two-column grid of folder buttons. Each button writes the
 * capture's `folder_id` through `ScanFlowController.setFolder`.
 * The selected button paints with the folder's stored color so
 * the picker reads the same as the Workspace home folder list.
 */
@Composable
private fun FolderButtonsGrid(
    folders: List<FolderEntity>,
    selectedFolderId: String?,
    onSelect: (String) -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current

    Column(verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3)) {
        Text(
            text  = "FOLDER",
            style = type.eyebrow,
            color = colors.muted,
        )

        // Manual two-column rows because the surrounding column is
        // a verticalScroll (LazyVerticalGrid + verticalScroll don't
        // compose). Pairs the folder list into rows of 2.
        folders.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3)) {
                pair.forEach { folder ->
                    val selected = folder.id == selectedFolderId
                    Box(modifier = Modifier.weight(1f)) {
                        FolderButton(
                            folder   = folder,
                            selected = selected,
                            onClick  = { onSelect(folder.id) },
                        )
                    }
                }
                if (pair.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun FolderButton(folder: FolderEntity, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current
    // Folder color drives the active fill so the button reads the
    // same as the corresponding folder tile on the Workspace home.
    // Falls back to the accent if the stored hex doesn't parse.
    val folderColor = remember(folder.color) {
        runCatching { Color(android.graphics.Color.parseColor(folder.color)) }
            .getOrDefault(colors.accent)
    }
    val bg          = if (selected) folderColor else colors.surface
    val fg          = if (selected) colors.textOnAccent else colors.ink
    val borderColor = if (selected) folderColor else colors.border
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(QuickInkRadius.md))
            .background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(QuickInkRadius.md))
            .clickable(onClick = onClick)
            .padding(horizontal = QuickInkSpacing.s2, vertical = QuickInkSpacing.s2),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
        ) {
            // Small filled swatch so unselected rows still telegraph
            // their folder color. Hidden on the active row because
            // the button itself is already painted the same hue.
            if (!selected) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(folderColor),
                )
            }
            Text(
                text      = folder.name,
                style     = type.cardTitle,
                color     = fg,
                textAlign = TextAlign.Center,
                maxLines  = 2,
            )
        }
    }
}

/**
 * Four-up chip row letting the user disambiguate the auto-detected
 * paper-size class (A4 / A5 / Letter / Custom). The auto-classifier
 * seeds the selection from the first page's rectified aspect ratio
 * plus the user's last pick — A4 vs A5 can't be told apart from
 * ratio alone (both are 1:√2 by ISO design), so this is the user's
 * escape hatch.
 *
 * `Card` isn't surfaced here because card-shaped captures flow
 * through the dedicated business-card capture surface, which writes
 * `PaperSize.Card` directly without going through this review
 * screen.
 */
@Composable
private fun PaperSizeChipRow(
    selected: PaperSize,
    onSelect: (PaperSize) -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current
    val options = listOf(
        PaperSize.A4     to "A4",
        PaperSize.A5     to "A5",
        PaperSize.Letter to "Letter",
        PaperSize.Card   to "Card",
        PaperSize.Custom to "Custom",
    )
    Column(verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2)) {
        Text(
            text       = "PAPER SIZE",
            style      = type.eyebrow,
            color      = colors.muted,
            fontFamily = QuickInkFonts.ui,
        )
        Row(
            modifier             = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            options.forEach { (size, label) ->
                PaperSizeChip(
                    label    = label,
                    selected = size == selected,
                    onClick  = { onSelect(size) },
                )
            }
        }
    }
}

@Composable
private fun PaperSizeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    Text(
        text       = label,
        fontFamily = QuickInkFonts.ui,
        fontSize   = 12.sp,
        fontWeight = FontWeight.Medium,
        color      = if (selected) colors.textOnAccent else colors.ink,
        modifier   = Modifier
            .clip(PaperSizeChipShape)
            .clickable(onClick = onClick)
            .background(
                color = if (selected) colors.accent else Color.White.copy(alpha = 0.85f),
                shape = PaperSizeChipShape,
            )
            .border(
                width = 1.dp,
                color = if (selected) colors.accent else colors.accent.copy(alpha = 0.25f),
                shape = PaperSizeChipShape,
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

private val PaperSizeChipShape = RoundedCornerShape(percent = 50)

/**
 * All tags in the user's namespace, rendered as toggle chips.
 * Selected (attached) chips paint accent-filled; unselected chips
 * paint outlined. Tap toggles attach/detach against the in-flight
 * capture. Lives below the paper-size chip row so it complements
 * the AI suggestions strip above the folder grid — suggestions
 * surface tags the user might not have thought of, this section
 * lets them pick from their own set.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagsSection(
    tags: List<TagEntity>,
    selectedNames: Set<String>,
    onToggle: (String) -> Unit,
    onAddTag: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current
    Column(verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text       = "TAGS",
                style      = type.eyebrow,
                color      = colors.muted,
                fontFamily = QuickInkFonts.ui,
            )
            Spacer(modifier = Modifier.weight(1f))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .clickable(onClick = onAddTag)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text  = "+",
                    style = type.label.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                    color = colors.accentDeep,
                )
                Spacer(modifier = Modifier.size(3.dp))
                Text(
                    text  = "ADD TAG",
                    style = type.label.copy(
                        fontSize      = 10.sp,
                        fontWeight    = FontWeight.SemiBold,
                        letterSpacing = 1.2.sp,
                    ),
                    color = colors.accentDeep,
                )
            }
        }
        // Selected (currently-attached) chips render first so the
        // user can see which tags this capture has at a glance.
        // Order within each partition matches the underlying
        // creation order so the strip stays stable frame-to-frame.
        val ordered = remember(tags, selectedNames) {
            val selected   = tags.filter { it.name in selectedNames }
            val unselected = tags.filter { it.name !in selectedNames }
            selected + unselected
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement   = Arrangement.spacedBy(6.dp),
        ) {
            ordered.forEach { tag ->
                val selected = tag.name in selectedNames
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(
                            if (selected) colors.accent else Color.White.copy(alpha = 0.85f),
                            RoundedCornerShape(999.dp),
                        )
                        .border(
                            1.dp,
                            if (selected) colors.accent else colors.accent.copy(alpha = 0.25f),
                            RoundedCornerShape(999.dp),
                        )
                        .clickable { onToggle(tag.name) }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text  = "#",
                        style = type.label.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Bold),
                        color = if (selected) {
                            Color.White.copy(alpha = 0.7f)
                        } else {
                            colors.accent.copy(alpha = 0.7f)
                        },
                    )
                    Text(
                        text  = tag.name,
                        style = type.label.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Medium),
                        color = if (selected) colors.textOnAccent else colors.ink,
                        modifier = Modifier.padding(start = 1.dp),
                    )
                }
            }
        }
    }
}

/**
 * Section header + inline toggle chips for every person in the user's
 * namespace. Attached chips paint accent-filled; unattached chips
 * paint outlined. Tap toggles attach/detach. The "+ ADD" header
 * button opens [PeoplePickerSheet] for creating a brand-new person
 * from a typed name when the inline list doesn't have what they want
 * yet. Mirror of [PlacesSection].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PeopleSection(
    all: List<PersonEntity>,
    selectedIds: Set<String>,
    onAdd: () -> Unit,
    onToggle: (PersonEntity) -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current
    Column(verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2)) {
        EntitySectionHeader(label = "PEOPLE", cta = "ADD PERSON", onEdit = onAdd)
        if (all.isEmpty()) {
            Text(
                text       = "No people yet. Tap “Add person” to create one.",
                style      = type.body.copy(fontSize = 12.sp),
                color      = colors.muted,
                fontFamily = QuickInkFonts.ui,
            )
        } else {
            // Selected first so the user sees what's already linked
            // at a glance; within each partition we walk the source
            // list so creation order stays stable frame-to-frame.
            val ordered = remember(all, selectedIds) {
                all.filter { it.id in selectedIds } +
                    all.filter { it.id !in selectedIds }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement   = Arrangement.spacedBy(6.dp),
            ) {
                ordered.forEach { person ->
                    EntityToggleChip(
                        name     = person.name,
                        selected = person.id in selectedIds,
                        onClick  = { onToggle(person) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlacesSection(
    all: List<LocationEntity>,
    selectedIds: Set<String>,
    onAdd: () -> Unit,
    onToggle: (LocationEntity) -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current
    Column(verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2)) {
        EntitySectionHeader(label = "PLACES", cta = "ADD PLACE", onEdit = onAdd)
        if (all.isEmpty()) {
            Text(
                text       = "No places yet. Tap “Add place” to create one.",
                style      = type.body.copy(fontSize = 12.sp),
                color      = colors.muted,
                fontFamily = QuickInkFonts.ui,
            )
        } else {
            val ordered = remember(all, selectedIds) {
                all.filter { it.id in selectedIds } +
                    all.filter { it.id !in selectedIds }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement   = Arrangement.spacedBy(6.dp),
            ) {
                ordered.forEach { loc ->
                    EntityToggleChip(
                        name     = loc.name,
                        selected = loc.id in selectedIds,
                        onClick  = { onToggle(loc) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EntitySectionHeader(
    label: String,
    cta: String,
    onEdit: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current
    Row(
        modifier          = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text       = label,
            style      = type.eyebrow,
            color      = colors.muted,
            fontFamily = QuickInkFonts.ui,
        )
        Spacer(modifier = Modifier.weight(1f))
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .clickable(onClick = onEdit)
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector        = Icons.Outlined.Add,
                contentDescription = null,
                tint               = colors.accentDeep,
                modifier           = Modifier.size(10.dp),
            )
            Spacer(modifier = Modifier.width(3.dp))
            Text(
                text  = cta,
                style = type.label.copy(
                    fontSize      = 10.sp,
                    fontWeight    = FontWeight.SemiBold,
                    letterSpacing = 1.2.sp,
                ),
                color = colors.accentDeep,
            )
        }
    }
}

/**
 * Toggle chip mirroring the TAGS-row chip style so all three inline
 * picker surfaces (tags, people, places) read as one family. Filled-
 * accent when selected, outlined otherwise. Tap is bidirectional —
 * attach if currently detached, detach if currently attached.
 */
@Composable
private fun EntityToggleChip(
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current
    Text(
        text       = name,
        style      = type.label.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Medium),
        color      = if (selected) colors.textOnAccent else colors.ink,
        modifier   = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(
                if (selected) colors.accent else Color.White.copy(alpha = 0.85f),
                RoundedCornerShape(999.dp),
            )
            .border(
                1.dp,
                if (selected) colors.accent else colors.accent.copy(alpha = 0.25f),
                RoundedCornerShape(999.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

@Composable
private fun StatusIndicator(state: ScanFlowController.State) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current

    when (state) {
        is ScanFlowController.State.Idle -> { /* not rendered */ }

        is ScanFlowController.State.Recognizing -> {
            Row(
                modifier            = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment   = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    color    = colors.accent,
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.size(QuickInkSpacing.s2))
                Text(
                    text  = "Recognizing page ${state.completedPages} of ${state.totalPages}",
                    style = type.body,
                    color = colors.inkSoft,
                )
            }
        }

        is ScanFlowController.State.Complete -> {
            Row(
                modifier            = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment   = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector       = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint              = colors.success,
                    modifier          = Modifier.size(20.dp),
                )
                Spacer(Modifier.size(QuickInkSpacing.s2))
                Text(
                    text  = "Saved — text on ${state.successCount} of ${state.totalPages} pages",
                    style = type.body,
                    color = colors.inkSoft,
                )
            }
        }

        is ScanFlowController.State.Failed -> {
            Column(
                modifier             = Modifier.fillMaxWidth().padding(vertical = QuickInkSpacing.s5),
                horizontalAlignment  = Alignment.CenterHorizontally,
                verticalArrangement  = Arrangement.spacedBy(QuickInkSpacing.s2),
            ) {
                Icon(
                    imageVector       = Icons.Filled.Warning,
                    contentDescription = null,
                    tint              = colors.warning,
                    modifier          = Modifier.size(32.dp),
                )
                Text("Couldn't save", style = type.heading, color = colors.ink)
                Text(
                    text     = state.message,
                    style    = type.body,
                    color    = colors.inkSoft,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * Workspace v1 Phase E.2 — AI-suggested tag chips. Surfaces tags
 * inferred from the in-flight capture's OCR text. The user can
 * tap "+#name" to attach immediately (writes to capture_tags with
 * source = "ai-suggested"); rejecting is implicit — chips not
 * tapped are simply discarded when the user leaves the screen.
 *
 * Wraps to multiple rows. By default clipped to 2 rows with a
 * chevron indicator on overflow; tapping the indicator expands to
 * show every chip, tapping again collapses back. The indicator is
 * rendered inline by Compose's [FlowRowOverflow] at the position
 * where the cap kicks in.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScanReviewSuggestions(
    names: List<String>,
    onAccept: (String) -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current
    val cardShape = RoundedCornerShape(QuickInkRadius.md)
    val expanded = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }
    androidx.compose.runtime.LaunchedEffect(names) {
        // Auto-collapse when the list shrinks back below the
        // 2-row cap so the chevron doesn't leave the strip stuck
        // in "expanded" mode after the user accepts everything.
        if (names.size <= 4) expanded.value = false
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(colors.accentSoft.copy(alpha = 0.4f), cardShape)
            .padding(QuickInkSpacing.s3),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text  = "SUGGESTED FROM THIS SCAN",
                style = type.label.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp),
                color = colors.accentDeep,
            )
        }
        Spacer(Modifier.height(QuickInkSpacing.s2))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement   = Arrangement.spacedBy(6.dp),
            maxLines = if (expanded.value) Int.MAX_VALUE else 2,
            overflow = FlowRowOverflow.expandOrCollapseIndicator(
                expandIndicator   = {
                    SuggestionsChevron(
                        label   = "Show all (${names.size})",
                        icon    = Icons.Filled.ExpandMore,
                        onClick = { expanded.value = true },
                    )
                },
                collapseIndicator = {
                    SuggestionsChevron(
                        label   = "Show less",
                        icon    = Icons.Filled.ExpandLess,
                        onClick = { expanded.value = false },
                    )
                },
                minRowsToShowCollapse = 3,
            ),
        ) {
            names.forEach { name ->
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(
                            Color.White.copy(alpha = 0.85f),
                            RoundedCornerShape(999.dp),
                        )
                        .border(
                            1.dp,
                            colors.accent.copy(alpha = 0.25f),
                            RoundedCornerShape(999.dp),
                        )
                        .clickable { onAccept(name) }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text  = "+",
                        style = type.label.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                        color = colors.accentDeep,
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        text  = "#",
                        style = type.label.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Bold),
                        color = colors.accent.copy(alpha = 0.7f),
                    )
                    Text(
                        text  = name,
                        style = type.label.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Medium),
                        color = colors.accentDeep,
                        modifier = Modifier.padding(start = 1.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SuggestionsChevron(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type   = LocalQuickInkTypography.current
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector       = icon,
            contentDescription = label,
            tint              = colors.accentDeep,
            modifier          = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(3.dp))
        Text(
            text  = label,
            style = type.label.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
            color = colors.accentDeep,
        )
    }
}
