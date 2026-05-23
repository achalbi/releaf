/*
 * MomentsScreen.kt
 *
 * Visual memory layer for QuickInk photos and videos. Unlike the
 * Workspace tab, this surface is content-first: search, smart
 * retrieval, and a grouped timeline of media thumbnails.
 */

@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package app.quickink.mobile.features.moments

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.quickink.mobile.QuickInkApp
import app.quickink.mobile.data.capture.CaptureEntity
import app.quickink.mobile.data.capture.CaptureRepository
import app.quickink.mobile.data.capture.displayTitle
import app.quickink.mobile.data.folder.FolderEntity
import app.quickink.mobile.data.folder.FolderRepository
import app.quickink.mobile.data.location.LocationEntity
import app.quickink.mobile.data.person.PersonEntity
import app.quickink.mobile.data.smartcollection.SmartCollectionEntity
import app.quickink.mobile.data.tag.TagEntity
import app.quickink.mobile.features.nav.QuickInkBottomNavReservedHeight
import app.quickink.mobile.features.workspace.FolderEditorDialog
import app.quickink.mobile.features.workspace.FolderEditorMode
import app.quickink.mobile.features.workspace.WorkspaceFolderPalette
import app.quickink.mobile.features.workspace.workspaceTagBuckets
import app.quickink.mobile.ui.theme.LocalQuickInkColors
import app.quickink.mobile.ui.theme.LocalQuickInkTypography
import app.quickink.mobile.ui.theme.QuickInkRadius
import app.quickink.mobile.ui.theme.QuickInkSpacing
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val GalleryTileGap = 6.dp

