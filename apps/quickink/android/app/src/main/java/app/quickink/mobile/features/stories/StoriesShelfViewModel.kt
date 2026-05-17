/*
 * StoriesShelfViewModel.kt
 *
 * State holder for the Stories shelf (§7.1 of the v3 mockup). Wraps
 * [StoryRepository.observeShelf] as a [StateFlow] the Compose screen
 * collects, plus an in-memory suggestion slot the Phase 5 engine
 * will populate. v3 ships with the suggestion always null so the
 * hero card renders its calm empty state.
 *
 * Mirror of iOS `StoriesShelfViewModel.swift`.
 */

package app.quickink.mobile.features.stories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.quickink.mobile.QuickInkApp
import app.quickink.mobile.data.capture.CaptureDao
import app.quickink.mobile.data.story.StoryRepository
import app.quickink.mobile.data.story.StoryShelfRow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * In-memory suggestion the §7.1 hero card renders when present.
 * Phase 5's engine fills this in; v3 leaves it null so the card
 * shows its empty state.
 */
data class StorySuggestion(
    val id: String,
    val reason: String,
    val candidateRefs: List<String>,
    val score: Double,
)

class StoriesShelfViewModel(
    private val repository: StoryRepository,
    private val userId: String,
    private val captureDao: CaptureDao,
) : ViewModel() {

    val rows: StateFlow<List<StoryShelfRow>> =
        repository.observeShelf(userId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _suggestion: MutableStateFlow<StorySuggestion?> = MutableStateFlow(null)
    val suggestion: StateFlow<StorySuggestion?> = _suggestion.asStateFlow()

    /**
     * Process-scoped dismissal set, per spec §7. Cleared on app
     * restart by virtue of being instance state on a ViewModel
     * scoped to the shelf composable.
     */
    private val dismissed: MutableSet<String> = mutableSetOf()

    init {
        refreshSuggestion()
    }

    fun refreshSuggestion() {
        viewModelScope.launch {
            _suggestion.value = StorySuggestionEngine.compute(
                userId     = userId,
                captureDao = captureDao,
                dismissed  = dismissed,
            )
        }
    }

    /** "Not interested" — drop the current suggestion and re-run the
     *  engine so a runner-up shows up if any. */
    fun dismissSuggestion() {
        val current = _suggestion.value ?: return
        dismissed += current.id
        _suggestion.value = null
        refreshSuggestion()
    }

    /**
     * Create a fresh draft story and return its id. The shelf calls
     * this when the user taps the "+" FAB; the screen then navigates
     * to the editor route keyed by the returned id.
     */
    suspend fun createDraft(): String? =
        repository.insertStory(
            userId   = userId,
            title    = "Untitled story",
            subtitle = null,
        )?.id

    companion object {
        fun factory(userId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as QuickInkApp)
                val repo = StoryRepository(
                    storyDao          = app.database.storyDao(),
                    storyItemDao      = app.database.storyItemDao(),
                    storyVoiceClipDao = app.database.storyVoiceClipDao(),
                )
                StoriesShelfViewModel(
                    repository = repo,
                    userId     = userId,
                    captureDao = app.database.captureDao(),
                )
            }
        }
    }
}
