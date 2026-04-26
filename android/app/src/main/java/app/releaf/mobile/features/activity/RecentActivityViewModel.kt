/*
 * RecentActivityViewModel.kt
 *
 * Backs both the home-screen "Today" timeline preview and the full
 * activity-log screen. The repo emits a sorted list directly; this VM
 * just hooks the user id and exposes a StateFlow with the right cap
 * for the surface that consumes it.
 */

package app.releaf.mobile.features.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.releaf.mobile.ReleafApp
import app.releaf.mobile.auth.AuthState
import app.releaf.mobile.data.activity.ActivityItem
import app.releaf.mobile.data.activity.RecentActivityRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class RecentActivityViewModel(
    private val repository: RecentActivityRepository,
    private val userId: String,
    private val maxItems: Int,
) : ViewModel() {

    val items: StateFlow<List<ActivityItem>> =
        repository.observe(userId, maxItems = maxItems)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    companion object {
        /** Home-screen preview — 5 rows is enough to fill the card. */
        const val HOME_LIMIT = 5

        /** Full activity-log screen — bigger window. */
        const val FULL_LIMIT = 200

        fun factory(maxItems: Int): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as ReleafApp
                val userId = (app.authStore.state.value as? AuthState.SignedIn)?.session?.userId
                    ?: error("RecentActivityViewModel created while not signed in")
                RecentActivityViewModel(
                    repository = app.recentActivityRepository,
                    userId     = userId,
                    maxItems   = maxItems,
                )
            }
        }
    }
}