@Composable
fun MomentsScreen(
    userId: String,
    onOpenCapture: (String) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenFolder: (FolderEntity) -> Unit,
    onOpenSmartCollection: (SmartCollectionEntity) -> Unit,
    onOpenTagLibrary: () -> Unit,
    onOpenTag: (TagEntity) -> Unit,
    onOpenLocation: (LocationEntity) -> Unit,
    onOpenPerson: (PersonEntity) -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val context = LocalContext.current
    val app = remember(context) { context.applicationContext as QuickInkApp }
    val scope = rememberCoroutineScope()

    val captureDao = remember(app) { app.database.captureDao() }
    val captureRepository = remember(app, captureDao) {
        CaptureRepository(
            captureDao = captureDao,
            ocrResultDao = app.database.ocrResultDao(),
        )
    }
    val folderRepository = remember(app) {
        FolderRepository(folderDao = app.database.folderDao())
    }
    val captures by remember(userId, captureDao) {
        captureDao.observeRecentMedia(userId, limit = 120)
    }.collectAsState(initial = emptyList())

    val folders by produceState(initialValue = emptyList<FolderEntity>(), key1 = userId) {
        app.database.folderDao().observeActive(userId).collect { value = it }
    }

    val primaryTagRows by remember(userId, app) {
        app.database.captureTagDao().observePrimaryTagNames(userId)
    }.collectAsState(initial = emptyList())
    val primaryTagByCapture = remember(primaryTagRows) {
        primaryTagRows.associate { it.captureId to it.tagName }
    }

    val tags by produceState(initialValue = emptyList<TagEntity>(), key1 = userId) {
        app.database.tagDao().observeActive(userId).collect { value = it }
    }
    val tagCounts by produceState(initialValue = emptyMap<String, Int>(), key1 = userId) {
        app.database.captureTagDao().observeTagCounts(userId).collect { rows ->
            value = rows.associate { it.tagId to it.docCount }
        }
    }
    val smartCollections by produceState(
        initialValue = emptyList<SmartCollectionEntity>(),
        key1 = userId,
    ) {
        app.database.smartCollectionDao().observeActive(userId).collect { value = it }
    }
    val locations by produceState(initialValue = emptyList<LocationEntity>(), key1 = userId) {
        app.database.locationDao().observeActive(userId).collect { value = it }
    }
    val locationCounts by produceState(initialValue = emptyMap<String, Int>(), key1 = userId) {
        app.database.captureLocationDao().observeLocationCounts(userId).collect { rows ->
            value = rows.associate { it.locationId to it.docCount }
        }
    }
    val people by produceState(initialValue = emptyList<PersonEntity>(), key1 = userId) {
        app.database.personDao().observeActive(userId).collect { value = it }
    }
    val personCounts by produceState(initialValue = emptyMap<String, Int>(), key1 = userId) {
        app.database.capturePersonDao().observePersonCounts(userId).collect { rows ->
            value = rows.associate { it.personId to it.docCount }
        }
    }
    val tagIdsByCapture by produceState(initialValue = emptyMap<String, Set<String>>(), key1 = userId) {
        app.database.captureTagDao().observeCaptureTagIds(userId).collect { rows ->
            value = rows
                .groupBy({ it.captureId }, { it.tagId })
                .mapValues { (_, tagIds) -> tagIds.toSet() }
        }
    }
    val personIdsByCapture by produceState(initialValue = emptyMap<String, Set<String>>(), key1 = userId) {
        app.database.capturePersonDao().observeCapturePersonIds(userId).collect { rows ->
            value = rows
                .groupBy({ it.captureId }, { it.personId })
                .mapValues { (_, personIds) -> personIds.toSet() }
        }
    }
    val locationIdsByCapture by produceState(initialValue = emptyMap<String, Set<String>>(), key1 = userId) {
        app.database.captureLocationDao().observeCaptureLocationIds(userId).collect { rows ->
            value = rows
                .groupBy({ it.captureId }, { it.locationId })
                .mapValues { (_, locationIds) -> locationIds.toSet() }
        }
    }

    var selectedFilters by remember { mutableStateOf<Set<MomentFilter>>(emptySet()) }
    var selectedTagIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedPersonIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedLocationIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showFilters by remember { mutableStateOf(false) }
    var activeFilterPicker by remember { mutableStateOf<MomentFilterPicker?>(null) }
    var openGalleryGroupKey by remember { mutableStateOf<String?>(null) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var isVoiceSearchListening by remember { mutableStateOf(false) }
    var selectedTab by rememberSaveable { mutableStateOf(MomentHomeTab.Timeline) }
    var showCreateAlbumDialog by remember { mutableStateOf(false) }
    val speechRecognizer = remember(context) {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            SpeechRecognizer.createSpeechRecognizer(context)
        } else {
            null
        }
    }

    fun applyVoiceSearchTranscript(text: String?) {
        val clean = text?.trim().orEmpty()
        if (clean.isNotEmpty()) searchQuery = clean
    }

    fun startVoiceSearch() {
        val recognizer = speechRecognizer
        if (recognizer == null) {
            Toast.makeText(context, "Voice search isn't available on this device.", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Search moments")
        }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                isVoiceSearchListening = true
            }

            override fun onBeginningOfSpeech() {
                isVoiceSearchListening = true
            }

            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                isVoiceSearchListening = false
            }

            override fun onError(error: Int) {
                isVoiceSearchListening = false
                if (error != SpeechRecognizer.ERROR_NO_MATCH &&
                    error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                ) {
                    Toast.makeText(context, "Voice search couldn't start.", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResults(results: Bundle?) {
                isVoiceSearchListening = false
                applyVoiceSearchTranscript(results.firstRecognitionText())
            }

            override fun onPartialResults(partialResults: Bundle?) {
                applyVoiceSearchTranscript(partialResults.firstRecognitionText())
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        runCatching {
            isVoiceSearchListening = true
            recognizer.startListening(intent)
        }.onFailure {
            isVoiceSearchListening = false
            Toast.makeText(context, "Voice search couldn't start.", Toast.LENGTH_SHORT).show()
        }
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startVoiceSearch()
        } else {
            Toast.makeText(context, "Microphone permission is needed for voice search.", Toast.LENGTH_SHORT).show()
        }
    }

    val onVoiceSearchClick = {
        val recognizer = speechRecognizer
        if (isVoiceSearchListening) {
            recognizer?.stopListening()
            isVoiceSearchListening = false
        } else if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        ) {
            startVoiceSearch()
        } else {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    DisposableEffect(speechRecognizer) {
        onDispose {
            speechRecognizer?.destroy()
        }
    }

    fun toggleFilter(filter: MomentFilter) {
        selectedFilters = if (filter in selectedFilters) {
            selectedFilters - filter
        } else {
            selectedFilters + filter
        }
    }

    fun clearAllFilters() {
        selectedFilters = emptySet()
        selectedTagIds = emptySet()
        selectedPersonIds = emptySet()
        selectedLocationIds = emptySet()
    }

    fun togglePickerOption(picker: MomentFilterPicker, id: String) {
        when (picker) {
            MomentFilterPicker.Tags -> selectedTagIds = selectedTagIds.toggle(id)
            MomentFilterPicker.People -> selectedPersonIds = selectedPersonIds.toggle(id)
            MomentFilterPicker.Places -> selectedLocationIds = selectedLocationIds.toggle(id)
        }
    }

    val tagById = remember(tags) { tags.associateBy { it.id } }
    val personById = remember(people) { people.associateBy { it.id } }
    val locationById = remember(locations) { locations.associateBy { it.id } }
    val selectedOptionChips = remember(
        selectedTagIds,
        selectedPersonIds,
        selectedLocationIds,
        tagById,
        personById,
        locationById,
    ) {
        buildList {
            selectedTagIds.forEach { id ->
                add(
                    SelectedMomentOptionChip(
                        id = "tag-$id",
                        picker = MomentFilterPicker.Tags,
                        optionId = id,
                        label = "#${tagById[id]?.name ?: "Tag"}",
                    ),
                )
            }
            selectedPersonIds.forEach { id ->
                add(
                    SelectedMomentOptionChip(
                        id = "person-$id",
                        picker = MomentFilterPicker.People,
                        optionId = id,
                        label = personById[id]?.name ?: "Person",
                    ),
                )
            }
            selectedLocationIds.forEach { id ->
                add(
                    SelectedMomentOptionChip(
                        id = "place-$id",
                        picker = MomentFilterPicker.Places,
                        optionId = id,
                        label = locationById[id]?.name ?: "Place",
                    ),
                )
            }
        }
    }
    val visibleCaptures = remember(
        captures,
        selectedFilters,
        selectedTagIds,
        selectedPersonIds,
        selectedLocationIds,
        searchQuery,
        primaryTagByCapture,
        tagIdsByCapture,
        personIdsByCapture,
        locationIdsByCapture,
    ) {
        val filtered = captures.filter { capture ->
            capture.matchesMomentFilters(
                selected = selectedFilters,
                selectedTagIds = selectedTagIds,
                selectedPersonIds = selectedPersonIds,
                selectedLocationIds = selectedLocationIds,
                tagIdsByCapture = tagIdsByCapture,
                personIdsByCapture = personIdsByCapture,
                locationIdsByCapture = locationIdsByCapture,
            )
        }
        val searched = filtered.filter { capture ->
            capture.matchesMomentSearch(searchQuery, primaryTagByCapture[capture.id])
        }
        searched.sortedByDescending { it.createdAt }
    }
    val groups = remember(visibleCaptures) { visibleCaptures.groupedByMomentDay() }
    val albums = remember(folders) {
        folders.filter { !it.isDefault && !it.isSeeded }
    }
    val mediaCountsByFolder = remember(captures) {
        captures.mapNotNull { capture ->
            capture.folderId?.takeIf { it.isNotBlank() }
        }.groupingBy { it }.eachCount()
    }
    val albumCoverByFolder = remember(captures) {
        captures.mapNotNull { capture ->
            capture.folderId?.takeIf { it.isNotBlank() }?.let { folderId -> folderId to capture }
        }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, folderCaptures) -> folderCaptures.first() }
    }
    val videoCount = remember(captures) { captures.count { it.mediaKind == MediaKind.Video } }
    val photoCount = remember(captures) { captures.count { it.mediaKind == MediaKind.Photo } }
    val openGalleryGroup = remember(groups, openGalleryGroupKey) {
        groups.firstOrNull { it.key == openGalleryGroupKey }
    }

    if (openGalleryGroup != null) {
        MomentGalleryDetailScreen(
            group = openGalleryGroup,
            primaryTagByCapture = primaryTagByCapture,
            onBack = { openGalleryGroupKey = null },
            onOpenCapture = onOpenCapture,
            onToggleFavorite = { capture ->
                scope.launch {
                    captureRepository.setFavorite(capture.id, !capture.isFavorite)
                    app.refreshPendingPushState()
                }
            },
        )
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg),
        contentPadding = PaddingValues(
            start = QuickInkSpacing.s4,
            end = QuickInkSpacing.s4,
            top = QuickInkSpacing.s3,
            bottom = QuickInkBottomNavReservedHeight,
        ),
        verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s5),
    ) {
        item {
            MomentsHeader(
                totalCount = captures.size,
                photoCount = photoCount,
                videoCount = videoCount,
                searchQuery = searchQuery,
                onSearchQueryChange = { searchQuery = it },
                isVoiceSearchListening = isVoiceSearchListening,
                onVoiceSearchClick = onVoiceSearchClick,
                onToggleFilters = { showFilters = !showFilters },
            )
        }

        if (showFilters) {
            item {
                FilterPanel(
                    selectedFilters = selectedFilters,
                    selectedTagCount = selectedTagIds.size,
                    selectedPersonCount = selectedPersonIds.size,
                    selectedPlaceCount = selectedLocationIds.size,
                    selectedOptionChips = selectedOptionChips,
                    onToggleFilter = ::toggleFilter,
                    onOpenPicker = { activeFilterPicker = it },
                    onRemoveOption = { picker, optionId ->
                        togglePickerOption(picker, optionId)
                    },
                )
            }
        }

        item {
            QuickAccessRow(
                selectedTab = selectedTab,
                albumCount = albums.size,
                smartCount = smartCollections.size,
                onSelectTab = { selectedTab = it },
            )
        }

        when (selectedTab) {
            MomentHomeTab.Timeline -> {
                if (captures.isEmpty()) {
                    item { EmptyMoments(onSearch = onOpenSearch) }
                } else if (visibleCaptures.isEmpty()) {
                    item {
                        EmptyFilteredState(onClear = {
                            clearAllFilters()
                            searchQuery = ""
                        })
                    }
                } else {
                    items(groups, key = { it.key }) { group ->
                        TimelineGroup(
                            group = group,
                            primaryTagByCapture = primaryTagByCapture,
                            onOpenCapture = onOpenCapture,
                            onOpenGallery = { openGalleryGroupKey = group.key },
                        )
                    }
                }
            }
            MomentHomeTab.Albums -> {
                item {
                    AlbumsTab(
                        albums = albums,
                        countsByFolder = mediaCountsByFolder,
                        coverByFolder = albumCoverByFolder,
                        onCreateAlbum = { showCreateAlbumDialog = true },
                        onOpenAlbum = onOpenFolder,
                    )
                }
            }
            MomentHomeTab.SmartCollections -> {
                item {
                    SmartCollectionsTab(
                        collections = smartCollections,
                        onOpenCollection = onOpenSmartCollection,
                    )
                }
            }
        }
    }

    activeFilterPicker?.let { picker ->
        val options = when (picker) {
            MomentFilterPicker.Tags -> tags.map { tag ->
                val bucketId = tag.bucket ?: inferMomentTagBucketId(tag.name)
                val bucket = workspaceTagBuckets.firstOrNull { it.id == bucketId }
                MomentFilterOption(
                    id = tag.id,
                    label = tag.name,
                    subtitle = "${tagCounts[tag.id] ?: 0} moments",
                    color = tag.color?.let(::parseColorOrNull) ?: bucket?.hue,
                    icon = Icons.Outlined.Tag,
                    bucketId = bucketId,
                )
            }
            MomentFilterPicker.People -> people.map { person ->
                MomentFilterOption(
                    id = person.id,
                    label = person.name,
                    subtitle = "${personCounts[person.id] ?: 0} moments",
                    color = person.color?.let(::parseColorOrNull),
                    icon = Icons.Outlined.Person,
                    bucketId = null,
                )
            }
            MomentFilterPicker.Places -> locations.map { location ->
                MomentFilterOption(
                    id = location.id,
                    label = location.name,
                    subtitle = "${locationCounts[location.id] ?: 0} moments",
                    color = location.color?.let(::parseColorOrNull),
                    icon = Icons.Outlined.LocationOn,
                    bucketId = null,
                )
            }
        }
        val selectedIds = when (picker) {
            MomentFilterPicker.Tags -> selectedTagIds
            MomentFilterPicker.People -> selectedPersonIds
            MomentFilterPicker.Places -> selectedLocationIds
        }
        MomentFilterPickerSheet(
            picker = picker,
            options = options,
            selectedIds = selectedIds,
            onToggle = { optionId -> togglePickerOption(picker, optionId) },
            onDismiss = { activeFilterPicker = null },
        )
    }

    if (showCreateAlbumDialog) {
        FolderEditorDialog(
            mode = FolderEditorMode.Create,
            initialName = "",
            initialColor = WorkspaceFolderPalette.first(),
            onDismiss = { showCreateAlbumDialog = false },
            onSubmit = { name, color ->
                scope.launch {
                    folderRepository.create(
                        userId = userId,
                        name = name,
                        color = color,
                        position = folders.size,
                    )
                    showCreateAlbumDialog = false
                }
            },
        )
    }
}

