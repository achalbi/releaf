/*
 * MomentsScreen.kt
 *
 * Visual memory layer for QuickInk photos and videos. Unlike the
 * Workspace tab, this surface is content-first: search, smart
 * retrieval, and a grouped timeline of media thumbnails.
 */

@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.LocalOffer
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.quickink.mobile.QuickInkApp
import app.quickink.mobile.data.capture.CaptureEntity
import app.quickink.mobile.data.capture.CaptureRepository
import app.quickink.mobile.data.capture.displayTitle
import app.quickink.mobile.data.location.LocationEntity
import app.quickink.mobile.data.person.PersonEntity
import app.quickink.mobile.data.smartcollection.SmartCollectionEntity
import app.quickink.mobile.data.tag.TagEntity
import app.quickink.mobile.features.nav.QuickInkBottomNavReservedHeight
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
    val captures by remember(userId, captureDao) {
        captureDao.observeRecentMedia(userId, limit = 120)
    }.collectAsState(initial = emptyList())

    val primaryTagRows by remember(userId, app) {
        app.database.captureTagDao().observePrimaryTagNames(userId)
    }.collectAsState(initial = emptyList())
    val primaryTagByCapture = remember(primaryTagRows) {
        primaryTagRows.associate { it.captureId to it.tagName }
    }

    val tags by produceState(initialValue = emptyList<TagEntity>(), key1 = userId) {
        app.database.tagDao().observeActive(userId).collect { value = it }
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
    val captureIdsWithPlaces by produceState(initialValue = emptySet<String>(), key1 = userId) {
        app.database.captureLocationDao().observeCaptureIdsWithLocations(userId).collect { rows ->
            value = rows.toSet()
        }
    }
    val captureIdsWithPeople by produceState(initialValue = emptySet<String>(), key1 = userId) {
        app.database.capturePersonDao().observeCaptureIdsWithPeople(userId).collect { rows ->
            value = rows.toSet()
        }
    }

    var selectedFilters by remember { mutableStateOf<Set<MomentFilter>>(emptySet()) }
    var showFilters by remember { mutableStateOf(false) }
    var openGalleryGroupKey by remember { mutableStateOf<String?>(null) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var isVoiceSearchListening by remember { mutableStateOf(false) }
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

    val visibleCaptures = remember(
        captures,
        selectedFilters,
        searchQuery,
        primaryTagByCapture,
        captureIdsWithPeople,
        captureIdsWithPlaces,
    ) {
        val filtered = captures.filter { capture ->
            capture.matchesMomentFilters(
                selected = selectedFilters,
                primaryTagByCapture = primaryTagByCapture,
                captureIdsWithPeople = captureIdsWithPeople,
                captureIdsWithPlaces = captureIdsWithPlaces,
            )
        }
        val searched = filtered.filter { capture ->
            capture.matchesMomentSearch(searchQuery, primaryTagByCapture[capture.id])
        }
        searched.sortedByDescending { it.createdAt }
    }
    val groups = remember(visibleCaptures) { visibleCaptures.groupedByMomentDay() }
    val videoCount = remember(captures) { captures.count { it.mediaKind == MediaKind.Video } }
    val photoCount = remember(captures) { captures.count { it.mediaKind == MediaKind.Photo } }
    val unsortedCount = remember(captures) { captures.count { it.folderId == null } }
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
                    onToggleFilter = ::toggleFilter,
                )
            }
        }

        item {
            QuickAccessRow(
                selectedFilters = selectedFilters,
                photoCount = photoCount,
                videoCount = videoCount,
                unsortedCount = unsortedCount,
                smartCount = smartCollections.size,
                tagCount = tags.size,
                peopleCount = people.size,
                placesCount = locations.size,
                onClearFilters = { selectedFilters = emptySet() },
                onToggleFilter = ::toggleFilter,
                onOpenSearch = onOpenSearch,
                onOpenSmart = {
                    smartCollections.firstOrNull()?.let(onOpenSmartCollection)
                },
                onOpenTags = onOpenTagLibrary,
                onOpenPeople = {
                    people.firstOrNull()?.let(onOpenPerson)
                },
                onOpenPlaces = {
                    locations.firstOrNull()?.let(onOpenLocation)
                },
            )
        }

        if (smartCollections.isNotEmpty() || tags.isNotEmpty() || locations.isNotEmpty() || people.isNotEmpty()) {
            item {
                DiscoverySection(
                    smartCollections = smartCollections,
                    tags = tags,
                    locations = locations,
                    locationCounts = locationCounts,
                    people = people,
                    personCounts = personCounts,
                    onOpenSmartCollection = onOpenSmartCollection,
                    onOpenTag = onOpenTag,
                    onOpenTagLibrary = onOpenTagLibrary,
                    onOpenLocation = onOpenLocation,
                    onOpenPerson = onOpenPerson,
                )
            }
        }

        if (captures.isEmpty()) {
            item { EmptyMoments(onSearch = onOpenSearch) }
        } else if (visibleCaptures.isEmpty()) {
            item {
                EmptyFilteredState(onClear = {
                    selectedFilters = emptySet()
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
    onToggleFilter: (MomentFilter) -> Unit,
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
                FilterPill(
                    label = filter.label,
                    selected = filter in selectedFilters,
                    onClick = { onToggleFilter(filter) },
                )
            }
        }
    }
}

