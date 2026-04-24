/*
 * ShelfPageViewModel.kt
 *
 * Room-backed VM for the variant-1 page screen. Observes a single
 * PageEntity, parses its JSON side-channels (attachments, todos,
 * contacts, locations), and maps everything onto the domain `Page`
 * shape the variant UI already speaks.
 */

package app.releaf.mobile.features.page

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.releaf.mobile.ReleafApp
import androidx.lifecycle.viewModelScope
import app.releaf.mobile.data.domain.Contact
import app.releaf.mobile.data.domain.LocationPin
import app.releaf.mobile.data.domain.Note
import app.releaf.mobile.data.domain.Page
import app.releaf.mobile.data.domain.Photo
import app.releaf.mobile.data.domain.ScannedDocument
import app.releaf.mobile.data.notebook.Attachment
import app.releaf.mobile.data.notebook.PageEntity
import app.releaf.mobile.data.notebook.PageRepository
import app.releaf.mobile.data.notebook.parseAttachments
import app.releaf.mobile.data.notebook.parseContacts
import app.releaf.mobile.data.notebook.parseLocations
import app.releaf.mobile.data.notebook.parseTodos
import java.time.Instant
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import app.releaf.mobile.data.domain.TodoItem as DomainTodo

sealed interface ShelfPageUiState {
    data object Loading : ShelfPageUiState
    data class Loaded(val page: Page) : ShelfPageUiState
    data class Failed(val message: String) : ShelfPageUiState
}

class ShelfPageViewModel(
    application: Application,
    pageId: String,
    pageRepository: PageRepository,
) : AndroidViewModel(application) {

    val state: StateFlow<ShelfPageUiState> = pageRepository.observeById(pageId)
        .map { entity ->
            if (entity == null) ShelfPageUiState.Failed("Page not found")
            else                ShelfPageUiState.Loaded(entity.toDomain()) as ShelfPageUiState
        }
        .stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = ShelfPageUiState.Loading,
        )

    companion object {
        const val ARG_PAGE_ID = "pageId"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as ReleafApp
                val saved: SavedStateHandle = createSavedStateHandle()
                val pageId = checkNotNull(saved.get<String>(ARG_PAGE_ID)) {
                    "ShelfPageViewModel missing $ARG_PAGE_ID"
                }
                ShelfPageViewModel(
                    application    = app,
                    pageId         = pageId,
                    pageRepository = app.pageRepository,
                )
            }
        }
    }
}

// ---------- entity → domain mapping ----------

private fun PageEntity.toDomain(): Page {
    val attachments = runCatching { attachments.parseAttachments() }.getOrDefault(emptyList())
    val contactsList = runCatching { contacts.parseContacts() }.getOrDefault(emptyList())
    val locationsList = runCatching { locations.parseLocations() }.getOrDefault(emptyList())
    val todosList    = runCatching { todos.parseTodos() }.getOrDefault(emptyList())

    val photos = attachments
        .filter { it.type == Attachment.TYPE_PHOTO }
        .map(::toDomainPhoto)
    val scans = attachments
        .filter { it.type == Attachment.TYPE_SCAN }
        .map(::toDomainScan)

    // Notes come straight from the markdown body. Split on blank lines
    // so the variant page's ProseParagraph renders each block
    // separately. A quote-style paragraph prefixed with
    // "NOTE TO SELF" (blockquote in markdown) is lifted into the
    // pull-quote slot by the UI layer.
    val noteBlocks = notes.split(Regex("\\n\\s*\\n"))
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapIndexed { index, body ->
            Note(id = "$id-note-$index", body = body, createdAt = parseIso(updatedAt))
        }

    return Page(
        id               = id,
        notebookId       = "",  // Not observed here; the UI doesn't need it on this surface.
        chapterId        = chapterId,
        title            = title?.takeIf { it.isNotBlank() } ?: "Untitled page",
        capturedOn       = humanCapturedOn(createdAt),
        updatedAt        = parseIso(updatedAt),
        notes            = noteBlocks,
        photos           = photos,
        voiceNotes       = emptyList(),
        todoItems        = todosList.mapIndexed { idx, todo ->
            DomainTodo(id = todo.id, body = todo.text, done = todo.done, position = idx)
        },
        scannedDocuments = scans,
        contacts         = contactsList.map { nc ->
            Contact(
                id    = nc.id,
                name  = nc.name,
                phone = nc.phone,
                email = nc.email,
                notes = listOfNotNull(nc.title, nc.organization, nc.location)
                    .joinToString(" · ")
                    .ifEmpty { null },
            )
        },
        locations        = locationsList.map { loc ->
            LocationPin(
                id         = loc.id,
                name       = loc.address ?: "Pinned location",
                latitude   = loc.lat,
                longitude  = loc.lng,
                capturedAt = parseIso(loc.capturedAt),
                notes      = null,
            )
        },
        tags             = emptyList(),
    )
}

private fun toDomainPhoto(a: Attachment): Photo = Photo(
    id          = a.id,
    driveFileId = null,
    caption     = null,
    capturedAt  = parseIso(a.capturedAt),
    width       = null,
    height      = null,
)

private fun toDomainScan(a: Attachment): ScannedDocument = ScannedDocument(
    id          = a.id,
    driveFileId = null,
    title       = a.title ?: a.recognizedText?.lineSequence()?.firstOrNull()?.take(48) ?: "Scan",
    pageCount   = 1,
    scannedAt   = parseIso(a.capturedAt),
)

private fun parseIso(iso: String): Instant =
    runCatching { Instant.parse(iso) }.getOrDefault(Instant.EPOCH)

private fun humanCapturedOn(iso: String): String? {
    val instant = runCatching { Instant.parse(iso) }.getOrNull() ?: return null
    val fmt = java.time.format.DateTimeFormatter.ofPattern("EEE · MMM d · yyyy")
        .withZone(java.time.ZoneId.systemDefault())
    return fmt.format(instant)
}