@Composable
private fun MomentsHeader(
    totalCount: Int,
    photoCount: Int,
    videoCount: Int,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    isVoiceSearchListening: Boolean,
    onVoiceSearchClick: () -> Unit,
    onToggleFilters: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current

    Column(verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Moments",
                    style = type.display.copy(fontSize = 30.sp),
                    color = colors.ink,
                )
                Text(
                    text = if (totalCount == 0) {
                        "Photos and videos, beautifully organized"
                    } else {
                        "$totalCount memories · $photoCount photos · $videoCount videos"
                    },
                    style = type.body.copy(fontSize = 13.sp),
                    color = colors.inkSoft,
                )
            }
            IconButtonCircle(
                icon = Icons.Outlined.AutoAwesome,
                label = "Smart memory highlights",
                background = colors.surface,
                tint = colors.accent,
                onClick = {},
            )
        }

        val searchShape = RoundedCornerShape(QuickInkRadius.md)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(searchShape)
                .background(colors.surface, searchShape)
                .border(1.dp, colors.border, searchShape)
                .padding(horizontal = QuickInkSpacing.s3, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = null,
                    tint = colors.muted,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(QuickInkSpacing.s2))
                BasicTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    singleLine = true,
                    textStyle = type.body.copy(fontSize = 13.sp, color = colors.ink),
                    modifier = Modifier.weight(1f),
                    decorationBox = { innerTextField ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (searchQuery.isBlank()) {
                                Text(
                                    text = if (isVoiceSearchListening) {
                                        "Listening..."
                                    } else {
                                        "Search photos, places, people, tags..."
                                    },
                                    style = type.body.copy(fontSize = 13.sp),
                                    color = colors.muted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            innerTextField()
                        }
                    },
                )
            }
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(18.dp)
                    .background(colors.border),
            )
            Spacer(modifier = Modifier.width(QuickInkSpacing.s2))
            Icon(
                imageVector = Icons.Outlined.Mic,
                contentDescription = null,
                tint = if (isVoiceSearchListening) colors.accent else colors.inkSoft,
                modifier = Modifier
                    .size(16.dp)
                    .clickable(onClick = onVoiceSearchClick)
                    .semantics {
                        contentDescription = if (isVoiceSearchListening) {
                            "Stop voice search"
                        } else {
                            "Start voice search"
                        }
                    },
            )
            Spacer(modifier = Modifier.width(QuickInkSpacing.s2))
            Icon(
                imageVector = Icons.Outlined.Tune,
                contentDescription = null,
                tint = colors.inkSoft,
                modifier = Modifier
                    .size(16.dp)
                    .clickable(onClick = onToggleFilters)
                    .semantics { contentDescription = "Show moment filters" },
            )
        }
    }
}

@Composable
private fun FilterPanel(
    selectedFilters: Set<MomentFilter>,
    selectedTagCount: Int,
    selectedPersonCount: Int,
    selectedPlaceCount: Int,
    selectedOptionChips: List<SelectedMomentOptionChip>,
    onToggleFilter: (MomentFilter) -> Unit,
    onOpenPicker: (MomentFilterPicker) -> Unit,
    onRemoveOption: (MomentFilterPicker, String) -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(QuickInkRadius.lg))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.lg))
            .padding(QuickInkSpacing.s4),
        verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3),
    ) {
        Text(text = "Filter", style = type.label, color = colors.ink)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
            verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
        ) {
            MomentFilter.values().forEach { filter ->
                val selected = when (filter) {
                    MomentFilter.Tags -> selectedTagCount > 0
                    MomentFilter.People -> selectedPersonCount > 0
                    MomentFilter.Places -> selectedPlaceCount > 0
                    else -> filter in selectedFilters
                }
                val label = when (filter) {
                    MomentFilter.Tags -> filter.labelWithCount(selectedTagCount)
                    MomentFilter.People -> filter.labelWithCount(selectedPersonCount)
                    MomentFilter.Places -> filter.labelWithCount(selectedPlaceCount)
                    else -> filter.label
                }
                FilterPill(
                    label = label,
                    selected = selected,
                    onClick = {
                        when (filter) {
                            MomentFilter.Tags -> onOpenPicker(MomentFilterPicker.Tags)
                            MomentFilter.People -> onOpenPicker(MomentFilterPicker.People)
                            MomentFilter.Places -> onOpenPicker(MomentFilterPicker.Places)
                            else -> onToggleFilter(filter)
                        }
                    },
                )
            }
        }
        if (selectedOptionChips.isNotEmpty()) {
            Text(
                text = "Selected",
                style = type.caption.copy(fontSize = 11.sp),
                color = colors.muted,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
                verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
            ) {
                selectedOptionChips.forEach { chip ->
                    SelectedFilterChip(
                        label = chip.label,
                        onRemove = { onRemoveOption(chip.picker, chip.optionId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickAccessRow(
    selectedTab: MomentHomeTab,
    albumCount: Int,
    smartCount: Int,
    onSelectTab: (MomentHomeTab) -> Unit,
) {
    val items = listOf(
        QuickAccessSpec("Timeline", "All memories", Icons.Outlined.CalendarToday, selectedTab == MomentHomeTab.Timeline) {
            onSelectTab(MomentHomeTab.Timeline)
        },
        QuickAccessSpec(
            "Albums",
            if (albumCount == 1) "1 album" else "$albumCount albums",
            Icons.Outlined.Folder,
            selectedTab == MomentHomeTab.Albums,
        ) {
            onSelectTab(MomentHomeTab.Albums)
        },
        QuickAccessSpec(
            "Smart collections",
            if (smartCount == 1) "1 collection" else "$smartCount collections",
            Icons.Outlined.AutoAwesome,
            selectedTab == MomentHomeTab.SmartCollections,
        ) {
            onSelectTab(MomentHomeTab.SmartCollections)
        },
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
    ) {
        items.forEach { item ->
            QuickAccessCard(item = item, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun QuickAccessCard(item: QuickAccessSpec, modifier: Modifier = Modifier) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    val bg = if (item.active) colors.accentSoft else colors.surface
    val tint = if (item.active) colors.accent else colors.ink
    Column(
        modifier = modifier
            .height(94.dp)
            .clip(RoundedCornerShape(QuickInkRadius.md))
            .background(bg)
            .border(
                1.dp,
                if (item.active) colors.accent.copy(alpha = 0.28f) else colors.border,
                RoundedCornerShape(QuickInkRadius.md),
            )
            .clickable(onClick = item.onClick)
            .padding(QuickInkSpacing.s3),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = if (item.title == "Smart collections") "Smart\ncollections" else item.title,
                style = type.label.copy(fontSize = 12.sp, lineHeight = 13.sp),
                color = tint,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.caption,
                style = type.caption.copy(fontSize = 10.sp),
                color = colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AlbumsTab(
    albums: List<FolderEntity>,
    countsByFolder: Map<String, Int>,
    coverByFolder: Map<String, CaptureEntity>,
    onCreateAlbum: () -> Unit,
    onOpenAlbum: (FolderEntity) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3)) {
        SectionHeader(
            title = "Albums",
            subtitle = if (albums.isEmpty()) "Create curated sets for your favorite moments"
            else "${albums.size} curated ${if (albums.size == 1) "set" else "sets"}",
        )
        if (albums.isEmpty()) {
            CreateAlbumHero(onClick = onCreateAlbum)
        } else {
            val tiles: List<FolderEntity?> = listOf(null) + albums
            tiles.chunked(2).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3),
                ) {
                    row.forEach { album ->
                        if (album == null) {
                            AddAlbumTile(
                                onClick = onCreateAlbum,
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            AlbumTile(
                                album = album,
                                itemCount = countsByFolder[album.id] ?: 0,
                                cover = coverByFolder[album.id],
                                onClick = { onOpenAlbum(album) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    if (row.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun SmartCollectionsTab(
    collections: List<SmartCollectionEntity>,
    onOpenCollection: (SmartCollectionEntity) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3)) {
        SectionHeader(
            title = "Smart collections",
            subtitle = if (collections.isEmpty()) "AI-built sets will appear here"
            else "${collections.size} automatic ${if (collections.size == 1) "collection" else "collections"}",
        )
        if (collections.isEmpty()) {
            SmartCollectionsEmptyCard()
        } else {
            collections.chunked(2).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3),
                ) {
                    row.forEach { collection ->
                        SmartCollectionTile(
                            collection = collection,
                            onClick = { onOpenCollection(collection) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (row.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    subtitle: String,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = title,
            style = type.label.copy(fontSize = 15.sp),
            color = colors.ink,
        )
        Text(
            text = subtitle,
            style = type.caption.copy(fontSize = 12.sp),
            color = colors.inkSoft,
        )
    }
}

@Composable
private fun CreateAlbumHero(onClick: () -> Unit) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(QuickInkRadius.lg))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.lg))
            .clickable(onClick = onClick)
            .padding(QuickInkSpacing.s4),
        horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(QuickInkRadius.md))
                .background(colors.accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Add,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(24.dp),
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = "Create your first album",
                style = type.label.copy(fontSize = 15.sp),
                color = colors.ink,
            )
            Text(
                text = "Collect photos and videos into a focused memory set.",
                style = type.caption.copy(fontSize = 12.sp),
                color = colors.inkSoft,
            )
        }
    }
}

@Composable
private fun AddAlbumTile(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    Column(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(QuickInkRadius.lg))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.lg))
            .clickable(onClick = onClick)
            .padding(QuickInkSpacing.s3),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(colors.accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Add,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(modifier = Modifier.height(QuickInkSpacing.s3))
        Text(
            text = "New album",
            style = type.label.copy(fontSize = 13.sp),
            color = colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AlbumTile(
    album: FolderEntity,
    itemCount: Int,
    cover: CaptureEntity?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    val accent = parseColorOrNull(album.color) ?: colors.accent
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(QuickInkRadius.lg))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.lg))
            .clickable(onClick = onClick),
    ) {
        if (cover != null) {
            MomentPreview(
                capture = cover,
                modifier = Modifier.matchParentSize(),
            )
        } else {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.linearGradient(
                            listOf(
                                accent.copy(alpha = 0.30f),
                                colors.surface,
                            ),
                        ),
                    ),
            )
        }
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = if (cover != null) 0.48f else 0.10f),
                        ),
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(QuickInkSpacing.s3),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = album.name,
                style = type.label.copy(fontSize = 13.sp),
                color = if (cover != null) Color.White else colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (itemCount == 1) "1 item" else "$itemCount items",
                style = type.caption.copy(fontSize = 11.sp),
                color = if (cover != null) Color.White.copy(alpha = 0.82f) else colors.inkSoft,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SmartCollectionTile(
    collection: SmartCollectionEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    val accent = collection.color?.let(::parseColorOrNull) ?: colors.accent
    Column(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(QuickInkRadius.lg))
            .background(
                Brush.linearGradient(
                    listOf(
                        accent.copy(alpha = 0.18f),
                        colors.surface,
                    ),
                ),
            )
            .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.lg))
            .clickable(onClick = onClick)
            .padding(QuickInkSpacing.s3),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(QuickInkRadius.md))
                .background(accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.AutoAwesome,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(22.dp),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = collection.name,
                style = type.label.copy(fontSize = 13.sp),
                color = colors.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "Smart album",
                style = type.caption.copy(fontSize = 11.sp),
                color = colors.inkSoft,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun SmartCollectionsEmptyCard() {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(QuickInkRadius.lg))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.lg))
            .padding(QuickInkSpacing.s4),
        horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(QuickInkRadius.md))
                .background(colors.accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.AutoAwesome,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(24.dp),
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = "No smart collections yet",
                style = type.label.copy(fontSize = 15.sp),
                color = colors.ink,
            )
            Text(
                text = "Automatic sets will appear as Moments learns from your media.",
                style = type.caption.copy(fontSize = 12.sp),
                color = colors.inkSoft,
            )
        }
    }
}