@Composable
private fun QuickAccessRow(
    selectedFilters: Set<MomentFilter>,
    photoCount: Int,
    videoCount: Int,
    unsortedCount: Int,
    smartCount: Int,
    tagCount: Int,
    peopleCount: Int,
    placesCount: Int,
    onClearFilters: () -> Unit,
    onToggleFilter: (MomentFilter) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSmart: () -> Unit,
    onOpenTags: () -> Unit,
    onOpenPeople: () -> Unit,
    onOpenPlaces: () -> Unit,
) {
    val items = listOf(
        QuickAccessSpec("Timeline", "All memories", Icons.Outlined.CalendarToday, selectedFilters.isEmpty()) {
            onClearFilters()
        },
        QuickAccessSpec("Photos", "$photoCount items", Icons.Outlined.Photo, MomentFilter.Photos in selectedFilters) {
            onToggleFilter(MomentFilter.Photos)
        },
        QuickAccessSpec("Videos", "$videoCount clips", Icons.Outlined.VideoLibrary, MomentFilter.Videos in selectedFilters) {
            onToggleFilter(MomentFilter.Videos)
        },
        QuickAccessSpec("Favorites", "Saved media", Icons.Outlined.StarBorder, MomentFilter.Favorites in selectedFilters) {
            onToggleFilter(MomentFilter.Favorites)
        },
        QuickAccessSpec("Albums", "Curated sets", Icons.Outlined.Folder, false) {
            onOpenSearch()
        },
        QuickAccessSpec("Smart", "$smartCount rules", Icons.Outlined.AutoAwesome, false, onOpenSmart),
        QuickAccessSpec("Tags", "$tagCount tags", Icons.Outlined.Tag, false, onOpenTags),
        QuickAccessSpec("People", "$peopleCount faces", Icons.Outlined.Person, false, onOpenPeople),
        QuickAccessSpec("Places", "$placesCount places", Icons.Outlined.LocationOn, false, onOpenPlaces),
        QuickAccessSpec("Archive", "Archived media", Icons.Outlined.Folder, false, onOpenSearch),
        QuickAccessSpec("Unsorted", "$unsortedCount items", Icons.Outlined.LocalOffer, false, onOpenSearch),
    )

    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s2),
        contentPadding = PaddingValues(end = QuickInkSpacing.s2),
    ) {
        items(items, key = { it.title }) { item ->
            QuickAccessCard(item)
        }
    }
}

