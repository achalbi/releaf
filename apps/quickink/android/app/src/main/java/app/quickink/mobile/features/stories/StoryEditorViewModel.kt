/*
 * StoryEditorViewModel.kt
 *
 * State holder for `StoryEditorScreen`. Mirror of iOS
 * `StoryEditorViewModel.swift`.
 *
 * Owns:
 *   - the story header — observed via DAO findById (refreshed on each
 *     update)
 *   - the ordered item list — observed via `observeItems`
 *   - the "Saved just now" auto-save toast (debounced text edits, hard
 *     saves on inserts / removes / reorder commits)
 */

package app.quickink.mobile.features.stories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.quickink.mobile.QuickInkApp
import app.quickink.mobile.data.story.StoryEntity
import app.quickink.mobile.data.story.StoryRepository
import app.quickink.mobile.data.storyitem.StoryItemEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class StoryEditorViewModel(
    val storyId: String,
    val userId: String,
    private val repository: StoryRepository,
) : ViewModel() {

    val items: StateFlow<List<StoryItemEntity>> =
        repository.observeItems(storyId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _story: MutableStateFlow<StoryEntity?> = MutableStateFlow(null)
    val story: StateFlow<StoryEntity?> = _story.asStateFlow()

    private val _savedJustNow: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val savedJustNow: StateFlow<Boolean> = _savedJustNow.asStateFlow()

    private var titleDebounce: Job? = null
    private val textDebounces: MutableMap<String, Job> = mutableMapOf()
    private var savedToastJob: Job? = null

    init {
        refreshStoryHeader()
    }

    private fun refreshStoryHeader() {
        viewModelScope.launch {
            _story.value = repository.fetchStory(storyId)
        }
    }

    fun updateTitle(value: String) {
        titleDebounce?.cancel()
        titleDebounce = viewModelScope.launch {
            delay(500)
            repository.updateTitle(storyId, value)
            refreshStoryHeader()
            flashSavedToast()
        }
    }

    fun updateSubtitle(value: String) {
        val s = value.takeIf { it.isNotEmpty() }
        viewModelScope.launch {
            repository.updateSubtitle(storyId, s)
            refreshStoryHeader()
            flashSavedToast()
        }
    }

    fun updateItemCaption(itemId: String, value: String) {
        debounceItem(itemId) {
            repository.updateItemCaption(itemId, value.takeIf { it.isNotEmpty() })
            flashSavedToast()
        }
    }

    fun updateItemText(itemId: String, value: String) {
        debounceItem(itemId) {
            repository.updateItemText(itemId, value.takeIf { it.isNotEmpty() })
            flashSavedToast()
        }
    }

    fun updateItemLayout(itemId: String, layout: StoryItemEntity.Layout) {
        viewModelScope.launch {
            repository.updateItemLayout(itemId, layout)
            flashSavedToast()
        }
    }

    fun removeItem(itemId: String) {
        viewModelScope.launch {
            repository.softDeleteItem(itemId)
            refreshStoryHeader()
            flashSavedToast()
        }
    }

    suspend fun insertItem(
        precedingId: String?,
        kind: StoryItemEntity.Kind,
        text: String? = null,
        caption: String? = null,
    ): String? {
        val newItem = repository.insertItem(
            storyId  = storyId,
            position = positionAfter(precedingId),
            kind     = kind,
            refId    = null,
            text     = text,
            caption  = caption,
            occurredAt = null,
            layout   = StoryItemEntity.Layout.FULL,
        ) ?: return null
        flashSavedToast()
        return newItem.id
    }

    /** Insert a capture-backed item (kind = photo or document)
     *  pointing at an existing `captures.id`. Used by the library
     *  picker. */
    suspend fun insertCaptureItem(
        precedingId: String?,
        captureId: String,
        kind: StoryItemEntity.Kind,
    ): String? {
        val item = repository.insertItem(
            storyId    = storyId,
            position   = positionAfter(precedingId),
            kind       = kind,
            refId      = captureId,
            text       = null,
            caption    = null,
            occurredAt = null,
            layout     = StoryItemEntity.Layout.FULL,
        ) ?: return null
        flashSavedToast()
        return item.id
    }

    suspend fun insertVoiceClipItem(
        precedingId: String?,
        audioUri: String,
        durationMs: Long,
    ): String? {
        val item = repository.insertItem(
            storyId    = storyId,
            position   = positionAfter(precedingId),
            kind       = StoryItemEntity.Kind.VOICE_CLIP,
            refId      = null,
            text       = null,
            caption    = null,
            occurredAt = null,
            layout     = StoryItemEntity.Layout.FULL,
        ) ?: return null
        repository.insertVoiceClip(
            storyItemId = item.id,
            userId      = userId,
            audioUri    = audioUri,
            durationMs  = durationMs,
        )
        flashSavedToast()
        return item.id
    }

    fun commitReorder(orderedIds: List<String>) {
        if (orderedIds.isEmpty()) return
        val updates = orderedIds.mapIndexed { index, id -> id to (index + 1) * 1024 }
        viewModelScope.launch {
            repository.updatePositions(updates)
            flashSavedToast()
        }
    }

    fun moveItem(itemId: String, delta: Int) {
        val current = items.value
        val idx = current.indexOfFirst { it.id == itemId }
        if (idx < 0) return
        val target = (idx + delta).coerceIn(0, current.size - 1)
        if (target == idx) return
        val ids = current.map { it.id }.toMutableList()
        val removed = ids.removeAt(idx)
        ids.add(target, removed)
        commitReorder(ids)
    }

    fun setCover(itemId: String?) {
        viewModelScope.launch {
            repository.setCoverItem(storyId, itemId)
            refreshStoryHeader()
            flashSavedToast()
        }
    }

    private fun positionAfter(precedingId: String?): Int {
        val current = items.value
        if (precedingId == null) {
            return (current.lastOrNull()?.position?.plus(1024)) ?: 1024
        }
        val idx = current.indexOfFirst { it.id == precedingId }
        if (idx < 0) {
            return (current.lastOrNull()?.position?.plus(1024)) ?: 1024
        }
        val precedingPos = current[idx].position
        return if (idx + 1 < current.size) {
            val nextPos = current[idx + 1].position
            val mid = (precedingPos + nextPos) / 2
            if (mid != precedingPos) mid else precedingPos + 1024
        } else {
            precedingPos + 1024
        }
    }

    private fun debounceItem(itemId: String, block: suspend () -> Unit) {
        textDebounces[itemId]?.cancel()
        textDebounces[itemId] = viewModelScope.launch {
            delay(500)
            block()
            textDebounces.remove(itemId)
        }
    }

    private fun flashSavedToast() {
        _savedJustNow.value = true
        savedToastJob?.cancel()
        savedToastJob = viewModelScope.launch {
            delay(3_000)
            _savedJustNow.value = false
        }
    }

    companion object {
        fun factory(storyId: String, userId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as QuickInkApp)
                val repo = StoryRepository(
                    storyDao          = app.database.storyDao(),
                    storyItemDao      = app.database.storyItemDao(),
                    storyVoiceClipDao = app.database.storyVoiceClipDao(),
                )
                StoryEditorViewModel(storyId, userId, repo)
            }
        }
    }
}