@Composable
private fun TimelineGroup(
    group: MomentGroup,
    primaryTagByCapture: Map<String, String>,
    onOpenCapture: (String) -> Unit,
    onOpenGallery: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = group.title,
                    style = LocalQuickInkTypography.current.heading,
                    color = LocalQuickInkColors.current.ink,
                )
                Text(
                    text = group.subtitle,
                    style = LocalQuickInkTypography.current.caption.copy(fontSize = 11.sp),
                    color = LocalQuickInkColors.current.muted,
                )
            }
            Text(
                text = "${group.items.size} items →",
                modifier = Modifier
                    .clip(RoundedCornerShape(QuickInkRadius.lg))
                    .clickable(onClick = onOpenGallery)
                    .padding(horizontal = QuickInkSpacing.s2, vertical = QuickInkSpacing.s1)
                    .semantics {
                        contentDescription = "Open ${group.title} gallery with ${group.items.size} items"
                    },
                style = LocalQuickInkTypography.current.caption.copy(fontSize = 11.sp),
                color = LocalQuickInkColors.current.accent,
            )
        }

        AirbnbMomentCollage(
            captures = group.items,
            primaryTagByCapture = primaryTagByCapture,
            onOpenCapture = onOpenCapture,
            onOpenGallery = onOpenGallery,
        )
    }
}

@Composable
private fun MomentGalleryDetailScreen(
    group: MomentGroup,
    primaryTagByCapture: Map<String, String>,
    onBack: () -> Unit,
    onOpenCapture: (String) -> Unit,
    onToggleFavorite: (CaptureEntity) -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    val blocks = remember(group.items) { group.items.toGalleryMosaicBlocks() }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg),
        contentPadding = PaddingValues(
            start = QuickInkSpacing.s4,
            end = QuickInkSpacing.s4,
            top = QuickInkSpacing.s3,
            bottom = QuickInkBottomNavReservedHeight,
        ),
        verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(colors.surface)
                        .border(1.dp, colors.border, CircleShape)
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back to Moments",
                        tint = colors.ink,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(modifier = Modifier.width(QuickInkSpacing.s3))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = group.title,
                        style = type.heading,
                        color = colors.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${group.items.size} photos & videos · ${group.subtitle}",
                        style = type.caption.copy(fontSize = 11.sp),
                        color = colors.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                AirbnbActionGlyph(icon = Icons.Outlined.Share, label = "Share gallery")
            }
        }

        item {
            GalleryMosaicView(
                blocks = blocks,
                primaryTagByCapture = primaryTagByCapture,
                onOpenCapture = onOpenCapture,
                onToggleFavorite = onToggleFavorite,
            )
        }
    }
}

@Composable
private fun GalleryMosaicView(
    blocks: List<GalleryMosaicBlock>,
    primaryTagByCapture: Map<String, String>,
    onOpenCapture: (String) -> Unit,
    onToggleFavorite: (CaptureEntity) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(GalleryTileGap),
    ) {
        blocks.forEach { block ->
            GalleryMosaicBlockView(
                block = block,
                primaryTagByCapture = primaryTagByCapture,
                onOpenCapture = onOpenCapture,
                onToggleFavorite = onToggleFavorite,
            )
        }
    }
}

@Composable
private fun GalleryMosaicBlockView(
    block: GalleryMosaicBlock,
    primaryTagByCapture: Map<String, String>,
    onOpenCapture: (String) -> Unit,
    onToggleFavorite: (CaptureEntity) -> Unit,
) {
    when (block.type) {
        GalleryMosaicBlockType.Wide,
        GalleryMosaicBlockType.Single -> {
            val capture = block.items.firstOrNull() ?: return
            FocusedGalleryTile(
                capture = capture,
                primaryTagName = primaryTagByCapture[capture.id],
                metaLabel = focusedGalleryMetaLabel(capture),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (block.type == GalleryMosaicBlockType.Wide) 232.dp else 208.dp),
                onClick = { onOpenCapture(capture.id) },
                onToggleFavorite = { onToggleFavorite(capture) },
            )
        }

        GalleryMosaicBlockType.Pair -> {
            Row(horizontalArrangement = Arrangement.spacedBy(GalleryTileGap)) {
                block.items.forEach { capture ->
                    FocusedGalleryTile(
                        capture = capture,
                        primaryTagName = primaryTagByCapture[capture.id],
                        metaLabel = focusedGalleryMetaLabel(capture),
                        modifier = Modifier
                            .weight(1f)
                            .height(180.dp),
                        onClick = { onOpenCapture(capture.id) },
                        onToggleFavorite = { onToggleFavorite(capture) },
                    )
                }
                if (block.items.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }

        GalleryMosaicBlockType.TallStack -> {
            val lead = block.items.firstOrNull() ?: return
            val stacked = block.items.drop(1)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp),
                horizontalArrangement = Arrangement.spacedBy(GalleryTileGap),
            ) {
                FocusedGalleryTile(
                    capture = lead,
                    primaryTagName = primaryTagByCapture[lead.id],
                    metaLabel = focusedGalleryMetaLabel(lead),
                    modifier = Modifier
                        .weight(1.08f)
                        .fillMaxSize(),
                    onClick = { onOpenCapture(lead.id) },
                    onToggleFavorite = { onToggleFavorite(lead) },
                )
                Column(
                    modifier = Modifier
                        .weight(0.92f)
                        .fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(GalleryTileGap),
                ) {
                    stacked.forEach { capture ->
                        FocusedGalleryTile(
                            capture = capture,
                            primaryTagName = primaryTagByCapture[capture.id],
                            metaLabel = focusedGalleryMetaLabel(capture),
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            onClick = { onOpenCapture(capture.id) },
                            onToggleFavorite = { onToggleFavorite(capture) },
                        )
                    }
                    if (stacked.size == 1) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .focusedGalleryTileChrome(),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FocusedGalleryTile(
    capture: CaptureEntity,
    primaryTagName: String?,
    metaLabel: String?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val title = capture.displayTitle(primaryTagName, fallback = sourceLabel(capture))

    Box(
        modifier = modifier
            .focusedGalleryTileChrome()
            .combinedClickable(onClick = onClick, onLongClick = onClick)
            .semantics { contentDescription = "Open ${capture.mediaKind.accessibilityLabel} $title" },
        contentAlignment = Alignment.Center,
    ) {
        MomentPreview(capture = capture, modifier = Modifier.fillMaxSize())
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Transparent,
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.26f),
                        ),
                    ),
                ),
        )
        if (capture.mediaKind == MediaKind.Video) {
            FocusedGalleryPlayBadge(modifier = Modifier.align(Alignment.TopEnd))
        }
        FocusedGalleryFavoriteButton(
            isFavorite = capture.isFavorite,
            onClick = onToggleFavorite,
            modifier = Modifier.align(Alignment.TopStart),
        )
        if (metaLabel != null) {
            FocusedGalleryMetaPill(
                label = metaLabel,
                modifier = Modifier.align(Alignment.BottomStart),
            )
        }
    }
}