@Composable
private fun QuickAccessCard(item: QuickAccessSpec) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    val bg = if (item.active) colors.accentSoft else colors.surface
    val tint = if (item.active) colors.accent else colors.ink
    Column(
        modifier = Modifier
            .width(96.dp)
            .height(86.dp)
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
                text = item.title,
                style = type.label.copy(fontSize = 12.sp),
                color = tint,
                maxLines = 1,
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
private fun DiscoverySection(
    smartCollections: List<SmartCollectionEntity>,
    tags: List<TagEntity>,
    locations: List<LocationEntity>,
    locationCounts: Map<String, Int>,
    people: List<PersonEntity>,
    personCounts: Map<String, Int>,
    onOpenSmartCollection: (SmartCollectionEntity) -> Unit,
    onOpenTag: (TagEntity) -> Unit,
    onOpenTagLibrary: () -> Unit,
    onOpenLocation: (LocationEntity) -> Unit,
    onOpenPerson: (PersonEntity) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3)) {
        SectionTitle(title = "Smart Collections", action = "See all", onAction = onOpenTagLibrary)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(QuickInkSpacing.s3)) {
            items(smartCollections.take(4), key = { it.id }) { collection ->
                DiscoveryCard(
                    title = collection.name,
                    caption = if (collection.isSeeded) "System managed" else "Custom rule",
                    icon = Icons.Outlined.AutoAwesome,
                    accent = collection.color?.let(::parseColorOrNull),
                    onClick = { onOpenSmartCollection(collection) },
                )
            }
            items(tags.take(3), key = { "tag-${it.id}" }) { tag ->
                DiscoveryCard(
                    title = tag.name,
                    caption = "Tag",
                    icon = Icons.Outlined.LocalOffer,
                    accent = tag.color?.let(::parseColorOrNull),
                    onClick = { onOpenTag(tag) },
                )
            }
            items(people.take(3), key = { "person-${it.id}" }) { person ->
                DiscoveryCard(
                    title = person.name,
                    caption = "${personCounts[person.id] ?: 0} moments",
                    icon = Icons.Outlined.Person,
                    accent = person.color?.let(::parseColorOrNull),
                    onClick = { onOpenPerson(person) },
                )
            }
            items(locations.take(3), key = { "place-${it.id}" }) { location ->
                DiscoveryCard(
                    title = location.name,
                    caption = "${locationCounts[location.id] ?: 0} moments",
                    icon = Icons.Outlined.LocationOn,
                    accent = location.color?.let(::parseColorOrNull),
                    onClick = { onOpenLocation(location) },
                )
            }
        }
    }
}

@Composable
private fun DiscoveryCard(
    title: String,
    caption: String,
    icon: ImageVector,
    accent: Color?,
    onClick: () -> Unit,
) {
    val colors = LocalQuickInkColors.current
    val type = LocalQuickInkTypography.current
    val resolvedAccent = accent ?: colors.accent
    Row(
        modifier = Modifier
            .width(172.dp)
            .clip(RoundedCornerShape(QuickInkRadius.lg))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(QuickInkRadius.lg))
            .clickable(onClick = onClick)
            .padding(QuickInkSpacing.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(QuickInkRadius.md))
                .background(resolvedAccent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = resolvedAccent,
                modifier = Modifier.size(21.dp),
            )
        }
        Spacer(modifier = Modifier.width(QuickInkSpacing.s3))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = type.label.copy(fontSize = 13.sp),
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = caption,
                style = type.caption.copy(fontSize = 10.sp),
                color = colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = colors.muted,
            modifier = Modifier.size(16.dp),
        )
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
    primaryTagByCapture: Map<String, String>,
    captureIdsWithPeople: Set<String>,
    captureIdsWithPlaces: Set<String>,
): Boolean {
    if (selected.isEmpty()) return true

    val mediaTypeSelected = MomentFilter.Photos in selected || MomentFilter.Videos in selected
    if (mediaTypeSelected) {
        val matchesMediaType =
            (MomentFilter.Photos in selected && mediaKind == MediaKind.Photo) ||
                (MomentFilter.Videos in selected && mediaKind == MediaKind.Video)
        if (!matchesMediaType) return false
    }

    if (MomentFilter.Favorites in selected && !isFavorite) return false
    if (MomentFilter.Tags in selected && primaryTagByCapture[id].isNullOrBlank()) return false
    if (MomentFilter.People in selected && id !in captureIdsWithPeople) return false
    if (MomentFilter.Places in selected &&
        id !in captureIdsWithPlaces &&
        locality.isNullOrBlank()
    ) return false

    return true
}

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

private fun parseColorOrNull(raw: String): Color? {
    val hex = raw.removePrefix("#")
    if (hex.length != 6) return null
    return runCatching { Color(("FF$hex").toLong(16)) }.getOrNull()
}

private val MomentDateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d")
