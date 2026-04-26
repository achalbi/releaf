/*
 * ContactsViewModel.kt
 *
 * Backs the contacts tab. Two data sources:
 *   - `ContactDirectoryRepository` — aggregated app contacts
 *     (notepad entries + pages). Always-on observer.
 *   - `DeviceContactsProvider` — device address book, queried
 *     on-demand as the user types. Gated by `READ_CONTACTS`.
 *
 * Exposes a single [ContactsUiState] with both halves so the
 * screen renders without having to coordinate two flows itself.
 */

package app.releaf.mobile.features.contacts

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.releaf.mobile.ReleafApp
import app.releaf.mobile.auth.AuthStore
import app.releaf.mobile.data.contact.ContactDirectoryRepository
import app.releaf.mobile.data.contact.DeviceContactsProvider
import app.releaf.mobile.data.contact.DirectoryContact
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ContactsUiState(
    val query: String = "",
    val isLoading: Boolean = true,
    /** Full app contacts (no filter). */
    val allAppContacts: List<DirectoryContact> = emptyList(),
    /** App contacts filtered by [query]. When query is blank, equals [allAppContacts]. */
    val filteredAppContacts: List<DirectoryContact> = emptyList(),
    /** Device contacts matching [query]. Empty when no query, no permission, or no matches. */
    val deviceContacts: List<DirectoryContact> = emptyList(),
    /** True when `READ_CONTACTS` has been granted. */
    val devicePermissionGranted: Boolean = false,
) {
    val isSearching: Boolean get() = query.trim().isNotEmpty()
    val totalAppContacts: Int get() = allAppContacts.size
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class ContactsViewModel(
    application: Application,
    private val userIdProvider: () -> String?,
    private val directoryRepository: ContactDirectoryRepository,
    private val deviceContactsProvider: DeviceContactsProvider,
) : AndroidViewModel(application) {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _permissionGranted = MutableStateFlow(deviceContactsProvider.hasPermission())
    private val _deviceContacts = MutableStateFlow<List<DirectoryContact>>(emptyList())

    private val appContactsFlow = run {
        val userId = userIdProvider()
        if (userId.isNullOrBlank()) flowOf(emptyList())
        else directoryRepository.observeAll(userId)
    }

    val state: StateFlow<ContactsUiState> = combine(
        _query,
        appContactsFlow,
        _deviceContacts,
        _permissionGranted,
    ) { q, appContacts, deviceHits, granted ->
        val trimmed = q.trim()
        val filtered = if (trimmed.isEmpty()) {
            appContacts
        } else {
            // Rank contacts whose *name* starts with the query
            // above contacts that merely contain it elsewhere. The
            // secondary key keeps each rank bucket alphabetical.
            appContacts
                .filter { it.matches(trimmed) }
                .sortedByMatchRank(trimmed)
        }
        ContactsUiState(
            query                    = q,
            isLoading                = false,
            allAppContacts           = appContacts,
            filteredAppContacts      = filtered,
            deviceContacts           = deviceHits,
            devicePermissionGranted  = granted,
        )
    }.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.WhileSubscribed(5_000),
        initialValue = ContactsUiState(),
    )

    private var deviceSearchJob: Job? = null

    fun updateQuery(value: String) {
        _query.value = value
        scheduleDeviceSearch(value)
    }

    fun clearQuery() {
        _query.value = ""
        deviceSearchJob?.cancel()
        _deviceContacts.value = emptyList()
    }

    /** Called after the permission request resolves. */
    fun onPermissionResult(granted: Boolean) {
        _permissionGranted.value = granted
        // Re-run the search so previously-empty device results
        // populate instantly after the user grants access.
        scheduleDeviceSearch(_query.value)
    }

    private fun scheduleDeviceSearch(rawQuery: String) {
        deviceSearchJob?.cancel()
        val q = rawQuery.trim()
        if (q.isEmpty() || !deviceContactsProvider.hasPermission()) {
            _deviceContacts.value = emptyList()
            return
        }
        deviceSearchJob = viewModelScope.launch {
            kotlinx.coroutines.delay(150)
            // Device search returns a LIKE-style hit list; reorder
            // so names starting with the query appear before
            // mid-string matches, mirroring the app-contact
            // behaviour above.
            _deviceContacts.value = deviceContactsProvider.search(q).sortedByMatchRank(q)
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as ReleafApp
                ContactsViewModel(
                    application             = app,
                    userIdProvider          = { (app.authStore.state.value as? app.releaf.mobile.auth.AuthState.SignedIn)?.session?.userId },
                    directoryRepository     = app.contactDirectoryRepository,
                    deviceContactsProvider  = app.deviceContactsProvider,
                )
            }
        }
    }
}

private fun DirectoryContact.matches(query: String): Boolean {
    return name.contains(query, ignoreCase = true) ||
        phones.any { it.contains(query, ignoreCase = true) } ||
        email?.contains(query, ignoreCase = true) == true ||
        organization?.contains(query, ignoreCase = true) == true
}

/**
 * Rank matching contacts so that names starting with the query
 * surface before names that only contain the query mid-string.
 * Each rank bucket is sorted alphabetically on the name.
 */
internal fun List<DirectoryContact>.sortedByMatchRank(
    query: String,
): List<DirectoryContact> {
    val lc = query.lowercase()
    return sortedWith(
        compareBy<DirectoryContact> { !it.name.lowercase().startsWith(lc) }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
    )
}