@Composable
private fun FocusedGalleryFavoriteButton(
    isFavorite: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalQuickInkColors.current
    Box(
        modifier = modifier
            .padding(QuickInkSpacing.s2)
            .size(30.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = if (isFavorite) 0.58f else 0.42f))
            .border(1.dp, Color.White.copy(alpha = 0.58f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
            contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
            tint = if (isFavorite) colors.accent else Color.White,
            modifier = Modifier.size(15.dp),
        )
    }
}

@Composable
private fun FocusedGalleryPlayBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(QuickInkSpacing.s2)
            .size(26.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.42f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = "Video",
            tint = Color.White,
            modifier = Modifier.size(12.dp),
        )
    }
}

@Composable
private fun FocusedGalleryMetaPill(label: String, modifier: Modifier = Modifier) {
    Text(
        text = label,
        style = LocalQuickInkTypography.current.label.copy(fontSize = 10.sp),
        color = Color.White,
        modifier = modifier
            .padding(6.dp)
            .clip(RoundedCornerShape(QuickInkRadius.pill))
            .background(Color.Black.copy(alpha = 0.38f))
            .border(1.dp, Color.White.copy(alpha = 0.72f), RoundedCornerShape(QuickInkRadius.pill))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

private fun Modifier.focusedGalleryTileChrome(): Modifier {
    val shape = RoundedCornerShape(QuickInkRadius.lg)
    return this
        .shadow(5.dp, shape, clip = false)
        .clip(shape)
        .background(Color(0xFFEAE7E0))
        .border(1.dp, Color.White.copy(alpha = 0.88f), shape)
}

private fun focusedGalleryMetaLabel(capture: CaptureEntity): String? =
    if (capture.mediaKind == MediaKind.Video) "Video" else null

@Composable
private fun AirbnbMomentCollage(
    captures: List<CaptureEntity>,
    primaryTagByCapture: Map<String, String>,
    onOpenCapture: (String) -> Unit,
    onOpenGallery: () -> Unit,
) {
    if (captures.isEmpty()) return

    val colors = LocalQuickInkColors.current
    val shape = RoundedCornerShape(QuickInkRadius.xl)
    val visible = captures.take(6)
    val hiddenCount = (captures.size - visible.size).coerceAtLeast(0)
    val secondRow = visible.drop(1).take(2)
    val lowerRow = visible.drop(3).take(3)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, colors.border.copy(alpha = 0.45f), shape)
            .semantics { contentDescription = "Moment gallery with ${captures.size} items" },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (visible.size == 1) {
            val capture = visible.first()
            AirbnbHeroTile(
                capture = capture,
                primaryTagName = primaryTagByCapture[capture.id],
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
                onClick = { onOpenCapture(capture.id) },
            )
            return@Column
        }

        if (visible.size == 2) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                visible.forEach { capture ->
                    AirbnbGalleryTile(
                        capture = capture,
                        primaryTagName = primaryTagByCapture[capture.id],
                        modifier = Modifier
                            .weight(1f)
                            .height(172.dp),
                        onClick = { onOpenCapture(capture.id) },
                    )
                }
            }
            return@Column
        }

        AirbnbHeroTile(
            capture = visible.first(),
            primaryTagName = primaryTagByCapture[visible.first().id],
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp),
            onClick = { onOpenCapture(visible.first().id) },
        )

        if (secondRow.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                secondRow.forEach { capture ->
                    AirbnbGalleryTile(
                        capture = capture,
                        primaryTagName = primaryTagByCapture[capture.id],
                        modifier = Modifier
                            .weight(1f)
                            .height(128.dp),
                        onClick = { onOpenCapture(capture.id) },
                    )
                }
                if (secondRow.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }

        if (lowerRow.isNotEmpty()) {
            when (lowerRow.size) {
                1 -> {
                    val capture = lowerRow.first()
                    AirbnbGalleryTile(
                        capture = capture,
                        primaryTagName = primaryTagByCapture[capture.id],
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(168.dp),
                        onClick = { onOpenCapture(capture.id) },
                    )
                }

                2 -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        lowerRow.forEach { capture ->
                            AirbnbGalleryTile(
                                capture = capture,
                                primaryTagName = primaryTagByCapture[capture.id],
                                modifier = Modifier
                                    .weight(1f)
                                    .height(150.dp),
                                onClick = { onOpenCapture(capture.id) },
                            )
                        }
                    }
                }

                else -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(230.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        AirbnbGalleryTile(
                            capture = lowerRow.first(),
                            primaryTagName = primaryTagByCapture[lowerRow.first().id],
                            modifier = Modifier
                                .weight(1.12f)
                                .fillMaxSize(),
                            onClick = { onOpenCapture(lowerRow.first().id) },
                        )

                        Column(
                            modifier = Modifier
                                .weight(0.88f)
                                .fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            lowerRow.drop(1).forEachIndexed { index, capture ->
                                val isOverflowTile = hiddenCount > 0 && index == lowerRow.drop(1).lastIndex
                                AirbnbGalleryTile(
                                    capture = capture,
                                    primaryTagName = primaryTagByCapture[capture.id],
                                    hiddenCount = if (isOverflowTile) hiddenCount else 0,
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth(),
                                    onClick = if (isOverflowTile) onOpenGallery else {
                                        { onOpenCapture(capture.id) }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AirbnbHeroTile(
    capture: CaptureEntity,
    primaryTagName: String?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    val title = capture.displayTitle(primaryTagName, fallback = sourceLabel(capture))

    Box(
        modifier = modifier
            .background(colors.borderSoft)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Open highlighted ${capture.mediaKind.accessibilityLabel} $title" },
    ) {
        MomentPreview(capture = capture, modifier = Modifier.fillMaxSize())
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.16f), Color.Transparent, Color.Black.copy(alpha = 0.46f)),
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(QuickInkSpacing.s4),
            verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s1),
        ) {
            Text(
                text = title,
                style = type.editorial.copy(fontSize = 19.sp),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = momentSubtitle(capture),
                style = type.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                color = Color.White.copy(alpha = 0.88f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (capture.mediaKind == MediaKind.Video) {
            MomentTypeBadge(capture.mediaKind, modifier = Modifier.align(Alignment.BottomEnd))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AirbnbGalleryTile(
    capture: CaptureEntity,
    primaryTagName: String?,
    hiddenCount: Int = 0,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val title = capture.displayTitle(primaryTagName, fallback = sourceLabel(capture))

    Box(
        modifier = modifier
            .background(colors.borderSoft)
            .combinedClickable(onClick = onClick, onLongClick = onClick)
            .semantics { contentDescription = "Open ${capture.mediaKind.accessibilityLabel} $title" },
        contentAlignment = Alignment.Center,
    ) {
        MomentPreview(capture = capture, modifier = Modifier.fillMaxSize())
        if (hiddenCount > 0) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.72f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "+$hiddenCount",
                    style = LocalQuickInkTypography.current.heading.copy(fontSize = 18.sp),
                    color = Color.White,
                )
            }
        } else if (capture.mediaKind == MediaKind.Video) {
            MomentTypeBadge(capture.mediaKind, modifier = Modifier.align(Alignment.TopEnd))
        }
    }
}

@Composable
private fun AirbnbActionGlyph(icon: ImageVector, label: String) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.92f))
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color(0xFF242424),
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun MomentRow(
    captures: List<CaptureEntity>,
    primaryTagByCapture: Map<String, String>,
    onOpenCapture: (String) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3)) {
        captures.forEach { capture ->
            MomentTile(
                capture = capture,
                primaryTagName = primaryTagByCapture[capture.id],
                modifier = Modifier.weight(1f),
                onClick = { onOpenCapture(capture.id) },
            )
        }
        if (captures.size == 1) {
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MomentTile(
    capture: CaptureEntity,
    primaryTagName: String?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    val context = LocalContext.current
    val shape = RoundedCornerShape(QuickInkRadius.lg)
    val title = capture.displayTitle(primaryTagName, fallback = sourceLabel(capture))

    Column(
        modifier = modifier
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, colors.border.copy(alpha = 0.72f), shape)
            .combinedClickable(onClick = onClick, onLongClick = onClick)
            .semantics { contentDescription = "Open ${capture.mediaKind.accessibilityLabel} $title" },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(if (capture.mediaKind == MediaKind.Video) 0.86f else 1f)
                .background(colors.borderSoft),
            contentAlignment = Alignment.Center,
        ) {
            MomentPreview(capture = capture, modifier = Modifier.fillMaxSize())
            MomentTypeBadge(capture.mediaKind)
        }
        Column(
            modifier = Modifier.padding(QuickInkSpacing.s3),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = type.cardTitle.copy(fontSize = 13.sp),
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = momentSubtitle(capture),
                style = type.caption.copy(fontSize = 10.sp),
                color = colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MomentHeroCard(
    capture: CaptureEntity,
    primaryTagName: String?,
    onClick: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    val shape = RoundedCornerShape(QuickInkRadius.xl)
    val title = capture.displayTitle(primaryTagName, fallback = sourceLabel(capture))

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(190.dp)
            .clip(shape)
            .background(colors.borderSoft)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Open highlighted ${capture.mediaKind.accessibilityLabel} $title" },
    ) {
        MomentPreview(capture = capture, modifier = Modifier.fillMaxSize())
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.64f)),
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(QuickInkSpacing.s4),
            verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s1),
        ) {
            Text(
                text = title,
                style = type.editorial.copy(fontSize = 18.sp),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = momentSubtitle(capture),
                style = type.caption.copy(fontSize = 11.sp),
                color = Color.White.copy(alpha = 0.84f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        MomentTypeBadge(capture.mediaKind, modifier = Modifier.align(Alignment.TopEnd))
    }
}

@Composable
private fun MomentPreview(capture: CaptureEntity, modifier: Modifier = Modifier) {
    val colors = LocalQuickInkColors.current
    val context = LocalContext.current
    val previewUri = capture.previewUri?.takeIf { it.isNotBlank() }
    if (previewUri == null) {
        Box(
            modifier = modifier.background(colors.borderSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (capture.mediaKind == MediaKind.Video) Icons.Filled.PlayArrow else Icons.Filled.Description,
                contentDescription = null,
                tint = colors.muted,
                modifier = Modifier.size(30.dp),
            )
        }
    } else {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(Uri.parse(previewUri))
                .crossfade(true)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    }
}

@Composable
private fun MomentTypeBadge(kind: MediaKind, modifier: Modifier = Modifier) {
    val colors = LocalQuickInkColors.current
    val icon = if (kind == MediaKind.Video) Icons.Filled.PlayArrow else Icons.Filled.Image
    Box(
        modifier = modifier
            .padding(QuickInkSpacing.s2)
            .clip(RoundedCornerShape(QuickInkRadius.pill))
            .background(Color.Black.copy(alpha = 0.42f))
            .padding(horizontal = QuickInkSpacing.s2, vertical = QuickInkSpacing.s1),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = kind.label,
            tint = colors.textOnAccent,
            modifier = Modifier.size(14.dp),
        )
    }
}

@Composable
private fun EmptyMoments(onSearch: () -> Unit) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(QuickInkRadius.xl))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.xl))
            .padding(QuickInkSpacing.s6),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3),
    ) {
        Box(
            modifier = Modifier
                .size(58.dp)
                .clip(RoundedCornerShape(QuickInkRadius.lg))
                .background(colors.accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Photo,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(28.dp),
            )
        }
        Text(text = "No moments yet", style = type.editorial.copy(fontSize = 18.sp), color = colors.ink)
        Text(
            text = "Capture or import photos and videos to build a visual timeline.",
            style = type.body.copy(fontSize = 13.sp),
            color = colors.inkSoft,
        )
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(QuickInkRadius.pill))
                .background(colors.accent)
                .clickable(onClick = onSearch)
                .padding(horizontal = QuickInkSpacing.s4, vertical = QuickInkSpacing.s3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = null,
                tint = colors.textOnAccent,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(QuickInkSpacing.s2))
            Text(
                text = "Search library",
                style = type.label.copy(fontSize = 13.sp),
                color = colors.textOnAccent,
            )
        }
    }
}

@Composable
private fun EmptyFilteredState(onClear: () -> Unit) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(QuickInkRadius.lg))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.lg))
            .padding(QuickInkSpacing.s5),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
    ) {
        Text("Nothing matches this view", style = type.editorial, color = colors.ink)
        Text("Clear search and filters to return to the full timeline.", style = type.body.copy(fontSize = 13.sp), color = colors.inkSoft)
        Text(
            text = "Show timeline",
            style = type.label.copy(fontSize = 13.sp),
            color = colors.accent,
            modifier = Modifier.clickable(onClick = onClear).padding(QuickInkSpacing.s2),
        )
    }
}

@Composable
private fun SectionTitle(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text = title, style = type.label, color = colors.ink, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) {
            Text(
                text = action,
                style = type.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                color = colors.accent,
                modifier = Modifier.clickable(onClick = onAction).padding(QuickInkSpacing.s1),
            )
        }
    }
}

@Composable
private fun FilterPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(QuickInkRadius.pill))
            .background(if (selected) colors.accent else colors.borderSoft)
            .clickable(onClick = onClick)
            .padding(horizontal = QuickInkSpacing.s3, vertical = QuickInkSpacing.s2),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = type.caption.copy(fontSize = 11.sp),
            color = if (selected) colors.textOnAccent else colors.ink,
            maxLines = 1,
        )
    }
}

@Composable
private fun SelectedFilterChip(label: String, onRemove: () -> Unit) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(QuickInkRadius.pill))
            .background(colors.accentSoft)
            .border(1.dp, colors.accent.copy(alpha = 0.18f), RoundedCornerShape(QuickInkRadius.pill))
            .padding(start = QuickInkSpacing.s3, end = QuickInkSpacing.s2, top = QuickInkSpacing.s2, bottom = QuickInkSpacing.s2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s1),
    ) {
        Text(
            text = label,
            style = type.caption.copy(fontSize = 11.sp),
            color = colors.accent,
            maxLines = 1,
        )
        Icon(
            imageVector = Icons.Outlined.Close,
            contentDescription = "Remove $label filter",
            tint = colors.accent,
            modifier = Modifier
                .size(14.dp)
                .clickable(onClick = onRemove),
        )
    }
}

@Composable
private fun MomentFilterPickerSheet(
    picker: MomentFilterPicker,
    options: List<MomentFilterOption>,
    selectedIds: Set<String>,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = QuickInkSpacing.s4)
                .padding(bottom = QuickInkSpacing.s5),
            verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3),
        ) {
            if (picker == MomentFilterPicker.Tags) {
                MomentTagVocabularyContent(
                    options = options,
                    selectedIds = selectedIds,
                    emptyMessage = picker.emptyMessage,
                    onToggle = onToggle,
                    onDone = onDismiss,
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = picker.title,
                            style = type.heading,
                            color = colors.ink,
                        )
                        Text(
                            text = picker.subtitle,
                            style = type.caption,
                            color = colors.muted,
                        )
                    }
                    Text(
                        text = "Done",
                        style = type.label.copy(fontSize = 13.sp),
                        color = colors.accent,
                        modifier = Modifier
                            .clickable(onClick = onDismiss)
                            .padding(QuickInkSpacing.s2),
                    )
                }

                if (options.isEmpty()) {
                    Text(
                        text = picker.emptyMessage,
                        style = type.body.copy(fontSize = 13.sp),
                        color = colors.inkSoft,
                        modifier = Modifier.padding(vertical = QuickInkSpacing.s4),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(420.dp),
                        verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
                        contentPadding = PaddingValues(bottom = QuickInkSpacing.s3),
                    ) {
                        items(options, key = { it.id }) { option ->
                            MomentFilterOptionRow(
                                option = option,
                                selected = option.id in selectedIds,
                                onToggle = { onToggle(option.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MomentTagVocabularyContent(
    options: List<MomentFilterOption>,
    selectedIds: Set<String>,
    emptyMessage: String,
    onToggle: (String) -> Unit,
    onDone: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    var tagSearchQuery by rememberSaveable { mutableStateOf("") }
    val normalizedQuery = tagSearchQuery.trim()
    val filteredOptions = remember(options, normalizedQuery) {
        if (normalizedQuery.isBlank()) {
            options
        } else {
            options.filter { option ->
                option.label.contains(normalizedQuery, ignoreCase = true)
            }
        }
    }
    val sections = remember(filteredOptions) { buildMomentTagVocabularySections(filteredOptions) }
    val lastSectionId = sections.lastOrNull()?.bucketId

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "TAG VOCABULARY",
                style = type.label.copy(
                    fontSize = 13.sp,
                    letterSpacing = 1.6.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = colors.ink,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${filteredOptions.size} tags · ${sections.size} buckets",
                style = type.body.copy(
                    fontSize = 12.sp,
                    fontStyle = FontStyle.Italic,
                ),
                color = colors.muted,
            )
            Text(
                text = "Done",
                style = type.label.copy(fontSize = 12.sp),
                color = colors.accent,
                modifier = Modifier
                    .padding(start = QuickInkSpacing.s2)
                    .clickable(onClick = onDone)
                    .padding(QuickInkSpacing.s2),
            )
        }

        if (options.isEmpty()) {
            Text(
                text = emptyMessage,
                style = type.body.copy(fontSize = 12.sp),
                color = colors.inkSoft,
                modifier = Modifier.padding(vertical = QuickInkSpacing.s4),
            )
        } else {
            MomentTagVocabularySearchField(
                query = tagSearchQuery,
                onQueryChange = { tagSearchQuery = it },
            )

            if (filteredOptions.isEmpty()) {
                Text(
                    text = "No matching tags.",
                    style = type.body.copy(fontSize = 12.sp),
                    color = colors.inkSoft,
                    modifier = Modifier.padding(vertical = QuickInkSpacing.s4),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(500.dp),
                    contentPadding = PaddingValues(bottom = QuickInkSpacing.s3),
                ) {
                    items(sections, key = { it.bucketId }) { section ->
                        MomentTagVocabularySection(
                            section = section,
                            selectedIds = selectedIds,
                            showBottomBorder = section.bucketId != lastSectionId,
                            onToggle = onToggle,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MomentTagVocabularySearchField(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    val searchShape = RoundedCornerShape(QuickInkRadius.md)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(searchShape)
            .background(colors.bg, searchShape)
            .border(1.dp, colors.borderSoft, searchShape)
            .padding(horizontal = QuickInkSpacing.s3, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Search,
            contentDescription = null,
            tint = colors.muted,
            modifier = Modifier.size(15.dp),
        )
        Spacer(modifier = Modifier.width(QuickInkSpacing.s2))
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = type.body.copy(fontSize = 12.sp, color = colors.ink),
            modifier = Modifier.weight(1f),
            decorationBox = { innerTextField ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isBlank()) {
                        Text(
                            text = "Search tags...",
                            style = type.body.copy(fontSize = 12.sp),
                            color = colors.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    innerTextField()
                }
            },
        )
        if (query.isNotBlank()) {
            Spacer(modifier = Modifier.width(QuickInkSpacing.s2))
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = "Clear tag search",
                tint = colors.muted,
                modifier = Modifier
                    .size(15.dp)
                    .clickable { onQueryChange("") },
            )
        }
    }
}

@Composable
private fun MomentTagVocabularySection(
    section: MomentTagVocabularySectionData,
    selectedIds: Set<String>,
    showBottomBorder: Boolean,
    onToggle: (String) -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = QuickInkSpacing.s3),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 3.dp)
                    .width(3.dp)
                    .height(42.dp)
                    .clip(RoundedCornerShape(99.dp))
                    .background(section.hue),
            )
            Spacer(modifier = Modifier.width(QuickInkSpacing.s3))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = section.name.uppercase(Locale.US),
                        style = type.label.copy(
                            fontSize = 14.sp,
                            letterSpacing = 1.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = section.hue,
                    )
                    section.prefixLabel?.let { prefix ->
                        Spacer(modifier = Modifier.width(QuickInkSpacing.s2))
                        Text(
                            text = prefix,
                            style = type.body.copy(fontSize = 12.sp),
                            color = colors.muted,
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(colors.bg),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = section.options.size.toString(),
                            style = type.label.copy(fontSize = 12.sp),
                            color = colors.ink,
                        )
                    }
                }
                Text(
                    text = section.question,
                    style = type.body.copy(
                        fontSize = 12.sp,
                        fontStyle = FontStyle.Italic,
                    ),
                    color = colors.muted,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
                    verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
                ) {
                    section.options.forEach { option ->
                        MomentTagVocabularyChip(
                            option = option,
                            tint = section.hue,
                            selected = option.id in selectedIds,
                            onToggle = { onToggle(option.id) },
                        )
                    }
                }
            }
        }

        if (showBottomBorder) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = QuickInkSpacing.s3)
                    .height(1.dp)
                    .background(colors.borderSoft),
            )
        }
    }
}

@Composable
private fun MomentTagVocabularyChip(
    option: MomentFilterOption,
    tint: Color,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    val type = LocalQuickInkTypography.current
    val fill = if (selected) tint.copy(alpha = 0.14f) else Color.Transparent
    val stroke = if (selected) tint else tint.copy(alpha = 0.72f)

    Text(
        text = option.label,
        style = type.label.copy(fontSize = 12.sp),
        color = tint,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(CircleShape)
            .background(fill)
            .border(1.dp, stroke, CircleShape)
            .clickable(onClick = onToggle)
            .padding(horizontal = QuickInkSpacing.s3, vertical = 7.dp)
            .semantics { contentDescription = "Filter by tag ${option.label}" },
    )
}

@Composable
private fun MomentFilterOptionRow(
    option: MomentFilterOption,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(QuickInkRadius.md))
            .background(if (selected) colors.accentSoft else colors.bg)
            .border(
                1.dp,
                if (selected) colors.accent.copy(alpha = 0.24f) else colors.borderSoft,
                RoundedCornerShape(QuickInkRadius.md),
            )
            .clickable(onClick = onToggle)
            .padding(QuickInkSpacing.s3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3),
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background((option.color ?: colors.accent).copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = option.icon,
                contentDescription = null,
                tint = option.color ?: colors.accent,
                modifier = Modifier.size(17.dp),
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                text = option.label,
                style = type.label.copy(fontSize = 13.sp),
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            option.subtitle?.let {
                Text(
                    text = it,
                    style = type.caption.copy(fontSize = 11.sp),
                    color = colors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun IconButtonCircle(
    icon: ImageVector,
    label: String,
    background: Color,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(background)
            .border(1.dp, LocalQuickInkColors.current.border, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
    }
}

private data class QuickAccessSpec(
    val title: String,
    val caption: String,
    val icon: ImageVector,
    val active: Boolean,
    val onClick: () -> Unit,
)

private enum class MomentHomeTab {
    Timeline,
    Albums,
    SmartCollections,
}

private data class MomentGroup(
    val key: String,
    val title: String,
    val subtitle: String,
    val items: List<CaptureEntity>,
)

private data class GalleryMosaicBlock(
    val key: String,
    val type: GalleryMosaicBlockType,
    val items: List<CaptureEntity>,
)

private enum class GalleryMosaicBlockType {
    Wide,
    Pair,
    TallStack,
    Single,
}

private enum class MomentFilter(val label: String) {
    Photos("Photos"),
    Videos("Videos"),
    Favorites("Favorites"),
    Tags("Tags"),
    People("People"),
    Places("Places"),
}

private fun MomentFilter.labelWithCount(count: Int): String =
    if (count > 0) "$label ($count)" else label

private enum class MomentFilterPicker(
    val title: String,
    val subtitle: String,
    val emptyMessage: String,
) {
    Tags(
        title = "Choose tags",
        subtitle = "Show moments with any selected tag.",
        emptyMessage = "No tags are available yet.",
    ),
    People(
        title = "Choose people",
        subtitle = "Show moments with any selected person.",
        emptyMessage = "No people are available yet.",
    ),
    Places(
        title = "Choose places",
        subtitle = "Show moments from any selected place.",
        emptyMessage = "No places are available yet.",
    ),
}

private data class MomentFilterOption(
    val id: String,
    val label: String,
    val subtitle: String?,
    val color: Color?,
    val icon: ImageVector,
    val bucketId: String?,
)

private data class MomentTagVocabularySectionData(
    val bucketId: String,
    val name: String,
    val question: String,
    val hue: Color,
    val prefixLabel: String?,
    val options: List<MomentFilterOption>,
)

private data class SelectedMomentOptionChip(
    val id: String,
    val picker: MomentFilterPicker,
    val optionId: String,
    val label: String,
)

private enum class MediaKind(val label: String, val accessibilityLabel: String) {
    Photo("Photo", "photo"),
    Video("Video", "video"),
}

private val CaptureEntity.mediaKind: MediaKind
    get() {
        val hasVideo = !videoUri.isNullOrBlank() || !videoDriveFileId.isNullOrBlank()
        return if (source == "video" || (source == "photo" && hasVideo)) {
            MediaKind.Video
        } else {
            MediaKind.Photo
        }
    }

private fun CaptureEntity.matchesMomentSearch(
    query: String,
    primaryTagName: String?,
): Boolean {
    val needle = query.trim()
    if (needle.isEmpty()) return true

    val day = parseMomentDate(createdAt)
        ?.atZone(ZoneId.systemDefault())
        ?.toLocalDate()
    val searchable = listOfNotNull(
        title,
        primaryTagName,
        displayTitle(primaryTagName, fallback = sourceLabel(this)),
        sourceLabel(this),
        mediaKind.label,
        subLocality,
        locality,
        address,
        createdAt,
        day?.let(::dayLabel),
        day?.format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault())),
        if (isFavorite) "favorite" else null,
    )
    return searchable.any { value ->
        value.contains(needle, ignoreCase = true)
    }
}

private fun CaptureEntity.matchesMomentFilters(
    selected: Set<MomentFilter>,
    selectedTagIds: Set<String>,
    selectedPersonIds: Set<String>,
    selectedLocationIds: Set<String>,
    tagIdsByCapture: Map<String, Set<String>>,
    personIdsByCapture: Map<String, Set<String>>,
    locationIdsByCapture: Map<String, Set<String>>,
): Boolean {
    if (
        selected.isEmpty() &&
        selectedTagIds.isEmpty() &&
        selectedPersonIds.isEmpty() &&
        selectedLocationIds.isEmpty()
    ) return true

    val mediaTypeSelected = MomentFilter.Photos in selected || MomentFilter.Videos in selected
    if (mediaTypeSelected) {
        val matchesMediaType =
            (MomentFilter.Photos in selected && mediaKind == MediaKind.Photo) ||
                (MomentFilter.Videos in selected && mediaKind == MediaKind.Video)
        if (!matchesMediaType) return false
    }

    if (MomentFilter.Favorites in selected && !isFavorite) return false
    if (selectedTagIds.isNotEmpty() && tagIdsByCapture[id].orEmpty().intersect(selectedTagIds).isEmpty()) return false
    if (selectedPersonIds.isNotEmpty() && personIdsByCapture[id].orEmpty().intersect(selectedPersonIds).isEmpty()) return false
    if (selectedLocationIds.isNotEmpty() && locationIdsByCapture[id].orEmpty().intersect(selectedLocationIds).isEmpty()) return false

    return true
}

private fun Set<String>.toggle(value: String): Set<String> =
    if (value in this) this - value else this + value

private fun Bundle?.firstRecognitionText(): String? =
    this
        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        ?.firstOrNull()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

private fun List<CaptureEntity>.groupedByMomentDay(): List<MomentGroup> {
    val zone = ZoneId.systemDefault()
    return groupBy { capture ->
        parseMomentDate(capture.createdAt)?.atZone(zone)?.toLocalDate()
            ?: LocalDate.MIN
    }.map { (day, items) ->
        MomentGroup(
            key = day.toString(),
            title = dayLabel(day),
            subtitle = groupSubtitle(items),
            items = items.sortedByDescending { it.createdAt },
        )
    }.sortedByDescending { it.key }
}

private fun List<CaptureEntity>.toGalleryMosaicBlocks(): List<GalleryMosaicBlock> {
    if (isEmpty()) return emptyList()

    val blocks = mutableListOf<GalleryMosaicBlock>()
    var index = 0
    val openingType = when {
        size >= 3 -> GalleryMosaicBlockType.TallStack
        size == 2 -> GalleryMosaicBlockType.Pair
        else -> GalleryMosaicBlockType.Single
    }
    val openingCount = when (openingType) {
        GalleryMosaicBlockType.TallStack -> 3
        GalleryMosaicBlockType.Pair -> 2
        GalleryMosaicBlockType.Wide,
        GalleryMosaicBlockType.Single -> 1
    }
    val openingItems = subList(0, openingCount)
    blocks += GalleryMosaicBlock(
        key = "${openingType.name.lowercase(Locale.US)}-${openingItems.joinToString("-") { it.id }}",
        type = openingType,
        items = openingItems,
    )
    index = openingCount

    var patternIndex = 0
    while (index < size) {
        val remaining = size - index
        val type = when {
            remaining >= 3 && patternIndex % 3 == 2 -> GalleryMosaicBlockType.TallStack
            remaining >= 2 && patternIndex % 3 == 1 -> GalleryMosaicBlockType.Pair
            patternIndex % 3 == 0 -> GalleryMosaicBlockType.Wide
            remaining >= 2 -> GalleryMosaicBlockType.Pair
            else -> GalleryMosaicBlockType.Single
        }
        val count = when (type) {
            GalleryMosaicBlockType.TallStack -> 3
            GalleryMosaicBlockType.Pair -> 2
            GalleryMosaicBlockType.Wide,
            GalleryMosaicBlockType.Single -> 1
        }
        val items = subList(index, (index + count).coerceAtMost(size))
        blocks += GalleryMosaicBlock(
            key = "${type.name.lowercase(Locale.US)}-${items.joinToString("-") { it.id }}",
            type = type,
            items = items,
        )
        index += items.size
        patternIndex += 1
    }

    return blocks
}

private fun groupSubtitle(items: List<CaptureEntity>): String {
    val places = items.mapNotNull {
        it.subLocality?.takeIf { value -> value.isNotBlank() }
            ?: it.locality?.takeIf { value -> value.isNotBlank() }
    }.distinct()
    val place = places.firstOrNull()
    val videoCount = items.count { it.mediaKind == MediaKind.Video }
    return listOfNotNull(
        place,
        if (videoCount > 0) "$videoCount videos" else null,
    ).ifEmpty { listOf("Mixed media") }.joinToString(" · ")
}

private fun dayLabel(day: LocalDate): String {
    if (day == LocalDate.MIN) return "Earlier"
    val today = LocalDate.now()
    return when (day) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> day.format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault()))
    }
}

private fun momentSubtitle(capture: CaptureEntity): String {
    val place = capture.subLocality?.takeIf { it.isNotBlank() }
        ?: capture.locality?.takeIf { it.isNotBlank() }
    val parts = buildList {
        add(formatMomentDate(capture.createdAt))
        if (place != null) add(place)
        add(sourceLabel(capture))
    }
    return parts.joinToString(" · ")
}

private fun sourceLabel(capture: CaptureEntity): String =
    when (capture.mediaKind) {
        MediaKind.Video -> "Video"
        MediaKind.Photo -> when (capture.source) {
            "import" -> "Import"
            "photo" -> "Photo"
            else -> "Scan"
        }
    }

private fun formatMomentDate(iso: String): String =
    runCatching {
        OffsetDateTime.parse(iso).format(MomentDateFormatter)
    }.getOrElse {
        iso.take(10)
    }

private fun parseMomentDate(iso: String): Instant? =
    runCatching { Instant.parse(iso) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(iso).toInstant() }.getOrNull()

private fun buildMomentTagVocabularySections(
    options: List<MomentFilterOption>,
): List<MomentTagVocabularySectionData> {
    val canonicalIds = workspaceTagBuckets.map { it.id }.toSet()
    val sections = workspaceTagBuckets.mapNotNull { bucket ->
        val bucketOptions = options.filter {
            (it.bucketId ?: inferMomentTagBucketId(it.label)) == bucket.id
        }
        if (bucketOptions.isEmpty()) {
            null
        } else {
            MomentTagVocabularySectionData(
                bucketId = bucket.id,
                name = bucket.name,
                question = bucket.question,
                hue = bucket.hue,
                prefixLabel = momentBucketPrefixLabel(bucket.prefixes),
                options = bucketOptions,
            )
        }
    }

    val otherOptions = options.filter {
        val bucketId = it.bucketId ?: inferMomentTagBucketId(it.label)
        bucketId == null || bucketId !in canonicalIds
    }
    if (otherOptions.isEmpty()) return sections

    return sections + MomentTagVocabularySectionData(
        bucketId = "other",
        name = "Other",
        question = "uncategorized media tags",
        hue = Color(0xFF6B625C),
        prefixLabel = null,
        options = otherOptions,
    )
}

private fun momentBucketPrefixLabel(prefixes: List<String>?): String? =
    prefixes
        ?.takeIf { it.isNotEmpty() }
        ?.joinToString(", ") { "#$it" }
        ?.let { "($it)" }

private fun inferMomentTagBucketId(name: String): String? {
    val normalized = name.trim().removePrefix("#").lowercase(Locale.US)
    workspaceTagBuckets.firstOrNull { bucket ->
        bucket.prefixes?.any { prefix -> normalized.startsWith(prefix.lowercase(Locale.US)) } == true
    }?.let { return it.id }

    return when (normalized) {
        "active", "later", "done", "todo" -> "status"
        "focus", "shallow", "errand", "call" -> "energy"
        "today", "thisweek", "thismonth" -> "time"
        "idea", "quote", "recipe", "checklist", "template" -> "kind"
        "camera", "capture", "import", "photo", "scan", "screenshot", "screenshots", "share", "shared", "video", "voice" -> "source"
        else -> null
    }
}

private fun parseColorOrNull(raw: String): Color? {
    val hex = raw.removePrefix("#")
    if (hex.length != 6) return null
    return runCatching { Color(("FF$hex").toLong(16)) }.getOrNull()
}

private val MomentDateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d")
